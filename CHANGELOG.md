# Changelog

All notable changes to this plugin are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed

- Updated `CONTRIBUTING.md` source-language guidance to remove stale
  routing to closed #47 and point contributors to the current Kotlin
  (#102, #103, #117), Groovy (#96, PRs #97/#98/#99/#100), and Scala
  (#111) tracking threads (issue #122).

### Added — `headerEdges` discovery strategy (issue #132)

Adds a fifth, default-on discovery strategy that closes the Spring DI
Gap: a change to a concrete `class StripeGateway implements
PaymentGateway` now selects `PaymentGatewayTest` (the interface
contract test) even when no source file source-imports `StripeGateway`
directly. Pre-issue-#132 the four existing strategies (naming /
usage / impl / transitive) walked only source-level type references
in test bodies and downward-subtype edges from changed classes —
none of them walked from a changed concrete class UP to its
supertypes or OUT to its header-declared types, so DI-only edges
were silently invisible.

The strategy treats anything that appears in a class declaration
before the opening `{` as part of the class's identity, splits it
across six categories (`extends`, `implements`, `permits`,
`type-bounds`, `record-components`, class-level `annotations`),
resolves the simple names to project FQNs via a four-tier walk
(non-wildcard imports → same-package lookup → wildcard imports →
project-wide unambiguous match), then adds the resolved FQNs to the
changed-class set BEFORE the four pre-existing strategies run.

Three layered safety contracts keep selection bounded: a default
ignore-glob list mutes JDK / Spring / JUnit / Mockito / Kotlin /
Lombok stdlib noise; a depth bound (default 1, clamped to 2)
prevents exponential walks; a sibling cap (default 5) suppresses the
downward impl-walk from header-edge-added types with more than N
direct subtypes — the surgical fix for the `BaseController` (52
subclasses) explosion case.

Five new DSL knobs expose the strategy (`headerEdgesEnabled`,
`headerEdgesExclude`, `headerEdgesDepth`, `headerEdgesMaxSiblings`,
`headerEdgesIgnore`). `--explain` text and JSON both gain a
`headerEdges` block listing the augmented count, per-edge entries
with category labels, and sibling-cap suppressions. The JSON schema
version bumps v2 → v3 (additive; v2 fields unchanged).
`ProjectIndexCache.SCHEMA_VERSION` bumps 4 → 5 to invalidate warm
caches whose `FileMetadataRow` is missing the new HeaderEdges
column, and every new knob feeds into `configHash` so a knob flip
across consecutive runs forces a clean rescan.

### Added — Phase 3 Groovy AST tech plan (issue #96 / #47)

`docs/PHASE-3-GROOVY-AST.md` lays out the plan for making Groovy a
first-class source language in the discovery engine, building on the
Phase 2 architecture: widen `SourceExtensions.EXTENSIONS` for `.groovy` /
`.gvy`, extend `SourceSetAutoDiscovery` to walk Groovy sourcesets via
`ss.getExtensions().findByType(GroovySourceDirectorySet.class)` so
Spock specs under `src/test/groovy` enter the test universe without
manual DSL config, ship `GroovyLanguageParser` against Apache Groovy
5.0.6's `CompilationUnit.compile(Phases.CONVERSION)` (committed over
`AstBuilder` on operational lifecycle grounds — shared
`GroovyClassLoader` parity with Phase 2's Kotlin parser shape — with
a small `GroovySupertypeResolver` helper for simple-name supertype
resolution at the `CONVERSION` phase), shade `org.apache.groovy.*` +
`org.codehaus.groovy.*` + bundled antlr4 / asm / picocli copies under
`io.affectedtests.shadow.{apache,codehaus,}groovy.*`, and add `Spec`
/ `Specification` to the default test-suffix list so Spock specs are
picked up by the naming strategy out of the box. Diagnostics ship as
a parallel `GroovyDiagnostics` carrier (sibling of `KotlinDiagnostics`,
no rename of the existing carrier so Phase 2's public API stays
intact); `LanguageParser` gains two default-method hooks
(`recordDiagnostics`, `replayWarmCacheDiagnostics`) so `ProjectIndex`
no longer needs `instanceof` branches per parser class. Three-PR
rollout (the `LanguageParser` interface already exists from Phase 2)
with explicit maintainer-driven decision pauses — Phase 3 may stop
after PR #1 if the maintainer's internal Spock fixture audit shows
approach A (filename-only + Spock suffixes + sourceset auto-discovery)
closes enough of the gap. Plan-only — no engine code changes in this
entry.

### Changed — Kotlin AST default-on, system property removed (issue #76, PR #4 of Phase 2)

Closes the Phase 2 rollout. `kotlinEnabled` flips to **default `true`**:
every adopter on the next minor sees `.kt` files participate in
AST-driven discovery (UsageStrategy direct-import, ImplementationStrategy
supertype walk, TransitiveStrategy reference graph) on every run with
zero opt-in. The pre-PR-4 system-property gate
(`-Daffected-tests.kotlin.enabled`) is **removed** along with its
property-parser tests — the DSL flag
(`affectedTests { kotlinEnabled = false }`) is the only remaining knob,
documented in the README "Known limitations" section as the escape hatch
for adopters who hit an embeddable bootstrap collision on a multi-module
parallel Gradle build.

PR #4 also closes the `--explain` plumbing deferred from PR #3. A new
`KotlinDiagnostics` carrier aggregates per-engine Kotlin metrics
(AST-mapped FQN samples, parse-failure count, path/package mismatch
samples, embeddable load-failure cause) and threads through
`LanguageParsers` → `KotlinLanguageParser` (records on every successful
extract + every bootstrap failure) → `ProjectIndex` (records mismatches
in `fileMetadata`'s non-Java branch + parse failures on `parseOrWarn`
returning `null`) → `AffectedTestsResult` (new `kotlinDiagnostics` field
with backward-compat constructor overloads defaulting to
`KotlinDiagnostics.EMPTY` so every existing test fixture and downstream
caller compiles unchanged) → `AffectedTestTask.renderExplainTrace`. Four
new pinned `--explain` strings emit:

- `Kotlin source AST-mapped to FQN <fqn>.` — fires per AST-mapped
  sample (capped at the existing `EXPLAIN_SAMPLE_LIMIT`, with an
  `… and N more Kotlin source(s) AST-mapped this run.` summary when
  the cap is hit).
- `Kotlin file failed to parse with embeddable 2.1.20; counted into
  DISCOVERY_INCOMPLETE.` — fires once per run when any `.kt` file
  failed to parse, naming the embeddable version operators are
  pinned against and surfacing the `DISCOVERY_INCOMPLETE` escalation
  link explicitly.
- `Kotlin file <path> declares package <X> but path-derives to <Y>;
  AST-driven strategies use the declared package, naming strategy
  uses the path-derived FQN.` — fires per mismatch sample (same cap)
  with the same `… and N more` summary, surfacing the over-selection
  shape PR #1's hint described abstractly.
- `Kotlin embeddable failed to load: <cause>. Treating .kt files as
  unparseable for this run.` — fires once per run on bootstrap
  failure, naming the underlying cause (`LogSanitizer`-stripped) so
  operators can correlate against the matching plugin-status WARN.

The polyglot `--explain` hint demoted: pre-PR-4 the unmapped-bucket
hint named the residual languages and linked to issue #47; post-PR-4
it is one line per distinct unmapped extension —
`Non-Java/Kotlin source (<ext>) mapped via filename only; AST-driven
strategies skipped (separate issue).` — and the issue-47 link drops
because Kotlin is no longer one of the affected languages. The PR #1
"Kotlin source mapped via filename only; AST-driven strategies
skipped (issue #76)." line survives only on the
`kotlinEnabled = false` fallback path so the escape hatch keeps a
diagnostic signal.

PR #4 also adds:

- A new Cucumber feature (`10-issue-76-kotlin-ast.feature`, 7
  scenarios) covering Naming / Usage / Implementation / Transitive
  strategies on Kotlin fixtures plus the parse-failure and
  path/package-mismatch diagnostic shapes — pins the four `--explain`
  strings byte-stable end-to-end, not just in unit tests.
- An update to the existing `09-issue-76-kotlin-path-derived-mapping.feature`
  flipping K01/K02/K04 from the PR #1 "filename only" hint to the
  PR #4 AST-mapped FQN hint, with K03's negative guards extended so
  the demoted polyglot wording cannot silently regress back.
- Updates to `AffectedTestTaskExplainFormatTest`'s polyglot-hint
  fixtures matching the new shape, with `assertFalse` guards
  against the pre-PR-4 wording (`currently maps only .java`,
  `issues/47`) preserved as regression nets.
- Documentation refresh: `AffectedTestsConfig`'s Javadoc, the
  Gradle extension Javadoc, README "Known limitations", and the
  Phase 2 design doc all describe the post-PR-4 state without
  framing the system property as if it still existed.

### Added — Kotlin AST parser (issue #76, PR #3 of Phase 2)

Wires `kotlin-compiler-embeddable:2.1.20` into the discovery engine
through a new `KotlinLanguageParser` registered behind the
`affected-tests.kotlin.enabled` system property. The flag defaults
to **off** for the rollout window — every `.kt` file still flows
through the path-derived FQN routing PR #1 introduced, and existing
adopters see zero behavioural change. Flipping the flag to `true`
routes `.kt` parsing through the embeddable's PSI walk, producing
the same `FileMetadata` shape (`packageName`, `primaryTypeName`,
imports, type-reference simple + dotted names, type declarations
with supertype names) the Java path produces. PR #4 will flip the
default.

PR #3 ships:

- A package-private `KotlinLanguageParser` in
  `io.affectedtests.core.discovery` that owns the embeddable's
  `KotlinCoreEnvironment` lifecycle (lazy bootstrap on first
  parse, single shared environment per `ProjectIndex`, deterministic
  disposal via `AutoCloseable`). Bootstrap failure routes to a
  single `WARN` line + the fail-closed posture the plan specifies:
  every `.kt` parse in the run returns `null`, the parse-failure
  counter bumps once per file, and `Situation.DISCOVERY_INCOMPLETE`
  decides whether the run escalates. Silent degradation to "Kotlin
  file, naming-strategy only" is forbidden — that's the silent-skip
  class of bug Phase 1 was written to avoid.
- A real PSI walk producing `FileMetadata` at parity with the
  Java extractor. Class-less `.kt` files surface the JVM-facade
  synthetic name (`<basename>Kt`) the Kotlin compiler emits at
  bytecode level; named companions surface as their own
  `TypeDecl`; anonymous companions (`companion object {}`) are
  filtered out so the over-generic `Companion` simple name does
  not over-match. Nullable wrappers (`Foo?`) and qualified user
  types (`com.example.Bar`) both contribute to the
  simple-name and dotted-name harvest. `@file:JvmName(...)` is
  detected and surfaced for `--explain`, but **not honoured** in
  Phase 2 — a follow-up issue tracks honouring the override.
- A `LanguageParsers` registry refactored from a static utility
  to a per-engine instance owning the lifecycle of every
  registered parser. Strategies' no-index fallback path uses
  `LanguageParsers.defaultJavaOnly()` (the rollout-default shape);
  the engine's index-aware path uses
  `LanguageParsers.forConfig(config)` which conditionally
  registers the Kotlin parser based on `kotlinEnabled`.
  `ProjectIndex` implements `AutoCloseable` and
  `AffectedTestsEngine.discover` wraps `ProjectIndex.build` in a
  try-with-resources so the embeddable's MockApplication +
  extension-point registry get disposed at engine shutdown even
  on the abnormal-exit path.
- A schema bump on `ProjectIndexCache.SCHEMA_VERSION` (3 → 4)
  plus the `kotlinEnabled` flag mixed into `configHash`. Both
  invalidate any pre-PR-3 warm cache automatically: a flag flip
  produces a fresh `configHash`, and a cross-version upgrade
  rejects the pre-PR-3 snapshot at the schema check before the
  load path even attempts to parse the body.
- Shading config in `affected-tests-gradle/build.gradle` that
  bundles the embeddable + its pre-relocated IntelliJ Platform /
  ASM / fastutil / jline / vavr payload under
  `io.affectedtests.shadow.*`. The published shadow JAR measures
  **55.7 MB** (well under Plan §5's 60 MB hard ceiling, inside
  the 40-50 MB realistic target window). Adopters with their own
  Kotlin compiler / ASM / coroutines on the build classpath are
  unaffected — every relocated namespace is verified by a
  ZIP-walk functional test against an exhaustive forbidden-root
  list.
- Three new functional tests:
  `ShadowRelocationFunctionalTest` (structural ZIP-walk gate over
  the published JAR — namespace shape, service-file rewrite,
  size ceiling); `ShadowParseGateFunctionalTest` (runtime gate
  loading the shaded JAR in an isolated child-first classloader
  and reflectively bootstrapping `KotlinCoreEnvironment` against
  a representative fixture); plus 16 `KotlinLanguageParserTest`
  unit cases pinning the PSI walk's contract (synthetic facade
  naming, nullable handling, anonymous-companion filter, nested
  type recursion, supertype harvest, FQN inline references,
  `@file:JvmName` flag, post-close fail-closed, idempotent
  disposal).

