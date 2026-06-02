package io.affectedtests.core.discovery;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-level contract for {@link FileMetadataExtractor}: the
 * {@link FileMetadata} record produced from a parsed source file must
 * carry exactly the fields the AST-driven strategies (Usage,
 * Implementation, Transitive) used to extract themselves before
 * stage&nbsp;2 of issue&nbsp;#41 routed them through the cache.
 *
 * <p>Each test pins one slice of the contract — a normalisation
 * choice (raw imports), a shape boundary (records / enums /
 * annotations), or an edge case (default package, malformed scoped
 * names) — so a future refactor that drops a field is loud at the
 * extractor level rather than appearing as a "Usage no longer
 * matches X" regression three layers up.
 */
class FileMetadataExtractorTest {

    @TempDir
    Path tmp;

    private final AtomicInteger uniqueId = new AtomicInteger();

    /**
     * Writes the source to a temp file named after the primary type
     * before parsing so {@link CompilationUnit#getPrimaryTypeName()}
     * can derive the name (its contract requires storage AND the
     * filename to match a top-level type; parsing from a bare string
     * returns {@code null}, parsing from a mismatched-name file also
     * returns {@code null}). The production code path always parses
     * from a {@link Path} that comes from a real source-tree walk, so
     * the storage / filename pair is always consistent.
     *
     * <p>Pass {@code primaryTypeName} as {@code null} for sources
     * with no top-level type (e.g. {@code package-info.java} or an
     * empty file) — the helper then writes to a generic
     * {@code ProbeN.java} and the extractor returns
     * {@link FileMetadata#primaryTypeName()} as {@code null} the way
     * production does.
     */
    private FileMetadata extract(String source) {
        return extract(source, /* primaryTypeName */ null);
    }

