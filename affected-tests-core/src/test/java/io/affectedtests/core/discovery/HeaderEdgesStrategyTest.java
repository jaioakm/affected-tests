package io.affectedtests.core.discovery;

import io.affectedtests.core.config.AffectedTestsConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue&nbsp;#132 — unit-level contract for
 * {@link HeaderEdgesStrategy#augment(Set, ProjectIndex)}. Each test
 * pins one slice of the strategy's behaviour: a category-by-category
 * fire-through, the ignore-glob / category opt-out / sibling-cap
 * safety layers, the depth bound, and the FQN resolution tiers.
 *
 * <p>Tests share the {@link #writeSource} helper to materialise a
 * project tree under {@code @TempDir} and the {@link #buildIndex}
 * helper to spin up a real {@link ProjectIndex} so the strategy
 * exercises the same code path adopters hit in production —
 * fixtures-of-fixtures stub indexes would silently let an FQN
 * resolution regression land.
 */
class HeaderEdgesStrategyTest {

    @TempDir
    Path tempDir;

    private void writeSource(String relativePath, String source) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private ProjectIndex buildIndex(AffectedTestsConfig config) {
        return ProjectIndex.build(tempDir, config);
    }

    private AffectedTestsConfig.Builder baseBuilder() {
        // Wipe the default ignore-globs list so the unit tests don't
        // accidentally lose an edge to the framework-noise filter.
        // Per-test overrides re-introduce a focused list as needed.
        return AffectedTestsConfig.builder()
                .headerEdgesIgnore(List.of());
    }

    @Test
    void augmentExtendsTargetsToChangedSet() throws IOException {
        // Killer case: a {@code class Dog extends Animal} change must
        // bring {@code Animal} into the changed-class set so the
        // existing strategies pick up {@code AnimalTest}. Without the
        // extends edge, naming sees {@code Dog} only and {@code
        // AnimalTest} is silently missed.
        writeSource("src/main/java/com/example/Animal.java",
                "package com.example;\npublic class Animal {}");
        writeSource("src/main/java/com/example/Dog.java",
                "package com.example;\npublic class Dog extends Animal {}");

        try (ProjectIndex index = buildIndex(baseBuilder().build())) {
            HeaderEdgesStrategy strategy = new HeaderEdgesStrategy(
                    baseBuilder().build());
            HeaderEdgesStrategy.AugmentationResult result =
                    strategy.augment(Set.of("com.example.Dog"), index);

            assertTrue(result.augmentedTypes().contains("com.example.Dog"),
                    "the originally-changed class must always remain in the augmented set");
            assertTrue(result.augmentedTypes().contains("com.example.Animal"),
                    "extends edge must augment the changed set with the base class");
            assertTrue(result.edges().stream()
                            .anyMatch(e -> e.status() == HeaderEdgesStrategy.EdgeStatus.ADDED
                                    && "com.example.Animal".equals(e.targetFqn())),
                    "the augmentation must surface a diagnostic edge for Animal");
        }
    }

    @Test
    void augmentImplementsResolvesViaImports() throws IOException {
        // The Spring DI killer case. A concrete impl change must
        // bring its interface into the run so existing tests of the
        // interface's consumer fire.
        writeSource("src/main/java/com/example/api/PaymentGateway.java",
                "package com.example.api;\npublic interface PaymentGateway {}");
        writeSource("src/main/java/com/example/impl/StripeGateway.java",
                "package com.example.impl;\n"
                        + "import com.example.api.PaymentGateway;\n"
                        + "public class StripeGateway implements PaymentGateway {}");

        try (ProjectIndex index = buildIndex(baseBuilder().build())) {
            HeaderEdgesStrategy strategy = new HeaderEdgesStrategy(
                    baseBuilder().build());
            HeaderEdgesStrategy.AugmentationResult result =
                    strategy.augment(Set.of("com.example.impl.StripeGateway"), index);

            assertTrue(result.augmentedTypes().contains("com.example.api.PaymentGateway"),
                    "implements edge must resolve via the non-wildcard import");
        }
    }

    @Test
    void augmentImplementsResolvesViaSamePackageLookup() throws IOException {
        // No import needed when the target sits in the same package
        // — the resolution tier-2 (same-package lookup) must fire.
        writeSource("src/main/java/com/example/PaymentGateway.java",
                "package com.example;\npublic interface PaymentGateway {}");
        writeSource("src/main/java/com/example/StripeGateway.java",
                "package com.example;\npublic class StripeGateway implements PaymentGateway {}");

        try (ProjectIndex index = buildIndex(baseBuilder().build())) {
            HeaderEdgesStrategy strategy = new HeaderEdgesStrategy(
                    baseBuilder().build());
            HeaderEdgesStrategy.AugmentationResult result =
                    strategy.augment(Set.of("com.example.StripeGateway"), index);

            assertTrue(result.augmentedTypes().contains("com.example.PaymentGateway"),
                    "implements edge must resolve via the same-package tier when no import is declared");
        }
    }

    @Test
    void augmentPermitsBringsSealedParentIntoScope() throws IOException {
        // Sealed-permits edge: a change to a permitted subtype must
        // bring the sealed parent into the run so pattern-match
        // exhaustiveness tests fire. The Circle impl declares
        // {@code implements Shape}; that's the {@code implements}
        // category we already cover. The dedicated coverage here is
        // the {@code permits} edge surfaced by the {@code Shape}
        // declaration itself when {@code Shape} is in the diff.
        writeSource("src/main/java/com/example/Shape.java",
                "package com.example;\n"
                        + "public sealed interface Shape permits Circle, Square {}");
        writeSource("src/main/java/com/example/Circle.java",
                "package com.example;\npublic final class Circle implements Shape {}");
        writeSource("src/main/java/com/example/Square.java",
                "package com.example;\npublic final class Square implements Shape {}");

        try (ProjectIndex index = buildIndex(baseBuilder().build())) {
            HeaderEdgesStrategy strategy = new HeaderEdgesStrategy(
                    baseBuilder().build());
            HeaderEdgesStrategy.AugmentationResult result =
                    strategy.augment(Set.of("com.example.Shape"), index);

            assertTrue(result.augmentedTypes().contains("com.example.Circle"),
                    "permits edge must augment the sealed parent's change with each permitted impl");
            assertTrue(result.augmentedTypes().contains("com.example.Square"),
                    "permits edge must augment with every permitted impl, not just the first");
        }
    }

    @Test
    void augmentTypeBoundsResolveToInterface() throws IOException {
        // Generic bounds on a class are part of the header. A change
        // to {@code class Box<T extends Validatable>} brings {@code
        // Validatable} into scope.
        writeSource("src/main/java/com/example/Validatable.java",
                "package com.example;\npublic interface Validatable {}");
        writeSource("src/main/java/com/example/Box.java",
                "package com.example;\npublic class Box<T extends Validatable> {}");

        try (ProjectIndex index = buildIndex(baseBuilder().build())) {
            HeaderEdgesStrategy strategy = new HeaderEdgesStrategy(
                    baseBuilder().build());
            HeaderEdgesStrategy.AugmentationResult result =
                    strategy.augment(Set.of("com.example.Box"), index);

            assertTrue(result.augmentedTypes().contains("com.example.Validatable"),
                    "type-bound edge must surface the bound type as a changed-class augmentation");
        }
    }

    @Test
    void augmentRecordComponentsResolveToComponentTypes() throws IOException {
        // Record-component edge: {@code record Order(Customer c)}
        // brings {@code Customer} into the run when {@code Order} is
        // in the diff.
        writeSource("src/main/java/com/example/Customer.java",
                "package com.example;\npublic class Customer {}");
        writeSource("src/main/java/com/example/Order.java",
                "package com.example;\npublic record Order(Customer customer) {}");

        try (ProjectIndex index = buildIndex(baseBuilder().build())) {
            HeaderEdgesStrategy strategy = new HeaderEdgesStrategy(
                    baseBuilder().build());
            HeaderEdgesStrategy.AugmentationResult result =
                    strategy.augment(Set.of("com.example.Order"), index);

            assertTrue(result.augmentedTypes().contains("com.example.Customer"),
                    "record-component edge must surface each component's type as a changed-class augmentation");
        }
    }

    @Test
    void augmentClassLevelAnnotationsResolve() throws IOException {
        // Adopter-defined class-level annotation. Framework
        // annotations are filtered by the default ignore globs but
        // adopter code (a {@code @CompanyAuditLog}) must surface.
        writeSource("src/main/java/com/example/CompanyAuditLog.java",
                "package com.example;\npublic @interface CompanyAuditLog {}");
        writeSource("src/main/java/com/example/PaymentService.java",
                "package com.example;\n"
                        + "@CompanyAuditLog\n"
                        + "public class PaymentService {}");

        try (ProjectIndex index = buildIndex(baseBuilder().build())) {
            HeaderEdgesStrategy strategy = new HeaderEdgesStrategy(
                    baseBuilder().build());
            HeaderEdgesStrategy.AugmentationResult result =
                    strategy.augment(Set.of("com.example.PaymentService"), index);

            assertTrue(result.augmentedTypes().contains("com.example.CompanyAuditLog"),
                    "class-level annotation edge must surface the annotation type as a changed-class augmentation");
        }
    }

    @Test
    void ignoreGlobSuppressesFrameworkEdges() throws IOException {
        // Default ignore-globs mute Spring / JDK / JUnit noise. An
        // adopter-defined {@code @com.framework.Annot} suppressed by
        // the glob {@code com.framework.**} must NOT enter the
        // augmented set, and the strategy must record an
        // {@link HeaderEdgesStrategy.EdgeStatus#IGNORED_BY_GLOB} edge
        // so {@code --explain} can show which glob fired.
        writeSource("src/main/java/com/framework/Annot.java",
                "package com.framework;\npublic @interface Annot {}");
        writeSource("src/main/java/com/example/Svc.java",
                "package com.example;\n"
                        + "import com.framework.Annot;\n"
                        + "@Annot\n"
                        + "public class Svc {}");

        AffectedTestsConfig config = baseBuilder()
                .headerEdgesIgnore(List.of("com.framework.**"))
                .build();
        try (ProjectIndex index = buildIndex(config)) {
            HeaderEdgesStrategy strategy = new HeaderEdgesStrategy(config);
            HeaderEdgesStrategy.AugmentationResult result =
                    strategy.augment(Set.of("com.example.Svc"), index);

            assertFalse(result.augmentedTypes().contains("com.framework.Annot"),
                    "ignore-glob match must keep the target OUT of the augmented set");
            assertTrue(result.edges().stream()
                            .anyMatch(e -> e.status() == HeaderEdgesStrategy.EdgeStatus.IGNORED_BY_GLOB
                                    && "com.framework.Annot".equals(e.targetFqn())
                                    && "com.framework.**".equals(e.ignoreGlob())),
                    "the diagnostic edge must record the glob that suppressed the target");
        }
    }

    @Test
    void categoryOptOutSuppressesEdgesInThatCategoryOnly() throws IOException {
        // {@code headerEdgesExclude = ["annotations"]} suppresses the
        // annotations category only. Implements / extends edges keep
        // firing — adopters opt out of one noisy category without
        // losing the rest.
        writeSource("src/main/java/com/example/Annot.java",
                "package com.example;\npublic @interface Annot {}");
        writeSource("src/main/java/com/example/Iface.java",
                "package com.example;\npublic interface Iface {}");
        writeSource("src/main/java/com/example/Svc.java",
                "package com.example;\n"
                        + "@Annot\n"
                        + "public class Svc implements Iface {}");

        AffectedTestsConfig config = baseBuilder()
                .headerEdgesExclude(Set.of(AffectedTestsConfig.HEADER_EDGE_ANNOTATIONS))
                .build();
        try (ProjectIndex index = buildIndex(config)) {
            HeaderEdgesStrategy strategy = new HeaderEdgesStrategy(config);
            HeaderEdgesStrategy.AugmentationResult result =
                    strategy.augment(Set.of("com.example.Svc"), index);

            assertTrue(result.augmentedTypes().contains("com.example.Iface"),
                    "the non-excluded implements category must keep firing");
            assertFalse(result.augmentedTypes().contains("com.example.Annot"),
                    "the excluded annotations category must NOT contribute");
        }
    }

    @Test
    void siblingCapSuppressesDownwardImplWalk() throws IOException {
        // Motivating case: BaseController with many subclasses. The
        // header edges DOES add BaseController to the augmented set
        // (so naming picks up BaseControllerTest if it exists), but
        // BaseController is in {@code suppressedFromImplWalk} so
        // ImplementationStrategy does NOT walk DOWN through every
        // subclass. The added type keeps participating in naming /
        // usage / transitive; only the explosion vector is killed.
        writeSource("src/main/java/com/example/BaseController.java",
                "package com.example;\npublic class BaseController {}");
        // Six subclasses: with maxSiblings=5, BaseController exceeds
        // the cap and must be in the suppressedFromImplWalk set.
        for (int i = 0; i < 6; i++) {
            writeSource("src/main/java/com/example/Subclass" + i + ".java",
                    "package com.example;\npublic class Subclass" + i
                            + " extends BaseController {}");
        }
        // A controller in the diff that extends BaseController — the
        // strategy must augment with BaseController and then mark
        // BaseController as suppressed.
        writeSource("src/main/java/com/example/PaymentController.java",
                "package com.example;\npublic class PaymentController extends BaseController {}");

        AffectedTestsConfig config = baseBuilder()
                .headerEdgesMaxSiblings(5)
                .build();
        try (ProjectIndex index = buildIndex(config)) {
            HeaderEdgesStrategy strategy = new HeaderEdgesStrategy(config);
            HeaderEdgesStrategy.AugmentationResult result =
                    strategy.augment(Set.of("com.example.PaymentController"), index);

            assertTrue(result.augmentedTypes().contains("com.example.BaseController"),
                    "the header-edge target must still be in the augmented set — "
                            + "the cap only suppresses the downward impl walk, not the augmentation itself");
            assertTrue(result.suppressedFromImplWalk().contains("com.example.BaseController"),
                    "BaseController with 7 total subtypes (6 siblings + PaymentController) "
                            + "must be in the suppressedFromImplWalk set");
        }
    }

    @Test
    void siblingCapHonouredOnExactBoundary() throws IOException {
        // Exact-boundary case: with maxSiblings=5 and exactly 5
        // direct subtypes, the cap must NOT fire — that's the "5 is
        // fine, 6 is the explosion" contract.
        writeSource("src/main/java/com/example/Base.java",
                "package com.example;\npublic class Base {}");
        for (int i = 0; i < 5; i++) {
            writeSource("src/main/java/com/example/Sub" + i + ".java",
                    "package com.example;\npublic class Sub" + i + " extends Base {}");
        }

        AffectedTestsConfig config = baseBuilder()
                .headerEdgesMaxSiblings(5)
                .build();
        try (ProjectIndex index = buildIndex(config)) {
            HeaderEdgesStrategy strategy = new HeaderEdgesStrategy(config);
            HeaderEdgesStrategy.AugmentationResult result =
                    strategy.augment(Set.of("com.example.Sub0"), index);

            assertTrue(result.augmentedTypes().contains("com.example.Base"));
            assertFalse(result.suppressedFromImplWalk().contains("com.example.Base"),
                    "exactly maxSiblings direct subtypes does NOT trip the cap — "
                            + "the contract is strict-greater-than");
        }
    }

    @Test
    void killSwitchReturnsIdentityResult() throws IOException {
        // {@code headerEdgesEnabled = false} is the one-flag kill
        // switch. Augmentation returns identity — every changed
        // class flows through unchanged; no edges; no suppressions.
        writeSource("src/main/java/com/example/Animal.java",
                "package com.example;\npublic class Animal {}");
        writeSource("src/main/java/com/example/Dog.java",
                "package com.example;\npublic class Dog extends Animal {}");

        AffectedTestsConfig config = baseBuilder()
                .headerEdgesEnabled(false)
                .build();
        try (ProjectIndex index = buildIndex(config)) {
            HeaderEdgesStrategy strategy = new HeaderEdgesStrategy(config);
            Set<String> input = new LinkedHashSet<>(List.of("com.example.Dog"));
            HeaderEdgesStrategy.AugmentationResult result =
                    strategy.augment(input, index);

            assertEquals(input, result.augmentedTypes(),
                    "kill switch must return the input set unchanged");
            assertTrue(result.suppressedFromImplWalk().isEmpty(),
                    "kill switch must not produce any suppressions");
            assertTrue(result.edges().isEmpty(),
                    "kill switch must not produce any diagnostic edges");
        }
    }

    @Test
    void depthZeroBehavesLikeKillSwitch() throws IOException {
        // Degenerate-config corner case. {@code headerEdgesDepth = 0}
        // means "walk zero hops" — equivalent to the kill switch.
        // Pin this explicitly because the builder clamps depth into
        // [0, 2] and adopters who set 0 must get the documented
        // identity behaviour, not a silent fallback to depth=1.
        writeSource("src/main/java/com/example/Animal.java",
                "package com.example;\npublic class Animal {}");
        writeSource("src/main/java/com/example/Dog.java",
                "package com.example;\npublic class Dog extends Animal {}");

        AffectedTestsConfig config = baseBuilder()
                .headerEdgesDepth(0)
                .build();
        try (ProjectIndex index = buildIndex(config)) {
            HeaderEdgesStrategy strategy = new HeaderEdgesStrategy(config);
            HeaderEdgesStrategy.AugmentationResult result =
                    strategy.augment(Set.of("com.example.Dog"), index);

            assertFalse(result.augmentedTypes().contains("com.example.Animal"),
                    "depth=0 must NOT augment — equivalent to the kill switch");
        }
    }

    @Test
    void emptyChangedSetReturnsIdentity() throws IOException {
        // Empty input: no augmentation is possible. Augment must
        // return an identity result without walking the index.
        writeSource("src/main/java/com/example/Foo.java",
                "package com.example;\npublic class Foo {}");

        try (ProjectIndex index = buildIndex(baseBuilder().build())) {
            HeaderEdgesStrategy strategy = new HeaderEdgesStrategy(
                    baseBuilder().build());
            HeaderEdgesStrategy.AugmentationResult result =
                    strategy.augment(Set.of(), index);

            assertTrue(result.augmentedTypes().isEmpty());
            assertTrue(result.edges().isEmpty());
        }
    }

    @Test
    void unresolvedSimpleNameRecordsDiagnosticEdge() throws IOException {
        // A simple name in a header that doesn't resolve to any
        // project FQN (no import, no same-package match, no
        // global match) is dropped from the augmented set but kept
        // as an {@link HeaderEdgesStrategy.EdgeStatus#UNRESOLVED}
        // diagnostic edge so adopters can audit the gap via
        // {@code --explain}.
        //
        // The {@code Phantom} interface is referenced in the
        // implements clause but never defined in the project tree.
        writeSource("src/main/java/com/example/Svc.java",
                "package com.example;\npublic class Svc implements Phantom {}");

        try (ProjectIndex index = buildIndex(baseBuilder().build())) {
            HeaderEdgesStrategy strategy = new HeaderEdgesStrategy(
                    baseBuilder().build());
            HeaderEdgesStrategy.AugmentationResult result =
                    strategy.augment(Set.of("com.example.Svc"), index);

            assertFalse(result.augmentedTypes().contains("com.example.Phantom"),
                    "unresolvable simple name must NOT enter the augmented set — "
                            + "under-selection beats over-selection on ambiguity");
            assertTrue(result.edges().stream()
                            .anyMatch(e -> e.status() == HeaderEdgesStrategy.EdgeStatus.UNRESOLVED
                                    && "Phantom".equals(e.targetName())
                                    && e.targetFqn() == null),
                    "an UNRESOLVED diagnostic edge must record the simple name + null FQN");
        }
    }

    @Test
    void depthTwoWalksTransitively() throws IOException {
        // depth=2 walks one more hop from the depth-1 frontier.
        // {@code Dog extends Animal extends LivingThing} — at depth 1
        // we add Animal, at depth 2 we add LivingThing.
        writeSource("src/main/java/com/example/LivingThing.java",
                "package com.example;\npublic class LivingThing {}");
        writeSource("src/main/java/com/example/Animal.java",
                "package com.example;\npublic class Animal extends LivingThing {}");
        writeSource("src/main/java/com/example/Dog.java",
                "package com.example;\npublic class Dog extends Animal {}");

        AffectedTestsConfig deepConfig = baseBuilder()
                .headerEdgesDepth(2)
                .build();
        try (ProjectIndex index = buildIndex(deepConfig)) {
            HeaderEdgesStrategy strategy = new HeaderEdgesStrategy(deepConfig);
            HeaderEdgesStrategy.AugmentationResult result =
                    strategy.augment(Set.of("com.example.Dog"), index);

            assertTrue(result.augmentedTypes().contains("com.example.Animal"),
                    "depth=2 must include the direct supertype (depth 1)");
            assertTrue(result.augmentedTypes().contains("com.example.LivingThing"),
                    "depth=2 must include the second-hop supertype");
        }

        // Sanity check the depth bound: at depth=1 the second hop
        // must NOT fire — that's the safety contract.
        AffectedTestsConfig shallowConfig = baseBuilder()
                .headerEdgesDepth(1)
                .build();
        try (ProjectIndex index = buildIndex(shallowConfig)) {
            HeaderEdgesStrategy strategy = new HeaderEdgesStrategy(shallowConfig);
            HeaderEdgesStrategy.AugmentationResult result =
                    strategy.augment(Set.of("com.example.Dog"), index);

            assertTrue(result.augmentedTypes().contains("com.example.Animal"));
            assertFalse(result.augmentedTypes().contains("com.example.LivingThing"),
                    "depth=1 must NOT walk transitively past the first hop");
        }
    }

    @Test
    void changedClassesAlwaysPreservedInAugmentedSet() throws IOException {
        // The strategy is purely additive — it must NEVER drop a
        // directly-changed class from the augmentation, even if the
        // class itself has no header-edge contributions.
        writeSource("src/main/java/com/example/Plain.java",
                "package com.example;\npublic class Plain {}");

        try (ProjectIndex index = buildIndex(baseBuilder().build())) {
            HeaderEdgesStrategy strategy = new HeaderEdgesStrategy(
                    baseBuilder().build());
            HeaderEdgesStrategy.AugmentationResult result =
                    strategy.augment(Set.of("com.example.Plain"), index);

            assertTrue(result.augmentedTypes().contains("com.example.Plain"),
                    "purely additive contract: the changed class is always preserved");
        }
    }

    @Test
    void nestedTypesDoNotCollideWithSameNamedTopLevelTypes() throws IOException {
        // Issue #132 follow-up (correctness C4/ADV-HE-03): a nested
        // type whose simple name matches a sibling top-level type used
        // to collide on the catalogue's FQN map — both registered at
        // {@code com.example.Foo}, the second silently overwriting the
        // first. The FQN catalogue must now key on the in-CU qualified
        // name so nested decls resolve to {@code com.example.Outer.Foo}
        // and top-level decls keep their plain {@code com.example.Foo}.
        writeSource("src/main/java/com/example/Foo.java",
                "package com.example;\npublic class Foo {}");
        writeSource("src/main/java/com/example/Outer.java",
                "package com.example;\n"
                        + "public class Outer {\n"
                        + "    public static class Foo {}\n"
                        + "}");
        // A consumer that extends the top-level Foo. The strategy must
        // walk the extends edge to {@code com.example.Foo} (the
        // top-level), not silently route to {@code com.example.Outer.Foo}.
        writeSource("src/main/java/com/example/UsesTopLevelFoo.java",
                "package com.example;\npublic class UsesTopLevelFoo extends Foo {}");

        try (ProjectIndex index = buildIndex(baseBuilder().build())) {
            HeaderEdgesStrategy strategy = new HeaderEdgesStrategy(
                    baseBuilder().build());
            HeaderEdgesStrategy.AugmentationResult result =
                    strategy.augment(Set.of("com.example.UsesTopLevelFoo"), index);

            assertTrue(result.augmentedTypes().contains("com.example.Foo"),
                    "extends edge must resolve to the top-level Foo, not the "
                            + "nested Outer.Foo. Got " + result.augmentedTypes());
            assertFalse(result.augmentedTypes().contains("com.example.Outer.Foo"),
                    "the nested Outer.Foo must NOT be reached from a UsesTopLevelFoo "
                            + "edge — that would mean catalogue collision overwrote one "
                            + "of the two same-simple-name decls. Got "
                            + result.augmentedTypes());
        }
    }

    @Test
    void fullyQualifiedAnnotationGoesThroughIgnoreGlobLayer() throws IOException {
        // Issue #132 follow-up (correctness C2/ADV-HE-01): an
        // annotation written as a fully-qualified usage like
        // {@code @org.springframework.stereotype.Service} must route
        // through the resolver's tier-0 (preserve the FQN verbatim)
        // and then match the framework-noise ignore-glob. Previously
        // the extractor stripped to {@code Service}, the resolver
        // routed through tier-1-4 (project lookup), and any
        // shadowing project-local {@code Service} was a false-positive
        // walk target.
        writeSource("src/main/java/com/example/spring/Service.java",
                "package com.example.spring;\npublic class Service {}");
        writeSource("src/main/java/com/example/FullyQualifiedBean.java",
                "package com.example;\n"
                        + "@org.springframework.stereotype.Service\n"
                        + "public class FullyQualifiedBean {}");

        AffectedTestsConfig config = baseBuilder()
                .headerEdgesIgnore(List.of("org.springframework.**"))
                .build();
        try (ProjectIndex index = buildIndex(config)) {
            HeaderEdgesStrategy strategy = new HeaderEdgesStrategy(config);
            HeaderEdgesStrategy.AugmentationResult result =
                    strategy.augment(Set.of("com.example.FullyQualifiedBean"), index);

            assertFalse(result.augmentedTypes().contains("com.example.spring.Service"),
                    "FQ Spring annotation must NOT be silently rerouted to a "
                            + "shadowing project-local class — the resolver's tier-0 "
                            + "must preserve the FQN so the ignore-glob layer fires. "
                            + "Got " + result.augmentedTypes());
            assertTrue(result.edges().stream()
                            .anyMatch(e -> e.status()
                                    == HeaderEdgesStrategy.EdgeStatus.IGNORED_BY_GLOB
                                    && "org.springframework.**".equals(e.ignoreGlob())),
                    "the FQ annotation must surface an IGNORED_BY_GLOB diagnostic "
                            + "with the matching glob attached for --explain");
        }
    }

    @Test
    void scopedSupertypeResolvesAgainstNestedCatalogueEntry() throws IOException {
        // Issue #132 follow-up (correctness C3): a supertype written
        // as a nested-type-scoped reference like
        // {@code extends Outer.Inner} must resolve to the nested
        // catalogue entry {@code com.example.Outer.Inner}, not to a
        // sibling top-level {@code com.example.Inner}. The resolver's
        // tier-0 dotted-name handler walks {@code Outer} via the
        // normal tiers then attaches {@code .Inner}.
        writeSource("src/main/java/com/example/Inner.java",
                "package com.example;\npublic class Inner {}");
        writeSource("src/main/java/com/example/Outer.java",
                "package com.example;\n"
                        + "public class Outer {\n"
                        + "    public static class Inner {}\n"
                        + "}");
        writeSource("src/main/java/com/example/Consumer.java",
                "package com.example;\npublic class Consumer extends Outer.Inner {}");

        try (ProjectIndex index = buildIndex(baseBuilder().build())) {
            HeaderEdgesStrategy strategy = new HeaderEdgesStrategy(
                    baseBuilder().build());
            HeaderEdgesStrategy.AugmentationResult result =
                    strategy.augment(Set.of("com.example.Consumer"), index);

            assertTrue(result.augmentedTypes().contains("com.example.Outer.Inner"),
                    "scoped supertype Outer.Inner must augment as com.example.Outer.Inner. "
                            + "Got " + result.augmentedTypes());
            assertFalse(result.augmentedTypes().contains("com.example.Inner"),
                    "the top-level Inner must NOT also fire — pre-fix the supertype "
                            + "was collapsed to {@code getNameAsString() == \"Inner\"} "
                            + "and routed to the top-level catalogue entry. Got "
                            + result.augmentedTypes());
        }
    }
}
