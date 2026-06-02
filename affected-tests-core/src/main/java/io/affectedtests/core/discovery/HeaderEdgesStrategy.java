package io.affectedtests.core.discovery;

import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Issue&nbsp;#132 — header-edges discovery strategy.
 *
 * <p>The four pre-existing strategies (naming / usage / impl /
 * transitive) walk <em>source-level type references in test bodies</em>
 * and <em>subtype edges from changed classes downward</em>. None of
 * them walks from a changed concrete class <em>up</em> to its
 * supertypes (extends / implements / permits) or <em>out</em> to its
 * header-declared types (generic bounds, record components, class-level
 * annotations).
 *
 * <p>That gap silently misses the single most common Spring DI shape:
 * a {@code class StripeGateway implements PaymentGateway} change does
 * not select {@code OrderServiceTest} when {@code OrderService}
 * {@code @Autowired}s the interface — no source-level edge connects
 * the impl file to the consumer test. This strategy closes the gap
 * by treating anything that appears in a class declaration <em>before
 * the opening {@code &#123;}</em> as part of the class's identity:
 * when a class with header-edge type references is in the diff, those
 * targets are added to the changed-class set and run through the
 * existing four strategies.
 *
 * <h3>The six categories</h3>
 *
 * <ol>
 *   <li><strong>extends</strong> — direct base classes. A class has
 *       at most one entry; an interface can have many. Killer case:
 *       {@code class Dog extends Animal} surfaces {@code Animal}
 *       so {@code AnimalTest} fires against an override change.</li>
 *   <li><strong>implements</strong> — interfaces a class declares.
 *       The Spring DI killer case lives here.</li>
 *   <li><strong>permits</strong> — sealed-hierarchy permits list.
 *       A change to a permitted subtype surfaces the sealed parent
 *       so pattern-match exhaustiveness tests fire.</li>
 *   <li><strong>type-bounds</strong> — generic type-parameter bounds.
 *       {@code class Foo<T extends Validatable>} surfaces
 *       {@code Validatable}; multi-bound
 *       {@code <T extends Foo &amp; Bar>} surfaces both.</li>
 *   <li><strong>record-components</strong> — types declared in a
 *       Java {@code record}'s parameter list or a Kotlin
 *       {@code data class}'s primary constructor. Generics flatten
 *       ({@code record Order(Customer, List&lt;Product&gt;)}
 *       surfaces {@code Customer}, {@code List}, {@code Product}).</li>
 *   <li><strong>annotations</strong> — class-level annotations
 *       (not method / field annotations). Adopter-defined
 *       {@code @CompanyAuditLog} fires; framework annotations are
 *       filtered by the default ignore globs.</li>
 * </ol>
 *
 * <h3>Safety contract</h3>
 *
 * <p>Three layered defences keep the strategy from exploding the
 * selection set:
 *
 * <ul>
 *   <li><strong>Ignore globs</strong>. {@link AffectedTestsConfig#headerEdgesIgnore()}
 *       drops framework / JDK targets ({@code java.lang.**},
 *       {@code org.springframework.**}, etc.) that pollute selection
 *       without contributing signal.</li>
 *   <li><strong>Depth bound</strong>. Default {@code 1} — walk the
 *       immediate header-declared types only. {@code 2} walks one more
 *       hop. Clamped at {@code 2}; higher values rapidly approach
 *       {@code FULL_SUITE} on real codebases.</li>
 *   <li><strong>Sibling cap</strong>. Default {@code 5} — when a
 *       header-edge-added type has more direct subtypes than the cap,
 *       {@code ImplementationStrategy}'s downward walk from THAT
 *       added type is suppressed. The added type still contributes
 *       via naming / usage / transitive — only the explosion vector
 *       is killed. Motivating case: {@code PaymentController extends
 *       BaseController} with 52 subclasses; without the cap, naming
 *       would select every {@code *ControllerTest} in the codebase.</li>
 * </ul>
 *
 * <h3>FQN resolution</h3>
 *
 * <p>Simple names in {@code HeaderEdges} ({@code Animal},
 * {@code PaymentGateway}) are resolved to FQNs via — in order:
 * <ol>
 *   <li>Non-wildcard imports on the source class's file (the
 *       common case — interfaces and base classes are almost
 *       always explicitly imported);</li>
 *   <li>Same-package lookup (the source class and its target sit
 *       in the same package, no import needed);</li>
 *   <li>Wildcard imports against the project's FQN catalogue;</li>
 *   <li>Same-package wildcard — i.e. a simple name with a single
 *       project-wide unambiguous FQN match;</li>
 *   <li>If unresolved, the edge is recorded with
 *       {@link EdgeStatus#UNRESOLVED} for {@code --explain} and
 *       silently dropped from the augmentation set — under-selection
 *       beats over-selection on an ambiguous resolution.</li>
 * </ol>
 *
 * <h3>Determinism</h3>
 *
 * <p>The strategy is deterministic for a given
 * {@code (changedProductionClasses, ProjectIndex, config)} triple.
 * Iteration uses {@link LinkedHashSet} / {@link LinkedHashMap} so
 * the {@link #augment} result, the {@code --explain} edge list, and
 * the augmented type set all preserve insertion order — adopter
 * diffing the {@code --explain} JSON between runs sees stable
 * output.
 */
public final class HeaderEdgesStrategy {

    private static final Logger log = LoggerFactory.getLogger(HeaderEdgesStrategy.class);

    private final AffectedTestsConfig config;

    public HeaderEdgesStrategy(AffectedTestsConfig config) {
        this.config = config;
    }

    /**
     * Categorisation outcome for a single header-edge reference.
     *
     * <ul>
     *   <li>{@link #ADDED} — successfully resolved to an FQN, not
     *       ignored, included in the augmented set.</li>
     *   <li>{@link #IGNORED_BY_GLOB} — resolved to an FQN that
     *       matched a {@link AffectedTestsConfig#headerEdgesIgnore()}
     *       glob; surfaces in {@code --explain} so adopters can see
     *       which glob killed which edge.</li>
     *   <li>{@link #IGNORED_BY_CATEGORY} — the source's category is
     *       in {@link AffectedTestsConfig#headerEdgesExclude()} so
     *       the strategy never attempted resolution.</li>
     *   <li>{@link #SKIPPED_SIBLING_CAP} — the added FQN survived
     *       ignore globs but has more than
     *       {@link AffectedTestsConfig#headerEdgesMaxSiblings()}
     *       direct subtypes; the FQN is still in the augmented
     *       set but {@code ImplementationStrategy} is told not to
     *       walk DOWN from it.</li>
     *   <li>{@link #UNRESOLVED} — the simple name could not be
     *       resolved to a project FQN (no matching import, no
     *       same-package candidate, no global lookup match). Edge
     *       is dropped from the augmented set but appears in
     *       {@code --explain} so adopters can audit the gap.</li>
     * </ul>
     */
    public enum EdgeStatus {
        ADDED, IGNORED_BY_GLOB, IGNORED_BY_CATEGORY, SKIPPED_SIBLING_CAP, UNRESOLVED
    }

    /**
     * Diagnostic edge surfaced by {@link AugmentationResult#edges()}
     * and consumed by the {@code --explain} renderer (text + JSON).
     *
     * @param sourceFqn  the changed class the edge originates from
     * @param targetName the header-edge target — resolved FQN when
     *                   {@link #targetFqn} is non-null, otherwise the
     *                   bare simple name from the AST (the
     *                   {@link EdgeStatus#UNRESOLVED} case)
     * @param targetFqn  the resolved target FQN, or {@code null} on
     *                   {@link EdgeStatus#UNRESOLVED}
     * @param category   one of the six
     *                   {@link AffectedTestsConfig#HEADER_EDGE_EXTENDS}
     *                   / {@code HEADER_EDGE_IMPLEMENTS} / etc.
     *                   constants
     * @param status     the categorisation outcome
     * @param ignoreGlob the glob that matched on
     *                   {@link EdgeStatus#IGNORED_BY_GLOB} (the same
     *                   string from
     *                   {@link AffectedTestsConfig#headerEdgesIgnore()}
     *                   that fired). {@code null} for every other
     *                   status. Surfaces verbatim in {@code --explain}
     *                   so adopters can grep their config for the
     *                   exact glob that suppressed an edge.
     */
    public record HeaderEdge(
            String sourceFqn,
            String targetName,
            String targetFqn,
            String category,
            EdgeStatus status,
            String ignoreGlob) {

        public HeaderEdge {
            // Records that flow into --explain JSON / fixture-pinned
            // assertions must hold immutable, normalised values. null
            // sourceFqn / category would silently render as the
            // literal string "null" in the renderer; reject them at
            // construction.
            if (sourceFqn == null) {
                throw new IllegalArgumentException("sourceFqn must not be null");
            }
            if (targetName == null) {
                throw new IllegalArgumentException("targetName must not be null");
            }
            if (category == null) {
                throw new IllegalArgumentException("category must not be null");
            }
            if (status == null) {
                throw new IllegalArgumentException("status must not be null");
            }
            // ignoreGlob is non-null iff status is IGNORED_BY_GLOB. The
            // pair is a discriminated union surfaced verbatim in
            // --explain — letting them drift produces either a "null"
            // string in the rendered glob slot or a stale glob value on
            // an ADDED edge, both of which a fixture-pinned assertion
            // would catch only by accident. Enforce the contract at
            // construction so consumers can read status and trust glob.
            if ((status == EdgeStatus.IGNORED_BY_GLOB) != (ignoreGlob != null)) {
                throw new IllegalArgumentException(
                        "ignoreGlob must be non-null iff status is IGNORED_BY_GLOB"
                                + " (status=" + status + ", ignoreGlob="
                                + (ignoreGlob == null ? "null" : "'" + ignoreGlob + "'") + ")");
            }
        }
    }

    /**
     * Result of {@link #augment(Set, ProjectIndex)}.
     *
     * @param augmentedTypes         the union of the original
     *                               {@code changedProductionClasses}
     *                               input and every header-edge target
     *                               that resolved + passed the ignore-
     *                               glob / category-opt-out filters.
     *                               Always a superset of the original
     *                               input — the strategy is purely
     *                               additive, it never removes a
     *                               directly-changed class from the
     *                               run.
     * @param suppressedFromImplWalk types that
     *                               {@link ImplementationStrategy}
     *                               must NOT walk DOWN from. Always
     *                               a subset of {@link #augmentedTypes}
     *                               minus the original input — only
     *                               header-edge-added types are
     *                               candidates for the cap. Empty
     *                               when the strategy is off, when
     *                               every added type has fewer
     *                               than {@code headerEdgesMaxSiblings}
     *                               direct subtypes, or when the cap
     *                               is set to {@link Integer#MAX_VALUE}.
     * @param edges                  per-edge diagnostic list for
     *                               {@code --explain}. Preserves
     *                               iteration order of the
     *                               augmentation walk (changed-class
     *                               first, then category-by-category
     *                               within each class) so the rendered
     *                               output is reproducible across runs.
     */
    public record AugmentationResult(
            Set<String> augmentedTypes,
            Set<String> suppressedFromImplWalk,
            List<HeaderEdge> edges) {

        public AugmentationResult {
            augmentedTypes = Collections.unmodifiableSet(
                    new LinkedHashSet<>(augmentedTypes));
            suppressedFromImplWalk = Collections.unmodifiableSet(
                    new LinkedHashSet<>(suppressedFromImplWalk));
            edges = List.copyOf(edges);
        }

        /**
         * No-op result — augmented set equals the input, no
         * suppressions, no edges. Returned by the engine wiring
         * when {@link AffectedTestsConfig#headerEdgesEnabled()} is
         * {@code false} or {@code STRATEGY_HEADER_EDGES} is not in
         * the configured strategies list. Saves callers from
         * constructing the trivial-shape result inline.
         */
        public static AugmentationResult identity(Set<String> changed) {
            return new AugmentationResult(changed, Set.of(), List.of());
        }
    }

    /**
     * Augments {@code changedProductionClasses} with the header-edge
     * targets visible from the project index. See class-level Javadoc
     * for the resolution / filter / cap algorithm.
     *
     * <p>Returns an {@link AugmentationResult#identity(Set)} when:
     * <ul>
     *   <li>{@link AffectedTestsConfig#headerEdgesEnabled()} is
     *       {@code false} (the documented one-flag kill switch);</li>
     *   <li>{@link AffectedTestsConfig#headerEdgesDepth()} is
     *       {@code 0} (degenerate config — equivalent to the
     *       kill switch);</li>
     *   <li>{@code changedProductionClasses} is empty (no class to
     *       walk from);</li>
     *   <li>every header-edge category is excluded via
     *       {@link AffectedTestsConfig#headerEdgesExclude()}.</li>
     * </ul>
     */
    public AugmentationResult augment(Set<String> changedProductionClasses, ProjectIndex index) {
        if (!config.headerEdgesEnabled()) {
            return AugmentationResult.identity(changedProductionClasses);
        }
        if (config.headerEdgesDepth() <= 0) {
            return AugmentationResult.identity(changedProductionClasses);
        }
        if (changedProductionClasses.isEmpty()) {
            return AugmentationResult.identity(changedProductionClasses);
        }
        Set<String> excluded = config.headerEdgesExclude();
        if (excluded.containsAll(Set.of(
                AffectedTestsConfig.HEADER_EDGE_EXTENDS,
                AffectedTestsConfig.HEADER_EDGE_IMPLEMENTS,
                AffectedTestsConfig.HEADER_EDGE_PERMITS,
                AffectedTestsConfig.HEADER_EDGE_TYPE_BOUNDS,
                AffectedTestsConfig.HEADER_EDGE_RECORD_COMPONENTS,
                AffectedTestsConfig.HEADER_EDGE_ANNOTATIONS))) {
            return AugmentationResult.identity(changedProductionClasses);
        }

        // Issue #132 follow-up (perf-002): skip the
        // ProjectFqnCatalogue.build() walk when no changed class has
        // any header edges to walk. The catalogue scans every source
        // file in the project's metadata — O(sourceFiles) — and is
        // useless on a diff that touches only files whose TypeDecls
        // are header-edge-free (e.g. plain DTOs, util classes with
        // no extends/implements/annotations). The check below is
        // O(changedClasses) and the dominant warm-cache path bottoms
        // out early.
        if (!anyChangedClassHasHeaderEdges(changedProductionClasses, index)) {
            log.debug("[headerEdges] no changed class has header edges — "
                    + "skipping catalogue build ({} class(es) checked)",
                    changedProductionClasses.size());
            return AugmentationResult.identity(changedProductionClasses);
        }

        ProjectFqnCatalogue catalogue = ProjectFqnCatalogue.build(index);
        List<PathMatcher> ignoreMatchers = compileIgnoreMatchers(config.headerEdgesIgnore());

        // Augmented set seeded with the input. Insertion order matters
        // for downstream determinism so the LinkedHashSet is the right
        // shape — every strategy that consumes the augmented set
        // iterates in encounter order.
        Set<String> augmented = new LinkedHashSet<>(changedProductionClasses);
        Set<String> suppressedFromImplWalk = new LinkedHashSet<>();
        List<HeaderEdge> edges = new ArrayList<>();

        int depth = config.headerEdgesDepth();
        // Frontier-driven walk. The first iteration walks the original
        // input; each subsequent iteration walks only the newly-added
        // entries from the previous round (already-walked entries are
        // skipped to keep cost bounded on cycles like
        // {@code class Foo extends Bar} / {@code class Bar extends Foo}
        // which the AST allows on malformed source).
        Set<String> frontier = new LinkedHashSet<>(changedProductionClasses);
        Set<String> walked = new HashSet<>();
        for (int hop = 0; hop < depth && !frontier.isEmpty(); hop++) {
            Set<String> nextFrontier = new LinkedHashSet<>();
            for (String sourceFqn : frontier) {
                if (!walked.add(sourceFqn)) continue;
                augmentFromFqn(sourceFqn, catalogue, excluded, ignoreMatchers,
                        augmented, nextFrontier, edges);
            }
            frontier = nextFrontier;
        }

        // Sibling-cap pass — only over the types that were ADDED
        // (i.e. members of augmented \ originalChangedSet). The cap
        // is a per-added-type decision, so the per-edge ADDED records
        // above don't need to be rewritten; we append a separate
        // SKIPPED_SIBLING_CAP record per offending type to keep the
        // edge log auditable.
        int maxSiblings = config.headerEdgesMaxSiblings();
        for (String addedFqn : augmented) {
            if (changedProductionClasses.contains(addedFqn)) continue;
            int siblingCount = catalogue.directSubtypeCount(addedFqn);
            if (siblingCount > maxSiblings) {
                suppressedFromImplWalk.add(addedFqn);
                edges.add(new HeaderEdge(
                        addedFqn,
                        addedFqn,
                        addedFqn,
                        "sibling-cap",
                        EdgeStatus.SKIPPED_SIBLING_CAP,
                        null));
                log.debug("[headerEdges] sibling-cap fired for {}: {} direct subtypes > cap {}",
                        LogSanitizer.sanitize(addedFqn), siblingCount, maxSiblings);
            }
        }

        log.info("[headerEdges] depth={} maxSiblings={} excluded={}: "
                        + "{} input class(es) → {} augmented, {} suppressed-impl-walk, {} diagnostic edge(s)",
                config.headerEdgesDepth(), config.headerEdgesMaxSiblings(),
                excluded.isEmpty() ? "[]" : excluded,
                changedProductionClasses.size(), augmented.size(),
                suppressedFromImplWalk.size(), edges.size());
        return new AugmentationResult(augmented, suppressedFromImplWalk, edges);
    }

    /**
     * Issue&nbsp;#132 follow-up (perf-002) — peeks at the project
     * index to short-circuit the strategy when no diff class has any
     * header edges to walk. The walk-time cost is dominated by
     * {@link ProjectFqnCatalogue#build}, which iterates every source
     * file's metadata; on a diff of pure plain-old-Java DTOs (zero
     * extends / implements / annotations / generics anywhere in the
     * diff) the catalogue build is wasted work.
     *
     * <p>Returns {@code true} as soon as one diff class with a
     * non-empty {@link FileMetadata.HeaderEdges} is found — the
     * worst case still scans the project but the dominant
     * small-diff path bottoms out on the first match.
     */
    private static boolean anyChangedClassHasHeaderEdges(Set<String> changedFqns,
                                                         ProjectIndex index) {
        for (Path file : index.sourceFiles()) {
            FileMetadata md = index.fileMetadata(file);
            if (md == null) continue;
            String pkg = md.packageName();
            for (FileMetadata.TypeDecl decl : md.typeDeclarations()) {
                String fqn = pkg.isEmpty()
                        ? decl.qualifiedName()
                        : pkg + "." + decl.qualifiedName();
                if (!changedFqns.contains(fqn)) continue;
                if (!decl.headerEdges().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void augmentFromFqn(String sourceFqn,
                                ProjectFqnCatalogue catalogue,
                                Set<String> excluded,
                                List<PathMatcher> ignoreMatchers,
                                Set<String> augmented,
                                Set<String> nextFrontier,
                                List<HeaderEdge> edges) {
        ProjectFqnCatalogue.DeclContext context = catalogue.contextFor(sourceFqn);
        if (context == null) {
            // The changed FQN doesn't have a TypeDecl in the project
            // index — either the file failed to parse, the FQN was
            // derived from a file the index didn't see (out-of-scope,
            // deleted in the diff), or the path-to-FQN mapping
            // disagreed with the source's declared package. Either
            // way, no header edges to walk; the diff-time mapper has
            // already routed the file through the relevant bucket so
            // staying silent here is the right call. DEBUG-only — a
            // WARN would be noisy for the dominant deleted-file case.
            log.debug("[headerEdges] no TypeDecl for changed FQN {} — no header edges to walk",
                    LogSanitizer.sanitize(sourceFqn));
            return;
        }
        FileMetadata.HeaderEdges he = context.decl().headerEdges();
        if (he == null || he.isEmpty()) return;

        processCategory(sourceFqn, AffectedTestsConfig.HEADER_EDGE_EXTENDS,
                he.extendsSimpleNames(), context, excluded, ignoreMatchers,
                catalogue, augmented, nextFrontier, edges);
        processCategory(sourceFqn, AffectedTestsConfig.HEADER_EDGE_IMPLEMENTS,
                he.implementsSimpleNames(), context, excluded, ignoreMatchers,
                catalogue, augmented, nextFrontier, edges);
        processCategory(sourceFqn, AffectedTestsConfig.HEADER_EDGE_PERMITS,
                he.permittedSimpleNames(), context, excluded, ignoreMatchers,
                catalogue, augmented, nextFrontier, edges);
        processCategory(sourceFqn, AffectedTestsConfig.HEADER_EDGE_TYPE_BOUNDS,
                he.typeBoundSimpleNames(), context, excluded, ignoreMatchers,
                catalogue, augmented, nextFrontier, edges);
        processCategory(sourceFqn, AffectedTestsConfig.HEADER_EDGE_RECORD_COMPONENTS,
                he.recordComponentSimpleNames(), context, excluded, ignoreMatchers,
                catalogue, augmented, nextFrontier, edges);
        processCategory(sourceFqn, AffectedTestsConfig.HEADER_EDGE_ANNOTATIONS,
                he.annotationSimpleNames(), context, excluded, ignoreMatchers,
                catalogue, augmented, nextFrontier, edges);
    }

    private void processCategory(String sourceFqn,
                                 String category,
                                 List<String> simpleNames,
                                 ProjectFqnCatalogue.DeclContext context,
                                 Set<String> excluded,
                                 List<PathMatcher> ignoreMatchers,
                                 ProjectFqnCatalogue catalogue,
                                 Set<String> augmented,
                                 Set<String> nextFrontier,
                                 List<HeaderEdge> edges) {
        if (simpleNames.isEmpty()) return;
        if (excluded.contains(category)) {
            // Surface the opt-out in --explain so adopters can see
            // what their {@code headerEdgesExclude} cost them — a
            // category drop on a Spring DI codebase that quietly
            // suppressed the implements walk would otherwise be
            // invisible until selection regressions surface in CI.
            for (String simple : simpleNames) {
                edges.add(new HeaderEdge(
                        sourceFqn, simple, null, category,
                        EdgeStatus.IGNORED_BY_CATEGORY, null));
            }
            return;
        }
        for (String simple : simpleNames) {
            String resolved = catalogue.resolve(simple, context);
            if (resolved == null) {
                edges.add(new HeaderEdge(
                        sourceFqn, simple, null, category,
                        EdgeStatus.UNRESOLVED, null));
                continue;
            }
            String matchingGlob = matchedIgnoreGlob(resolved, ignoreMatchers,
                    config.headerEdgesIgnore());
            if (matchingGlob != null) {
                edges.add(new HeaderEdge(
                        sourceFqn, simple, resolved, category,
                        EdgeStatus.IGNORED_BY_GLOB, matchingGlob));
                continue;
            }
            if (augmented.add(resolved)) {
                nextFrontier.add(resolved);
            }
            edges.add(new HeaderEdge(
                    sourceFqn, simple, resolved, category,
                    EdgeStatus.ADDED, null));
        }
    }

    /**
     * Compiles each {@code headerEdgesIgnore} glob via {@link
     * FileSystems#getDefault()}'s glob syntax, with one defensive
     * normalisation: FQN globs are dotted (e.g. {@code
     * java.lang.**}), but Java's PathMatcher glob syntax separates
     * segments with {@code /}. We translate dots to slashes before
     * handing the pattern to the matcher, then translate the
     * candidate FQN the same way when checking. Pre-compiling the
     * matchers keeps the inner loop allocation-free for adopters
     * with hundreds of changed classes and dozens of ignore globs.
     */
    private static List<PathMatcher> compileIgnoreMatchers(List<String> globs) {
        List<PathMatcher> compiled = new ArrayList<>(globs.size());
        for (String glob : globs) {
            String translated = "glob:" + glob.replace('.', '/');
            try {
                compiled.add(FileSystems.getDefault().getPathMatcher(translated));
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                // Malformed glob entries are skipped with a single
                // WARN per offender. The fallback is "this glob
                // matches nothing" — strictly the lesser surprise
                // (adopter sees noise in --explain but selection
                // doesn't crash) compared with throwing a runtime
                // exception inside Gradle's discovery loop. The
                // entry is sanitised because adopter DSL flows
                // through here unchecked.
                log.warn("[headerEdges] skipping malformed ignore glob: {} ({})",
                        LogSanitizer.sanitize(glob),
                        LogSanitizer.sanitize(e.getMessage()));
                compiled.add(p -> false);
            }
        }
        return compiled;
    }

    private static String matchedIgnoreGlob(String fqn,
                                            List<PathMatcher> matchers,
                                            List<String> originalGlobs) {
        Path fqnAsPath = Path.of(fqn.replace('.', '/'));
        for (int i = 0; i < matchers.size(); i++) {
            if (matchers.get(i).matches(fqnAsPath)) {
                return originalGlobs.get(i);
            }
        }
        return null;
    }

    /**
     * Pre-built lookup over every {@link FileMetadata.TypeDecl} in
     * the project index. Owns three indices:
     *
     * <ul>
     *   <li>{@code byFqn} — FQN → {@link DeclContext}; lets the
     *       per-changed-class walk find the source declaration's
     *       header edges and imports in constant time.</li>
     *   <li>{@code bySimpleName} — simple name → set of FQNs;
     *       seeds the global-lookup tier of
     *       {@link #resolve(String, DeclContext)} when imports and
     *       same-package don't find a match.</li>
     *   <li>{@code directSubtypeCounts} — FQN → count of distinct
     *       project types that declare it as a supertype simple
     *       name. Feeds the sibling-cap check.</li>
     * </ul>
     *
     * <p>The catalogue is built once per
     * {@link HeaderEdgesStrategy#augment} call. Memoisation across
     * calls inside a single engine run is feasible but not done
     * here — the engine wires the strategy as a one-shot pass
     * before the four downstream strategies run, so the catalogue
     * is computed exactly once per run anyway.
     */
    static final class ProjectFqnCatalogue {

        /**
         * Per-type-decl context — the {@link FileMetadata.TypeDecl}
         * itself plus the file-level metadata
         * (imports + package) needed to resolve simple names
         * referenced by its {@link FileMetadata.HeaderEdges}.
         */
        record DeclContext(
                FileMetadata.TypeDecl decl,
                String packageName,
                List<FileMetadata.Import> imports) {}

        private final Map<String, DeclContext> byFqn;
        private final Map<String, Set<String>> bySimpleName;
        private final Map<String, Integer> directSubtypeCounts;

        private ProjectFqnCatalogue(Map<String, DeclContext> byFqn,
                                    Map<String, Set<String>> bySimpleName,
                                    Map<String, Integer> directSubtypeCounts) {
            this.byFqn = byFqn;
            this.bySimpleName = bySimpleName;
            this.directSubtypeCounts = directSubtypeCounts;
        }

        static ProjectFqnCatalogue build(ProjectIndex index) {
            Map<String, DeclContext> byFqn = new LinkedHashMap<>();
            Map<String, Set<String>> bySimpleName = new HashMap<>();
            // Subtype count is "for each TypeDecl T in project, for
            // each supertype simple name S of T, increment count[S]".
            // The count is on the simple name because that's the
            // shape the AST records; the lookup in
            // {@link #directSubtypeCount(String)} converts an FQN
            // back to its simple name and reads the count there.
            // Slight over-count when two distinct FQNs share a
            // simple name (e.g. {@code com.a.Foo} and
            // {@code com.b.Foo}) is acceptable — the cap is a
            // safety bound, not a precise metric.
            Map<String, Integer> simpleNameSubtypeCounts = new HashMap<>();
            for (Path file : index.sourceFiles()) {
                FileMetadata md = index.fileMetadata(file);
                if (md == null) continue;
                String pkg = md.packageName();
                List<FileMetadata.Import> imports = md.imports();
                for (FileMetadata.TypeDecl decl : md.typeDeclarations()) {
                    // Issue #132 — use qualifiedName (in-CU path) so
                    // nested types like {@code Outer.Inner} land at
                    // {@code pkg.Outer.Inner} rather than colliding
                    // with a sibling top-level {@code pkg.Inner}
                    // (correctness finding C4/ADV-HE-03). For
                    // top-level decls qualifiedName == simpleName so
                    // the FQN shape is byte-identical to the pre-#132
                    // catalogue.
                    String inCu = decl.qualifiedName();
                    String fqn = pkg.isEmpty() ? inCu : pkg + "." + inCu;
                    byFqn.put(fqn, new DeclContext(decl, pkg, imports));
                    // bySimpleName still uses the leaf name so an
                    // unqualified header-edge reference (the dominant
                    // shape) can still find every candidate via
                    // tier-4 resolution.
                    bySimpleName.computeIfAbsent(decl.simpleName(),
                            k -> new HashSet<>()).add(fqn);
                    for (String s : decl.supertypeSimpleNames()) {
                        simpleNameSubtypeCounts.merge(s, 1, Integer::sum);
                    }
                }
            }
            return new ProjectFqnCatalogue(byFqn, bySimpleName, simpleNameSubtypeCounts);
        }

        DeclContext contextFor(String fqn) {
            return byFqn.get(fqn);
        }

        int directSubtypeCount(String fqn) {
            String simple = SourceFileScanner.simpleClassName(fqn);
            return directSubtypeCounts.getOrDefault(simple, 0);
        }

        /**
         * Resolves a simple name referenced by a header edge of
         * {@code context}'s source class to a project FQN. Returns
         * {@code null} when none of the four tiers below produce a
         * single unambiguous match — the caller records the edge as
         * {@link EdgeStatus#UNRESOLVED} so {@code --explain} surfaces
         * the gap.
         *
         * <ol>
         *   <li>Non-wildcard imports — the dominant case. An
         *       {@code import com.example.PaymentGateway;} on the
         *       source file resolves any {@code PaymentGateway}
         *       header-edge reference to the imported FQN.</li>
         *   <li>Same-package lookup — when the source file's
         *       package contains a project type whose simple name
         *       matches.</li>
         *   <li>Wildcard imports against the project catalogue —
         *       an {@code import com.example.gateways.*;} resolves
         *       a {@code PaymentGateway} reference when
         *       {@code com.example.gateways.PaymentGateway} exists
         *       in the project. False positive on a wildcard import
         *       that brings in a type from outside the project is
         *       acceptable — the impl strategy would already select
         *       the consumer test for the resolved FQN, so
         *       over-selection is bounded by the catalogue itself.</li>
         *   <li>Global lookup — if the project has a single
         *       FQN whose simple name matches, that's the answer.
         *       Multiple matches return {@code null} so the strategy
         *       under-selects rather than over-selects on a name
         *       collision; adopters who hit this edit their imports
         *       to disambiguate.</li>
         * </ol>
         */
        String resolve(String simpleName, DeclContext context) {
            // Issue #132 — tier-0 handles already-dotted names. Two
            // shapes flow through here:
            // (a) Package-qualified: {@code @org.springframework.Service}
            //     or {@code extends com.acme.Base}. First segment is
            //     lowercase (Java identifier convention, JLS §6.1).
            //     We return it verbatim so the caller's ignore-glob
            //     pass matches the FQN exactly and so we don't
            //     conflate it with a shadowing project type sharing
            //     the leaf simple name (correctness C2/ADV-HE-01).
            // (b) Nested-type-scoped: {@code @Outer.Inner} or
            //     {@code extends Outer.Inner}. First segment is
            //     uppercase. We resolve the outer first then append
            //     the inner tail, so the catalogue entry written as
            //     {@code pkg.Outer.Inner} is reached (correctness C3).
            int firstDot = simpleName.indexOf('.');
            if (firstDot > 0) {
                char head = simpleName.charAt(0);
                if (Character.isLowerCase(head)) {
                    // Package-qualified — return verbatim. If the
                    // FQN is project-internal it's the answer; if
                    // external the strategy treats it as resolved
                    // for ignore-glob purposes but won't find a
                    // catalogue match (UNRESOLVED-but-named).
                    return simpleName;
                }
                // Nested-type-scoped — resolve the outer head and
                // attach the tail.
                String outer = simpleName.substring(0, firstDot);
                String tail = simpleName.substring(firstDot + 1);
                String outerFqn = resolve(outer, context);
                if (outerFqn != null) {
                    String candidate = outerFqn + "." + tail;
                    if (byFqn.containsKey(candidate)) {
                        return candidate;
                    }
                    // Outer resolved but the nested member isn't in
                    // the project — return the synthesised FQN so
                    // ignore-globs can still match.
                    return candidate;
                }
                // Fall through to the simple-name resolution tiers
                // using the leaf as a last-ditch attempt.
                simpleName = SourceFileScanner.simpleClassName(simpleName);
            }
            for (FileMetadata.Import imp : context.imports()) {
                if (imp.isAsterisk()) continue;
                String name = imp.name();
                if (name == null) continue;
                if (imp.isStatic()) {
                    String classFqn = UsageStrategy.stripLastSegment(name);
                    if (classFqn != null
                            && SourceFileScanner.simpleClassName(classFqn).equals(simpleName)) {
                        return classFqn;
                    }
                } else if (SourceFileScanner.simpleClassName(name).equals(simpleName)) {
                    return name;
                }
            }

            String samePkg = context.packageName().isEmpty()
                    ? simpleName
                    : context.packageName() + "." + simpleName;
            if (byFqn.containsKey(samePkg)) {
                return samePkg;
            }

            for (FileMetadata.Import imp : context.imports()) {
                if (!imp.isAsterisk() || imp.isStatic()) continue;
                String name = imp.name();
                if (name == null || name.isEmpty()) continue;
                String candidate = name + "." + simpleName;
                if (byFqn.containsKey(candidate)) {
                    return candidate;
                }
            }

            Set<String> candidates = bySimpleName.get(simpleName);
            if (candidates != null && candidates.size() == 1) {
                return candidates.iterator().next();
            }

            return null;
        }
    }
}