#### Post-review hardening

A multi-persona code review of the original PR #3 surfaced a
cluster of correctness, reliability, and adversarial findings that
all landed in this commit before merge:

- **Per-file `Throwable` catch.** `parseOrWarn`'s exception handler
  widened from `Exception` to `Throwable` so `StackOverflowError`
  (deeply nested generics), `OutOfMemoryError` (multi-GB single-line
  source), and `AssertionError` (embeddable PSI invariant violations)
  fail-closed at the file level instead of crashing the engine —
  matches the bootstrap layer's posture and removes the asymmetry an
  adversarial review flagged as exploitable.
- **PsiErrorElement gate.** `KtPsiFactory.createFile` returns a
  walkable `KtFile` even for malformed input — syntax errors surface
  as `PsiErrorElement` nodes, not exceptions. The extractor now
  walks for any `PsiErrorElement` and treats its presence as a parse
  failure, mirroring Java's `ParseResult.isSuccessful()` gate. Without
  this gate an in-flight broken `.kt` edit would silently produce
  partial `FileMetadata` and under-select tests — exactly the
  silent-drop class of bug Phase 2 §3.4 was written to forbid.
- **Close-vs-bootstrap race.** `close()` now acquires
  `bootstrapLock` for the field-read window so a concurrent
  in-flight bootstrap cannot publish a live environment after
  disposal returns, eliminating a ~25 MB MockApplication leak per
  occurrence on a long-running Gradle daemon. `ensureEnvironment`
  re-checks both `bootstrapFailed` and `closed` inside the
  synchronized block so threads queued on `bootstrapLock` exit
  cleanly instead of re-attempting a known-failed bootstrap.
- **Half-constructed Disposable.** Bootstrap failure now disposes
  the `Disposable parent` token before propagating null — without
  this, `KotlinCoreEnvironment.createForProduction` registering
  child disposables before throwing left a stranded entry in
  IntelliJ's static `Disposer` registry.
- **`companion object Companion {}` filter.** Code generators (KSP,
  Mockito-Kotlin, Hilt) emit explicitly-named `Companion`
  companions; the original anonymous-only filter let these slip
  through as `TypeDecl{simpleName="Companion"}`, over-matching every
  Kotlin file with a companion. The filter now uses both signals
  (anonymous OR explicit-name-equals-`Companion`).
- **`derivePrimaryTypeName` prefers class over object.** A file
  shaped like `object Singleton; class TheClass` now surfaces
  `TheClass` as the primary type — aligning the parser-side FQN
  with the diff-side `PathToClassMapper`'s file-stem-derived FQN
  for the same file. Falls back to the first object only when the
  file declares no top-level class.
- **Trailing-dot bail in `qualifiedName`.** The qualifier-chain walk
  now bails before appending a joining `.` when the leaf
  `referencedName` is null/empty (a shape malformed PSI can
  produce), preventing trailing-dot dotted names from passing the
  `indexOf('.') >= 0` filter and creating false-positive
  `startsWith` matches in `UsageStrategy`'s tier-3.
- **`Map.copyOf` order preservation.** The registry's defensive copy
  now uses `Collections.unmodifiableMap(new LinkedHashMap<>(...))`
  to actually keep registration order — the previous
  `Map.copyOf(LinkedHashMap)` produced a hash-based `MapN` whose
  iteration order contradicted the surrounding comment.
- **Schema-too-new rejection test + early-out.** Added
  `postUpgradeFutureSchemaSnapshotInvalidatesAndForcesFullRescan`
  to pin the symmetric direction of `tryLoad`'s `!=` schema check
  (a v=5 future-schema snapshot from a CI matrix downgrade must
  also be rejected). `Snapshot.read` now short-circuits on a
  non-current `v\t…` line so `tryLoad` surfaces the canonical
  "schemaVersion mismatch" log instead of a "failed to read"
  diagnostic on a future row-shape change. Schema bump rationale
  in the Javadoc was rewritten to be honest: v=4 is a forward-looking
  marker bump, not a wire-shape change.
- **Service-file body-leak prefixes extended.**
  `ShadowRelocationFunctionalTest`'s body-leak check now mirrors
  the relocator input set 1:1 — `_COROUTINE.`, `gnu.trove.`,
  `org.jetbrains.org.`, `org.jetbrains.concurrency.`,
  `org.jetbrains.annotations.`, `org.intellij.lang.`,
  `com.intellij.`, plus the broader `kotlinx.` were missing.
- **`parseKotlinEnabledProperty` test parity.** Mirrored the
  `parseParallelDiscoveryProperty` alias-set + typo-rejection
  tests, with the polarity flipped: typos like
  `affected-tests.kotlin.enabled=tru` fall back to `false` (the
  safe rollout-window default), not `true`.
- **Documented MockApplication invariant.** The Javadoc on
  `KotlinLanguageParser` and the README's known-limitations section
  now document that `KotlinCoreEnvironment` registers a process-wide
  static MockApplication on first use; multi-module Gradle builds
  with `org.gradle.parallel=true` and the rollout flag flipped on
  collide on that slot. PR #4's flag-flip carries the
  multi-module-parallel acceptance gate.

The shadow JAR measures **~58.4 MB** (under Plan §5's 60 MB hard
ceiling); the original 55.7 MB figure cited above grew with the
kotlin-compiler-embeddable point bumps that landed in flight.

### Changed — `LanguageParser` dispatch (issue #76, PR #2 of Phase 2)

Refactor only — zero adopter-visible behaviour change for Java diffs.
Pre-PR-2 the discovery pipeline hard-coded `JavaParser` everywhere a
source file needed parsing: `ProjectIndex.compilationUnit` carried a
`static ThreadLocal<JavaParser>`, the three index-aware strategies
(`UsageStrategy`, `ImplementationStrategy`, `TransitiveStrategy`)
each took a `JavaParser fallbackParser` argument on their no-index
fallback path, and a `JavaParsers` static utility owned the
language-level constant. PR #1 widened the scanner to admit `.kt`
files but every parser-side site still assumed Java; the temporary
short-circuit in `ProjectIndex.compilationUnit` was the only thing
preventing a `DISCOVERY_INCOMPLETE` cascade on every Kotlin diff,
flagged with a grep-able PR #1 marker.

PR #2 introduces a small `LanguageParser` interface
(`io.affectedtests.core.discovery.LanguageParser`) with a single
operation — `parseOrWarn(Path, String) -> FileMetadata` — and a
package-private `LanguageParsers` registry mapping file extensions
to their parser implementations. `JavaParsers` collapses into a
`JavaLanguageParser` that owns the per-thread `JavaParser` instance
and exposes the canonical language-level constant. Both
`ProjectIndex.compilationUnit` and `ProjectIndex.fileMetadata`
dispatch through the registry; the strategy fallbacks
(`UsageStrategy`, `ImplementationStrategy`, `TransitiveStrategy`)
also route through `LanguageParsers.parseOrWarn`. The PR #1
short-circuit and its grep marker are gone. The only test changes
are the two fixtures (`FileMetadataExtractorTest`,
`ProjectIndexCacheStage2Test`) that called `JavaParsers.newParser()`
directly — both moved to `JavaLanguageParser.newParser()` with no
behaviour change.

