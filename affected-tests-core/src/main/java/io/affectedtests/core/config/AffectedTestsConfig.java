package io.affectedtests.core.config;

import io.affectedtests.core.util.LogSanitizer;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Configuration for affected test detection.
 * Immutable value object — use the {@link Builder} to construct.
 *
 * <h2>v2 situation-based config</h2>
 * Every engine run produces exactly one {@link Situation}; each situation
 * resolves to one {@link Action} ({@link Action#SELECTED},
 * {@link Action#FULL_SUITE}, or {@link Action#SKIPPED}) via the priority
 * ladder in {@link #actionFor(Situation)}:
 *
 * <ol>
 *   <li>the caller's explicit {@code onXxx(Action)} setting, if any;</li>
 *   <li>otherwise the per-{@link Mode} default
 *       (CI / LOCAL / STRICT — AUTO auto-detects from the environment).</li>
 * </ol>
 *
 * <p>v1's three legacy knobs ({@code runAllIfNoMatches},
 * {@code runAllOnNonJavaChange}, {@code excludePaths}) were removed in
 * v2.0. Callers upgrading from v1 should map them to the v2 DSL per the
 * "Migrating from v1" section of the plugin README.
 */
public final class AffectedTestsConfig {

    /** The built-in discovery strategy names. */
    public static final String STRATEGY_NAMING = "naming";
    public static final String STRATEGY_USAGE = "usage";
    public static final String STRATEGY_IMPL = "impl";
    public static final String STRATEGY_TRANSITIVE = "transitive";
    /**
     * Issue&nbsp;#132 — header-edges discovery strategy. Walks
     * class-header type references (extends, implements, permits,
     * generic bounds, record components, class-level annotations)
     * from a changed concrete type up to its supertypes and out
     * to its header-declared types, then runs the resulting
     * augmented changed-class set through the existing four
     * strategies. Closes the dominant Spring DI gap where a single
     * impl change otherwise misses the interface's consumer tests.
     */
    public static final String STRATEGY_HEADER_EDGES = "headerEdges";

    /**
     * Canonical category names for the {@code headerEdgesExclude}
     * opt-out list. Adopters disable a category by listing its
     * canonical name (case-insensitive); unknown entries are
     * rejected at the builder gate so a typo doesn't silently
     * disable nothing.
     */
    public static final String HEADER_EDGE_EXTENDS = "extends";
    public static final String HEADER_EDGE_IMPLEMENTS = "implements";
    public static final String HEADER_EDGE_PERMITS = "permits";
    public static final String HEADER_EDGE_TYPE_BOUNDS = "type-bounds";
    public static final String HEADER_EDGE_RECORD_COMPONENTS = "record-components";
    public static final String HEADER_EDGE_ANNOTATIONS = "annotations";

    /**
     * The complete set of valid {@code headerEdgesExclude} entries.
     * Builder validates against this so misspellings ({@code
     * "implments"}, {@code "type-bound"}) fail loudly at config
     * time rather than silently disabling no category.
     */
    private static final Set<String> VALID_HEADER_EDGE_CATEGORIES = Set.of(
            HEADER_EDGE_EXTENDS, HEADER_EDGE_IMPLEMENTS, HEADER_EDGE_PERMITS,
            HEADER_EDGE_TYPE_BOUNDS, HEADER_EDGE_RECORD_COMPONENTS,
            HEADER_EDGE_ANNOTATIONS);

    private final String baseRef;
    private final boolean includeUncommitted;
    private final boolean includeStaged;
    private final Set<String> strategies;
    private final int transitiveDepth;
    private final List<String> testSuffixes;
    private final List<String> sourceDirs;
    private final List<String> testDirs;
    private final List<String> testTaskNames;
    private final List<String> ignorePaths;
    private final List<String> outOfScopeTestDirs;
    private final List<String> outOfScopeSourceDirs;
    private final boolean includeImplementationTests;
    private final List<String> implementationNaming;
    private final long gradlewTimeoutSeconds;
    private final Mode mode;
    private final Mode effectiveMode;
    private final Map<Situation, Action> situationActions;
    private final Map<Situation, ActionSource> situationActionSources;
    private final boolean parallelDiscovery;
    private final boolean kotlinEnabled;
    private final boolean headerEdgesEnabled;
    private final Set<String> headerEdgesExclude;
    private final int headerEdgesDepth;
    private final int headerEdgesMaxSiblings;
    private final List<String> headerEdgesIgnore;

    private AffectedTestsConfig(Builder builder) {
        this.baseRef = builder.baseRef;
        this.includeUncommitted = builder.includeUncommitted;
        this.includeStaged = builder.includeStaged;
        this.strategies = Set.copyOf(builder.strategies);
        this.transitiveDepth = builder.transitiveDepth;
        this.testSuffixes = List.copyOf(builder.testSuffixes);
        this.sourceDirs = List.copyOf(builder.sourceDirs);
        this.testDirs = List.copyOf(builder.testDirs);
        this.testTaskNames = List.copyOf(builder.testTaskNames);
        this.includeImplementationTests = builder.includeImplementationTests;
        this.implementationNaming = List.copyOf(builder.implementationNaming);
        // Zero = no timeout, preserving the pre-v1.9.22 "wait forever"
        // behaviour for zero-config callers. Negative values are
        // rejected at the builder gate below; see
        // {@link Builder#gradlewTimeoutSeconds(long)}.
        this.gradlewTimeoutSeconds = builder.gradlewTimeoutSeconds;

        this.ignorePaths = builder.ignorePaths != null
                ? List.copyOf(builder.ignorePaths)
                : List.copyOf(Builder.DEFAULT_IGNORE_PATHS);

        this.outOfScopeTestDirs = List.copyOf(
                builder.outOfScopeTestDirs != null ? builder.outOfScopeTestDirs : List.of());
        this.outOfScopeSourceDirs = List.copyOf(
                builder.outOfScopeSourceDirs != null ? builder.outOfScopeSourceDirs : List.of());

        this.mode = builder.mode != null ? builder.mode : Mode.AUTO;
        this.effectiveMode = resolveEffectiveMode(this.mode);

        ResolvedActions resolved = resolveSituationActions(builder, this.effectiveMode);
        this.situationActions = resolved.actions;
        this.situationActionSources = resolved.sources;
        this.parallelDiscovery = builder.parallelDiscovery;
        this.kotlinEnabled = builder.kotlinEnabled;
        this.headerEdgesEnabled = builder.headerEdgesEnabled;
        this.headerEdgesExclude = Set.copyOf(builder.headerEdgesExclude);
        this.headerEdgesDepth = builder.headerEdgesDepth;
        this.headerEdgesMaxSiblings = builder.headerEdgesMaxSiblings;
        this.headerEdgesIgnore = List.copyOf(builder.headerEdgesIgnore);
    }

    /** Parallel pair returned from the situation-action resolver. */
    private record ResolvedActions(
            Map<Situation, Action> actions,
            Map<Situation, ActionSource> sources) {
    }

    /**
     * Resolves the configured {@link Mode} to the concrete profile whose
     * defaults will be applied. {@link Mode#AUTO} probes the environment
     * via {@link Builder#detectMode()}; every other mode passes through
     * verbatim. Always returns one of {@link Mode#LOCAL},
     * {@link Mode#CI}, or {@link Mode#STRICT} — never {@code null},
     * never {@link Mode#AUTO}.
     */
    private static Mode resolveEffectiveMode(Mode configured) {
        if (configured == Mode.AUTO) return Builder.detectMode();
        return configured;
    }

    /**
     * Per-situation actions in strict priority order:
     * <ol>
     *   <li>the caller's explicit {@code on*} action,</li>
     *   <li>the per-mode default table (see {@link #defaultFor}).</li>
     * </ol>
     *
     * <p>{@link Situation#DISCOVERY_SUCCESS} is definitionally
     * {@link Action#SELECTED} — there is no other sensible outcome when
     * discovery returns tests, so the code fixes it rather than routing
     * it through the resolver.
     */
    private static ResolvedActions resolveSituationActions(Builder b, Mode effectiveMode) {
        EnumMap<Situation, Action> actions = new EnumMap<>(Situation.class);
        EnumMap<Situation, ActionSource> sources = new EnumMap<>(Situation.class);
        resolveInto(actions, sources, Situation.EMPTY_DIFF, b.onEmptyDiff, effectiveMode);
        resolveInto(actions, sources, Situation.ALL_FILES_IGNORED, b.onAllFilesIgnored, effectiveMode);
        resolveInto(actions, sources, Situation.ALL_FILES_OUT_OF_SCOPE, b.onAllFilesOutOfScope, effectiveMode);
        resolveInto(actions, sources, Situation.UNMAPPED_FILE, b.onUnmappedFile, effectiveMode);
        resolveInto(actions, sources, Situation.DISCOVERY_EMPTY, b.onDiscoveryEmpty, effectiveMode);
        resolveInto(actions, sources, Situation.DISCOVERY_INCOMPLETE, b.onDiscoveryIncomplete, effectiveMode);
        actions.put(Situation.DISCOVERY_SUCCESS, Action.SELECTED);
        sources.put(Situation.DISCOVERY_SUCCESS, ActionSource.EXPLICIT);
        return new ResolvedActions(Map.copyOf(actions), Map.copyOf(sources));
    }

    private static void resolveInto(EnumMap<Situation, Action> actions,
                                    EnumMap<Situation, ActionSource> sources,
                                    Situation s,
                                    Action explicit,
                                    Mode effectiveMode) {
        if (explicit != null) {
            actions.put(s, explicit);
            sources.put(s, ActionSource.EXPLICIT);
            return;
        }
        actions.put(s, defaultFor(s, effectiveMode));
        sources.put(s, ActionSource.MODE_DEFAULT);
    }

    /**
     * Per-mode defaults (see {@link Mode} javadoc for the full table).
     */
    private static Action defaultFor(Situation s, Mode effectiveMode) {
        return switch (effectiveMode) {
            case LOCAL -> switch (s) {
                case EMPTY_DIFF, ALL_FILES_IGNORED, ALL_FILES_OUT_OF_SCOPE, DISCOVERY_EMPTY -> Action.SKIPPED;
                case UNMAPPED_FILE -> Action.FULL_SUITE;
                // LOCAL is the dev-iteration profile: surfacing the WARN
                // at parse time is enough feedback, and forcing a full
                // suite on every parse hiccup would make the wrapper
                // unusable while mid-edit. The dev can re-run after
                // fixing the offending file.
                case DISCOVERY_INCOMPLETE, DISCOVERY_SUCCESS -> Action.SELECTED;
            };
            case CI -> switch (s) {
                case EMPTY_DIFF, ALL_FILES_IGNORED, ALL_FILES_OUT_OF_SCOPE -> Action.SKIPPED;
                // Parse failures on a CI merge-gate are the exact situation
                // the safety net exists for: we cannot prove coverage, so
                // default to running everything. Operators who know their
                // trees have transient parse noise can override with
                // {@code onDiscoveryIncomplete = "selected"}.
                case UNMAPPED_FILE, DISCOVERY_EMPTY, DISCOVERY_INCOMPLETE -> Action.FULL_SUITE;
                case DISCOVERY_SUCCESS -> Action.SELECTED;
            };
            case STRICT -> switch (s) {
                case ALL_FILES_OUT_OF_SCOPE -> Action.SKIPPED;
                case EMPTY_DIFF, ALL_FILES_IGNORED, UNMAPPED_FILE, DISCOVERY_EMPTY,
                     DISCOVERY_INCOMPLETE -> Action.FULL_SUITE;
                case DISCOVERY_SUCCESS -> Action.SELECTED;
            };
            case AUTO -> throw new IllegalStateException(
                    "AUTO must be resolved to LOCAL or CI before calling defaultFor");
        };
    }

    public String baseRef() { return baseRef; }
    public boolean includeUncommitted() { return includeUncommitted; }
    public boolean includeStaged() { return includeStaged; }
    public Set<String> strategies() { return strategies; }
    public int transitiveDepth() { return transitiveDepth; }

    /**
     * Whether the engine fans the four discovery strategies out across
     * a small thread pool ({@code true}, default) or runs them serially
     * in declaration order ({@code false}). Issue #42's Stage 1 win:
     * on workloads where transitive dominates wall time, overlapping
     * naming + usage + impl + transitive halves discovery time on a
     * 4-vCPU runner with no algorithmic change.
     *
     * <p>Disable via DSL ({@code parallelDiscovery = false}) or the
     * Gradle property {@code -PaffectedTestsParallelDiscovery=false}
     * if a workload triggers a JavaParser-thread-safety regression
     * we have not seen yet — the serial path remains a one-line
     * fallback and is what the existing functional tests exercise.
     */
    public boolean parallelDiscovery() { return parallelDiscovery; }

    /**
     * Whether the Kotlin AST-driven parser is registered for {@code .kt}
     * files (issue #76 Phase 2). Defaults to {@code true} as of PR #4 —
     * Kotlin AST participation is now first-class. The pre-PR-4 system
     * property ({@code -Daffected-tests.kotlin.enabled}) was removed
     * with the default flip; the DSL flag
     * ({@code affectedTests { kotlinEnabled = false }}) is the
     * documented escape hatch for adopters who hit a regression.
     *
     * <p>When {@code true} (default), {@code KotlinLanguageParser} is
     * registered with the per-engine {@code LanguageParsers} registry;
     * AST-driven strategies (Usage / Implementation / Transitive) start
     * producing matches for Kotlin files; embeddable bootstrap
     * failures fail closed via the {@link Situation#DISCOVERY_INCOMPLETE}
     * escalation path (the WARN is logged once and every {@code .kt}
     * in the run is treated as unparseable).
     *
     * <p>When {@code false} (escape hatch), {@code .kt} files keep the
     * Phase 1 (PR #1) shape: path-derived FQN routing on the diff side,
     * no AST-driven strategy participation. {@code DISCOVERY_INCOMPLETE}
     * does not trigger for unparsed Kotlin in this mode — the absence is
     * "by design" under the escape hatch, not a parse failure.
     *
     * <p>The flag value participates in
     * {@link io.affectedtests.core.discovery.ProjectIndexCache#configHash(AffectedTestsConfig)}
     * so a flip across consecutive runs forces a full rescan rather
     * than reusing a half-Kotlin-shaped cache.
     *
     * @return whether Kotlin AST parsing is enabled for this run
     */
    public boolean kotlinEnabled() { return kotlinEnabled; }

    /**
     * Issue&nbsp;#132 — whether the {@code headerEdges} discovery
     * strategy participates in this run. Defaults to {@code true}.
     * The strategy walks from each changed concrete production
     * class up to its supertypes (extends, implements, permits)
     * and out to its header-declared types (generic bounds, record
     * components, class-level annotations), adding the resulting
     * types to the changed-class set that the four existing
     * strategies (naming, usage, impl, transitive) consume.
     *
     * <p>One-flag kill switch — {@code false} disables every
     * category at once and reverts plugin behaviour to byte-for-byte
     * identical to pre-issue-#132. Adopters who want per-category
     * opt-out use {@link #headerEdgesExclude()} instead so the rest
     * of the strategy keeps firing.
     *
     * <p>The flag itself doesn't gate strategy registration — the
     * strategy is wired unconditionally into the engine and
     * short-circuits to "augmented set = original set, zero
     * diagnostic edges" when this is {@code false}, so the
     * cache-hash impact (knob participates in
     * {@link io.affectedtests.core.discovery.ProjectIndexCache#configHash})
     * is bounded to "off vs on" and not "every category combination".
     */
    public boolean headerEdgesEnabled() { return headerEdgesEnabled; }

    /**
     * Issue&nbsp;#132 — canonical category names whose header-edge
     * contributions are skipped during augmentation. Adopters
     * exclude (not include) — every category is on by default; the
     * exclude list is the surgical opt-out. Valid entries are the
     * {@code HEADER_EDGE_*} constants on this class; unknown
     * entries are rejected at the builder gate.
     *
     * <p>Common opt-out shapes:
     * <ul>
     *   <li>{@code ["annotations"]} — adopter uses many custom
     *       class-level annotations that don't carry test-relevant
     *       behaviour. Most frequent opt-out.</li>
     *   <li>{@code ["record-components"]} — DTO-heavy codebases
     *       where record components are pure data carriers.</li>
     * </ul>
     *
     * <p>Defaults to empty (every category contributes). When this
     * set contains an entry, the strategy still records the edge in
     * the {@code --explain} side-channel with
     * {@code status = IGNORED_BY_CATEGORY} so adopters can tell
     * what the opt-out cost them.
     */
    public Set<String> headerEdgesExclude() { return headerEdgesExclude; }

    /**
     * Issue&nbsp;#132 — how many header-edge hops the strategy
     * walks from each changed class. {@code 1} (default) is the
     * recommended setting: walk the immediate header-declared types
     * only. {@code 2} walks one more level (the supertypes' own
     * supertypes), and is clamped at the upper bound — higher
     * values rapidly approach {@code FULL_SUITE} selection on
     * real codebases because almost every concrete class transitively
     * reaches {@code Object}'s consumer base.
     *
     * <p>Clamped to {@code [0, 2]} at the builder gate. Zero is
     * a degenerate setting — the augmented set equals the
     * original set — and is treated as identical to
     * {@code headerEdgesEnabled = false}.
     */
    public int headerEdgesDepth() { return headerEdgesDepth; }

    /**
     * Issue&nbsp;#132 — sibling-cap on the explosion vector. When
     * a header-edge-added type has more direct subtypes than this
     * value, the {@code impl} strategy's downward walk from THAT
     * added type is suppressed; the added type still contributes
     * via naming / usage / transitive.
     *
     * <p>Motivating case: {@code PaymentController extends
     * BaseController}, with {@code BaseController} owning 52
     * subclasses. Without the cap, walking {@code extends} would
     * add {@code BaseController} to the changed set and the impl
     * strategy would then walk DOWN through all 52 subtypes,
     * selecting every {@code *ControllerTest} in the codebase.
     * The cap is the surgical fix: {@code BaseController} still
     * adds {@code BaseControllerTest} via naming, but the 52-way
     * fan-out is skipped.
     *
     * <p>Default: {@code 5}. Adopters with deep inheritance
     * hierarchies (test bases, abstract DAO bases) typically keep
     * this default; the {@code --explain} JSON surfaces every
     * skipped fan-out so it's an evidence-driven tune.
     *
     * <p>Set to {@code 0} to suppress every downward walk from a
     * header-edge-added type — useful in adopters whose hierarchies
     * are universally fan-out-heavy. Setting it negative is
     * rejected at the builder gate.
     */
    public int headerEdgesMaxSiblings() { return headerEdgesMaxSiblings; }

    /**
     * Issue&nbsp;#132 — globs (Java-style {@code **}-prefixed FQN
     * patterns) for types whose header-edge contributions are
     * ignored. The default list covers framework / standard-library
     * types that pollute selection without contributing signal:
     * {@code java.lang.**}, {@code java.util.**},
     * {@code org.springframework.**}, {@code org.junit.**},
     * {@code lombok.**}, {@code kotlin.**}, {@code groovy.lang.**},
     * and the {@code javax} / {@code jakarta} equivalents.
     *
     * <p>Globs match against the simple name's resolved FQN — so
     * {@code @Service class FooService} only ignores the
     * {@code Service} annotation if the strategy resolves it to
     * {@code org.springframework.stereotype.Service}; an
     * adopter-defined {@code @Service} under
     * {@code com.example.Service} would still contribute.
     *
     * <p>Adopters typically extend the default rather than replace
     * it — the Gradle DSL wiring uses the builder default whenever
     * the user-set list is null.
     */
    public List<String> headerEdgesIgnore() { return headerEdgesIgnore; }

    public List<String> testSuffixes() { return testSuffixes; }
    public List<String> sourceDirs() { return sourceDirs; }
    public List<String> testDirs() { return testDirs; }
    /**
     * Names of the Gradle test tasks the dispatch path may invoke
     * (one nested {@code ./gradlew} call per {@code module × taskName}
     * pair). Default: {@code ["test"]} — the only Gradle convention
     * task name that exists on a fresh Java plugin install.
     *
     * <p>Adopters with extra source sets ({@code integrationTest},
     * {@code e2eTest}, etc.) opt those tasks in by listing them here:
     * {@code testTaskNames = ["test", "integrationTest"]}. The
     * dispatch path then routes each discovered FQN to the task whose
     * source-set directory the FQN's file sits under, by the standard
     * Gradle convention {@code src/<taskName>/java}. FQNs that do
     * not match any listed task fall back to the first entry — the
     * same conservative posture the rest of the plugin takes when an
     * input shape is ambiguous.
     */
    public List<String> testTaskNames() { return testTaskNames; }

    /**
     * Hard wall-clock timeout applied to the nested {@code ./gradlew}
     * invocation that runs the affected / full test suite. Zero means
     * "no timeout" (the pre-v1.9.22 default — wait for the child to
     * finish no matter how long it takes); any positive value is the
     * deadline in seconds, after which the Gradle task destroys the
     * child process tree and fails with a clear error.
     *
     * <p>Motivating class of bug: CI workers pinned for hours on a
     * hung JVM or a stuck test — usually a deadlocked custom test
     * harness, an exhausted Docker fixture, or a JDK agent that
     * mis-responds to {@code SIGTERM}. Without a timeout the plugin
     * has no way to surface the stall, so the only feedback is a
     * pipeline that times out at the CI runner level with no mapping
     * back to which test got stuck.
     *
     * <p>Recommended values: {@code 1800} (30 min) for merge-gate
     * unit-test runs, {@code 3600} (1 hour) for suites that include
     * integration tests, {@code 0} for local runs where the operator
     * wants to attach a debugger and has infinite patience. Must be
     * {@code >= 0}; negative values are rejected at build time.
     *
     * @return the wall-clock timeout in seconds, or {@code 0} when
     *         disabled
     */
    public long gradlewTimeoutSeconds() { return gradlewTimeoutSeconds; }

    /**
     * Glob patterns for files that must not influence test selection at all.
     * A diff consisting entirely of ignored paths routes through
     * {@link Situation#ALL_FILES_IGNORED}.
     *
     * @return the ignore paths list
     */
    public List<String> ignorePaths() { return ignorePaths; }

    /**
     * Test source directories (e.g. {@code api-test/src/test/java}) that the
     * plugin must not resolve as in-scope tests. A diff consisting entirely
     * of files under these directories routes through
     * {@link Situation#ALL_FILES_OUT_OF_SCOPE}. Intended for test source sets
     * the user does not want the {@code affectedTest} task to dispatch
     * (Cucumber/api-test, performance tests, etc.).
     *
     * @return the out-of-scope test directories
     */
    public List<String> outOfScopeTestDirs() { return outOfScopeTestDirs; }

    /**
     * Production source directories the plugin must not consider as in-scope
     * sources during mapping and discovery. A diff entirely under these
     * directories also routes through {@link Situation#ALL_FILES_OUT_OF_SCOPE}.
     *
     * @return the out-of-scope source directories
     */
    public List<String> outOfScopeSourceDirs() { return outOfScopeSourceDirs; }
    public boolean includeImplementationTests() { return includeImplementationTests; }
    public List<String> implementationNaming() { return implementationNaming; }

    /**
     * The configured {@link Mode} — the raw value as set by the caller.
     * May be {@link Mode#AUTO}. Use {@link #effectiveMode()} to read the
     * already-resolved mode.
     *
     * @return the configured mode (may be AUTO)
     */
    public Mode mode() { return mode; }

    /**
     * The mode after {@link Mode#AUTO} resolution. Always returns one of
     * {@link Mode#LOCAL}, {@link Mode#CI} or {@link Mode#STRICT} — never
     * {@code null}, never {@link Mode#AUTO}.
     *
     * @return the resolved mode
     */
    public Mode effectiveMode() { return effectiveMode; }

    /**
     * The {@link Action} the engine will take for a given {@link Situation}.
     * Produced by layering the explicit caller-set action (highest priority)
     * over the {@link Mode} default.
     *
     * @param situation the situation to resolve
     * @return the configured action for {@code situation}
     */
    public Action actionFor(Situation situation) {
        return Objects.requireNonNull(situationActions.get(situation), "no action for " + situation);
    }

    /**
     * View of the full per-situation action map. Useful for diagnostic
     * output like {@code --explain}; engine code should prefer
     * {@link #actionFor(Situation)}.
     *
     * @return an immutable situation-to-action map
     */
    public Map<Situation, Action> situationActions() { return situationActions; }

    /**
     * The {@link ActionSource} that picked the {@link Action} for a given
     * {@link Situation}. Used by {@code --explain} so operators can tell
     * whether an outcome came from an explicit setting or a mode default.
     *
     * @param situation the situation to resolve
     * @return the source tier that produced {@link #actionFor(Situation)}
     */
    public ActionSource actionSourceFor(Situation situation) {
        return Objects.requireNonNull(situationActionSources.get(situation),
                "no action source for " + situation);
    }

    /**
     * View of the per-situation {@link ActionSource} map. Kept immutable
     * and aligned with {@link #situationActions()} so consumers can zip
     * the two for diagnostic output.
     *
     * @return an immutable situation-to-source map
     */
    public Map<Situation, ActionSource> situationActionSources() { return situationActionSources; }

    /** Creates a builder with sensible defaults. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        /**
         * Default list of paths that must never influence test selection.
         * Broader than the pre-v2 default ({@code ["**}{@code /generated/**"]})
         * so markdown-only PRs don't sneak past ignore rules on zero-config
         * installs.
         */
        static final List<String> DEFAULT_IGNORE_PATHS = List.of(
                // Each "extension" category is listed twice: once for the
                // root-level form ({@code *.md}) and once for the nested
                // form ({@code **}{@code /*.md}). Java's glob PathMatcher
                // does NOT treat {@code **}{@code /} as optional — the
                // root-level forms are genuinely required or a pure
                // "README.md" diff silently falls through to the unmapped
                // bucket and triggers the full-suite safety net on
                // zero-config installs.
                "**/generated/**",
                "*.md", "**/*.md",
                "*.txt", "**/*.txt",
                "LICENSE", "**/LICENSE",
                "LICENSE.*", "**/LICENSE.*",
                "CHANGELOG*", "**/CHANGELOG*",
                "*.png", "**/*.png",
                "*.jpg", "**/*.jpg",
                "*.jpeg", "**/*.jpeg",
                "*.svg", "**/*.svg",
                "*.gif", "**/*.gif"
        );

        /**
         * Issue&nbsp;#132 — default ignore globs for the headerEdges
         * strategy. Every entry is a FQN-glob (Java-style
         * {@code **}-suffixed) matched against the RESOLVED FQN of
         * each candidate header-edge target. The list covers the
         * framework / standard-library types that historically
         * polluted selection without contributing signal:
         * <ul>
         *   <li>JDK ({@code java.**}, {@code javax.**},
         *       {@code jakarta.**})</li>
         *   <li>Spring ({@code org.springframework.**}) — its
         *       stereotypes ({@code @Service}, {@code @Component})
         *       and base classes are pure framework glue, not the
         *       adopter's behaviour.</li>
         *   <li>JUnit / Mockito ({@code org.junit.**},
         *       {@code org.mockito.**}) — adopters writing tests
         *       against the test framework itself are vanishingly
         *       rare.</li>
         *   <li>Lombok ({@code lombok.**}) — annotation-driven
         *       code generation; the generated members participate
         *       through their concrete shapes, not through Lombok's
         *       own marker annotations.</li>
         *   <li>Kotlin / Groovy stdlib ({@code kotlin.**},
         *       {@code groovy.lang.**}) — same JDK-equivalent
         *       reasoning.</li>
         * </ul>
         *
         * <p>Adopters EXTEND (not replace) — the setter merges the
         * user-supplied list with this default by default. Adopters
         * who genuinely want to OVERRIDE pass their own list to
         * {@code headerEdgesIgnore(...)} explicitly; that path
         * surfaces the full picture in {@code --explain}.
         */
        static final List<String> DEFAULT_HEADER_EDGES_IGNORE = List.of(
                "java.lang.**",
                "java.util.**",
                "java.io.**",
                "java.time.**",
                "java.math.**",
                "java.nio.**",
                "java.text.**",
                "javax.**",
                "jakarta.**",
                "org.springframework.**",
                "org.springframework.boot.**",
                "org.springframework.data.**",
                "org.junit.**",
                "org.mockito.**",
                "lombok.**",
                "kotlin.**",
                "kotlinx.**",
                "groovy.lang.**"
        );

        private String baseRef = "origin/master";
        // Committed-only by default: the plugin's question is "what
        // tests does *this commit* touch?", not "what tests does this
        // commit plus whatever is rattling around in your working
        // tree touch?". Matching the default to that framing means a
        // programmatic or Gradle invocation on the same HEAD picks the
        // same tests every time, independent of dev workstation state,
        // and lines up with how CI checks the tree out. Callers who
        // want WIP to expand the diff opt in via {@code includeUncommitted(true)}.
        private boolean includeUncommitted = false;
        private boolean includeStaged = false;
        // Issue #132 ships headerEdges as a default-on strategy.
        // The DSL knob {@code headerEdgesEnabled = false} is the
        // documented one-flag kill switch (keeps the strategy in
        // the list but short-circuits at runtime), so dropping
        // "headerEdges" from the strategies list explicitly is
        // the secondary opt-out for adopters who want surgical
        // strategy-by-strategy disablement (mirrors how Phase 2
        // adopters could drop "transitive" or any other entry).
        private Set<String> strategies = Set.of(STRATEGY_NAMING, STRATEGY_USAGE,
                STRATEGY_IMPL, STRATEGY_TRANSITIVE, STRATEGY_HEADER_EDGES);
        // 4 matches the v2 design: most real-world ctrl -> svc -> repo ->
        // mapper chains are 2-3 deep, so 4 leaves headroom without
        // exploding discovery cost. Callers can still clamp back to 2
        // with the {@link #transitiveDepth(int)} setter.
        private int transitiveDepth = 4;
        private List<String> testSuffixes = List.of("Test", "IT", "ITTest", "IntegrationTest");
        private List<String> sourceDirs = List.of("src/main/java");
        private List<String> testDirs = List.of("src/test/java");
        private List<String> testTaskNames = List.of("test");
        private List<String> ignorePaths;
        private List<String> outOfScopeTestDirs;
        private List<String> outOfScopeSourceDirs;
        private boolean includeImplementationTests = true;
        // "Default" covers the Java-idiom pattern of {@code FooService} with
        // a {@code DefaultFooService} implementation; "Impl" covers the
        // {@code FooServiceImpl} pattern. v1 only knew about "Impl", which
        // silently dropped tests for every "Default"-prefixed impl in the
        // wild on zero-config installs.
        private List<String> implementationNaming = List.of("Impl", "Default");
        // 0 = no timeout (matches pre-v1.9.22 behaviour, wait forever).
        // Positive values are wall-clock seconds before the nested
        // ./gradlew child process is destroyed. Validated at the
        // setter; negative values throw IllegalArgumentException.
        private long gradlewTimeoutSeconds = 0L;
        // Issue #42: engine-level fan-out across the four discovery
        // strategies. Default ON because the per-strategy work is
        // independent and the thread-safety guards on ProjectIndex /
        // NamingConventionStrategy.crossPackageMatches make the
        // parallel path a strict win over serial on every workload
        // we've measured. Adopters who hit a regression can flip to
        // false without touching code via -PaffectedTestsParallelDiscovery=false.
        private boolean parallelDiscovery = true;

        // Issue #76 Phase 2 PR #4 — Kotlin AST is now first-class
        // (default ON). The {@code -Daffected-tests.kotlin.enabled}
        // system property has been removed; adopters who hit a
        // regression flip the DSL property
        // ({@code affectedTests { kotlinEnabled = false }}) or
        // route the affected sources via {@code outOfScopeSourceDirs}
        // / {@code outOfScopeTestDirs}. See README "Known limitations"
        // and docs/PHASE-2-KOTLIN-AST.md §9 PR #4 for the escape
        // hatches. Default ON because the embeddable shading + JAR
        // size budget were validated in PR #3 and the four pinned
        // --explain strings let adopters audit the AST path on
        // every run.
        private boolean kotlinEnabled = true;

        // Issue #132 — headerEdges discovery strategy defaults.
        // Default ON, all six categories enabled, depth=1 (immediate
        // header types only), sibling cap=5 (suppress impl downward
        // walk when an added type has >5 direct subtypes). The
        // ignore-globs default to the framework / JDK / Lombok /
        // JUnit / Kotlin / Groovy set adopters extend (not replace) —
        // see the README for the rationale on each entry.
        private boolean headerEdgesEnabled = true;
        private Set<String> headerEdgesExclude = Set.of();
        private int headerEdgesDepth = 1;
        private int headerEdgesMaxSiblings = 5;
        private List<String> headerEdgesIgnore = DEFAULT_HEADER_EDGES_IGNORE;

        private Mode mode;
        private Action onEmptyDiff;
        private Action onAllFilesIgnored;
        private Action onAllFilesOutOfScope;
        private Action onUnmappedFile;
        private Action onDiscoveryEmpty;
        private Action onDiscoveryIncomplete;

        public Builder baseRef(String baseRef) {
            if (baseRef == null || baseRef.isBlank()) {
                throw new IllegalArgumentException("baseRef must not be null or blank");
            }
            if (!isAcceptableBaseRef(baseRef)) {
                // The rejected value is echoed back in the exception message, which
                // Gradle renders verbatim into the build log (and often into build-
                // scan HTML). Sanitising here closes the same log-forgery surface
                // that containsControlChars closes on the accept path — without it,
                // an attacker-poisoned CI_BASE_REF would still get its forged
                // status line printed, just via the *reject* branch instead of the
                // accept branch. See the javadoc on isAcceptableBaseRef.
                throw new IllegalArgumentException(
                        "baseRef is not a valid git ref, SHA, or short form: '"
                                + LogSanitizer.sanitize(baseRef) + "' — expected something like "
                                + "'origin/master', 'HEAD~1', or a 7-40 char hex SHA");
            }
            this.baseRef = baseRef;
            return this;
        }

        private static final java.util.regex.Pattern SHORT_SHA =
                java.util.regex.Pattern.compile("^[0-9a-fA-F]{7,40}$");
        // Covers HEAD, HEAD~N, HEAD^, HEAD^N, HEAD@{0}, master~2, etc.
        // The refname validity of the left-hand side is delegated to
        // JGit below; this pattern only validates the suffix grammar.
        private static final java.util.regex.Pattern REV_EXPR =
                java.util.regex.Pattern.compile("^([^~^@]+)([~^][0-9]*|@\\{[^}]+\\})+$");

        /**
         * Accepts any input JGit would successfully resolve against a real
         * repository: canonical ref names (delegated to
         * {@link org.eclipse.jgit.lib.Repository#isValidRefName(String)}),
         * short/long SHAs, and {@code HEAD~N}/{@code ^N}/{@code @{N}}
         * rev-expressions. Rejects path-traversal shapes by construction
         * since JGit's refname validator already forbids {@code ..} and
         * a leading {@code /}. The {@link #SHORT_SHA} short-circuit at
         * the top bypasses {@code isValidRefName}, but is safe by
         * construction: the hex-only character class excludes {@code .}
         * and {@code /}, so path-traversal shapes cannot reach the SHA
         * path.
         *
         * <p>Control characters ({@code \n}, {@code \r}, ESC, CSI, DEL,
         * the whole C0 and C1 ranges) are rejected at the entry gate
         * before any of the downstream matchers run. This is load-
         * bearing for the {@link #REV_EXPR} path: its
         * {@code @\{[^}]+\}} segment matches newline, ESC, and CSI
         * verbatim, so a {@code baseRef} sourced from an attacker-
         * controlled CI environment variable like
         * {@code master@\{1\n\u001b[2JAffected Tests: SELECTED\u001b[m\}}
         * previously passed validation and then flowed straight into
         * {@code log.info("Base ref: {}", …)} in
         * {@code AffectedTestsEngine} and into three
         * {@code IllegalStateException} messages in
         * {@code GitChangeDetector} — a log-forgery surface that let
         * an attacker fabricate plugin-branded status lines in CI
         * output. Rejecting at the gate closes every regex path at
         * once without depending on getting each hand-crafted
         * character class right.
         */
        private static boolean isAcceptableBaseRef(String baseRef) {
            if (containsControlChars(baseRef)) {
                return false;
            }
            if (SHORT_SHA.matcher(baseRef).matches()) {
                return true;
            }
            if (org.eclipse.jgit.lib.Repository.isValidRefName(baseRef)) {
                return true;
            }
            // Short ref names like `master` or `origin/master` are rejected
            // by isValidRefName (which requires a leading `refs/...`), so
            // accept them if they at least survive the refname rules when
            // prefixed. This preserves the pre-v1.9.19 behaviour of
            // accepting `origin/master` as a base ref.
            if (org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/" + baseRef)) {
                return true;
            }
            java.util.regex.Matcher rev = REV_EXPR.matcher(baseRef);
            if (rev.matches()) {
                String head = rev.group(1);
                if ("HEAD".equals(head)
                        || org.eclipse.jgit.lib.Repository.isValidRefName(head)
                        || org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/" + head)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Returns {@code true} if {@code value} contains any C0 control
         * character ({@code 0x00..0x1F}), DEL ({@code 0x7F}), or C1
         * control character ({@code 0x80..0x9F}). Kept in the builder
         * rather than reaching for {@link io.affectedtests.core.util.LogSanitizer}
         * because this is a validation gate, not a logging concern —
         * the string must be rejected here before it ever reaches a
         * logger, and keeping the check local makes the
         * {@link #isAcceptableBaseRef(String)} contract auditable
         * without cross-package hops.
         */
        private static boolean containsControlChars(String value) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c < 0x20 || c == 0x7F || (c >= 0x80 && c <= 0x9F)) {
                    return true;
                }
            }
            return false;
        }
        public Builder includeUncommitted(boolean v) { this.includeUncommitted = v; return this; }
        public Builder includeStaged(boolean v) { this.includeStaged = v; return this; }
        public Builder strategies(Set<String> v) {
            this.strategies = Objects.requireNonNull(v, "strategies must not be null");
            return this;
        }
        public Builder transitiveDepth(int v) { this.transitiveDepth = Math.max(0, Math.min(v, 5)); return this; }
        /**
         * @see AffectedTestsConfig#parallelDiscovery()
         */
        public Builder parallelDiscovery(boolean v) { this.parallelDiscovery = v; return this; }

        /**
         * @see AffectedTestsConfig#kotlinEnabled()
         */
        public Builder kotlinEnabled(boolean v) { this.kotlinEnabled = v; return this; }

        /** @see AffectedTestsConfig#headerEdgesEnabled() */
        public Builder headerEdgesEnabled(boolean v) {
            this.headerEdgesEnabled = v;
            return this;
        }

        /**
         * Sets the list of header-edge categories to opt out of.
         * Entries are case-insensitive and validated against the
         * canonical set {@code (extends, implements, permits,
         * type-bounds, record-components, annotations)}. Unknown
         * entries are rejected — silently ignoring a typo would
         * leave the adopter expecting their opt-out applied when
         * it didn't.
         *
         * @see AffectedTestsConfig#headerEdgesExclude()
         */
        public Builder headerEdgesExclude(Set<String> v) {
            Objects.requireNonNull(v, "headerEdgesExclude must not be null");
            Set<String> normalised = new java.util.LinkedHashSet<>();
            for (String entry : v) {
                if (entry == null || entry.isBlank()) {
                    throw new IllegalArgumentException(
                            "headerEdgesExclude entries must be non-blank.");
                }
                String lower = entry.toLowerCase(java.util.Locale.ROOT);
                if (!VALID_HEADER_EDGE_CATEGORIES.contains(lower)) {
                    throw new IllegalArgumentException(
                            "headerEdgesExclude entry '" + LogSanitizer.sanitize(entry)
                                    + "' is not a known category. Valid entries: "
                                    + VALID_HEADER_EDGE_CATEGORIES);
                }
                normalised.add(lower);
            }
            this.headerEdgesExclude = normalised;
            return this;
        }

        /**
         * Clamps to {@code [0, 2]} — values outside the range silently
         * pin to the boundary. The plan-side rationale lives on
         * {@link AffectedTestsConfig#headerEdgesDepth()}: depths
         * above 2 rapidly approach full-suite selection on real
         * codebases.
         *
         * @see AffectedTestsConfig#headerEdgesDepth()
         */
        public Builder headerEdgesDepth(int v) {
            this.headerEdgesDepth = Math.max(0, Math.min(v, 2));
            return this;
        }

        /**
         * Rejects negative values — there is no such thing as a
         * negative sibling cap, and clamping to zero would hide a
         * misconfigured DSL expression. {@code 0} is the legitimate
         * "suppress every downward walk from added types"
         * configuration.
         *
         * @see AffectedTestsConfig#headerEdgesMaxSiblings()
         */
        public Builder headerEdgesMaxSiblings(int v) {
            if (v < 0) {
                throw new IllegalArgumentException(
                        "headerEdgesMaxSiblings must be >= 0; got " + v);
            }
            this.headerEdgesMaxSiblings = v;
            return this;
        }

        /**
         * @see AffectedTestsConfig#headerEdgesIgnore()
         */
        public Builder headerEdgesIgnore(List<String> v) {
            this.headerEdgesIgnore = Objects.requireNonNull(v,
                    "headerEdgesIgnore must not be null");
            return this;
        }
        public Builder testSuffixes(List<String> v) {
            this.testSuffixes = Objects.requireNonNull(v, "testSuffixes must not be null");
            return this;
        }
        public Builder sourceDirs(List<String> v) {
            this.sourceDirs = Objects.requireNonNull(v, "sourceDirs must not be null");
            return this;
        }
        public Builder testDirs(List<String> v) {
            this.testDirs = Objects.requireNonNull(v, "testDirs must not be null");
            return this;
        }

        /**
         * Sets the list of Gradle test task names the dispatch path
         * may invoke. See {@link AffectedTestsConfig#testTaskNames()}
         * for the routing semantics. Empty / null lists are rejected;
         * task names containing whitespace, control characters, or
         * Gradle path separators ({@code ':' / '/'}) are also
         * rejected — those would forge a malformed task path that
         * the nested Gradle invocation would mis-parse, and the same
         * "fail at config time, not at dispatch time" posture
         * {@link #baseRef(String)} takes applies here.
         */
        public Builder testTaskNames(List<String> v) {
            Objects.requireNonNull(v, "testTaskNames must not be null");
            if (v.isEmpty()) {
                throw new IllegalArgumentException(
                        "testTaskNames must contain at least one task name "
                                + "(default: ['test']).");
            }
            for (String name : v) {
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException(
                            "testTaskNames entries must be non-blank.");
                }
                for (int i = 0; i < name.length(); i++) {
                    char c = name.charAt(i);
                    if (c == ':' || c == '/' || c == '\\'
                            || Character.isWhitespace(c)
                            || Character.isISOControl(c)) {
                        throw new IllegalArgumentException(
                                "testTaskNames entry '" + name + "' contains an "
                                        + "illegal character at index " + i
                                        + ". Task names must not contain ':' / "
                                        + "'/' / whitespace / control characters "
                                        + "— those forge a malformed Gradle task "
                                        + "path on dispatch.");
                    }
                }
            }
            this.testTaskNames = v;
            return this;
        }

        /** Glob patterns for files the plugin must ignore entirely. */
        public Builder ignorePaths(List<String> v) {
            this.ignorePaths = Objects.requireNonNull(v, "ignorePaths must not be null");
            return this;
        }

        /** Test source directories the plugin must not dispatch (e.g. {@code api-test/src/test/java}). */
        public Builder outOfScopeTestDirs(List<String> v) {
            this.outOfScopeTestDirs = Objects.requireNonNull(v, "outOfScopeTestDirs must not be null");
            return this;
        }

        /** Production source directories the plugin must treat as out-of-scope. */
        public Builder outOfScopeSourceDirs(List<String> v) {
            this.outOfScopeSourceDirs = Objects.requireNonNull(v, "outOfScopeSourceDirs must not be null");
            return this;
        }

        public Builder includeImplementationTests(boolean v) { this.includeImplementationTests = v; return this; }
        public Builder implementationNaming(List<String> v) {
            this.implementationNaming = Objects.requireNonNull(v, "implementationNaming must not be null");
            return this;
        }

        /**
         * Sets the wall-clock deadline for the nested {@code ./gradlew}
         * invocation. {@code 0} disables the timeout (pre-v1.9.22
         * default — wait indefinitely). Any positive value is seconds
         * to wait before the Gradle task destroys the child process and
         * fails with a clear error.
         *
         * <p>Negative values are rejected — the single pre-computed
         * policy decision here is "there is no such thing as a negative
         * deadline". Clamping to zero would hide a misconfigured
         * Groovy/Kotlin DSL expression; throwing forces the user to see
         * it at build-config time.
         *
         * @param v the timeout in seconds; must be {@code >= 0}
         * @return this builder
         * @throws IllegalArgumentException if {@code v} is negative
         */
        public Builder gradlewTimeoutSeconds(long v) {
            if (v < 0) {
                throw new IllegalArgumentException(
                        "gradlewTimeoutSeconds must be >= 0 (0 disables the timeout); got " + v);
            }
            this.gradlewTimeoutSeconds = v;
            return this;
        }

        public Builder mode(Mode v) {
            this.mode = Objects.requireNonNull(v, "mode must not be null");
            return this;
        }
        public Builder onEmptyDiff(Action v) {
            this.onEmptyDiff = Objects.requireNonNull(v, "onEmptyDiff must not be null");
            return this;
        }
        public Builder onAllFilesIgnored(Action v) {
            this.onAllFilesIgnored = Objects.requireNonNull(v, "onAllFilesIgnored must not be null");
            return this;
        }
        public Builder onAllFilesOutOfScope(Action v) {
            this.onAllFilesOutOfScope = Objects.requireNonNull(v, "onAllFilesOutOfScope must not be null");
            return this;
        }
        public Builder onUnmappedFile(Action v) {
            this.onUnmappedFile = Objects.requireNonNull(v, "onUnmappedFile must not be null");
            return this;
        }
        public Builder onDiscoveryEmpty(Action v) {
            this.onDiscoveryEmpty = Objects.requireNonNull(v, "onDiscoveryEmpty must not be null");
            return this;
        }
        public Builder onDiscoveryIncomplete(Action v) {
            this.onDiscoveryIncomplete = Objects.requireNonNull(v, "onDiscoveryIncomplete must not be null");
            return this;
        }

        public AffectedTestsConfig build() {
            return new AffectedTestsConfig(this);
        }

        /**
         * Detects whether the current process is running in CI via common
         * env vars. Kept package-private so tests can verify the detection
         * rules without going through {@link #build()}.
         */
        static Mode detectMode() {
            if (envSet("CI")
                    || envSet("GITLAB_CI")
                    || envSet("GITHUB_ACTIONS")
                    || envSet("JENKINS_HOME")
                    || envSet("CIRCLECI")
                    || envSet("TRAVIS")
                    || envSet("BUILDKITE")
                    || envSet("TF_BUILD")) {
                return Mode.CI;
            }
            return Mode.LOCAL;
        }

        private static boolean envSet(String name) {
            String v = System.getenv(name);
            return v != null && !v.isBlank();
        }
    }
}
