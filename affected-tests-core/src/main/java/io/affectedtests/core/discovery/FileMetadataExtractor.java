package io.affectedtests.core.discovery;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import io.affectedtests.core.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Distils a parsed {@link CompilationUnit} into a strategy-agnostic
 * {@link FileMetadata} record.
 *
 * <p>Single AST walk per file: one pass over imports, one pass over
 * {@link ClassOrInterfaceType} nodes (shared between the type-ref sets
 * and per-decl supertype extraction via grouped findAll results), and
 * one pass over {@link TypeDeclaration}s for the per-decl shape.
 * Pre-stage-2 each strategy did its own walk; the extractor folds those
 * into a single pass and caches the result so subsequent strategy
 * invocations on the same file are zero-AST-cost.
 *
 * <p>The class is stateless and side-effect-free; the same
 * {@link CompilationUnit} reused across threads always returns an
 * equivalent record (and JavaParser AST nodes are not modified).
 */
final class FileMetadataExtractor {

    private static final Logger log = LoggerFactory.getLogger(FileMetadataExtractor.class);

    private FileMetadataExtractor() {}

    /**
     * Builds a {@link FileMetadata} from a parsed compilation unit.
     *
     * <p>The compilation unit is read but not retained — callers can
     * release it as soon as this method returns. The returned record
     * is safe to share across threads.
     */
    static FileMetadata extract(CompilationUnit cu) {
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
        String primaryTypeName = cu.getPrimaryTypeName().orElse(null);

        List<FileMetadata.Import> imports = new ArrayList<>(cu.getImports().size());
        for (ImportDeclaration imp : cu.getImports()) {
            imports.add(new FileMetadata.Import(
                    imp.getNameAsString(),
                    imp.isStatic(),
                    imp.isAsterisk()));
        }

        Set<String> typeRefSimpleNames = new LinkedHashSet<>();
        Set<String> typeRefDottedNames = new LinkedHashSet<>();
        for (ClassOrInterfaceType type : cu.findAll(ClassOrInterfaceType.class)) {
            typeRefSimpleNames.add(type.getNameAsString());
            String scoped = nameWithScopeOrNull(type);
            if (scoped != null && scoped.indexOf('.') >= 0) {
                typeRefDottedNames.add(scoped);
            }
        }

        // findAll(TypeDeclaration.class) is the same shape Implementation
        // already used (every top-level + nested type), with the same
        // raw-types caveat — TypeDeclaration is generic in its self-type
        // and JavaParser's findAll returns the raw form.
        @SuppressWarnings({"rawtypes", "unchecked"})
        List<TypeDeclaration<?>> declarations =
                (List<TypeDeclaration<?>>) (List) cu.findAll(TypeDeclaration.class);
        List<FileMetadata.TypeDecl> typeDecls = new ArrayList<>(declarations.size());
        for (TypeDeclaration<?> decl : declarations) {
            List<ClassOrInterfaceType> supertypes = supertypesOf(decl);
            List<String> supertypeNames = new ArrayList<>(supertypes.size());
            for (ClassOrInterfaceType s : supertypes) {
                supertypeNames.add(s.getNameAsString());
            }
            FileMetadata.HeaderEdges headerEdges = headerEdgesOf(decl);
            String qualifiedName = qualifiedNameInCu(decl);
            typeDecls.add(new FileMetadata.TypeDecl(
                    decl.getNameAsString(), qualifiedName, supertypeNames, headerEdges));
        }

        return new FileMetadata(
                packageName,
                primaryTypeName,
                imports,
                typeRefSimpleNames,
                typeRefDottedNames,
                typeDecls);
    }