`ProjectIndex.fileMetadata` is the load-bearing addition that the
original draft deferred to PR #3. Adversarial review surfaced a
silent-drop trap in the deferred shape: once PR #3 registered a
`KotlinLanguageParser`, a malformed `.kt` file would route through
`compilationUnit(Path)`, hit the JavaParser-only `instanceof` gate,
return `null` without bumping `parseFailureCount`, and silently
drop out of discovery without escalating to
`DISCOVERY_INCOMPLETE` — the exact failure mode the counter exists
to surface. Pulling the `fileMetadata` split into PR #2 closes the
trap structurally: the `parseFailureCount` bump now lives in two
partitioned sites (Java parses bump inside `compilationUnit`,
non-Java parses bump inside `fileMetadata`'s non-Java branch),
both inside `ProjectIndex` so the parallel-discovery dedupe
guarantees from issue #42 still hold.

PR #3 of the rollout will register a `KotlinLanguageParser` for
`.kt` (gated on `-Daffected-tests.kotlin.enabled=true`) without
touching any call site this PR rewired — adding `.kt` to
`LanguageParsers.BY_EXTENSION` is the only registry edit needed,
and the `parseFailureCount` invariant for malformed Kotlin comes
along for free. PR #4 flips the flag default. The no-index
fallback paths previously used by unit tests now route through
the registry too — a `.kt` file fed to a strategy outside an
index context returns `null` (skip) cleanly instead of crashing
JavaParser.

### Added — Kotlin path-derived mapping (issue #76 / #47, PR #1 of Phase 2)

Kotlin (`.kt`) sources are now first-class participants in the
`affected-tests` discovery pipeline at the path-derived mapping
tier. Pre-PR-1 every `.kt` file in a diff fell through to the
`UNMAPPED_FILE` bucket, escalating to `FULL_SUITE` under the CI
default — the most common adopter complaint behind issue #47.
PR #1 widens five suffix-strip / extension-filter sites
(`SourceFileScanner.collectJavaFiles` inner walker,
`SourceFileScanner.walkFqnsUnder`, `SourceFileScanner.pathToFqn`,
`PathToClassMapper.mapChangedFiles` and `tryMapToClass`,
`UsageStrategy.extractFqn`) into the new
`io.affectedtests.core.discovery.SourceExtensions` helper so a
single point of truth governs the supported source-language set.

For adopters with mixed Java + Kotlin codebases:

- A `Foo.kt` with a sibling `FooTest.kt` now selects via
  `NamingConventionStrategy` exactly like its Java counterpart —
  no FULL_SUITE escalation on every Kotlin edit.
- Top-level Kotlin functions (`Util.kt` with no class declaration)
  emit a synthetic `<basename>Kt` FQN alongside the path-derived
  one, so a `UtilKtTest.kt` neighbour selects via
  `NamingConventionStrategy` (which appends `Test` / `IT` /
  `IntegrationTest` suffixes to changed production FQNs and
  probes against the test-FQN universe). PR #3 of the rollout
  will additionally light up `UsageStrategy` tier-1 import lookup
  so a Java test that imports the compiled `UtilKt` class also
  selects without a name-suffix neighbour. Test-side synthetic
  emission is deliberately suppressed to avoid "no tests found"
  runner errors.
- `--explain` adds two pinned hints: `Kotlin source mapped via
  filename only; AST-driven strategies skipped (issue #76).` and
  `Kotlin source unmapped (no matching source/test root); routed
  to unmapped bucket.` so adopters can grep CI logs for the
  rollout signal.
- Bucket labels in `--explain` drop the `.java` qualifier
  (`production` / `test` instead of `production .java` /
  `test .java`) so `.kt` rows don't need a parallel listing.

PR #1 ships path-derived mapping only — `FileMetadata` is not
populated for Kotlin yet, so `UsageStrategy` / `ImplementationStrategy`
/ `TransitiveStrategy` AST-driven tiers return zero matches for
`.kt` until PR #3 lands `kotlin-compiler-embeddable`. PR #4 makes
the full AST path default and demotes the PR #1 hints. See
`docs/PHASE-2-KOTLIN-AST.md` §2-§4 for the full rollout sequence.

`ProjectIndexCache.SCHEMA_VERSION` bumps from 2 → 3 to invalidate
pre-PR-1 caches on mixed Java + Kotlin projects — the `configHash`
hashes only the declared dir-list strings, not the file-extension
scope the scanner walks, so a warm cache from a v2.x build would
otherwise silently serve a Java-only test-FQN universe to the new
mapper.

### Added — Phase 2 Kotlin AST tech plan (issue #76 / #47)

`docs/PHASE-2-KOTLIN-AST.md` lays out the plan for making Kotlin a
first-class source language in the discovery engine — `LanguageParser`
interface, `kotlin-compiler-embeddable` dependency, shading rules under
`io.affectedtests.shadow.kotlin`, FQN-resolution rules, cache-schema
bump, and a four-PR rollout. Plan-only — no engine code changes
in this entry.

### Changed — Maven Central releases are now fully automatic (issue #87)

The release pipeline no longer stages Maven Central deployments
for a manual **Publish** click in the Sonatype portal.
`publishToMavenCentral(true)` in `affected-tests-gradle/build.gradle`
and `publishAndReleaseToMavenCentral` in `release.yml` upload,
validate, and release each cut automatically. v2.2.20 was the last
version published via the manual staging flow.

### Added — Maven Central publishing alongside Gradle Plugin Portal (issue #87)

The plugin now publishes to **Maven Central** in addition to the Gradle
Plugin Portal. Both channels ship identical JAR bytes from the same CI
release run; Maven Central artifacts are GPG-signed (a Sonatype hard
requirement) and the SLSA build provenance from issue #83 covers both.

For adopters: nothing changes by default — the existing
`plugins { id 'io.github.vedanthvdev.affectedtests' version 'x.y.z' }`
block continues to resolve from the Plugin Portal. Adopters who prefer
Maven Central as the source can add `mavenCentral()` to their
`pluginManagement.repositories` and the same coordinate resolves there.
Adopters who pin checksums via `./gradlew --write-verification-metadata
sha256` now have a second cryptographically-signed source to
cross-verify against.

For maintainers: the `Publish to Maven Central` step in
`release.yml` auto-releases each cut (see the **Changed** entry
above for when manual staging was retired). Full secret setup and
one-time GPG key generation steps are in
[`docs/RELEASING.md`](docs/RELEASING.md).

### Added — persistent per-file ProjectIndex cache, stage 2 (issue #41)

Stage 1 of issue #41 (PR #78) added a persistent
`build/affected-tests/index/v1/snapshot.tsv` so the path-derived
aggregates — source-file list, test-file list, FQN→path index — survive
between `affectedTest` invocations. That covered the directory-walk
phase. The follow-up Stage 2 lifts the AST phase out of the per-run cost
too.

The four discovery strategies (naming, usage, impl, transitive) used to
re-parse every test (and, on the impl/transitive side, every production
source) on every invocation, even when the file's bytes hadn't changed.
That cost scaled with the size of the suite, not with the diff. Stage 2
distils each `CompilationUnit` into a serialisable `FileMetadata` record
once — package name, primary type name, imports (with `static` /
`asterisk` flags preserved), simple- and dotted-name type references,
and per-type-declaration supertype simple names — and persists those
records into the same snapshot under a new `m` line type. The schema
version bumps to `2`; readers of the old `v1` format invalidate cleanly
and fall back to a full rebuild.

Invalidation is per-file, not per-snapshot: each `m` row carries the
source file's `(mtime, size)` fingerprint, so on the next run the cache
hands metadata back for every file whose fingerprint matches and lets
the lazy `FileMetadataExtractor` re-parse only the files that actually
changed. The directory-level `(mtime, child-count)` check from Stage 1
still gates the upstream snapshot, so a brand-new file appearing under
a tracked dir still forces a full rebuild — only edits to existing files
are eligible for partial reuse.

Foo-service warm-run discovery wall time on an unchanged tree, with the
Gradle daemon hot, sampled with `affectedTest --explain` (10 runs per
version, median):

```
2.2.13-alpha (no cache)         ~3.4s
2.2.14-alpha (Stage 1 only)     ~2.2s
2.2.16 with Stage 2             ~1.2s   (≈45% faster than Stage 1)
```

The kill switch is unchanged from Stage 1:
`-Daffected-tests.indexCache.enabled=false` short-circuits both load
and persist, and `rm -rf build/affected-tests/index` is the obvious
on-disk reset. Strategies fall through to live `JavaParser` extraction
on a row-level miss, so a corrupt or truncated `m` row degrades to a
single re-parse rather than a full-rebuild penalty.

### Added — engine-level parallel discovery (issue #42)

The four discovery strategies (naming, usage, impl, transitive) now
fan out across a small thread pool sized to
`min(strategies, availableProcessors)`. The pool is gated on
`availableProcessors() > 1`, so single-vCPU hosts (cgroups-pinned
containers, GitHub Actions free runners) automatically collapse back
to the in-line serial path where the pool would just add overhead. On
multi-vCPU hosts a synthetic workload sweep at 200/500/1000/3000/5000
classes shows 2.0–3.3× wall-time speedup on the discovery phase; the
`Foo-service` pilot project sees ~10% real-world speedup
(1700ms → 1520ms). The smaller real-world delta is expected — the
remaining contention is on a per-file `ConcurrentHashMap.computeIfAbsent`
in the parsed-CU cache, which the deeper intra-strategy parallelism
work (issue #42 "Option 1") can address in a follow-up if adopter
data justifies the engineering cost.

The kill switch is a single line in `build.gradle`:

```groovy
affectedTests {
    parallelDiscovery = false
}
```

…or `-PaffectedTestsParallelDiscovery=false` on the CLI for one-off
investigation. The CLI flag accepts the human-friendly aliases
`true|false|1|0|on|off|yes|no` (case-insensitive); typo'd values are
rejected with a build-log WARN rather than silently flipping the
switch via `Boolean.parseBoolean`'s permissive default.

### Added — `Discovery:` block in `--explain` output

The `--explain` trace and JSON now include per-run discovery diagnostics
so adopters can see directly which strategy is the wall-time hog and
whether parallel actually helped on their workload:

```
Discovery:       parallel (4 threads, 12ms total)
  naming     :    1ms (2 tests)
  usage      :    4ms (5 tests)
  impl       :    2ms (1 test)
  transitive :   11ms (3 tests, dominant)
```

The JSON `discovery` field bumps the explain-JSON schema from `v1` to
`v2` (non-breaking — every `v1` field is preserved). The new fields
are `parallelEnabled`, `concurrencyLevel`, `totalMillis`, and a
`perStrategy` array with `name`, `wallMillis`, and `testCount`.

### Added — `DiscoveryProfile` on `AffectedTestsResult`

A new `discoveryProfile()` method on the public `AffectedTestsResult`
record exposes the same parallel/serial + per-strategy + total-wall-time
data programmatically, so anyone embedding the engine outside Gradle
can read it without parsing `--explain` output. The pre-issue-#42
constructor signatures are preserved unchanged for source compatibility.

### Changed — thread-safety pass on `ProjectIndex` and `NamingConventionStrategy`

`ProjectIndex.cuCache` is now a `ConcurrentHashMap<Path, Optional<CompilationUnit>>`
with `computeIfAbsent` enforcing the "parse-once" contract under
contention. `parseFailureCount` is an `AtomicInteger` so the
issue-#41 de-dup invariant survives concurrent failure cases. The
JavaParser instance is held per-thread via a `static ThreadLocal`,
honouring JavaParser's documented per-instance mutability contract.
`NamingConventionStrategy.crossPackageMatches` is a
`ConcurrentHashMap` with `Collections.synchronizedSet(LinkedHashSet)`
values, and the cross-package iteration order is sorted at filter
time so the `--explain` text output stays deterministic across runs.

None of these changes are adopter-visible on green paths. They exist
purely to make the new fan-out safe; the serial path picks them up
for free.

## [v2.2.1] — code-review follow-ups after the v2.2 ship

v2.2.1 is a **non-breaking patch** that closes the Medium + Low
findings a structured code review raised against the v2.2
changeset. None of them were adopter-reported regressions — v2.2 is
green end-to-end on the `security-service` pilot — but each one
represents a real failure mode for a second adopter who doesn't
ship in exactly the same posture. Point-releasing them now keeps
the v2.3 tech-plan window open for the larger feature work
(non-conventional test task names, explain-trace truncation
unification) without delaying these fixes behind it.

If you're on v2.2.0 today you can bump to v2.2.1 without touching
`build.gradle`. The only observable change on a green path is that
enabling `org.gradle.configuration-cache` now works. The only
observable changes on the unhappy path are sharper error messages
and a new hint branch for the `onDiscoveryIncomplete = 'skipped'`
opt-in.

### Fixed — `org.gradle.configuration-cache` no longer crashes the plugin (M1)

The v2.2 dispatch-dependency Callable closed over a `Project`
reference to resolve each subproject's `testClasses` task. `Project`
is explicitly documented as **not** configuration-cache-serialisable,
so a repo with `org.gradle.configuration-cache=true` in
`gradle.properties` would fail to serialise the task graph with:

```
Task `:affectedTest` of type `...AffectedTestTask`: cannot serialize
object of type Project
```

The fix resolves each subproject's `testClasses` task once, eagerly,
into a `TaskProvider<?>` (which IS CC-serialisable) and closes the
Callable over that plus the task's own `Property<Boolean>` for the
`--explain` gate. Cache-hit rate and task-graph shape are identical
to v2.2 — only the serialisation surface changed. A new functional
scenario runs `./gradlew affectedTest --configuration-cache
--explain` on a SELECTED-shaped diff and pins "Configuration cache
entry …" in the output so the next edit that accidentally re-captures
`Project` breaks a test instead of only an adopter's CI.

Adopters who don't enable CC see no change.

### Fixed — `Action.SKIPPED` hint no longer calls the skip "safe" (M3)

v2.2's `DISCOVERY_INCOMPLETE` hint was a two-way branch: SELECTED
warned about the partial-selection risk; everything else printed "the
resolved action above is the safe fallback". That "everything else"
bucket silently absorbed `Action.SKIPPED` — which an operator can
opt into explicitly via `onDiscoveryIncomplete = 'skipped'`. Calling
SKIPPED "safe" is precisely inverted advice: the run executed zero
tests on a partial-parse diff.

v2.2.1 gives SKIPPED its own hint branch:

```
Hint:            one or more Java files in the diff failed to parse,
                 so discovery ran with missing inputs.
                 onDiscoveryIncomplete = 'skipped' meant no tests ran
                 for this diff — fix the parse error to restore
                 coverage, or set onDiscoveryIncomplete = 'full_suite'
                 if silently skipping a partial-parse diff is not the
                 intended policy.
```

The hint names the exact knob that was set (so a second reader of the
trace can locate the override) and names the escape (`'full_suite'`)
without ever calling the current state "safe". SELECTED and
FULL_SUITE wording are unchanged.

### Fixed — `-PaffectedTestsMode=` (empty) no longer crashes (L1)

CI templates that unconditionally emit `-PaffectedTestsMode=$MODE`
with an unset `$MODE` passed the literal empty string into the
extension's convention, and v2.2 rejected it with
`Unknown affectedTests.mode ''`. v2.2.1 filters empty/whitespace in
the convention chain so the empty case is identical to omitting the
flag — the AUTO default keeps working, and the "unknown mode" error
stays reserved for actual typos.

### Fixed — unknown-mode error names the AUTO fallback (L4)

v2.2's `parseMode` error listed the four legal values only, which
led adopters to ask "so which one is the default?". v2.2.1 adds the
AUTO-fallback hint plus the `CI=true` tripwire that AUTO keys off:

```
Unknown affectedTests.mode 'xyzzy'. Expected one of: auto, local,
ci, strict (omit the value or leave -PaffectedTestsMode unset to
keep the AUTO default, which picks CI when CI=true is exported).
```

### Changed — explain-trace module-block contract now fails loudly on misuse (L2)

`AffectedTestTask#appendModulesBlock` used to silently re-normalise
map keys that were missing a leading colon, papering over any caller
that forgot to route their module paths through
`AffectedTestTask#testTaskPath`. A future edit that fed raw
`"api:test"` strings into the map produced a split trace (`":api:test"`
next to `"core:test"`). The helper now asserts the contract up-front,
so the failure surface moves into the test suite.

The assertion is plain `assert`, active on every Gradle test JVM by
default and a no-op on production runs — the public operator-facing
trace output is unchanged.

_Changelog housekeeping: the v2.2.0 section below has been backfilled
with items that were in the v2.2.0 ship but were still sitting under
`[Unreleased]` in the CHANGELOG when the tag was cut._

## [v2.2.0] — adoption-feedback polish from the security-service pilot

v2.2.0 is a **non-breaking** release driven entirely by what a second
real-world adopter (Modulr's `security-service`, CAR-5190) ran into
while plugging v2.1 in. Every DSL knob, every default, and every
resolved behaviour from v2.1 keeps working bit-for-bit; v2.2 only
sharpens the edges an operator touches when something goes wrong
or when they want to A/B a mode from CI.

If you're on v2.1 today you can bump to v2.2 without touching
`build.gradle`. The only observable difference on a green path is a
faster `--explain` run (Bug A) and a per-module breakdown in that
trace (Polish E). The only observable difference on the unhappy path
is clearer hint wording (Bug B) and — in LOCAL mode specifically — a
loud `WARN` when discovery can't parse part of the diff (Risk C).

### Changed — internal refinements from the v2.2 code review

Follow-up polish on the v2.2 adoption-feedback changeset, all
non-breaking and none of them visible in build scripts.

* **Risk C WARN no longer fires with "0 test classes".** The engine
  rewrites `SELECTED` with nothing to dispatch into `skipped=true`;
  the WARN now short-circuits on `result.skipped() || FQNs.isEmpty()`
  so an operator never sees "LOCAL mode accepted a partial selection
  of 0 test classes" immediately followed by a skipped run.
* **Risk C WARN wording is de-duplicated against the engine log.**
  The message no longer restates "could not parse one or more Java
  files" (the engine's own WARN a few lines up already says so) and
  instead cross-references it — keeps the mode-specific postscript
  without echoing the parse-failure fact twice.
* **`DISCOVERY_INCOMPLETE` hint is now action-aware.** `Action.SELECTED`
  continues to say "selection is necessarily partial — set
  `onDiscoveryIncomplete = 'full_suite'` to escalate". On
  `Action.FULL_SUITE` (CI/STRICT default, or an explicit operator
  override) the hint drops the partial-selection wording entirely and
  only names the parse failure for next-run follow-up — avoids the
  circular "escalate to the action we already took" advice.
* **Shared `testTaskPath` helper.** Both the `--explain` Modules-block
  preview and the dispatch-path argv now route through
  `AffectedTestTask#testTaskPath`, so the two operator-facing strings
  can no longer drift. Root project is `":test"` with a leading colon
  on both sides.
* **Unit tests for the Risk C WARN gate.** The four-way gate (mode,
  situation, action, skipped/empty) is now pinned by six direct unit
  tests on the pure `shouldWarnLocalDiscoveryIncomplete` /
  `formatLocalDiscoveryIncompleteWarning` helpers, matching what the
  Javadoc already promised.
* **Two-module Bug A e2e scenario.** Pins that `--explain` prunes
  `compileJava` / `compileTestJava` on every subproject, not just the
  root — catches a regression where someone pins the Callable wiring
  to the root project instead of iterating via `allprojects { ... }`.

### Fixed — `--explain` no longer forces a full compile (Bug A)

Pre-v2.2 the `affectedTest` task eagerly depended on `testClasses` in
every subproject applying the `java` plugin. Great for the dispatch
path — the nested `./gradlew` needs class files to actually run tests
against — but pure overhead when the operator just wants to see the
decision trace. On a security-service-shaped repo this turned a
3-second diagnostic run into a multi-minute compile.

v2.2 wires the dependency through a Gradle `Callable` that re-evaluates
after command-line parsing. When `--explain` is set the Callable
returns an empty list and `testClasses` is pruned from the task graph
entirely; when `--explain` is absent the dependency behaves exactly
as before. The fix is transparent to build scripts and is pinned by
both a unit test on the Callable return shape and a Cucumber e2e
scenario that asserts `:compileJava` is absent from the executed-task
list of a real `--explain` run.

### Fixed — situation-specific Hint lines in the `--explain` trace (Bug B)

Pre-v2.2 the `--explain` trace printed a single "Hint:" line regardless
of the resolved situation, and that hint always named
`outOfScopeTestDirs` / `outOfScopeSourceDirs`. Correct for a subset of
DISCOVERY_SUCCESS runs, actively misleading everywhere else — a
DISCOVERY_EMPTY run with no OOS configured, and a DISCOVERY_INCOMPLETE
run where the real risk is a parse failure, both got the same OOS
advice that had nothing to do with the actual cause.

v2.2 splits that one line into three targeted branches:

- **DISCOVERY_EMPTY** now leads with "discovery mapped 0 test classes"
  and lists the three realistic causes — wrong `testSuffixes`,
  `testDirs` misconfigured, or no test coverage yet.
- **DISCOVERY_INCOMPLETE** now names the actual risk: the mapper
  couldn't parse one or more Java files in the diff, so the selection
  is definitionally partial. It also points at
  `onDiscoveryIncomplete = "full_suite"` as the escalation knob.
- **DISCOVERY_SUCCESS** with `outOfScopeTestDirs` /
  `outOfScopeSourceDirs` configured-but-unmatched keeps the v2.1 OOS
  advice — that was the one situation where the old hint was right.

### Added — loud WARN when LOCAL mode accepts a partial selection (Risk C)

LOCAL mode defaults `onDiscoveryIncomplete = SELECTED` on purpose —
developers iterating on WIP want fast feedback. The adoption risk:
when a Java parse failure drops files silently, the green "SELECTED"
summary overstates what actually ran, and there was no way to tell
from the non-`--explain` output that the selection was incomplete.

v2.2 emits a lifecycle-level `WARN` in this exact combination
(`mode = LOCAL` + `DISCOVERY_INCOMPLETE` + `Action = SELECTED`) before
the dispatch fires. The marker string
`affectedTest: LOCAL mode accepted a partial selection` is grep-friendly
and visible at Gradle's default log level — operators don't need to
re-run with `--info` to see the safety signal. CI and STRICT modes
already escalate to FULL_SUITE on DISCOVERY_INCOMPLETE by default, so
the warning is mode-gated and does not fire there.

### Added — `-PaffectedTestsMode` runtime override (Feature D)

v2.2 mirrors the existing `-PaffectedTestsBaseRef` pattern: set
`-PaffectedTestsMode=local|ci|strict|auto` on the command line to flip
the plugin's mode without editing `build.gradle`. Useful for adoption
experiments ("what would STRICT mode pick on today's HEAD?") and for
CI jobs that want to A/B two modes from the same pipeline.

DSL-declared `mode = '...'` still wins because Gradle Property
semantics apply explicit `set()` calls ahead of `convention()` — so
the `-P` is genuinely a fallback, not an override, and a repo pinning
its CI mode in `build.gradle` keeps that pin even if a stray `-P`
slips past review.

### Added — `:module:test` dispatch breakdown in `--explain` (Polish E)

The pre-v2.2 `--explain` trace named the total test-class count on a
SELECTED run but not the module distribution, so an operator asking
"which tasks will Gradle actually kick off?" still had to dry-run a
real dispatch to answer that. v2.2 threads the same grouping the
dispatch path uses into the trace:

```
Modules:         2 modules, 3 test classes to dispatch
  :application:test (2 test classes)
    com.example.FooTest
    com.example.BarTest
  :api:test (1 test class)
    com.example.BazTest
```

Non-SELECTED runs (EMPTY_DIFF, docs-only, etc.) suppress the block
entirely rather than print a noisy "Modules: 0 modules" line.

### Verified — regression coverage

- New unit tests in `AffectedTestsPluginTest` pin the `--explain`
  Callable's prune behaviour (present without `--explain`, absent with
  it) and the mode-precedence contract (DSL beats convention).
- New unit tests in `AffectedTestTaskExplainFormatTest` pin the three
  situation-specific hint variants and the empty-map / multi-module /
  root-project / preview-truncation shapes of the Modules block.
- A new Cucumber feature
  (`06-v2.2-adoption-feedback.feature`) pins every user-facing
  behaviour above as a full TestKit e2e, so a regression on any of
  the five fixes surfaces as a scenario failure rather than silent
  drift in operator experience.

## [v2.1.0] — DSL polish on top of the v2 breaking release

v2.1.0 is the **first publicly tagged v2 release**. It bundles everything
the v2.0 branch landed in master (the legacy-knob removal below) with two
small-but-sharp polish fixes that surfaced during a real-world pilot of
v2.0 against a Modulr micro-service. The polish fixes are strictly
additive on top of v2.0 behaviour — operators already on the v2 DSL see
no functional change, only earlier and clearer errors when something is
misconfigured.

### Changed — targeted error messages when v1 knobs appear in build.gradle

Before v2.1, a v1 user who dropped `runAllIfNoMatches = false` into their
`affectedTests { }` block got Gradle's default unknown-property error:

```
> Could not set unknown property 'runAllIfNoMatches' for extension
  'affectedTests' of type io.affectedtests.gradle.AffectedTestsExtension.
```

Correct, but unhelpful — it names the removed knob without naming the v2
replacement, forcing the operator to grep the CHANGELOG to find the fix.

v2.1 intercepts the assignment on the extension and swaps that generic
error for a targeted migration hint pointing at the exact `onXxx` knob
that took over each responsibility:

```
> affectedTests.runAllIfNoMatches was removed in v2.0.0. Use
  onEmptyDiff = "full_suite" and/or onDiscoveryEmpty = "full_suite"
  instead (or set mode = "ci" / "strict" to get those defaults). See
  CHANGELOG.md v2.0 for the full migration table.
```

Matching shims for `runAllOnNonJavaChange` and `excludePaths` point at
`onUnmappedFile` and at the `ignorePaths` vs `outOfScopeTestDirs`
distinction respectively. The v1 names are still rejected — v2 does not
re-introduce the knobs — but the rejection is now actionable.

Kotlin DSL callers already got a compile error naming the removed
property, so these shims only fire in Groovy DSL, which is where the
generic error lived.

Regression coverage:

- `AffectedTestsPluginTest.legacyKnobAssignmentThrowsWithV2MigrationHint_runAllIfNoMatches`
- `AffectedTestsPluginTest.legacyKnobAssignmentThrowsWithV2MigrationHint_runAllOnNonJavaChange`
- `AffectedTestsPluginTest.legacyKnobAssignmentThrowsWithV2MigrationHint_excludePaths`

Each pins both the v1 knob name *and* the v2 replacement name in the
error text, so a well-intentioned "tidy up the error message" refactor
that drops either half gets caught.

### Added — `.release-version` override file for merge-to-master minor/major releases

Until v2.1, the only way to ship a minor/major bump instead of an
auto-patch-increment was to run the release workflow via
`workflow_dispatch` with an explicit `version` input. That meant every
minor release required coordination between "merge the PR" and
"manually trigger the workflow" — and if the auto-release on the merge
push got there first, it would mint an unwanted patch tag the operator
then had to work around.

v2.1 teaches `.github/workflows/release.yml` a third version source: a
`.release-version` file committed at repo root. When the merge-to-master
release workflow finds the file, it reads the SemVer string, validates
it, and tags that exact version instead of auto-incrementing the patch.
After a successful publish the workflow deletes the file in a follow-up
commit tagged `[skip ci]` (so the cleanup push doesn't re-trigger the
release), which keeps ongoing patch releases on auto-increment.

Priority order on `./gradlew release`:

1. `workflow_dispatch` `version` input (manual dispatch — unchanged).
2. `.release-version` file at repo root (new in v2.1).
3. Auto-patch-increment (default — unchanged).

This release itself uses the file mechanism to ship as `v2.1.0` on
top of the auto-patch-increment line that would otherwise have cut
`v1.9.24`. See README §Versioning for the decision matrix.

### Changed — `gradlewTimeoutSeconds` range check moved to configuration time

The `gradlewTimeoutSeconds >= 0` check added in v1.9.22 lived on the
core config builder, which is only invoked at task-execution time. That
meant an invalid value like `gradlewTimeoutSeconds = -5` passed through
configuration silently and only blew up when someone actually ran
`./gradlew affectedTest`. IDE sync, `./gradlew help`, and
`./gradlew tasks` all ran green against a misconfigured build.

v2.1 adds a mirror check in `AffectedTestsPlugin#apply` via
`project.afterEvaluate`, so the same error now fires at configuration
completion. IDE sync and any dry Gradle invocation now surface the
misconfiguration immediately. The builder-side check stays in place as
belt-and-braces for programmatic callers that bypass the DSL extension.

Regression coverage:
`AffectedTestsPluginTest.negativeGradlewTimeoutFailsAtConfigurationTime`
walks the thrown exception chain end-to-end and pins the knob name, the
rejected value, and the `>= 0` range bound in the error message.

### Removed — v2.0 breaking release: legacy v1 knobs are gone

The three v1 configuration knobs that were deprecated across the v1.9.x
line have been removed from the plugin entirely. This is the long-promised
v2.0 breaking release (Phase 3 of `docs/DESIGN-v2.md`). The v2
situation/action DSL is now the only configuration surface, which keeps
`--explain` output honest, the priority-ladder resolver simple, and the
migration path explicit for operators who have been getting deprecation
warnings since v1.9.18.

- **Removed `runAllIfNoMatches` from the Gradle DSL and
  `AffectedTestsConfig`.** The v2 replacements
  (`onEmptyDiff`, `onDiscoveryEmpty`) have been available since v1.9.18
  and cover the same behaviour with independent per-situation control.
  Callers who set `runAllIfNoMatches = true` in v1 must now set
  `onEmptyDiff = "FULL_SUITE"` and/or `onDiscoveryEmpty = "FULL_SUITE"`
  depending on whether they want the safety net on empty diffs,
  post-discovery misses, or both.
- **Removed `runAllOnNonJavaChange` from the Gradle DSL and
  `AffectedTestsConfig`.** The v2 replacement is `onUnmappedFile`, which
  has been available since v1.9.18. `runAllOnNonJavaChange = true`
  maps to `onUnmappedFile = "FULL_SUITE"`; the explicit opt-out case
  (`runAllOnNonJavaChange = false`) maps to `onUnmappedFile = "SELECTED"`.
- **Removed `excludePaths` — use `ignorePaths` instead.** `excludePaths`
  was the pre-v1.9.18 name and has been an alias of `ignorePaths` since
  v1.9.18. In v2 it is gone; callers must rename to `ignorePaths`.
- **Removed `ActionSource.LEGACY_BOOLEAN` and
  `ActionSource.HARDCODED_DEFAULT`.** The priority ladder the resolver
  walks is now a two-tier structure: explicit `onXxx` > mode default.
  Zero-config installs always resolve to a concrete mode (`LOCAL` / `CI`
  via `Mode.AUTO` detection, or `STRICT` when pinned) so there is no
  longer a hardcoded-default fall-through to report. `--explain` output
  drops the corresponding `(source: legacy boolean …)` and
  `(source: pre-v2 hardcoded default)` phrases — anything still printing
  those strings is now either `(source: explicit onXxx setting)` or
  `(source: mode default)`.
- **Removed `AffectedTestsConfig.deprecationWarnings()` and the startup
  warning loop.** With the legacy knobs gone, there is nothing left to
  warn about. The Gradle task's "deprecation" log line disappears in
  v2, which is itself a (small) behaviour change: integrations that
  grep for `runAllIfNoMatches is deprecated` in CI logs must drop that
  check.

**Migration** — the mapping is mechanical and the full migration guide
lives in `docs/DESIGN-v2.md`, but the short version is:

| v1 (removed in v2.0)                   | v2 replacement                                   |
| -------------------------------------- | ------------------------------------------------ |
| `runAllIfNoMatches = true`             | `onEmptyDiff = "FULL_SUITE"` + `onDiscoveryEmpty = "FULL_SUITE"` |
| `runAllIfNoMatches = false`            | `mode = "local"` **or** explicit `onEmptyDiff = "SKIPPED"` + `onDiscoveryEmpty = "SKIPPED"`. Simply deleting the line is not equivalent — under the new zero-config `mode = "auto"` the `ci` profile escalates `DISCOVERY_EMPTY` to `FULL_SUITE`, so a v1 pipeline that used `false` to suppress the no-match full-suite will regress silently unless one of the two explicit paths is picked. |
| `runAllOnNonJavaChange = true`         | `onUnmappedFile = "FULL_SUITE"`                  |
| `runAllOnNonJavaChange = false`        | `onUnmappedFile = "SELECTED"`                    |
| `excludePaths = […]`                   | `ignorePaths = […]`                              |

Regression coverage:

- `AffectedTestsPluginTest.legacyDslKnobsNoLongerExistInV2` reflectively
  pins the absence of the three legacy getters on
  `AffectedTestsExtension` so a well-intentioned "restore the getter
  for back-compat" revert fails the build instead of silently
  re-opening the v1 API surface.
- `AffectedTestsConfigTest.actionSourceReflectsResolutionTierOrdering`
  pins that the only two `ActionSource` values that survive in v2 are
  `EXPLICIT` and `MODE_DEFAULT`, so a resolver refactor cannot
  reintroduce the old tiers without being caught.
- `AffectedTestTaskExplainFormatTest.actionSourceSurfacesModeDefaultForZeroConfig`
  pins the `--explain` trace wording for zero-config installs under
  the new two-tier model, so the pre-v2 `(source: pre-v2 hardcoded
  default)` string cannot sneak back into CI logs.

### Added — discovery-incomplete signal + nested `gradlew` timeout (v1.9.22)

The two deferred findings from the v1.9.21 reliability batch: both close
long-tail silent-failure modes that could leave an operator convinced a
merge-gate run was green when it was in fact half-blind or stuck.

- **`Situation.DISCOVERY_INCOMPLETE` now fires whenever any scanned Java
  file fails to parse.** Before this release, `JavaParsers.parseOrWarn`
  emitted a `WARN` and returned `null`, and the strategy call site
  silently continued — the dispatch map was built from whatever happened
  to parse. For a branch with one mid-refactor syntax error that meant
  `DISCOVERY_SUCCESS` with a quietly-shrunk selection, which is exactly
  the scenario where the merge-gate safety net needs to pay out.
  `ProjectIndex#compilationUnit` now increments a per-run
  `parseFailureCount` at the cache boundary (de-duplicated across
  strategies — a file that fails to parse once counts once, no matter
  how many of naming / usage / implementation / transitive ask for it).
  `AffectedTestsEngine#run` consults that count after discovery and
  routes through the new situation before the old `DISCOVERY_EMPTY` /
  `DISCOVERY_SUCCESS` branches. The mode-default resolution matches the
  existing asymmetry — `CI` and `STRICT` escalate to `FULL_SUITE`
  (safety wins over speed on merge-gate runs), `LOCAL` stays on
  `SELECTED` (iteration speed wins for the developer actively editing
  the broken file, who already has the `WARN` in their terminal). The
  new knob is configurable via the Gradle DSL:
  ```groovy
  affectedTests {
      onDiscoveryIncomplete = "FULL_SUITE" // or "SELECTED" / "SKIPPED"
  }
  ```
  A matching `EscalationReason.RUN_ALL_ON_DISCOVERY_INCOMPLETE` feeds
  `--explain` and the `affectedTest` task's skip-reason / escalation
  copy so operators can tell at a glance whether a full-suite run was
  triggered by an empty diff, an unmapped file, or a parse failure.
  Regression coverage:
    - `ProjectIndexTest.parseFailureCountIncrementsOnUnparseableFile`
      pins the cache-boundary counting contract and the de-duplication
      behaviour (three calls on the same broken file still count as
      one failure).
    - `AffectedTestsEngineTest.discoveryIncompleteEscalatesToFullSuiteInCI`
      and `discoveryIncompleteStillSelectsInLocalMode` pin the
      CI-vs-LOCAL asymmetry.
    - `discoveryIncompleteRespectsExplicitOnDiscoveryIncompleteOverride`
      pins the explicit-wins-over-mode tier.
    - Three `AffectedTestsConfigTest` cases pin the mode defaults for
      `CI`, `LOCAL`, and `STRICT` so a future refactor of the resolver
      can't silently bump zero-config users.
- **New `affectedTests.gradlewTimeoutSeconds` knob deadlines the nested
  `./gradlew` invocation.** The task spawns a child `./gradlew :module:test
  --tests …` for each affected module. Before this release the child
  was launched via Gradle's `ExecOperations`, which has no timeout
  surface at all — a hung test kept the CI worker busy until the outer
  CI-system deadline killed the whole job, which typically surfaces as
  "pipeline failed with no useful logs" hours later. The new knob is a
  wall-clock deadline in seconds; `0` (the default) preserves the
  pre-v1.9.22 "wait forever" behaviour for zero-config callers so the
  upgrade is safe. Positive values branch the task onto a `ProcessBuilder`
  watchdog path: the child still streams its output to the operator's
  terminal via `inheritIO()`, and on timeout the task runs a polite
  `destroy()` on the wrapper → 10-second grace → `destroyForcibly()`
  on both the wrapper and every snapshotted descendant (shared Gradle
  daemons, test JVMs) → 5-second reap ladder before failing the build
  with a message that names the setting so the operator knows what to
  raise. Because `./gradlew` connects to a shared daemon JVM by
  default, killing only the wrapper would leave the actually-hung
  test JVM running as a grandchild; the watchdog snapshots
  `ProcessHandle#descendants()` *before* signalling (once the wrapper
  exits, re-parented daemons become unreachable from
  `process.descendants()`) and forcibly destroys every live descendant
  after the wrapper is reaped, regardless of whether the wrapper
  itself exited gracefully on `SIGTERM` or had to be `destroyForcibly`
  escalated. This matters because a SIGTERM-responsive wrapper
  (plain `sh`, most test runners) exits on `destroy()` but leaves its
  children re-parented to pid 1 — those grandchildren are the real
  hung workload and would keep holding the CI worker hostage if the
  descendants-kill only ran on the forcible leg. Operators who need a
  hard cutoff with no daemon reuse can pass `--no-daemon` at the
  outer build level.
  Outer-build cancellation (Ctrl-C, Gradle daemon shutdown) propagates
  via the same snapshot-then-destroyForcibly path and re-asserts the
  interrupt. The trade-off for opting in:
  the watchdog path uses `inheritIO` instead of `ExecOperations`, so
  Develocity build-scan stream capture of the child's output is not
  available on timed runs — callers who rely on scan ingestion of the
  nested output should leave the knob at `0` and enforce a deadline at
  the CI-job level instead.   Validation lives at the builder gate:
  `AffectedTestsConfigTest.gradlewTimeoutRejectsNegativeValues` fails
  when a user sets `-1` (typoed "no timeout"), and
  `gradlewTimeoutDefaultsToZero` pins the zero-config default so any
  future refactor that flips the default breaks loudly instead of
  retroactively deadline-killing every long-running CI on upgrade.
  Runtime coverage lives in `AffectedTestTaskTimeoutTest`
  (POSIX-only): `hungChildIsKilledWithinLadderBudget` pins the ladder
  budget, `sigtermSwallowingChildEscalatesToForcibleKill` exercises
  the forcible leg against a `trap '' TERM; sleep 60` wrapper,
  `successfulChildReturnsExitCodeBeforeTimeout` pins both the happy
  path and non-zero exit propagation, and
  `descendantsAreTerminatedNotOnlyTheWrapper` writes the grandchild's
  PID to a pidfile and asserts via `ProcessHandle.of(pid).isAlive()`
  that the re-parented `sleep` is reaped — the exact grandchild-
  survival bug the descendants-kill loop closes. The last test was
  the one that surfaced the "only the forcible leg kills descendants"
  regression in the first cut of the fix: if the wrapper exited
  gracefully on SIGTERM the descendants loop never ran and the
  orphaned sleep survived. The fix now reaps descendants on both
  legs.

### Fixed — tier-1 reliability / safety batch (v1.9.21)

The v1.9.20 batch closed every tier-1 *correctness* bug surfaced by the
multi-reviewer sweep. v1.9.21 closes the tier-1 *reliability and
safety* cluster from the same review — the class of issues that don't
silently drop tests but do weaken the merge-gate contract and the
hardening work shipped in v1.9.19.

- **`SourceFileScanner` now rejects file-level symlinks during the
  walk.** v1.9.19 hardened the *directory* path via
  `stayInsideProjectRoot` + `toRealPath`, which closed
  `src/main/java -> /` as an MR-planted symlink. The per-file leg of
  the same attack — an attacker committing
  `src/main/java/com/x/Leak.java -> /etc/passwd` — slid past that
  guard because `Files.walkFileTree` still called `visitFile` on the
  symlink and the visitor didn't check `attrs.isSymbolicLink()`. The
  two file-walk visitors in the scanner (`collectJavaFiles`,
  `walkFqnsUnder`) now skip any entry flagged as a symlink
  unconditionally; a regular `.java` file whose real path
  legitimately lives inside the project tree is just a regular file,
  not a symlink. Regression lives in `SourceFileScannerTest`
  (`rejectsFileLevelSymlinkEscapingProjectRoot`,
  `scansTestFqnsIgnoresFileLevelSymlinks`) — both fail when the
  production guard is reverted, covering both the
  `collectTestFiles`/`collectSourceFiles` path and the
  `scanTestFqns` path so the two halves of discovery can never
  diverge on what counts as a valid source file.
- **Per-subtree `IOException`s no longer truncate the whole walk.**
  The default `SimpleFileVisitor.visitFileFailed` rethrows, and the
  scanner's outer `IOException` catch swallowed the remaining walk
  silently — a single unreadable nested module (permission denied,
  file-deleted-under-us race, filesystem loop) took every readable
  sibling down with it. All three walk sites (`collectJavaFiles`,
  `walkFqnsUnder`, `findAllMatchingDirs`) now override
  `visitFileFailed` to log the unreadable entry at WARN via
  `LogSanitizer` and continue. Default-visible surfacing of the
  skipped entry is the load-bearing change: operators can now see
  when a scan ran incomplete, where previously they'd silently
  under-select tests and have no way to correlate it with CI config
  drift. No regression test — inducing a portable per-entry
  `IOException` requires either root-evadable `chmod 000` semantics
  (fails on CI runners running as root) or heavyweight filesystem
  mocking; the fix is evident in the diff and backed by the 11
  existing walk tests in `SourceFileScannerTest`.
- **`AffectedTestsConfig.Builder#isAcceptableBaseRef` now rejects
  control characters at the entry gate.** The rev-expression pattern
  used `@\{[^}]+\}` for the reflog segment — `[^}]` happily matched
  newline, ESC, CSI, and DEL. A `baseRef` sourced from an
  attacker-controlled CI env var like
  `master@{1\n\u001b[2JAffected Tests: SELECTED\u001b[m}` previously
  passed validation and then flowed unsanitised into
  `log.info("Base ref: {}", …)` in `AffectedTestsEngine` and into
  three `IllegalStateException` messages in `GitChangeDetector`. An
  attacker who could poison `CI_BASE_REF` (or an equivalent) could
  forge plugin-branded status lines into CI output and defeat
  grep-based merge gates. The fix is a single blanket
  `containsControlChars` check before any of the downstream matchers
  run — C0 (`0x00..0x1F`), DEL (`0x7F`), and C1 (`0x80..0x9F`) are
  all rejected, closing every regex path at once without depending
  on getting each hand-crafted character class right. Regression
  lives in `AffectedTestsConfigTest.baseRefRejectsControlCharsInReflogSuffix`,
  which covers the newline-in-reflog shape, bare ESC/DEL, and CRLF
  anywhere in the input; each case fails when the entry-gate check
  is removed.
- **`LogSanitizer` coverage extended to every remaining diff-sourced
  log site.** v1.9.19 introduced the sanitizer and applied it at
  four sites; the multi-reviewer review found ~28 additional sites
  passing raw filenames, FQNs, or exception messages straight to the
  logger — most at DEBUG, but several at default-visible WARN (and
  two inside `IllegalStateException` messages that Gradle renders
  verbatim into the build log). An attacker-crafted filename or a
  poisoned `JavaParser` diagnostic carrying `\u001b[2J…\u001b[m`
  could still forge ops-visible log lines. All remaining sites now
  route through `LogSanitizer.sanitize`:
  `JavaParsers.parseOrWarn` (WARN + DEBUG),
  `ImplementationStrategy.supertypesOf` (WARN) and its per-depth
  match trace (DEBUG), `PathToClassMapper`'s eight classification
  DEBUG lines, `UsageStrategy`'s seven tier-match DEBUG lines,
  `NamingConventionStrategy`'s match DEBUG line,
  `GitChangeDetector`'s per-file changed-entry DEBUG line and the
  `IOException` / `GitAPIException` wrappers it throws (because
  JGit exception messages can carry attacker-influenced pack-file
  paths and ref segments), `AffectedTestsEngine`'s FQN-has-no-file
  DEBUG line, `SourceFileScanner.findAllMatchingDirs`'s trailing
  DEBUG summary (brought into parity with its WARN path), and the
  echo of the offending value inside the `IllegalArgumentException`
  thrown by `AffectedTestsConfig.Builder#baseRef` when validation
  fails (otherwise the reject branch would reopen the same surface
  `containsControlChars` closes on the accept branch). Even DEBUG
  sites are sanitised because operators who bump the log level to
  chase a false-positive selection are exactly the audience an
  attacker would target — the log-forgery contract has to hold at
  every visibility level, not just WARN/INFO. Regressions:
  `LogSanitizerTest` covers the escape table;
  `baseRefValidationErrorDoesNotEchoRawControlChars` pins the
  newly-sanitised exception-message path.

Two related findings from the same review are intentionally deferred
to batch 6 (v1.9.22) so each PR stays scoped: #9 (`ProjectIndex`
parse failures signal discovery-incomplete to the engine so the
safety net can escalate explicitly) and #11 (configurable timeout on
the nested `./gradlew` invocation so a hung child test cannot pin
the outer build indefinitely). Both are observable-behaviour changes
and deserve their own CHANGELOG entries.

