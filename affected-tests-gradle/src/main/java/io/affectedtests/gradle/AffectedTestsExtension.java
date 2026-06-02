package io.affectedtests.gradle;

import java.util.List;

import org.gradle.api.GradleException;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * DSL extension for configuring the Affected Tests plugin.
 *
 * <p>Usage in {@code build.gradle}:
 * <pre>{@code
 * affectedTests {
 *     baseRef = "origin/master"
 *     // Defaults are COMMITTED-ONLY (both flags default to false) so
 *     // local runs match CI. Flip to true if you want WIP to seed the
 *     // diff while iterating on tests locally.
 *     includeUncommitted = false
 *     includeStaged = false
 *     // v2: per-situation actions. See README.md for the full table.
 *     mode = "ci"
 *     onEmptyDiff          = "full_suite"
 *     onAllFilesOutOfScope = "skipped"
 *     onUnmappedFile       = "full_suite"
 *     onDiscoveryEmpty     = "full_suite"
 *     ignorePaths = ["&#42;&#42;/generated/&#42;&#42;"]
 *     outOfScopeTestDirs = ["api-test/src/test/java"]
 *     strategies = ["naming", "usage", "impl", "transitive"]
 *     transitiveDepth = 4
 *     parallelDiscovery = true
 *     testSuffixes = ["Test", "IT", "ITTest", "IntegrationTest"]
 *     sourceDirs = ["src/main/java"]
 *     testDirs = ["src/test/java"]
 *     includeImplementationTests = true
 *     implementationNaming = ["Impl", "Default"]
 * }
 * }</pre>
 */
public abstract class AffectedTestsExtension {

    /**
     * Git base ref to diff against.
     * Default: {@code "origin/master"}.
     *
     * @return the base ref property
     */
    public abstract Property<String> getBaseRef();

    /**
     * Include uncommitted (unstaged) changes.
     * Default: {@code false} — committed-only semantics match CI. Set
     * to {@code true} if you iterate on WIP locally and want the
     * unstaged edits to seed the diff boundary.
     *
     * @return the include uncommitted property
     */
    public abstract Property<Boolean> getIncludeUncommitted();

    /**
     * Include staged changes.
     * Default: {@code false}. See {@link #getIncludeUncommitted()} for
     * the rationale.
     *
     * @return the include staged property
     */
    public abstract Property<Boolean> getIncludeStaged();

    /**
     * Strategies to use for test discovery. Valid values:
     * {@code "naming"}, {@code "usage"}, {@code "impl"}, {@code "transitive"}.
     * Default: all four.
     *
     * <p>The {@code transitive} strategy additionally respects
     * {@link #getTransitiveDepth() transitiveDepth}.
     *
     * @return the strategies list property
     */
    public abstract ListProperty<String> getStrategies();

    /**
     * How many levels of transitive dependencies to follow when the
     * {@code transitive} strategy is enabled.
     * Default: {@code 4} — matches the depth most Java controller →
     * service → repository chains actually reach in Modulr-shaped
     * codebases while leaving one level of margin. Range: 0–5.
     *
     * @return the transitive depth property
     */
    public abstract Property<Integer> getTransitiveDepth();

    /**
     * Whether to fan the four discovery strategies out across a small
     * thread pool (default {@code true}) or run them serially. Engine-
     * level parallelism is the Stage 1 win for issue #42; on the
     * workloads we've measured it cuts discovery wall time by ~2x
     * with no algorithmic change. Adopters can flip to {@code false}
     * if a custom strategy fork or a JavaParser regression we have
     * not seen yet introduces a race; the serial path remains a
     * one-line fallback.
     *
     * <p>The Gradle property {@code -PaffectedTestsParallelDiscovery=false}
     * provides the same kill switch without touching the DSL. The
     * property accepts {@code true|false|1|0|on|off|yes|no}
     * (case-insensitive); typo'd values are rejected with a build-log
     * WARN rather than silently flipping the switch.
     *
     * @return the parallel-discovery property
     */
    public abstract Property<Boolean> getParallelDiscovery();

