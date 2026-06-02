package io.affectedtests.gradle;

import io.affectedtests.core.AffectedTestsEngine;
import io.affectedtests.core.AffectedTestsEngine.AffectedTestsResult;
import io.affectedtests.core.AffectedTestsEngine.Buckets;
import io.affectedtests.core.AffectedTestsEngine.EscalationReason;
import io.affectedtests.core.config.Action;
import io.affectedtests.core.config.ActionSource;
import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.config.Mode;
import io.affectedtests.core.config.Situation;
import io.affectedtests.core.discovery.DiscoveryProfile;
import io.affectedtests.core.util.LogSanitizer;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Gradle task that detects affected tests and executes them.
 *
 * <p>Execution flow:
 * <ol>
 *   <li>Detects git changes against the configured base ref</li>
 *   <li>Maps changed files to production/test classes</li>
 *   <li>Discovers which test classes are affected</li>
 *   <li>Groups FQNs by owning subproject and runs {@code :<module>:test --tests <fqn>}
 *       once per module so that {@code --tests} filters don't cross module boundaries</li>
 * </ol>
 *
 * <p>Configuration-cache safe: every input is a Gradle {@code Property} /
 * {@code ListProperty} / {@code MapProperty} / {@code DirectoryProperty},
 * the dispatch path runs through an injected {@link ExecOperations}
 * service, and no {@code Project} or live build-time object is captured
 * by the task action. Reading the live git state and walking the source
 * tree happen entirely inside {@link #runAffectedTests()} — that's
 * task-action work, which CC stores once and replays on subsequent
 * runs without re-resolving the task graph.
 *
 * <p>The task is deliberately marked as never up-to-date
 * ({@code outputs.upToDateWhen(t -> false)}) so a real git change always
 * triggers a re-run; that's an up-to-date concern, independent of CC.
 *
 * <p>The build cache is disabled for the same reason the up-to-date
 * check is: the task has no cacheable outputs (it dispatches {@code
 * :test} runs and writes nothing of its own), and its work depends on
 * live git state which the build cache cannot key on. Gradle 9.5+'s
 * {@code validatePlugins} requires this be declared explicitly.
 */
@DisableCachingByDefault(because =
        "AffectedTestTask is a dispatcher: it reads live git state, "
                + "selects affected tests, and forwards to :test. It produces "
                + "no cacheable outputs and is forced never-up-to-date so a "
                + "real git change always triggers a re-run.")
public abstract class AffectedTestTask extends DefaultTask {

    public AffectedTestTask() {
        // No declared outputs and the result depends on live git state,
        // so up-to-date checks must always miss. CC compatibility is a
        // separate concern (the task IS CC-safe — see the class Javadoc)
        // so we no longer self-mark with notCompatibleWithConfigurationCache.
        getOutputs().upToDateWhen(t -> false);
    }

    /**
     * Git base ref to diff against.
     * Default: {@code "origin/master"}. Override via {@code -PaffectedTestsBaseRef=...}.
     *
     * @return the base ref property
     */
    @Input
    public abstract Property<String> getBaseRef();

    /**
     * Whether to include uncommitted (unstaged) changes in the diff.
     * Default: {@code false} — committed-only, so a local run picks
     * the same tests CI would pick on the same HEAD, and two runs on
     * the same commit are deterministic. Flip to {@code true} in
     * {@code build.gradle} if you iterate on tests locally and want
     * WIP to seed the diff.
     *
     * @return the include uncommitted property
     */
    @Input
    public abstract Property<Boolean> getIncludeUncommitted();

    /**
     * Whether to include staged (added to index) changes in the diff.
     * Default: {@code false} — see {@link #getIncludeUncommitted()} for
     * the rationale; both knobs move together on the CI-first defaults.
     *
     * @return the include staged property
     */
    @Input
    public abstract Property<Boolean> getIncludeStaged();

    /**
     * Discovery strategies to use for finding affected tests.
     * Valid values: {@code "naming"}, {@code "usage"}, {@code "impl"}, {@code "transitive"}.
     * Default: all four.
     *
     * @return the strategies list property
     */
    @Input
    public abstract ListProperty<String> getStrategies();

    /**
     * How many levels of transitive dependencies to follow when the
     * {@code transitive} strategy is enabled.
     * Range: 0 (disabled) to 5. Default: {@code 4} — matches the depth
     * most Java controller &rarr; service &rarr; repository chains
     * actually reach in Modulr-shaped codebases while leaving one level
     * of margin. The pre-v2 default was {@code 2}, which under-reached
     * on any MR that crossed more than one seam; consumers reading this
     * Javadoc were being told they still needed to set {@code 4}
     * explicitly — they do not.
     *
     * @return the transitive depth property
     */
    @Input
    public abstract Property<Integer> getTransitiveDepth();

    /**
     * Whether to fan the four discovery strategies out across a small
     * thread pool (default {@code true}, issue #42). Wired from the
     * {@code parallelDiscovery} extension property; can also be
     * overridden per-invocation via the Gradle property
     * {@code -PaffectedTestsParallelDiscovery=false}.
     *
     * @return the parallel-discovery property
     */
    @Input
    public abstract Property<Boolean> getParallelDiscovery();

    /**
     * Whether to register the Kotlin AST parser for {@code .kt} files
     * (issue #76 Phase 2, PR #4). Default {@code true} — Kotlin
     * sources participate in AST-driven discovery (Usage / Impl /
     * Transitive) on every run. The {@code -Daffected-tests.kotlin.enabled}
     * system property has been removed; adopters who hit a regression
     * flip the DSL property to {@code false} or route the affected
     * sources via {@code outOfScopeSourceDirs} / {@code outOfScopeTestDirs}.
     *
     * <p>The flag value participates in the project-index cache hash
     * so a flip across consecutive runs forces a clean rescan rather
     * than reusing rows the new flag value would not produce.
     *
     * @return the Kotlin-enabled property
     */
    @Input
    public abstract Property<Boolean> getKotlinEnabled();

    /**
     * Issue&nbsp;#132 — kill switch for the {@code headerEdges}
     * discovery strategy. See
     * {@link AffectedTestsExtension#getHeaderEdgesEnabled()} for
     * the full rationale. {@code @Input} so a flip across runs
     * forces fresh discovery — the strategy's contribution can
     * widen the selected set materially.
     *
     * @return the headerEdges-enabled property
     */
    @Input
    public abstract Property<Boolean> getHeaderEdgesEnabled();

    /**
     * Issue&nbsp;#132 — header-edge categories the adopter has
     * opted OUT of. See
     * {@link AffectedTestsExtension#getHeaderEdgesExclude()} for
     * the valid category names and rationale. Unset (or empty)
     * means "all categories on".
     *
     * @return the headerEdgesExclude list property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract ListProperty<String> getHeaderEdgesExclude();

    /**
     * Issue&nbsp;#132 — header-edge walk depth ({@code 1}, the
     * default, walks immediate header targets only). See
     * {@link AffectedTestsExtension#getHeaderEdgesDepth()} for the
     * clamping and "explosion at depth>2" rationale.
     *
     * @return the headerEdges depth property
     */
    @Input
    public abstract Property<Integer> getHeaderEdgesDepth();

    /**
     * Issue&nbsp;#132 — sibling cap on the downward impl-walk
     * triggered by a header-edge-added type. See
     * {@link AffectedTestsExtension#getHeaderEdgesMaxSiblings()}
     * for the {@code BaseController} explosion case the cap
     * exists to neutralise. {@code 5} by default.
     *
     * @return the headerEdgesMaxSiblings property
     */
    @Input
    public abstract Property<Integer> getHeaderEdgesMaxSiblings();

    /**
     * Issue&nbsp;#132 — globs (FQN patterns) for types whose
     * header-edge contributions are ignored. Optional — when
     * unset the core config builder's default list applies, which
     * mutes JDK / Spring / JUnit / Mockito / Kotlin / Lombok
     * stdlib noise. See
     * {@link AffectedTestsExtension#getHeaderEdgesIgnore()} for
     * extension vs replace semantics.
     *
     * @return the headerEdgesIgnore list property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract ListProperty<String> getHeaderEdgesIgnore();

    /**
     * Suffixes used by the naming strategy to find test classes.
     * Default: {@code ["Test", "IT", "ITTest", "IntegrationTest"]}.
     *
     * @return the test suffixes list property
     */
    @Input
    public abstract ListProperty<String> getTestSuffixes();

    /**
     * Production source directories relative to each module root.
     * Default: {@code ["src/main/java"]}.
     *
     * @return the source dirs list property
     */
    @Input
    public abstract ListProperty<String> getSourceDirs();

    /**
     * Test source directories relative to each module root.
     * Default: {@code ["src/test/java"]}.
     *
     * @return the test dirs list property
     */
    @Input
    public abstract ListProperty<String> getTestDirs();

    /**
     * Names of the Gradle test tasks the dispatch path may invoke.
     * Default: {@code ["test"]}. See
     * {@link AffectedTestsExtension#getTestTaskNames} for the
     * routing semantics.
     *
     * @return the test task names list property
     */
    @Input
    public abstract ListProperty<String> getTestTaskNames();

    /**
     * Glob patterns for files that must never influence test selection.
     * Optional — when unset, the core config's default list applies.
     *
     * @return the ignore paths list property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract ListProperty<String> getIgnorePaths();

    /**
     * Test source directories (e.g. {@code "api-test/src/test/java"})
     * whose contents the plugin must not dispatch via the
     * {@code affectedTest} task.
     *
     * @return the out-of-scope test dirs list property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract ListProperty<String> getOutOfScopeTestDirs();

    /**
     * Production source directories the plugin must treat as
     * out-of-scope.
     *
     * @return the out-of-scope source dirs list property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract ListProperty<String> getOutOfScopeSourceDirs();

    /**
     * Whether to include tests for implementations of changed interfaces/base classes.
     * Default: {@code true}.
     *
     * @return the include implementation tests property
     */
    @Input
    public abstract Property<Boolean> getIncludeImplementationTests();

    /**
     * Suffixes/prefixes for finding implementation classes.
     * Default: {@code ["Impl", "Default"]}.
     *
     * @return the implementation naming list property
     */
    @Input
    public abstract ListProperty<String> getImplementationNaming();

    /**
     * Execution profile name — one of {@code "auto"}, {@code "local"},
     * {@code "ci"}, {@code "strict"}. Unset resolves to {@code "auto"}
     * in the core config, which detects CI from common env vars.
     *
     * @return the mode property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getMode();

    /** @return the on-empty-diff situation action property */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getOnEmptyDiff();

    /** @return the on-all-files-ignored situation action property */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getOnAllFilesIgnored();

    /** @return the on-all-files-out-of-scope situation action property */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getOnAllFilesOutOfScope();

    /** @return the on-unmapped-file situation action property */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getOnUnmappedFile();

    /** @return the on-discovery-empty situation action property */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getOnDiscoveryEmpty();

    /**
     * Action to take when discovery ran but one or more scanned Java files
     * failed to parse (see {@link Situation#DISCOVERY_INCOMPLETE}). One of
     * {@code "selected"}, {@code "full_suite"}, {@code "skipped"}. Unset
     * falls through to the {@link Mode} default (CI and STRICT escalate
     * to {@code full_suite}; LOCAL keeps the partial selection).
     *
     * @return the on-discovery-incomplete property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getOnDiscoveryIncomplete();

    /**
     * Wall-clock timeout (in seconds) for the nested {@code ./gradlew}
     * invocation that runs the affected / full test suite. {@code 0}
     * disables the timeout (pre-v1.9.22 default: wait indefinitely).
     * Positive values deadline the child: the task destroys the
     * process tree after the interval and fails the build.
     *
     * <p>Kept {@link org.gradle.api.tasks.Optional @Optional} so that
     * zero-config callers skip the timeout entirely — opting in is
     * explicit. Must be {@code >= 0}; the core config builder rejects
     * negative values at build-config time.
     *
     * @return the gradlew timeout property in seconds
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<Long> getGradlewTimeoutSeconds();

    /**
     * When {@code true}, the task prints the full decision trace
     * (buckets, situation, action, action source) and exits without
     * running any tests. Used by operators to answer "why did this MR
     * land on that outcome?" without having to enable debug logs.
     *
     * <p>Marked {@link Internal @Internal} rather than {@link Input @Input}
     * because flipping the explain flag must not invalidate a cached
     * execution — it changes only the lifecycle logging, never the set
     * of tests Gradle would actually run.
     *
     * @return the explain flag property
     */
    @Internal
    @Option(option = "explain",
            description = "Print the decision trace (buckets, situation, action, source) "
                    + "and exit without running tests.")
    public abstract Property<Boolean> getExplain();

    /**
     * Format for the {@code --explain} trace. {@code "text"} (the
     * default) produces the human-readable line-oriented trace every
     * adopter has been consuming since v2; {@code "json"} produces
     * a compact single-line JSON object with the same fields, suitable
     * for dashboard / telemetry pipelines that previously had to
     * regex-parse the text trace and broke every time the trace
     * shape evolved (issue #53).
     *
     * <p>{@link Internal @Internal} for the same reason as
     * {@link #getExplain}: format selection only changes lifecycle
     * logging, never the set of tests Gradle would run.
     *
     * @return the explain format property
     */
    @Internal
    @Option(option = "explain-format",
            description = "Output format for --explain. One of: 'text' (default, "
                    + "human-readable) or 'json' (compact JSON object for dashboards "
                    + "and telemetry).")
    public abstract Property<String> getExplainFormat();

    /**
     * Map of subproject directory (relative to the root project, empty string
     * for the root project itself) to the Gradle path of that subproject
     * (e.g. {@code ":services:payment"}). Populated automatically by the plugin
     * and used to group affected test FQNs by their owning module.
     *
     * @return the subproject dirs map property
     */
    @Internal
    public abstract MapProperty<String, String> getSubprojectPaths();

    /**
     * The root project directory (resolved at configuration time).
     *
     * @return the root dir property
     */
    @Internal
    public abstract DirectoryProperty getRootDir();

    /**
     * Injected by Gradle for executing the test subprocess.
     *
     * @return the exec operations service
     */
    @Inject
    protected abstract ExecOperations getExecOperations();

    /**
     * Detects affected tests and executes them via a Gradle subprocess.
     */
    @TaskAction
    public void runAffectedTests() {
        Path projectDir = getRootDir().get().getAsFile().toPath();

        AffectedTestsConfig config = buildConfig();

        AffectedTestsEngine engine = new AffectedTestsEngine(config, projectDir);
        AffectedTestsResult result = engine.run();

        // Loud warning before the explain/dispatch fork: LOCAL-mode's
        // default on DISCOVERY_INCOMPLETE is SELECTED, which silently
        // accepts a partial selection when the discovery parser hit an
        // unparseable Java file. On a workstation that's a conscious
        // trade-off (dev iterates on WIP tests, wants fast feedback),
        // but unless the operator knows about it, a green "SELECTED N
        // tests" summary reads as "we ran the affected tests" — not
        // as "we ran what the parser could see, which may not be all
        // of them". We emit WARN so it's visible in both the IDE and
        // CI logs without extra flags, and we do it on the --explain
        // path too so the diagnostic mode surfaces the same risk the
        // dispatch mode does.
        warnIfLocalDiscoveryIncompleteSelected(config, result);

        boolean explain = getExplain().getOrElse(false);
        if (explain) {
            // {@code --explain} is a diagnostic mode: we print the trace and
            // return without touching the executor so operators can re-run
            // as many times as they need without waiting for the suite.
            // Every line goes through {@code lifecycle()} so the trace is
            // visible by default (no {@code --info} gymnastics).
            //
            // Compute the module grouping here (not inside
            // renderExplainTrace) because the grouping needs
            // instance-level state — {@link #getSubprojectPaths()} and
            // the {@code projectDir} Path — which the pure-function
            // renderer deliberately doesn't see. Empty map when
            // there's no selection to split by module, so the renderer
            // can simply skip the "Modules:" block rather than print
            // an empty one.
            Map<String, List<String>> moduleGroupsForExplain = Map.of();
            if (result.action() == Action.SELECTED && !result.testClassFqns().isEmpty()) {
                // groupFqnsByModule returns keys shaped like the
                // owning project path (``, `:api`, `:application`).
                // The dispatch path then appends `:test` to produce
                // the real `:module:test` task path before invoking
                // the nested gradle. Mirror that transformation here
                // so the --explain preview names the EXACT tasks
                // that would run in a non-explain dispatch — anything
                // less defeats the point of the diagnostic.
                moduleGroupsForExplain = groupFqnsByTaskKey(
                        projectDir, result.testClassFqns(), result.testFqnToPath(),
                        config.testTaskNames());
            }
            String format = resolveExplainFormat(getExplainFormat().getOrNull());
            if ("json".equals(format)) {
                // Single line so dashboards / telemetry pipelines can
                // tail-grep the lifecycle stream and feed each line
                // directly to a JSON parser without multi-line
                // assembly. Routed through lifecycle() so it shows up
                // in the same default-visible stream the text trace
                // uses — operators don't have to flip --info to see
                // it.
                getLogger().lifecycle(
                        renderExplainJson(config, result, moduleGroupsForExplain));
            } else {
                for (String line : renderExplainTrace(config, result, moduleGroupsForExplain)) {
                    getLogger().lifecycle(line);
                }
            }
            return;
        }

        // The summary line is the single place the task names the
        // outcome (SELECTED / FULL_SUITE / SKIPPED), the situation that
        // produced it, and the reason phrase. Downstream lines must not
        // repeat any of those fields or CI logs drift into contradictory
        // duplicate phrasing. We hand the logger a format string + args
        // pair instead of a pre-formatted string so any future phrase
        // containing `{` or `}` characters (Liquibase file names,
        // JSONpath expressions) renders literally rather than silently
        // disappearing into the placeholder parser.
        LogLine summary = renderSummary(result);
        getLogger().lifecycle(summary.format(), summary.args());

        // Skipped results return here without touching the executor.
        // Intentionally no follow-up line — the summary already carried
        // the {@code SKIPPED (SITUATION)} prefix and the reason phrase,
        // so a separate "Skipping test execution (…)" log would only
        // duplicate what the operator already read.
        if (result.skipped()) {
            return;
        }

        if (result.testClassFqns().isEmpty() && !result.runAll()) {
            // Defensive fallthrough: the engine should have already
            // routed this into {@code SKIPPED} via
            // {@link AffectedTestsEngine.Situation#DISCOVERY_EMPTY},
            // but we keep the guard so a future regression surfaces as
            // a log line instead of a silent no-op.
            return;
        }

        executeTests(projectDir,
                result.testClassFqns(),
                result.testFqnToPath(),
                result.runAll(),
                config.gradlewTimeoutSeconds(),
                config.testTaskNames());
    }

    /**
     * Assembles the immutable core config from the task's Gradle
     * properties. All situation/mode knobs are optional at this layer;
     * the core builder handles precedence via the v2 two-tier ladder
     * (explicit {@code onXxx} > mode default).
     */
    private AffectedTestsConfig buildConfig() {
        AffectedTestsConfig.Builder builder = AffectedTestsConfig.builder()
                .baseRef(getBaseRef().get())
                .includeUncommitted(getIncludeUncommitted().get())
                .includeStaged(getIncludeStaged().get())
                .strategies(new LinkedHashSet<>(getStrategies().get()))
                .transitiveDepth(getTransitiveDepth().get())
                .testSuffixes(getTestSuffixes().get())
                .sourceDirs(getSourceDirs().get())
                .testDirs(getTestDirs().get())
                .testTaskNames(getTestTaskNames().get())
                .includeImplementationTests(getIncludeImplementationTests().get())
                .implementationNaming(getImplementationNaming().get())
                .parallelDiscovery(getParallelDiscovery().getOrElse(true))
                .kotlinEnabled(getKotlinEnabled().getOrElse(true))
                .headerEdgesEnabled(getHeaderEdgesEnabled().getOrElse(true))
                .headerEdgesDepth(getHeaderEdgesDepth().getOrElse(1))
                .headerEdgesMaxSiblings(getHeaderEdgesMaxSiblings().getOrElse(5));

        // Issue #132 — pass the per-category opt-out and ignore-glob
        // list through ONLY when the adopter set them. An unset
        // property must NOT replace the builder's default list with
        // an empty one, matching the existing {@code ignorePaths}
        // pattern below: explicit empty list means "wipe defaults",
        // unset means "keep defaults".
        // Issue #132 — treat `[]` consistently across both knobs: an
        // empty list means "no override, use defaults". headerEdgesIgnore
        // ships with 16 framework-noise globs we don't want a stray
        // `headerEdgesIgnore = []` in build.gradle to silently wipe; the
        // documented contract is "extend not replace", so an empty list
        // here matches the headerEdgesExclude semantics directly above.
        if (getHeaderEdgesExclude().isPresent()
                && !getHeaderEdgesExclude().get().isEmpty()) {
            builder.headerEdgesExclude(new java.util.LinkedHashSet<>(getHeaderEdgesExclude().get()));
        }
        if (getHeaderEdgesIgnore().isPresent()
                && !getHeaderEdgesIgnore().get().isEmpty()) {
            builder.headerEdgesIgnore(getHeaderEdgesIgnore().get());
        }

        if (getIgnorePaths().isPresent() && !getIgnorePaths().get().isEmpty()) {
            builder.ignorePaths(getIgnorePaths().get());
        }
        if (getOutOfScopeTestDirs().isPresent() && !getOutOfScopeTestDirs().get().isEmpty()) {
            builder.outOfScopeTestDirs(getOutOfScopeTestDirs().get());
        }
        if (getOutOfScopeSourceDirs().isPresent() && !getOutOfScopeSourceDirs().get().isEmpty()) {
            builder.outOfScopeSourceDirs(getOutOfScopeSourceDirs().get());
        }
        if (getMode().isPresent()) {
            builder.mode(parseMode(getMode().get()));
        }
        if (getOnEmptyDiff().isPresent()) {
            builder.onEmptyDiff(parseAction(getOnEmptyDiff().get(), "onEmptyDiff"));
        }
        if (getOnAllFilesIgnored().isPresent()) {
            builder.onAllFilesIgnored(parseAction(getOnAllFilesIgnored().get(), "onAllFilesIgnored"));
        }
        if (getOnAllFilesOutOfScope().isPresent()) {
            builder.onAllFilesOutOfScope(parseAction(getOnAllFilesOutOfScope().get(), "onAllFilesOutOfScope"));
        }
        if (getOnUnmappedFile().isPresent()) {
            builder.onUnmappedFile(parseAction(getOnUnmappedFile().get(), "onUnmappedFile"));
        }
        if (getOnDiscoveryEmpty().isPresent()) {
            builder.onDiscoveryEmpty(parseAction(getOnDiscoveryEmpty().get(), "onDiscoveryEmpty"));
        }
        if (getOnDiscoveryIncomplete().isPresent()) {
            builder.onDiscoveryIncomplete(parseAction(getOnDiscoveryIncomplete().get(), "onDiscoveryIncomplete"));
        }
        if (getGradlewTimeoutSeconds().isPresent()) {
            builder.gradlewTimeoutSeconds(getGradlewTimeoutSeconds().get());
        }
        return builder.build();
    }

    // Package-private so AffectedTestTaskLocaleTest can lock in the
    // Locale.ROOT contract — the Turkish-locale failure this guards
    // against is not reachable from any Gradle-level test shape, so
    // direct unit coverage is the only line of defence.
    static Mode parseMode(String raw) {
        try {
            // Locale.ROOT is mandatory here: Turkish-locale JVMs turn
            // "ci".toUpperCase() into "Cİ" (U+0130) and the enum lookup
            // then fails with a misleading "Unknown mode 'ci'" error
            // even though the spelling is literally in the valid list.
            return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // Mention the AUTO fallback explicitly: the most common
            // cause of a bad value here is a CI template that forgot
            // to scrub an unset variable, and the operator needs to
            // know they can just leave the -P off (or pass an empty
            // value — see the plugin's convention wiring) to get the
            // environment-aware default.
            //
            // We list the actual trigger set (mirror
            // AffectedTestsConfig.Builder.detectMode) rather than
            // just "CI=true" because a Jenkins or Azure Pipelines
            // operator reading a "CI=true is exported" hint on
            // their own runner would wrongly conclude AUTO falls
            // back to LOCAL — JENKINS_HOME / TF_BUILD flip it to CI
            // without CI=true ever being set.
            throw new GradleException("Unknown affectedTests.mode '" + raw
                    + "'. Expected one of: auto, local, ci, strict "
                    + "(omit the value or leave -PaffectedTestsMode unset to keep "
                    + "the AUTO default, which picks CI on recognised runners — "
                    + "CI=true, GITHUB_ACTIONS, GITLAB_CI, JENKINS_HOME, CIRCLECI, "
                    + "TRAVIS, BUILDKITE, TF_BUILD).", e);
        }
    }

    static Action parseAction(String raw, String property) {
        try {
            return Action.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new GradleException("Unknown " + property + " action '" + raw
                    + "'. Expected one of: selected, full_suite, skipped.", e);
        }
    }

    private void executeTests(Path projectDir,
                              Set<String> testFqns,
                              Map<String, Path> fqnToPath,
                              boolean runAll,
                              long gradlewTimeoutSeconds,
                              List<String> testTaskNames) {
        String gradleCommand = resolveGradleCommand(projectDir);

        List<String> args = new ArrayList<>();
        args.add(gradleCommand);

        // The previous `runAll || testFqns.isEmpty()` disjunct was stale:
        // the guard in runAffectedTests above already returned when the set
        // was empty and runAll was false, so by the time we reach this branch
        // testFqns.isEmpty() can only be true under runAll.
        if (runAll) {
            // Reason already printed by renderSummary in the caller; keeping
            // it out of this line prevents the "log prints the same phrase
            // twice" grep collision on the "Running ALL tests" marker.
            getLogger().lifecycle("Running ALL tests.");
            args.add("test");
        } else {
            // Group FQNs by owning subproject AND owning test task
            // (issue #48), then emit one nested ":moduleA:test --tests x
            // :moduleA:integrationTest --tests y" invocation per
            // (module × task) pair so Gradle's --tests filters don't
            // spill across modules or across source sets (where they'd
            // fail any task that doesn't happen to contain the FQN).
            Map<String, List<String>> grouped = groupFqnsByTaskKey(
                    projectDir, testFqns, fqnToPath, testTaskNames);

            // Validate every discovered FQN up front so the header,
            // per-module preview, and argv-append stay arithmetically
            // consistent. Splitting validation from dispatch also
            // means a module whose entire discovered set is malformed
            // (vanishingly unlikely in practice, but possible from a
            // buggy custom strategy) is dropped cleanly instead of
            // silently falling through to "run the whole module's
            // test suite" once the bare `taskPath` is appended to the
            // argv with no `--tests` filter.
            Map<String, List<String>> validatedGroups = new LinkedHashMap<>(grouped.size());
            int totalValid = 0;
            for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
                String taskPath = entry.getKey();
                List<String> valid = new ArrayList<>(entry.getValue().size());
                for (String fqn : entry.getValue()) {
                    if (!isValidFqn(fqn)) {
                        // Defence in depth against a compromised source
                        // tree sneaking shell-like tokens into a --tests
                        // argument. The FQN cannot correspond to a real
                        // JVM test class, so dropping it is lossless.
                        // Sanitise before logging — this is the exact
                        // input shape an attacker-planted filename
                        // containing a newline or ANSI escape would land
                        // in, and WARN is rendered in CI by default.
                        getLogger().warn(
                                "Affected Tests: skipping malformed test FQN '{}' for task {} — "
                                        + "not a Java-shaped identifier, cannot correspond to a "
                                        + "real test class.",
                                LogSanitizer.sanitize(fqn), taskPath);
                        continue;
                    }
                    valid.add(fqn);
                }
                if (!valid.isEmpty()) {
                    validatedGroups.put(taskPath, valid);
                    totalValid += valid.size();
                }
            }

            int skipped = testFqns.size() - totalValid;

            // Belt-and-braces: a dispatch with zero surviving FQNs
            // across ALL modules would otherwise produce an argv of
            // `[gradlew, -x, compileJava]` with no task specified —
            // Gradle's behavior there (fail vs. default-task) is
            // environment-dependent and definitely not the safety
            // posture `runAll` promises. If we get here the WARN logs
            // above already explain which FQNs were dropped; a hard
            // fail surfaces the mis-discovery instead of silently
            // running nothing.
            if (validatedGroups.isEmpty()) {
                throw new GradleException(
                        "Affected Tests: every discovered FQN (" + testFqns.size()
                                + ") was malformed and skipped — refusing to dispatch a taskless "
                                + "Gradle invocation. See WARN logs above for the rejected FQNs.");
            }

            String skippedSuffix = skipped == 0
                    ? ""
                    : " (" + skipped + (skipped == 1 ? " malformed FQN skipped" : " malformed FQNs skipped")
                            + " — see WARN above)";
            getLogger().lifecycle("Running {} affected test classes across {} module(s):{}",
                    totalValid, validatedGroups.size(), skippedSuffix);

            // Dispatch-side emission. Lifecycle per module is bounded
            // by LIFECYCLE_FQN_PREVIEW_LIMIT; the full list stays at
            // info level (see that constant's Javadoc for why).
            for (Map.Entry<String, List<String>> entry : validatedGroups.entrySet()) {
                String taskPath = entry.getKey();
                List<String> validFqns = entry.getValue();

                args.add(taskPath);
                for (String fqn : validFqns) {
                    args.add("--tests");
                    args.add(fqn);
                }

                renderLifecycleDispatchPreview(taskPath, validFqns)
                        .forEach(getLogger()::lifecycle);
                for (String fqn : validFqns) {
                    getLogger().info("  {} -> {}", taskPath, fqn);
                }
            }
        }

        // Skip recompiling production code — testClasses (declared as a task
        // dependency by the plugin) has already been built as part of this
        // outer Gradle invocation, so the class files the nested process needs
        // are on disk. We deliberately do NOT pass -x compileTestJava: on a
        // clean checkout the test classes wouldn't exist yet, and Gradle would
        // fail with "No tests found". Letting compileTestJava run is cheap and
        // correct on all checkouts.
        args.add("-x");
        args.add("compileJava");

        int exitCode = (gradlewTimeoutSeconds > 0)
                ? runWithTimeout(args, projectDir, gradlewTimeoutSeconds)
                : runWithoutTimeout(args, projectDir);

        if (exitCode != 0) {
            throw new GradleException("Test execution failed with exit code " + exitCode);
        }
    }

    /**
     * Pre-v1.9.22 dispatch path: hand the argv to Gradle's
     * {@link ExecOperations} and wait for the child to finish with no
     * deadline. Kept as the default because {@link ExecOperations}
     * integrates with the outer Gradle build's log capture and build-
     * scan infrastructure in ways {@link ProcessBuilder} cannot match
     * from a plugin.
     */
    private int runWithoutTimeout(List<String> args, Path projectDir) {
        ExecResult execResult = getExecOperations().exec(spec -> {
            spec.commandLine(args);
            spec.workingDir(projectDir.toFile());
            spec.setIgnoreExitValue(true);
        });
        return execResult.getExitValue();
    }

    /**
     * Watchdog dispatch path used when
     * {@code affectedTests.gradlewTimeoutSeconds} is set to a positive
     * value. Spawns the child via {@link ProcessBuilder} (so we can
     * hold a {@link Process} handle and call {@link Process#destroyForcibly()}),
     * {@code inheritIO()}'s stdin/stdout/stderr so the user still sees
     * the nested Gradle output in real time, and enforces a wall-clock
     * deadline.
     *
     * <p>Termination ladder on timeout:
     * <ol>
     *   <li>{@link Process#destroy()} — polite SIGTERM; most JVMs react
     *       to this within a second or two, which gives the test
     *       runner a chance to flush coverage reports / screenshots
     *       instead of leaving them half-written.</li>
     *   <li>Wait {@value #TIMEOUT_GRACEFUL_SHUTDOWN_SECONDS}s for the
     *       polite stop to land.</li>
     *   <li>Fall back to {@link Process#destroyForcibly()} — SIGKILL —
     *       so a child that swallowed the SIGTERM cannot keep holding
     *       the CI worker hostage.</li>
     *   <li>Give the kernel another
     *       {@value #TIMEOUT_FORCIBLE_SHUTDOWN_SECONDS}s to reap the
     *       process and fail the build either way.</li>
     * </ol>
     *
     * <p>Process-tree semantics: {@code ./gradlew} almost always
     * connects to a shared Gradle daemon JVM, so the wrapper process
     * is the parent of the daemon only transiently; the test JVM
     * that's actually hung lives as a grandchild (shared daemon) or
     * a sibling (daemon reused across invocations). Killing only the
     * wrapper would therefore leave the hung workload running and
     * defeat the whole point of this knob. {@link #shutdownChild}
     * snapshots {@link ProcessHandle#descendants()} before the first
     * signal and {@code destroyForcibly}-es every still-live
     * descendant on the forcible leg. Operators who want a true
     * "no grace period, no daemon reuse" deadline can pass
     * {@code --no-daemon} at the outer build level.
     *
     * <p>Interrupt handling: if the outer Gradle build is cancelled
     * (Ctrl-C, Gradle daemon shutdown), the watchdog propagates the
     * cancellation by {@code destroyForcibly}-ing the wrapper and
     * every snapshotted descendant, then re-asserts the interrupt
     * on the caller thread.
     */
    // Package-private so AffectedTestTaskTimeoutTest can drive the
    // watchdog directly against a controllable child process. The
    // outer `executeTests` caller is still the only production call
    // site; exposing at package level is the smallest surface that
    // lets the regression test pin the kill-ladder behaviour without
    // rebuilding a DefaultTask harness.
    int runWithTimeout(List<String> args, Path projectDir, long timeoutSeconds) {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(projectDir.toFile());
        // Inherit stdin/stdout/stderr so the child's output (Gradle's
        // test task output, test report progress, etc.) streams to
        // the operator's terminal exactly as it did on the
        // ExecOperations path. We trade build-scan stream capture
        // for the ability to kill the child on timeout; opting in to
        // the timeout is opting in to that trade.
        pb.inheritIO();
        Process process;
        try {
            process = pb.start();
        } catch (java.io.IOException e) {
            throw new GradleException(
                    "Affected Tests: failed to spawn nested Gradle invocation for timeout dispatch: "
                            + LogSanitizer.sanitize(e.getMessage()),
                    e);
        }
        try {
            boolean finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (finished) {
                return process.exitValue();
            }
            getLogger().error(
                    "Affected Tests: nested Gradle invocation exceeded configured timeout of {}s; "
                            + "terminating the child process tree.",
                    timeoutSeconds);
            shutdownChild(process);
            throw new GradleException(
                    "Affected Tests: nested Gradle invocation exceeded the configured timeout of "
                            + timeoutSeconds + "s (affectedTests.gradlewTimeoutSeconds). "
                            + "The child process has been terminated. Raise the timeout or "
                            + "investigate a hung test — see the output above for progress.");
        } catch (InterruptedException e) {
            // Outer build was cancelled or the daemon is shutting down.
            // Kill the whole tree immediately so we do not leave
            // orphaned Gradle daemons / test JVMs on the runner, then
            // re-assert the interrupt so Gradle's own shutdown path
            // can continue. Snapshotting descendants before
            // destroyForcibly on the wrapper mirrors shutdownChild —
            // once the wrapper exits, re-parented daemons become
            // unreachable from process.descendants().
            List<ProcessHandle> descendants = process.descendants()
                    .collect(Collectors.toList());
            process.destroyForcibly();
            for (ProcessHandle child : descendants) {
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            }
            Thread.currentThread().interrupt();
            throw new GradleException(
                    "Affected Tests: nested Gradle invocation interrupted before completion.", e);
        }
    }

    private static final int TIMEOUT_GRACEFUL_SHUTDOWN_SECONDS = 10;
    private static final int TIMEOUT_FORCIBLE_SHUTDOWN_SECONDS = 5;

    /**
     * Shuts down the child process tree: sends {@link Process#destroy()}
     * to the wrapper first, waits for a grace period, then escalates
     * to {@link Process#destroyForcibly()} on the wrapper and every
     * still-live descendant.
     *
     * <p>Killing only the wrapper is not enough because
     * {@code ./gradlew} connects to a shared Gradle daemon JVM that
     * outlives the wrapper. On the timeout path the daemon is running
     * a test JVM — that's the actually-hung process that kept the
     * runner busy — and if we only reap the wrapper the daemon / test
     * JVM keep holding the runner hostage, which defeats the entire
     * point of the watchdog. We therefore snapshot
     * {@link ProcessHandle#descendants()} before the first signal
     * (so any daemon / test-JVM grandchildren are captured while
     * still reachable via the wrapper's process tree) and
     * {@code destroyForcibly} each descendant on the forcible leg.
     *
     * <p>The graceful leg still targets only the wrapper so SIGTERM-
     * aware test runners get their normal shutdown hook window to
     * flush coverage reports. Descendants are then reaped
     * {@code destroyForcibly} regardless of whether the wrapper
     * exited gracefully or had to be SIGKILLed, because a SIGTERM-
     * responsive wrapper (plain {@code sh}, most test runners)
     * exits on its own but leaves its children re-parented to
     * pid 1 — those grandchildren are the real hung workload and
     * would keep holding the CI worker hostage otherwise. Operators
     * who need "hard cutoff, no grace" semantics can still run the
     * outer build with {@code --no-daemon}, which re-parents the
     * test JVM as a direct child of the wrapper and makes the polite
     * leg already effective against the daemon too.
     */
    private void shutdownChild(Process process) {
        // Snapshot the descendant tree before we signal — once the
        // wrapper exits the grandchildren may re-parent to pid 1 and
        // {@link ProcessHandle#descendants()} on the wrapper will
        // return empty, leaving orphaned Gradle daemons alive.
        List<ProcessHandle> descendants = process.descendants()
                .collect(Collectors.toList());
        process.destroy();
        try {
            if (!process.waitFor(TIMEOUT_GRACEFUL_SHUTDOWN_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                // Best-effort wait — if SIGKILL itself does not reap
                // the child within this window something is deeply
                // wrong with the host, but we still want to return
                // to the caller so the build can fail cleanly rather
                // than hang here forever.
                process.waitFor(TIMEOUT_FORCIBLE_SHUTDOWN_SECONDS,
                        java.util.concurrent.TimeUnit.SECONDS);
            }
            // Reap any still-live descendants regardless of whether
            // the wrapper exited gracefully or had to be SIGKILLed.
            // A SIGTERM-responsive wrapper (plain `sh`, most test
            // runners) exits on destroy() but leaves its children
            // (daemon / test JVM) re-parented to pid 1 — those
            // grandchildren are the real hung workload and would
            // keep holding the CI worker hostage if we returned
            // here without reaping them.
            for (ProcessHandle child : descendants) {
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            for (ProcessHandle child : descendants) {
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            }
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Converts a Gradle project path (as returned by
     * {@link #groupFqnsByModule}) into the test task path used for
     * both dispatch argv and the {@code --explain} Modules-block
     * preview. Package-private so the unit tests can pin the root /
     * subproject shapes directly, and shared between the two sites
     * so the two lines of output an operator diffs
     * (explain preview vs dispatch log) cannot drift out of sync.
     *
     * <p>Empty key (= root project) resolves to {@code :test} with a
     * leading colon. The nested Gradle invocation accepts both
     * {@code test} and {@code :test} for the root case, so this
     * normalisation is cosmetic on the dispatch side but keeps the
     * preview unambiguous.
     */
    static String testTaskPath(String modulePath) {
        return testTaskPath(modulePath, "test");
    }

    /**
     * Per-task variant introduced in #48 so adopters with extra
     * source sets ({@code integrationTest}, {@code e2eTest}, etc.)
     * can route discovered FQNs to the right Gradle task. Same
     * leading-colon contract as the 1-arg form: the result always
     * starts with {@code ':'} so {@code appendModulesBlock}'s
     * canonical-key assertion stays valid.
     *
     * @param modulePath {@code ""} for the root project, otherwise a
     *                   {@code :sub:project} path
     * @param taskName   the Gradle test task name (e.g. {@code "test"},
     *                   {@code "integrationTest"})
     */
    static String testTaskPath(String modulePath, String taskName) {
        return modulePath.isEmpty() ? ":" + taskName : modulePath + ":" + taskName;
    }

    /**
     * Picks the Gradle test task name that owns the given test file,
     * by Gradle's source-set convention {@code src/<taskName>/java}
     * (or {@code src/<taskName>/kotlin} / {@code src/<taskName>/groovy}
     * — same name segment, different language root). Walks the
     * configured task-name list in order so adopters can express a
     * priority for ambiguous shapes (an integration test under
     * {@code src/integrationTest/java} that also matches a
     * less-specific generic task name resolves to the more specific
     * entry as long as it's listed first).
     *
     * <p>Falls back to the first entry in {@code testTaskNames}
     * (which defaults to {@code "test"}) when no source-set segment
     * matches — same conservative posture {@link #resolveOwningModule}
     * takes when subproject resolution fails. Never returns
     * {@code null}; the caller's contract is "this FQN goes to
     * <em>some</em> task" so a quiet routing failure would be a
     * regression of the kind v2.2.1 was raised to surface.
     *
     * <p>Package-private so {@code AffectedTestTaskTaskRoutingTest}
     * can pin both the segment-matching path and the fallback path
     * without spinning up a Gradle test runtime.
     */
    static String resolveTaskNameForFile(Path file, List<String> testTaskNames) {
        // Defensive: builder validation rejects empty / null lists,
        // but unit tests drive the helper directly with arbitrary
        // arguments. Returning "test" matches the documented default
        // and keeps the helper safe to call in any test fixture.
        if (testTaskNames == null || testTaskNames.isEmpty()) {
            return "test";
        }
        if (file == null) {
            return testTaskNames.get(0);
        }
        String normalized = file.toString().replace(File.separatorChar, '/');
        for (String taskName : testTaskNames) {
            // The segment we're looking for is `/src/<taskName>/`
            // — strict on both delimiters so a longer name like
            // `integrationTestPart` doesn't false-match a shorter
            // configured `integrationTest` (or vice versa). The
            // alternative — a substring search — would silently
            // route an `integrationTestPart` test to the
            // `integrationTest` task on some adopters' source-set
            // layouts, which is exactly the silent mis-route #48
            // is meant to prevent.
            String marker = "/src/" + taskName + "/";
            if (normalized.contains(marker)) {
                return taskName;
            }
        }
        return testTaskNames.get(0);
    }

    /**
     * Groups discovered test FQNs by the Gradle path of the subproject that
     * owns each test file. Tests under the root project fall under the
     * empty-string key and get dispatched to the root {@code test} task.
     *
     * <p>If no matching subproject is found for an FQN (e.g. the project
     * structure changed since configuration time), the FQN is routed to the
     * root project as a best-effort fallback.
     */
    private Map<String, List<String>> groupFqnsByModule(Path projectDir,
                                                       Set<String> testFqns,
                                                       Map<String, Path> fqnToPath) {
        Map<String, String> subprojectPaths = getSubprojectPaths().getOrElse(Map.of());

        // Sort entries by descending dir-length so that deeper subprojects
        // (e.g. "services/payment") win over their parents ("services").
        List<Map.Entry<String, String>> orderedEntries = new ArrayList<>(subprojectPaths.entrySet());
        orderedEntries.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));

        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String fqn : testFqns) {
            Path file = fqnToPath.get(fqn);
            String moduleGradlePath = resolveOwningModule(projectDir, file, orderedEntries);
            grouped.computeIfAbsent(moduleGradlePath, k -> new ArrayList<>()).add(fqn);
        }
        return grouped;
    }

    /**
     * Per-task-key grouping introduced by #48: runs
     * {@link #groupFqnsByModule} to find each FQN's owning module,
     * then post-processes the result by splitting each module bucket
     * into per-task buckets via {@link #resolveTaskNameForFile}.
     * Returns a map keyed by the canonical {@code :module:taskName}
     * Gradle path so dispatch and {@code --explain} can iterate
     * without re-running the helper.
     *
     * <p>Insertion order is module-first then task-first (within a
     * module) so a multi-source-set project's {@code --explain}
     * output groups by module — which is the shape adopters
     * naturally read.
     */
    private Map<String, List<String>> groupFqnsByTaskKey(Path projectDir,
                                                         Set<String> testFqns,
                                                         Map<String, Path> fqnToPath,
                                                         List<String> testTaskNames) {
        Map<String, List<String>> byModule = groupFqnsByModule(projectDir, testFqns, fqnToPath);
        Map<String, List<String>> byTaskKey = new LinkedHashMap<>(byModule.size());
        for (Map.Entry<String, List<String>> entry : byModule.entrySet()) {
            String modulePath = entry.getKey();
            for (String fqn : entry.getValue()) {
                Path file = fqnToPath.get(fqn);
                String taskName = resolveTaskNameForFile(file, testTaskNames);
                String taskKey = testTaskPath(modulePath, taskName);
                byTaskKey.computeIfAbsent(taskKey, k -> new ArrayList<>()).add(fqn);
            }
        }
        return byTaskKey;
    }

    private String resolveOwningModule(Path projectDir, Path file,
                                       List<Map.Entry<String, String>> orderedEntries) {
        if (file == null) {
            return "";
        }
        Path relative;
        try {
            relative = projectDir.relativize(file.toAbsolutePath());
        } catch (IllegalArgumentException e) {
            return "";
        }
        String normalized = relative.toString().replace(File.separatorChar, '/');
        for (Map.Entry<String, String> entry : orderedEntries) {
            String dir = entry.getKey();
            if (dir.isEmpty()) continue;
            String prefix = dir.endsWith("/") ? dir : dir + "/";
            if (normalized.startsWith(prefix)) {
                return entry.getValue();
            }
        }
        return "";
    }

    /**
     * Resolves the Gradle command to use. Prefers the wrapper in the project directory;
     * falls back to the bare {@code "gradle"} command name so the OS PATH is used.
     */
    private String resolveGradleCommand(Path projectDir) {
        String wrapperName = isWindows() ? "gradlew.bat" : "gradlew";
        File gradlew = projectDir.resolve(wrapperName).toFile();
        if (gradlew.exists() && gradlew.canExecute()) {
            return gradlew.getAbsolutePath();
        }
        // The wrapper is the contract we expect; falling back to a system-wide
        // gradle is a last resort. Warn loudly so broken checkouts don't pass
        // silently in CI.
        getLogger().warn("Gradle wrapper not found at {}/{}; falling back to '{}' from PATH. "
                        + "This usually indicates a broken or incomplete checkout.",
                projectDir, wrapperName, isWindows() ? "gradle.bat" : "gradle");
        return isWindows() ? "gradle.bat" : "gradle";
    }

    /**
     * Matches a dotted sequence of Java identifier segments — the shape
     * Gradle's {@code --tests} accepts. Each segment starts with a
     * letter, {@code _}, or {@code $} and continues with letters,
     * digits, {@code _}, or {@code $}. The trailing segment doubles as
     * either an inner-class name (in the {@code Outer.Inner} form
     * JavaParser emits) or a method name (in the
     * {@code com.example.Foo.someMethod} form Gradle's
     * {@code --tests} matcher expects); the regex does not — and
     * intentionally cannot — distinguish between those two cases, since
     * both are legal argv for the nested Gradle invocation. The
     * bytecode-style {@code Outer$Inner} shape is also accepted because
     * {@code $} is a valid identifier character in Java; discovery does
     * not produce that shape today but users occasionally type it by
     * hand on the command line.
     *
     * <p>Deliberately does NOT reject Java reserved words
     * ({@code if}, {@code class}, {@code return}, ...). The contract
     * of this filter is "is this argv safe to hand to
     * {@code gradle --tests}" — a compromised source tree sneaking
     * shell-like tokens or argv-flag-shaped strings into the FQN list
     * is the threat model. An FQN that happens to look like a reserved
     * word could never be produced by the discovery strategies (which
     * derive names from real {@code .java} filenames), so the only way
     * one reaches this method is adversarially, and the downstream
     * Gradle {@code --tests} matcher will simply report
     * "no tests found" for it — never a compile failure or RCE.
     * Keeping the regex broad here means we don't have to ship a
     * stale list of keywords that drifts as the JLS grows.
     */
    private static final Pattern JAVA_FQN =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    static boolean isValidFqn(String fqn) {
        return fqn != null && !fqn.isEmpty() && JAVA_FQN.matcher(fqn).matches();
    }

    private static boolean isWindows() {
        // Locale.ROOT keeps "Windows" → "windows" on Turkish-locale JVMs;
        // without it the dotted-i rules turn it into "wındows" and the
        // contains("win") check misses, routing Windows runners down the
        // non-Windows branch and picking 'gradlew' / 'gradle' where
        // 'gradlew.bat' / 'gradle.bat' is required.
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * A lifecycle log line expressed as an SLF4J-style format string plus
     * its positional arguments, so the caller can hand both to
     * {@link org.gradle.api.logging.Logger#lifecycle(String, Object...)}
     * and benefit from Gradle's placeholder parser — keeping this task
     * consistent with the other {@code lifecycle(...)} call-sites in the
     * file, and preventing any future phrase containing literal {@code {}}
     * from being swallowed by that parser.
     *
     * <p>Package-private so the log-shape test can assert on either the
     * format string or the rendered output without pulling in Gradle's
     * live logger.
     */
    record LogLine(String format, Object[] args) {
        LogLine {
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(args, "args");
            // Defensive copy — otherwise a caller that mutates the array
            // after construction would change the rendered log line.
            // Cheap (always small), and stops the "Object[] component in
            // a record is secretly shared state" class of bugs dead.
            args = args.clone();
        }

        @Override
        public Object[] args() {
            return args.clone();
        }
    }

    /** Marker substring used by both the emission and the tests to pin the WARN. */
    static final String LOCAL_DISCOVERY_INCOMPLETE_WARNING_MARKER =
            "affectedTest: LOCAL mode accepted a partial selection";

    /**
     * Emits a lifecycle-level WARN when the resolved run is a LOCAL-mode
     * {@link Situation#DISCOVERY_INCOMPLETE} + {@link Action#SELECTED}
     * combination — i.e. the workstation default that silently trusts
     * a partial discovery set after the Java parser dropped one or
     * more files. Deliberately WARN (not INFO): Gradle renders WARN in
     * the default log level, so operators don't need to opt in via
     * {@code --info} to see the risk.
     *
     * <p>Called before the {@code --explain} / dispatch fork so both
     * paths surface the same concern: a diagnostic run reading the
     * same partial selection that a non-diagnostic run would actually
     * dispatch must raise the same alarm. Fires at most once per
     * task execution — there is only one resolved situation per run.
     *
     * <p>The gate logic lives in the pure {@link #shouldWarnLocalDiscoveryIncomplete}
     * and message formatting in {@link #formatLocalDiscoveryIncompleteWarning};
     * this instance method only pipes the result through the task's
     * Gradle logger. Unit tests exercise the pure pair directly so
     * the four-way gate (mode, situation, action, skipped/empty) is
     * locked in without a log-capture fixture.
     */
    void warnIfLocalDiscoveryIncompleteSelected(AffectedTestsConfig config,
                                                AffectedTestsResult result) {
        if (!shouldWarnLocalDiscoveryIncomplete(config, result)) {
            return;
        }
        // Intentionally does NOT restate the parse-failure fact — the
        // engine's own WARN (rendered a few lines above, with file-level
        // detail) already did that. This line's job is the mode-specific
        // postscript the engine can't emit (it doesn't know the mode):
        // LOCAL chose to honour a partial selection, here's how to
        // escalate if you'd rather not.
        getLogger().warn(formatLocalDiscoveryIncompleteWarning(result.testClassFqns().size()));
    }

    /**
     * Pure gate for {@link #warnIfLocalDiscoveryIncompleteSelected}.
     * Returns {@code true} iff the WARN should fire. Package-private
     * for direct unit-test coverage of each guard:
     * <ul>
     *   <li>Non-LOCAL modes never warn (CI / STRICT escalate, no
     *       under-testing risk).</li>
     *   <li>Only DISCOVERY_INCOMPLETE ever warns (parse failure is
     *       the only "selection was quietly partial" shape).</li>
     *   <li>Only SELECTED ever warns (FULL_SUITE / SKIPPED either
     *       already escalated or already bailed, so there is no
     *       silent partial selection to surface).</li>
     *   <li>Skipped results or empty FQN lists short-circuit — the
     *       engine rewrites "SELECTED with nothing to select" into
     *       {@code skipped=true}, and a zero-count WARN immediately
     *       before the task bails would contradict itself.</li>
     * </ul>
     */
    static boolean shouldWarnLocalDiscoveryIncomplete(AffectedTestsConfig config,
                                                      AffectedTestsResult result) {
        if (config.effectiveMode() != Mode.LOCAL) {
            return false;
        }
        if (result.situation() != Situation.DISCOVERY_INCOMPLETE) {
            return false;
        }
        if (result.action() != Action.SELECTED) {
            return false;
        }
        if (result.skipped() || result.testClassFqns().isEmpty()) {
            return false;
        }
        return true;
    }

    /**
     * Formats the Risk C WARN line. Package-private so tests can pin
     * both the stable marker substring (for grep-based alerting
     * assertions) and the singular/plural "test class"/"test classes"
     * toggle on the FQN count without reaching into a log capture.
     */
    static String formatLocalDiscoveryIncompleteWarning(int selectedCount) {
        String classWord = selectedCount == 1 ? "test class" : "test classes";
        return LOCAL_DISCOVERY_INCOMPLETE_WARNING_MARKER + " of " + selectedCount
                + " " + classWord + " — see the discovery WARN above for the files "
                + "that failed to parse. Fix the parse error to recover a precise "
                + "selection, or set onDiscoveryIncomplete = 'full_suite' to escalate "
                + "(CI and STRICT already do).";
    }

    /**
     * Renders an {@link EscalationReason} as a short human-readable phrase
     * suitable for a lifecycle log line. Package-private so the log-shape
     * test can pin the exact wording; kept in one place so the summary and
     * any downstream "Running ALL tests" lines cannot drift into
     * contradictory phrasing.
     *
     * <p>{@link EscalationReason#NONE} is rejected: this helper is called
     * only on {@code runAll} results, and a {@code runAll=true + NONE}
     * combination is an engine bug rather than a log-formatting concern.
     * Throwing here ensures such a drift is loud (build fails) instead of
     * silently surfacing a placeholder phrase to CI.
     */
    static String describeEscalation(EscalationReason reason) {
        Objects.requireNonNull(reason, "reason");
        return switch (reason) {
            case RUN_ALL_ON_NON_JAVA_CHANGE ->
                    "onUnmappedFile=FULL_SUITE — non-Java or unmapped file in diff";
            case RUN_ALL_ON_EMPTY_CHANGESET ->
                    "onEmptyDiff=FULL_SUITE — no changed files detected";
            case RUN_ALL_IF_NO_MATCHES ->
                    "onDiscoveryEmpty=FULL_SUITE — no affected tests discovered";
            case RUN_ALL_ON_ALL_FILES_IGNORED ->
                    "onAllFilesIgnored=FULL_SUITE — every changed file matched ignorePaths";
            case RUN_ALL_ON_ALL_FILES_OUT_OF_SCOPE ->
                    "onAllFilesOutOfScope=FULL_SUITE — every changed file sat under out-of-scope dirs";
            case RUN_ALL_ON_DISCOVERY_INCOMPLETE ->
                    "onDiscoveryIncomplete=FULL_SUITE — discovery observed unparseable Java files, selection may be incomplete";
            case NONE -> throw new IllegalStateException(
                    "describeEscalation must not be called for EscalationReason.NONE; "
                            + "the engine should only produce NONE on non-runAll results");
        };
    }

    /** Cap on files listed per bucket in the {@code --explain} trace. */
    static final int EXPLAIN_SAMPLE_LIMIT = 10;

    /**
     * Cap on FQNs listed at lifecycle level per module in the
     * "Running N affected test classes" dispatch output. Chosen at 5
     * to keep the preview tight enough that a reviewer can read it
     * without scrolling yet large enough to sanity-check selection on
     * most MRs, which dispatch single digits of classes per module.
     * Larger dispatches still log every FQN at info level — this cap
     * exists only to keep the default lifecycle log bounded and well
     * under the 4 MiB GitHub Actions step cap that forced the
     * per-FQN demotion in pre-v1.9.18 versions.
     */
    static final int LIFECYCLE_FQN_PREVIEW_LIMIT = 5;

    /**
     * Renders the lifecycle-level dispatch preview for a single
     * module: the summary line, then up to
     * {@link #LIFECYCLE_FQN_PREVIEW_LIMIT} FQNs indented underneath,
     * and — when the dispatch exceeds the preview limit — a single
     * "… and N more (use --info for full list)" tail.
     *
     * <p>Package-private so
     * {@code AffectedTestTaskDispatchPreviewTest} can pin the format
     * without spinning up the Gradle runtime. The helper is pure over
     * its inputs, so the test treats it as a pure function and the
     * caller in {@link #executeTests} just pipes each returned line
     * to {@link org.gradle.api.logging.Logger#lifecycle(String)}.
     *
     * @param taskPath the Gradle task path the dispatch targets (for
     *                 example {@code "application:test"})
     * @param fqns     the validated FQNs being dispatched, in the
     *                 order they will be passed to Gradle; preserving
     *                 this order in the preview keeps the mental map
     *                 from "what did I change" to "what is running"
     *                 intact for the operator
     */
    static List<String> renderLifecycleDispatchPreview(String taskPath, List<String> fqns) {
        List<String> lines = new ArrayList<>(Math.min(fqns.size(), LIFECYCLE_FQN_PREVIEW_LIMIT) + 2);
        int size = fqns.size();
        String plural = size == 1 ? "" : "es";
        lines.add("  " + taskPath + " (" + size + " test class" + plural + ")");
        int preview = Math.min(size, LIFECYCLE_FQN_PREVIEW_LIMIT);
        for (int i = 0; i < preview; i++) {
            lines.add("    " + fqns.get(i));
        }
        if (size > preview) {
            lines.add("    … and " + (size - preview) + " more (use --info for full list)");
        }
        return lines;
    }

    /**
     * Renders the human-readable decision trace produced by
     * {@code affectedTest --explain}. Returned as a list of lines so the
     * caller can hand each line to {@link org.gradle.api.logging.Logger#lifecycle(String)}
     * (no format placeholders — the content is pre-rendered) and so tests
     * can pin the exact shape without the live logger.
     *
     * <p>Every section names the source of the decision so an operator
     * can see at a glance whether the action came from an explicit
     * {@code onXxx} setting or the mode default table (the v2 two-tier
     * resolver has no other sources).
     *
     * <p>Package-private so {@code AffectedTestTaskExplainFormatTest}
     * can assert the format without spinning up Gradle.
     *
     * @param moduleGroups ordered map of {@code :module:test} task
     *                     path → list of FQNs dispatched to that
     *                     module. Pass {@link Map#of()} when no
     *                     per-module breakdown applies (every
     *                     situation other than
     *                     {@link Situation#DISCOVERY_SUCCESS +
     *                     SELECTED}). The renderer skips the
     *                     "Modules:" block when the map is empty, so
     *                     the trace stays compact on non-selective
     *                     runs.
     */
    static List<String> renderExplainTrace(AffectedTestsConfig config, AffectedTestsResult result) {
        // 2-arg overload: preserves the signature every unit test
        // was written against before v2.2 added the per-module
        // breakdown. The module block is a DISCOVERY_SUCCESS-only
        // diagnostic that every non-dispatch unit test can safely
        // skip by threading an empty map through to the 3-arg
        // renderer.
        return renderExplainTrace(config, result, Map.of());
    }

    static List<String> renderExplainTrace(AffectedTestsConfig config,
                                           AffectedTestsResult result,
                                           Map<String, List<String>> moduleGroups) {
        List<String> lines = new ArrayList<>();
        lines.add("=== Affected Tests — decision trace (--explain) ===");
        lines.add("Base ref:        " + config.baseRef());
        // config.mode() is guaranteed non-null in v2 (defaults to AUTO when
        // the user doesn't set it), so we don't branch on null here — doing
        // so would only re-introduce the pre-v2 "unset" rendering we
        // deliberately dropped. effectiveMode() is also always non-null
        // (zero-config callers get the AUTO-detected value, identical to
        // what an explicit `mode = "auto"` would have resolved to).
        lines.add("Mode:            " + config.mode().name()
                + " (effective: " + config.effectiveMode().name() + ")");
        lines.add("Changed files:   " + result.changedFiles().size());

        Buckets buckets = result.buckets();
        lines.add("Buckets:");
        lines.add("  ignored         " + buckets.ignoredFiles().size());
        lines.add("  out-of-scope    " + buckets.outOfScopeFiles().size());
        // Pre-PR-1 of issue #76 these read "production .java" /
        // "test .java" because only Java was mapped. PR #1 widens
        // the scope to .java + .kt; the labels drop the extension
        // qualifier rather than enumerate both, so PR #3 (full
        // Kotlin AST) and any future Groovy / Scala extension
        // doesn't have to re-touch this format.
        lines.add("  production      " + buckets.productionFiles().size());
        lines.add("  test            " + buckets.testFiles().size());
        lines.add("  unmapped        " + buckets.unmappedFiles().size());

        appendSample(lines, "ignored",      buckets.ignoredFiles());
        appendSample(lines, "out-of-scope", buckets.outOfScopeFiles());
        appendSample(lines, "production",   buckets.productionFiles());
        appendSample(lines, "test",         buckets.testFiles());
        appendSample(lines, "unmapped",     buckets.unmappedFiles());

        ActionSource source = config.actionSourceFor(result.situation());
        lines.add("Situation:       " + result.situation().name());
        lines.add("Action:          " + result.action().name()
                + " (source: " + describeSource(source) + ")");

        String outcome;
        if (result.runAll()) {
            outcome = "FULL_SUITE — " + describeEscalation(result.escalationReason());
        } else if (result.skipped()) {
            outcome = "SKIPPED — no tests will run";
        } else if (result.action() == Action.SELECTED) {
            outcome = "SELECTED — " + result.testClassFqns().size()
                    + " test class(es) will run";
        } else {
            outcome = result.action().name();
        }
        lines.add("Outcome:         " + outcome);

        // Issue #42: the discovery profile is the data adopters need
        // to answer "did parallel actually help on my workload?" and
        // "which strategy is the wall-time hog?". We render a
        // breakdown only when discovery actually ran (test-only fast
        // paths, EMPTY_DIFF, etc., produce DiscoveryProfile.empty()
        // which we deliberately skip — no point printing "0ms total"
        // for a situation that didn't dispatch any work).
        appendDiscoveryProfile(lines, result);

        // Diagnostic hint: pick the hint that actually matches the
        // situation the operator is staring at. Earlier versions
        // unconditionally printed the out-of-scope hint on every
        // mapper-touching situation, which meant a DISCOVERY_EMPTY run
        // (no test mapped to the changed prod class) or
        // DISCOVERY_INCOMPLETE run (parse failure dropped a file from
        // the mapper) would show OOS advice that had nothing to do
        // with the actual problem. v2.2 splits the hint into three
        // targeted branches — see {@link #appendSituationHint} for the
        // full routing.
        appendSituationHint(lines, config, result);

        // Phase 2 PR #1 of issue #76 introduced the path-derived
        // Kotlin mapping hint; PR #4 extends it to the four pinned
        // AST-driven --explain strings (AST-mapped FQN, parse
        // failure with embeddable version, path-vs-package mismatch,
        // embeddable load failure). The dispatcher routes between
        // them based on the per-engine {@link KotlinDiagnostics}
        // and the buckets — see method Javadoc for the full table.
        appendKotlinMappingHints(lines, config, result);

        // Issue #132 — headerEdges augmentation block. Skipped
        // entirely when augmentation didn't fire (e.g. the strategy
        // was disabled, EMPTY_DIFF, or no header-edge target was
        // resolvable). The renderer relies on the engine threading
        // a populated {@link HeaderEdgesStrategy.AugmentationResult}
        // even on no-op paths, so the conditional here is the only
        // "should we print this block?" gate the renderer needs.
        appendHeaderEdgesBlock(lines, result);

        // Per-module dispatch preview — populated only for
        // SELECTED runs so the "what tasks will Gradle actually
        // kick off?" question can be answered directly from the
        // explain trace instead of a dry-run dispatch. On every
        // other outcome the map is empty and we skip the block
        // entirely to keep the trace compact. Shares the same
        // {@link #groupFqnsByModule} as the real dispatch path so a
        // SELECTED --explain line can never contradict what the
        // next non-explain run will actually execute.
        appendModulesBlock(lines, moduleGroups);

        // The full action matrix is cheap to print (five rows) and
        // invaluable for debugging "why did my explicit setting not
        // win?" — so we always include it, not only on ambiguous
        // branches. Rows are rendered in a stable order matching the
        // Situation javadoc's evaluation order so greps/diffs stay
        // stable across runs.
        lines.add("Action matrix (situation → action [source]):");
        for (Situation s : situationOrder()) {
            ActionSource rowSource = config.actionSourceFor(s);
            lines.add(String.format(Locale.ROOT, "  %-24s %s [%s]",
                    s.name(), config.actionFor(s).name(), describeSource(rowSource)));
        }
        lines.add("=== end --explain ===");
        return lines;
    }

    /**
     * Routes to the situation-specific hint block for the
     * {@code --explain} trace. Kept package-private so
     * {@code AffectedTestTaskExplainFormatTest} can pin the exact
     * conditions without spinning up Gradle.
     *
     * <p>Only the three mapper-touching situations produce hints in
     * the v2.2+ trace: {@link Situation#DISCOVERY_SUCCESS} carries the
     * original "out-of-scope configured but nothing matched"
     * misconfiguration tell; {@link Situation#DISCOVERY_EMPTY} calls
     * out the "mapped 0 tests" case with naming-suffix advice; and
     * {@link Situation#DISCOVERY_INCOMPLETE} flags the parse-failure
     * path so an operator doesn't silently accept a partial selection.
     * The other four situations ({@link Situation#EMPTY_DIFF},
     * {@link Situation#ALL_FILES_IGNORED},
     * {@link Situation#ALL_FILES_OUT_OF_SCOPE}) reach their outcome
     * for reasons none of these hints can usefully add to — so we
     * stay silent and let the {@code Outcome:} line speak for itself.
     *
     * <p>{@link Situation#UNMAPPED_FILE} <em>does</em> get a hint as
     * of #47: we recognise common polyglot extensions (Kotlin, Groovy,
     * Scala) in the unmapped bucket and surface the Java-only mapping
     * limitation explicitly, so adopters who land their first Kotlin
     * file don't have to read the source to find out why every MR is
     * suddenly running the full suite.
     */
    static void appendSituationHint(List<String> lines,
                                    AffectedTestsConfig config,
                                    AffectedTestsResult result) {
        // Note on the removed `changedFiles().isEmpty()` guard that
        // lived here pre-v2.2.1: the three situations the switch routes
        // (DISCOVERY_SUCCESS / DISCOVERY_EMPTY / DISCOVERY_INCOMPLETE)
        // all definitionally require at least one changed file to
        // reach them — EMPTY_DIFF is the "no files" situation and it
        // falls through the default branch. The guard was unreachable
        // in production and gave a misleading impression that
        // changed-file-count was part of the hint gate, so deleting
        // it keeps the dispatch contract legible.
        switch (result.situation()) {
            case DISCOVERY_SUCCESS -> {
                appendOutOfScopeMisconfigHint(lines, config, result);
                // Independent diagnostic from the OOS-misconfig hint —
                // both can fire on the same DISCOVERY_SUCCESS run when
                // naming over-selects across packages and the
                // out-of-scope dirs are silently misconfigured. They
                // describe orthogonal misconfigurations, so the
                // operator gets both signals; the renderer ordering
                // (OOS first, naming second) follows decreasing
                // signal-to-noise — OOS misconfig is more likely to
                // explain "why is the suite running at all" while the
                // naming hint refines "which tests in particular look
                // questionable".
                appendNamingCrossPackageHint(lines, result);
            }
            case DISCOVERY_EMPTY       -> appendDiscoveryEmptyHint(lines, config, result);
            case DISCOVERY_INCOMPLETE  -> appendDiscoveryIncompleteHint(lines, result);
            case UNMAPPED_FILE         -> appendUnmappedFileHint(lines, result);
            default                    -> {
                // No hint for EMPTY_DIFF / ALL_FILES_IGNORED /
                // ALL_FILES_OUT_OF_SCOPE — see method-level javadoc
                // for the per-situation rationale.
            }
        }
    }

    /**
     * Fires on {@link Situation#DISCOVERY_SUCCESS} when
     * {@code outOfScopeTestDirs} / {@code outOfScopeSourceDirs} were
     * configured but no file in the diff matched any of them. The
     * heuristic catches the silent-misconfig case that lands
     * OOS-shaped MRs (docs-only, tooling-only) into
     * DISCOVERY_SUCCESS-instead-of-SKIPPED: wrong path, wrong glob
     * shape, trailing-slash typo. Suppressed on runs where the OOS
     * bucket actually non-empty — the configuration clearly works on
     * this diff, so firing would only train reviewers to ignore the
     * hint.
     */
    private static void appendOutOfScopeMisconfigHint(List<String> lines,
                                                      AffectedTestsConfig config,
                                                      AffectedTestsResult result) {
        if (!result.buckets().outOfScopeFiles().isEmpty()) {
            return;
        }
        int testEntries = config.outOfScopeTestDirs().size();
        int sourceEntries = config.outOfScopeSourceDirs().size();
        int totalEntries = testEntries + sourceEntries;
        if (totalEntries == 0) {
            return;
        }

        List<String> configuredKnobs = new ArrayList<>(2);
        if (testEntries > 0) {
            configuredKnobs.add("outOfScopeTestDirs");
        }
        if (sourceEntries > 0) {
            configuredKnobs.add("outOfScopeSourceDirs");
        }
        String knobs = String.join(" / ", configuredKnobs);
        String verb = configuredKnobs.size() == 1 ? "is" : "are";
        String entryWord = totalEntries == 1 ? "entry" : "entries";

        lines.add("Hint:            " + knobs + " " + verb + " configured ("
                + totalEntries + " " + entryWord + ") but no file in the diff matched.");
        lines.add("                 Values are directory prefixes "
                + "(e.g. 'api-test/src/test/java') or globs (e.g. 'api-test/**').");
    }

    /**
     * Fires on {@link Situation#DISCOVERY_SUCCESS} when
     * {@link NamingConventionStrategy} added at least one test whose
     * package differs from the changed production class's package. The
     * naming strategy is intentionally package-agnostic (issue #40
     * rationale: parallel test trees and Cucumber-shape harnesses
     * legitimately keep tests outside the SUT's package, so flipping
     * to a strict same-package match would silently under-select), so
     * over-selection is the policy's known failure mode; this hint
     * surfaces it without flipping the policy. Quiet when there are
     * no cross-package matches — the silent-success path stays silent.
     *
     * <p>Caps the listed pairs at {@link #EXPLAIN_SAMPLE_LIMIT} to
     * keep the trace readable on monorepos where dozens of FQNs may
     * over-select; an "(+N more)" suffix preserves the total so an
     * operator can decide whether to dig deeper.
     */
    private static void appendNamingCrossPackageHint(List<String> lines, AffectedTestsResult result) {
        Map<String, Set<String>> matches = result.namingCrossPackageMatches();
        if (matches.isEmpty()) {
            return;
        }
        int total = matches.values().stream().mapToInt(Set::size).sum();
        String pairWord = total == 1 ? "test" : "tests";
        lines.add("Hint:            naming strategy selected " + total
                + " " + pairWord + " whose package differs from the "
                + "changed production class's package — possible "
                + "cross-package simple-name collision.");
        int rendered = 0;
        for (var entry : matches.entrySet()) {
            for (String testFqn : entry.getValue()) {
                if (rendered >= EXPLAIN_SAMPLE_LIMIT) {
                    break;
                }
                lines.add("                  * " + entry.getKey() + " → " + testFqn);
                rendered++;
            }
            if (rendered >= EXPLAIN_SAMPLE_LIMIT) {
                break;
            }
        }
        if (total > rendered) {
            lines.add("                  … (+" + (total - rendered)
                    + " more — set --info to log every naming match)");
        }
        lines.add("                 If this is a false positive (different "
                + "production class, same simple name), the test is still "
                + "selected — over-select is the documented trade-off for "
                + "naming. Move the test into the same package as the SUT, "
                + "or rename one of the colliding production classes, to "
                + "make the match precise.");
    }

    /**
     * Fires on {@link Situation#DISCOVERY_EMPTY} — the engine mapped
     * production .java changes but no test class matched any strategy.
     * v2.1 printed the OOS hint here which was actively misleading
     * because the OOS bucket by definition never influenced the
     * outcome; v2.2 swaps in the three things that actually produce
     * an empty discovery set: test-file naming mismatches the
     * configured suffix list, the test lives outside the configured
     * {@code testDirs}, or the prod class genuinely has no test
     * coverage yet. We enumerate the first two with the operator's
     * actual config values so the hint is self-checking.
     */
    private static void appendDiscoveryEmptyHint(List<String> lines,
                                                 AffectedTestsConfig config,
                                                 AffectedTestsResult result) {
        int prodFileCount = result.buckets().productionFiles().size();
        String fileWord = prodFileCount == 1 ? "file" : "files";
        lines.add("Hint:            discovery mapped 0 test classes to the "
                + prodFileCount + " changed production " + fileWord + ".");
        lines.add("                 Common causes:");
        lines.add("                  * test name does not match testSuffixes "
                + formatInlineList(config.testSuffixes())
                + " (e.g. Foo.java → FooTest.java / FooIT.java)");
        lines.add("                  * test lives outside testDirs "
                + formatInlineList(config.testDirs()));
        lines.add("                  * the production class has no test coverage yet");
    }

    /**
     * Fires on {@link Situation#DISCOVERY_INCOMPLETE} — one or more
     * Java files in the diff failed to parse, so the mapper ran with
     * missing inputs. Two shapes reach this hint:
     *
     * <ul>
     *   <li>{@link Action#SELECTED} — LOCAL-mode default. The discovered
     *       selection is definitionally partial, and the hint names
     *       that risk explicitly plus the escalation knob an operator
     *       can flip to move off the partial-selection default. Pairs
     *       with the lifecycle WARN in
     *       {@link #warnIfLocalDiscoveryIncompleteSelected}.</li>
     *   <li>{@link Action#FULL_SUITE} — CI/STRICT default, or an
     *       explicit {@code onDiscoveryIncomplete='full_suite'}
     *       override. The whole suite runs, so "partial selection"
     *       wording is actively wrong and "let onDiscoveryIncomplete
     *       escalate" is circular — escalation already happened. We
     *       render a trimmed hint that just names the parse failure
     *       and the precise-selection follow-up.</li>
     * </ul>
     *
     * <p>We don't count "parse failures" directly because the engine
     * doesn't surface that number today; an operator who wants the
     * exact file list reads the INFO-level engine log. The hint stays
     * action-shape-agnostic on the file count so it can't drift into
     * "0 files failed to parse" wording on edge cases.
     */
    private static void appendDiscoveryIncompleteHint(List<String> lines,
                                                      AffectedTestsResult result) {
        // Every branch opens with the same root-cause line — the parse
        // failure is the operator-actionable fact regardless of what
        // the mode chose to do about it. Only the follow-on guidance
        // differs per resolved Action.
        lines.add("Hint:            one or more Java files in the diff failed to parse, "
                + "so discovery ran with missing inputs.");
        switch (result.action()) {
            case SELECTED -> lines.add("                 The resolved selection is "
                    + "necessarily partial — fix the parse error to recover a precise "
                    + "selection, or set onDiscoveryIncomplete = 'full_suite' to "
                    + "escalate (CI and STRICT modes already do).");
            case FULL_SUITE -> lines.add("                 Fix the parse error to "
                    + "recover a precise selection on future runs (until then the "
                    + "resolved action above is the safe fallback).");
            // Reachable only via an explicit `onDiscoveryIncomplete =
            // 'skipped'` DSL override — unusual but legal. SKIPPED is
            // the OPPOSITE of safe here (we neither narrowed nor
            // escalated; we just didn't run tests), so the hint must
            // not call it "the safe fallback" the way FULL_SUITE does.
            // We name the opt-in knob and offer the two sane exits
            // (fix the parse error, or flip the knob to 'full_suite')
            // without implying the current state is acceptable.
            case SKIPPED -> lines.add("                 onDiscoveryIncomplete = "
                    + "'skipped' meant no tests ran for this diff — fix the parse "
                    + "error to restore coverage, or set onDiscoveryIncomplete = "
                    + "'full_suite' if silently skipping a partial-parse diff is "
                    + "not the intended policy.");
        }
    }

    /**
     * Fires on {@link Situation#UNMAPPED_FILE} runs whose unmapped
     * bucket contains files in well-known polyglot JVM extensions
     * the plugin still cannot map ({@code .kts}, {@code .groovy},
     * {@code .scala}). Phase 2 PR #4 of issue #76 demotes the
     * pre-PR-4 framing — which read like an "AST-driven strategies
     * skipped" rollout signal pointing at the Java-only-mapping
     * limitation — to a single non-class-bearing-change line per
     * extension, matching the new reality after Kotlin AST
     * participation became default-on:
     *
     * <pre>{@code
     * Hint:            Non-Java/Kotlin source (.scala) mapped via filename only; AST-driven strategies skipped (separate issue).
     * }</pre>
     *
     * <p>Stable in its full literal form across PR #4+ — adopters
     * grepping for the older "the plugin currently maps only .java"
     * wording will hit nothing and need to migrate, which is
     * exactly the rollout signal we want at version-bump time.
     *
     * <p>Silent on UNMAPPED_FILE runs whose unmapped bucket is
     * purely config / asset / yaml — those are the "expected"
     * UNMAPPED_FILE shape and the {@code Outcome:} line already
     * explains them.
     */
    private static void appendUnmappedFileHint(List<String> lines,
                                               AffectedTestsResult result) {
        java.util.Set<String> polyglotExts = new java.util.LinkedHashSet<>();
        for (String path : result.buckets().unmappedFiles()) {
            String ext = polyglotExtensionOf(path);
            if (ext != null) {
                polyglotExts.add(ext);
            }
        }
        if (polyglotExts.isEmpty()) {
            return;
        }
        // Emit one demoted line per distinct extension so adopters
        // hitting more than one polyglot family in a single MR see
        // each one named explicitly. Insertion order is preserved
        // (LinkedHashSet) so the hint output is byte-stable for any
        // given diff shape.
        for (String ext : polyglotExts) {
            lines.add("Hint:            Non-Java/Kotlin source ("
                    + ext + ") mapped via filename only; "
                    + "AST-driven strategies skipped (separate issue).");
        }
    }

    /**
     * Renders the Kotlin-specific {@code --explain} hint block.
     * Routes between the three rollout-era strings based on
     * {@link AffectedTestsConfig#kotlinEnabled()} and the per-engine
     * {@link io.affectedtests.core.discovery.KotlinDiagnostics}.
     *
     * <h4>Phase 2 PR #4 (default-on) — four pinned AST-driven strings</h4>
     *
     * <p>When {@code config.kotlinEnabled()} is true and the parser
     * actually ran on at least one {@code .kt} file in the run, one
     * or more of these fire (from {@link io.affectedtests.core.discovery.KotlinDiagnostics}):
     *
     * <ul>
     *   <li>{@code Kotlin embeddable failed to load: {cause}. Treating
     *       .kt files as unparseable for this run.} — fires once when
     *       the {@code KotlinCoreEnvironment} bootstrap threw. The
     *       cause is the underlying exception's message, sanitised
     *       for log forgery.</li>
     *   <li>{@code Kotlin file failed to parse with embeddable
     *       {version}; counted into DISCOVERY_INCOMPLETE.} — fires
     *       once when at least one {@code .kt} file returned
     *       {@code null} from the parser. The WARN line at the
     *       failure site has already named the specific file; this
     *       hint is the per-run summary.</li>
     *   <li>{@code Kotlin file {path} declares package {parsed} but
     *       path-derives to {path-derived}; AST-driven strategies use
     *       the declared package, naming strategy uses the path-
     *       derived FQN.} — fires once per distinct mismatched file
     *       (capped at {@link
     *       io.affectedtests.core.discovery.KotlinDiagnostics#SAMPLE_LIMIT}).
     *       Surfaces the under-selection risk: the AST and the
     *       diff-side mapper disagree on the FQN.</li>
     *   <li>{@code Kotlin source AST-mapped to FQN {fqn}.} — fires
     *       once per distinct AST-mapped FQN (same cap), oldest-first
     *       insertion order. A "+N more" tail when truncated. The
     *       absence of every other Kotlin hint while this fires is
     *       the "happy path" rollout signal an adopter looks for.</li>
     * </ul>
     *
     * <h4>Phase 2 PR #1 — path-derived fallback strings</h4>
     *
     * <p>When {@code config.kotlinEnabled()} is false (adopter
     * explicitly disabled in DSL, or the engine hit a Kotlin-free
     * fast path), the original PR #1 hints route the explanation:
     *
     * <ul>
     *   <li>{@code Kotlin source mapped via filename only;
     *       AST-driven strategies skipped (issue #76).} — fires when
     *       any {@code .kt} file landed in
     *       {@link io.affectedtests.core.AffectedTestsResult.Buckets#productionFiles()}
     *       or
     *       {@link io.affectedtests.core.AffectedTestsResult.Buckets#testFiles()}.
     *       Tells adopters that the file was selected via path-derived
     *       FQN (NamingConventionStrategy + UsageStrategy tier 1
     *       import lookup against the synthetic {@code <basename>Kt}
     *       class), not via AST.</li>
     * </ul>
     *
     * <h4>Path-routing string — fires on either rollout phase</h4>
     *
     * <ul>
     *   <li>{@code Kotlin source unmapped (no matching source/test
     *       root); routed to unmapped bucket.} — fires when any
     *       {@code .kt} file landed in
     *       {@link io.affectedtests.core.AffectedTestsResult.Buckets#unmappedFiles()}.
     *       Distinct from the older polyglot hint
     *       ({@link #appendUnmappedFileHint}) which still fires for
     *       Groovy / Scala / {@code .kts} — those remain Java-only
     *       mapped pending separate follow-ups. The string narrows
     *       the diagnosis: a {@code .kt} in unmapped means the file
     *       path didn't match any configured source / test root, not
     *       that the plugin can't map Kotlin extensions.</li>
     * </ul>
     */
    static void appendKotlinMappingHints(List<String> lines,
                                         AffectedTestsConfig config,
                                         AffectedTestsResult result) {
        Buckets buckets = result.buckets();
        boolean kotlinMapped = anyKotlin(buckets.productionFiles())
                || anyKotlin(buckets.testFiles());
        boolean kotlinUnmapped = anyKotlin(buckets.unmappedFiles());

        // Path-routing hint fires in either rollout phase: it tells
        // adopters their .kt sits outside any configured source / test
        // root, which is independent of whether the AST parser is
        // engaged.
        if (kotlinUnmapped) {
            lines.add("Hint:            Kotlin source unmapped"
                    + " (no matching source/test root); routed to unmapped bucket.");
        }

        io.affectedtests.core.discovery.KotlinDiagnostics diag = result.kotlinDiagnostics();
        if (config.kotlinEnabled() && diag != null && !diag.isEmpty()) {
            appendKotlinAstHints(lines, diag);
            return;
        }

        // Fallback path: PR #1 hint preserved verbatim for adopters
        // who flipped {@code kotlinEnabled = false} in the DSL after
        // PR #4 (the documented escape hatch — see README known
        // limitations section).
        if (kotlinMapped) {
            lines.add("Hint:            Kotlin source mapped via filename only;"
                    + " AST-driven strategies skipped (issue #76).");
        }
    }

    /**
     * Emits the four pinned AST-driven Kotlin --explain strings
     * (issue #76 PR #4) in the order operators read them most
     * naturally: the load-failure case first (because it dominates
     * every subsequent line if it fires), then per-run summaries
     * (parse failures, mismatches), then the per-FQN AST-mapped
     * sample. Each line is rendered verbatim against the pinned
     * template — the substitution slots are sanitised at the
     * boundary so an attacker-committable filename or package name
     * cannot forge a fake plugin-status log line.
     */
    private static void appendKotlinAstHints(
            List<String> lines,
            io.affectedtests.core.discovery.KotlinDiagnostics diag) {
        String loadFailure = diag.embeddableLoadFailureCause();
        if (loadFailure != null && !loadFailure.isEmpty()) {
            lines.add("Hint:            Kotlin embeddable failed to load: "
                    + LogSanitizer.sanitize(loadFailure)
                    + ". Treating .kt files as unparseable for this run.");
        }
        if (diag.parseFailureCount() > 0) {
            lines.add("Hint:            Kotlin file failed to parse with embeddable "
                    + io.affectedtests.core.discovery.KotlinDiagnostics.EMBEDDABLE_VERSION
                    + "; counted into DISCOVERY_INCOMPLETE.");
        }
        if (diag.pathPackageMismatchCount() > 0) {
            int rendered = 0;
            for (var sample : diag.mismatchSamples()) {
                if (rendered >= EXPLAIN_SAMPLE_LIMIT) break;
                lines.add("Hint:            Kotlin file "
                        + LogSanitizer.sanitize(String.valueOf(sample.file()))
                        + " declares package "
                        + LogSanitizer.sanitize(sample.parsedPackage())
                        + " but path-derives to "
                        + LogSanitizer.sanitize(sample.pathDerivedPackage())
                        + "; AST-driven strategies use the declared package, "
                        + "naming strategy uses the path-derived FQN.");
                rendered++;
            }
            // Only emit the "… and N more" tail when at least one
            // sample line preceded it. With the post-review null-input
            // guards in {@link KotlinDiagnostics#recordPathPackageMismatch}
            // counter and sample size stay in lockstep, but the guard
            // protects against a future refactor that loosens that
            // invariant: a bare "… and 1 more" with no sample line
            // above it is an unhelpful trace that suggests the
            // renderer is broken rather than that the data was
            // malformed.
            if (rendered > 0 && diag.pathPackageMismatchCount() > rendered) {
                lines.add("Hint:            … and "
                        + (diag.pathPackageMismatchCount() - rendered)
                        + " more Kotlin file(s) with path/package mismatch.");
            }
        }
        if (diag.astMappedCount() > 0) {
            int rendered = 0;
            for (String fqn : diag.astMappedFqnSamples()) {
                if (rendered >= EXPLAIN_SAMPLE_LIMIT) break;
                lines.add("Hint:            Kotlin source AST-mapped to FQN "
                        + LogSanitizer.sanitize(fqn) + ".");
                rendered++;
            }
            if (rendered > 0 && diag.astMappedCount() > rendered) {
                lines.add("Hint:            … and "
                        + (diag.astMappedCount() - rendered)
                        + " more Kotlin source(s) AST-mapped this run.");
            }
        }
    }

    private static boolean anyKotlin(java.util.Collection<String> paths) {
        if (paths == null || paths.isEmpty()) return false;
        for (String path : paths) {
            // No `!endsWith(".gradle.kts")` carve-out needed here:
            // `.gradle.kts` ends in `.kts` (not `.kt`), so it can
            // never reach this branch. The `.kts` carve-out lives
            // in {@link #polyglotExtensionOf} where it is actually
            // load-bearing.
            if (path.toLowerCase(Locale.ROOT).endsWith(".kt")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the lowercased polyglot extension (e.g. {@code .kt})
     * for paths whose extension matches a known JVM-language file
     * shape, or {@code null} for everything else (including
     * {@code .java}, {@code .yml}, {@code .properties}, etc.).
     * Centralised here so the JSON renderer and tests share the
     * same classifier without copy-pasting the suffix list.
     *
     * <p>Gradle Kotlin DSL build scripts ({@code build.gradle.kts},
     * {@code settings.gradle.kts}) are deliberately excluded: they
     * are Kotlin syntactically but configuration files semantically,
     * and firing the "your Kotlin sources are unmapped" hint when
     * the only Kotlin file in the diff is {@code build.gradle.kts}
     * would be misleading (it is unmapped because no strategy maps
     * build scripts, not because of the .kt-file gap issue #47
     * tracks).
     */
    private static String polyglotExtensionOf(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".gradle.kts")) return null;
        // `.kt` was deliberately dropped in PR #1 of issue #76:
        // post-PR-1 the plugin maps Kotlin sources via
        // path-derived FQN (PathToClassMapper), so a `.kt` in the
        // unmapped bucket means the file sits outside any
        // configured source / test root — not that the plugin
        // can't see Kotlin extensions. The phase 1 hint's text
        // ("the plugin currently maps only .java") would be
        // factually wrong for `.kt`, and would contradict the new
        // `Kotlin source unmapped (no matching source/test root)`
        // hint emitted by {@link #appendKotlinMappingHints}.
        // `.kts`, `.groovy`, `.gvy`, `.scala`, `.sc` remain
        // Java-only mapped; they keep the original phase-1 hint
        // text and the link to issue #47.
        if (lower.endsWith(".kts"))    return ".kts";
        if (lower.endsWith(".groovy")) return ".groovy";
        if (lower.endsWith(".gvy"))    return ".gvy";
        if (lower.endsWith(".scala"))  return ".scala";
        if (lower.endsWith(".sc"))     return ".sc";
        return null;
    }

    private static String formatInlineList(List<String> items) {
        if (items.isEmpty()) {
            return "[]";
        }
        return items.stream().collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * Renders the discovery profile (issue #42) so adopters can see
     * directly from {@code --explain} how dispatch executed and which
     * strategy is the dominant wall-time consumer. Format:
     * <pre>
     * Discovery:       parallel (4 threads, 12.3ms total)
     *   naming     :   0.8ms (2 tests)
     *   usage      :   4.5ms (5 tests)
     *   impl       :   2.1ms (1 test)
     *   transitive :  11.9ms (3 tests, dominant)
     * </pre>
     *
     * <p>Empty profiles (test-only fast path, EMPTY_DIFF, etc.) are
     * skipped on purpose — printing "0ms total" would just add a
     * meaningless line for situations that didn't run discovery.
     *
     * <p>Sub-millisecond timings round to {@code 0ms} in the output;
     * we intentionally don't print microseconds because a) the
     * dominant signal at that scale is JVM warm-up, not strategy
     * cost, and b) test snapshots that pin millisecond-rounded
     * output won't churn on every run. The dominant-strategy hint
     * is delegated to
     * {@link DiscoveryProfile#dominantStrategy()} so the record and
     * the renderer agree on what "dominant" means; the renderer
     * adds the trace-only floor (multi-strategy + ≥1ms total) on
     * top of that single source of truth.
     *
     * <p>Package-private so {@code AffectedTestTaskExplainFormatTest}
     * can pin both the empty-profile fast path and the rendered
     * shape without spinning up Gradle.
     */
    static void appendDiscoveryProfile(List<String> lines, AffectedTestsResult result) {
        DiscoveryProfile profile = result.discoveryProfile();
        if (profile == null
                || profile.perStrategyWallTime().isEmpty()
                || profile.totalDiscoveryWallTime().isZero()) {
            return;
        }
        String header = profile.parallelEnabled()
                ? "parallel (" + profile.concurrencyLevel() + " threads, "
                        + profile.totalDiscoveryWallTime().toMillis() + "ms total)"
                : "serial (" + profile.totalDiscoveryWallTime().toMillis() + "ms total)";
        lines.add("Discovery:       " + header);

        // Trace-only floor: only flag a dominant strategy when the
        // total wall time is at least 1ms. Anything finer is JVM-
        // warmup noise that shouldn't drive the operator's attention.
        // {@link DiscoveryProfile#dominantStrategy()} already
        // enforces "more than one strategy ran" and "strictly greater
        // than runner-up" — we just gate on the floor here.
        String dominant = null;
        if (profile.totalDiscoveryWallTime().toMillis() >= 1) {
            dominant = profile.dominantStrategy();
        }

        for (Map.Entry<String, java.time.Duration> e : profile.perStrategyWallTime().entrySet()) {
            String strategyName = e.getKey();
            long ms = e.getValue().toMillis();
            int testCount = profile.perStrategyTestCount().getOrDefault(strategyName, 0);
            String testWord = testCount == 1 ? "test" : "tests";
            String marker = strategyName.equals(dominant) ? ", dominant" : "";
            lines.add(String.format(Locale.ROOT, "  %-11s: %4dms (%d %s%s)",
                    strategyName, ms, testCount, testWord, marker));
        }
    }

    /**
     * Renders the "Modules:" section of the {@code --explain} trace.
     * No-op when the map is empty — every non-SELECTED run threads an
     * empty map through so those traces stay compact. On SELECTED
     * runs we list each {@code :module:test} target with its class
     * count, then up to
     * {@link #LIFECYCLE_FQN_PREVIEW_LIMIT} FQNs per module, matching
     * the same preview cap the actual dispatch path uses — so an
     * operator comparing an {@code --explain} trace against a
     * subsequent non-explain run sees the same preview shape.
     *
     * <p>Package-private so
     * {@code AffectedTestTaskExplainFormatTest} can pin both the
     * empty-map fast path and the preview shape without spinning up
     * Gradle.
     */
    static void appendModulesBlock(List<String> lines, Map<String, List<String>> moduleGroups) {
        if (moduleGroups == null || moduleGroups.isEmpty()) {
            return;
        }
        int totalFqns = moduleGroups.values().stream().mapToInt(List::size).sum();
        String moduleWord = moduleGroups.size() == 1 ? "module" : "modules";
        String classWord = totalFqns == 1 ? "class" : "classes";
        lines.add("Modules:         " + moduleGroups.size() + " " + moduleWord + ", "
                + totalFqns + " test " + classWord + " to dispatch");
        for (Map.Entry<String, List<String>> entry : moduleGroups.entrySet()) {
            // Contract: callers pass the key through
            // {@link #testTaskPath(String)}, which guarantees a
            // canonical ":module:test" / ":test" shape with a leading
            // colon. We assert rather than silently re-normalise so
            // any future caller that forgets the helper breaks loudly
            // in tests instead of producing a ":x" / "x" mixed trace.
            String taskPath = entry.getKey();
            List<String> fqns = entry.getValue();
            assert taskPath.startsWith(":") : "appendModulesBlock expects testTaskPath-shaped "
                    + "keys (leading ':'); got '" + taskPath + "' — route through "
                    + "AffectedTestTask#testTaskPath before inserting into the map";
            int size = fqns.size();
            String rowClassWord = size == 1 ? "class" : "classes";
            lines.add("  " + taskPath + " (" + size + " test " + rowClassWord + ")");
            int preview = Math.min(size, LIFECYCLE_FQN_PREVIEW_LIMIT);
            for (int i = 0; i < preview; i++) {
                lines.add("    " + fqns.get(i));
            }
            if (size > preview) {
                lines.add("    … and " + (size - preview) + " more (use --info for full list)");
            }
        }
    }

    /**
     * Issue&nbsp;#132 — renders the headerEdges augmentation block
     * of the {@code --explain} trace. The block reports four things
     * the operator needs to answer "why did this MR's class set
     * grow?":
     *
     * <ul>
     *   <li>The augmented-set total ({@code +N classes from header
     *       edges}) — the headline number that tells the operator
     *       whether the strategy contributed at all.</li>
     *   <li>A per-category preview of every {@link
     *       io.affectedtests.core.discovery.HeaderEdgesStrategy.EdgeStatus#ADDED}
     *       edge, capped by {@link #EXPLAIN_SAMPLE_LIMIT} so a
     *       framework-y diff can't spam the trace.</li>
     *   <li>The list of types whose downward impl-walk was
     *       suppressed by the sibling cap, with the cap value
     *       inline so the operator doesn't have to cross-reference
     *       the config knob.</li>
     *   <li>A summary count of {@code IGNORED_BY_GLOB} /
     *       {@code IGNORED_BY_CATEGORY} / {@code UNRESOLVED} edges,
     *       so an unexpectedly small augmentation can be traced back
     *       to a too-aggressive ignore-glob list or a missing
     *       category opt-in.</li>
     * </ul>
     *
     * <p>The whole block is skipped when augmentation didn't fire
     * (no augmented types, no suppressed types, and no edges at
     * all) — keeps the trace compact on the common case where the
     * strategy adds nothing new.
     */
    static void appendHeaderEdgesBlock(List<String> lines, AffectedTestsResult result) {
        io.affectedtests.core.discovery.HeaderEdgesStrategy.AugmentationResult he =
                result.headerEdgesAugmentation();
        if (he == null) return;
        int added = he.augmentedTypes().size();
        int suppressed = he.suppressedFromImplWalk().size();
        List<io.affectedtests.core.discovery.HeaderEdgesStrategy.HeaderEdge> edges = he.edges();
        if (edges.isEmpty() && suppressed == 0) {
            return;
        }
        long addedEdges = edges.stream()
                .filter(e -> e.status() == io.affectedtests.core.discovery.HeaderEdgesStrategy.EdgeStatus.ADDED)
                .count();
        long byGlob = edges.stream()
                .filter(e -> e.status() == io.affectedtests.core.discovery.HeaderEdgesStrategy.EdgeStatus.IGNORED_BY_GLOB)
                .count();
        long byCategory = edges.stream()
                .filter(e -> e.status() == io.affectedtests.core.discovery.HeaderEdgesStrategy.EdgeStatus.IGNORED_BY_CATEGORY)
                .count();
        long unresolved = edges.stream()
                .filter(e -> e.status() == io.affectedtests.core.discovery.HeaderEdgesStrategy.EdgeStatus.UNRESOLVED)
                .count();
        long siblingCap = edges.stream()
                .filter(e -> e.status() == io.affectedtests.core.discovery.HeaderEdgesStrategy.EdgeStatus.SKIPPED_SIBLING_CAP)
                .count();

        lines.add("Header edges (issue #132):");
        lines.add("  augmented:      " + added + " class(es) total, "
                + addedEdges + " edge(s) added");
        lines.add("  filtered:       "
                + byGlob + " by ignore-glob, "
                + byCategory + " by category opt-out, "
                + unresolved + " unresolved, "
                + siblingCap + " skipped by sibling-cap");

        if (addedEdges > 0) {
            lines.add("  added edges (sample):");
            int shown = 0;
            for (var edge : edges) {
                if (edge.status() != io.affectedtests.core.discovery.HeaderEdgesStrategy.EdgeStatus.ADDED) continue;
                if (shown >= EXPLAIN_SAMPLE_LIMIT) {
                    lines.add("    … and " + (addedEdges - shown) + " more");
                    break;
                }
                shown++;
                String target = edge.targetFqn() != null ? edge.targetFqn() : edge.targetName();
                lines.add("    " + LogSanitizer.sanitize(edge.sourceFqn())
                        + " --[" + edge.category() + "]--> "
                        + LogSanitizer.sanitize(target));
            }
        }

        if (suppressed > 0) {
            lines.add("  suppressed impl-walk (sibling cap):");
            int shown = 0;
            for (String fqn : he.suppressedFromImplWalk()) {
                if (shown >= EXPLAIN_SAMPLE_LIMIT) {
                    lines.add("    … and " + (suppressed - shown) + " more");
                    break;
                }
                shown++;
                lines.add("    " + LogSanitizer.sanitize(fqn));
            }
        }
    }

    /**
     * Issue&nbsp;#132 — JSON counterpart to
     * {@link #appendHeaderEdgesBlock}. Always emits the field
     * ({@code "headerEdges": {...}}) so consumers can iterate
     * without null-checking; the no-op case is the {@code added:0,
     * suppressed:0, edges:[]} shape. Per-edge entries carry the
     * resolved FQN when one is known and {@code null} for
     * {@code UNRESOLVED} edges so dashboards can distinguish "we
     * resolved it but suppressed it" from "we couldn't resolve it
     * at all".
     */
    private static void appendHeaderEdgesJson(StringBuilder json, AffectedTestsResult result) {
        io.affectedtests.core.discovery.HeaderEdgesStrategy.AugmentationResult he =
                result.headerEdgesAugmentation();
        json.append("\"headerEdges\":{");
        if (he == null) {
            appendJsonField(json, "added", 0); json.append(',');
            appendJsonField(json, "suppressed", 0); json.append(',');
            json.append("\"edges\":[]");
            json.append("},");
            return;
        }
        appendJsonField(json, "added", he.augmentedTypes().size()); json.append(',');
        appendJsonField(json, "suppressed", he.suppressedFromImplWalk().size()); json.append(',');
        json.append("\"suppressedFqns\":[");
        boolean firstSup = true;
        int supShown = 0;
        for (String fqn : he.suppressedFromImplWalk()) {
            if (supShown >= EXPLAIN_SAMPLE_LIMIT) break;
            if (!firstSup) json.append(',');
            firstSup = false;
            supShown++;
            json.append('"').append(jsonEscape(LogSanitizer.sanitize(fqn))).append('"');
        }
        json.append("],");

        json.append("\"edges\":[");
        boolean firstEdge = true;
        int edgeShown = 0;
        for (var edge : he.edges()) {
            if (edgeShown >= EXPLAIN_SAMPLE_LIMIT) break;
            if (!firstEdge) json.append(',');
            firstEdge = false;
            edgeShown++;
            json.append('{');
            appendJsonField(json, "source", LogSanitizer.sanitize(edge.sourceFqn())); json.append(',');
            appendJsonField(json, "target",
                    edge.targetFqn() == null ? null : LogSanitizer.sanitize(edge.targetFqn()));
            json.append(',');
            appendJsonField(json, "targetName", LogSanitizer.sanitize(edge.targetName())); json.append(',');
            appendJsonField(json, "category", edge.category()); json.append(',');
            appendJsonField(json, "status", edge.status().name());
            if (edge.ignoreGlob() != null) {
                json.append(',');
                appendJsonField(json, "ignoreGlob", edge.ignoreGlob());
            }
            json.append('}');
        }
        json.append("]");
        json.append("},");
    }

    private static void appendSample(List<String> lines, String label, Set<String> files) {
        if (files.isEmpty()) {
            return;
        }
        // Filenames originate from the git diff of an (on the merge-gate,
        // attacker-controllable) MR tree. Sanitise before they reach the
        // logger — see LogSanitizer for the full log-forgery rationale.
        String preview = files.stream()
                .sorted()
                .limit(EXPLAIN_SAMPLE_LIMIT)
                .map(LogSanitizer::sanitize)
                .collect(Collectors.joining(", "));
        if (files.size() > EXPLAIN_SAMPLE_LIMIT) {
            preview = preview + ", … (+" + (files.size() - EXPLAIN_SAMPLE_LIMIT) + " more)";
        }
        lines.add("  " + label + " sample: " + preview);
    }

    /**
     * Situation order used by the explain trace and anywhere else we
     * render a full per-situation matrix. Matches the evaluation order
     * documented on {@link Situation} so operators can read the trace
     * top-to-bottom and mentally simulate the engine without cross-
     * referencing another doc.
     */
    private static List<Situation> situationOrder() {
        // Mirrors the engine's evaluation order in
        // {@link io.affectedtests.core.AffectedTestsEngine#run()}:
        // DISCOVERY_INCOMPLETE short-circuits before DISCOVERY_EMPTY /
        // DISCOVERY_SUCCESS whenever parseFailureCount > 0. The
        // --explain trace has to list the situations in the same
        // order an operator reads the engine logs, otherwise the
        // matrix reads as a lie when a parse-failure run elides
        // DISCOVERY_EMPTY.
        return List.of(
                Situation.EMPTY_DIFF,
                Situation.ALL_FILES_IGNORED,
                Situation.ALL_FILES_OUT_OF_SCOPE,
                Situation.UNMAPPED_FILE,
                Situation.DISCOVERY_INCOMPLETE,
                Situation.DISCOVERY_EMPTY,
                Situation.DISCOVERY_SUCCESS
        );
    }

    private static String describeSource(ActionSource source) {
        return switch (source) {
            case EXPLICIT     -> "explicit onXxx setting";
            case MODE_DEFAULT -> "mode default";
        };
    }

    /**
     * Builds the single summary line printed to Gradle's lifecycle log
     * for every {@code affectedTest} run. Every branch names the
     * outcome ({@link Action#SELECTED}, {@link Action#FULL_SUITE},
     * {@link Action#SKIPPED}) and the {@link Situation} that produced
     * it, followed by the file count and branch-specific details.
     *
     * <p>Shape:
     * <pre>
     * Affected Tests: SELECTED (DISCOVERY_SUCCESS) — N changed file(s), P production class(es), T test class(es) affected
     * Affected Tests: FULL_SUITE (UNMAPPED_FILE) — N changed file(s); running full suite (reason)
     * Affected Tests: SKIPPED (ALL_FILES_IGNORED) — N changed file(s); every changed file matched ignorePaths
     * </pre>
     *
     * <p>Pluralisation is deliberately fixed to {@code file(s)} and
     * {@code class(es)} across every branch so CI greps stay stable
     * across runs with different selection sizes.
     */
    static LogLine renderSummary(AffectedTestsResult result) {
        String prefix = "Affected Tests: " + result.action().name()
                + " (" + result.situation().name() + ") — ";
        if (result.runAll()) {
            // The "running full suite (reason)" phrase is the single
            // place the reason string shows up in the summary line.
            // Reason phrase is sourced from describeEscalation to
            // avoid duplicating the vocabulary across two files.
            return new LogLine(
                    prefix + "{} changed file(s); running full suite ({}).",
                    new Object[] {
                            result.changedFiles().size(),
                            describeEscalation(result.escalationReason())
                    });
        }
        if (result.skipped()) {
            return new LogLine(
                    prefix + "{} changed file(s); {}.",
                    new Object[] {
                            result.changedFiles().size(),
                            describeSkipReason(result.situation(), result.action())
                    });
        }
        return new LogLine(
                prefix + "{} changed file(s), {} production class(es), {} test class(es) affected",
                new Object[] {
                        result.changedFiles().size(),
                        result.changedProductionClasses().size(),
                        result.testClassFqns().size()
                });
    }

    /**
     * Renders a {@link Situation} as a short human-readable phrase
     * suitable for the summary line's {@code SKIPPED} branch. Only the
     * ambiguous situations can legitimately resolve to
     * {@link Action#SKIPPED}; {@link Situation#DISCOVERY_SUCCESS} is
     * rejected because the engine never skips when it found tests.
     *
     * <p>Takes the resolved {@link Action} because one situation
     * ({@link Situation#DISCOVERY_INCOMPLETE}) can legitimately reach
     * the "skipped" rendering path with {@code SELECTED} as well as
     * {@code SKIPPED}. The engine routes
     * {@code DISCOVERY_INCOMPLETE + SELECTED} with an empty selection
     * through the skipped-summary branch in the task summary (the
     * `skipped` flag is set by {@link io.affectedtests.core.AffectedTestsEngine#emptyResult}
     * whenever a non-SUCCESS situation winds up with zero tests),
     * so a situation-only rendering would print the {@code SKIPPED}
     * literal for a run the user actually configured as {@code SELECTED}.
     * Branching on the action keeps the phrase truthful without
     * requiring operators to reconcile two different action labels
     * in the same summary line.
     *
     * <p>Package-private so {@code AffectedTestTaskLogFormatTest} can
     * pin the exact wording — mirrors {@link #describeEscalation} so
     * the two halves of the summary log share one vocabulary.
     */
    static String describeSkipReason(Situation situation, Action action) {
        Objects.requireNonNull(situation, "situation");
        Objects.requireNonNull(action, "action");
        return switch (situation) {
            case EMPTY_DIFF ->
                    "no changed files detected";
            case ALL_FILES_IGNORED ->
                    "every changed file matched ignorePaths";
            case ALL_FILES_OUT_OF_SCOPE ->
                    "every changed file sat under out-of-scope dirs";
            case UNMAPPED_FILE ->
                    "onUnmappedFile=SKIPPED — non-Java or unmapped file in diff";
            case DISCOVERY_EMPTY ->
                    "no affected tests discovered";
            case DISCOVERY_INCOMPLETE -> action == Action.SELECTED
                    ? "onDiscoveryIncomplete=SELECTED — no affected tests matched the parsed files"
                    : "onDiscoveryIncomplete=SKIPPED — discovery observed unparseable files";
            case DISCOVERY_SUCCESS -> throw new IllegalStateException(
                    "describeSkipReason must not be called for DISCOVERY_SUCCESS; "
                            + "the engine only produces that situation on a non-empty "
                            + "selection, which is never skipped");
        };
    }

    // ─────────────────────────────────────────────────────────────────
    // --explain JSON output (issue #53)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Schema version for {@link #renderExplainJson}. Bumped to 2 in
     * issue #42 to add the additive {@code discovery} block (parallel
     * flag, concurrency level, total wall time, per-strategy timings
     * and contribution counts). Bumped to 3 in issue #132 to add the
     * additive {@code headerEdges} block (augmented-set total,
     * suppressed-list, and per-edge entries with source / target /
     * category / status). All v1 / v2 fields remain present so
     * existing consumers keep working without changes.
     */
    static final int EXPLAIN_JSON_SCHEMA_VERSION = 3;

    /**
     * Resolves and validates the {@code --explain-format} argument.
     * Empty / null defaults to {@code "text"} for backwards
     * compatibility with every adopter who has been consuming the
     * line-oriented trace since v2. Anything other than
     * {@code "text"} or {@code "json"} fails the build at the
     * {@code @TaskAction} entry point — we want a typo to surface
     * the typo, not silently fall back to text and have the operator
     * wonder why their dashboard pipeline never receives JSON.
     *
     * <p>Package-private so {@code AffectedTestTaskExplainJsonFormatTest}
     * can pin both the default-resolution and the validation paths
     * without spinning up a Gradle test runtime.
     */
    static String resolveExplainFormat(String raw) {
        if (raw == null || raw.isBlank()) {
            return "text";
        }
        String normalised = raw.trim().toLowerCase(Locale.ROOT);
        if (!normalised.equals("text") && !normalised.equals("json")) {
            throw new GradleException(
                    "Unsupported --explain-format='" + raw + "'. "
                            + "Supported values: 'text' (default) or 'json'.");
        }
        return normalised;
    }

    /**
     * Renders the same decision trace as {@link #renderExplainTrace} but
     * as a single-line, compact JSON object suitable for direct
     * consumption by dashboards / telemetry pipelines. Schema version
     * is carried in the {@code "version"} field so consumers can
     * detect future additions; new fields will be added additively
     * within {@link #EXPLAIN_JSON_SCHEMA_VERSION} as long as no
     * existing field's name or type changes.
     *
     * <p>The renderer is a pure function of {@link AffectedTestsConfig},
     * {@link AffectedTestsResult}, and the per-module breakdown — no
     * Gradle services, no I/O — so the unit tests exercise it directly
     * without spinning up a build runtime, mirroring the text-trace
     * unit-test ergonomics. Bucket samples are capped at
     * {@link #EXPLAIN_SAMPLE_LIMIT} (matching the text trace) so the
     * payload size stays bounded for very large diffs; consumers
     * that need full bucket contents can compare the per-bucket count
     * field against the per-bucket sample array length to detect
     * truncation.
     *
     * <p><b>Why a hand-rolled serialiser:</b> the plugin deliberately
     * has no Jackson / Gson dependency (keeps the published artifact
     * small and avoids version conflicts with adopter projects). The
     * schema is small, fixed, and string-keyed so a focussed escaper
     * + concatenation is enough; adding a JSON library for one
     * 50-line method would be a poor trade.
     */
    static String renderExplainJson(AffectedTestsConfig config,
                                    AffectedTestsResult result,
                                    Map<String, List<String>> moduleGroups) {
        StringBuilder json = new StringBuilder(512);
        json.append('{');
        appendJsonField(json, "version", EXPLAIN_JSON_SCHEMA_VERSION); json.append(',');
        appendJsonField(json, "baseRef", config.baseRef()); json.append(',');

        json.append("\"mode\":{");
        appendJsonField(json, "configured", config.mode().name()); json.append(',');
        appendJsonField(json, "effective", config.effectiveMode().name());
        json.append("},");

        appendJsonField(json, "changedFiles", result.changedFiles().size()); json.append(',');

        Buckets buckets = result.buckets();
        json.append("\"buckets\":{");
        appendJsonField(json, "ignored", buckets.ignoredFiles().size()); json.append(',');
        appendJsonField(json, "outOfScope", buckets.outOfScopeFiles().size()); json.append(',');
        appendJsonField(json, "production", buckets.productionFiles().size()); json.append(',');
        appendJsonField(json, "test", buckets.testFiles().size()); json.append(',');
        appendJsonField(json, "unmapped", buckets.unmappedFiles().size());
        json.append("},");

        // Bucket samples mirror the text-trace cap — same truncation
        // posture, same EXPLAIN_SAMPLE_LIMIT applied. Skip empty
        // buckets entirely so the payload doesn't bloat with five
        // empty arrays on a typical clean diff.
        json.append("\"samples\":{");
        boolean firstSample = true;
        firstSample = appendBucketSampleField(json, firstSample, "ignored", buckets.ignoredFiles());
        firstSample = appendBucketSampleField(json, firstSample, "outOfScope", buckets.outOfScopeFiles());
        firstSample = appendBucketSampleField(json, firstSample, "production", buckets.productionFiles());
        firstSample = appendBucketSampleField(json, firstSample, "test", buckets.testFiles());
        appendBucketSampleField(json, firstSample, "unmapped", buckets.unmappedFiles());
        json.append("},");

        appendJsonField(json, "situation", result.situation().name()); json.append(',');

        json.append("\"action\":{");
        appendJsonField(json, "name", result.action().name()); json.append(',');
        appendJsonField(json, "source", config.actionSourceFor(result.situation()).name());
        json.append("},");

        // Outcome shape mirrors the text trace's "Outcome:" line but
        // exposes the components separately so consumers don't have
        // to string-parse "FULL_SUITE — non-Java change in diff" back
        // into kind + reason. The kind is one of FULL_SUITE / SKIPPED
        // / SELECTED; selectedClassCount is only meaningful on
        // SELECTED but is always present (0 elsewhere) so the schema
        // is total — consumers don't need to branch on optional
        // fields.
        json.append("\"outcome\":{");
        String outcomeKind;
        if (result.runAll()) {
            outcomeKind = "FULL_SUITE";
        } else if (result.skipped()) {
            outcomeKind = "SKIPPED";
        } else if (result.action() == Action.SELECTED) {
            outcomeKind = "SELECTED";
        } else {
            outcomeKind = result.action().name();
        }
        appendJsonField(json, "kind", outcomeKind); json.append(',');
        appendJsonField(json, "selectedClassCount", result.testClassFqns().size()); json.append(',');
        appendJsonField(json, "escalationReason", result.escalationReason().name());
        json.append("},");

        // Modules block: only populated on SELECTED runs (the text
        // renderer has the same conditional). Keep the array always
        // present (empty when there's no selection) so consumers can
        // iterate without null-checking.
        json.append("\"modules\":[");
        boolean firstModule = true;
        if (moduleGroups != null) {
            for (Map.Entry<String, List<String>> entry : moduleGroups.entrySet()) {
                if (!firstModule) json.append(',');
                firstModule = false;
                json.append('{');
                appendJsonField(json, "task", entry.getKey()); json.append(',');
                appendJsonField(json, "fqns", entry.getValue());
                json.append('}');
            }
        }
        json.append("],");

        // Discovery profile (issue #42, schema v2). Always present so
        // consumers can iterate without null-checking; an
        // empty-strategy case (test-only fast path, EMPTY_DIFF) shows
        // up as `parallelEnabled: false`, `concurrencyLevel: 0`,
        // `totalMillis: 0`, and an empty `perStrategy` array.
        DiscoveryProfile profile = result.discoveryProfile();
        json.append("\"discovery\":{");
        appendJsonField(json, "parallelEnabled", profile.parallelEnabled());
        json.append(',');
        appendJsonField(json, "concurrencyLevel", profile.concurrencyLevel()); json.append(',');
        appendJsonField(json, "totalMillis", (int) profile.totalDiscoveryWallTime().toMillis());
        json.append(',');
        json.append("\"perStrategy\":[");
        boolean firstStrategy = true;
        for (Map.Entry<String, java.time.Duration> e : profile.perStrategyWallTime().entrySet()) {
            if (!firstStrategy) json.append(',');
            firstStrategy = false;
            json.append('{');
            appendJsonField(json, "name", e.getKey()); json.append(',');
            appendJsonField(json, "millis", (int) e.getValue().toMillis()); json.append(',');
            appendJsonField(json, "tests",
                    profile.perStrategyTestCount().getOrDefault(e.getKey(), 0));
            json.append('}');
        }
        json.append("]},");

        // Issue #132 — headerEdges augmentation. Always present so
        // consumers can iterate without null-checking; the empty-no-op
        // case shows up as zero counts and an empty edges array.
        // {@link #appendHeaderEdgesJson} owns the encoding so the
        // text and JSON renderers can't drift apart on the field
        // names.
        appendHeaderEdgesJson(json, result);

        // The action matrix is cheap to serialise (5 entries) and
        // invaluable for "why did my explicit setting not win"
        // dashboards. Same shape as the text trace's "Action matrix"
        // block: situation -> {action, source}.
        json.append("\"actionMatrix\":{");
        boolean firstMatrix = true;
        for (Situation s : situationOrder()) {
            if (!firstMatrix) json.append(',');
            firstMatrix = false;
            json.append('"').append(jsonEscape(s.name())).append("\":{");
            appendJsonField(json, "action", config.actionFor(s).name()); json.append(',');
            appendJsonField(json, "source", config.actionSourceFor(s).name());
            json.append('}');
        }
        json.append('}');

        json.append('}');
        return json.toString();
    }

    /**
     * Renders one entry of the bucket-samples block. Empty buckets
     * are skipped entirely so the payload stays compact on the
     * common clean-diff case where most buckets are empty. Returns
     * the updated {@code first} flag so the caller knows whether to
     * emit a leading comma on the next field.
     */
    private static boolean appendBucketSampleField(StringBuilder json, boolean first,
                                                   String key, Set<String> files) {
        if (files.isEmpty()) {
            return first;
        }
        if (!first) json.append(',');
        List<String> capped = files.stream()
                .sorted()
                .limit(EXPLAIN_SAMPLE_LIMIT)
                .map(LogSanitizer::sanitize)
                .toList();
        appendJsonField(json, key, capped);
        return false;
    }

    private static void appendJsonField(StringBuilder json, String key, String value) {
        json.append('"').append(jsonEscape(key)).append("\":");
        if (value == null) {
            json.append("null");
        } else {
            json.append('"').append(jsonEscape(value)).append('"');
        }
    }

    private static void appendJsonField(StringBuilder json, String key, int value) {
        json.append('"').append(jsonEscape(key)).append("\":").append(value);
    }

    private static void appendJsonField(StringBuilder json, String key, boolean value) {
        json.append('"').append(jsonEscape(key)).append("\":").append(value);
    }

    private static void appendJsonField(StringBuilder json, String key, List<String> values) {
        json.append('"').append(jsonEscape(key)).append("\":[");
        boolean first = true;
        for (String v : values) {
            if (!first) json.append(',');
            first = false;
            json.append('"').append(jsonEscape(v == null ? "" : v)).append('"');
        }
        json.append(']');
    }

    /**
     * RFC 8259 string escaper. Escapes the six required control
     * characters ({@code \b \f \n \r \t \"} / {@code \\}) and emits
     * any other character below 0x20 as a {@code \\u}NNNN sequence.
     * Forward slash and the non-mandatory unicode separators are
     * passed through — RFC 8259 only requires escaping where the
     * unescaped form would terminate the string or break the JSON
     * grammar.
     *
     * <p>The input strings on the merge gate are attacker-influenced
     * (filenames from the diff, FQNs derived from those filenames),
     * so this method must be defensively comprehensive. A bare
     * unsanitised newline in a sample path would terminate the JSON
     * line and let an attacker inject a forged trace into a
     * dashboard pipeline.
     */
    static String jsonEscape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        // Split the "\\u" prefix from the format
                        // specifier so javac does not read the
                        // following four characters as a unicode
                        // escape sequence (Java unicode escapes
                        // are processed in the source-text phase
                        // before lexing, even inside comments).
                        out.append("\\u")
                                .append(String.format(Locale.ROOT, "%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