### Fixed — post-v1.9.19 multi-reviewer correctness pass (v1.9.20)

A second, deeper multi-agent review of the post-v1.9.19 tree surfaced a
cluster of tier-1 silent-drop bugs that all shared the same failure
mode: the strategy *thought* it had walked a construct but had actually
missed one of its real-world shapes, so the consumer test for a changed
class never entered the selected set. Every fix below ships with a
regression test that fails when the fix is reverted.

- **JavaParser no longer silently drops every file using Java 14+
  language features.** `new JavaParser()` defaults to
  `LanguageLevel.JAVA_11` in JavaParser 3.28, which means every
  compilation unit containing a record, sealed type, or pattern-matching
  shape produced `ParseResult.isSuccessful() == false`. The four call
  sites (`ProjectIndex`, `TransitiveStrategy`, `ImplementationStrategy`,
  `UsageStrategy`) then discarded the result and the file contributed
  nothing to reverse-dependency, implementation, or usage discovery.
  Any modern Spring/DDD codebase leaning on value-object records would
  silently drop every consumer test that depended on a record-shaped
  production class. All four sites now route through a new
  `JavaParsers.newParser()` factory that sets the level to `JAVA_25`
  (the highest stable, non-preview level bundled with JavaParser 3.28,
  which closes the same failure mode for adopters shipping Java
  22–25-era syntax). `JavaParsers.parseOrWarn` also replaces the four
  independent `parseOrGet` helpers so parse failures now log at `WARN`
  with the first JavaParser diagnostic — previously the `isSuccessful
  == false` branch logged at `DEBUG`, which is the exact invisibility
  that hid this bug class for the last eight releases. Regression
  lives in `ImplementationStrategyTest`
  (`findsRecordImplementationOfChangedInterface`,
  `findsEnumImplementationOfChangedInterface`).
