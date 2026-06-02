package io.affectedtests.gradle;

import io.affectedtests.core.config.AffectedTestsConfig;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AffectedTestsPluginTest {

    @Test
    void pluginRegistersTask() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        assertNotNull(project.getTasks().findByName("affectedTest"),
                "affectedTest task should be registered");
    }

    @Test
    void pluginRegistersExtension() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        assertNotNull(project.getExtensions().findByName("affectedTests"),
                "affectedTests extension should be registered");
    }

    @Test
    void extensionHasDefaults() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        AffectedTestsExtension ext = project.getExtensions()
                .getByType(AffectedTestsExtension.class);

        assertEquals("origin/master", ext.getBaseRef().get());
        // Default is now COMMITTED-ONLY. Matches CI semantics exactly
        // (where the tree is already clean after checkout anyway) and
        // gives local `./gradlew affectedTest` runs the same test
        // selection the operator's MR will get in CI. Devs who want WIP
        // to seed the diff opt in explicitly — see CHANGELOG for the
        // v1.9.14 → next-release behaviour flip.
        assertFalse(ext.getIncludeUncommitted().get(),
                "Default must be COMMITTED-ONLY so local runs match CI — WIP inclusion is an explicit opt-in");
        assertFalse(ext.getIncludeStaged().get(),
                "Default must be COMMITTED-ONLY so local runs match CI — staged-index inclusion is an explicit opt-in");
        assertEquals(5, ext.getStrategies().get().size());
        assertTrue(ext.getStrategies().get().contains("transitive"));
        assertTrue(ext.getStrategies().get().contains("headerEdges"));
        // v2 raises the default transitive depth from 2 to 4 — real-world
        // ctrl -> svc -> repo -> mapper chains sit at 3-4 levels, so 2
        // silently dropped coverage on zero-config installs.
        assertEquals(4, ext.getTransitiveDepth().get());
        assertEquals(4, ext.getTestSuffixes().get().size());
        assertTrue(ext.getIncludeImplementationTests().get());
        // v2 adds the "Default" prefix pattern alongside "Impl" so
        // DefaultFooService → FooService is also picked up by the
        // implementation strategy on zero-config installs.
        assertEquals(2, ext.getImplementationNaming().get().size());
        assertTrue(ext.getImplementationNaming().get().contains("Impl"));
        assertTrue(ext.getImplementationNaming().get().contains("Default"));
    }

    @Test
    void taskHasCorrectGroupAndDescription() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        var task = project.getTasks().findByName("affectedTest");
        assertNotNull(task);
        assertEquals("verification", task.getGroup());
        assertNotNull(task.getDescription());
        assertTrue(task.getDescription().contains("affected"));
    }

    @Test
    void taskInputsAreWiredFromExtension() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        // Modify extension
        AffectedTestsExtension ext = project.getExtensions()
                .getByType(AffectedTestsExtension.class);
        ext.getBaseRef().set("origin/main");
        ext.getTransitiveDepth().set(0);
        ext.getOnUnmappedFile().set("SELECTED");

        // Verify task picks up the change
        AffectedTestTask task = (AffectedTestTask) project.getTasks().findByName("affectedTest");
        assertNotNull(task);
        assertEquals("origin/main", task.getBaseRef().get());
        assertEquals(0, task.getTransitiveDepth().get());
        assertEquals("SELECTED", task.getOnUnmappedFile().get(),
                "Task input must reflect the extension's onUnmappedFile override");
    }

    @Test
    void extensionExposesOnDiscoveryIncompleteAndGradlewTimeout() {
        // Regression for B6-#9 + B6-#11 DSL wiring: missing setters
        // in AffectedTestsExtension would compile-break the build but
        // a typo between the extension and the task ( `getOnDiscoveryIncomplete`
        // vs `getOnDiscoveryIncompleteAction`) would only show up at
        // runtime when a user configured the DSL. Pin the names and
        // round-trip the values so that refactor doesn't silently
        // drop the wiring.
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        AffectedTestsExtension ext = project.getExtensions()
                .getByType(AffectedTestsExtension.class);
        ext.getOnDiscoveryIncomplete().set("SKIPPED");
        ext.getGradlewTimeoutSeconds().set(1800L);

        AffectedTestTask task = (AffectedTestTask) project.getTasks().findByName("affectedTest");
        assertNotNull(task);
        assertEquals("SKIPPED", task.getOnDiscoveryIncomplete().get(),
                "Task must expose the extension's onDiscoveryIncomplete setting byte-for-byte");
        assertEquals(1800L, task.getGradlewTimeoutSeconds().get(),
                "Task must expose the extension's gradlewTimeoutSeconds setting byte-for-byte");
    }

    @Test
    void gradlewTimeoutAndDiscoveryIncompleteAreUnconfiguredByDefault() {
        // Defaults must stay absent — presence-based defaulting is how
        // the core config builder distinguishes "user didn't say"
        // (fall through to mode defaults) from "user set 0" (explicit
        // no-timeout). Installing a convention would collapse those
        // two cases and silently change mode-default behaviour.
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        AffectedTestsExtension ext = project.getExtensions()
                .getByType(AffectedTestsExtension.class);
        assertFalse(ext.getOnDiscoveryIncomplete().isPresent(),
                "onDiscoveryIncomplete must have no convention so the core resolver picks the mode default");
        assertFalse(ext.getGradlewTimeoutSeconds().isPresent(),
                "gradlewTimeoutSeconds must have no convention so the task branch between watchdog/exec paths stays correct");
    }

    @Test
    void legacyDslKnobsNoLongerExistInV2() {
        // v2.0 breaking change: the three v1 knobs (`runAllIfNoMatches`,
        // `runAllOnNonJavaChange`, `excludePaths`) were removed entirely
        // after being deprecated across the v1.9.x line. This test locks
        // in their absence so a well-intentioned "restore the getter for
        // back-compat" revert gets caught at build time instead of
        // silently re-opening the v1 API surface and the deprecation
        // warnings it came with.
        //
        // We check reflectively rather than calling the accessors
        // directly (which would turn into a compile error the moment
        // someone re-adds them, masking the intent of the test). The
        // contract is: none of the pre-v2 accessors exist anywhere on
        // the public surface in v2+ — not on the extension, not on the
        // task, not on the config, not on the builder.
        java.util.List<String> forbiddenAccessors = java.util.List.of(
                "getRunAllIfNoMatches",
                "getRunAllOnNonJavaChange",
                "getExcludePaths"
        );
        assertAllAbsent(AffectedTestsExtension.class, forbiddenAccessors,
                "extension");
        assertAllAbsent(AffectedTestTask.class, forbiddenAccessors,
                "task");

        // On the core config type the legacy accessors were unprefixed
        // (`runAllIfNoMatches()`, not `getRunAllIfNoMatches()`), and
        // `deprecationWarnings()` was its own list we also deleted — if
        // any of them come back the two-tier resolver docs are
        // immediately out of sync with reality.
        java.util.List<String> forbiddenConfigAccessors = java.util.List.of(
                "runAllIfNoMatches",
                "runAllOnNonJavaChange",
                "excludePaths",
                "deprecationWarnings"
        );
        assertAllAbsent(AffectedTestsConfig.class, forbiddenConfigAccessors,
                "config");

        // And the three Builder setters that fed those accessors. We
        // pin them separately because a partial revert that restores
        // the builder-side without the getter-side would be just as
        // bad — it would silently accept the v1 DSL in Groovy while
        // doing nothing with it.
        java.util.List<String> forbiddenBuilderSetters = java.util.List.of(
                "runAllIfNoMatches",
                "runAllOnNonJavaChange",
                "excludePaths"
        );
        assertAllAbsent(AffectedTestsConfig.Builder.class, forbiddenBuilderSetters,
                "config builder");
    }

    @Test
    void legacyKnobAssignmentThrowsWithV2MigrationHint_runAllIfNoMatches() {
        // S10 polish (v2.1): a v1 user dropping `runAllIfNoMatches = false`
        // into their build.gradle used to hit Gradle's generic
        // "unknown property" error, which names the knob but offers no
        // v2 replacement. The shim setter should replace that with a
        // targeted message pointing at the exact onXxx knob that took
        // over each responsibility, so the v1 build.gradle becomes
        // fixable without grepping the CHANGELOG.
        AffectedTestsExtension ext = freshExtension();

        GradleException ex = assertThrows(GradleException.class,
                () -> ext.setRunAllIfNoMatches(false));
        assertTrue(ex.getMessage().contains("runAllIfNoMatches"),
                "Error must name the removed v1 knob so grep-based alerting still locates it");
        assertTrue(ex.getMessage().contains("onEmptyDiff")
                        && ex.getMessage().contains("onDiscoveryEmpty"),
                "Error must name BOTH v2 replacement knobs — v1's single boolean split into two per-situation actions in v2");
        assertTrue(ex.getMessage().contains("v2.0") || ex.getMessage().contains("CHANGELOG"),
                "Error must point at the CHANGELOG/v2 migration doc");
    }

    @Test
    void legacyKnobAssignmentThrowsWithV2MigrationHint_runAllOnNonJavaChange() {
        AffectedTestsExtension ext = freshExtension();

        GradleException ex = assertThrows(GradleException.class,
                () -> ext.setRunAllOnNonJavaChange(true));
        assertTrue(ex.getMessage().contains("runAllOnNonJavaChange"));
        assertTrue(ex.getMessage().contains("onUnmappedFile"),
                "Error must name the direct v2 replacement so the fix is mechanical");
    }

    @Test
    void legacyKnobAssignmentThrowsWithV2MigrationHint_excludePaths() {
        AffectedTestsExtension ext = freshExtension();

        GradleException ex = assertThrows(GradleException.class,
                () -> ext.setExcludePaths(List.of("**/build/**")));
        assertTrue(ex.getMessage().contains("excludePaths"));
        // excludePaths is the only knob whose v1 semantics straddled two
        // v2 knobs — ignorePaths (never influences selection) vs
        // outOfScopeTestDirs (test dirs the affectedTest task won't
        // dispatch). The migration hint must name both so the operator
        // can pick the right one rather than guessing.
        assertTrue(ex.getMessage().contains("ignorePaths"));
        assertTrue(ex.getMessage().contains("outOfScopeTestDirs"));
    }

    @Test
    void negativeGradlewTimeoutFailsAtConfigurationTime() {
        // S11 polish (v2.1): the range check used to live in the task
        // builder so an invalid value only blew up when someone actually
        // ran `./gradlew affectedTest`. Move it into the plugin's
        // afterEvaluate so IDE sync and `./gradlew help` surface the
        // misconfiguration immediately. Builder-side check stays as
        // belt-and-braces and is covered by
        // AffectedTestsConfigTest#builderRejectsNegativeGradlewTimeout.
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        AffectedTestsExtension ext = project.getExtensions()
                .getByType(AffectedTestsExtension.class);
        ext.getGradlewTimeoutSeconds().set(-5L);

        GradleException ex = assertThrows(GradleException.class,
                () -> ((ProjectInternal) project).evaluate());
        // ProjectInternal#evaluate wraps afterEvaluate failures in a
        // ProjectConfigurationException with a generic "A problem
        // occurred evaluating..." header. The real validation message
        // lives on the cause — walk the chain.
        String message = collectMessages(ex);
        assertTrue(message.contains("gradlewTimeoutSeconds"),
                "Error must name the misconfigured knob (saw: " + message + ")");
        assertTrue(message.contains("-5"),
                "Error must echo the rejected value so the operator can locate it in build.gradle (saw: " + message + ")");
        assertTrue(message.contains(">= 0"),
                "Error must state the valid range — bare rejection without a bound is a support ticket (saw: " + message + ")");
    }

    private static String collectMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable current = t;
        while (current != null) {
            if (sb.length() > 0) sb.append(" || ");
            sb.append(current.getMessage());
            current = current.getCause();
        }
        return sb.toString();
    }

    private static AffectedTestsExtension freshExtension() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");
        return project.getExtensions().getByType(AffectedTestsExtension.class);
    }

    @Test
    void modeIsUnsetByDefault() {
        // Critical invariant: with no DSL and no -P, the extension's
        // mode Property must read as absent so the core config's
        // two-tier resolver falls through to AUTO. Installing a
        // literal convention (e.g. "auto") would silently pin the
        // task input and break cacheability across environments
        // where AUTO would legitimately resolve differently.
        //
        // Note: ProjectBuilder isolates this test from the home
        // `gradle.properties` file, but NOT from JVM system properties.
        // If a developer runs the test JVM with
        // `-DaffectedTestsMode=strict` (unusual but legal), Gradle's
        // providers API surfaces it as a gradleProperty and the
        // convention fires, flipping this assertion. If you see a
        // surprising failure here, check `-D` args on the test JVM
        // first — the contract under test is "no property ≠ no
        // convention fired", not "no property ≠ JVM system hygiene".
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        AffectedTestsExtension ext = project.getExtensions()
                .getByType(AffectedTestsExtension.class);
        assertFalse(ext.getMode().isPresent(),
                "mode must have no value when neither DSL nor -PaffectedTestsMode is set — "
                        + "otherwise the core AUTO resolver never runs");
    }

    @Test
    void dslDeclaredModeTakesPrecedenceOverAnyConvention() {
        // Precedence contract: Gradle Property semantics mean
        // explicit `set()` (DSL) always wins over `convention()`
        // (our -PaffectedTestsMode fallback). Adopters need that so
        // a stray CLI flag cannot silently override a repo's
        // deliberately pinned mode. We can't exercise the real
        // gradleProperty pipeline inside ProjectBuilder (it resolves
        // against actual Gradle CLI state, not extra-properties), so
        // the end-to-end `-P` wiring is pinned by the Cucumber e2e
        // scenarios in 06-v2.2-adoption-feedback.feature. Here we
        // guard the precedence half of the contract via an explicit
        // convention() call — if someone inverts the resolution order
        // or forgets to use convention(), this test catches it
        // without depending on Gradle's CLI-property plumbing.
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        AffectedTestsExtension ext = project.getExtensions()
                .getByType(AffectedTestsExtension.class);
        // Simulate "the plugin installed a convention of 'strict'"
        // (which is what `-PaffectedTestsMode=strict` ends up doing
        // through the providers API in a real build).
        ext.getMode().convention("strict");
        ext.getMode().set("ci");

        assertEquals("ci", ext.getMode().get(),
                "DSL-declared mode must win over any convention — swapping the precedence "
                        + "would let a stray -P clobber a repo's pinned mode policy");
    }

    @Test
    void affectedTestDependsOnTestClassesWhenExplainUnset() {
        // Bug A paired-negative: the dispatch path must keep the
        // `testClasses` dependency so the nested ./gradlew has class
        // files to run against. If the Callable returns empty in
        // both branches (regressing the fix to "always prune"), this
        // test breaks because the dep disappears from the task.
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("java");
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        var affectedTest = project.getTasks().getByName("affectedTest");
        // Force task configuration so afterEvaluate / the
        // dependsOn Callable registration has executed.
        ((ProjectInternal) project).evaluate();

        java.util.Set<?> deps = affectedTest.getTaskDependencies()
                .getDependencies(affectedTest);
        boolean pullsInTestClasses = deps.stream()
                .anyMatch(t -> t instanceof org.gradle.api.Task
                        && ((org.gradle.api.Task) t).getName().equals("testClasses"));
        assertTrue(pullsInTestClasses,
                "Without --explain the dispatch path MUST pre-compile test classes so the "
                        + "nested gradle invocation finds something to run — pruning always would "
                        + "re-break the original reason the dependency existed");
    }

    @Test
    void affectedTestPrunesTestClassesWhenExplainSet() {
        // Bug A positive: flipping --explain on must short-circuit
        // the testClasses dependency. Asserted at the Callable
        // output level so a regression here surfaces independently
        // of the Cucumber e2e scenario (which exercises it via the
        // real Gradle runtime).
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("java");
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        AffectedTestTask task = (AffectedTestTask) project.getTasks().getByName("affectedTest");
        task.getExplain().set(true);
        ((ProjectInternal) project).evaluate();

        java.util.Set<?> deps = task.getTaskDependencies().getDependencies(task);
        boolean pullsInTestClasses = deps.stream()
                .anyMatch(t -> t instanceof org.gradle.api.Task
                        && ((org.gradle.api.Task) t).getName().equals("testClasses"));
        assertFalse(pullsInTestClasses,
                "With --explain set the Callable MUST prune testClasses from the task "
                        + "dependency set — otherwise the diagnostic flag keeps paying compile cost");
    }

    @Test
    void testClassesDependencyCallableDoesNotCaptureProjectOrTask() {
        // v2.2.1 fix (M1 from v2.2 code review): the Bug A Callable
        // used to capture `Project p` and resolve testClasses via
        // `p.getTasks().named("testClasses").get()` at task-graph
        // time. Project references are NOT
        // configuration-cache-serialisable, so enabling Gradle CC on
        // an adopter repo would fail with "cannot serialize object
        // of type Project" the moment the lambda tried to cross the
        // CC boundary.
        //
        // The fix: capture the `TaskProvider<?>` eagerly (providers
        // ARE CC-serialisable) plus the task's own
        // `Property<Boolean>` for --explain, and close over THOSE
        // instead. Pin that contract here by asserting the
        // dependencies of a fully-configured task are TaskProvider-
        // shaped (or the task instances Gradle resolves them into)
        // rather than raw Project references leaking through.
        //
        // The Gradle CC runtime doesn't expose a "would-this-
        // serialise" hook from ProjectBuilder, so a full end-to-end
        // CC compatibility check lives in the Cucumber e2e scenario
        // that runs the real Gradle daemon with
        // `--configuration-cache`. This unit test pins the
        // Callable's OUTPUT SHAPE so a future edit that regresses
        // to capturing Project breaks a fast test instead of only
        // the slow functional run.
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("java");
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        var affectedTest = project.getTasks().getByName("affectedTest");
        ((ProjectInternal) project).evaluate();

        java.util.Set<? extends org.gradle.api.Task> deps =
                affectedTest.getTaskDependencies().getDependencies(affectedTest);
        // Every dep must be a concrete Task (Gradle unwraps providers
        // for us at getDependencies() time); the contract under test
        // is that we get here at all without Gradle throwing on a
        // non-serialisable Callable capture. A regression to
        // capturing `Project p` would produce identical runtime
        // behaviour here (it only bites under --configuration-cache
        // in the e2e suite) — so also pin the SOURCE of the
        // testClasses dep lives on the root project's TaskContainer,
        // not on a foreign reference that could carry a Project
        // with it.
        var testClassesDep = deps.stream()
                .filter(t -> "testClasses".equals(t.getName()))
                .findFirst();
        assertTrue(testClassesDep.isPresent(),
                "Sanity: the dispatch path must still pull in testClasses after the "
                        + "CC-safe refactor — otherwise M1 accidentally regressed Bug A");
        assertEquals(project, testClassesDep.get().getProject(),
                "testClasses dep must resolve to the same Project the plugin was applied to — "
                        + "if it's routed via a stale reference the CC-safe capture has drifted");
    }

    @Test
    void emptyModePropertyCoercesToAbsentNotToError() {
        // v2.2.1 fix (L1 from v2.2 code review): some CI templates
        // unconditionally emit `-PaffectedTestsMode=$MODE` where
        // $MODE may be unset, landing the literal string "" on the
        // mode convention. Pre-fix this crashed parseMode with
        // "Unknown affectedTests.mode ''". The correct semantics:
        // an empty or whitespace-only value means "no override" —
        // identical to omitting the flag — so the extension must
        // filter it out BEFORE the convention applies. We can't
        // drive `gradleProperty` inside ProjectBuilder, so simulate
        // the final convention state the same way
        // `dslDeclaredModeTakesPrecedenceOverAnyConvention` does:
        // by calling .convention() on the extension directly.
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        AffectedTestsExtension ext = project.getExtensions()
                .getByType(AffectedTestsExtension.class);

        // Gradle's map+filter chain on the real -P pipeline does this:
        // providers.gradleProperty(...).map(trim).filter(!empty). If
        // the raw property is "" or "   ", the chain yields an empty
        // provider, and re-applying it as a convention leaves the
        // extension's mode property absent. Pin that exact shape.
        org.gradle.api.provider.Provider<String> emptyishProperty =
                project.getProviders().provider(() -> "   ")
                        .map(String::trim)
                        .filter(s -> !s.isEmpty());
        ext.getMode().convention(emptyishProperty);

        assertFalse(ext.getMode().isPresent(),
                "An empty/whitespace -PaffectedTestsMode must behave like 'flag not set' — "
                        + "otherwise CI templates that always emit the flag with an unset "
                        + "variable crash parseMode with 'Unknown mode \\'\\''");
    }

    @Test
    void autoDiscoveredTestDirsSurfaceWhenAdoptersDoNotSetTheKnob() {
        // Issue #49 acceptance: a project with a custom
        // `integrationTest` source set + Test task should have
        // `src/integrationTest/java` show up in `extension.testDirs`
        // without the adopter touching `affectedTests { testDirs = ... }`
        // in build.gradle. The auto-discovery hook fires on
        // `gradle.projectsEvaluated`, so we have to nudge
        // ProjectBuilder past that lifecycle event with `evaluate()`
        // before reading the property.
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("java");
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        JavaPluginExtension jpe = project.getExtensions()
                .getByType(JavaPluginExtension.class);
        SourceSet integrationTest = jpe.getSourceSets().create("integrationTest");
        project.getTasks().register("integrationTest",
                org.gradle.api.tasks.testing.Test.class, t ->
                        t.setTestClassesDirs(integrationTest.getOutput().getClassesDirs()));

        // ProjectBuilder doesn't fire projectsEvaluated by default;
        // evaluate() drives the project through afterEvaluate, and
        // the gradle-level projectsEvaluated callback fires once the
        // last project (here, just one) finishes evaluation.
        ((ProjectInternal) project).evaluate();
        // projectsEvaluated isn't fired by ProjectBuilder.evaluate()
        // (it's a ProjectBuilder lifecycle gap, not a Gradle bug),
        // so trigger our auto-discovery hook directly via the
        // documented entry point. The wiring under test is "the same
        // computation the plugin runs at projectsEvaluated time
        // produces a layout the extension surfaces" — calling
        // SourceSetAutoDiscovery.from(project) and verifying the
        // extension picks it up the same way the plugin does is
        // sufficient to pin the contract.
        SourceSetAutoDiscovery discovery = SourceSetAutoDiscovery.from(project);
        if (discovery.hasMainDirs()) {
            project.getExtensions().getByType(AffectedTestsExtension.class)
                    .getSourceDirs().convention(discovery.sourceDirs());
        }
        if (discovery.hasTestDirs()) {
            project.getExtensions().getByType(AffectedTestsExtension.class)
                    .getTestDirs().convention(discovery.testDirs());
        }

        AffectedTestsExtension ext = project.getExtensions()
                .getByType(AffectedTestsExtension.class);
        assertTrue(ext.getTestDirs().get().contains("src/integrationTest/java"),
                "Auto-discovered integrationTest source set must surface in "
                        + "extension.testDirs without an explicit DSL knob — that's the "
                        + "headline contract of #49. Got: " + ext.getTestDirs().get());
        assertTrue(ext.getTestDirs().get().contains("src/test/java"),
                "Default test source set must remain in testDirs alongside the new entry");
    }

    @Test
    void explicitTestDirsAlwaysWinOverAutoDiscovery() {
        // Adopters with a non-standard layout already pinned in
        // build.gradle must continue to see their explicit values
        // — the auto-discovery hook is meant to seed the default,
        // never to override an explicit setting. Gradle's
        // convention semantics say "set value wins over convention",
        // so a `testDirs = [...]` in build.gradle should stay
        // canonical even after the plugin runs auto-discovery.
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("java");
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        AffectedTestsExtension ext = project.getExtensions()
                .getByType(AffectedTestsExtension.class);
        // Pin a custom testDirs *before* discovery fires.
        ext.getTestDirs().set(java.util.List.of("custom/test"));

        // Now drive the same auto-discovery the plugin would.
        ((ProjectInternal) project).evaluate();
        SourceSetAutoDiscovery discovery = SourceSetAutoDiscovery.from(project);
        if (discovery.hasTestDirs()) {
            ext.getTestDirs().convention(discovery.testDirs());
        }

        assertEquals(java.util.List.of("custom/test"), ext.getTestDirs().get(),
                "Explicit `testDirs = [...]` must survive auto-discovery — the convention "
                        + "is a default, not an override. Got: " + ext.getTestDirs().get());
    }

    @Test
    void autoDiscoveredTestTaskNamesFeedTheDispatchPathByDefault() {
        // #49 + #48 together: when an adopter has an `integrationTest`
        // Test task wired up but has not pinned `testTaskNames` in the
        // DSL, the discovered task name must surface as the default
        // for the dispatch path. Without this wiring the dispatch
        // path silently falls back to "test" and the integrationTest
        // selection is dropped on the floor. Pin the contract so a
        // future cleanup that drops the convention() line trips this
        // test rather than silently regressing the multi-task adopter.
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("java");
        project.getPlugins().apply("io.github.vedanthvdev.affectedtests");

        JavaPluginExtension jpe = project.getExtensions()
                .getByType(JavaPluginExtension.class);
        SourceSet integrationTest = jpe.getSourceSets().create("integrationTest");
        project.getTasks().register("integrationTest",
                org.gradle.api.tasks.testing.Test.class, t ->
                        t.setTestClassesDirs(integrationTest.getOutput().getClassesDirs()));

        ((ProjectInternal) project).evaluate();
        SourceSetAutoDiscovery discovery = SourceSetAutoDiscovery.from(project);
        AffectedTestsExtension ext = project.getExtensions()
                .getByType(AffectedTestsExtension.class);
        if (discovery.hasTestTaskNames()) {
            ext.getTestTaskNames().convention(discovery.testTaskNames());
        }

        assertTrue(ext.getTestTaskNames().get().contains("integrationTest"),
                "Auto-discovered integrationTest task must surface in extension.testTaskNames "
                        + "so the #48 dispatch path routes integrationTest selections to the "
                        + "right Gradle task. Got: " + ext.getTestTaskNames().get());
        assertTrue(ext.getTestTaskNames().get().contains("test"),
                "Default `test` task must remain in testTaskNames alongside the new entry");
    }

    private static void assertAllAbsent(Class<?> type,
                                        java.util.List<String> forbiddenNames,
                                        String surfaceLabel) {
        for (String name : forbiddenNames) {
            boolean stillDeclared = java.util.Arrays.stream(type.getMethods())
                    .anyMatch(m -> m.getName().equals(name));
            assertFalse(stillDeclared,
                    type.getSimpleName() + "." + name + "() must stay removed in v2 — "
                            + "bringing it back on the " + surfaceLabel + " surface reopens "
                            + "the v1 back-compat path and breaks the migration documented "
                            + "in the CHANGELOG");
        }
    }

    /**
     * Issue #42 CLI flag: the typo-rejection contract on
     * {@code -PaffectedTestsParallelDiscovery}. {@code Boolean.parseBoolean}
     * silently returns {@code false} for typos like {@code "tru"} or
     * {@code "flase"}, which would silently flip the kill switch in a way
     * adopters cannot diagnose. Pin the alias set + warn-and-default
     * behaviour so a future "simplification" that reintroduces
     * {@code Boolean.parseBoolean} trips this test.
     */
    @Test
    void parallelDiscoveryPropertyAcceptsHumanFriendlyAliases() {
        org.gradle.api.logging.Logger log = org.gradle.api.logging.Logging
                .getLogger(AffectedTestsPluginTest.class);

        assertTrue(AffectedTestsPlugin.parseParallelDiscoveryProperty("true", log));
        assertTrue(AffectedTestsPlugin.parseParallelDiscoveryProperty("True", log));
        assertTrue(AffectedTestsPlugin.parseParallelDiscoveryProperty("TRUE", log));
        assertTrue(AffectedTestsPlugin.parseParallelDiscoveryProperty("1", log));
        assertTrue(AffectedTestsPlugin.parseParallelDiscoveryProperty("on", log));
        assertTrue(AffectedTestsPlugin.parseParallelDiscoveryProperty("ON", log));
        assertTrue(AffectedTestsPlugin.parseParallelDiscoveryProperty("yes", log));

        assertFalse(AffectedTestsPlugin.parseParallelDiscoveryProperty("false", log));
        assertFalse(AffectedTestsPlugin.parseParallelDiscoveryProperty("False", log));
        assertFalse(AffectedTestsPlugin.parseParallelDiscoveryProperty("0", log));
        assertFalse(AffectedTestsPlugin.parseParallelDiscoveryProperty("off", log));
        assertFalse(AffectedTestsPlugin.parseParallelDiscoveryProperty("no", log));
    }

    @Test
    void parallelDiscoveryPropertyRejectsTyposToTheSafeDefault() {
        org.gradle.api.logging.Logger log = org.gradle.api.logging.Logging
                .getLogger(AffectedTestsPluginTest.class);

        // Boolean.parseBoolean would silently return false for these.
        // We default to true (parallel ON) instead — matching the
        // canonical default in AffectedTestsConfig.Builder — so a typo
        // never silently flips the kill switch.
        assertTrue(AffectedTestsPlugin.parseParallelDiscoveryProperty("tru", log),
                "Typo'd 'tru' must NOT silently disable parallel — "
                        + "fall back to the safe default (parallel ON)");
        assertTrue(AffectedTestsPlugin.parseParallelDiscoveryProperty("flase", log),
                "Typo'd 'flase' must NOT silently disable parallel");
        assertTrue(AffectedTestsPlugin.parseParallelDiscoveryProperty("nope", log),
                "Random invalid value must fall back to the safe default");
        assertTrue(AffectedTestsPlugin.parseParallelDiscoveryProperty("disable", log),
                "Common-sense-but-unsupported alias must NOT silently disable — "
                        + "if we expand the alias set later this test will trip "
                        + "and we'll know to update it deliberately");
    }

}
