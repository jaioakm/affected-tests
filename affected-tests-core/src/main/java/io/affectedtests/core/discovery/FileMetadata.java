package io.affectedtests.core.discovery;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The strategy-relevant projection of a parsed source file's
 * {@link com.github.javaparser.ast.CompilationUnit}, distilled into a
 * cheap-to-serialise record so the persistent
 * {@link ProjectIndexCache} (issue&nbsp;#41 stage&nbsp;2) can hand it
 * straight back to {@link UsageStrategy}, {@link ImplementationStrategy},
 * and {@link TransitiveStrategy} without re-parsing on a warm run.
 *
 * <p>Captures the union of fields the three AST-driven strategies
 * actually consume:
 * <ul>
 *   <li><strong>{@link #packageName()}</strong> — declared package, or
 *       the empty string for the default package.</li>
 *   <li><strong>{@link #primaryTypeName()}</strong> — Usage's last-resort
 *       FQN fallback for tests whose path can't be resolved against any
 *       configured test root. {@code null} when the file declares no
 *       primary type.</li>
 *   <li><strong>{@link #imports()}</strong> — raw, in-source-order
 *       {@link Import} list. Strategies normalise on read because Usage
 *       and Transitive disagree on import semantics: Usage folds static
 *       imports back to their owning class FQN, Transitive ignores
 *       static imports entirely. Caching the raw form keeps the
 *       strategy-specific normalisation contained and avoids duplicate
 *       per-strategy fields in the snapshot.</li>
 *   <li><strong>{@link #typeRefSimpleNames()}</strong> — every
 *       {@link com.github.javaparser.ast.type.ClassOrInterfaceType#getNameAsString()
 *       ClassOrInterfaceType simple name} that appears anywhere in the
 *       AST. Used by Usage's wildcard-package and same-package tiers
 *       and by Transitive's frontier filter.</li>
 *   <li><strong>{@link #typeRefDottedNames()}</strong> — every
 *       {@code ClassOrInterfaceType.getNameWithScope()} that yielded a
 *       dotted form. Used by Usage's tier&nbsp;3 fully-qualified inline
 *       reference match.</li>
 *   <li><strong>{@link #typeDeclarations()}</strong> — every
 *       {@link com.github.javaparser.ast.body.TypeDeclaration} in the
 *       file (not just the primary), each with its declared supertypes
 *       as simple names. Used by Implementation's fixpoint scan, which
 *       needs to walk records and enums (not just classes/interfaces)
 *       and respects inner-type names.</li>
 * </ul>
 *
 * <p>The record is intentionally <em>not</em> per-strategy: keeping a
 * single union shape lets one extractor pass do the work of all three
 * AST walks ({@code findAll(ClassOrInterfaceType.class)}, the import
 * loop, and the {@code findAll(TypeDeclaration.class)} loop), so a
 * cache miss costs one parse + one walk regardless of how many
 * strategies later read from it.
 */
public record FileMetadata(
        String packageName,
        String primaryTypeName,
        List<Import> imports,
        Set<String> typeRefSimpleNames,
        Set<String> typeRefDottedNames,
        List<TypeDecl> typeDeclarations) {

    /**
     * Defensive copy + immutable wrappers in the canonical constructor
     * so the cached record stays safe to share across the discovery
     * pool's worker threads. Strategies treat {@code FileMetadata} as
     * read-only; this stops a future caller from mutating a shared
     * cache entry by accident.
     */
    public FileMetadata {
        packageName = packageName == null ? "" : packageName;
        imports = List.copyOf(imports);
        typeRefSimpleNames = unmodifiableLinkedSet(typeRefSimpleNames);
        typeRefDottedNames = unmodifiableLinkedSet(typeRefDottedNames);
        typeDeclarations = List.copyOf(typeDeclarations);
    }

    private static Set<String> unmodifiableLinkedSet(Set<String> in) {
        if (in == null || in.isEmpty()) return Set.of();
        return Collections.unmodifiableSet(new LinkedHashSet<>(in));
    }

    /**
     * Raw representation of a single Java {@code import} statement.
     *
     * <p>{@link #name()} is the {@code import}'s
     * {@code getNameAsString()} (i.e. the dotted name without the
     * leading {@code import} keyword and trailing semicolon), preserved
     * exactly as JavaParser reports it so downstream strategies can
     * normalise without losing information.
     *
     * <p>{@link #isStatic()} mirrors {@code ImportDeclaration.isStatic()}
     * — a {@code true} value means the import targets a member of the
     * named class, not the class itself. Usage strips the trailing
     * member segment to recover the owning class FQN; Transitive drops
     * static imports entirely (they never produce reverse-dependency
     * edges).
     *
     * <p>{@link #isAsterisk()} mirrors {@code ImportDeclaration.isAsterisk()}.
     * For a non-static asterisk import the name is a package; for a
     * static asterisk import the name is the owning class. Both
     * strategies handle the wildcard branch separately from the plain
     * branch.
     */
    public record Import(String name, boolean isStatic, boolean isAsterisk) {}

    /**
     * Cached projection of a single {@link
     * com.github.javaparser.ast.body.TypeDeclaration}. Carries the
     * supertype simple names (combined extends + implements, kept for
     * {@link ImplementationStrategy}'s pre-headerEdges contract) plus
     * the per-category {@link HeaderEdges} breakdown that issue&nbsp;#132
     * ({@code headerEdges} strategy) consumes.
     *
     * <p>{@link #simpleName()} is {@code TypeDeclaration.getNameAsString()}
     * — the type's own simple name (not the FQN; the file's
     * {@link FileMetadata#packageName()} is its prefix).
     *
     * <p>{@link #supertypeSimpleNames()} is the combined list of
     * {@code extends} + {@code implements} type names, in declaration
     * order, each via {@code ClassOrInterfaceType.getNameAsString()}.
     * Empty for annotation declarations and any future
     * {@link com.github.javaparser.ast.body.TypeDeclaration} subtype
     * the extractor doesn't yet recognise. Preserved verbatim so
     * {@link ImplementationStrategy}'s pre-headerEdges fixpoint walk
     * keeps its single-loop shape — splitting it into two fields
     * would force every existing consumer to rewrite the iteration.
     *
     * <p>{@link #headerEdges()} is the issue&nbsp;#132 extension:
     * the six header-edge categories (extends, implements, permits,
     * type-parameter bounds, record-component types, annotations)
     * indexed separately so {@code HeaderEdgesStrategy} can categorise
     * each fired edge for {@code --explain} and apply per-category
     * opt-out. Always non-null; defaults to {@link HeaderEdges#EMPTY}
     * for declarations whose source did not carry any header-edge
     * shape (e.g. a top-level {@code class Foo {}} with no
     * extends / implements / annotations / generics).
     */
    public record TypeDecl(
            String simpleName,
            String qualifiedName,
            List<String> supertypeSimpleNames,
            HeaderEdges headerEdges) {

        public TypeDecl {
            supertypeSimpleNames = List.copyOf(supertypeSimpleNames);
            if (headerEdges == null) {
                headerEdges = HeaderEdges.EMPTY;
            }
            if (qualifiedName == null) {
                qualifiedName = simpleName;
            }
        }

        /**
         * Backwards-compatible two-arg constructor — preserves the
         * pre-issue-#132 record shape every existing test fixture and
         * downstream caller was written against. Defaults
         * {@link #qualifiedName()} to {@code simpleName} (correct for
         * top-level types, which is the dominant case) and
         * {@link HeaderEdges} to {@link HeaderEdges#EMPTY}.
         */
        public TypeDecl(String simpleName, List<String> supertypeSimpleNames) {
            this(simpleName, simpleName, supertypeSimpleNames, HeaderEdges.EMPTY);
        }

        /**
         * Three-arg constructor preserving the issue-#132 shape before
         * the {@code qualifiedName} field was added — defaults
         * {@link #qualifiedName()} to {@code simpleName}. Used by
         * existing test fixtures that wire {@link HeaderEdges}
         * explicitly but don't need the qualified-name disambiguation
         * (i.e. exclusively top-level decls).
         */
        public TypeDecl(String simpleName, List<String> supertypeSimpleNames,
                        HeaderEdges headerEdges) {
            this(simpleName, simpleName, supertypeSimpleNames, headerEdges);
        }
    }

    /**
     * Per-{@link TypeDecl} breakdown of the six header-edge categories
     * issue&nbsp;#132 ({@code headerEdges} discovery strategy) walks
     * to close the single-impl Spring-DI gap. Anything that appears
     * in a class declaration <em>before</em> the opening {@code &#123;}
     * is part of the class's identity — and tests for the consumer
     * of the header-declared type should run when the impl changes,
     * even though no test source-imports the impl directly.
     *
     * <p>Categories are kept as separate lists (not a flat set) so
     * {@code HeaderEdgesStrategy} can:
     * <ul>
     *   <li>Skip a category via the
     *       {@code headerEdgesExclude = ["annotations"]} DSL knob
     *       without losing edges from the other categories;</li>
     *   <li>Render each fired edge in {@code --explain} with the
     *       category label that triggered it
     *       ({@code [implements]}, {@code [record-components]}, etc.).</li>
     * </ul>
     *
     * <p>{@link #EMPTY} is the all-empty sentinel. Strategies that
     * pre-date issue&nbsp;#132 can ignore this field entirely; the
     * issue's strategy is the only consumer.
     *
     * @param extendsSimpleNames    category 1 — the {@code extends}
     *                              clause (single entry on a class,
     *                              multi-entry on an interface).
     * @param implementsSimpleNames category 2 — the {@code implements}
     *                              clause (Spring DI's killer case
     *                              shape).
     * @param permittedSimpleNames  category 3 — {@code permits} on a
     *                              sealed class/interface. Empty for
     *                              non-sealed declarations. Note that
     *                              {@code permits} lists subtypes —
     *                              header edges expand the sealed
     *                              contract's universe, so a change
     *                              to a permitted subtype escalates
     *                              to the sealed type's tests too.
     * @param typeBoundSimpleNames  category 4 — generic type-parameter
     *                              bounds ({@code <T extends Foo>}
     *                              surfaces {@code Foo}). Multi-bound
     *                              ({@code <T extends Foo & Bar>})
     *                              surfaces both.
     * @param recordComponentSimpleNames category 5 — types of record
     *                              components ({@code record
     *                              Order(Customer customer, Product
     *                              product)} surfaces
     *                              {@code Customer}, {@code Product}).
     *                              For Kotlin {@code data class} the
     *                              extractor mirrors this with the
     *                              primary-constructor parameter
     *                              types.
     * @param annotationSimpleNames category 6 — annotations on the
     *                              class declaration. Filtered by
     *                              the default {@code
     *                              headerEdgesIgnore} globs at the
     *                              strategy layer (Spring, JPA, JUnit,
     *                              Lombok, JDK, etc. are excluded by
     *                              default).
     */
    public record HeaderEdges(
            List<String> extendsSimpleNames,
            List<String> implementsSimpleNames,
            List<String> permittedSimpleNames,
            List<String> typeBoundSimpleNames,
            List<String> recordComponentSimpleNames,
            List<String> annotationSimpleNames) {

        public static final HeaderEdges EMPTY = new HeaderEdges(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        public HeaderEdges {
            extendsSimpleNames = List.copyOf(extendsSimpleNames);
            implementsSimpleNames = List.copyOf(implementsSimpleNames);
            permittedSimpleNames = List.copyOf(permittedSimpleNames);
            typeBoundSimpleNames = List.copyOf(typeBoundSimpleNames);
            recordComponentSimpleNames = List.copyOf(recordComponentSimpleNames);
            annotationSimpleNames = List.copyOf(annotationSimpleNames);
        }

        /**
         * {@code true} when every category list is empty. Lets
         * {@code HeaderEdgesStrategy} short-circuit the per-decl walk
         * without checking six fields, and keeps the
         * {@link ProjectIndexCache} encoder honest — an all-empty
         * record encodes to the empty string so the cache row stays
         * compact for the dominant "no header edges" case.
         */
        public boolean isEmpty() {
            return extendsSimpleNames.isEmpty()
                    && implementsSimpleNames.isEmpty()
                    && permittedSimpleNames.isEmpty()
                    && typeBoundSimpleNames.isEmpty()
                    && recordComponentSimpleNames.isEmpty()
                    && annotationSimpleNames.isEmpty();
        }
    }
}