- **`TransitiveStrategy` now preserves generic type arguments when
  building the reverse-dependency graph.** The old pipeline parsed
  field and method-signature types as plain strings, normalised
  `List<FooService>` down to `List`, looked the base name up against
  the known FQN set (stdlib import, not a project class), and dropped
  the edge. Every consumer that wrapped the changed type in a
  collection, `Optional`, `Flux`, or any other container lost its
  reverse edge — and with it, all of its tests. The new scan walks
  every `ClassOrInterfaceType` node in the AST, so the inner `FooService`
  of `List<FooService>` is a first-class reference. Regression:
  `TransitiveStrategyTest#preservesGenericArgumentsInReverseDependencyEdges`.
- **`TransitiveStrategy` now scans method bodies, not just signatures.**
  The same string-based approach skipped everything inside method
  bodies. A helper referenced only through `new PricingCalculator()`,
  a cast, or an `instanceof` had no reverse edge and its test was
  silently dropped whenever the helper changed — the single most
  common refactor shape on a service layer. Switching to
  `findAll(ClassOrInterfaceType.class)` covers field types, signatures,
  local variables, instantiations, casts, and pattern checks in one
  pass. Regression:
  `TransitiveStrategyTest#discoversEdgesFromMethodBodyReferences`.
- **`ImplementationStrategy` now recognises records and enums as
  implementers.** The AST pass only iterated
  `ClassOrInterfaceDeclaration`, so
  `record UsdMoney(long cents) implements Money` and
  `enum Currency implements HasCode` were invisible to the supertype
  walk. Naming-convention matching does not rescue records (records
  are named after the value they hold, not after the interface they
  implement), so a change to the interface silently dropped the
  record's test. The pass now finds every `TypeDeclaration` subtype
  and the new `supertypesOf` helper extracts the correct
  extends/implements list for classes, records, and enums. An explicit
  `AnnotationDeclaration` branch and a defensive `WARN`-logged default
  branch close the door on the same bug class silently reappearing
  when JavaParser introduces a future declaration kind. Regressions
  live in `ImplementationStrategyTest` (the same two tests above).
