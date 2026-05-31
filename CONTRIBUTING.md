# Contributing

Thanks for considering a contribution. This document is short on
purpose: the bigger the document, the less likely it is to be read.

## What kinds of changes are welcome

The plugin is in active production use on at least one large
service. The bar for adopter-impacting changes is high, but the
bar for the surfaces below is genuinely low — those are where
contributions help the plugin compound:

| Surface | Bar |
| --- | --- |
| **Bug fix backed by a failing test** | Low — open a PR. Include the test before the fix in the same diff so the regression is documented. |
| **New `--explain` field, log line, or hint** | Low — operability is a first-class concern. Anything that helps an adopter answer "why did the plugin do that?" without re-running with `--info` is welcome. |
| **Performance fix with a measurement** | Low — bring a `time ./gradlew affectedTest --explain` before/after, or a JMH delta if the change is in a hot loop. Numbers carry the change. |
| **New discovery strategy** | Medium — open an issue first to agree on the situation/action wiring. The four existing strategies (naming, usage, impl, transitive) cover the common cases; a fifth one needs a justification an adopter can map to a real diff. |
| **Mode profile change, default flip** | High — open an issue first. A mode default is a behaviour change for every adopter on `mode = 'auto'`, so the discussion is "what does this break?", not "is this code correct?" |
| **Kotlin source mapping** | Phase 2 shipped in #76. Follow-up hardening is tracked in #102 (`@file:JvmName`), #103 (sealed-class permits), and #117 (typealias + KMP expect/actual). |
| **Groovy source mapping** | Active epic: #96 (`help wanted`). Tech plan landed in PR #97; implementation tracks in #98, #99, and #100. |
| **Scala source mapping** | Tracking placeholder: #111 (awaiting adopter signal; currently no active implementation plan). |

## How to set up locally

```bash
git clone https://github.com/vedanthvdev/affected-tests.git
cd affected-tests
./gradlew :affected-tests-core:test :affected-tests-gradle:test
./gradlew :affected-tests-gradle:functionalTest
```

Toolchain expectations:

- **Java 21** for compilation (`sourceCompatibility = 21`, set in
  the root `build.gradle`).
- **Gradle 8+** at the consumer site, but the wrapper in this repo
  pins to 9.x — use `./gradlew`, not a system Gradle.
- **No external services required.** All tests are hermetic; the
  functional suite shells out to TestKit-managed Gradle daemons
  inside `build/`.

## Running a focused test

The unit suite (`affected-tests-core` + `affected-tests-gradle`'s
`test` task) is fast — a few seconds on a warm cache. The
functional suite (`affected-tests-gradle`'s `functionalTest` task)
spins a fresh Gradle daemon per scenario and takes ~30s; run only
the scenarios you need while iterating:

```bash
./gradlew :affected-tests-gradle:functionalTest \
  --tests '*.RunCucumberTest' \
  -Dcucumber.filter.name='your scenario name'
```

## Branches and commits

- **One branch, one commit.** When you push a follow-up to a PR,
  amend the existing commit (`git commit --amend`) and force-push
  with `--force-with-lease` rather than stacking a "fix review
  comments" commit. The merge gate squashes anyway and the PR
  history stays readable.
- **Commit message format:** `<branch-name>: <one-line summary>`,
  followed by a blank line, followed by a paragraph explaining
  *what* changed and *why*. The `*what*` is the diff; the *why* is
  the part that compounds.
- **Branch naming:** `issue-NN` for tracked work, `fix/<topic>` or
  `feat/<topic>` for self-directed work. A PR that closes an issue
  ends its body with `Closes #NN` so GitHub auto-links.

## Tests are required

Every code change carries a test. Three options for where it lives:

- **`affected-tests-core/src/test/`** — pure-logic coverage of the
  decision engine, mappers, strategies, and parsers. Use this for
  anything that does not touch Gradle types.
- **`affected-tests-gradle/src/test/`** — ProjectBuilder-level
  coverage of the extension wiring, task creation, `--explain`
  rendering, and locale edge cases. Use this for anything that
  touches the DSL or task setup but does not need a real build.
- **`affected-tests-gradle/src/functionalTest/`** — Cucumber +
  Gradle TestKit. Use this for anything that needs a real Gradle
  daemon (Configuration Cache compatibility, plugin-publish
  variants, multi-project wiring). Each `.feature` file maps to
  one `RunCucumberTest` scenario.

## Releasing

Releases are cut from `master` only, by the maintainer, via the
`Release` workflow (`.github/workflows/release.yml`). Three paths:

1. **Auto-patch increment** — every push to `master` bumps the
   patch version unless HEAD is already tagged. This is the normal
   path for small fixes and is a no-op for the contributor.
2. **`.release-version` file** — to ship a minor/major bump
   without manual workflow dispatch, land a commit that creates a
   `.release-version` file at repo root containing the target
   SemVer (e.g. `2.3.0`). The release workflow tags that exact
   version, then auto-deletes the file in a follow-up commit so
   subsequent pushes resume auto-patch-increment.
3. **`workflow_dispatch` input** — the maintainer can trigger the
   workflow manually with an explicit `version` input. This wins
   over the file and is the path used to recover from a failed
   publish.

If you need a release cut, leave a comment on your PR or the
relevant issue rather than running the workflow yourself.

## Reporting a security issue

See `SECURITY.md` for the disclosure path. Do not file public
issues for security reports.

## Code of conduct

Be kind. Engage with the technical content, not the contributor.
The goal is a plugin that is correct, fast, and operable — every
review comment, commit message, and issue should serve that goal.

The full standards and the enforcement / reporting flow live in
[`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) (Contributor
Covenant 2.1). Read it before opening your first issue or PR.