    /**
     * Mirrors {@link com.github.javaparser.ast.type.ClassOrInterfaceType#getNameWithScope()}
     * with a try/catch to swallow the rare partial-resolution
     * RuntimeExceptions that JavaParser surfaces on malformed source.
     * Returning {@code null} skips that single node while letting the
     * rest of the file contribute — same contract as the previous
     * {@code UsageStrategy.nameWithScopeOrNull} helper this method
     * replaces.
     */
    private static String nameWithScopeOrNull(ClassOrInterfaceType type) {
        try {
            return type.getNameWithScope();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Issue&nbsp;#132 follow-up (correctness C3) — returns a
     * scope-aware name for a header-edge supertype reference. Used by
     * {@link #headerEdgesOf} so {@code extends java.io.Serializable}
     * surfaces as {@code "java.io.Serializable"} (not {@code
     * "Serializable"}) and {@code extends Outer.Inner} surfaces as
     * {@code "Outer.Inner"} (not {@code "Inner"}).
     *
     * <p>The fallback to {@code getNameAsString()} preserves the
     * conservative leaf-name shape on the rare malformed-source path
     * — under-resolution on a single supertype is preferable to
     * dropping the entire decl's header-edge entry.
     */
    private static String scopedNameOf(ClassOrInterfaceType type) {
        String scoped = nameWithScopeOrNull(type);
        return scoped != null ? scoped : type.getNameAsString();
    }

    /**
     * Returns the combined {@code extends} + {@code implements} list
     * for the supported {@link TypeDeclaration} shapes. Records,
     * enums, and annotations cannot {@code extends} another named
     * type and contribute only via {@code implements}; annotation
     * declarations have no widenable supertype at all.
     *
     * <p>Unknown {@code TypeDeclaration} subtypes (e.g. a future
     * JavaParser release) return an empty list — Implementation's
     * pre-existing WARN already covers the surfacing; here we just
     * preserve the conservative empty-supertype fallback so the
     * cached record contract stays stable.
     */
    /**
     * Issue&nbsp;#132 — extracts the six header-edge categories from a
     * {@link TypeDeclaration}: extends, implements, permits, generic
     * type-parameter bounds, record components, and class-level
     * annotations. Anything that appears in the declaration line
     * <em>before</em> the opening {@code &#123;} is part of the type's
     * identity, and the {@code headerEdges} strategy treats every
     * such referenced type as a header edge that connects the
     * declaring class to its consumers.
     *
     * <p>Categorisation follows JavaParser's per-shape getters:
     * <ul>
     *   <li>{@code ClassOrInterfaceDeclaration.getExtendedTypes()} →
     *       category 1 (extends). A class has 0–1 entries; an
     *       interface can have many.</li>
     *   <li>{@code ClassOrInterfaceDeclaration.getImplementedTypes()}
     *       and the record / enum equivalents → category 2
     *       (implements / record's implements clause / enum's
     *       implements clause). The strategy's killer Spring DI
     *       case lives here.</li>
     *   <li>{@code ClassOrInterfaceDeclaration.getPermittedTypes()} →
     *       category 3 (permits — sealed hierarchies only).</li>
     *   <li>{@code NodeWithTypeParameters.getTypeParameters()} per
     *       {@link TypeParameter#getTypeBound()} → category 4
     *       (generic bounds — {@code <T extends Foo & Bar>}).</li>
     *   <li>{@code RecordDeclaration.getParameters()} per parameter
     *       type → category 5 (record components — the surface
     *       record types expose to their consumers).</li>
     *   <li>{@code NodeWithAnnotations.getAnnotations()} on the
     *       declaration itself → category 6 (class-level
     *       annotations).</li>
     * </ul>
     *
     * <p>Returns {@link FileMetadata.HeaderEdges#EMPTY} when every
     * category is empty — the dominant case on plain
     * {@code class Foo {}} declarations.
     */
    private static FileMetadata.HeaderEdges headerEdgesOf(TypeDeclaration<?> decl) {
        List<String> extendsNames = new ArrayList<>();
        List<String> implementsNames = new ArrayList<>();
        List<String> permittedNames = new ArrayList<>();
        List<String> typeBoundNames = new ArrayList<>();
        List<String> recordComponentNames = new ArrayList<>();
        List<String> annotationNames = new ArrayList<>();

        // Issue #132 follow-up (correctness C3): header-edge supertype
        // categories preserve the scoped form ({@code Outer.Inner},
        // {@code java.io.Serializable}) rather than the leaf
        // ({@code Inner}, {@code Serializable}) so the resolver's
        // tier-0 can route nested supertypes to the right catalogue
        // entry and FQ supertypes through the ignore-glob layer.
        // The legacy {@code supertypeSimpleNames} field
        // (extracted by {@code supertypesOf} below) keeps the bare
        // simple-name shape so {@link ImplementationStrategy}'s
        // pre-#132 matching contract stays byte-identical.
        if (decl instanceof ClassOrInterfaceDeclaration c) {
            for (ClassOrInterfaceType t : c.getExtendedTypes()) {
                extendsNames.add(scopedNameOf(t));
            }
            for (ClassOrInterfaceType t : c.getImplementedTypes()) {
                implementsNames.add(scopedNameOf(t));
            }
            for (ClassOrInterfaceType t : c.getPermittedTypes()) {
                permittedNames.add(scopedNameOf(t));
            }
        } else if (decl instanceof RecordDeclaration r) {
            for (ClassOrInterfaceType t : r.getImplementedTypes()) {
                implementsNames.add(scopedNameOf(t));
            }
            for (Parameter p : r.getParameters()) {
                addTypeRefSimpleNames(p.getType(), recordComponentNames);
            }
        } else if (decl instanceof EnumDeclaration e) {
            for (ClassOrInterfaceType t : e.getImplementedTypes()) {
                implementsNames.add(scopedNameOf(t));
            }
        }

        if (decl instanceof NodeWithTypeParameters<?> nodeWithParams) {
            for (TypeParameter param : nodeWithParams.getTypeParameters()) {
                for (ClassOrInterfaceType bound : param.getTypeBound()) {
                    typeBoundNames.add(scopedNameOf(bound));
                }
            }
        }

        // {@code @interface} declarations carry their own annotations
        // (e.g. {@code @Inherited @Retention(RUNTIME) @interface Foo})
        // — we still surface those so a custom meta-annotation tagged
        // onto another annotation participates as a header edge.
        //
        // {@code AnnotationExpr.getNameAsString()} returns the
        // declared form. Three shapes matter:
        // 1. Plain simple ({@code "Service"}): keep as-is, the
        //    resolver walks imports / same-package / global.
        // 2. Package-qualified ({@code "org.springframework.stereotype.Service"}):
        //    keep verbatim, the resolver's tier-0 routes it directly
        //    to the ignore-glob layer. Pre-strip would conflate
        //    {@code @org.springframework.Service} with a shadowing
        //    project type {@code com.example.Service} (correctness
        //    finding C2/ADV-HE-01).
        // 3. Nested-type-qualified ({@code "Outer.Inner"} for a
        //    nested annotation reference): keep scoped, the resolver
        //    walks the outer first then attaches the inner segment.
        // The "lowercase first segment is a package qualifier"
        // convention is the universal Java identifier rule
        // (JLS §6.1) — we lean on it to split (2) from (3) without
        // needing a symbol table.
        NodeWithAnnotations<?> annotated = (NodeWithAnnotations<?>) decl;
        for (AnnotationExpr ann : annotated.getAnnotations()) {
            annotationNames.add(ann.getNameAsString());
        }

        if (extendsNames.isEmpty() && implementsNames.isEmpty()
                && permittedNames.isEmpty() && typeBoundNames.isEmpty()
                && recordComponentNames.isEmpty() && annotationNames.isEmpty()) {
            return FileMetadata.HeaderEdges.EMPTY;
        }
        return new FileMetadata.HeaderEdges(
                extendsNames, implementsNames, permittedNames,
                typeBoundNames, recordComponentNames, annotationNames);
    }

    /**
     * Issue&nbsp;#132 — returns the in-compilation-unit qualified name
     * for a {@link TypeDeclaration}. For top-level types this equals
     * {@link TypeDeclaration#getNameAsString()}; for nested types it's
     * the dot-joined path from the outermost enclosing TypeDeclaration
     * to the decl itself (e.g. {@code "Outer.Inner.Leaf"}).
     *
     * <p>Used by {@link ProjectIndexCache} to disambiguate two nested
     * decls that share a simple name within one file (correctness
     * finding C1/ADV-HE-06: {@code class A { class Z {} } class B {
     * class Z {} }} previously collapsed both {@code Z}s on cache
     * decode), and by {@link HeaderEdgesStrategy.ProjectFqnCatalogue}
     * to build correct project FQNs for nested types (correctness
     * finding C4/ADV-HE-03: previously catalogued as {@code pkg.Z},
     * silently overwriting a top-level {@code pkg.Z}).
     */
    private static String qualifiedNameInCu(TypeDeclaration<?> decl) {
        StringBuilder sb = new StringBuilder(decl.getNameAsString());
        com.github.javaparser.ast.Node parent = decl.getParentNode().orElse(null);
        while (parent instanceof TypeDeclaration<?> outer) {
            sb.insert(0, outer.getNameAsString() + ".");
            parent = outer.getParentNode().orElse(null);
        }
        return sb.toString();
    }

    /**
     * Adds scoped names for every {@link ClassOrInterfaceType}
     * reachable from {@code type} into {@code sink}. Handles the
     * outer type, generic argument types, and arrays of class types —
     * matching how a record-component declaration like
     * {@code List<Customer> customers} surfaces both {@code List} and
     * {@code Customer} as header-edge targets. Uses
     * {@link #scopedNameOf} so a record component declared as
     * {@code java.util.List<com.example.Order>} surfaces with both
     * the FQ outer and the FQ argument preserved, letting the
     * resolver's tier-0 short-circuit through ignore-globs (JDK,
     * project package) without going through the simple-name tiers.
     */
    private static void addTypeRefSimpleNames(Type type, List<String> sink) {
        for (ClassOrInterfaceType t : type.findAll(ClassOrInterfaceType.class)) {
            sink.add(scopedNameOf(t));
        }
    }

    private static List<ClassOrInterfaceType> supertypesOf(TypeDeclaration<?> decl) {
        List<ClassOrInterfaceType> result = new ArrayList<>();
        if (decl instanceof ClassOrInterfaceDeclaration c) {
            result.addAll(c.getExtendedTypes());
            result.addAll(c.getImplementedTypes());
        } else if (decl instanceof RecordDeclaration r) {
            result.addAll(r.getImplementedTypes());
        } else if (decl instanceof EnumDeclaration e) {
            result.addAll(e.getImplementedTypes());
        } else if (decl instanceof AnnotationDeclaration) {
            // Annotations cannot widen their implicit supertype
            // (java.lang.annotation.Annotation). Empty list is the
            // correct answer — not a drop.
            return result;
        } else {
            // Defensive: when a future JavaParser release introduces a
            // new TypeDeclaration subtype we want the new construct to
            // be *loud* rather than silently dropping every consumer
            // test that depended on it. WARN surfaces at the default
            // log level so operators can open a ticket; the extractor
            // keeps running and treats the type as having no known
            // supertypes, which is the correct conservative fallback
            // (cannot become a false impl match; at worst we
            // underselect for a single declaration).
            // {@code decl.getNameAsString()} originates from an
            // attacker-controllable source file — sanitised to keep
            // the log-forgery contract honest.
            log.warn("Affected Tests: [extract] unknown TypeDeclaration subtype {} for {} — "
                            + "supertype edges cannot be derived, skipping",
                    decl.getClass().getSimpleName(),
                    LogSanitizer.sanitize(decl.getNameAsString()));
        }
        return result;
    }
}