- **`GitChangeDetector` now diffs against the merge-base, not the tip
  of `baseRef`.** The old `committedChanges` path diffed HEAD directly
  against `baseId`. If master moved on after the branch diverged —
  which on any busy repo means "always" — every post-divergence
  master-only commit showed up as a "change" on the feature branch,
  inflating the affected-tests set toward "everything" and destroying
  the whole point of selective testing. The new path computes the
  merge-base via `RevFilter.MERGE_BASE` and diffs against that; on the
  pathological case of no common ancestor it falls back to the
  `baseId` tip so the previous behaviour remains available and now
  surfaces as a `WARN` (was `DEBUG`) so operators can see the semantic
  reversion on grafted or subtree-merged histories. Regression:
  `GitChangeDetectorTest#diffsAgainstMergeBaseNotBaseRefTip`.
- **`UsageStrategy` now catches fully-qualified inline references.** A
  test that skipped the import and wrote
  `com.example.service.Thing t = new com.example.service.Thing();`
  slid through every tier of the old matcher — no import, no
  wildcard, different package, and the simple-name AST scan would
  only fire if the test lived in the same package. A new "Tier 3"
  walks every `ClassOrInterfaceType`, reads
  `getNameWithScope()` via a `nameWithScopeOrNull` helper, and matches
  the reconstituted dotted name against the changed-FQN set. The
  same tier also catches inline references to inner classes of a
  changed outer. Regressions:
  `UsageStrategyTest#findsTestThatUsesChangedClassByFullyQualifiedInlineReference`,
  `UsageStrategyTest#findsTestThatInnerClassQualifiesThroughChangedOuter`.
