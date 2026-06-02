# Affected Tests

A Gradle plugin that detects changes in the current branch and runs only the unit and integration tests relevant to those changes. No seed run required — it works immediately.

**Target stack:** Gradle 8+ (primary support: 9.x), Java 21+, JUnit 5

## Quick Start

### 1. Apply the plugin

```groovy
// build.gradle
plugins {
    id 'io.github.vedanthvdev.affectedtests' version 'x.y.z'
}
```

> Check the [Gradle Plugin Portal](https://plugins.gradle.org/plugin/io.github.vedanthvdev.affectedtests) or [Maven Central](https://central.sonatype.com/artifact/io.github.vedanthvdev/affected-tests-gradle) for the latest version. Both channels publish identical, SLSA-attested artifacts from the same CI run; Maven Central artifacts are additionally GPG-signed. See [`SECURITY.md`](SECURITY.md) for the verification flow.

### 2. Run affected tests

```bash
./gradlew affectedTest
```

### 3. (Optional) Explain the decision

```bash
./gradlew affectedTest --explain
```

Prints the full decision trace — bucket counts, situation, action, and the tier of the priority ladder (explicit `onXxx` / mode default) that picked each action — without running a single test. Useful when a CI run escalated to the full suite and the operator needs to know *why* before filing a bug.

When `outOfScopeTestDirs` / `outOfScopeSourceDirs` are configured but zero files in the diff land in the out-of-scope bucket *and* the situation is `DISCOVERY_SUCCESS` or `DISCOVERY_EMPTY`, the trace emits a one-line `Hint:` pointing at the configured knob. That's the silent-failure trap a real adopter hit: a perfectly valid-looking glob that never bit anything, which the plugin would otherwise only surface after a 30-minute full-suite CI run. The hint is suppressed on `EMPTY_DIFF`, `ALL_FILES_IGNORED`, `ALL_FILES_OUT_OF_SCOPE`, and `UNMAPPED_FILE` because those branches ran the way they did for reasons an out-of-scope pattern could not have influenced.

Sample output:

```
=== Affected Tests — decision trace (--explain) ===
Base ref:        origin/master
Mode:            AUTO (effective: LOCAL)
Changed files:   3
Buckets:
  ignored         1
  out-of-scope    0
  production      1
  test            0
  unmapped        1
  ignored sample: README.md
  production sample: src/main/java/com/example/Foo.java
  unmapped sample: build.gradle
Situation:       UNMAPPED_FILE
Action:          FULL_SUITE (source: mode default)
Outcome:         FULL_SUITE — onUnmappedFile=FULL_SUITE — non-Java or unmapped file in diff
Action matrix (situation → action [source]):
  EMPTY_DIFF               SKIPPED    [mode default]
  ALL_FILES_IGNORED        SKIPPED    [mode default]
  ALL_FILES_OUT_OF_SCOPE   SKIPPED    [mode default]
  UNMAPPED_FILE            FULL_SUITE [mode default]
  DISCOVERY_INCOMPLETE     SELECTED   [mode default]
  DISCOVERY_EMPTY          SKIPPED    [mode default]
  DISCOVERY_SUCCESS        SELECTED   [explicit onXxx setting]
=== end --explain ===
```

#### JSON output for dashboards / telemetry

```bash
./gradlew affectedTest --explain --explain-format=json
```

Emits the same decision trace as a single-line JSON object on `lifecycle()` instead of the human-readable text block. Designed for dashboard / telemetry pipelines that previously had to regex-parse the text trace and broke whenever the trace shape evolved.

Schema (carried in the `version` field; new fields are added additively, the version bumps when fields are renamed, removed, or retyped):

```json
{
  "version": 3,
  "baseRef": "origin/master",
  "mode": {"configured": "AUTO", "effective": "LOCAL"},
  "changedFiles": 3,
  "buckets": {"ignored": 1, "outOfScope": 0, "production": 1, "test": 0, "unmapped": 1},
  "samples": {
    "ignored": ["README.md"],
    "production": ["src/main/java/com/example/StripeGateway.java"],
    "unmapped": ["build.gradle"]
  },
  "situation": "DISCOVERY_SUCCESS",
  "action": {"name": "SELECTED", "source": "EXPLICIT"},
  "outcome": {"kind": "SELECTED", "selectedClassCount": 2},
  "discovery": {
    "totalMs": 13,
    "threads": 4,
    "strategies": {
      "naming":     {"ms": 0,  "tests": 1},
      "usage":      {"ms": 12, "tests": 2, "dominant": true},
      "impl":       {"ms": 12, "tests": 1},
      "transitive": {"ms": 2,  "tests": 0}
    }
  },
  "headerEdges": {
    "added": 2,
    "suppressed": 0,
    "suppressedFqns": [],
    "edges": [
      {
        "source": "com.example.StripeGateway",
        "target": "com.example.PaymentGateway",
        "targetName": "PaymentGateway",
        "category": "implements",
        "status": "ADDED"
      }
    ]
  },
  "modules": [
    {"path": ":test", "testClasses": ["com.example.PaymentGatewayTest", "com.example.PaymentGatewayContractTest"]}
  ],
  "actionMatrix": {
    "EMPTY_DIFF":              {"action": "SKIPPED",    "source": "MODE_DEFAULT"},
    "ALL_FILES_IGNORED":       {"action": "SKIPPED",    "source": "MODE_DEFAULT"},
    "ALL_FILES_OUT_OF_SCOPE":  {"action": "SKIPPED",    "source": "MODE_DEFAULT"},
    "UNMAPPED_FILE":           {"action": "FULL_SUITE", "source": "MODE_DEFAULT"},
    "DISCOVERY_INCOMPLETE":    {"action": "SELECTED",   "source": "MODE_DEFAULT"},
    "DISCOVERY_EMPTY":         {"action": "SKIPPED",    "source": "MODE_DEFAULT"},
    "DISCOVERY_SUCCESS":       {"action": "SELECTED",   "source": "EXPLICIT"}
  }
}
```

Schema history: `v=1` (initial); `v=2` adds the `discovery` block (issue #76, parallel-strategy timing); `v=3` adds the `headerEdges` block (issue #132, header-edges strategy diagnostics).

Field-by-field semantics:

| Field | Type | Notes |
|---|---|---|
| `version` | int | Schema version. Future additions stay within this version as long as field names + types don't change. |
| `baseRef` | string | The git ref the diff was computed against. |
| `mode` | object | `configured` is what the DSL declared (`AUTO` / `LOCAL` / `CI` / `STRICT`); `effective` is what `AUTO` resolved to on this run. |
| `changedFiles` | int | Total diff size across every bucket. |
| `buckets` | object | Five fixed integer counts: `ignored`, `outOfScope`, `production`, `test`, `unmapped`. |
| `samples` | object | Per-bucket file samples (sorted, capped at 10 entries — same cap the text trace uses). Empty buckets are omitted from this object; compare each sample's length against the corresponding `buckets[X]` count to detect truncation. |
| `situation` | string | One of the seven `Situation` enum values. |
| `action` | object | `name` is one of `SELECTED` / `FULL_SUITE` / `SKIPPED`; `source` says which configuration tier resolved it (`MODE_DEFAULT` / `EXPLICIT`). |
| `outcome` | object | `kind` mirrors `action.name`; `selectedClassCount` is meaningful on `SELECTED` runs (and `0` elsewhere); `escalationReason` is the same enum the text trace renders in the `Outcome:` line. |
| `modules` | array | One entry per dispatched `:module:test` task (empty on non-`SELECTED` runs). Each entry carries the canonical task path and the full FQN list — no truncation, since this is the actionable downstream-routing surface. |
| `discovery` | object | Always present. Carries discovery execution metadata: total wall time (`totalMs`), worker count (`threads`), and per-strategy timing / contribution details under `strategies`. Empty-diff / test-only fast paths emit zero defaults and an empty strategies object. |
| `headerEdges` | object | Always present. Carries the header-edge augmentation summary added in schema v3: augmented type count (`added`), sibling-cap suppression count (`suppressed`), sampled suppressed FQNs, and per-edge source / target / category / status entries. The no-op shape is zero counts plus empty arrays. |
| `actionMatrix` | object | Every `Situation` enum value maps to its `{action, source}` pair. Pinned in tests so a future engine that adds a situation also has to update the JSON renderer. |

Fields may be added without bumping `version`. Removing or renaming fields, changing field types, or changing existing field semantics requires a version bump. Consumers should parse defensively by ignoring unknown fields.

The text format remains the default — JSON is opt-in. Switching format never invalidates a Gradle cached execution because `--explain-format` only changes lifecycle logging, never the set of tests Gradle would actually run.

That's it. With zero config, the plugin will:

- Diff against `origin/master` (including uncommitted + staged changes).
- Route each changed file through one of five buckets: **ignored** (`*.md`, LICENSE, CHANGELOG, images, `**/generated/**`), **out-of-scope**, **production `.java`**, **test `.java`**, or **unmapped** (everything else, e.g. `application.yml`).
- Pick a discovery strategy — **naming**, **usage**, **impl**, **transitive**, **headerEdges** — and merge their results into one test set.
- Follow 4 levels of transitive dependencies (tuned for typical controller → service → repository chains).
- Fall through to the full suite if it encounters an unmapped file, so a YAML/Gradle/Liquibase diff never ships without tests.

## CI Integration

### GitHub Actions

```yaml
- run: ./gradlew affectedTest -PaffectedTestsBaseRef=${{ github.event.pull_request.base.sha }}
```

### GitLab CI / Jenkins

```bash
./gradlew affectedTest -PaffectedTestsBaseRef=origin/main
```

> Make sure to use `fetch-depth: 0` so `git diff` has access to the full history.

## The v2 decision model

Every invocation resolves to exactly one **Situation** and exactly one **Action**, both of which appear in the log line so an operator can tell — at a glance — why the plugin chose what it did.

### Summary log format

Every `affectedTest` run prints exactly one summary line in the form `Affected Tests: <OUTCOME> (<SITUATION>) — <details>`:

```
Affected Tests: SELECTED (DISCOVERY_SUCCESS) — 3 changed file(s), 2 production class(es), 5 test class(es) affected
Affected Tests: FULL_SUITE (UNMAPPED_FILE) — 1 changed file(s); running full suite (onUnmappedFile=FULL_SUITE — non-Java or unmapped file in diff).
Affected Tests: SKIPPED (ALL_FILES_IGNORED) — 1 changed file(s); every changed file matched ignorePaths.
```

The outcome (`SELECTED` / `FULL_SUITE` / `SKIPPED`) and the situation that produced it are first-class fields on every branch, so CI dashboards can `grep -E '^Affected Tests: (SELECTED|FULL_SUITE|SKIPPED)'` and bucket runs without parsing the tail.

On a `SELECTED` outcome, the task also prints the first few FQNs per module at lifecycle level so a reviewer can sanity-check the dispatch from the default CI log without rerunning with `--info`:

```
Running 17 affected test classes across 2 module(s):
  application:test (12 test classes)
    com.example.auth.LoginControllerTest
    com.example.auth.TokenServiceTest
    com.example.auth.PasswordHasherTest
    com.example.auth.SessionRepositoryTest
    com.example.auth.RoleMapperTest
    … and 7 more (use --info for full list)
  api:test (5 test classes)
    com.example.api.PublicEndpointsTest
    com.example.api.RateLimitTest
    com.example.api.VersionHeaderTest
    com.example.api.ErrorFormatTest
    com.example.api.HealthProbeTest
```

The preview caps at five FQNs per module; `--info` still surfaces the full per-FQN list. The cap exists so a utility change that ripples into hundreds of test classes can't blow past the 4 MiB GitHub Actions per-step log cap before the nested test output even starts.

### Situations (what the engine saw)

| Situation | Fires when |
|---|---|
| `EMPTY_DIFF` | `git diff` produced no files at all. |
| `ALL_FILES_IGNORED` | Every file in the diff matched `ignorePaths` (e.g. a docs-only MR). |
| `ALL_FILES_OUT_OF_SCOPE` | Every file sat under `outOfScopeTestDirs` or `outOfScopeSourceDirs` (e.g. a Cucumber/api-test-only MR). |
| `UNMAPPED_FILE` | The diff contains at least one file the plugin cannot resolve to a Java class under `sourceDirs`/`testDirs` (e.g. `application.yml`, `build.gradle`, a Liquibase changelog). |
| `DISCOVERY_EMPTY` | Mapping succeeded but the discovery strategies returned zero tests. |
| `DISCOVERY_SUCCESS` | Mapping + discovery produced a non-empty test set. |

### Actions (what the engine will do)

| Action | Meaning |
|---|---|
| `SELECTED` | Run only the discovered affected tests. |
| `FULL_SUITE` | Run the entire test suite (no `--tests` filter). |
| `SKIPPED` | Exit 0 without running tests. |

Every situation gets an independently-configurable action. The matrix is resolved in strict priority order: **explicit `onXxx`** setting → **`mode` profile default**. Zero-config installs always resolve to a concrete mode via `Mode.AUTO` detection, so nothing you configure today silently regresses tomorrow.

### Mode profiles

`mode` seeds the defaults for situations you haven't explicitly configured:

| Mode | `EMPTY_DIFF` | `ALL_FILES_IGNORED` | `ALL_FILES_OUT_OF_SCOPE` | `UNMAPPED_FILE` | `DISCOVERY_EMPTY` | `DISCOVERY_INCOMPLETE` |
|---|---|---|---|---|---|---|
| `local` | SKIPPED | SKIPPED | SKIPPED | FULL_SUITE | SKIPPED | SELECTED |
| `ci` | SKIPPED | SKIPPED | SKIPPED | FULL_SUITE | **FULL_SUITE** | **FULL_SUITE** |
| `strict` | FULL_SUITE | FULL_SUITE | SKIPPED | FULL_SUITE | FULL_SUITE | FULL_SUITE |
| `auto` | Detects `CI` / `GITHUB_ACTIONS` / `GITLAB_CI` / `JENKINS_HOME` and resolves to `ci` or `local`. |

Leaving `mode` unset picks `auto`, which resolves to `local` or `ci` depending on the environment. The `UNMAPPED_FILE → FULL_SUITE` safety net is the default in every built-in mode, so a zero-config install still escalates on unmapped files without any DSL wiring.

## Configuration

All settings have sensible defaults. Override only what you need.

```groovy
affectedTests {
    // Git base ref to diff against (default: "origin/master")
    baseRef = "origin/main"

    // Include uncommitted/staged changes (default: false — committed-only).
    // The plugin ships COMMITTED-ONLY so a local run matches the MR diff
    // CI will pick up on the same HEAD, and running the task twice in a
    // row produces the same test selection regardless of workstation state.
    // Flip to `true` locally when you are iterating on tests and want
    // WIP to seed the diff. Never enable these in CI — the tree is
    // already clean after checkout there, so they are pure noise.
    includeUncommitted = false
    includeStaged = false

    // v2 profile. "auto" is the recommended migration target.
    // See the "Mode profiles" table above.
    // (default: unset — preserves pre-v2 defaults)
    mode = "auto"

    // ---------------- Path buckets (v2) ----------------

    // Files that must not influence test selection (docs, LICENSE,
    // CHANGELOG, images, generated sources). When every file in the
    // diff matches ignorePaths, the engine lands on ALL_FILES_IGNORED.
    // The defaults already cover markdown/text/LICENSE/CHANGELOG/images
    // at both the repo root and nested paths — you usually only extend this.
    ignorePaths = ["**/*Dto.java"]

    // Test source sets the plugin must not dispatch via the affectedTest
    // task (e.g. Cucumber, Gatling). A diff entirely under these dirs
    // routes to ALL_FILES_OUT_OF_SCOPE → SKIPPED by default.
    //
    // Entries may be either:
    //   • literal directory prefixes — "api-test/src/test/java" matches
    //     that path at the repo root or under any module
    //     (e.g. "services/orders/api-test/src/test/java/..."), and
    //     "api-test" (no source-dir suffix) never claims sibling names
    //     like "api-test-utils/...";
    //   • Ant-style globs — "api-test/**" or "**/api-test/**" — using
    //     the standard JVM glob syntax ("*", "**", "?", "[abc]", "{a,b}").
    //
    // Mix both shapes freely; the plugin picks the right semantics per
    // entry. If you configure this knob but see "Hint:" on --explain
    // saying zero files matched, your paths/globs don't bite — double
    // check them (that's the silent-failure trap sanity testing caught).
    outOfScopeTestDirs = ["api-test/**", "performance-test/**"]

    // Production source sets the plugin must treat as out-of-scope.
    // Accepts the same literal-prefix / glob shapes as outOfScopeTestDirs.
    outOfScopeSourceDirs = []

    // ---------------- Per-situation actions (v2) ----------------

    // Each takes one of "selected" | "full_suite" | "skipped".
    // Any value left unset falls back through mode → pre-v2 default.
    onEmptyDiff            = "skipped"
    onAllFilesIgnored      = "skipped"
    onAllFilesOutOfScope   = "skipped"
    onUnmappedFile         = "full_suite"  // the key MR-safety knob
    onDiscoveryEmpty       = "full_suite"  // belt-and-braces for CI
    // Fires when any scanned Java file failed to parse (malformed source,
    // half-committed refactor, encoding glitch). Mode defaults: CI / STRICT
    // escalate to full_suite (selection is known to be under-reported so
    // safety wins); LOCAL stays on selected (the developer sees the WARN
    // and values iteration speed on a branch they're actively editing).
    onDiscoveryIncomplete  = "full_suite"

    // ---------------- Child-process deadline (v1.9.22) ----------------

    // Wall-clock timeout in seconds for the nested `./gradlew :module:test`
    // invocation. 0 (the default) disables the watchdog and matches
    // pre-v1.9.22 behaviour — the task waits forever. Positive values
    // spawn the child under a ProcessBuilder watchdog: on timeout the
    // task runs destroy() → 10s grace → destroyForcibly() → 5s reap and
    // then fails the build so a hung test never holds the CI worker
    // hostage. Note: the watchdog path uses inheritIO for the child's
    // stdio, so Develocity build-scan stream capture of the nested
    // output is not available when a timeout is set — leave at 0 and
    // enforce the deadline at the CI-job level if you rely on scan
    // ingestion of child-process output.
    //
    // Negative values are rejected. Since v2.1 the range check fires at
    // configuration time (IDE sync / `./gradlew help`) rather than only
    // when the task executes, so a typo like `-5` is caught without
    // needing to actually launch `affectedTest`.
    gradlewTimeoutSeconds  = 1800  // 30 min; use 3600 for suites with integration tests

    // ---------------- Discovery tuning ----------------

    // Discovery strategies: "naming", "usage", "impl", "transitive",
    // "headerEdges" (default: all five). "headerEdges" closes the
    // Spring DI Gap — a change to a concrete impl whose interface is
    // consumed only via DI now selects the interface's contract tests.
    // See the "headerEdges" row in the strategies table below for the
    // full rationale and safety contracts.
    strategies = ["naming", "usage", "impl", "transitive", "headerEdges"]

    // ---------------- headerEdges tuning (issue #132) ----------------

    // One-flag kill switch. Default true; flip to false to revert to
    // pre-issue-#132 behaviour without un-listing the strategy.
    headerEdgesEnabled = true

    // Per-category opt-out. Valid entries: "extends", "implements",
    // "permits", "type-bounds", "record-components", "annotations".
    // Default empty (every category on). Most common opt-out:
    // ["annotations"] for codebases with many custom class-level
    // annotations that carry no test-relevant behaviour.
    headerEdgesExclude = []

    // Walk depth: 1 (default) walks immediate header targets only,
    // 2 walks one more hop. Clamped to [0, 2]; higher values rapidly
    // approach FULL_SUITE on real codebases. 0 is equivalent to the
    // kill switch.
    headerEdgesDepth = 1

    // Sibling cap on the impl-walk explosion vector. When a
    // header-edge-added type has more than N direct subtypes,
    // ImplementationStrategy's downward walk from THAT added type is
    // suppressed. The added type still participates in
    // naming / usage / transitive — only the impl-walk explosion is
    // killed. Default 5; set to 0 to suppress every downward walk
    // from a header-edge-added type. Motivating case:
    // BaseController extends with 52 subclasses — without the cap,
    // a touch of any PaymentController would select every
    // *ControllerTest in the codebase.
    headerEdgesMaxSiblings = 5

    // FQN globs (java.nio glob syntax) for header-edge targets whose
    // contribution is suppressed. Default list mutes JDK / Spring /
    // JUnit / Mockito / Kotlin / Lombok stdlib noise that pollutes
    // selection without contributing signal. Setting an explicit list
    // here REPLACES the default — concatenate with the default if you
    // only want to ADD a glob.
    headerEdgesIgnore = ["com.adopter.framework.**"]

    // Transitive dependency depth — used when the "transitive" strategy is enabled.
    // Raised from 2 → 4 in v2 because typical Spring controller→service→repo
    // chains are 2–3 deep; 4 gives a margin without producing runaway sets.
    // (default: 4, max: 5, 0 = disabled)
    transitiveDepth = 4

    // Engine-level parallel discovery (issue #42). Fans the four discovery
    // strategies out across a small thread pool sized to
    // min(strategies, availableProcessors); collapses to in-line serial on
    // single-vCPU hosts where the pool would just add overhead. Default
    // true; flip to false (or pass `-PaffectedTestsParallelDiscovery=false`)
    // if a workload ever needs the serial path back. The `--explain` trace
    // prints a per-strategy breakdown
    // ("Discovery: parallel (4 threads, 12ms total)") so adopters can see
    // whether parallel actually helped on their workload and which
    // strategy is the wall-time hog.
    parallelDiscovery = true

    // Test class suffixes (default: ["Test", "IT", "ITTest", "IntegrationTest"])
    testSuffixes = ["Test", "IT", "ITTest", "IntegrationTest"]

    // Source directories (default: auto-discovered from JavaPluginExtension
    // source sets at projectsEvaluated time, falling back to ["src/main/java"]
    // when no Java plugin is applied; setting an explicit list here always
    // wins, since Gradle's Property.convention semantics apply only when no
    // value is set)
    sourceDirs = ["src/main/java"]

    // Test directories (default: auto-discovered from JavaPluginExtension —
    // any source set wired to a Test-typed task surfaces here, including
    // custom names like `integrationTest`, `e2eTest`, etc.; setting an
    // explicit list overrides the auto-discovered default)
    testDirs = ["src/test/java"]

    // Gradle test task names the dispatch path may invoke (default: ["test"]).
    // Adopters with extra source sets list them here so discovered tests
    // route to the right task by Gradle's `src/<taskName>/java` convention.
    // Most users keep the default; opt in only when an `integrationTest` /
    // `e2eTest` task exists *and* its source directory is also added to
    // `testDirs` so discovery can see those tests.
    testTaskNames = ["test", "integrationTest"]

    // Include tests for implementations of changed interfaces (default: true)
    includeImplementationTests = true

    // Implementation naming prefixes/suffixes — "Impl" matches FooImpl for Foo;
    // "Default" matches DefaultFoo for Foo, which is idiomatic in Spring code.
    // (default: ["Impl", "Default"])
    implementationNaming = ["Impl", "Default"]
}
```

## How It Works

The pipeline is five stages: **detect** what changed, **bucket** each path (ignored / out-of-scope / production / test / unmapped), **resolve** the `Situation`, **discover** the tests impacted by the in-scope Java classes, and **execute** only that subset — or the full suite, or nothing at all — based on the `Action` the `Situation` maps to.

### Known limitations

- **Kotlin AST: default-on (PR #4 of issue [#76](https://github.com/vedanthvdev/affected-tests/issues/76)).** `.kt` sources participate in AST-driven discovery on every run, the same way `.java` does. The path-derived fallback PR #1 introduced (a `Foo.kt` with sibling `FooTest.kt` selecting via the naming-convention strategy, top-level functions in `Util.kt` emitting a synthetic `UtilKt` FQN) still runs underneath, but the AST path now feeds UsageStrategy direct-import, ImplementationStrategy supertype walk, and TransitiveStrategy reference graph the same `FileMetadata` shape Java produces. `--explain` surfaces four pinned strings — `Kotlin source AST-mapped to FQN ...`, `Kotlin file failed to parse with embeddable ...; counted into DISCOVERY_INCOMPLETE`, `Kotlin file ... declares package X but path-derives to Y; AST-driven strategies use the declared package, naming strategy uses the path-derived FQN`, and `Kotlin embeddable failed to load: ...; treating .kt files as unparseable for this run` — so adopters can audit the AST path on every run. Three Phase-2 limitations carry forward: (1) `@file:JvmName(...)` is detected and surfaced via `--explain` but the synthetic class name still uses the `<basename>Kt` shape (a follow-up issue tracks honouring the override); (2) the parser focuses on Kotlin/JVM and prunes the JS / Native / WASM / FIR-analysis / IR-codegen subsystems from the shadow JAR for size, so any future Kotlin Multiplatform feature that depends on those subsystems is not reachable through this plugin; (3) the `KotlinCoreEnvironment` underpinning the parser registers a process-wide static MockApplication on first use — adopters running multi-module Gradle builds with `org.gradle.parallel=true` who hit a bootstrap collision across subprojects can flip `affectedTests { kotlinEnabled = false }` (the documented escape hatch) to fall back to the Phase 1 path-derived shape on `.kt` files. See [`docs/PHASE-2-KOTLIN-AST.md`](docs/PHASE-2-KOTLIN-AST.md) for the four-PR sequence.
- **Other JVM polyglot files still unmapped.** `.kts`, `.groovy`, and `.scala` changes still fall through to the `unmapped` bucket and — under the default `ci` / `strict` profiles — escalate to `FULL_SUITE` via `UNMAPPED_FILE`. Tracking issue: [#47](https://github.com/vedanthvdev/affected-tests/issues/47).
  - Workaround for "Java production + Groovy/Scala tests" repos: route those test files into `outOfScopeTestDirs` so a non-Java/Kotlin-only edit lands in `ALL_FILES_OUT_OF_SCOPE` (`SKIPPED`) instead of escalating; combine with a separate Gradle task that runs those tests unconditionally on every MR.
- **Gradle 9.3+ only.** The published plugin is built against the current Gradle wrapper (currently 9.5.1) and requires Java 21+ on the classpath. Older Gradle versions are not supported.
- **In-process git read.** Diff resolution uses JGit reading the working-tree `.git` directory directly. Bare repos, alternate worktrees that don't have their own `.git/objects`, and shallow clones with the base ref outside the fetched history are not supported — the engine logs a JGit error and escalates to `FULL_SUITE`.

<p align="center">
  <img src="docs/architecture.svg" alt="Affected Tests v2 architecture: git diff feeds PathToClassMapper which buckets each changed file as ignored, out-of-scope, production Java, test Java, or unmapped; the resulting Situation (EMPTY_DIFF, ALL_FILES_IGNORED, ALL_FILES_OUT_OF_SCOPE, UNMAPPED_FILE, DISCOVERY_EMPTY, DISCOVERY_SUCCESS) maps to an Action (SELECTED, FULL_SUITE, SKIPPED) resolved in priority order from explicit onXxx settings, legacy booleans, mode defaults, and pre-v2 hardcoded defaults" width="100%">
</p>

<sub>Source: [`docs/architecture.mmd`](docs/architecture.mmd) · regenerate with `npx --yes @mermaid-js/mermaid-cli -i docs/architecture.mmd -o docs/architecture.svg -b transparent`</sub>

### Discovery Strategies

All five strategies run against every changed production class. Their results are merged (union), so a test is run if **any** strategy identifies it. The goal is maximum coverage — running a few extra tests is always preferable to missing one.

| Strategy | What it does | Example |
|----------|-------------|---------|
| **naming** | For each changed class `Foo`, looks for test files named `FooTest`, `FooIT`, `FooITTest`, `FooIntegrationTest` (configurable suffixes). Purely file-name based — no parsing required. **Trade-off:** the match is intentionally package-agnostic so adopters who keep tests in a deliberately different package than the SUT (parallel test trees, Cucumber-shape harnesses) are not silently under-selected. The cost is over-selection when two production classes legitimately share a simple name across packages: a change to `a.b.Foo` can pull in `e.f.FooTest` that actually exercises `c.d.Foo`. Surfaced via a `--explain` Hint listing the exact pairs so the over-selection is visible without flipping the policy. | `FooService` changed → finds `FooServiceTest`, `FooServiceIT` |
| **usage** | Parses every test file with JavaParser and checks whether it references any changed class. Uses a two-tier approach: **(1)** direct import match — if the test has `import com.example.FooService;`, it's affected regardless of how it uses the class; **(2)** type-reference scan for wildcard imports (`import com.example.*`) and same-package usage (no import needed). Catches fields, method parameters, return types, constructor calls, generics, and casts. | `BarModel` changed → finds `BarValidatorTest` (imports it), `BazMapperTest` (same package, uses it as field type) |
| **impl** | When an interface or base class changes, scans all production source files to find classes that `extends` or `implements` the changed type (via AST) and classes following the `*Impl` or `Default*` naming convention. Then re-runs the naming and usage strategies on those implementations. | `FooService` (interface) changed → finds `FooServiceImpl` and `DefaultFooService` → finds `FooServiceImplTest` / `DefaultFooServiceTest` |
| **transitive** | Builds a reverse dependency map of all production classes: for each class, which other classes depend on it (via field types). When a class changes, walks this "used-by" graph N levels deep (configurable, default 4, max 5) to find consumers. Then runs naming + usage on those consumers. | `BazGateway` changed → `FooService` uses it (depth 1) → `OrdersController` uses `FooService` (depth 2) → finds both tests via naming |
| **headerEdges** *(issue [#132](https://github.com/vedanthvdev/affected-tests/issues/132))* | Closes the Spring DI Gap. Walks each changed class's header — anything before the opening `{` — and augments the changed-class set with the resolved targets BEFORE the four pre-existing strategies run. Six categories: `extends`, `implements`, `permits`, generic `type-bounds`, `record-components`, class-level `annotations`. Three safety layers: default ignore-globs mute JDK / Spring / JUnit / Mockito / Kotlin / Lombok noise; a depth bound (default 1, max 2) prevents transitive explosions; a sibling cap (default 5) suppresses the impl-walk explosion from over-connected types like `BaseController` (52 subclasses). Tune via `headerEdgesEnabled` / `headerEdgesExclude` / `headerEdgesDepth` / `headerEdgesMaxSiblings` / `headerEdgesIgnore` DSL knobs; the `--explain` trace gains a `Header edges (issue #132):` block listing every fired edge with its category label. | `StripeGateway implements PaymentGateway` changed → adds `PaymentGateway` to the changed-class set → naming finds `PaymentGatewayTest` (the interface contract test that previously was silently missed because no source file source-imports `StripeGateway`) |

### How scanning works

The plugin scans the project tree **recursively at any depth** to find source and test directories. It is completely project-structure agnostic — it does not assume any particular module layout. Whether your modules are flat (`api/src/test/java`), nested (`services/orders/src/test/java`), or deeply nested (`platform/services/orders/src/test/java`), all test files are discovered.

Directories like `.git`, `build`, `.gradle`, and `node_modules` are automatically skipped during the walk. `outOfScopeTestDirs` and `outOfScopeSourceDirs` are additionally filtered at index time so discovery never picks up tests living there.

### Persistent discovery cache

Every `affectedTest` run caches its index — directory structure, file lists, FQN→path map, and the per-file AST data the strategies actually consume — into `build/affected-tests/index/v1/snapshot.tsv`, so a subsequent run on the same tree skips the scan and AST work and only re-parses files whose `(mtime, size)` fingerprint changed. Disable with `-Daffected-tests.indexCache.enabled=false` or `rm -rf build/affected-tests/index` to force a rebuild.

### Source-set auto-discovery

When the consumer applies the Java plugin (or anything that derives from `java-base`), the plugin walks each subproject's `JavaPluginExtension` source sets at `gradle.projectsEvaluated` time and seeds `sourceDirs` / `testDirs` from the live source-set graph. A source set is classified as a **test source set** when a `Test`-typed Gradle task points at its `output.classesDirs` — the same convention `JvmTestSuitePlugin` uses. Everything else falls into `sourceDirs` (the production bucket), including helper / fixture source sets that are consumed by tests at compile time but have no Test task of their own.

The result: an adopter with `src/integrationTest/java` + a same-named `integrationTest` Gradle task picks up that directory automatically — no `affectedTests { testDirs = […] }` line required. Custom layouts (e.g. a `main` source set rooted at `src/cool/java`) are also picked up verbatim, since the suffix is computed by relativising every `srcDir` against its owning project.

If the auto-discovered list isn't what you want — for example, on a non-conventional monorepo where one module's `internal-test` source set should not influence selection — set `sourceDirs` / `testDirs` explicitly in the DSL. Gradle's Property semantics put an explicit `set()` ahead of any `convention()`, so the auto-discovered default never overrides a pinned value.

### Directly changed tests

Any test file that is itself modified in the diff is **always** included in the run, regardless of strategy results.

## Multi-Module Support

The plugin works out of the box with multi-module projects — it recursively scans all modules at any nesting depth, so no configuration is needed.

Internally, each discovered test FQN is traced back to the Gradle subproject that owns its file, and tests are then dispatched per module:

```
./gradlew :moduleA:test --tests com.example.FooTest \
          :moduleB:test --tests com.example.BarTest
```

This makes `--tests` filters scope cleanly to their owning module, instead of being applied globally and failing on any subproject that doesn't happen to contain the FQN. Cross-module imports (e.g. a test in `application` that imports a class from `api`) are still detected correctly via the `usage` and `impl` strategies.

### Coverage tools (JaCoCo, Sonar, Cobertura, …)

Coverage tools work transparently: `affectedTest` invokes each module's standard `test` task, so any agent or report wired to `test` — JaCoCo's agent, `jacocoTestReport`, the `org.sonarqube` plugin, `jacoco-report-aggregation`, etc. — attaches to the run unchanged, and coverage on changed lines reflects the tests the plugin selected (which is what new-code coverage gates require). Project-wide coverage drops on an MR pipeline are expected because untouched tests don't run; configure your gate against new code (Sonar's default) rather than overall coverage when running `affectedTest` instead of `test`.

## Behaviour reference

Every row below shows the situation the engine resolved, and the action applied with the default configuration (no `mode` set, no explicit `onXxx`).

| Diff contents | Resolved `Situation` | Default `Action` | Override knob |
|---|---|---|---|
| Only mapped production/test `.java` files | `DISCOVERY_SUCCESS` (or `DISCOVERY_EMPTY` if no tests map) | `SELECTED` | discovery tuning |
| Only files matching `ignorePaths` (docs, LICENSE, CHANGELOG, images, generated) | `ALL_FILES_IGNORED` | `SKIPPED` | `onAllFilesIgnored` or `mode=strict` |
| Only files under `outOfScopeTestDirs` / `outOfScopeSourceDirs` (e.g. api-test only) | `ALL_FILES_OUT_OF_SCOPE` | `SKIPPED` | `onAllFilesOutOfScope` |
| Any YAML / Gradle / Liquibase / `.java` outside configured dirs | `UNMAPPED_FILE` | `FULL_SUITE` (via `onUnmappedFile = "full_suite"`) | `onUnmappedFile = "selected"` |
| No changed files at all | `EMPTY_DIFF` | `SKIPPED` | `onEmptyDiff = "full_suite"` / `mode = strict` |
| Mapping succeeds but discovery returns zero tests | `DISCOVERY_EMPTY` | `SKIPPED` — or `FULL_SUITE` if `mode=ci`/`strict` | `onDiscoveryEmpty` / `mode` |
| At least one scanned `.java` file failed to parse (malformed / half-committed / encoding glitch) | `DISCOVERY_INCOMPLETE` (takes precedence over both `DISCOVERY_EMPTY` and `DISCOVERY_SUCCESS`) | `SELECTED` — or `FULL_SUITE` if `mode=ci`/`strict` | `onDiscoveryIncomplete` / `mode` |
| Mixed diff: Java + unmapped file | `UNMAPPED_FILE` (takes precedence) | `FULL_SUITE` | `onUnmappedFile` — set to `"selected"` to fall through to discovery |
| `baseRef` not resolvable | `FAILED` | Hard error (prevents silent test skipping in CI) | — |
| Not a git work tree / JGit I/O error | `FAILED` | Hard error | — |

The `onUnmappedFile = "full_suite"` default follows the "run more, never run less" principle: a change to `application.yml` can alter production behaviour just as surely as a change to a `.java` file, so the plugin cannot safely pick a subset from an empty Java mapping.

### Migrating from v1 config

**v2.0.0 removed the three v1 legacy knobs.** If any of these still appear in your `build.gradle`, Gradle configuration will fail before the `affectedTest` task runs:

- `runAllIfNoMatches`
- `runAllOnNonJavaChange`
- `excludePaths`

Since **v2.1.0**, the error is targeted: instead of Gradle's generic "unknown property" message, you'll see a migration hint naming the exact v2 replacement knob. For example, `runAllIfNoMatches = false` now fails with:

```
> affectedTests.runAllIfNoMatches was removed in v2.0.0. Use
  onEmptyDiff = "full_suite" and/or onDiscoveryEmpty = "full_suite"
  instead (or set mode = "ci" / "strict" to get those defaults). See
  CHANGELOG.md v2.0 for the full migration table.
```

Shims exist for all three legacy knobs; Kotlin DSL callers already get a compile error naming the removed property.

#### Deprecation timeline

| Release | What happens |
|---|---|
| **v1.9.x and earlier** | Legacy knobs worked silently. No warnings. |
| **v1.10.x – v1.11.x** | Legacy knobs still worked. A per-run `WARNING: [affected-tests] '<knob>' is deprecated…` named each one and its replacement. Zero-config users saw nothing. |
| **v2.0.0** (this release) | **Legacy knobs removed.** `excludePaths`, `runAllIfNoMatches`, `runAllOnNonJavaChange` are unknown properties — Gradle configuration fails. Migrate using the table below. |

#### Before / after

| v1 config | v2 equivalent | Why |
|---|---|---|
| `runAllIfNoMatches = true` | `onEmptyDiff = "full_suite"` **and** `onDiscoveryEmpty = "full_suite"` | The v1 flag conflated two different situations ("git diff is empty" vs "discovery found nothing"). v2 splits them so you can e.g. skip empty-diff runs but still fall back to full suite when discovery fails. |
| `runAllIfNoMatches = false` (explicit) | **Set `mode = "local"`, or pin `onEmptyDiff = "skipped"` + `onDiscoveryEmpty = "skipped"` explicitly.** Do *not* just delete the line — in v2 the zero-config `mode = "auto"` resolves to `ci` in a CI runner, and `ci` escalates `DISCOVERY_EMPTY` to `FULL_SUITE`. A v1 pipeline that set `runAllIfNoMatches = false` specifically to prevent the discovery-empty branch from flipping to full suite will start running the full suite on every no-match MR unless one of these two knobs is pinned. |
| `runAllOnNonJavaChange = true` | `onUnmappedFile = "full_suite"` | Single-situation knob, same semantics. |
| `runAllOnNonJavaChange = false` | `onUnmappedFile = "selected"` | Plugin treats the unmapped file as if absent and continues to discovery. |
| `excludePaths = ["**/generated/**"]` | `ignorePaths = ["**/generated/**"]` | Identical semantics — just a rename. |
| `excludePaths = []` (explicit empty) | **Delete the line** | v2's default `ignorePaths` list is broader (markdown, licence, changelog, images, generated). Explicitly empty discards all of it. |

#### Worked example

**Before (v1):**

```groovy
affectedTests {
    baseRef = "origin/master"
    runAllIfNoMatches = true
    runAllOnNonJavaChange = true
    excludePaths = ["**/generated/**"]
    transitiveDepth = 4
}
```

**After (v2):**

```groovy
affectedTests {
    baseRef = "origin/master"
    mode = "ci"                                // one line replaces both booleans in 95% of cases
    // excludePaths / ignorePaths unset — v2 default already covers generated/
    // transitiveDepth = 4 now the default, no need to set it
}
```

Or if you want every situation explicit (recommended for production pipelines where you care about each edge case):

```groovy
affectedTests {
    baseRef = "origin/master"
    onEmptyDiff          = "full_suite"
    onAllFilesIgnored    = "skipped"
    onAllFilesOutOfScope = "skipped"
    onUnmappedFile       = "full_suite"
    onDiscoveryEmpty     = "full_suite"
}
```

#### Decision tree — "which replacement do I need?"

```
Did you set runAllIfNoMatches?
├─ No                    → nothing to do for this knob
├─ runAllIfNoMatches = true
│  └─ set onEmptyDiff = "full_suite" AND onDiscoveryEmpty = "full_suite"
│     (or just set mode = "ci" / mode = "strict" — both imply it)
└─ runAllIfNoMatches = false
   └─ DO NOT just delete the line. In v2, zero-config AUTO-in-CI
      escalates DISCOVERY_EMPTY to FULL_SUITE. Either:
      · pin mode = "local", OR
      · pin onEmptyDiff = "skipped" + onDiscoveryEmpty = "skipped"

Did you set runAllOnNonJavaChange?
├─ No                           → nothing to do
├─ runAllOnNonJavaChange = true → set onUnmappedFile = "full_suite"
│                                  (or just delete the line — it's the default)
└─ runAllOnNonJavaChange = false → set onUnmappedFile = "selected"

Did you set excludePaths?
├─ No                → nothing to do
├─ excludePaths = [] → delete the line (you probably want the broader v2 default)
└─ excludePaths = [...] → rename to ignorePaths with the same list
```

#### What the summary log tells you after migration

Every `affectedTest` run prints the outcome + situation + the v2 knob that fired on one line, so CI dashboards can key off a stable vocabulary:

```
Affected Tests: FULL_SUITE (UNMAPPED_FILE) — 1 changed file(s); running full suite (onUnmappedFile=FULL_SUITE — non-Java or unmapped file in diff).
```

**Breaking-change note for grep-based alerting:** v1 summary lines carried both the v1 name (`runAllOnNonJavaChange=true`) and the v2 name (`onUnmappedFile=FULL_SUITE`) to ease migration. v2.0 drops the v1 vocabulary. Any CI alert rules keyed on `runAllIfNoMatches=true`, `runAllOnNonJavaChange=true`, or the `[affected-tests] '…' is deprecated` warning must be updated to the v2 knob names.

## Project Structure

```
affected-tests/
├── affected-tests-core/          # Git integration, change detection, test discovery
├── affected-tests-gradle/        # Gradle plugin (io.github.vedanthvdev.affectedtests)
├── docs/
│   ├── DESIGN-v2.md              # v2 design document (situation/action/mode model)
│   ├── architecture.mmd          # Mermaid source for the architecture diagram
│   └── architecture.svg          # Rendered diagram embedded in README
├── build.gradle
├── settings.gradle
└── README.md
```

## Requirements

| Component | Version |
|-----------|---------|
| Gradle | 8.x+, primary support on 9.x |
| Java | 21+ |
| JUnit | 5.x |
| Spring Boot | Compatible (no Boot-specific code) |

## Dependencies

- **JGit** — Git change detection (no native git binary required)
- **JavaParser** — Source-level test discovery (usage + implementation strategies)
- **SLF4J** — Logging

## Changelog

See [`CHANGELOG.md`](CHANGELOG.md) for the release history. GitHub's auto-generated release notes (one entry per tag, backed by the merged PR titles) are on the [Releases page](https://github.com/vedanthvdev/affected-tests/releases).

## Versioning

Versions are managed automatically via [axion-release](https://github.com/allegro/axion-release-plugin) — derived from git tags, never hardcoded in source. The release workflow (`.github/workflows/release.yml`) runs on every push to `master` and cuts a **patch** release by default. For minor or major bumps, trigger the workflow manually with a `version` input.

| Goal | How |
|------|-----|
| Check what version this branch is | `./gradlew currentVersion` |
| Auto patch release (e.g. `1.9.12` → `1.9.13`) | Merge to `master` — the release workflow does the rest |
| Force a minor or major release (e.g. `1.9.x` → `1.10.0`) | GitHub → Actions → **Release** → *Run workflow* → fill `version` (e.g. `1.10.0`), or run `gh workflow run release.yml --ref master -f version=1.10.0` |
| Pin a minor/major on a **merge-to-master** release (no dispatch) | Commit a `.release-version` file at repo root containing the SemVer (e.g. `2.1.0`) in the same PR whose merge should ship that version. The release workflow tags that version, publishes it, then auto-deletes the file in a `[skip ci]` follow-up commit so the next push falls back to auto-patch-increment. |
| Release-candidate / pre-release | Trigger *Run workflow* with `version: 1.10.0-RC1`, or locally: `./gradlew release -Prelease.versionIncrementer=incrementPrerelease` |
| Manually re-run a failed publish | Re-trigger the workflow on the already-tagged commit — portal-check + release-check steps are idempotent |

Note: `./gradlew markNextVersion -Prelease.version=X.Y.0` influences only what `currentVersion` reports on that branch. It does **not** change what `./gradlew release` picks, because the release task still follows the default patch incrementer unless `-Prelease.version=X.Y.Z` is passed at release time. Use the workflow dispatch above for minor/major bumps instead.

## License

Apache 2.0