    /**
     * Whether to register the Kotlin AST-driven parser for {@code .kt}
     * files (issue #76 Phase 2, PR #4). Defaults to {@code true} —
     * Kotlin sources participate in AST-driven discovery (Usage /
     * Implementation / Transitive) on every run.
     *
     * <p>When {@code true}, {@code KotlinLanguageParser} is registered
     * with the per-engine {@code LanguageParsers} registry; AST-driven
     * strategies start producing matches for Kotlin files; embeddable
     * bootstrap failures fail closed via {@code DISCOVERY_INCOMPLETE}
     * (the WARN is logged once and every {@code .kt} in the run is
     * treated as unparseable).
     *
     * <p>When {@code false}, {@code .kt} files fall back to the
     * Phase 1 (PR #1) shape: path-derived FQN routing on the diff
     * side, no AST-driven strategy participation, no
     * {@code DISCOVERY_INCOMPLETE} on unparsed Kotlin — the absence
     * is "by design" rather than a parse failure. Use this kill
     * switch only as a regression escape hatch; PR #4 removed the
     * {@code -Daffected-tests.kotlin.enabled} system property
     * because the rollout window closed and the AST path is now
     * first-class.
     *
     * @return the Kotlin-enabled property
     */
    public abstract Property<Boolean> getKotlinEnabled();

    /**
     * Issue&nbsp;#132 — kill switch for the {@code headerEdges}
     * discovery strategy. {@code true} (default) walks class-header
     * type references (extends / implements / permits / generic
     * bounds / record components / class-level annotations) and
     * augments the changed-class set with the resolved targets
     * before the four pre-existing strategies (naming / usage /
     * impl / transitive) run. {@code false} disables every
     * category at once and reverts plugin behaviour to byte-for-byte
     * identical to pre-issue-#132. Adopters who want per-category
     * opt-out use {@link #getHeaderEdgesExclude()} instead so the
     * rest of the strategy keeps firing.
     *
     * @return the headerEdges-enabled property
     */
    public abstract Property<Boolean> getHeaderEdgesEnabled();

    /**
     * Issue&nbsp;#132 — header-edge categories whose contributions
     * are skipped during augmentation. Adopters EXCLUDE (not
     * include) — every category is on by default; this list is the
     * surgical opt-out. Valid entries: {@code "extends"},
     * {@code "implements"}, {@code "permits"}, {@code "type-bounds"},
     * {@code "record-components"}, {@code "annotations"}. Entries
     * are case-insensitive; unknown entries are rejected at config
     * time (a silent ignore of a typo would leave the adopter
     * expecting their opt-out applied when it wasn't).
     *
     * <p>Common opt-out shapes:
     * <ul>
     *   <li>{@code headerEdgesExclude = ["annotations"]} — adopter
     *       uses many custom class-level annotations that don't
     *       carry test-relevant behaviour. Most frequent opt-out.</li>
     *   <li>{@code headerEdgesExclude = ["record-components"]} —
     *       DTO-heavy codebases where record components are pure
     *       data carriers.</li>
     * </ul>
     *
     * @return the headerEdgesExclude list property
     */
    public abstract ListProperty<String> getHeaderEdgesExclude();

    /**
     * Issue&nbsp;#132 — how many header-edge hops the strategy
     * walks from each changed class. {@code 1} (default) walks the
     * immediate header-declared types only; {@code 2} walks one
     * more level. Clamped to {@code [0, 2]} at the builder gate;
     * higher values rapidly approach {@code FULL_SUITE} selection
     * on real codebases because almost every concrete class
     * transitively reaches {@code Object}'s consumer base.
     * {@code 0} is treated identically to
     * {@link #getHeaderEdgesEnabled() headerEdgesEnabled = false}.
     *
     * @return the headerEdges depth property
     */
    public abstract Property<Integer> getHeaderEdgesDepth();