- **`UsageStrategy` now treats `import pkg.Outer.*` as a dependency on
  `pkg.Outer`.** The old wildcard tier bucketed `pkg.Outer` as a
  package wildcard (since it ended in `.*`) and then checked whether
  the AST referenced the bare name `Outer` — which test code using the
  wildcard almost never writes, because the whole point of the
  wildcard is to skip qualification. Any `Outer.java` change that
  shipped without touching its own imports therefore silently dropped
  every consumer relying on the class-member wildcard. The matcher
  now recognises class-member wildcards by direct FQN equality against
  the changed set. Regression:
  `UsageStrategyTest#findsTestThatWildcardsClassMembersOfChangedClass`.
- **`module-info.java` and `package-info.java` now route to the
  unmapped bucket.** The old mapper produced `module-info` /
  `com.example.package-info` as "production FQNs", poisoning every
  downstream strategy (they would try to derive a simple class name
  from the FQN and match it against real source classes, which never
  matched and silently skipped all the tests the descriptor change
  could actually affect). Both marker files carry project-wide
  semantics — JPMS visibility, package annotations — so the correct
  conservative routing is to hand them to the `UNMAPPED_FILE` safety
  net, which in CI mode escalates to a full suite. Regressions:
  `PathToClassMapperTest#moduleInfoRoutesToUnmappedNotProduction`,
  `PathToClassMapperTest#packageInfoRoutesToUnmappedNotProduction`.
- **Mixed ignored + out-of-scope diffs now route to
  `ALL_FILES_OUT_OF_SCOPE` instead of falling through to
  `DISCOVERY_EMPTY`.** An MR that combined a markdown tweak (ignored
  by default globs) with an `api-test/` change (out-of-scope under the
  pilot config) previously landed nowhere. Neither bucket alone
  matched the whole diff, so the engine dropped through to mapping,
  produced empty production/test sets, and hit `DISCOVERY_EMPTY` —
  which under CI mode defaults to `FULL_SUITE`. That is exactly the
  "quietly escalate a no-op MR into a full CI run" shape v2 was built
  to prevent. The engine now explicitly routes the union case to
  `ALL_FILES_OUT_OF_SCOPE`, which defaults to `SKIPPED`. Regression:
  `AffectedTestsEngineTest#mixedIgnoredAndOutOfScopeDiffRoutesToAllFilesOutOfScope`.

### Fixed — post-v1.9.18 code-review pass

A four-reviewer sweep (correctness, security, maintainability, simplicity)
ran against the plugin source tree as it landed in v1.9.18. Ten findings
were triaged into two tiers — safety-critical (Tier 1) and correctness
gaps that silently dropped tests in real hierarchies (Tier 2) — and both
tiers are resolved in this release. Tier 3 polish items ship in the same
PR because they touch adjacent code and keep v2.0 from carrying dead
weight.

- **Transitive strategy no longer drops consumer tests when a production
  class is deleted.** `TransitiveStrategy.buildReverseDependencyMap`
  built its `allKnownFqns` set purely from files currently on disk.
  A pure `git rm FooService.java` MR ended up with `FooService` in the
  changed set (surfaced by `GitChangeDetector` via the old path) but
  nowhere in the reverse map, so the consumers of `FooService` — the
  only tests that would have caught the breakage — were silently
  excluded. The fix unions `changedProductionClasses` into
  `allKnownFqns` before walking. Regression lives in
  `TransitiveStrategyTest#discoversConsumerTestsForDeletedProductionClass`.
- **`SourceFileScanner` no longer follows symlinks out of the project
  root.** An MR that introduced `src/main/java` as a symlink to `/`
  (or any path outside the project) would walk the victim's filesystem,
  enumerating arbitrary `.java` files in the scanner's match list and
  inflating the scan to the point of DoS on laptops. Matched filenames
  then flowed into `--explain` samples and the WARN log, which is where
  the leak would have become visible to the attacker on CI. 
  `findAllMatchingDirs` now canonicalises every candidate via
  `Path.toRealPath()` and rejects anything whose real path does not
  start with the project root's real path. Two regressions:
  `rejectsSymlinkEscapingProjectRoot` covers the exploit;
  `acceptsSymlinkThatStaysInsideProjectRoot` prevents the cure from
  being worse than the disease for monorepos that symlink between
  sibling modules.
- **Log output is now sanitised.** Filenames from the git diff can
  legally contain newlines, carriage returns, and ANSI escape bytes —
  JGit faithfully preserves them. Before this release the
  `--explain` sample lines, the unmapped-file WARN, the malformed-FQN
  WARN in the Gradle task, and the per-test INFO line in the engine
  all fed those bytes straight into SLF4J, which means a hostile MR
  could forge whole log lines (including fake
  `Affected Tests: SELECTED` headers) or colour legitimate log lines
  to hide warnings from a human reviewer. New `LogSanitizer` escapes
  the C0 range (NUL..US), DEL (0x7F), and the C1 range (0x80..0x9F,
  which includes single-byte CSI) into visible `\\xHH` / `\\n` / `\\r`
  / `\\t` forms, and every filename-derived log site — four in total
  — now routes through it.
- **`AffectedTestsConfig#effectiveMode()` now always returns a concrete
  mode.** The Javadoc promised one of `LOCAL`, `CI`, `STRICT`; the
  getter returned `null` for zero-config callers, so the documented
  switch shape NPE'd on anyone who followed the Javadoc to the letter.
  The public getter now falls back to `Builder.detectMode()` when the
  internal field is unset — preserving the nullable internal signal
  that `resolveSituationActions` uses to distinguish "mode was
  explicitly set" from "we inferred it" — and a regression test
  (`effectiveModeIsAlwaysConcreteForZeroConfigCallers`) locks the
  contract down.
- **`outOfScopeTestDirs`/`outOfScopeSourceDirs` now catch literal
  entries whose value equals the resolved directory path.** The
  matcher treated `sub/src/main/java` as a prefix only and relied on
  `contains("/sub/src/main/java/")`. `ProjectIndex` relativises source
  directories with `Path.relativize`, which never emits a trailing
  slash, so the contain-check missed its only real input shape. The
  literal branch now also accepts `path.equals(bare)` and
  `path.endsWith("/" + bare)`. Regression:
  `OutOfScopeMatchersTest#literalEntryMatchesExactDirectoryPath`.
- **`UsageStrategy` now selects tests that import inner classes or use
  static imports of the changed class.** Pre-fix, `import x.Outer.Inner`
  and `import static x.Constants.MAX` did not register as a reference
  to `x.Outer` / `x.Constants`, so a genuinely affected test whose only
  reference to the changed surface was a static constant was silently
  dropped. `testReferencesChangedClass` now strips the last segment
  from static imports (handling both the named-member and
  `x.C.*` shapes) and prefix-matches inner-class imports against
  changed FQNs. Three regressions cover the inner-class direct import,
  the static-member import, and the static wildcard.
- **`ImplementationStrategy` now performs a fixpoint walk of the
  subtype closure.** Given `interface A <- abstract B implements A
  <- class C extends B`, only B is a direct subtype of A. A single
  pass stopped at B, so `CTest` — the only concrete-implementor test
  — was silently dropped whenever A changed. The strategy now seeds
  each subsequent pass with the freshly-found impls, bounded by
  `transitiveDepth` as a sanity cap. Regression:
  `findsGrandchildImplementationThroughMultiLevelHierarchy`.
- **`PathToClassMapper` rejects path-traversal segments in diff
  input.** Git never emits `..` as a standalone segment in diff paths,
  so one appearing there is either a malformed ref or an attempt to
  confuse the mapper. Pre-fix, `../../etc/passwd.java` was handed to
  `tryMapToClass`, which produced an FQN starting with `..` and
  classified it as production — a shape the test dispatcher would then
  try to run. The segment check now routes those inputs to the
  unmapped bucket (at `debug` level, so a malicious diff cannot flood
  the build log), which trips the `UNMAPPED_FILE` situation and
  escalates to a full run on the engine's normal WARN path. A
  segment-aware check keeps legitimate filenames like `foo..bar.java`
  working.
- **`baseRef` validation is now JGit-native.** The v1.x check rejected
  the single shape `contains("../")` and accepted everything else —
  including control characters, leading slashes, and trailing
  `.lock`, all of which JGit's own refname rules already forbid. The
  builder now delegates to `Repository.isValidRefName` (canonical
  `refs/...` names), accepts 7-40 char hex SHAs directly, and accepts
  short rev-expressions like `HEAD~3` and `master^2`. The
  error message on rejection explicitly names the three accepted
  shapes so consumers have something actionable instead of "suspicious
  path traversal".

### Removed

- `PathToClassMapper#extractModule` and its three unit tests. The
  method had no in-tree caller and the `extractModuleFromDirs` branch
  under it duplicated logic that `tryMapToClass` already performs more
  carefully. Dead code by construction; removing it keeps the mapper's
  surface aligned with what the engine actually uses.

## [1.9.18]

### Changed — behaviour flip, read this

- **`includeUncommitted` and `includeStaged` now default to `false`
  (committed-only).** Previously both defaulted to `true`, meaning a
  local `./gradlew affectedTest` would expand the diff boundary with
  whatever happened to be sitting in the dev's working tree. That made
  local and CI runs of the same HEAD pick different test sets, and the
  inclusion was invisible in the summary log.

  The new default mirrors CI reality (where the tree is clean after
  checkout anyway) and makes two runs on the same commit deterministic
  regardless of workstation state. Adopters who iterate on tests
  locally and want WIP to seed the diff flip the two knobs back on in
  `build.gradle`:

  ```groovy
  affectedTests {
      includeUncommitted = true  // opt back in for local WIP runs
      includeStaged = true
  }
  ```

  No migration is required for anyone who was already setting these
  explicitly — the config resolver always preferred the explicit value
  over the convention. Adopters who relied on the old `true` default
  without setting anything will see a smaller local-run test set until
  they commit or opt back in; CI selection is unchanged.

### Added

- `outOfScopeTestDirs` and `outOfScopeSourceDirs` now accept Ant-style
  globs (`api-test/**`, `**/api-test/**`, `{api,perf}-test/**`) in
  addition to the existing literal directory prefixes. Each list entry
  is classified independently, so users can mix both shapes in the
  same config. Surfaced after a real adopter configured
  `outOfScopeTestDirs = ['api-test/**']` and the engine silently
  treated it as a literal prefix — which never matched.
- New `Hint:` line on `affectedTest --explain` when
  `outOfScopeTestDirs` / `outOfScopeSourceDirs` are configured but
  zero files in the diff landed in the out-of-scope bucket. Points at
  the configured knob so the operator learns about the silent
  misconfiguration on the trace instead of after a 30-minute full-suite
  CI run. The hint is suppressed on empty diffs, on runs where the
  bucket is non-empty, and on zero-config installs — so its rarity is
  itself a signal.
- `OutOfScopeMatchers` — an internal utility shared between
  `PathToClassMapper` and `ProjectIndex`. Not a public API, but
  called out here because it is the structural fix behind the
  glob-alignment bug in the Fixed section below, and because its
  malformed-glob error path is now the single source of truth for
  `Affected Tests: invalid glob at outOfScope*Dirs[N]` messages.

### Fixed — post-v1.9.17 sanity-test pass

- The `Hint:` line on `affectedTest --explain` no longer fires on
  situations where an out-of-scope pattern could not have mattered.
  Running v1.9.17 through nine representative MR shapes on the
  security-service pilot (empty diff, api-test-only, performance-test-
  only, production Java only, markdown only, mixed api-test + prod,
  test-only, `git rm` markdown, gradle + prod) showed the hint firing
  on five of them — including a bare markdown-only MR where the diff
  contained nothing a source-tree matcher could have bitten. That
  5-of-9 false-positive rate trains reviewers to ignore the line,
  defeating its purpose.

  The hint now requires the situation to be `DISCOVERY_SUCCESS` or
  `DISCOVERY_EMPTY` (the only two branches where a correctly-configured
  out-of-scope pattern could have changed the outcome) in addition to
  the existing "diff non-empty AND out-of-scope bucket empty AND at
  least one out-of-scope dir configured" guard. `EMPTY_DIFF`,
  `ALL_FILES_IGNORED`, `ALL_FILES_OUT_OF_SCOPE`, and `UNMAPPED_FILE`
  all suppress the hint — none of them is a case where an operator
  could act on it.

