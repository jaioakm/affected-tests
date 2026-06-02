Feature: Issue #132 — headerEdges discovery strategy
  Issue #132 closes the Spring DI Gap: a change to a concrete impl
  whose interface is consumed only via DI must select the
  interface's contract tests. The four pre-existing strategies
  (naming / usage / impl / transitive) walk source-level type
  references and downward-subtype edges only — none of them walks
  from a changed concrete class UP to its supertypes or OUT to its
  header-declared types.

  The headerEdges strategy fills that gap. It treats anything that
  appears in a class declaration before the opening `{` as part of
  the class's identity and augments the changed-class set with the
  resolved header-edge targets before the four pre-existing
  strategies run.

  The scenarios below pin one slice of the behaviour each: a
  fire-through for each of the six categories, the three safety
  layers (ignore-globs, category opt-out, sibling cap), the kill
  switch, and the `--explain` rendering contract.

  Every scenario runs with `--explain` because:
  (a) the `selected tests include "..."` step asserts on FQNs that
      appear in the `--explain` Modules block exactly the same way
      they appear in the dispatch preview, so the assertion shape
      is identical on either path; and
  (b) actually dispatching the affected suite needs a complete
      Gradle wrapper in the TestKit project tree, which the e2e
      fixture intentionally omits — `--explain` exits before the
      dispatch fork, so the feature stays focused on the discovery
      decision without owning a parallel "real Gradle build"
      harness for what is fundamentally a discovery test.

  Background:
    Given a freshly initialised project with a committed baseline

  Scenario: H132-1 — implements edge closes the Spring DI Gap
    # The headline motivating case: StripeGateway implements
    # PaymentGateway. The diff touches only StripeGateway, but
    # PaymentGatewayTest (the interface contract test) must fire.
    # Without the implements edge, PaymentGatewayTest is silently
    # missed.
    Given a file at "src/main/java/com/example/api/PaymentGateway.java" with content:
      """
      package com.example.api;
      public interface PaymentGateway {}
      """
    And a file at "src/main/java/com/example/impl/StripeGateway.java" with content:
      """
      package com.example.impl;
      import com.example.api.PaymentGateway;
      public class StripeGateway implements PaymentGateway {}
      """
    And a file at "src/test/java/com/example/api/PaymentGatewayTest.java" with content:
      """
      package com.example.api;
      public class PaymentGatewayTest {}
      """
    And a file at "src/test/java/com/example/impl/StripeGatewayTest.java" with content:
      """
      package com.example.impl;
      public class StripeGatewayTest {}
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/impl/StripeGateway.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the action is SELECTED
    And the selected tests include "com.example.impl.StripeGatewayTest"
    And the selected tests include "com.example.api.PaymentGatewayTest"
    And the output contains "Header edges (issue #132):"

  Scenario: H132-2 — extends edge selects the base-class test
    # `Dog extends Animal`: changing Dog must fire AnimalTest so
    # the behaviour-tests on the parent class still cover the
    # changed override.
    Given a file at "src/main/java/com/example/Animal.java" with content:
      """
      package com.example;
      public class Animal {}
      """
    And a file at "src/main/java/com/example/Dog.java" with content:
      """
      package com.example;
      public class Dog extends Animal {}
      """
    And a file at "src/test/java/com/example/AnimalTest.java" with content:
      """
      package com.example;
      public class AnimalTest {}
      """
    And a file at "src/test/java/com/example/DogTest.java" with content:
      """
      package com.example;
      public class DogTest {}
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/Dog.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the action is SELECTED
    And the selected tests include "com.example.DogTest"
    And the selected tests include "com.example.AnimalTest"

  Scenario: H132-3 — permits edge selects every permitted impl's test
    # `sealed interface Shape permits Circle, Square` — when Shape
    # changes (pattern-match exhaustiveness contract evolved), both
    # CircleTest and SquareTest must fire because the permitted
    # impls' tests are the source of truth for the sealed
    # hierarchy's runtime behaviour.
    Given a file at "src/main/java/com/example/Shape.java" with content:
      """
      package com.example;
      public sealed interface Shape permits Circle, Square {}
      """
    And a file at "src/main/java/com/example/Circle.java" with content:
      """
      package com.example;
      public final class Circle implements Shape {}
      """
    And a file at "src/main/java/com/example/Square.java" with content:
      """
      package com.example;
      public final class Square implements Shape {}
      """
    And a file at "src/test/java/com/example/CircleTest.java" with content:
      """
      package com.example;
      public class CircleTest {}
      """
    And a file at "src/test/java/com/example/SquareTest.java" with content:
      """
      package com.example;
      public class SquareTest {}
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/Shape.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the action is SELECTED
    And the selected tests include "com.example.CircleTest"
    And the selected tests include "com.example.SquareTest"

  Scenario: H132-4 — type-bounds edge selects the bound's contract test
    # `class Box<T extends Validatable>` — changing Box's behaviour
    # in a way that depends on the Validatable contract must fire
    # ValidatableTest so the behavioural contract gets re-checked.
    Given a file at "src/main/java/com/example/Validatable.java" with content:
      """
      package com.example;
      public interface Validatable {}
      """
    And a file at "src/main/java/com/example/Box.java" with content:
      """
      package com.example;
      public class Box<T extends Validatable> {}
      """
    And a file at "src/test/java/com/example/ValidatableTest.java" with content:
      """
      package com.example;
      public class ValidatableTest {}
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/Box.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the action is SELECTED
    And the selected tests include "com.example.ValidatableTest"

  Scenario: H132-5 — record-components edge selects component-type tests
    # `record Order(Customer customer)` — changing Order's
    # construction signature must fire CustomerTest so the
    # component's invariants get re-checked.
    Given a file at "src/main/java/com/example/Customer.java" with content:
      """
      package com.example;
      public class Customer {}
      """
    And a file at "src/main/java/com/example/Order.java" with content:
      """
      package com.example;
      public record Order(Customer customer) {}
      """
    And a file at "src/test/java/com/example/CustomerTest.java" with content:
      """
      package com.example;
      public class CustomerTest {}
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/Order.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the action is SELECTED
    And the selected tests include "com.example.CustomerTest"

  Scenario: H132-6 — class-level annotations edge selects the annotation's test
    # Adopter-defined class-level annotation. Framework annotations
    # are muted by the default ignore-globs (covered in H132-7), but
    # an adopter-defined @CompanyAuditLog with non-trivial semantics
    # must fire its contract test when a class it tags changes.
    Given a file at "src/main/java/com/example/CompanyAuditLog.java" with content:
      """
      package com.example;
      public @interface CompanyAuditLog {}
      """
    And a file at "src/main/java/com/example/PaymentService.java" with content:
      """
      package com.example;
      @CompanyAuditLog
      public class PaymentService {}
      """
    And a file at "src/test/java/com/example/CompanyAuditLogTest.java" with content:
      """
      package com.example;
      public class CompanyAuditLogTest {}
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/PaymentService.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the action is SELECTED
    And the selected tests include "com.example.CompanyAuditLogTest"

  Scenario: H132-7 — ignore-globs suppress framework annotation noise
    # `@org.springframework.stereotype.Service`-shaped framework
    # annotations are on the default ignore-globs list. A class
    # tagged with one must NOT bring the framework annotation into
    # the augmented set — the strategy silently filters the edge
    # before resolution. We assert by checking the diagnostic
    # `--explain` block surfaces an IGNORED_BY_GLOB entry for the
    # Service annotation rather than an ADDED one. The PaymentService
    # → PaymentServiceTest match still fires via naming on the
    # directly-changed class; the test is that the framework
    # augmentation did NOT happen.
    Given a file at "src/main/java/org/springframework/stereotype/Service.java" with content:
      """
      package org.springframework.stereotype;
      public @interface Service {}
      """
    And a file at "src/main/java/com/example/PaymentService.java" with content:
      """
      package com.example;
      import org.springframework.stereotype.Service;
      @Service
      public class PaymentService {}
      """
    And a file at "src/test/java/com/example/PaymentServiceTest.java" with content:
      """
      package com.example;
      public class PaymentServiceTest {}
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/PaymentService.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the action is SELECTED
    And the selected tests include "com.example.PaymentServiceTest"
    # The framework annotation must show up as filtered, not added,
    # in the --explain block — confirming the default ignore-glob
    # `org.springframework.**` killed the edge.
    And the output contains "by ignore-glob"

  Scenario: H132-8 — kill switch restores pre-issue-#132 behaviour
    # `headerEdgesEnabled = false` — the one-flag escape hatch.
    # Same Spring DI Gap scenario as H132-1 but with the strategy
    # off; PaymentGatewayTest must NOT fire and the headerEdges
    # block must NOT appear in --explain.
    Given the affected-tests DSL contains:
      """
      headerEdgesEnabled = false
      """
    And a file at "src/main/java/com/example/api/PaymentGateway.java" with content:
      """
      package com.example.api;
      public interface PaymentGateway {}
      """
    And a file at "src/main/java/com/example/impl/StripeGateway.java" with content:
      """
      package com.example.impl;
      import com.example.api.PaymentGateway;
      public class StripeGateway implements PaymentGateway {}
      """
    And a file at "src/test/java/com/example/api/PaymentGatewayTest.java" with content:
      """
      package com.example.api;
      public class PaymentGatewayTest {}
      """
    And a file at "src/test/java/com/example/impl/StripeGatewayTest.java" with content:
      """
      package com.example.impl;
      public class StripeGatewayTest {}
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/impl/StripeGateway.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the action is SELECTED
    And the selected tests include "com.example.impl.StripeGatewayTest"
    # Kill switch reverts to pre-issue-#132 behaviour — no
    # PaymentGatewayTest, no headerEdges --explain block.
    And the output does not contain "com.example.api.PaymentGatewayTest"
    And the output does not contain "Header edges (issue #132):"

  Scenario: H132-9 — category opt-out drops one category cleanly
    # `headerEdgesExclude = ["annotations"]` suppresses the
    # annotations category only. The implements category still
    # fires.
    Given the affected-tests DSL contains:
      """
      headerEdgesExclude = ["annotations"]
      """
    And a file at "src/main/java/com/example/Audit.java" with content:
      """
      package com.example;
      public @interface Audit {}
      """
    And a file at "src/main/java/com/example/Iface.java" with content:
      """
      package com.example;
      public interface Iface {}
      """
    And a file at "src/main/java/com/example/Svc.java" with content:
      """
      package com.example;
      @Audit
      public class Svc implements Iface {}
      """
    And a file at "src/test/java/com/example/AuditTest.java" with content:
      """
      package com.example;
      public class AuditTest {}
      """
    And a file at "src/test/java/com/example/IfaceTest.java" with content:
      """
      package com.example;
      public class IfaceTest {}
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/Svc.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the action is SELECTED
    And the selected tests include "com.example.IfaceTest"
    # Annotations category is opted out — AuditTest must NOT be in
    # the selected set.
    And the output does not contain "com.example.AuditTest"

  Scenario: H132-10 — --explain renders the headerEdges block
    # The --explain trace must surface the augmentation block when
    # the strategy fires. The block lists per-edge entries so an
    # operator can answer "why did this MR's selected-set grow?"
    # without having to read the source. Pin the headline strings;
    # detailed JSON-shape assertions live in the unit tests.
    Given a file at "src/main/java/com/example/api/PaymentGateway.java" with content:
      """
      package com.example.api;
      public interface PaymentGateway {}
      """
    And a file at "src/main/java/com/example/impl/StripeGateway.java" with content:
      """
      package com.example.impl;
      import com.example.api.PaymentGateway;
      public class StripeGateway implements PaymentGateway {}
      """
    And a file at "src/test/java/com/example/api/PaymentGatewayTest.java" with content:
      """
      package com.example.api;
      public class PaymentGatewayTest {}
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/impl/StripeGateway.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the output contains "Header edges (issue #132):"
    And the output contains "augmented:"
    # The per-edge sample must label the category — pin the
    # "implements" arrow that fired for this scenario.
    And the output contains "[implements]"