    /**
     * Issue&nbsp;#132 — sibling-cap on the explosion vector. When
     * a header-edge-added type has more direct subtypes than this
     * value, the {@code impl} strategy's downward walk from THAT
     * added type is suppressed; the added type still contributes
     * via naming / usage / transitive. Default: {@code 5}.
     *
     * <p>Motivating case: {@code PaymentController extends
     * BaseController} with {@code BaseController} owning 52
     * subclasses. Without the cap, walking {@code extends} would
     * add {@code BaseController} to the changed set and the impl
     * strategy would then walk DOWN through all 52 subtypes,
     * selecting every {@code *ControllerTest} in the codebase.
     * The cap is the surgical fix — {@code BaseController} still
     * adds {@code BaseControllerTest} via naming, but the 52-way
     * fan-out is skipped and recorded in {@code --explain} as
     * {@code SKIPPED downward impl-walk from BaseController (52
     * subtypes > headerEdgesMaxSiblings=5)}.
     *
     * <p>Set to {@code 0} to suppress every downward walk from a
     * header-edge-added type. Setting it negative is rejected at
     * config time.
     *
     * @return the headerEdgesMaxSiblings property
     */
    public abstract Property<Integer> getHeaderEdgesMaxSiblings();

    /**
     * Issue&nbsp;#132 — globs (Java-style {@code **}-prefixed FQN
     * patterns) for types whose header-edge contributions are
     * ignored. Default covers framework / JDK / Lombok / JUnit /
     * Mockito / Kotlin / Groovy types that pollute selection
     * without contributing signal: {@code java.lang.**},
     * {@code java.util.**}, {@code org.springframework.**},
     * {@code org.junit.**}, {@code lombok.**}, {@code kotlin.**},
     * {@code groovy.lang.**}, and the {@code javax} / {@code jakarta}
     * equivalents.
     *
     * <p>When unset, the core {@code AffectedTestsConfig} default
     * list applies. Adopters typically EXTEND rather than replace:
     * setting an explicit list overrides the default entirely, so
     * a project that wants to add a single extra glob should
     * concatenate with the default rather than supply only the
     * one entry.
     *
     * @return the headerEdgesIgnore list property
     */
    public abstract ListProperty<String> getHeaderEdgesIgnore();

    /**
     * Test class suffixes used by the naming strategy.
     * Default: {@code ["Test", "IT", "ITTest", "IntegrationTest"]}.
     *
     * @return the test suffixes list property
     */
    public abstract ListProperty<String> getTestSuffixes();

    /**
     * Production source directories (relative to each module root).
     * Default: {@code ["src/main/java"]}.
     *
     * @return the source dirs list property
     */
    public abstract ListProperty<String> getSourceDirs();

    /**
     * Test source directories (relative to each module root).
     * Default: {@code ["src/test/java"]}.
     *
     * @return the test dirs list property
     */
    public abstract ListProperty<String> getTestDirs();

    /**
     * Names of the Gradle test tasks the dispatch path may invoke
     * (one nested {@code ./gradlew} call per {@code module × taskName}
     * pair). Default: {@code ["test"]}.
     *
     * <p>Adopters with extra source sets opt them in here:
     * {@code testTaskNames = ["test", "integrationTest"]}. The
     * dispatch path routes each discovered FQN to the task whose
     * source-set directory the FQN's file sits under, by the
     * standard Gradle convention {@code src/<taskName>/java}. When
     * setting this knob, callers typically also extend
     * {@link #getTestDirs()} to include the corresponding source-set
     * directories so the discovery passes scan them — otherwise the
     * extra source sets stay invisible to discovery and the new
     * task name has nothing to dispatch.
     *
     * @return the test task names list property
     */
    public abstract ListProperty<String> getTestTaskNames();

    /**
     * Glob patterns for files that must never influence test selection —
     * for purely documentation, build metadata, or generated artifacts.
     * A diff consisting entirely of ignored paths routes through
     * {@code Situation.ALL_FILES_IGNORED}.
     *
     * <p>When unset, the core {@code AffectedTestsConfig} default list
     * applies (markdown, generated/, text/licence/changelog, images).
     *
     * @return the ignore paths list property
     */
    public abstract ListProperty<String> getIgnorePaths();

