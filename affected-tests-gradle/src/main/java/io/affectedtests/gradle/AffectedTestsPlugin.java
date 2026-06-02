package io.affectedtests.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.logging.Logger;
import org.gradle.api.tasks.TaskProvider;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Gradle plugin that registers the {@code affectedTest} task and the
 * {@code affectedTests} DSL extension.
 *
 * <p>Plugin ID: {@code io.github.vedanthvdev.affectedtests}
 */
public class AffectedTestsPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        AffectedTestsExtension extension = project.getExtensions()
                .create("affectedTests", AffectedTestsExtension.class);

        extension.getBaseRef().convention(
                project.getProviders().gradleProperty("affectedTestsBaseRef")
                        .orElse("origin/master")
        );
        // Mirror the baseRef pattern: let callers flip mode from the
        // command line via `-PaffectedTestsMode=local|ci|strict|auto`
        // without editing build.gradle. Useful for adoption experiments
        // ("what would STRICT mode pick on today's HEAD?") and for CI
        // jobs that want to A/B two modes from the same pipeline.
        // DSL-declared `mode = '...'` still wins because Gradle's
        // Property resolution applies explicit sets before conventions;
        // when both the DSL and the -P are absent, no value is
        // present and the core config falls through to AUTO — same as
        // before this convention existed.
        //
        // Empty/whitespace-only values are coerced to absent: a CI
        // template that always emits `-PaffectedTestsMode=$MODE` with
        // an unset $MODE would otherwise land "" on the convention and
        // fail parseMode with "Unknown affectedTests.mode ''". Trimming
        // + filtering here keeps the happy path working ("no value ==
        // no override") and the error wording targeted at actual typos.
        extension.getMode().convention(
                project.getProviders().gradleProperty("affectedTestsMode")
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
        );
        // COMMITTED-ONLY by default: the plugin's whole job is "what
        // tests does this MR touch?", and the MR is the committed diff
        // — not the dev's WIP. Matching this default to the CI reality
        // means a local `./gradlew affectedTest` run picks the same
        // tests CI will run on the same HEAD, and two runs on the same
        // commit are deterministic. Adopters who iterate on WIP tests
        // flip these back to `true` in their build.gradle; the plugin
        // never silently expands the diff boundary.
        extension.getIncludeUncommitted().convention(false);
        extension.getIncludeStaged().convention(false);
        // Issue #132 ships {@code headerEdges} as a default-on
        // strategy. The DSL knob {@code headerEdgesEnabled = false}
        // is the documented one-flag kill switch; removing
        // {@code "headerEdges"} from the strategies list here is the
        // secondary opt-out for adopters who curate the strategies
        // set explicitly (mirrors how transitive can be dropped the
        // same way). The core-side
        // {@link io.affectedtests.core.config.AffectedTestsConfig.Builder#strategies}
        // default already includes headerEdges; this convention only
        // surfaces the same list to adopters who never override it.
        extension.getStrategies().convention(
                List.of("naming", "usage", "impl", "transitive", "headerEdges"));
        extension.getTransitiveDepth().convention(4);
        extension.getTestSuffixes().convention(List.of("Test", "IT", "ITTest", "IntegrationTest"));
        extension.getSourceDirs().convention(List.of("src/main/java"));
        extension.getTestDirs().convention(List.of("src/test/java"));
        extension.getTestTaskNames().convention(List.of("test"));
        // No convention for ignorePaths: an empty Gradle provider is how
        // we signal "let the core config builder pick the default list".
        // Setting a convention here would stop callers who want to
        // explicitly wipe the default list with an empty list, and
        // would also pin the list shape to this file rather than to
        // the core {@code AffectedTestsConfig.Builder.DEFAULT_IGNORE_PATHS}.
        extension.getIncludeImplementationTests().convention(true);
        extension.getImplementationNaming().convention(List.of("Impl", "Default"));
        // Mirror the baseRef / mode pattern: let callers flip the
        // engine-level discovery dispatch from serial to parallel (or
        // back) without editing build.gradle, via
        // {@code -PaffectedTestsParallelDiscovery=false}. The property
        // name follows the existing {@code affectedTests*} camelCase
        // convention used by every other override
        // ({@code affectedTestsBaseRef}, {@code affectedTestsMode},
        // …). The adopter-facing kill switch is documented on
        // {@link io.affectedtests.core.config.AffectedTestsConfig#parallelDiscovery()}.
        //
        // We accept a small set of human-friendly aliases (true/false,
        // 1/0, on/off, yes/no, case-insensitive) and emit a build-log
        // WARN for anything else — {@code Boolean.parseBoolean}
        // silently returns {@code false} for typos like "tru" or
        // "flase", which would silently flip the kill switch in a way
        // adopters cannot diagnose.
        Logger pluginLog = project.getLogger();
        extension.getParallelDiscovery().convention(
                project.getProviders().gradleProperty("affectedTestsParallelDiscovery")
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(raw -> parseParallelDiscoveryProperty(raw, pluginLog))
                        .orElse(true)
        );

        // Issue #76 PR #4 — Kotlin AST is first-class. The PR #3
        // {@code -Daffected-tests.kotlin.enabled} system-property
        // gate was removed when the rollout closed (see
        // docs/PHASE-2-KOTLIN-AST.md §9 PR #4). The DSL property
        // ({@code affectedTests { kotlinEnabled = false }}) remains
        // as the documented escape hatch for adopters who hit a
        // regression. Default ON.
        extension.getKotlinEnabled().convention(true);

        // Issue #132 — header-edges discovery strategy defaults.
        // Default ON, no per-category opt-out, depth 1, sibling cap
        // 5. The ignore-glob list is intentionally NOT given a
        // convention here: when the Gradle Property is unset, the
        // core {@link io.affectedtests.core.config.AffectedTestsConfig.Builder#headerEdgesIgnore}
        // default fires, which keeps the canonical "framework noise
        // muted" list in one place. Setting the convention to a
        // copy would silently fork the two lists; the Property's
        // {@code orElse}-on-the-task-side relies on this absence to
        // distinguish "adopter explicitly emptied the list" from
        // "adopter didn't touch the knob".
        extension.getHeaderEdgesEnabled().convention(true);
        extension.getHeaderEdgesDepth().convention(1);
        extension.getHeaderEdgesMaxSiblings().convention(5);

        Project rootProject = project.getRootProject();
        Directory rootDir = rootProject.getLayout().getProjectDirectory();

        project.getTasks().register("affectedTest", AffectedTestTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Runs only the tests affected by changes in the current branch.");

            task.getBaseRef().set(extension.getBaseRef());
            task.getIncludeUncommitted().set(extension.getIncludeUncommitted());
            task.getIncludeStaged().set(extension.getIncludeStaged());
            task.getStrategies().set(extension.getStrategies());
            task.getTransitiveDepth().set(extension.getTransitiveDepth());
            task.getTestSuffixes().set(extension.getTestSuffixes());
            task.getSourceDirs().set(extension.getSourceDirs());
            task.getTestDirs().set(extension.getTestDirs());
            task.getTestTaskNames().set(extension.getTestTaskNames());
            task.getIgnorePaths().set(extension.getIgnorePaths());
            task.getOutOfScopeTestDirs().set(extension.getOutOfScopeTestDirs());
            task.getOutOfScopeSourceDirs().set(extension.getOutOfScopeSourceDirs());
            task.getIncludeImplementationTests().set(extension.getIncludeImplementationTests());
            task.getImplementationNaming().set(extension.getImplementationNaming());
            task.getParallelDiscovery().set(extension.getParallelDiscovery());
            task.getKotlinEnabled().set(extension.getKotlinEnabled());
            task.getHeaderEdgesEnabled().set(extension.getHeaderEdgesEnabled());
            task.getHeaderEdgesExclude().set(extension.getHeaderEdgesExclude());
            task.getHeaderEdgesDepth().set(extension.getHeaderEdgesDepth());
            task.getHeaderEdgesMaxSiblings().set(extension.getHeaderEdgesMaxSiblings());
            task.getHeaderEdgesIgnore().set(extension.getHeaderEdgesIgnore());
            task.getMode().set(extension.getMode());
            task.getOnEmptyDiff().set(extension.getOnEmptyDiff());
            task.getOnAllFilesIgnored().set(extension.getOnAllFilesIgnored());
            task.getOnAllFilesOutOfScope().set(extension.getOnAllFilesOutOfScope());
            task.getOnUnmappedFile().set(extension.getOnUnmappedFile());
            task.getOnDiscoveryEmpty().set(extension.getOnDiscoveryEmpty());
            task.getOnDiscoveryIncomplete().set(extension.getOnDiscoveryIncomplete());
            task.getGradlewTimeoutSeconds().set(extension.getGradlewTimeoutSeconds());

            task.getRootDir().set(rootDir);
            task.getSubprojectPaths().set(project.provider(() -> collectSubprojectPaths(rootProject)));

            // Ensure test classes are compiled before the nested gradle
            // invocation runs. Without this, a fresh CI checkout would
            // have nothing to test and Gradle would fail with "No tests
            // found for given includes".
            //
            // Wrapped in a {@link Callable} so the dependency is
            // materialised at task-graph calculation time, which is
            // after Gradle has parsed {@code --explain} into
            // {@link AffectedTestTask#getExplain()}. When the operator
            // asked for the decision trace we skip every
            // {@code testClasses} dependency — {@code --explain}
            // short-circuits before dispatch and never consumes a
            // class file, so forcing a compile turns a 3-second
            // diagnostic into a multi-minute full compile. The
            // dispatch path still picks them up as before.
            //
            // CC-safe capture: we resolve the {@code TaskProvider<?>}
            // for each subproject's {@code testClasses} task eagerly
            // (providers are configuration-cache-serialisable) and
            // close the Callable over that Provider plus the task's
            // own {@code Property<Boolean>}. We deliberately do NOT
            // capture {@code Project p} or {@code task} — either would
            // make the lambda unserialisable when an adopter enables
            // {@code org.gradle.configuration-cache=true}.
            var explainFlag = task.getExplain();
            rootProject.allprojects(p -> p.getPluginManager().withPlugin("java", unused -> {
                TaskProvider<?> testClasses = p.getTasks().named("testClasses");
                Callable<List<TaskProvider<?>>> testClassesWhenDispatching = () -> {
                    if (Boolean.TRUE.equals(explainFlag.getOrElse(false))) {
                        return List.of();
                    }
                    return List.of(testClasses);
                };
                task.dependsOn(testClassesWhenDispatching);
            }));
        });

        // Validate scalar-range constraints at configuration completion so
        // operators get feedback during IDE sync / a dry `./gradlew help`
        // run instead of having to execute the task to see a negative
        // timeout get rejected. The task-side builder keeps its own
        // range check as belt-and-braces for programmatic callers that
        // bypass the extension.
        project.afterEvaluate(p -> {
            Long timeout = extension.getGradlewTimeoutSeconds().getOrNull();
            if (timeout != null && timeout < 0L) {
                throw new GradleException(
                        "affectedTests.gradlewTimeoutSeconds must be >= 0 "
                                + "(0 disables the timeout); got " + timeout);
            }
        });

        // Source-set auto-discovery (issue #49): when the consumer hasn't
        // explicitly set sourceDirs / testDirs / testTaskNames in
        // build.gradle, we walk every subproject's {@link JavaPluginExtension}
        // source sets and seed the conventions from the discovered layout.
        //
        // Hooked via {@code gradle.projectsEvaluated} rather than the root's
        // own {@code afterEvaluate} so every subproject's
        // {@code apply java} block (including those triggered by Spring
        // Boot / convention plugins inside their own
        // {@code afterEvaluate} listeners) has had a chance to declare
        // its source sets and Test tasks. Doing this in root-only
        // afterEvaluate would silently drop subprojects' source sets in
        // the common multi-module shape — exactly the regression #49
        // is raised to prevent.
        //
        // The replacement is routed through {@code Property.convention(value)}
        // so an explicit {@code extension.testDirs = [...]} in
        // build.gradle still wins. Gradle's convention semantics
        // ("default if no explicit value set") deliver that
        // out-of-the-box without an extra is-set check on our side.
        project.getGradle().projectsEvaluated(g ->
                seedAutoDiscoveredConventions(rootProject, extension));
    }

    /**
     * Replaces the static {@code "src/main/java"} / {@code "src/test/java"}
     * / {@code "test"} conventions with auto-discovered values when the
     * consumer hasn't explicitly set the property. The replacement is
     * routed through {@code Property.convention(value)} so an explicit
     * {@code extension.testDirs = [...]} in build.gradle still wins —
     * Gradle's convention semantics already say "convention is the
     * default if no value is set".
     *
     * <p>We deliberately use a single static {@code List<String>} rather
     * than a lazy {@code Provider} because:
     * <ul>
     *   <li>The walk runs at {@code afterEvaluate} time, which is also
     *       when the discovery happens — there's no benefit to deferring
     *       it to the task-execution thread.</li>
     *   <li>Configuration cache safety is simpler with a static list:
     *       no captured {@code Project} reference, no provider chain to
     *       serialise.</li>
     *   <li>The overhead is paid exactly once per build, not once per
     *       task that wires through {@code task.testDirs.set(extension.testDirs)}.</li>
     * </ul>
     */
    private static void seedAutoDiscoveredConventions(Project rootProject,
                                                      AffectedTestsExtension extension) {
        SourceSetAutoDiscovery discovery = SourceSetAutoDiscovery.from(rootProject);
        if (discovery.hasMainDirs()) {
            extension.getSourceDirs().convention(discovery.sourceDirs());
        }
        if (discovery.hasTestDirs()) {
            extension.getTestDirs().convention(discovery.testDirs());
        }
        if (discovery.hasTestTaskNames()) {
            // Feeds the #48 dispatch path: discovered Test-task names
            // (e.g. ["test", "integrationTest"]) become the default for
            // per-task routing. Explicit testTaskNames in the DSL still
            // win because convention() only fills the unset case.
            extension.getTestTaskNames().convention(discovery.testTaskNames());
        }
    }

    /**
     * Returns a map of relative-path-from-root-project-dir (empty string for the
     * root project itself) to the Gradle path of each project. The task uses
     * this map to route {@code --tests} filters to the correct module task.
     */
    private static Map<String, String> collectSubprojectPaths(Project rootProject) {
        File rootDir = rootProject.getProjectDir();
        Map<String, String> result = new LinkedHashMap<>();
        rootProject.getAllprojects().forEach(p -> {
            String relative = relativiseNormalised(rootDir, p.getProjectDir());
            result.putIfAbsent(relative, p.getPath());
        });
        return result;
    }

    private static String relativiseNormalised(File rootDir, File projectDir) {
        String relative = rootDir.toPath().relativize(projectDir.toPath()).toString();
        if (relative.isEmpty()) {
            return "";
        }
        return relative.replace(File.separatorChar, '/');
    }

    /**
     * Parses the {@code -PaffectedTestsParallelDiscovery} property
     * value, accepting a small set of human-friendly aliases and
     * warning on anything else.
     *
     * <p>{@link Boolean#parseBoolean(String)} is unsuitable here:
     * it returns {@code false} for every non-{@code "true"} input,
     * which means typos like {@code "tru"} or {@code "flase"}
     * silently flip the kill switch in a way the adopter cannot
     * diagnose. This helper rejects unknown values to a default
     * of {@code true} (matching {@link
     * io.affectedtests.core.config.AffectedTestsConfig.Builder}'s
     * canonical default) and emits a build-log WARN naming the bad
     * value so the operator notices.
     *
     * <p>Package-private for unit-test reach.
     */
    static boolean parseParallelDiscoveryProperty(String raw, Logger log) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "true", "1", "on", "yes" -> true;
            case "false", "0", "off", "no" -> false;
            default -> {
                log.warn("Affected Tests: ignoring -PaffectedTestsParallelDiscovery='{}'; "
                        + "expected one of true|false|1|0|on|off|yes|no. "
                        + "Falling back to the default (parallel discovery ON).", raw);
                yield true;
            }
        };
    }

}
