package io.affectedtests.core;

import io.affectedtests.core.config.Action;
import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.config.Situation;
import io.affectedtests.core.discovery.*;
import io.affectedtests.core.git.GitChangeDetector;
import io.affectedtests.core.mapping.PathToClassMapper;
import io.affectedtests.core.mapping.PathToClassMapper.MappingResult;
import io.affectedtests.core.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main orchestrator: detects changes, maps them to classes, discovers affected tests.
 *
 * <p>Pipeline (v2 situation-based):
 * <ol>
 *   <li>Detect changed files via JGit ({@code baseRef..HEAD} + uncommitted/staged)</li>
 *   <li>Map file paths into five mutually-exclusive buckets: ignored,
 *       out-of-scope, production, test, unmapped</li>
 *   <li>Pick the {@link Situation} for the diff (see evaluation order in the
 *       {@link Situation} javadoc). {@link Situation#EMPTY_DIFF} short-circuits
 *       before any discovery runs.</li>
 *   <li>For "ambiguous" situations (empty diff, all-ignored, all-out-of-scope,
 *       unmapped) the {@link Action} from {@link AffectedTestsConfig#actionFor}
 *       is applied directly — no discovery.</li>
 *   <li>Otherwise run all enabled discovery strategies (naming, usage, impl,
 *       transitive) and merge their results, then route through
 *       {@link Situation#DISCOVERY_EMPTY} or {@link Situation#DISCOVERY_SUCCESS}.</li>
 *   <li>Filter the union against test classes that actually exist on disk so
 *       deleted/renamed tests don't reach the downstream {@code test} task</li>
 * </ol>
 */
public final class AffectedTestsEngine {

    private static final Logger log = LoggerFactory.getLogger(AffectedTestsEngine.class);

    private final AffectedTestsConfig config;
    private final Path projectDir;

    public AffectedTestsEngine(AffectedTestsConfig config, Path projectDir) {
        this.config = config;
        this.projectDir = projectDir;
    }

    /**
     * Why a result flipped to {@code runAll = true}, or {@link #NONE} when no
     * escalation occurred. Derived one-for-one from the
     * {@link Situation}/{@link Action} pair the engine resolved, and
     * surfaced on the result record so Gradle-task log formatting and
     * {@code --explain} can describe the escalation without re-deriving
     * the mapping.
     */
    public enum EscalationReason {
        /** No escalation — either a filtered selection or a plain "nothing to do" result. */
        NONE,
        /**
         * Git produced an empty change set (no files differ between
         * {@code baseRef} and the working tree) and the action for
         * {@link Situation#EMPTY_DIFF} resolved to {@link Action#FULL_SUITE}.
         */
        RUN_ALL_ON_EMPTY_CHANGESET,
        /**
         * Discovery completed, returned an empty test set, and the action
         * for {@link Situation#DISCOVERY_EMPTY} resolved to
         * {@link Action#FULL_SUITE}.
         */
        RUN_ALL_IF_NO_MATCHES,
        /**
         * The change set contained at least one file the mapper could not
         * resolve to a Java class under the configured source/test
         * directories, and the action for {@link Situation#UNMAPPED_FILE}
         * resolved to {@link Action#FULL_SUITE}.
         */
        RUN_ALL_ON_NON_JAVA_CHANGE,
        /**
         * Every file in the diff matched {@link AffectedTestsConfig#ignorePaths()}
         * and the action for {@link Situation#ALL_FILES_IGNORED} resolved
         * to {@link Action#FULL_SUITE}.
         */
        RUN_ALL_ON_ALL_FILES_IGNORED,
        /**
         * Every file in the diff sat under
         * {@link AffectedTestsConfig#outOfScopeTestDirs()} or
         * {@link AffectedTestsConfig#outOfScopeSourceDirs()} and the
         * action for {@link Situation#ALL_FILES_OUT_OF_SCOPE} resolved to
         * {@link Action#FULL_SUITE}.
         */
        RUN_ALL_ON_ALL_FILES_OUT_OF_SCOPE,
        /**
         * One or more scanned Java files failed to parse (malformed
         * source, or a language level beyond {@link io.affectedtests.core.discovery.JavaLanguageParser#LANGUAGE_LEVEL}),
         * so discovery may have under-reported its selection. The action
         * for {@link Situation#DISCOVERY_INCOMPLETE} resolved to
         * {@link Action#FULL_SUITE}, typically via {@link io.affectedtests.core.config.Mode#CI}
         * or {@link io.affectedtests.core.config.Mode#STRICT} defaults.
         */
        RUN_ALL_ON_DISCOVERY_INCOMPLETE
    }

    /**
     * Per-bucket breakdown of the diff as classified by
     * {@link PathToClassMapper}. Populated on every
     * {@link AffectedTestsResult} (empty buckets when the engine
     * short-circuited before mapping, e.g. on {@link Situation#EMPTY_DIFF}).
     * Carried on the result so {@code --explain} and downstream log
     * lines can describe "why" without re-running the mapper.
     *
     * @param ignoredFiles     files matching {@link AffectedTestsConfig#ignorePaths()}
     * @param outOfScopeFiles  files under {@link AffectedTestsConfig#outOfScopeTestDirs()}
     *                         or {@link AffectedTestsConfig#outOfScopeSourceDirs()}
     * @param productionFiles  source files (extensions in
     *                         {@link io.affectedtests.core.discovery.SourceExtensions#EXTENSIONS} —
     *                         {@code .java} and {@code .kt} as of PR #1 of issue #76)
     *                         under a configured source dir
     * @param testFiles        source files (same extension set as
     *                         {@code productionFiles}) under a configured test dir
     * @param unmappedFiles    everything else (yaml, gradle, migrations, stray
     *                         {@code .java} / {@code .kt})
     */
    public record Buckets(
            Set<String> ignoredFiles,
            Set<String> outOfScopeFiles,
            Set<String> productionFiles,
            Set<String> testFiles,
            Set<String> unmappedFiles
    ) {
        public static Buckets empty() {
            return new Buckets(Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        }

        /** Total file count across every bucket — always equal to the size of the diff. */
        public int total() {
            return ignoredFiles.size() + outOfScopeFiles.size()
                    + productionFiles.size() + testFiles.size()
                    + unmappedFiles.size();
        }
    }

    /**
     * Result of the affected tests analysis.
     *
     * @param testClassFqns            FQNs of tests that should be executed
     *                                 (empty when {@link #runAll} or
     *                                 {@link #skipped} is true).
     * @param testFqnToPath            map of test FQN to its absolute file path on
     *                                 disk (used by callers to route invocations
     *                                 to the correct subproject). Empty when
     *                                 {@link #runAll} or {@link #skipped} is
     *                                 {@code true}.
     * @param changedFiles             raw changed file paths from git
     * @param changedProductionClasses production FQNs detected in the diff
     * @param changedTestClasses       test FQNs detected directly in the diff
     *                                 (may include FQNs whose files were deleted)
     * @param buckets                  per-bucket diff breakdown from the mapper;
     *                                 always present (empty buckets on an
     *                                 empty-diff short-circuit)
     * @param runAll                   whether the caller should run the full suite
     * @param skipped                  whether the caller should run no tests at all
     *                                 (v2 — previously impossible to express)
     * @param situation                which decision branch the engine landed on
     * @param action                   the resolved {@link Action} for
     *                                 {@link #situation}; one of SELECTED,
     *                                 FULL_SUITE, SKIPPED
     * @param escalationReason         reason code derived from the
     *                                 {@link #situation}/{@link #action}
     *                                 pair; see {@link EscalationReason}
     * @param namingCrossPackageMatches diagnostic map of changed
     *                                 production FQN -> set of test FQNs
     *                                 that {@link NamingConventionStrategy}
     *                                 selected via simple-name match
     *                                 even though the test lives in a
     *                                 different package. Empty when
     *                                 naming wasn't enabled, when no
     *                                 cross-package matches occurred, or
     *                                 when the engine short-circuited
     *                                 before discovery. Surfaced on
     *                                 {@code DISCOVERY_SUCCESS} runs by
     *                                 the {@code --explain} renderer so
     *                                 over-selection is visible without
     *                                 changing the (deliberately
     *                                 package-agnostic) selection
     *                                 policy.
     * @param discoveryProfile         per-strategy timing / test-count
     *                                 capture from issue #42, exposed
     *                                 via {@code --explain} so adopters
     *                                 can see whether parallel discovery
     *                                 helped on their workload and which
     *                                 strategy is the dominant
     *                                 wall-time consumer. Defaults to
     *                                 {@link DiscoveryProfile#empty()}
     *                                 on engine paths that don't run
     *                                 discovery (test-only fast path,
     *                                 EMPTY_DIFF, etc.).
     * @param kotlinDiagnostics        per-engine carrier for the four
     *                                 Kotlin AST signals plumbed into
     *                                 {@code --explain}: AST-mapped
     *                                 FQN samples, parse-failure count,
     *                                 path-vs-package mismatch samples,
     *                                 and embeddable-bootstrap-failure
     *                                 cause. Issue #76 PR #4. Defaults
     *                                 to {@link KotlinDiagnostics#EMPTY}
     *                                 when the engine ran without
     *                                 Kotlin participation (Kotlin flag
     *                                 off, fast paths, etc.) so the
     *                                 renderer can poll without a null
     *                                 branch.
     * @param headerEdgesAugmentation  issue&nbsp;#132 — header-edges
     *                                 augmentation result feeding the
     *                                 {@code --explain} text and JSON
     *                                 renderers. Always non-null; the
     *                                 {@link HeaderEdgesStrategy.AugmentationResult#identity}
     *                                 sentinel covers every short-circuit
     *                                 path (test-only fast path,
     *                                 EMPTY_DIFF, headerEdges disabled,
     *                                 etc.) so the renderer never has
     *                                 to null-check the augmentation
     *                                 field.
     */
    public record AffectedTestsResult(
            Set<String> testClassFqns,
            Map<String, Path> testFqnToPath,
            Set<String> changedFiles,
            Set<String> changedProductionClasses,
            Set<String> changedTestClasses,
            Buckets buckets,
            boolean runAll,
            boolean skipped,
            Situation situation,
            Action action,
            EscalationReason escalationReason,
            Map<String, Set<String>> namingCrossPackageMatches,
            DiscoveryProfile discoveryProfile,
            KotlinDiagnostics kotlinDiagnostics,
            HeaderEdgesStrategy.AugmentationResult headerEdgesAugmentation
    ) {
        public AffectedTestsResult {
            namingCrossPackageMatches = namingCrossPackageMatches == null
                    ? Map.of()
                    : Collections.unmodifiableMap(namingCrossPackageMatches);
            discoveryProfile = discoveryProfile == null
                    ? DiscoveryProfile.empty()
                    : discoveryProfile;
            kotlinDiagnostics = kotlinDiagnostics == null
                    ? KotlinDiagnostics.EMPTY
                    : kotlinDiagnostics;
            headerEdgesAugmentation = headerEdgesAugmentation == null
                    ? HeaderEdgesStrategy.AugmentationResult.identity(
                            changedProductionClasses == null
                                    ? Set.of()
                                    : changedProductionClasses)
                    : headerEdgesAugmentation;
        }

        /**
         * Backwards-compatible 14-arg constructor — preserves the
         * pre-issue-#132 record shape (Kotlin diagnostics, no
         * header-edges augmentation). Defaults
         * {@code headerEdgesAugmentation} to the identity sentinel.
         */
        public AffectedTestsResult(
                Set<String> testClassFqns,
                Map<String, Path> testFqnToPath,
                Set<String> changedFiles,
                Set<String> changedProductionClasses,
                Set<String> changedTestClasses,
                Buckets buckets,
                boolean runAll,
                boolean skipped,
                Situation situation,
                Action action,
                EscalationReason escalationReason,
                Map<String, Set<String>> namingCrossPackageMatches,
                DiscoveryProfile discoveryProfile,
                KotlinDiagnostics kotlinDiagnostics
        ) {
            this(testClassFqns, testFqnToPath, changedFiles,
                    changedProductionClasses, changedTestClasses,
                    buckets, runAll, skipped, situation, action,
                    escalationReason, namingCrossPackageMatches,
                    discoveryProfile, kotlinDiagnostics,
                    HeaderEdgesStrategy.AugmentationResult.identity(
                            changedProductionClasses == null
                                    ? Set.of()
                                    : changedProductionClasses));
        }

        /**
         * Backwards-compatible 13-arg constructor — preserves the
         * pre-issue-#76-PR-4 record shape. Defaults
         * {@code kotlinDiagnostics} to {@link KotlinDiagnostics#EMPTY},
         * matching every engine path that did not run Kotlin AST
         * participation.
         */
        public AffectedTestsResult(
                Set<String> testClassFqns,
                Map<String, Path> testFqnToPath,
                Set<String> changedFiles,
                Set<String> changedProductionClasses,
                Set<String> changedTestClasses,
                Buckets buckets,
                boolean runAll,
                boolean skipped,
                Situation situation,
                Action action,
                EscalationReason escalationReason,
                Map<String, Set<String>> namingCrossPackageMatches,
                DiscoveryProfile discoveryProfile
        ) {
            this(testClassFqns, testFqnToPath, changedFiles,
                    changedProductionClasses, changedTestClasses,
                    buckets, runAll, skipped, situation, action,
                    escalationReason, namingCrossPackageMatches,
                    discoveryProfile, KotlinDiagnostics.EMPTY);
        }

        /**
         * Backwards-compatible 12-arg constructor — preserves the
         * pre-issue-#42 record shape every test fixture and
         * downstream caller was written against. Defaults the
         * {@code discoveryProfile} field to {@link DiscoveryProfile#empty()},
         * matching what every engine path that didn't actually run
         * discovery produces.
         */
        public AffectedTestsResult(
                Set<String> testClassFqns,
                Map<String, Path> testFqnToPath,
                Set<String> changedFiles,
                Set<String> changedProductionClasses,
                Set<String> changedTestClasses,
                Buckets buckets,
                boolean runAll,
                boolean skipped,
                Situation situation,
                Action action,
                EscalationReason escalationReason,
                Map<String, Set<String>> namingCrossPackageMatches
        ) {
            this(testClassFqns, testFqnToPath, changedFiles,
                    changedProductionClasses, changedTestClasses,
                    buckets, runAll, skipped, situation, action,
                    escalationReason, namingCrossPackageMatches,
                    DiscoveryProfile.empty(), KotlinDiagnostics.EMPTY,
                    HeaderEdgesStrategy.AugmentationResult.identity(
                            changedProductionClasses == null
                                    ? Set.of()
                                    : changedProductionClasses));
        }

        /**
         * Backwards-compatible 11-arg constructor — preserves the
         * pre-issue-#40 record shape. Defaults
         * {@code namingCrossPackageMatches} to {@link Map#of()},
         * {@code discoveryProfile} to {@link DiscoveryProfile#empty()},
         * and {@code kotlinDiagnostics} to {@link KotlinDiagnostics#EMPTY}.
         */
        public AffectedTestsResult(
                Set<String> testClassFqns,
                Map<String, Path> testFqnToPath,
                Set<String> changedFiles,
                Set<String> changedProductionClasses,
                Set<String> changedTestClasses,
                Buckets buckets,
                boolean runAll,
                boolean skipped,
                Situation situation,
                Action action,
                EscalationReason escalationReason
        ) {
            this(testClassFqns, testFqnToPath, changedFiles,
                    changedProductionClasses, changedTestClasses,
                    buckets, runAll, skipped, situation, action,
                    escalationReason, Map.of(),
                    DiscoveryProfile.empty(), KotlinDiagnostics.EMPTY,
                    HeaderEdgesStrategy.AugmentationResult.identity(
                            changedProductionClasses == null
                                    ? Set.of()
                                    : changedProductionClasses));
        }
    }

    /**
     * Runs the full pipeline: detect changes, map to classes, pick a
     * situation, resolve it to an action, and (where relevant) discover
     * the affected test set.
     */
    public AffectedTestsResult run() {
        log.info("=== Affected Tests Analysis ===");
        log.info("Project dir: {}", projectDir);
        log.info("Base ref: {}", config.baseRef());
        log.info("Strategies: {}", config.strategies());
        log.info("Transitive depth: {}", config.transitiveDepth());
        log.info("Effective mode: {}", config.effectiveMode());

        GitChangeDetector changeDetector = new GitChangeDetector(projectDir, config);
        Set<String> changedFiles = changeDetector.detectChangedFiles();

        if (changedFiles.isEmpty()) {
            log.info("No changed files detected.");
            return resolveAmbiguous(Situation.EMPTY_DIFF, changedFiles,
                    Set.of(), Set.of(), Buckets.empty());
        }

        PathToClassMapper mapper = new PathToClassMapper(config);
        MappingResult mapping = mapper.mapChangedFiles(changedFiles);

        Buckets buckets = new Buckets(
                Set.copyOf(mapping.ignoredFiles()),
                Set.copyOf(mapping.outOfScopeFiles()),
                Set.copyOf(mapping.changedProductionFiles()),
                Set.copyOf(mapping.changedTestFiles()),
                Set.copyOf(mapping.unmappedChangedFiles())
        );

        int diffSize = changedFiles.size();
        int ignored = mapping.ignoredFiles().size();
        int outOfScope = mapping.outOfScopeFiles().size();

        // Priority matches the Situation javadoc. Remember that the mapper
        // already routes each file into at most one bucket, so the "all X"
        // branches and the "any unmapped" branch are mutually exclusive by
        // construction. The order here just picks the situation name that
        // matches the diff's shape.
        if (ignored == diffSize) {
            log.info("All {} changed file(s) matched ignorePaths.", diffSize);
            return resolveAmbiguous(Situation.ALL_FILES_IGNORED, changedFiles,
                    mapping.productionClasses(), mapping.testClasses(), buckets);
        }
        if (outOfScope == diffSize) {
            log.info("All {} changed file(s) fell under out-of-scope dirs.", diffSize);
            return resolveAmbiguous(Situation.ALL_FILES_OUT_OF_SCOPE, changedFiles,
                    mapping.productionClasses(), mapping.testClasses(), buckets);
        }
        // Mixed "nothing actionable" case: every file is either ignored
        // or out-of-scope, but no single bucket owns the whole diff. The
        // two pure-bucket checks above already short-circuited if any
        // unmapped / production / test file existed, so reaching here
        // with both ignored > 0 AND outOfScope > 0 means the diff
        // contains exactly those two categories and nothing else.
        //
        // Pre-fix, this slid through to discovery with empty
        // {@code productionClasses} and {@code testClasses}, every
        // strategy returned empty, and the engine escalated via
        // {@link Situation#DISCOVERY_EMPTY} — which under the CI
        // default routed to {@code FULL_SUITE}. The result: a pure
        // "docs + api-test" MR ran the whole unit suite despite the
        // user having told the plugin "these dirs don't influence
        // tests". Routing to {@link Situation#ALL_FILES_OUT_OF_SCOPE}
        // instead lets {@code onAllFilesOutOfScope} (the stronger
        // operator signal when both signals disagree) decide, which
        // defaults to {@link Action#SKIPPED} in every built-in mode.
        if (ignored + outOfScope == diffSize) {
            log.info("All {} changed file(s) split between ignored ({}) and out-of-scope ({}); "
                            + "routing to ALL_FILES_OUT_OF_SCOPE.",
                    diffSize, ignored, outOfScope);
            return resolveAmbiguous(Situation.ALL_FILES_OUT_OF_SCOPE, changedFiles,
                    mapping.productionClasses(), mapping.testClasses(), buckets);
        }
        if (!mapping.unmappedChangedFiles().isEmpty()) {
            Action action = config.actionFor(Situation.UNMAPPED_FILE);
            // Filenames flow from the untrusted MR tree straight into the
            // logger here — sanitise or an attacker committing a file
            // with `\n` + fake plugin status line can forge CI audit
            // output. See LogSanitizer for the full rationale.
            List<String> examples = mapping.unmappedChangedFiles().stream()
                    .limit(5)
                    .map(LogSanitizer::sanitize)
                    .toList();
            log.warn("Non-Java / unmapped change detected ({} file(s)). Action: {}. Examples: {}",
                    mapping.unmappedChangedFiles().size(), action, examples);
            // SELECTED here means "ignore the unmapped file, proceed with
            // discovery on whatever production/test files were in the
            // diff" — the opt-out for operators who don't want
            // non-Java changes to escalate to a full suite.
            if (action != Action.SELECTED) {
                return emptyResult(Situation.UNMAPPED_FILE, action, changedFiles,
                        mapping.productionClasses(), mapping.testClasses(), buckets);
            }
        }

        Set<String> candidateTests = new LinkedHashSet<>();
        candidateTests.addAll(mapping.testClasses());
        log.info("Directly changed test classes: {}", mapping.testClasses().size());

        Set<String> productionClasses = mapping.productionClasses();

        // Fast path for test-only diffs: if the diff has zero production
        // classes, every discovery strategy would early-return empty (each
        // strategy's first check is `if (changedProductionClasses.isEmpty())
        // return Set.of()`), so the only candidate tests are the
        // directly-changed ones in `mapping.testClasses()`. Building the
        // ProjectIndex would then walk every source dir, every test dir,
        // and every test FQN-resolution candidate without ever consulting
        // any of the resulting maps — pure waste. Two costs the fast path
        // avoids on a 10k-class harness:
        //
        //   * SourceFileScanner.collectSourceFiles + collectTestFiles
        //     (two full file-tree walks of source/test dirs).
        //   * SourceFileScanner.scanTestFqnsWithFiles + the per-source-dir
        //     fqnsUnder loop (a third + fourth walk to build the FQN
        //     dispatch map and the source-FQN disambiguation set).
        //
        // The on-disk filter that the index normally provides is
        // recreated here directly via Files.exists on the diff's own
        // changedTestFiles paths — bounded by the diff size, not the
        // workspace size.
        if (productionClasses.isEmpty()) {
            return runTestOnlyFastPath(changedFiles, mapping, buckets, candidateTests);
        }

        NamingConventionStrategy namingStrategy = new NamingConventionStrategy(config);
        UsageStrategy usageStrategy = new UsageStrategy(config);
        ImplementationStrategy implStrategy = new ImplementationStrategy(config, namingStrategy, usageStrategy);
        TransitiveStrategy transitiveStrategy = new TransitiveStrategy(config, namingStrategy, usageStrategy);
        HeaderEdgesStrategy headerEdgesStrategy = new HeaderEdgesStrategy(config);

        // try-with-resources on the {@link ProjectIndex}: when the
        // Kotlin AST gate is on (issue #76 PR #3+), the index owns a
        // {@code KotlinCoreEnvironment} parent {@link
        // com.intellij.openapi.Disposable Disposable} that must be
        // disposed at engine shutdown to avoid leaking ~25 MB of
        // pinned MockApplication state on the Gradle-daemon
        // classloader for the daemon's lifetime. With the gate off
        // (default during the rollout window) the close is a no-op
        // — the registry is Java-only and Java parsers are
        // process-wide thread-locals — so wrapping unconditionally
        // costs nothing but means a future flag flip does not
        // require touching this site.
        try (ProjectIndex index = ProjectIndex.build(projectDir, config)) {

        // Issue #132 — header-edges augmentation runs BEFORE the four
        // existing strategies so each downstream strategy sees the
        // union of the diff and the header-edge-derived FQNs. The
        // strategy short-circuits to the identity when {@code
        // headerEdgesEnabled = false} or {@code STRATEGY_HEADER_EDGES}
        // is not in the configured strategies list, so the cost of
        // running this call site unconditionally is bounded to a
        // single map allocation for adopters who opted out.
        HeaderEdgesStrategy.AugmentationResult headerEdgesAugmentation;
        if (config.strategies().contains(AffectedTestsConfig.STRATEGY_HEADER_EDGES)) {
            headerEdgesAugmentation = headerEdgesStrategy.augment(productionClasses, index);
        } else {
            headerEdgesAugmentation =
                    HeaderEdgesStrategy.AugmentationResult.identity(productionClasses);
        }
        Set<String> augmentedProductionClasses = headerEdgesAugmentation.augmentedTypes();
        Set<String> suppressedFromImplWalk = headerEdgesAugmentation.suppressedFromImplWalk();
        Set<String> implInputClasses = suppressedFromImplWalk.isEmpty()
                ? augmentedProductionClasses
                : minus(augmentedProductionClasses, suppressedFromImplWalk);

        // Issue #42: build the list of (name, callable) work items the
        // engine will dispatch; running them serially below or via a
        // small thread pool above is a uniform decision rather than a
        // five-way `if` ladder (naming / usage / impl / transitive +
        // the issue-#132 headerEdges sibling-cap-aware impl input).
        // The per-strategy capture into {@link DiscoveryProfile} lives
        // here so the parallel path and the serial path produce the
        // same shape of diagnostic.
        List<DiscoveryWorkItem> workItems = new ArrayList<>(4);
        if (config.strategies().contains(AffectedTestsConfig.STRATEGY_NAMING)) {
            workItems.add(new DiscoveryWorkItem(AffectedTestsConfig.STRATEGY_NAMING,
                    () -> namingStrategy.discoverTests(augmentedProductionClasses, index)));
        }
        if (config.strategies().contains(AffectedTestsConfig.STRATEGY_USAGE)) {
            workItems.add(new DiscoveryWorkItem(AffectedTestsConfig.STRATEGY_USAGE,
                    () -> usageStrategy.discoverTests(augmentedProductionClasses, index)));
        }
        if (config.strategies().contains(AffectedTestsConfig.STRATEGY_IMPL)) {
            workItems.add(new DiscoveryWorkItem(AffectedTestsConfig.STRATEGY_IMPL,
                    () -> implStrategy.discoverTests(implInputClasses, index)));
        }
        if (config.strategies().contains(AffectedTestsConfig.STRATEGY_TRANSITIVE)
                && config.transitiveDepth() > 0) {
            workItems.add(new DiscoveryWorkItem(AffectedTestsConfig.STRATEGY_TRANSITIVE,
                    () -> transitiveStrategy.discoverTests(augmentedProductionClasses, index)));
        }
        DiscoveryProfile profile = runDiscovery(workItems, candidateTests, config);

        // Issue #41 stage 2: re-persist the snapshot now that the
        // strategies have populated the per-file metadata cache. On a
        // cache-miss build this turns the previously-empty Stage 2
        // block into a fully-populated one; on a cache-hit-then-
        // -refresh build this writes any newly-extracted entries back
        // (files whose fingerprint drifted since the previous run).
        // Honours the same system-property kill switch as the
        // build-time persist, and silently skips a no-op write when
        // the index has no scanned-dir context (defensive — every
        // current code path produces an index with non-null dirs).
        ProjectIndexCache.persist(projectDir, config, index);

        // C2 guard: keep only FQNs whose source file still exists on disk.
        // Deleted/renamed tests (their old FQN) must not be passed to Gradle's
        // --tests flag or it will fail the whole build with "No tests found".
        Map<String, Path> knownTests = index.testFqnToPath();
        Set<String> allTestsToRun = new LinkedHashSet<>();
        Map<String, Path> fqnToPath = new LinkedHashMap<>();
        for (String fqn : candidateTests) {
            Path file = knownTests.get(fqn);
            if (file != null) {
                allTestsToRun.add(fqn);
                fqnToPath.put(fqn, file);
            } else {
                // The FQN reached this log site via the discovery
                // strategies, which derive names from diff paths and
                // from the scanned source tree — both attacker-
                // influenced on a merge-gate run.
                log.debug("Skipping FQN with no matching test file on disk: {}",
                        LogSanitizer.sanitize(fqn));
            }
        }

        // Parse failures during index / strategy walks mean the Usage /
        // Implementation / Transitive tiers may have silently dropped
        // tests that actually do reference the changed production code.
        // Surface that as its own situation so callers can pick a policy
        // (escalate to FULL_SUITE on CI, keep the partial selection on
        // LOCAL) instead of routing through DISCOVERY_EMPTY /
        // DISCOVERY_SUCCESS as if the selection were trustworthy. The
        // count is de-duplicated inside ProjectIndex so a single
        // unparseable file is not multi-counted across strategies.
        int parseFailures = index.parseFailureCount();
        if (parseFailures > 0) {
            Action action = config.actionFor(Situation.DISCOVERY_INCOMPLETE);
            log.warn("Affected Tests: discovery observed {} unparseable Java file(s); "
                            + "selection may be incomplete. Action: {}. See WARN lines above "
                            + "for the specific files that failed to parse.",
                    parseFailures, action);
            if (action == Action.FULL_SUITE || action == Action.SKIPPED) {
                return emptyResult(Situation.DISCOVERY_INCOMPLETE, action, changedFiles,
                        mapping.productionClasses(), mapping.testClasses(), buckets,
                        index.kotlinDiagnostics(), headerEdgesAugmentation);
            }
            // SELECTED: honour the situation but fall through to the
            // shared empty/selected tail below. The tail now picks the
            // returned situation based on parseFailures so both the
            // happy path and this one share one log line ({@code "==="
            // Result: N affected test classes (SITUATION)}) and one
            // record construction, instead of two near-identical copies
            // that will drift.
        }

        Situation finalSituation = parseFailures > 0
                ? Situation.DISCOVERY_INCOMPLETE
                : Situation.DISCOVERY_SUCCESS;

        if (allTestsToRun.isEmpty()) {
            // Shared empty tail. For parseFailures == 0 we report
            // DISCOVERY_EMPTY (the pre-v1.9.22 branch); for
            // parseFailures > 0 + SELECTED we report DISCOVERY_INCOMPLETE
            // so --explain, the skip-reason phrase, and the CI log
            // grep all see a single truthful situation.
            Situation emptySituation = parseFailures > 0
                    ? Situation.DISCOVERY_INCOMPLETE
                    : Situation.DISCOVERY_EMPTY;
            Action action = config.actionFor(emptySituation);
            log.info("Discovery produced no affected tests. Situation: {}, Action: {}.",
                    emptySituation, action);
            return emptyResult(emptySituation, action, changedFiles,
                    mapping.productionClasses(), mapping.testClasses(), buckets,
                    index.kotlinDiagnostics(), headerEdgesAugmentation);
        }

        log.info("=== Result: {} affected test classes ({}) ===",
                allTestsToRun.size(), finalSituation);
        // FQNs are derived from diff filenames by the discovery strategies
        // and have not yet been through the AffectedTestTask#isValidFqn
        // gate; sanitise here so an attacker-planted filename like
        // `Test\nAffected Tests: SELECTED.java` can't forge a fake
        // log line at INFO/--info level.
        allTestsToRun.forEach(t -> log.info("  -> {}", LogSanitizer.sanitize(t)));

        // Trim the diagnostic map to FQNs that actually survived the
        // on-disk filter: a cross-package match for a test that was
        // deleted in the same diff (or never existed) is noise that
        // would point operators at FQNs they can't open.
        Map<String, Set<String>> survivingCrossPackage =
                filterCrossPackageMatchesToSurvivors(
                        namingStrategy.crossPackageMatches(), allTestsToRun);

        return new AffectedTestsResult(
                allTestsToRun,
                Collections.unmodifiableMap(fqnToPath),
                changedFiles,
                mapping.productionClasses(),
                mapping.testClasses(),
                buckets,
                false,
                false,
                finalSituation,
                Action.SELECTED,
                EscalationReason.NONE,
                survivingCrossPackage,
                profile,
                index.kotlinDiagnostics(),
                headerEdgesAugmentation
        );
        }
    }

    /**
     * Test-only diff fast path: every discovery strategy would early-return
     * on an empty production-class set, so the only candidates are the
     * directly-changed tests in {@code mapping.testClasses()}. We still
     * have to filter out FQNs whose source file is gone (a deleted test
     * class FQN must not reach Gradle's {@code --tests} flag, or the
     * outer build fails with "No tests found"), but that filter only
     * needs to look at the diff's own {@link MappingResult#changedTestFiles()}
     * entries — not at the entire workspace.
     *
     * <p>Iterates the relative paths from the mapping result, derives each
     * one's FQN via {@link SourceFileScanner#pathToFqn(Path, List)} (the
     * same string-only logic the diff-time mapper uses, no file I/O), and
     * keeps only the entries whose absolute path resolves to a file that
     * still exists on disk. Returns the same {@link AffectedTestsResult}
     * shape the strategy-driven code path returns, with
     * {@link Situation#DISCOVERY_SUCCESS} when at least one test survives
     * the filter and {@link Situation#DISCOVERY_EMPTY} otherwise.
     */
    private AffectedTestsResult runTestOnlyFastPath(Set<String> changedFiles,
                                                    MappingResult mapping,
                                                    Buckets buckets,
                                                    Set<String> candidateTests) {
        log.info("[fast-path] Test-only diff: skipping ProjectIndex build (no production classes in diff)");

        Set<String> survivingTests = new LinkedHashSet<>();
        Map<String, Path> fqnToPath = new LinkedHashMap<>();
        for (String relativeTestPath : mapping.changedTestFiles()) {
            Path absolute = projectDir.resolve(relativeTestPath).toAbsolutePath();
            if (!Files.exists(absolute)) {
                // Deleted test in the diff — its FQN is in
                // mapping.testClasses() but the file is gone. Drop it
                // for the same reason the index-based filter does:
                // Gradle's --tests flag would fail the outer build with
                // "No tests found for given includes".
                log.debug("[fast-path] Skipping deleted test file: {}",
                        LogSanitizer.sanitize(relativeTestPath));
                continue;
            }
            String fqn = SourceFileScanner.pathToFqn(absolute, config.testDirs());
            if (fqn == null) {
                // Defensive: every entry in changedTestFiles was placed
                // there because tryMapToClass derived an FQN from its
                // relative path, so re-derivation against the absolute
                // form should always succeed. Treat a null here as an
                // implementation drift between the two derivers and
                // log at debug — there's no operator-actionable signal
                // since the file existed.
                log.debug("[fast-path] Test path on disk but FQN un-derivable, skipping: {}",
                        LogSanitizer.sanitize(relativeTestPath));
                continue;
            }
            if (!candidateTests.contains(fqn)) {
                // Defensive: same reasoning as above. The candidate set
                // was seeded from mapping.testClasses() in lock-step with
                // mapping.changedTestFiles(), so the two should always
                // agree on FQNs.
                continue;
            }
            survivingTests.add(fqn);
            fqnToPath.put(fqn, absolute);
        }

        if (survivingTests.isEmpty()) {
            // Either the diff had only deleted test files, or the FQN
            // re-derivation produced no matches (defensive branch). Same
            // outcome either way: route through DISCOVERY_EMPTY so the
            // configured action picks the policy.
            Action action = config.actionFor(Situation.DISCOVERY_EMPTY);
            log.info("[fast-path] No surviving test classes after on-disk filter. "
                    + "Situation: {}, Action: {}.", Situation.DISCOVERY_EMPTY, action);
            return emptyResult(Situation.DISCOVERY_EMPTY, action, changedFiles,
                    mapping.productionClasses(), mapping.testClasses(), buckets);
        }

        log.info("=== Result: {} affected test classes ({}) — fast path ===",
                survivingTests.size(), Situation.DISCOVERY_SUCCESS);
        survivingTests.forEach(t -> log.info("  -> {}", LogSanitizer.sanitize(t)));

        return new AffectedTestsResult(
                survivingTests,
                Collections.unmodifiableMap(fqnToPath),
                changedFiles,
                mapping.productionClasses(),
                mapping.testClasses(),
                buckets,
                false,
                false,
                Situation.DISCOVERY_SUCCESS,
                Action.SELECTED,
                EscalationReason.NONE
        );
    }

    /**
     * Resolves a situation that short-circuits discovery into an empty
     * result with the appropriate {@code runAll}/{@code skipped} flags.
     * Used by every branch except {@link Situation#DISCOVERY_SUCCESS} and
     * {@link Situation#UNMAPPED_FILE}-with-{@link Action#SELECTED}.
     *
     * <p>When {@link Situation#UNMAPPED_FILE} resolves to
     * {@link Action#SELECTED} the engine deliberately does <em>not</em>
     * route through here — it continues into discovery so the diff's
     * Java files still get analysed.
     */
    private AffectedTestsResult resolveAmbiguous(Situation situation,
                                                 Set<String> changedFiles,
                                                 Set<String> changedProduction,
                                                 Set<String> changedTests,
                                                 Buckets buckets) {
        Action action = config.actionFor(situation);
        if (action == Action.SELECTED) {
            // The only meaningful way for SELECTED to reach here is
            // someone explicitly configured {@code onEmptyDiff(SELECTED)}
            // or similar. In every "ambiguous" branch there is no
            // selection to run by definition, so SELECTED collapses to
            // "do nothing, don't claim a full run".
            log.info("Situation {} → SELECTED with empty selection; running no tests.", situation);
        }
        return emptyResult(situation, action, changedFiles, changedProduction, changedTests, buckets);
    }

    private AffectedTestsResult emptyResult(Situation situation, Action action,
                                            Set<String> changedFiles,
                                            Set<String> changedProduction,
                                            Set<String> changedTests,
                                            Buckets buckets) {
        return emptyResult(situation, action, changedFiles,
                changedProduction, changedTests, buckets,
                KotlinDiagnostics.EMPTY, null);
    }

    /**
     * Overload threaded through the engine's {@code try (ProjectIndex
     * index = ...)} block so the Kotlin diagnostics captured during
     * discovery survive into {@code DISCOVERY_INCOMPLETE} /
     * {@code DISCOVERY_EMPTY} returns. Without this overload the
     * pre-existing 11-arg {@link AffectedTestsResult} constructor
     * defaults the diagnostics to {@link KotlinDiagnostics#EMPTY}
     * and the four pinned --explain strings would silently drop on
     * every parse-failure / empty-discovery run — exactly the
     * situations adopters most need them on. The
     * outside-the-index callers (EMPTY_DIFF, ALL_FILES_IGNORED,
     * etc.) keep the no-arg overload and pass {@link
     * KotlinDiagnostics#EMPTY} because no parser ran in those
     * branches.
     *
     * <p>The {@code headerEdgesAugmentation} parameter carries the
     * issue-#132 augmentation — non-null on the parse-failure /
     * discovery-empty branches that already ran the augmentation
     * step, null on every outside-the-index short-circuit (in
     * which case the {@link AffectedTestsResult} record defaults
     * it to the identity sentinel).
     */
    private AffectedTestsResult emptyResult(Situation situation, Action action,
                                            Set<String> changedFiles,
                                            Set<String> changedProduction,
                                            Set<String> changedTests,
                                            Buckets buckets,
                                            KotlinDiagnostics kotlinDiagnostics,
                                            HeaderEdgesStrategy.AugmentationResult headerEdgesAugmentation) {
        boolean runAll = action == Action.FULL_SUITE;
        // SELECTED on an ambiguous branch is treated as "skipped" for the
        // Gradle task's wiring — there is nothing to dispatch either way —
        // but the {@link Action} field on the result still reads SELECTED
        // so {@code --explain} can report honestly.
        boolean skipped = action == Action.SKIPPED
                || (action == Action.SELECTED && situation != Situation.DISCOVERY_SUCCESS);
        return new AffectedTestsResult(
                Set.of(),
                Map.of(),
                changedFiles,
                changedProduction,
                changedTests,
                buckets,
                runAll,
                skipped,
                situation,
                action,
                escalationReason(situation, action),
                Map.of(),
                DiscoveryProfile.empty(),
                kotlinDiagnostics == null ? KotlinDiagnostics.EMPTY : kotlinDiagnostics,
                headerEdgesAugmentation
        );
    }

    /**
     * Drops cross-package naming matches whose test FQN didn't survive
     * the on-disk filter so the {@code --explain} hint never points
     * operators at a deleted/missing test. Also prunes entries whose
     * surviving set is empty (every cross-package match for that
     * changed FQN was filtered) — the renderer treats an entry's
     * presence as "this changed FQN over-selected" so an empty set
     * would render misleadingly. Returns an empty map when nothing
     * survives, matching the {@code Map.of()} default in the no-naming
     * / no-discovery short-circuits.
     *
     * <p>Sorts the outer keys and inner sets alphabetically so the
     * {@code --explain} hint and JSON output stay deterministic
     * across runs. Issue #42 — under parallel discovery, multiple
     * threads can populate the underlying {@link
     * java.util.concurrent.ConcurrentHashMap} (in
     * {@link io.affectedtests.core.discovery.NamingConventionStrategy})
     * in races, so the raw iteration order varies between
     * runs even on identical inputs. We pin a deterministic order
     * here at the filter boundary rather than inside the strategy
     * because the strategy is hot-path and the filter is cold-path
     * (called once per engine run, post-discovery).
     */
    private static Map<String, Set<String>> filterCrossPackageMatchesToSurvivors(
            Map<String, Set<String>> raw, Set<String> survivingTests) {
        if (raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> filtered = new TreeMap<>();
        for (var entry : raw.entrySet()) {
            Set<String> survivingForKey = new TreeSet<>();
            for (String testFqn : entry.getValue()) {
                if (survivingTests.contains(testFqn)) {
                    survivingForKey.add(testFqn);
                }
            }
            if (!survivingForKey.isEmpty()) {
                filtered.put(entry.getKey(), survivingForKey);
            }
        }
        return filtered;
    }

    /**
     * Set difference preserving insertion order — used by the engine
     * to compute the {@code impl} strategy's input as the augmented
     * set minus the issue-#132 sibling-cap-suppressed types. Pulled
     * out of the call site so the intent reads cleanly and the
     * {@link LinkedHashSet} contract stays uniform across every
     * derived set in the engine.
     */
    private static Set<String> minus(Set<String> source, Set<String> exclude) {
        Set<String> out = new LinkedHashSet<>(source.size());
        for (String fqn : source) {
            if (!exclude.contains(fqn)) out.add(fqn);
        }
        return out;
    }

    private static EscalationReason escalationReason(Situation situation, Action action) {
        if (action != Action.FULL_SUITE) return EscalationReason.NONE;
        return switch (situation) {
            case EMPTY_DIFF -> EscalationReason.RUN_ALL_ON_EMPTY_CHANGESET;
            case DISCOVERY_EMPTY -> EscalationReason.RUN_ALL_IF_NO_MATCHES;
            case DISCOVERY_INCOMPLETE -> EscalationReason.RUN_ALL_ON_DISCOVERY_INCOMPLETE;
            case UNMAPPED_FILE -> EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE;
            case ALL_FILES_IGNORED -> EscalationReason.RUN_ALL_ON_ALL_FILES_IGNORED;
            case ALL_FILES_OUT_OF_SCOPE -> EscalationReason.RUN_ALL_ON_ALL_FILES_OUT_OF_SCOPE;
            case DISCOVERY_SUCCESS -> EscalationReason.NONE;
        };
    }

    // ── Issue #42: parallel discovery dispatch ─────────────────────────────

    /**
     * One unit of discovery work: a strategy name (used as the diagnostic
     * key in {@link DiscoveryProfile}) plus the callable that produces
     * the strategy's test-FQN result. Bundled as a record so {@link
     * #runDiscovery} can iterate the same shape regardless of whether
     * dispatch happens serially or via a thread pool.
     */
    /**
     * Package-private so {@code AffectedTestsEngineErrorPropagationTest}
     * can drive {@link #runDiscovery} with synthetic work items that
     * throw deterministic exceptions, pinning the
     * "parallel and serial unwrap the same way" contract without
     * needing to corrupt a real project layout.
     */
    record DiscoveryWorkItem(String name, Callable<Set<String>> work) {}

    /**
     * Daemon factory for the discovery pool. Threads are named
     * {@code affected-tests-discovery-N} so a {@code jstack} against a
     * stuck Gradle daemon can attribute a parked thread back to a
     * concrete strategy slot.
     */
    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r,
                    "affected-tests-discovery-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }

    /**
     * Runs the supplied {@link DiscoveryWorkItem}s, accumulates results
     * into {@code candidateTests}, and returns a {@link DiscoveryProfile}
     * describing how dispatch actually executed (parallel vs serial,
     * concurrency level, per-strategy timings, per-strategy contribution
     * counts).
     *
     * <p>The parallel path uses a fixed {@link ExecutorService} sized to
     * {@code min(workItems.size(), availableProcessors)} with daemon
     * threads (so a misbehaving strategy can't keep the JVM alive past
     * the engine run). The pool is gated on
     * {@code availableProcessors() > 1} — on a single-vCPU host
     * (cgroups-pinned containers, GitHub Actions free runners) the
     * pool collapses to "serial via an executor", which is strictly
     * slower than the in-line serial path. When there is only one
     * work item, or {@link AffectedTestsConfig#parallelDiscovery()} is
     * false, we skip the pool entirely — no point paying executor
     * spin-up overhead to run a single callable.
     *
     * <p>Result merge order is intentionally NOT the dispatch order
     * — the candidate set is a {@link LinkedHashSet} which keeps
     * INSERTION order, but the discovery union is an unordered
     * concept; downstream consumers (the on-disk filter, the
     * dispatch loop, the {@code --explain} renderer) re-order
     * deterministically before any user-visible output. The
     * determinism contract is "same inputs produce the same SET",
     * not "same inputs produce the same iteration order".
     *
     * <p>Note for adopters running Gradle's {@code --parallel} flag
     * across multiple modules: each module spins up its own discovery
     * pool. On an 8-vCPU runner with 4 modules in parallel that's
     * potentially 16 discovery threads; the per-thread JavaParser
     * footprint is small but the contention on the CU cache may
     * reduce per-module speedup.
     */
    static DiscoveryProfile runDiscovery(List<DiscoveryWorkItem> workItems,
                                         Set<String> candidateTests,
                                         AffectedTestsConfig config) {
        if (workItems.isEmpty()) {
            return DiscoveryProfile.empty();
        }
        // Gate parallel on:
        //  - flag enabled,
        //  - more than one work item (single-callable parallelism is
        //    strictly slower than in-line serial — pool spin-up is
        //    pure overhead), and
        //  - more than one vCPU (a 1-thread pool runs the same work
        //    serially, plus pool overhead).
        boolean runParallel = config.parallelDiscovery()
                && workItems.size() > 1
                && Runtime.getRuntime().availableProcessors() > 1;
        long t0 = System.nanoTime();
        Map<String, Duration> perStrategyWall = new LinkedHashMap<>();
        Map<String, Integer> perStrategyTestCount = new LinkedHashMap<>();
        // Per-strategy result containers. We hold them outside the
        // executor block so the merge into candidateTests stays
        // single-threaded (LinkedHashSet is not thread-safe; the merge
        // happens AFTER all parallel work completes).
        Map<String, Set<String>> perStrategyResult = new LinkedHashMap<>();

        if (runParallel) {
            int concurrencyLevel = Math.min(workItems.size(), Runtime.getRuntime().availableProcessors());
            ExecutorService pool = Executors.newFixedThreadPool(
                    concurrencyLevel, new DaemonThreadFactory());
            try {
                Map<String, Future<TimedResult>> futures = new LinkedHashMap<>();
                for (DiscoveryWorkItem item : workItems) {
                    futures.put(item.name(), pool.submit(() -> {
                        long start = System.nanoTime();
                        Set<String> result = item.work().call();
                        return new TimedResult(result, System.nanoTime() - start);
                    }));
                }
                // Drain every future, collecting results AND any
                // exceptions. We don't fail-fast on the first error
                // because a second concurrent failure (e.g. two
                // strategies hitting a shared corruption) would
                // otherwise stay invisible until the operator fixes
                // #1 and re-runs. Suppress exceptions onto the first
                // one so the stack trace surfaces every failure.
                RuntimeException firstFailure = null;
                for (Map.Entry<String, Future<TimedResult>> e : futures.entrySet()) {
                    try {
                        TimedResult tr = e.getValue().get();
                        perStrategyResult.put(e.getKey(), tr.result());
                        perStrategyWall.put(e.getKey(), Duration.ofNanos(tr.elapsedNanos()));
                        perStrategyTestCount.put(e.getKey(), tr.result().size());
                    } catch (ExecutionException ex) {
                        // Unwrap to surface the original exception
                        // exactly as the serial path would have — hiding
                        // a strategy crash behind ExecutionException
                        // would make adopter debugging materially
                        // harder ("affectedTest failed with j.u.c.EE").
                        // Tag with the strategy name so parallel and
                        // serial produce the same message shape.
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        RuntimeException wrapped = (cause instanceof RuntimeException re)
                                ? re
                                : new RuntimeException("Strategy " + e.getKey() + " threw", cause);
                        if (cause instanceof Error er) {
                            // Errors aren't recoverable; surface immediately.
                            throw er;
                        }
                        if (firstFailure == null) {
                            firstFailure = wrapped;
                        } else {
                            firstFailure.addSuppressed(wrapped);
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Discovery interrupted", ie);
                    }
                }
                if (firstFailure != null) {
                    throw firstFailure;
                }
            } finally {
                // shutdown() rather than shutdownNow(): every future
                // has already been .get()'d in the loop above on the
                // happy path, so there is no in-flight work to cancel.
                // On the failure path we've drained every future too
                // (the suppressed-exception accumulator runs the loop
                // to completion). shutdown() expresses intent
                // accurately; shutdownNow() reads as "I have
                // outstanding work to interrupt" which we don't.
                pool.shutdown();
            }
            for (Set<String> r : perStrategyResult.values()) {
                candidateTests.addAll(r);
            }
            Duration total = Duration.ofNanos(System.nanoTime() - t0);
            log.info("[discovery] parallel ({} threads) completed in {} ms; per-strategy: {}",
                    concurrencyLevel, total.toMillis(), perStrategyWall);
            return new DiscoveryProfile(true, concurrencyLevel, total,
                    perStrategyWall, perStrategyTestCount);
        }

        // Serial fallback: keeps the single-strategy and disabled-flag
        // paths cheap, and gives us a baseline to A/B against in
        // perf tests (toggle config.parallelDiscovery and the same
        // engine code runs each path).
        for (DiscoveryWorkItem item : workItems) {
            long start = System.nanoTime();
            Set<String> result;
            try {
                result = item.work().call();
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception ex) {
                throw new RuntimeException("Strategy " + item.name() + " threw", ex);
            }
            perStrategyResult.put(item.name(), result);
            perStrategyWall.put(item.name(), Duration.ofNanos(System.nanoTime() - start));
            perStrategyTestCount.put(item.name(), result.size());
            candidateTests.addAll(result);
        }
        Duration total = Duration.ofNanos(System.nanoTime() - t0);
        log.info("[discovery] serial completed in {} ms; per-strategy: {}",
                total.toMillis(), perStrategyWall);
        return new DiscoveryProfile(false, 0, total, perStrategyWall, perStrategyTestCount);
    }

    /** Internal carrier for "strategy result + how long it took to compute". */
    private record TimedResult(Set<String> result, long elapsedNanos) {}
}
