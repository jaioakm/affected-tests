package io.affectedtests.core.discovery;

import io.affectedtests.core.util.LogSanitizer;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.config.CommonConfigurationKeys;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtDeclaration;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtImportDirective;
import org.jetbrains.kotlin.psi.KtNullableType;
import org.jetbrains.kotlin.psi.KtObjectDeclaration;
import org.jetbrains.kotlin.psi.KtParameter;
import org.jetbrains.kotlin.psi.KtPrimaryConstructor;
import org.jetbrains.kotlin.psi.KtPsiFactory;
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry;
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry;
import org.jetbrains.kotlin.psi.KtTypeConstraint;
import org.jetbrains.kotlin.psi.KtTypeElement;
import org.jetbrains.kotlin.psi.KtTypeParameter;
import org.jetbrains.kotlin.psi.KtTypeReference;
import org.jetbrains.kotlin.psi.KtUserType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link LanguageParser} backed by {@code kotlin-compiler-embeddable}.
 * Owns a single shared {@link KotlinCoreEnvironment} per engine run
 * (one instance per {@link LanguageParsers}) and a {@link Disposable}
 * lifecycle parent that disposes the environment + its bundled
 * MockApplication / extension-point registry on {@link #close()}.
 *
 * <p><strong>Known limitation — JVM-wide MockApplication slot.</strong>
 * The IntelliJ platform underpinning {@link KotlinCoreEnvironment}
 * registers a process-wide static MockApplication on
 * {@code ApplicationManager} the first time
 * {@link KotlinCoreEnvironment#createForProduction} runs. Two
 * {@link KotlinLanguageParser} instances alive in the same JVM
 * (e.g. multi-module Gradle build with {@code --parallel} and
 * {@code kotlinEnabled = true}) collide on that slot. PR #3 shipped
 * the rollout flag default-off so the collision was theoretical for
 * the rollout window; PR #4 flipped the default and the multi-module
 * parallel posture is now load-bearing — adopters who hit a
 * collision flip {@code kotlinEnabled = false} as the documented
 * escape hatch (see README "Known limitations").
 *
 * <p>Introduced in PR #3 of issue #76 (Phase 2 Kotlin AST). The class
 * is registered with {@link LanguageParsers} only when
 * {@link io.affectedtests.core.config.AffectedTestsConfig#kotlinEnabled()}
 * is {@code true}, which is the post-PR-4 default. The pre-PR-4
 * system-property gate ({@code -Daffected-tests.kotlin.enabled})
 * was removed when the rollout closed; the DSL flag is the
 * remaining knob. When the flag is set to {@code false}, every
 * {@code .kt} file flows through the path-derived FQN routing
 * PR #1 introduced — no AST, no AST-driven strategy participation.
 *
 * <h3>Lifecycle</h3>
 *
 * <p>{@link KotlinCoreEnvironment} is none of the things
 * {@link com.github.javaparser.JavaParser} is — it is stateful, owns a
 * MockApplication + extension-point registry, requires a
 * {@link Disposable} parent for explicit teardown, and is multi-MB.
 * The plan ({@code docs/PHASE-2-KOTLIN-AST.md} §3.4) requires a
 * single shared environment per {@link ProjectIndex} disposed at
 * engine shutdown — that is what this class implements:
 *
 * <ol>
 *   <li>{@link #parseOrWarn(Path, String)} lazily bootstraps the
 *       environment on the first {@code .kt} parse via a double-
 *       checked-locking guard so concurrent first-parse callers do
 *       not race to construct redundant environments.</li>
 *   <li>{@link #close()} disposes the {@link #parentDisposable},
 *       which transitively tears down the environment, its
 *       extension-point registry, and any open
 *       {@code VirtualFileSystem} handles.</li>
 *   <li>If bootstrap fails (bad shading, missing extension XMLs,
 *       classloader collision), the failure is recorded in
 *       {@link #bootstrapFailed} and every subsequent parse returns
 *       {@code null} — the parse-failure path that callers translate
 *       into {@code parseFailureCount.incrementAndGet()} → eventual
 *       {@code DISCOVERY_INCOMPLETE} escalation. Silent degradation
 *       to "Kotlin file, naming-strategy only" is forbidden — that
 *       is the exact silent-skip class of bug Phase 1 was written
 *       to avoid.</li>
 * </ol>
 *
 * <h3>PSI walk</h3>
 *
 * <p>Once the environment is alive the per-file path is:
 * <ol>
 *   <li>Read the file content from disk (UTF-8).</li>
 *   <li>Build a {@link KtFile} via {@link KtPsiFactory#createFile(String, String)}
 *       — passing the file's basename so file-level annotations like
 *       {@code @file:JvmName} resolve their own basename context
 *       correctly inside the embeddable.</li>
 *   <li>Inside {@code ApplicationManager.runReadAction(...)}, walk
 *       the {@link KtFile} to extract the union of fields the
 *       AST-driven strategies consume — package name, primary type
 *       name (with the {@code <basename>Kt} synthetic for class-less
 *       files), imports, type-reference simple + dotted names, and
 *       per-declaration supertype simple names. The mapping to
 *       {@link FileMetadata} is one-to-one with what
 *       {@link FileMetadataExtractor} produces from a Java
 *       {@link com.github.javaparser.ast.CompilationUnit}, so
 *       Usage / Implementation / Transitive consume the record
 *       without language-specific branches.</li>
 * </ol>
 *
 * <p>The MockApplication that {@link KotlinCoreEnvironment} sets up
 * runs {@code runReadAction} as a no-op (it is single-threaded), but
 * we wrap reads in it anyway: matching the Detekt / ktlint posture
 * keeps the contract forward-compat if the embeddable later swaps in
 * a real Application implementation, and the lambda overhead is
 * negligible compared to the parse cost itself.
 *
 * <h3>{@code @file:JvmName}</h3>
 *
 * <p>{@code @file:JvmName("FooUtils")} overrides the compiled class
 * name. <strong>Phase 2 does not honour the annotation</strong>;
 * {@code Util.kt} always produces a primary type name of
 * {@code UtilKt}. Honouring {@code @file:JvmName} is tracked as a
 * follow-up issue. The parser records via {@link #fileHasJvmNameAnnotation}
 * which files in the run carry the annotation so the engine /
 * {@code --explain} surface can tell adopters they are hitting the
 * documented limitation.
 */
final class KotlinLanguageParser implements LanguageParser {

    private static final Logger log = LoggerFactory.getLogger(KotlinLanguageParser.class);

    /**
     * Compiler-pinned synthetic-class suffix the Kotlin compiler
     * appends to the basename of a class-less {@code .kt} file when
     * lowering top-level functions / properties to a JVM facade
     * class. Kept as a constant so the diff-side
     * {@link io.affectedtests.core.mapping.PathToClassMapper} and
     * the parser-side primary-type name agree on one literal —
     * adding this string in a second site by hand is the kind of
     * drift that surfaces as silent under-selection long after
     * the fact.
     */
    static final String KOTLIN_FILE_FACADE_SUFFIX = "Kt";

    /**
     * Lazy-bootstrap guard. Instance held in
     * {@link #environment} after the first successful bootstrap;
     * {@link #parentDisposable} is set in the same critical section
     * so {@link #close()} can rely on the pair being either both
     * null (no bootstrap attempted yet) or both populated.
     */
    private final Object bootstrapLock = new Object();

    /**
     * Set to {@code true} the first time the embeddable bootstrap
     * fails. Subsequent {@link #parseOrWarn} calls short-circuit
     * to {@code null} without re-attempting, so a single failure
     * produces a single WARN line — not one per {@code .kt} file
     * in the diff. The fail-closed posture matches the plan
     * (docs/PHASE-2-KOTLIN-AST.md §3.4): every {@code .kt} file in
     * the run becomes unparseable, the strategies de-dup their
     * parse-failure counters at the {@link ProjectIndex} layer, and
     * {@code DISCOVERY_INCOMPLETE} decides whether the run
     * escalates per the configured action.
     */
    private final AtomicBoolean bootstrapFailed = new AtomicBoolean(false);

    /**
     * Set to {@code true} the first time {@link #close()} runs.
     * Subsequent calls short-circuit so the disposal contract
     * (idempotency, see {@link LanguageParser#close()}) holds even
     * if a misconfigured caller invokes {@code close()} from two
     * threads concurrently or twice on the same registry.
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Files in the current run that contained a file-level
     * {@code @file:JvmName(...)} annotation. Phase 2 does not
     * honour the override (a follow-up issue tracks it); the
     * engine consults this set to surface a {@code --explain}
     * hint so adopters know they are hitting the documented
     * limitation. Bounded to one entry per run per file, so the
     * memory cost is per-run and per-Kotlin-file (typically
     * dozens, occasionally low thousands).
     */
    private final Set<Path> fileHasJvmNameAnnotation =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * Per-engine carrier the parser writes to on the four
     * adopter-visible signals (AST-mapped success, parse failure,
     * embeddable load failure — path-vs-package mismatch is recorded
     * by the engine, not by the parser, because it requires the
     * path-derived FQN context the parser does not own). Owned by
     * the {@link LanguageParsers} that constructed this instance and
     * exposed via {@link ProjectIndex#kotlinDiagnostics()} so
     * {@code AffectedTestTask}'s --explain renderer can pin the four
     * verbatim strings (issue #76 PR #4).
     *
     * <p>Constructor-injected so the parser does not have to know
     * how diagnostics are wired into the engine result; tests that
     * exercise the parser directly pass {@link KotlinDiagnostics#EMPTY}
     * (or a fresh instance when they want to assert on counter
     * values without spinning up a {@link ProjectIndex}).
     */
    private final KotlinDiagnostics diagnostics;

    /** Lazy-bootstrapped on first parse. {@code null} until then. */
    private volatile KotlinCoreEnvironment environment;
    /** Lifecycle parent for {@link #environment}. {@code null} until first parse. */
    private volatile Disposable parentDisposable;

    /**
     * Convenience constructor used by tests that exercise the parser
     * directly without an engine-supplied diagnostics carrier.
     * Production callers go through
     * {@link LanguageParsers#forConfig(io.affectedtests.core.config.AffectedTestsConfig)}
     * which threads its per-engine {@link KotlinDiagnostics} into
     * the parser via the explicit constructor.
     */
    KotlinLanguageParser() {
        this(KotlinDiagnostics.EMPTY);
    }

    KotlinLanguageParser(KotlinDiagnostics diagnostics) {
        this.diagnostics = diagnostics == null ? KotlinDiagnostics.EMPTY : diagnostics;
    }

    @Override
    public String extension() {
        return ".kt";
    }

    @Override
    public FileMetadata parseOrWarn(Path file, String label) {
        if (bootstrapFailed.get()) {
            // Already-bootstrap-failed path — no second WARN, just
            // null out the parse so {@link ProjectIndex#fileMetadata}
            // bumps {@code parseFailureCount} on this caller's
            // behalf and {@code DISCOVERY_INCOMPLETE} escalation
            // covers the run. The single WARN was emitted at the
            // failure site.
            return null;
        }
        if (closed.get()) {
            // Defence in depth: a strategy or test that holds onto
            // the registry past engine shutdown gets a clear log
            // hint instead of an obscure "Disposable already
            // disposed" stack trace from inside the embeddable.
            log.warn("Affected Tests: [{}] dropped Kotlin parse for {} — "
                            + "language parser was already closed (engine shutdown "
                            + "happened before discovery finished).",
                    label, LogSanitizer.sanitize(String.valueOf(file)));
            return null;
        }
        KotlinCoreEnvironment env = ensureEnvironment(label);
        if (env == null) {
            return null;
        }
        try {
            String content = Files.readString(file);
            // KtPsiFactory needs a Project + the basename for the
            // synthetic file. Wrapping in runReadAction matches the
            // Detekt / ktlint posture (see class-level Javadoc) —
            // it is a no-op against the MockApplication today but
            // forward-compat against a real Application
            // implementation should the embeddable swap one in.
            String basename = file.getFileName().toString();
            return ApplicationManager.getApplication().runReadAction(
                    (Computable<FileMetadata>) () -> extractMetadata(env, file, label, basename, content));
        } catch (Throwable t) {
            // Catch Throwable, not Exception — the embeddable's
            // PSI / IntelliJ-platform machinery can throw
            // {@link AssertionError} from internal invariant
            // checks, and adopter source can drive
            // {@link StackOverflowError} (deeply nested generics)
            // or {@link OutOfMemoryError} (a single multi-GB
            // {@code .kt} file fed into {@link Files#readString}).
            // Aligning the per-file catch with the bootstrap
            // catch above ensures one hostile file fails-closed
            // rather than crashing the engine. Asymmetry between
            // the two catches was the silent-crash class of bug
            // an early adversarial review surfaced. Re-check
            // {@link #closed} after the throw — a concurrent
            // close() can dispose the env mid-parse and surface
            // as a generic "Disposable already disposed" which
            // we want to route to the post-close-drop log instead
            // of the parse-failure WARN (the latter would
            // misleadingly bump {@code parseFailureCount} on a
            // teardown race).
            if (closed.get()) {
                log.warn("Affected Tests: [{}] dropped Kotlin parse for {} — "
                                + "language parser was closed mid-parse "
                                + "(engine shutdown raced with discovery).",
                        label, LogSanitizer.sanitize(String.valueOf(file)));
                return null;
            }
            // Any per-file failure (IO error, malformed source past
            // what the embeddable can recover from, runtime PSI
            // inconsistency, JVM Error) translates to {@code null}
            // so the file counts toward {@code parseFailureCount}
            // and {@code DISCOVERY_INCOMPLETE} escalation. WARN at
            // the plugin's default level — matching {@link
            // JavaLanguageParser#compilationUnit}'s posture so an
            // operator scanning the build log sees Java and Kotlin
            // parse failures with the same shape. Sanitise both
            // {@code file} (attacker-committable filename) and
            // exception message (can carry source-snippet text).
            log.warn("Affected Tests: [{}] failed to parse Kotlin file {}: {}",
                    label,
                    LogSanitizer.sanitize(String.valueOf(file)),
                    LogSanitizer.sanitize(String.valueOf(t.getMessage())));
            return null;
        }
    }

    /**
     * Returns the lazily-bootstrapped {@link KotlinCoreEnvironment},
     * constructing it under a double-checked-locking guard if this
     * is the first call. Returns {@code null} (and sets
     * {@link #bootstrapFailed}) if construction throws — every
     * subsequent {@link #parseOrWarn} call then short-circuits to
     * {@code null} without re-attempting, so a single bootstrap
     * failure produces a single WARN line per engine run.
     */
    private KotlinCoreEnvironment ensureEnvironment(String label) {
        KotlinCoreEnvironment env = environment;
        if (env != null) return env;
        synchronized (bootstrapLock) {
            env = environment;
            if (env != null) return env;
            // Re-check both flags inside the lock. Without these
            // re-checks, a thread queued on {@code bootstrapLock}
            // while T1 was failing the bootstrap would re-attempt
            // the bootstrap on the same broken-shading condition,
            // emitting a duplicate WARN line and paying the 2-5s
            // failure cost again. Mirrors the close-vs-bootstrap
            // drain protocol below — once {@link #closed} or
            // {@link #bootstrapFailed} is observable inside the
            // critical section, every queued caller exits cleanly.
            if (bootstrapFailed.get()) return null;
            if (closed.get()) return null;
            Disposable parent = Disposer.newDisposable("AffectedTestsKotlinLanguageParser");
            boolean published = false;
            try {
                CompilerConfiguration config = new CompilerConfiguration();
                // Suppress the embeddable's diagnostic stream — we
                // emit our own WARN lines via the SLF4J pipeline so
                // the build log stays in lock-step with the rest of
                // the plugin's output. {@link MessageCollector} is a
                // Kotlin {@code interface} with a {@code Companion}
                // object holding the {@code NONE} singleton, which
                // surfaces from Java as
                // {@code MessageCollector.Companion.getNONE()}.
                config.put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY,
                        MessageCollector.Companion.getNONE());
                config.put(CommonConfigurationKeys.MODULE_NAME,
                        "affected-tests-kotlin-parser");
                env = KotlinCoreEnvironment.createForProduction(
                        parent, config, EnvironmentConfigFiles.JVM_CONFIG_FILES);
                this.parentDisposable = parent;
                this.environment = env;
                published = true;
                return env;
            } catch (Throwable t) {
                // Catch Throwable, not Exception — the embeddable
                // bootstrap can throw {@link java.lang.LinkageError}
                // and friends if shading is incomplete (a
                // {@code com.intellij.*} class slipped past the
                // relocator), and those must surface as parse
                // failures, not as engine crashes. The plan
                // (docs/PHASE-2-KOTLIN-AST.md §3.4) is explicit:
                // bootstrap failure means every {@code .kt} file in
                // the run is unparseable, not "engine throws and
                // adopter sees an obscure stack trace".
                bootstrapFailed.set(true);
                // Carry the cause to {@link KotlinDiagnostics} so
                // {@code AffectedTestTask}'s --explain renderer can
                // pin the {@code Kotlin embeddable failed to load:
                // {cause}.} line (issue #76 PR #4 — one of the four
                // verbatim strings). First-cause-wins semantics
                // inside the diagnostics carrier handle the case
                // where two threads race the bootstrap; only one
                // string ends up in the explain trace, matching
                // what the WARN-line below already promises.
                //
                // Build the cause string defensively: the most
                // adopter-relevant bootstrap failure shape — a
                // shading regression that surfaces as a no-message
                // {@code LinkageError} or {@code NoClassDefFoundError}
                // — has {@link Throwable#getMessage()} returning
                // {@code null}. {@code String.valueOf(null)} would
                // emit the four-character literal "null" into the
                // pinned explain trace, defeating the whole reason
                // the cause-carrying field exists. Prefer the
                // exception type when no message is available so the
                // operator at least learns whether they hit a
                // {@code LinkageError}, an {@code AssertionError},
                // or some other class.
                String cause = t.getMessage() != null
                        ? t.getMessage()
                        : t.getClass().getName();
                diagnostics.recordEmbeddableLoadFailure(cause);
                log.warn("Affected Tests: [{}] Kotlin language parser bootstrap failed; "
                                + "every .kt file in this run will be treated as unparseable "
                                + "(DISCOVERY_INCOMPLETE escalation depends on the configured "
                                + "action for Situation.DISCOVERY_INCOMPLETE). Cause: {}",
                        label, LogSanitizer.sanitize(cause));
                log.debug("Affected Tests: Kotlin bootstrap failure stack trace", t);
                return null;
            } finally {
                // {@link KotlinCoreEnvironment#createForProduction}
                // registers child disposables on {@code parent}
                // before it can throw (mid-init LinkageError, missing
                // extension XML, OOM during MockApplication setup).
                // When that happens, {@link #parentDisposable} is
                // never published, so {@link #close()} cannot find
                // {@code parent} to dispose — the orphaned tree
                // stays pinned in IntelliJ's static Disposer
                // registry for the life of the Gradle daemon.
                // Dispose here so the failure path matches the
                // success path's resource ownership contract.
                if (!published) {
                    try {
                        Disposer.dispose(parent);
                    } catch (Throwable ignored) {
                        // Disposal of a half-constructed parent
                        // can throw the same way disposal of a
                        // healthy parent can; the close() handler
                        // already documents the bounded-leak
                        // tradeoff — match its posture.
                    }
                }
            }
        }
    }

    /**
     * Walks {@code ktFile} to produce {@link FileMetadata}.
     * <strong>Must run inside {@code runReadAction}</strong> — every
     * PSI traversal is read-locked under the IntelliJ-platform
     * contract.
     */
    private FileMetadata extractMetadata(KotlinCoreEnvironment env,
                                         Path file,
                                         String label,
                                         String basename,
                                         String content) {
        Project project = env.getProject();
        // KtPsiFactory(Project, markGenerated, eventSystemEnabled).
        // markGenerated=false — we are not synthesising code; the
        // KtFile we build represents real adopter source. eventSystemEnabled
        // =false — we never edit the resulting PSI tree, so the
        // PSI event bus is dead weight on a parse-only path and
        // turning it off shaves a few ms off the parse + cache
        // entry creation per file in adopter-scale benchmarks.
        KtPsiFactory factory = new KtPsiFactory(project, false, false);
        KtFile ktFile = factory.createFile(basename, content);

        // {@link KtPsiFactory#createFile} returns a {@link KtFile}
        // even for malformed input — syntax errors surface as
        // {@link PsiErrorElement} nodes inside an otherwise-walkable
        // tree, not as exceptions. The Java parser pins the
        // silent-drop contract by gating on
        // {@link com.github.javaparser.ParseResult#isSuccessful()};
        // the Kotlin parser must do the same or an in-flight
        // adopter edit that fails to compile silently produces a
        // partial {@link FileMetadata} with degraded
        // {@code packageName} / {@code imports} / {@code typeRefs},
        // strategies under-match against that file, no WARN is
        // logged, and {@code DISCOVERY_INCOMPLETE} never fires —
        // exactly the silent-skip class of bug Phase 2 §3.4 was
        // written to forbid.
        PsiErrorElement firstError =
                PsiTreeUtil.findChildOfType(ktFile, PsiErrorElement.class);
        if (firstError != null) {
            log.warn("Affected Tests: [{}] failed to parse Kotlin file {}: {}",
                    label,
                    LogSanitizer.sanitize(String.valueOf(file)),
                    LogSanitizer.sanitize(String.valueOf(firstError.getErrorDescription())));
            return null;
        }

        String packageName = ktFile.getPackageFqName().asString();
        String primaryTypeName = derivePrimaryTypeName(ktFile, basename);
        List<FileMetadata.Import> imports = collectImports(ktFile);
        Set<String> simpleNames = new LinkedHashSet<>();
        Set<String> dottedNames = new LinkedHashSet<>();
        collectTypeRefs(ktFile, simpleNames, dottedNames);
        List<FileMetadata.TypeDecl> typeDecls = collectTypeDecls(ktFile);

        if (hasFileLevelJvmName(ktFile)) {
            fileHasJvmNameAnnotation.add(file);
        }

        // Record the AST-mapped FQN on the engine's diagnostics
        // carrier so {@code AffectedTestTask}'s --explain renderer
        // can pin the {@code Kotlin source AST-mapped to FQN {fqn}.}
        // line. The FQN format mirrors what the JVM compiler emits:
        // {@code <package>.<primaryTypeName>} for class-bearing files,
        // {@code <package>.<basename>Kt} for class-less files (the
        // synthetic-facade contract Phase 2 PR #1 built into
        // {@link io.affectedtests.core.mapping.PathToClassMapper}).
        // The empty-package case drops the leading dot to keep the
        // string readable when an adopter's diff sits at the
        // source-root level.
        diagnostics.recordAstMapped(KotlinDiagnostics.joinFqn(packageName, primaryTypeName));

        return new FileMetadata(packageName, primaryTypeName, imports,
                simpleNames, dottedNames, typeDecls);
    }

    /**
     * Picks the {@code primaryTypeName} for {@link FileMetadata}.
     * Prefers the first top-level {@code class} / {@code interface}
     * in the file over a top-level {@code object} — matches
     * Kotlin's compiled-class shape ({@code class Foo} +
     * {@code class Bar} → {@code Foo.class}, {@code Bar.class}
     * with {@code Foo} the convention-default for the file's
     * primary type) and matches the diff side
     * ({@link io.affectedtests.core.mapping.PathToClassMapper})
     * which derives the FQN from the file's stem and would name
     * the class, not the object, for a file containing both.
     *
     * <p>When the file declares no top-level class but does
     * declare an {@code object}, that object's name wins (the
     * file's primary type IS the object — matches the
     * single-declaration adopter case). When the file declares
     * neither, falls back to the synthetic {@code <basename>Kt}
     * the Kotlin compiler emits to host the file's top-level
     * functions / properties — matching the synthetic the diff
     * side emits for production {@code .kt} files since PR #1.
     */
    private static String derivePrimaryTypeName(KtFile ktFile, String basename) {
        String firstObjectName = null;
        for (KtDeclaration decl : ktFile.getDeclarations()) {
            if (!(decl instanceof KtClassOrObject classOrObject)) continue;
            String name = classOrObject.getName();
            if (name == null || name.isEmpty()) continue;
            if (!(decl instanceof KtObjectDeclaration)) {
                // Class / interface — wins over any object that
                // appears earlier or later in the file. Aligns the
                // parser-side primary type with the diff-side
                // {@code PathToClassMapper}, which names the class.
                return name;
            }
            if (firstObjectName == null) {
                firstObjectName = name;
            }
        }
        if (firstObjectName != null) {
            return firstObjectName;
        }
        return syntheticFileFacadeName(basename);
    }

    /**
     * Returns the JVM-facade synthetic class name the Kotlin
     * compiler generates for a class-less {@code .kt} file (e.g.
     * {@code Util.kt} → {@code UtilKt}). Strips the {@code .kt}
     * suffix verbatim — Kotlin does not capitalise the basename;
     * the synthetic literally appends {@code Kt} to whatever the
     * file's stem is.
     */
    static String syntheticFileFacadeName(String basename) {
        String stem = basename;
        int dot = stem.lastIndexOf('.');
        if (dot > 0) {
            stem = stem.substring(0, dot);
        }
        return stem + KOTLIN_FILE_FACADE_SUFFIX;
    }

    private static List<FileMetadata.Import> collectImports(KtFile ktFile) {
        List<KtImportDirective> directives = ktFile.getImportDirectives();
        List<FileMetadata.Import> out = new ArrayList<>(directives.size());
        for (KtImportDirective directive : directives) {
            org.jetbrains.kotlin.name.FqName fqName = directive.getImportedFqName();
            if (fqName == null) continue;
            String name = fqName.asString();
            if (name == null || name.isEmpty()) continue;
            // Kotlin has no static-import distinction — type
            // imports and member-level imports share the same
            // syntax. The strategies that consume {@link
            // FileMetadata.Import#isStatic()} (Usage's static-import
            // tier) treat {@code false} as "regular import"; a
            // member-level Kotlin import like
            // {@code import com.example.Util.helper} therefore
            // surfaces as a regular import whose name is
            // {@code com.example.Util.helper}, and Usage's
            // tier-3 dotted match catches it the same way it
            // would for a Java {@code import com.example.Util}
            // followed by a {@code Util.helper(...)} reference.
            out.add(new FileMetadata.Import(name, false, directive.isAllUnder()));
        }
        return out;
    }

    /**
     * Walks every {@link KtTypeReference} in the file and harvests
     * type-reference names. Mirrors {@link FileMetadataExtractor}'s
     * Java contract — the resulting {@code typeRefSimpleNames}
     * surfaces every type a strategy might want to match against
     * (Usage's wildcard-package and same-package tiers, Transitive's
     * frontier filter); {@code typeRefDottedNames} holds every
     * fully-qualified-inline form for Usage's tier-3 dotted match.
     */
    private static void collectTypeRefs(KtFile ktFile,
                                        Set<String> simpleNames,
                                        Set<String> dottedNames) {
        Collection<KtTypeReference> refs =
                PsiTreeUtil.findChildrenOfType(ktFile, KtTypeReference.class);
        for (KtTypeReference ref : refs) {
            KtTypeElement element = ref.getTypeElement();
            if (element == null) continue;
            collectFromTypeElement(element, simpleNames, dottedNames);
        }
    }

    private static void collectFromTypeElement(KtTypeElement element,
                                               Set<String> simpleNames,
                                               Set<String> dottedNames) {
        // Peel off the nullable wrapper recursively: {@code Foo?} →
        // {@code KtNullableType(KtUserType("Foo"))}, and although
        // {@code Foo??} is not valid Kotlin source, defensive
        // unwrapping costs one extra dispatch and shields the
        // harvest from any future PSI shape change.
        while (element instanceof KtNullableType nullable) {
            element = nullable.getInnerType();
            if (element == null) return;
        }
        if (!(element instanceof KtUserType userType)) {
            // Function types ({@code (X) -> Y}), intersection types,
            // and dynamic types don't carry a simple identifier the
            // Java extractor's two-channel contract recognises.
            // Skipping them keeps parity with
            // {@code ClassOrInterfaceType}'s coverage on the Java
            // side. {@link FileMetadataExtractor}'s harvest uses
            // {@code findAll(ClassOrInterfaceType.class)} which
            // similarly skips primitive / array / wildcard types
            // for the same reason: they don't contribute to
            // strategy match keys.
            return;
        }
        String simple = userType.getReferencedName();
        if (simple != null && !simple.isEmpty()) {
            simpleNames.add(simple);
        }
        String dotted = qualifiedName(userType);
        if (dotted != null && dotted.indexOf('.') >= 0) {
            dottedNames.add(dotted);
        }
    }

    /**
     * Reconstructs the dotted form of a {@link KtUserType} by
     * walking its {@code qualifier} chain back to the root.
     * Returns the deepest leaf simple name when there is no
     * qualifier (in which case the dotted form is simply the
     * simple name; the caller filters those out via the
     * {@code .indexOf('.')} check above). Mirrors JavaParser's
     * {@code ClassOrInterfaceType.getNameWithScope()}.
     */
    private static String qualifiedName(KtUserType userType) {
        StringBuilder sb = new StringBuilder();
        appendQualifierChain(userType, sb);
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void appendQualifierChain(KtUserType userType, StringBuilder sb) {
        if (userType == null) return;
        KtUserType qualifier = userType.getQualifier();
        String name = userType.getReferencedName();
        if (qualifier != null) {
            appendQualifierChain(qualifier, sb);
            // Bail before appending the joining '.' if the leaf
            // name is null/empty — malformed PSI (the embeddable
            // tolerantly parses degenerate code) can produce a
            // user type whose qualifier resolves but whose
            // {@code referencedName} is absent. Without this
            // guard the StringBuilder ends with a trailing '.'
            // (e.g. "com.example."), which would pass the
            // {@code indexOf('.') >= 0} filter in
            // {@link #collectFromTypeElement} and create
            // false-positive {@code startsWith}-prefix matches in
            // Usage's tier-3 dotted-name discovery.
            if (name == null || name.isEmpty()) return;
            sb.append('.');
        }
        if (name != null) {
            sb.append(name);
        }
    }

    /**
     * Recursively collects {@link FileMetadata.TypeDecl} entries
     * for every {@link KtClassOrObject} reachable from the file.
     * Mirrors {@link FileMetadataExtractor}'s Java-side recursion
     * over nested types — companion objects, sealed-class
     * children, and inner classes all surface here so
     * {@link ImplementationStrategy}'s fixpoint walk catches
     * cross-Kotlin subtype edges that live one level deep.
     */
    private static List<FileMetadata.TypeDecl> collectTypeDecls(KtFile ktFile) {
        List<FileMetadata.TypeDecl> out = new ArrayList<>();
        for (KtDeclaration decl : ktFile.getDeclarations()) {
            collectTypeDeclsRecursive(decl, "", out);
        }
        return out;
    }

    private static void collectTypeDeclsRecursive(KtDeclaration decl,
                                                  String scopePrefix,
                                                  List<FileMetadata.TypeDecl> out) {
        if (!(decl instanceof KtClassOrObject classOrObject)) return;
        if (isGenericCompanion(classOrObject)) {
            // Anonymous {@code companion object {}} OR explicit
            // {@code companion object Companion {}} both compile to
            // {@code Outer$Companion} on the JVM. The strategies
            // match by simple name and {@code Companion} is too
            // generic to produce a usable match — surfacing it as
            // a TypeDecl would generate spurious hits against every
            // Kotlin file with a companion (a frequent shape in
            // KSP / Mockito-Kotlin / Hilt-generated code that
            // adopters routinely have on their classpath). The
            // skip applies only to the companion's own TypeDecl
            // entry; its body still recurses, so a
            // {@code class Nested} inside such a companion still
            // surfaces. Custom-named companions ({@code companion
            // object Foo}) flow through normally — {@code Foo} is
            // specific enough to participate.
            // Issue #132 — companion's body recurses under the
            // outer scope unchanged; the companion itself doesn't
            // contribute a TypeDecl so the scope prefix doesn't
            // need to advance through {@code Companion}.
            walkChildDeclarations(classOrObject, scopePrefix, out);
            return;
        }
        String simpleName = classOrObject.getName();
        if (simpleName == null || simpleName.isEmpty()) {
            // Defence-in-depth: any other unnamed declaration
            // (object literals leaked into top-level position,
            // synthetic forms) — recurse into the body but don't
            // emit a TypeDecl with an empty / null simple name
            // (the {@code FileMetadata.TypeDecl} record allows it,
            // but Implementation's match tier on
            // {@code supertypeSimpleNames.contains(...)} would
            // never resolve such a self-referential entry).
            walkChildDeclarations(classOrObject, scopePrefix, out);
            return;
        }
        List<String> superNames = collectSupertypeSimpleNames(classOrObject);
        FileMetadata.HeaderEdges headerEdges = collectHeaderEdges(classOrObject, superNames);
        // Issue #132 — qualifiedName is the in-CU path so nested decls
        // sharing a simple name across siblings don't collide on cache
        // decode (correctness finding C1) or in the FQN catalogue
        // (correctness finding C4). Top-level decls keep
        // qualifiedName == simpleName.
        String qualifiedName = scopePrefix.isEmpty() ? simpleName : scopePrefix + "." + simpleName;
        out.add(new FileMetadata.TypeDecl(simpleName, qualifiedName, superNames, headerEdges));
        walkChildDeclarations(classOrObject, qualifiedName, out);
    }

    /**
     * Issue&nbsp;#132 — extracts the six header-edge categories for a
     * Kotlin {@link KtClassOrObject}.
     *
     * <p>Mapping notes (Kotlin's syntactic surface differs from
     * Java's, so the categorisation is best-effort):
     * <ul>
     *   <li><strong>extends vs implements (categories 1 + 2)</strong>:
     *       Kotlin uses a single colon-prefixed
     *       {@code super-type list}; the first entry that uses
     *       constructor-call syntax ({@code : Base()}) is the class
     *       supertype and surfaces as {@code extends}, every other
     *       entry is an interface and surfaces as
     *       {@code implements}. For interfaces (no constructor call
     *       on any entry) all entries surface as {@code extends}
     *       (interfaces in Kotlin extend, they don't implement).
     *       Kotlin {@code object}s follow the same rule as classes.
     *       This mirrors how the JVM compiles the same declarations,
     *       so a {@code class StripeGateway : PaymentGateway} —
     *       Kotlin's syntactic shape for the killer Spring DI case —
     *       reliably surfaces in {@code implementsSimpleNames}.</li>
     *   <li><strong>permits (category 3)</strong>: Kotlin
     *       {@code sealed} types don't carry a permits list — sealed
     *       hierarchies are restricted to the same file (or
     *       compilation module on 1.7+). Always empty.</li>
     *   <li><strong>type bounds (category 4)</strong>: Walks both
     *       inline bounds ({@code <T : Foo>}) and the
     *       {@code where T : Foo, T : Bar} constraint clause.</li>
     *   <li><strong>record components (category 5)</strong>:
     *       Surfaces primary-constructor parameter types for
     *       {@code data class}es, matching how Java {@code record}s
     *       expose their component types as header edges. Plain
     *       classes also surface their primary-constructor types —
     *       Kotlin's primary constructor IS the type's identity in
     *       the same way a record's component list is.</li>
     *   <li><strong>annotations (category 6)</strong>: Class-level
     *       annotations from
     *       {@link KtClassOrObject#getAnnotationEntries()}.</li>
     * </ul>
     *
     * <p>The {@code superNames} list is the already-flattened
     * combined supertype list (categories 1 + 2 union) — passed in
     * so we can re-categorise without re-walking the PSI tree. The
     * categorisation here is the only place that needs the
     * extends-vs-implements distinction; consumers that don't care
     * (like the pre-headerEdges {@link ImplementationStrategy}'s
     * fixpoint walk) keep reading {@code supertypeSimpleNames} as
     * the union.
     */
    private static FileMetadata.HeaderEdges collectHeaderEdges(KtClassOrObject decl,
                                                               List<String> superNames) {
        List<KtSuperTypeListEntry> entries = decl.getSuperTypeListEntries();
        List<String> extendsNames = new ArrayList<>();
        List<String> implementsNames = new ArrayList<>();
        boolean isInterface = decl instanceof KtClass kt && kt.isInterface();
        if (entries != null) {
            for (KtSuperTypeListEntry entry : entries) {
                String name = supertypeEntrySimpleName(entry);
                if (name == null) continue;
                if (isInterface) {
                    // Kotlin interfaces extend, never implement. JVM
                    // compiles them with {@code extends} edges; we
                    // mirror that so a base interface change reaches
                    // tests of its sub-interface consumers.
                    extendsNames.add(name);
                } else if (entry instanceof KtSuperTypeCallEntry) {
                    // Constructor-call form ({@code : Base()}) means
                    // "this is the class supertype". Kotlin allows
                    // at most one such entry per class.
                    extendsNames.add(name);
                } else {
                    // Any non-call entry on a class declaration is an
                    // interface (Kotlin syntax forbids extending two
                    // classes). This is the Spring DI killer case
                    // shape ({@code class StripeGateway : PaymentGateway}).
                    implementsNames.add(name);
                }
            }
        }

        List<String> typeBoundNames = new ArrayList<>();
        if (decl instanceof KtClass ktClass) {
            for (KtTypeParameter param : ktClass.getTypeParameters()) {
                KtTypeReference extendsBound = param.getExtendsBound();
                appendSimpleName(extendsBound, typeBoundNames);
            }
            for (KtTypeConstraint constraint : ktClass.getTypeConstraints()) {
                appendSimpleName(constraint.getBoundTypeReference(), typeBoundNames);
            }
        }

        List<String> recordComponentNames = new ArrayList<>();
        KtPrimaryConstructor primary = decl.getPrimaryConstructor();
        if (primary != null) {
            for (KtParameter param : primary.getValueParameters()) {
                appendSimpleName(param.getTypeReference(), recordComponentNames);
            }
        }

        List<String> annotationNames = new ArrayList<>();
        for (KtAnnotationEntry ann : decl.getAnnotationEntries()) {
            org.jetbrains.kotlin.name.Name shortName = ann.getShortName();
            if (shortName != null) {
                annotationNames.add(shortName.asString());
            }
        }

        if (extendsNames.isEmpty() && implementsNames.isEmpty()
                && typeBoundNames.isEmpty() && recordComponentNames.isEmpty()
                && annotationNames.isEmpty()) {
            return FileMetadata.HeaderEdges.EMPTY;
        }
        return new FileMetadata.HeaderEdges(
                extendsNames, implementsNames, List.of(),
                typeBoundNames, recordComponentNames, annotationNames);
    }

    private static String supertypeEntrySimpleName(KtSuperTypeListEntry entry) {
        KtTypeReference ref = entry.getTypeReference();
        if (ref == null) return null;
        KtTypeElement element = ref.getTypeElement();
        while (element instanceof KtNullableType nullable) {
            element = nullable.getInnerType();
        }
        if (!(element instanceof KtUserType userType)) return null;
        String name = userType.getReferencedName();
        return (name == null || name.isEmpty()) ? null : name;
    }

    /**
     * Appends the simple name of {@code typeRef}'s outer type plus
     * any inner type-argument simple names. Mirrors how the Java
     * extractor surfaces {@code List<Customer>} as both {@code List}
     * and {@code Customer} when surfaced as a record-component
     * type — the strategy resolves either side back to its FQN.
     */
    private static void appendSimpleName(KtTypeReference typeRef, List<String> sink) {
        if (typeRef == null) return;
        KtTypeElement element = typeRef.getTypeElement();
        while (element instanceof KtNullableType nullable) {
            element = nullable.getInnerType();
        }
        if (!(element instanceof KtUserType userType)) return;
        String outer = userType.getReferencedName();
        if (outer != null && !outer.isEmpty()) {
            sink.add(outer);
        }
        // Generic-argument types ({@code val x: List<Customer>}
        // surfaces {@code Customer} too) are emitted as their own
        // {@link KtTypeReference} children inside the user type's
        // argument list — recurse so multi-arg generics surface
        // every inner type.
        for (KtTypeReference innerRef : userType.getTypeArgumentsAsTypes()) {
            appendSimpleName(innerRef, sink);
        }
    }

    /**
     * Returns {@code true} for any companion object whose simple
     * name surfaces as {@code "Companion"} — both anonymous
     * {@code companion object {}} (no source identifier; the
     * compiler synthesises the name) and explicit
     * {@code companion object Companion {}} (the source contains
     * the literal token, but it compiles to the same JVM shape).
     * Both forms produce {@code Outer$Companion} on the JVM and
     * {@code "Companion"} as a TypeDecl simple name is too
     * generic to participate usefully in strategy matching.
     * Custom-named companions ({@code companion object Foo}) are
     * not affected — {@code Foo} flows through normally.
     */
    private static boolean isGenericCompanion(KtClassOrObject decl) {
        if (!(decl instanceof KtObjectDeclaration object)) return false;
        if (!object.isCompanion()) return false;
        if (object.getNameIdentifier() == null) {
            // Anonymous {@code companion object {}} — the Kotlin
            // compiler / PSI synthesises {@code "Companion"} as
            // the simple name.
            return true;
        }
        // Explicit name. Strip and compare to the JVM-default
        // synthetic — adopters who write {@code companion object
        // Companion {}} (KSP / Mockito-Kotlin / Hilt-generated
        // shape) get the same skip as the anonymous case.
        return "Companion".equals(object.getName());
    }

    private static void walkChildDeclarations(KtClassOrObject classOrObject,
                                              String scopePrefix,
                                              List<FileMetadata.TypeDecl> out) {
        for (KtDeclaration child : classOrObject.getDeclarations()) {
            collectTypeDeclsRecursive(child, scopePrefix, out);
        }
    }

    private static List<String> collectSupertypeSimpleNames(KtClassOrObject decl) {
        List<KtSuperTypeListEntry> entries = decl.getSuperTypeListEntries();
        if (entries == null || entries.isEmpty()) return List.of();
        List<String> names = new ArrayList<>(entries.size());
        for (KtSuperTypeListEntry entry : entries) {
            KtTypeReference ref = entry.getTypeReference();
            if (ref == null) continue;
            KtTypeElement element = ref.getTypeElement();
            if (!(element instanceof KtUserType userType)) continue;
            String name = userType.getReferencedName();
            if (name != null && !name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Returns {@code true} if the file's {@code @file:...}
     * annotation list contains a {@code JvmName} entry.
     * Phase 2 surfaces this via {@code --explain} but does not
     * honour the override. The check is by simple name only —
     * matching {@code @file:JvmName(...)} and the much-rarer
     * fully-qualified {@code @file:kotlin.jvm.JvmName(...)}
     * without forcing the parser to resolve the import alias
     * graph.
     */
    private static boolean hasFileLevelJvmName(KtFile ktFile) {
        for (KtAnnotationEntry entry : ktFile.getAnnotationEntries()) {
            org.jetbrains.kotlin.name.Name shortName = entry.getShortName();
            if (shortName == null) continue;
            String text = shortName.asString();
            if ("JvmName".equals(text)) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if {@link #parseOrWarn} observed a
     * file-level {@code @file:JvmName(...)} annotation on
     * {@code file} during this engine run. The engine consults
     * this through {@link LanguageParsers} for {@code --explain}
     * surfacing — see plan §6.
     */
    boolean hasFileLevelJvmNameAnnotation(Path file) {
        return fileHasJvmNameAnnotation.contains(file);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Disposable parent;
        // Acquire {@code bootstrapLock} so the field-read happens
        // inside the same critical section a concurrent
        // {@link #ensureEnvironment} would publish into. Without
        // this lock, a thread that has just completed
        // {@code createForProduction} but has not yet written
        // {@link #parentDisposable} could publish a live
        // environment AFTER close() has already set
        // {@code closed = true} and read {@code parentDisposable}
        // as null — leaving the parser in a state where
        // {@code closed = true} but {@code environment} and
        // {@code parentDisposable} point at undisposed live state
        // (~25 MB MockApplication leak per occurrence, pinned for
        // the life of the Gradle daemon). With the lock acquired,
        // close() drains any in-flight bootstrap before reading
        // the fields, and {@link #ensureEnvironment}'s re-check of
        // {@link #closed} inside the same critical section ensures
        // a bootstrap that started racing close() exits cleanly.
        synchronized (bootstrapLock) {
            parent = this.parentDisposable;
            this.parentDisposable = null;
            this.environment = null;
        }
        if (parent == null) {
            // Bootstrap never ran (no .kt file in this engine run,
            // or the registry built the parser but never reached
            // {@link #parseOrWarn}). Nothing to dispose.
            return;
        }
        try {
            Disposer.dispose(parent);
        } catch (Throwable t) {
            // Disposal must not throw. Log at DEBUG (parse failures
            // already surfaced as WARN; failed teardown is a
            // separate, non-actionable event for the operator) and
            // continue — the bounded-leak tradeoff is documented
            // on {@link LanguageParser#close()}.
            log.debug("Affected Tests: Kotlin language parser disposal failed: {}",
                    LogSanitizer.sanitize(String.valueOf(t.getMessage())));
        }
    }
}