- Lifecycle output for `SELECTED` dispatches now previews the first
  five FQNs per module with a "… and N more (use --info for full list)"
  tail on larger dispatches. Pre-v1.9.18 the task printed only the
  module summary at lifecycle level and demoted every FQN to info,
  leaving a reviewer scrolling the default CI log unable to see *which*
  tests were selected without either rerunning with `--info` or opening
  the JUnit report after the fact. The info-level per-FQN log is
  retained for `--info` users, and the bounded preview keeps total
  lifecycle output well under the 4 MiB GitHub Actions step cap that
  forced the demotion in the first place.

- Dispatch-side FQN validation was hoisted out of the per-module log
  loop so the "Running N affected test classes across M module(s)"
  header now reports the post-validation count and stays arithmetically
  consistent with the per-module previews underneath it. If any FQNs
  were skipped (an `isValidFqn` WARN was already logged) the header
  gains a ` (K malformed FQN skipped — see WARN above)` suffix so the
  mismatch between "discovered" and "dispatched" is visible at a
  glance rather than a puzzle only the WARN log can solve. Two latent
  side-effects fall out of the refactor:
  - A module whose entire discovered FQN set fails validation is now
    dropped from dispatch. Pre-v1.9.18 the task appended that module's
    bare `taskPath` with no `--tests` filter, which silently degraded
    into a full module test suite run — the exact safety posture
    `isValidFqn` was added to prevent. Operators relying on this
    accidental fallback should configure `runAllIfNoMatches = true` /
    `Action.FULL_SUITE` explicitly instead.
  - A dispatch where zero FQNs survive validation across ALL modules
    now throws `GradleException` instead of invoking Gradle with an
    empty task list (environment-dependent behaviour, ranging from
    "fail with No tasks specified" to "silently run the default task").
    The WARN logs above the failure name every rejected FQN, so the
    mis-discovery is diagnosable from the same console output.

### Fixed — post-v1.9.16 review batch

- `GitChangeDetector.uncommittedChanges` and `stagedChanges` now
  surface `DELETE` entries through the old path, the same way
  `committedChanges` has since v1.9.16. The v1.9.16 release patched
  the committed branch only, which was inert under its own
  committed-only defaults but re-opened the silent-skip hole the
  moment any adopter flipped `includeUncommitted` or `includeStaged`
  back on to iterate on a local `git rm`. All three diff sources
  now share one `collectPaths` helper so future bucketing changes
  can't drift between them.
- Regression tests added for the previously-untested error branches:
  `GitChangeDetector`'s shallow-clone `MissingObjectException` hint,
  `PathToClassMapper.isIgnored`'s `InvalidPathException` guard (NUL
  bytes and friends), and the config-time error message for
  malformed globs in `ignorePaths`. No code change — only coverage
  for branches whose existing behaviour the batch-1 fix relied on.

### Documentation

- `AffectedTestTask.getTransitiveDepth()` Javadoc now documents the
  actual default of `4` with rationale. The v1.9.16 pass updated
  `TransitiveStrategy` and `AffectedTestsExtension` but missed this
  third site — consumers reading `AffectedTestTask` Javadoc were
  still being told they needed to set `transitiveDepth = 4`
  explicitly; they do not.
- `OutOfScopeMatchers` class Javadoc replaces the Javadoc-safe
  `&#42;&#42;` escapes with literal `**` inside `{@code}` (the
  comment-terminator was never a risk — only `*/` is, and `{@code}`
  only cares about matching braces). The opening brace still has to
  be escaped because Javadoc closes `{@code}` at the first `}`; a
  one-line comment now explains why the escape is unavoidable.
- `OutOfScopeMatchers.hasGlobMetachar` now documents why only the
  opening forms `[` and `{` are treated as glob indicators — a
  literal directory name that happens to contain an unbalanced `]`
  or `}` is a far more likely reality than a user meaning "glob",
  and promoting it to glob compilation would convert
  typo-in-literal into a build-breaking glob syntax error.
- `AffectedTestTask.JAVA_FQN` now documents the intentional
  non-validation of Java reserved words. An FQN shaped like a
  keyword can't be produced by the discovery strategies (they read
  real filenames); the only way one reaches the filter is
  adversarially, and Gradle's downstream `--tests` matcher reports
  "no tests found" for it — never a compile failure or RCE.

### Fixed — post-v1.9.15 review batch

- `outOfScopeTestDirs` / `outOfScopeSourceDirs` glob entries now work
  on both sides of the pipeline. Before this fix the diff-side
  classifier in `PathToClassMapper` honoured `"api-test/**"` but the
  on-disk classifier in `ProjectIndex` treated the same string as a
  literal prefix, so a mixed diff (one production file + a refactor
  under `api-test/`) bucketed the api-test file correctly yet still
  dispatched tests discovered under `api-test/src/test/java`. Both
  sides now delegate to a shared compiler, with a regression test that
  exercises the literal and glob shapes against identical on-disk
  layouts.
- `git rm`-only MRs no longer silently skip all tests. `DiffEntry.DELETE`
  entries now surface through their old path, so ignore/out-of-scope
  rules apply normally and deleted production classes reach the
  transitive strategy instead of routing the whole MR through
  `EMPTY_DIFF → SKIPPED` under `local`/`ci` mode. The existing engine
  filter still drops FQNs whose backing file is gone, so surfacing
  deletions never asks Gradle to run a missing test.
- `ImplementationStrategy` now recognises the `DefaultFooService`
  prefix shape, not only the `FooServiceImpl` suffix. The plugin has
  always shipped `implementationNaming = ["Impl", "Default"]` and the
  Javadoc documents both shapes, but the naming-convention loop used
  to append both tokens as suffixes (`FooServiceDefault`), matching
  nothing real. The AST-scan branch rescued explicit
  `implements FooService` cases; generics-only declarations and files
  that JavaParser couldn't parse silently missed the Default-prefixed
  impl.
- Turkish (and any other locale whose case-folding tables differ from
  US English) no longer turn `mode = "ci"` into `Unknown
  affectedTests.mode 'ci'`. `parseMode` and `parseAction` now force
  `Locale.ROOT`, matching `AffectedTestsConfig`'s own parsing. The
  Windows detection in the Gradle-command resolver was pinned to
  `Locale.ROOT` for the same reason.
- `PathToClassMapper.isIgnored` and the `OutOfScopeMatchers` glob
  matchers now fail closed on `InvalidPathException` (NUL bytes,
  Linux-committed filenames like `foo:bar.md` arriving on a Windows
  CI runner, Windows reserved names). Before this fix the unhandled
  exception killed the whole `affectedTest` task with a stack trace;
  now the offending file falls through to the unmapped bucket and the
  safety net escalates normally.
- `GitChangeDetector` now translates JGit's `MissingObjectException`
  into a targeted message naming the likely cause: a shallow clone in
  CI that doesn't know the base ref. Before, users saw the raw JGit
  exception and had to guess whether the problem was the ref, the
  clone depth, or a corrupt repo.
- Malformed globs in `outOfScopeTestDirs` / `outOfScopeSourceDirs` /
  `ignorePaths` now fail at config-time with an
  `IllegalStateException` naming the config key, list index, and
  offending pattern. The raw `PatternSyntaxException` the JVM throws
  is useless on its own because it doesn't say which config entry
  caused the regex error.
- The `--tests` argv assembly now skips and warns on any discovered
  FQN that isn't shaped like a Java identifier. Defense-in-depth
  against a buggy custom strategy or parser anomaly injecting a shell
  metacharacter, whitespace, or a hyphen into Gradle's test filter —
  such strings either crashed the test runner with an obscure glob-
  expansion error or silently matched zero tests.
- Per-FQN dispatch lines in the task output demoted from `lifecycle`
  to `info`. Each MR now gets one `lifecycle` summary per Gradle task
  (`:api:test (3 test classes)`); the individual FQNs print only when
  the user opts in with `--info`. Before, a 200-class MR produced 200
  console lines and drowned the single line the user actually cared
  about — the outcome/situation summary.

### Documentation

- `Mode` Javadoc now matches the mode-defaults table in the design
  doc. The post-v2 table said zero-config CI users get
  `DISCOVERY_EMPTY = FULL_SUITE` on top of `LOCAL`, but the class-
  level doc still described `LOCAL` as the pre-v2 baseline "minus"
  the safety net.
- `Situation` Javadoc cross-reference for `ALL_FILES_OUT_OF_SCOPE`
  updated — the old reference pointed at a since-renamed constant.
- `TransitiveStrategy` and `AffectedTestsExtension` now document the
  actual `transitiveDepth` default of `4`, not the pre-v2 value of
  `2`. Consumers reading Javadoc were being told they needed to set
  `transitiveDepth = 4` explicitly; they do not.

## [1.9.12] — 2026-04-22

Metadata and release-pipeline release — no plugin behaviour change. Introduces
this file, fixes the README's outdated guidance on forcing minor/major bumps,
and teaches the release workflow a proper entry point for non-patch releases
so the next minor bump can actually be requested end-to-end.

### Added

- `CHANGELOG.md` itself, plus a README link so it's discoverable from the
  landing page.
- `workflow_dispatch` trigger on `release.yml` with an optional `version`
  input. Supplying `version: 1.10.0` runs
  `./gradlew release -Prelease.version=1.10.0` and overrides the default patch
  auto-increment. Manually triggered runs still go through the same
  portal-check and GH-release steps, so they stay idempotent.

### Fixed

- README Versioning table previously claimed
  `./gradlew markNextVersion -Prelease.version=X.Y.0` would force a minor bump
  on the next release. It did not — axion-release's `markNextVersion` creates
  a `release-X.Y.Z` marker tag that only influences the `currentVersion`
  *display*, not the `release` task. The table now reflects the three
  mechanisms that actually work: default patch (merge to master), explicit
  version (Actions → Release → Run workflow), and manual local
  (`./gradlew release -Prelease.version=X.Y.Z` with a Personal Access Token).

## Release history before this file existed

The v2 configuration model landed additively across the preceding patch
releases. The full per-tag auto-generated notes are on
[GitHub Releases](https://github.com/vedanthvdev/affected-tests/releases). In
broad strokes:

- **v1.9.11** — Deprecation warnings on the three legacy knobs
  (`runAllIfNoMatches`, `runAllOnNonJavaChange`, `excludePaths`), pointing at
  the v2 replacements. README examples flipped to v2-native.
  See [`docs/DESIGN-v2.md`](docs/DESIGN-v2.md) for the design doc and the
  "Migrating from v1 config" section in `README.md` for the worked example,
  before/after table, and decision tree. The legacy knobs are scheduled for
  removal in v2.0.0.
- **v1.9.10** — Named outcome and situation on every `affectedTest` summary
  log line:
  `Affected Tests: <OUTCOME> (<SITUATION>) — <details>`. Matches the
  vocabulary `--explain` reports.
- **v1.9.9** — `--explain` CLI flag on `affectedTest`. Prints the full
  decision trace — bucketed diff, resolved action per situation with the
  priority-ladder tier that picked each
  (`EXPLICIT` / `LEGACY_BOOLEAN` / `MODE_DEFAULT` / `HARDCODED_DEFAULT`), and
  the final outcome — without running any tests.
- **v1.9.8** — Core v2 configuration API: the `Action` / `Situation` / `Mode`
  vocabulary, per-situation `onXxx` actions, `ignorePaths` (replaces
  `excludePaths` with a broader default list), and
  `outOfScopeTestDirs` / `outOfScopeSourceDirs` for Cucumber / API-test
  exclusions that should skip rather than escalate to the full suite.
- **v1.9.7 and earlier** — Plugin bring-up, Gradle Plugin Portal publishing,
  safety hardening, multi-module scanning, axion-release versioning. See the
  Releases page for detail.

[Unreleased]: https://github.com/vedanthvdev/affected-tests/compare/v2.2.0...HEAD
[v2.2.0]: https://github.com/vedanthvdev/affected-tests/releases/tag/v2.2.0
[v2.1.0]: https://github.com/vedanthvdev/affected-tests/releases/tag/v2.1.0
[1.9.12]: https://github.com/vedanthvdev/affected-tests/releases/tag/v1.9.12