    private FileMetadata extract(String source, String primaryTypeName) {
        try {
            String fileName = (primaryTypeName != null ? primaryTypeName : "Probe" + uniqueId.incrementAndGet())
                    + ".java";
            Path file = tmp.resolve(fileName);
            Files.writeString(file, source);
            JavaParser parser = JavaLanguageParser.newParser();
            ParseResult<CompilationUnit> result = parser.parse(file);
            assertTrue(result.isSuccessful(), () -> "Parse failed: " + result.getProblems());
            CompilationUnit cu = result.getResult().orElseThrow();
            return FileMetadataExtractor.extract(cu);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void capturesPackageAndPrimaryTypeName() {
        FileMetadata md = extract("""
                package com.example.svc;
                public class FooService {}
                """, "FooService");

        assertEquals("com.example.svc", md.packageName());
        assertEquals("FooService", md.primaryTypeName());
    }

    @Test
    void defaultPackageReportsEmptyString() {
        FileMetadata md = extract("public class Foo {}", "Foo");
        // Empty string is the contract — distinguishes "no package
        // declared" from "explicit empty package", which is not a
        // legal Java construct anyway. UsageStrategy / Transitive
        // both compare against {@code SourceFileScanner.packageOf}
        // which returns "" for default-package FQNs, so the choice
        // has to match.
        assertEquals("", md.packageName());
        assertEquals("Foo", md.primaryTypeName());
    }

    @Test
    void primaryTypeNameDerivesFromFileNameNotTypeDeclarations() {
        // JavaParser's {@code getPrimaryTypeName()} is filename-driven:
        // it returns the source file's basename minus .java when
        // storage is set, regardless of whether a top-level type
        // matching that name actually exists in the source. Usage's
        // FQN fallback (only reached when the file path can't be
        // relativised against any test root) consumes this value
        // directly, so the extractor must surface whatever
        // JavaParser said — not invent a "primary type derived from
        // first declaration" contract that doesn't match production.
        FileMetadata md = extract("""
                package com.example;
                public class Renamed {}
                """, "OriginalName");
        assertEquals("OriginalName", md.primaryTypeName(),
                "Filename drives primaryTypeName even when no top-level type "
                        + "matches — that's a JavaParser invariant the extractor inherits");
    }

    @Test
    void compilationUnitWithNoStorageReportsNullPrimaryType() {
        // The {@code .orElse(null)} in {@link FileMetadataExtractor}
        // is the defensive branch for the no-storage case. It should
        // never fire in production because every parse goes through
        // {@code parser.parse(Path)} which always sets storage, but
        // the contract is part of {@link FileMetadata}'s nullable
        // shape and a future caller passing in a synthetic CU without
        // storage must see {@code null} rather than a stale value.
        com.github.javaparser.ast.CompilationUnit cu =
                new com.github.javaparser.ast.CompilationUnit();
        cu.setPackageDeclaration("com.example");
        FileMetadata md = FileMetadataExtractor.extract(cu);
        assertNull(md.primaryTypeName());
    }

    @Test
    void importsArePreservedRawWithKindFlags() {
        // Strategies disagree on import normalisation (Usage strips
        // static-member trailing segments, Transitive ignores static
        // imports entirely), so the extractor must NOT pre-normalise
        // — it preserves the raw shape and lets each strategy normalise
        // on read. This test pins that contract.
        FileMetadata md = extract("""
                package com.example;
                import com.foo.A;
                import com.bar.*;
                import static com.baz.C.MAX;
                import static com.qux.D.*;
                public class X {}
                """);

        List<FileMetadata.Import> imports = md.imports();
        assertEquals(4, imports.size());

        FileMetadata.Import normal = imports.get(0);
        assertEquals("com.foo.A", normal.name());
        assertFalse(normal.isStatic());
        assertFalse(normal.isAsterisk());

        FileMetadata.Import wildcard = imports.get(1);
        assertEquals("com.bar", wildcard.name());
        assertFalse(wildcard.isStatic());
        assertTrue(wildcard.isAsterisk());

        FileMetadata.Import staticMember = imports.get(2);
        assertEquals("com.baz.C.MAX", staticMember.name());
        assertTrue(staticMember.isStatic());
        assertFalse(staticMember.isAsterisk());

        FileMetadata.Import staticWildcard = imports.get(3);
        assertEquals("com.qux.D", staticWildcard.name());
        assertTrue(staticWildcard.isStatic());
        assertTrue(staticWildcard.isAsterisk());
    }

    @Test
    void typeRefSimpleNamesCoverAllAstReferences() {
        // The simple-name set drives Usage's tier 1b/2 and Transitive's
        // frontier filter. It must include every type-shaped reference
        // anywhere in the file: extends/implements clauses, fields,
        // method signatures, generics, casts, instanceof, method
        // bodies, and nested-type references. {@code @Nullable} hits
        // the annotation-name path; the parser surfaces those
        // separately so they don't leak in here unless the annotation
        // is also used as a type — keeping the extractor focused on
        // {@code ClassOrInterfaceType} nodes matches the pre-stage-2
        // walk shape.
        FileMetadata md = extract("""
                package com.example;
                import java.util.List;
                public class X extends Base implements Iface {
                    private List<Foo> items;
                    public Bar method(Baz b) {
                        Object o = (Qux) b;
                        if (o instanceof Quux q) {}
                        return new Bar();
                    }
                    static class Inner extends Outer.Nested {}
                }
                """);

        Set<String> simples = md.typeRefSimpleNames();
        // Spot-check the major reference shapes — exhaustive
        // enumeration would lock us in to JavaParser's exact
        // node-walk order.
        assertTrue(simples.contains("Base"),       "extends clause");
        assertTrue(simples.contains("Iface"),      "implements clause");
        assertTrue(simples.contains("List"),       "generic outer");
        assertTrue(simples.contains("Foo"),        "generic inner");
        assertTrue(simples.contains("Bar"),        "return type + constructor");
        assertTrue(simples.contains("Baz"),        "parameter type");
        assertTrue(simples.contains("Qux"),        "cast");
        assertTrue(simples.contains("Quux"),       "instanceof pattern");
        assertTrue(simples.contains("Outer"),      "scoped extends prefix");
        assertTrue(simples.contains("Nested"),     "scoped extends leaf");
    }

    @Test
    void typeRefDottedNamesIncludeOnlyScopedReferences() {
        FileMetadata md = extract("""
                package com.example;
                public class X {
                    com.other.Foo a;
                    com.other.Foo.Inner b;
                    Bar c;
                }
                """);

        Set<String> dotted = md.typeRefDottedNames();
        assertTrue(dotted.contains("com.other.Foo"),
                "fully-qualified inline reference must surface in the dotted set "
                        + "so Usage's tier 3 can match it");
        assertTrue(dotted.contains("com.other.Foo.Inner"),
                "scoped inner-class reference must surface in the dotted set");
        // {@code Bar} is a bare simple name; it must not appear in the
        // dotted set or tier 3 would double-count it against tier 1/2.
        for (String d : dotted) {
            assertTrue(d.indexOf('.') >= 0,
                    "Dotted set must not contain bare simple name " + d);
        }
        assertFalse(dotted.contains("Bar"));
    }

    @Test
    void typeDeclarationsListEveryTopLevelAndNestedType() {
        // Implementation walks every TypeDeclaration in a file, not
        // just the primary, so a mix of top-level + nested + record
        // + enum + interface must all surface.
        FileMetadata md = extract("""
                package com.example;
                public class Outer extends Base implements Iface {
                    static class Inner implements Other {}
                }
                interface SiblingIface extends ParentIface {}
                record SiblingRecord(int x) implements RecordIface {}
                enum SiblingEnum implements EnumIface { A }
                """);

        List<FileMetadata.TypeDecl> decls = md.typeDeclarations();
        // Ordering follows JavaParser's findAll traversal — outer
        // first, then nested, then later top-levels. We assert on
        // membership rather than position so a parser bump that
        // tweaks traversal order doesn't break the test.
        assertEquals(5, decls.size());

        FileMetadata.TypeDecl outer = decls.stream()
                .filter(d -> d.simpleName().equals("Outer"))
                .findFirst().orElseThrow();
        assertEquals(List.of("Base", "Iface"), outer.supertypeSimpleNames());

        FileMetadata.TypeDecl inner = decls.stream()
                .filter(d -> d.simpleName().equals("Inner"))
                .findFirst().orElseThrow();
        assertEquals(List.of("Other"), inner.supertypeSimpleNames());

        FileMetadata.TypeDecl iface = decls.stream()
                .filter(d -> d.simpleName().equals("SiblingIface"))
                .findFirst().orElseThrow();
        assertEquals(List.of("ParentIface"), iface.supertypeSimpleNames());

        FileMetadata.TypeDecl rec = decls.stream()
                .filter(d -> d.simpleName().equals("SiblingRecord"))
                .findFirst().orElseThrow();
        assertEquals(List.of("RecordIface"), rec.supertypeSimpleNames(),
                "Records cannot extends; the implements list is the only supertype source");

        FileMetadata.TypeDecl en = decls.stream()
                .filter(d -> d.simpleName().equals("SiblingEnum"))
                .findFirst().orElseThrow();
        assertEquals(List.of("EnumIface"), en.supertypeSimpleNames(),
                "Enums cannot extends; the implements list is the only supertype source");
    }

    @Test
    void annotationDeclarationsCarryNoSupertypes() {
        // Annotations implicitly extend java.lang.annotation.Annotation
        // and cannot widen — an empty supertype list is the correct
        // contract, not a "we forgot to extract" silent drop.
        FileMetadata md = extract("""
                package com.example;
                public @interface Marker {}
                """);

        FileMetadata.TypeDecl marker = md.typeDeclarations().stream()
                .filter(d -> d.simpleName().equals("Marker"))
                .findFirst().orElseThrow();
        assertEquals(List.of(), marker.supertypeSimpleNames());
    }

    @Test
    void recordWithMultipleImplementsCapturesAll() {
        FileMetadata md = extract("""
                package com.example;
                public record Money(long cents) implements Comparable<Money>, HasCurrency {}
                """);

        FileMetadata.TypeDecl money = md.typeDeclarations().stream()
                .filter(d -> d.simpleName().equals("Money"))
                .findFirst().orElseThrow();
        // Generic types are surfaced as their simple name only, not
        // their argument list — Implementation matches by simple name
        // anyway, so {@code Comparable} (not {@code Comparable<Money>})
        // is the right shape.
        assertEquals(List.of("Comparable", "HasCurrency"), money.supertypeSimpleNames());
    }

    @Test
    void emptyFileSurfacesEmptyCollections() {
        // Storage is set (we wrote to a real file) so primaryTypeName
        // surfaces the filename basename — that's covered by the
        // dedicated test above. Here we pin that the type-shape
        // collections are empty for a genuinely empty source.
        FileMetadata md = extract("", "EmptyProbe");
        assertEquals("", md.packageName());
        assertEquals("EmptyProbe", md.primaryTypeName());
        assertTrue(md.imports().isEmpty());
        assertTrue(md.typeRefSimpleNames().isEmpty());
        assertTrue(md.typeRefDottedNames().isEmpty());
        assertTrue(md.typeDeclarations().isEmpty());
    }

    @Test
    void multipleTopLevelTypesInOneFileAllSurfaceInTypeDeclarations() {
        // Legal but rare: a single .java file with one public class and
        // one or more package-private peers. Implementation must consider
        // every top-level type as a potential implementer; the rewire
        // must NOT collapse to {@code primaryTypeName()} only or a
        // package-private peer that implements the same interface
        // would silently slip past the impl strategy.
        FileMetadata md = extract("""
                package com.example;
                public class Foo implements Iface {}
                class Bar implements Other {}
                """, "Foo");

        assertEquals("Foo", md.primaryTypeName(),
                "primaryTypeName is filename-driven and must surface the public type");
        List<FileMetadata.TypeDecl> decls = md.typeDeclarations();
        assertEquals(2, decls.size(),
                "Both top-level types must surface so ImplementationStrategy considers "
                        + "the package-private peer too");
        FileMetadata.TypeDecl bar = decls.stream()
                .filter(d -> d.simpleName().equals("Bar"))
                .findFirst().orElseThrow();
        assertEquals(List.of("Other"), bar.supertypeSimpleNames(),
                "package-private peer's implements clause must be preserved alongside "
                        + "the primary type's");
    }

    @Test
    void sealedHierarchyIsExtractedNormally() {
        // Sealed types extend / implement normally; the {@code permits}
        // clause is structural metadata about who may sub-type and does
        // NOT contribute supertype edges to the sealed type itself.
        // Implementation only cares about extends / implements, so the
        // extractor's contract is "permits is invisible at this layer".
        FileMetadata md = extract("""
                package com.example;
                public sealed interface Shape extends Comparable<Shape>
                        permits Circle, Square {}
                """, "Shape");

        FileMetadata.TypeDecl shape = md.typeDeclarations().stream()
                .filter(d -> d.simpleName().equals("Shape"))
                .findFirst().orElseThrow();
        assertEquals(List.of("Comparable"), shape.supertypeSimpleNames(),
                "sealed interfaces surface their extends clause normally; "
                        + "the permits list contributes no supertype edges");

        FileMetadata permitted = extract("""
                package com.example;
                public final class Circle implements Shape {}
                """, "Circle");
        FileMetadata.TypeDecl circle = permitted.typeDeclarations().stream()
                .filter(d -> d.simpleName().equals("Circle"))
                .findFirst().orElseThrow();
        assertEquals(List.of("Shape"), circle.supertypeSimpleNames(),
                "permitted impl must surface its implements clause — "
                        + "ImplementationStrategy's match relies on it");
    }

    @Test
    void packageInfoFileSurfacesPackageButHasNoTypeDeclarations() throws IOException {
        // package-info.java declares no real top-level type — JavaParser's
        // {@code getPrimaryTypeName()} happens to return the filename
        // basename {@code "package-info"}, but there's no
        // {@link com.github.javaparser.ast.body.TypeDeclaration} for it
        // (which is the discriminator the strategies actually consume:
        // ImplementationStrategy walks {@link FileMetadata#typeDeclarations()},
        // not the primary-type field). The contract for the extractor
        // is therefore: package surfaces, typeDeclarations is empty,
        // primaryTypeName is whatever JavaParser hands us — strategies
        // gate on the empty typeDeclarations list which is the
        // "no real type here" signal.
        Path file = tmp.resolve("package-info.java");
        Files.writeString(file, """
                @Deprecated
                package com.example;
                """);
        JavaParser parser = JavaLanguageParser.newParser();
        ParseResult<CompilationUnit> result = parser.parse(file);
        assertTrue(result.isSuccessful(), () -> "Parse failed: " + result.getProblems());
        FileMetadata md = FileMetadataExtractor.extract(result.getResult().orElseThrow());

        assertEquals("com.example", md.packageName());
        assertTrue(md.typeDeclarations().isEmpty(),
                "package-info.java has no TypeDeclaration even though it carries "
                        + "annotations — ImplementationStrategy walks typeDeclarations() "
                        + "and so correctly skips package-info as an impl candidate");
    }

    @Test
    void headerEdgesCaptureExtendsImplementsAndPermits() {
        // Issue #132 — the six-category header-edges record is the
        // first place {@code HeaderEdgesStrategy} touches. Pin the
        // simple shapes (a plain class, a sealed interface, and a
        // permitted impl) here so a parser-side regression that
        // drops permits / mis-routes extends / implements is loud
        // at this layer rather than appearing as a "headerEdges
        // adds nothing" silent miss.
        FileMetadata sealedSrc = extract("""
                package com.example;
                public sealed interface Shape extends Comparable<Shape>
                        permits Circle, Square {}
                """, "Shape");
        FileMetadata.TypeDecl shape = sealedSrc.typeDeclarations().stream()
                .filter(d -> d.simpleName().equals("Shape"))
                .findFirst().orElseThrow();
        FileMetadata.HeaderEdges sealedEdges = shape.headerEdges();
        // {@code interface ... extends X} is the {@code extends}
        // category — interfaces don't have an {@code implements}
        // clause. The generic argument inside {@code Comparable<Shape>}
        // is part of the extends clause's type, not a type-parameter
        // bound — {@code Shape} itself declares no type parameters
        // here, so {@code typeBoundSimpleNames} is empty.
        assertEquals(List.of("Comparable"), sealedEdges.extendsSimpleNames());
        assertTrue(sealedEdges.implementsSimpleNames().isEmpty());
        assertEquals(List.of("Circle", "Square"), sealedEdges.permittedSimpleNames());
        assertTrue(sealedEdges.typeBoundSimpleNames().isEmpty(),
                "Shape declares no type parameters, so no type-bound edges");

        FileMetadata classSrc = extract("""
                package com.example;
                public class FooService extends BaseService implements Service, AutoCloseable {}
                """, "FooService");
        FileMetadata.TypeDecl foo = classSrc.typeDeclarations().get(0);
        FileMetadata.HeaderEdges fooEdges = foo.headerEdges();
        assertEquals(List.of("BaseService"), fooEdges.extendsSimpleNames(),
                "classes route to the extends category");
        assertEquals(List.of("Service", "AutoCloseable"), fooEdges.implementsSimpleNames(),
                "multi-implements must preserve order so --explain renders them deterministically");
        assertTrue(fooEdges.permittedSimpleNames().isEmpty());
    }

    @Test
    void headerEdgesCaptureGenericBoundsOnTypeParameters() {
        // Type-parameter bounds are a header-edge category in their
        // own right — a class declared as {@code <T extends Foo &
        // Bar>} carries Foo and Bar as type-bound edges and the
        // strategy must surface both. Intersection types (the {@code
        // &} clause) are the place we previously dropped the
        // secondary bound, so pin both.
        FileMetadata md = extract("""
                package com.example;
                public class Box<T extends Number & Comparable<T>, U extends Iface> {}
                """, "Box");
        FileMetadata.TypeDecl box = md.typeDeclarations().get(0);
        FileMetadata.HeaderEdges edges = box.headerEdges();
        assertTrue(edges.typeBoundSimpleNames().contains("Number"));
        assertTrue(edges.typeBoundSimpleNames().contains("Comparable"),
                "the second bound after the & contributes its own type-bound entry");
        assertTrue(edges.typeBoundSimpleNames().contains("Iface"));
        // Generic arguments inside the bound (the {@code T} in
        // {@code Comparable<T>}) are part of the bound's type, not
        // a separate header-edge entry — the extractor takes the
        // outer name only.
        assertFalse(edges.typeBoundSimpleNames().contains("T"),
                "type-parameter references inside the bound's generic args "
                        + "must NOT be promoted to header-edge entries");
    }

    @Test
    void headerEdgesCaptureRecordComponents() {
        // Record components carry types in the header — a {@code
        // record Money(long cents, Currency code)} contributes
        // {@code Currency} as a record-component edge. {@code long}
        // is a primitive and contributes no edge (HeaderEdgesStrategy
        // can never resolve a primitive name to a project FQN).
        FileMetadata md = extract("""
                package com.example;
                public record Money(long cents, Currency code, java.util.List<Note> notes)
                        implements Comparable<Money> {}
                """, "Money");
        FileMetadata.TypeDecl money = md.typeDeclarations().get(0);
        FileMetadata.HeaderEdges edges = money.headerEdges();
        // {@code Currency} and {@code List} (component type) surface;
        // {@code Note} is a nested generic and also surfaces; {@code
        // long} does not. Primitive filtering is the extractor's job.
        assertTrue(edges.recordComponentSimpleNames().contains("Currency"));
        assertTrue(edges.recordComponentSimpleNames().contains("List")
                        || edges.recordComponentSimpleNames().contains("Note"),
                "the qualified component type's outer or inner simple name must surface");
        assertFalse(edges.recordComponentSimpleNames().contains("long"),
                "primitive types must NOT contribute record-component edges — "
                        + "the strategy's FQN resolver could never match them");
        // The {@code implements} clause still routes to the
        // implements category, not record-components.
        assertEquals(List.of("Comparable"), edges.implementsSimpleNames());
    }

    @Test
    void headerEdgesCaptureClassLevelAnnotations() {
        // Class-level annotations carry test-relevant behaviour in
        // Spring-shaped codebases ({@code @Service}, {@code
        // @Component}, {@code @Transactional}) and routing them
        // through the strategy is one of issue #132's headline
        // motivating cases. Field / method / parameter annotations
        // are NOT class-level and must not leak into this list.
        FileMetadata md = extract("""
                package com.example;
                import org.springframework.stereotype.Service;
                @Service("fooBean")
                @org.springframework.beans.factory.annotation.Autowired
                public class FooImpl {
                    @Deprecated
                    private String field;
                    @SuppressWarnings("unused")
                    public void method(@Deprecated String s) {}
                }
                """, "FooImpl");
        FileMetadata.TypeDecl foo = md.typeDeclarations().get(0);
        FileMetadata.HeaderEdges edges = foo.headerEdges();
        assertTrue(edges.annotationSimpleNames().contains("Service"),
                "imported class-level annotation must surface as its simple name");
        // Issue #132 follow-up (correctness C2/ADV-HE-01): a
        // fully-qualified annotation name must be preserved verbatim,
        // not stripped to its leaf. Stripping silently conflated
        // {@code @org.springframework.Autowired} with a hypothetical
        // shadowing project-local {@code com.example.Autowired},
        // routing the strategy down a misresolved edge. The resolver's
        // tier-0 routes the FQN directly to the ignore-glob layer so
        // Spring's {@code org.springframework.**} default fires the
        // ignored-by-glob outcome without involving any catalogue
        // lookup.
        assertTrue(edges.annotationSimpleNames().contains(
                        "org.springframework.beans.factory.annotation.Autowired"),
                "fully-qualified class-level annotation must surface verbatim "
                        + "so ignore-globs can match the actual FQN");
        assertFalse(edges.annotationSimpleNames().contains("Deprecated"),
                "field-level annotations are NOT class-level and must not leak in");
        assertFalse(edges.annotationSimpleNames().contains("SuppressWarnings"),
                "method-level annotations are NOT class-level and must not leak in");
    }

    @Test
    void headerEdgesAreEmptyForPlainClassWithNoHeader() {
        // The all-empty sentinel ({@link FileMetadata.HeaderEdges#EMPTY})
        // is the dominant case on the pre-issue-#132 code path: a
        // top-level {@code class Foo {}} with no extends / implements
        // / annotations / generics has nothing to contribute. Pin
        // that the extractor produces that sentinel-shaped record
        // rather than a six-empty-list construction the cache
        // encoder would have to round-trip.
        FileMetadata md = extract("""
                package com.example;
                public class Plain {}
                """, "Plain");
        FileMetadata.TypeDecl plain = md.typeDeclarations().get(0);
        assertTrue(plain.headerEdges().isEmpty(),
                "a plain class with no header-edge shape must surface HeaderEdges.EMPTY");
    }

    @Test
    void recordIsImmutableAndDefensivelyCopiesInputs() {
        // FileMetadata is shared across the discovery thread pool,
        // so mutation through any returned collection would race
        // with reads from other strategies. The canonical-record
        // copy pattern blocks that.
        java.util.ArrayList<FileMetadata.Import> mutableImports = new java.util.ArrayList<>();
        mutableImports.add(new FileMetadata.Import("com.foo.A", false, false));
        java.util.LinkedHashSet<String> mutableSimples = new java.util.LinkedHashSet<>();
        mutableSimples.add("Foo");
        java.util.ArrayList<FileMetadata.TypeDecl> mutableDecls = new java.util.ArrayList<>();
        mutableDecls.add(new FileMetadata.TypeDecl("X", List.of("Base")));

        FileMetadata md = new FileMetadata(
                "com.example",
                "X",
                mutableImports,
                mutableSimples,
                Set.of(),
                mutableDecls);

        // Mutating the inputs after construction must not affect the
        // record. {@code List.copyOf} / unmodifiable wrappers enforce
        // the snapshot contract.
        mutableImports.add(new FileMetadata.Import("evil", false, false));
        mutableSimples.add("Evil");
        mutableDecls.add(new FileMetadata.TypeDecl("Y", List.of()));

        assertEquals(1, md.imports().size());
        assertEquals(1, md.typeRefSimpleNames().size());
        assertEquals(1, md.typeDeclarations().size());

        // Returned collections must reject in-place mutation as well.
        assertThrows(UnsupportedOperationException.class,
                () -> md.imports().add(new FileMetadata.Import("evil", false, false)));
        assertThrows(UnsupportedOperationException.class,
                () -> md.typeRefSimpleNames().add("Evil"));
        assertThrows(UnsupportedOperationException.class,
                () -> md.typeDeclarations().add(new FileMetadata.TypeDecl("Y", List.of())));
    }

    @Test
    void qualifiedNameDisambiguatesNestedTypesSharingASimpleName() {
        // Issue #132 follow-up (correctness C1/ADV-HE-06 + C4/ADV-HE-03)
        // — when two outer classes each contain a nested type sharing
        // the same simple name, both nested decls must carry distinct
        // qualified names so the cache and the FQN catalogue can keep
        // them apart. Previously both surfaced as {@code simpleName="Z"}
        // and the cache's name-keyed merge collapsed their header
        // edges onto a single entry, while the catalogue overwrote one
        // FQN with the other.
        FileMetadata md = extract("""
                package com.example;
                public class A {
                    public static class Z {}
                }
                class B {
                    public static class Z {}
                }
                """, "A");

        List<String> qualified = new java.util.ArrayList<>();
        for (FileMetadata.TypeDecl decl : md.typeDeclarations()) {
            qualified.add(decl.qualifiedName());
        }
        assertTrue(qualified.contains("A"), "top-level A must surface as qualifiedName=A");
        assertTrue(qualified.contains("B"), "top-level B must surface as qualifiedName=B");
        assertTrue(qualified.contains("A.Z"),
                "nested Z in A must surface as qualifiedName=A.Z, got " + qualified);
        assertTrue(qualified.contains("B.Z"),
                "nested Z in B must surface as qualifiedName=B.Z, got " + qualified);

        for (FileMetadata.TypeDecl decl : md.typeDeclarations()) {
            if (decl.qualifiedName().equals("A.Z") || decl.qualifiedName().equals("B.Z")) {
                assertEquals("Z", decl.simpleName(),
                        "nested type's simpleName must remain the leaf, "
                                + "qualifiedName carries the in-CU path");
            }
        }
    }

    @Test
    void qualifiedNameMatchesSimpleNameForTopLevelTypes() {
        // The dominant case — top-level decls — must have
        // qualifiedName == simpleName so every pre-#132 caller that
        // reads simpleName keeps observing the expected leaf shape.
        FileMetadata md = extract("""
                package com.example;
                public class Foo {}
                class Bar {}
                """, "Foo");
        for (FileMetadata.TypeDecl decl : md.typeDeclarations()) {
            assertEquals(decl.simpleName(), decl.qualifiedName(),
                    "top-level decl's qualifiedName must equal its simpleName "
                            + "to preserve pre-#132 behavior for the dominant case");
        }
    }

    @Test
    void fullyQualifiedAnnotationPreservesItsFqn() {
        // Issue #132 follow-up (correctness C2/ADV-HE-01) — an
        // annotation written as a fully-qualified usage like
        // {@code @org.springframework.stereotype.Service} must
        // surface verbatim, not stripped to {@code Service}.
        // Stripping silently conflated the external Spring annotation
        // with a hypothetical shadowing project-local {@code Service}.
        FileMetadata md = extract("""
                package com.example;
                @org.springframework.stereotype.Service
                public class FullyQualifiedBean {}
                """, "FullyQualifiedBean");
        FileMetadata.HeaderEdges edges = md.typeDeclarations().get(0).headerEdges();
        assertTrue(edges.annotationSimpleNames()
                        .contains("org.springframework.stereotype.Service"),
                "FQ annotation usage must preserve its qualifier so the "
                        + "ignore-glob layer can match the actual FQN, got "
                        + edges.annotationSimpleNames());
        assertFalse(edges.annotationSimpleNames().contains("Service"),
                "FQ annotation must NOT also surface as the bare leaf — "
                        + "duplicating it would route through both the "
                        + "tier-0 FQN path and the tier-1-4 simple-name "
                        + "resolver, doubling diagnostic edges");
    }
}