    /**
     * Test source directories (e.g. {@code "api-test/src/test/java"})
     * whose contents the plugin must not dispatch via the
     * {@code affectedTest} task. A diff entirely under these directories
     * routes through {@code Situation.ALL_FILES_OUT_OF_SCOPE}. Intended
     * for Cucumber/api-test, performance, or other non-unit-test source
     * sets.
     *
     * @return the out-of-scope test dirs list property
     */
    public abstract ListProperty<String> getOutOfScopeTestDirs();

    /**
     * Production source directories the plugin must treat as
     * out-of-scope. A diff entirely under these dirs routes through
     * {@code Situation.ALL_FILES_OUT_OF_SCOPE}.
     *
     * @return the out-of-scope source dirs list property
     */
    public abstract ListProperty<String> getOutOfScopeSourceDirs();

    /**
     * Include tests for implementations of changed interfaces/base classes.
     * Default: {@code true}.
     *
     * @return the include implementation tests property
     */
    public abstract Property<Boolean> getIncludeImplementationTests();

    /**
     * Implementation naming prefixes/suffixes (e.g. "Impl" matches
     * {@code FooBarImpl} for {@code FooBar}; "Default" matches
     * {@code DefaultFooBar} for {@code FooBar}).
     * Default: {@code ["Impl", "Default"]}.
     *
     * @return the implementation naming list property
     */
    public abstract ListProperty<String> getImplementationNaming();

    /**
     * Execution profile ({@code "auto"}, {@code "local"}, {@code "ci"},
     * or {@code "strict"}). Controls per-situation default actions
     * when the explicit {@code onXxx} setting is not set.
     *
     * <p>Default: unset — which resolves to {@link io.affectedtests.core.config.Mode#AUTO}
     * on the core config. {@code AUTO} detects CI via common env vars
     * and picks the CI defaults there, and the LOCAL defaults otherwise.
     *
     * @return the mode property
     */
    public abstract Property<String> getMode();

    /**
     * Action to take on an empty git diff. One of {@code "selected"},
     * {@code "full_suite"}, {@code "skipped"} (case-insensitive).
     *
     * @return the on-empty-diff property
     */
    public abstract Property<String> getOnEmptyDiff();

    /**
     * Action to take when every file in the diff matched
     * {@link #getIgnorePaths()}. One of {@code "selected"},
     * {@code "full_suite"}, {@code "skipped"}.
     *
     * @return the on-all-files-ignored property
     */
    public abstract Property<String> getOnAllFilesIgnored();

    /**
     * Action to take when every file in the diff sat under
     * {@link #getOutOfScopeTestDirs()} or {@link #getOutOfScopeSourceDirs()}.
     * One of {@code "selected"}, {@code "full_suite"}, {@code "skipped"}.
     *
     * @return the on-all-files-out-of-scope property
     */
    public abstract Property<String> getOnAllFilesOutOfScope();

    /**
     * Action to take when the diff contains at least one unmapped file
     * (non-Java, outside configured source/test dirs). One of
     * {@code "selected"}, {@code "full_suite"}, {@code "skipped"}. When
     * set to {@code "selected"} the engine treats the unmapped file as
     * if it weren't there and continues to discovery — matching
     * pre-v2 {@code runAllOnNonJavaChange=false} behaviour.
     *
     * @return the on-unmapped-file property
     */
    public abstract Property<String> getOnUnmappedFile();

    /**
     * Action to take when discovery completes but returns no tests. One
     * of {@code "selected"}, {@code "full_suite"}, {@code "skipped"}.
     *
     * @return the on-discovery-empty property
     */
    public abstract Property<String> getOnDiscoveryEmpty();

    /**
     * Action to take when discovery ran but at least one scanned Java file
     * failed to parse — see {@code Situation.DISCOVERY_INCOMPLETE} on the
     * core module for the full rationale. One of {@code "selected"},
     * {@code "full_suite"}, {@code "skipped"}.
     *
     * <p>Unset falls through to the {@link #getMode() mode} default
     * (CI and STRICT escalate to {@code full_suite}; LOCAL keeps the
     * partial selection).
     *
     * @return the on-discovery-incomplete property
     */
    public abstract Property<String> getOnDiscoveryIncomplete();

    /**
     * Wall-clock timeout in seconds for the nested {@code ./gradlew}
     * invocation that executes the affected / full test suite.
     * {@code 0} disables the timeout (pre-v1.9.22 default — wait
     * indefinitely). Positive values deadline the child process: the
     * plugin destroys the process tree after the interval and fails
     * the build with a clear error.
     *
     * <p>Recommended values: {@code 1800} (30 min) for merge-gate
     * unit-test runs, {@code 3600} (1 hour) for suites that include
     * integration tests. Must be {@code >= 0}; the plugin rejects
     * negative values at {@code afterEvaluate} (configuration) time
     * and again at task-execution time as belt-and-braces.
     *
     * @return the gradlew timeout property in seconds
     */
    public abstract Property<Long> getGradlewTimeoutSeconds();

    // ------------------------------------------------------------------
    // v2 migration hints for the three legacy knobs removed in v2.0.
    //
    // The properties themselves are gone, so Gradle's default behaviour
    // for `affectedTests.runAllIfNoMatches = false` is a terse
    // "Could not set unknown property 'runAllIfNoMatches'" — correct
    // but unhelpful for operators reading a v1 build.gradle.
    //
    // These shim setters are invoked by Groovy DSL assignment
    // (`ext.foo = bar` maps to `setFoo(bar)` on the managed type) and
    // replace the terse error with an actionable v2 replacement
    // pointing at the exact `onXxx` knob to use. Kotlin DSL callers
    // get a compile error referencing the removed property, which is
    // already clearer than Groovy's runtime error, so no shim needed
    // there.
    //
    // If v2 ever adds real `runAllIfNoMatches` / `runAllOnNonJavaChange`
    // / `excludePaths` semantics back (unlikely — the v2 names are
    // strictly richer), delete these shims and re-introduce the
    // abstract Property<T> getters.
    // ------------------------------------------------------------------

    /**
     * Migration shim: {@code runAllIfNoMatches} was removed in v2.0.
     * Intercepts a v1-style assignment in Groovy DSL and raises a
     * targeted migration error instead of Gradle's generic
     * "unknown property" failure.
     *
     * @param ignored v1 boolean value (never read)
     * @throws GradleException always, with a v2 replacement hint
     */
    @SuppressWarnings("unused")
    public void setRunAllIfNoMatches(Object ignored) {
        throw new GradleException(
                "affectedTests.runAllIfNoMatches was removed in v2.0.0. "
                        + "Use onEmptyDiff = \"full_suite\" and/or "
                        + "onDiscoveryEmpty = \"full_suite\" instead "
                        + "(or set mode = \"ci\" / \"strict\" to get those defaults). "
                        + "See CHANGELOG.md v2.0 for the full migration table.");
    }

    /**
     * Migration shim: {@code runAllOnNonJavaChange} was removed in v2.0.
     *
     * @param ignored v1 boolean value (never read)
     * @throws GradleException always, with a v2 replacement hint
     */
    @SuppressWarnings("unused")
    public void setRunAllOnNonJavaChange(Object ignored) {
        throw new GradleException(
                "affectedTests.runAllOnNonJavaChange was removed in v2.0.0. "
                        + "Use onUnmappedFile = \"full_suite\" (v1 true) or "
                        + "onUnmappedFile = \"selected\" (v1 false) instead. "
                        + "See CHANGELOG.md v2.0 for the full migration table.");
    }

    /**
     * Migration shim: {@code excludePaths} was removed in v2.0.
     *
     * @param ignored v1 list value (never read)
     * @throws GradleException always, with a v2 replacement hint
     */
    @SuppressWarnings("unused")
    public void setExcludePaths(List<?> ignored) {
        throw new GradleException(
                "affectedTests.excludePaths was removed in v2.0.0. "
                        + "Use ignorePaths (glob patterns that bypass test selection) "
                        + "or outOfScopeTestDirs (test dirs the affectedTest task never dispatches) "
                        + "depending on intent. See CHANGELOG.md v2.0 for the full migration table.");
    }
}
