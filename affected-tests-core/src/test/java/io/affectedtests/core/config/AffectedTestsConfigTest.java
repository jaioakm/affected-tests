package io.affectedtests.core.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AffectedTestsConfigTest {

    @Test
    void defaultsAreApplied() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();

        assertEquals("origin/master", config.baseRef());
        // v1.10.x flip: the core builder now defaults to COMMITTED-ONLY
        // (both flags false). Rationale lives in the release notes and
        // AffectedTestsPlugin#apply — picking CI semantics as the
        // default, with WIP inclusion as an explicit opt-in, keeps a
        // programmatic run on the same HEAD deterministic.
        assertFalse(config.includeUncommitted(),
                "Core builder default must be COMMITTED-ONLY as of the v1.9.14 → next-release flip");
        assertFalse(config.includeStaged(),
                "Core builder default must be COMMITTED-ONLY as of the v1.9.14 → next-release flip");
        // Issue #132 ships headerEdges as a default-on strategy. The
        // DSL knob {@code headerEdgesEnabled = false} is the
        // documented one-flag kill switch; removing
        // {@code "headerEdges"} from the strategies list explicitly
        // is the secondary opt-out for adopters who already curate
        // the strategies set.
        assertEquals(Set.of("naming", "usage", "impl", "transitive", "headerEdges"),
                config.strategies());
        // v2 raises the default from 2 to 4: real ctrl→svc→repo→mapper
        // chains sit 3-4 deep so 2 dropped coverage on zero-config.
        assertEquals(4, config.transitiveDepth());
        assertEquals(List.of("Test", "IT", "ITTest", "IntegrationTest"), config.testSuffixes());
        assertEquals(List.of("src/main/java"), config.sourceDirs());
        assertEquals(List.of("src/test/java"), config.testDirs());
        // v2 widens the default ignore list from "generated/**" only to a
        // broader "things that can't possibly affect tests" baseline
        // (markdown, images, licence/changelog). Assertion checks a
        // representative subset; tests that care about exact contents
        // should inspect {@link AffectedTestsConfig.Builder#DEFAULT_IGNORE_PATHS}.
        assertTrue(config.ignorePaths().contains("**/generated/**"));
        assertTrue(config.ignorePaths().contains("**/*.md"));
        assertTrue(config.includeImplementationTests());
        assertEquals(List.of("Impl", "Default"), config.implementationNaming());
        assertEquals(List.of(), config.outOfScopeTestDirs());
        assertEquals(List.of(), config.outOfScopeSourceDirs());
        // Raw configured mode is AUTO — the resolved mode in
        // {@link #effectiveMode()} is one of LOCAL / CI / STRICT depending
        // on the environment (covered by
        // {@link #effectiveModeIsAlwaysConcreteForZeroConfigCallers()}).
        assertEquals(Mode.AUTO, config.mode());
        // Environment-independent action assertions — DISCOVERY_SUCCESS
        // is hardcoded SELECTED; UNMAPPED_FILE is FULL_SUITE in every
        // built-in mode. Mode-specific behaviour (DISCOVERY_EMPTY,
        // EMPTY_DIFF) is covered by the per-mode tests below, which
        // pin {@code mode(Mode.X)} explicitly to stay deterministic.
        assertEquals(Action.FULL_SUITE, config.actionFor(Situation.UNMAPPED_FILE));
        assertEquals(Action.SELECTED, config.actionFor(Situation.DISCOVERY_SUCCESS));
    }

    @Test
    void customValuesArePreserved() {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .baseRef("origin/main")
                .includeUncommitted(false)
                .includeStaged(false)
                .strategies(Set.of("naming"))
                .transitiveDepth(0)
                .testSuffixes(List.of("Test"))
                .sourceDirs(List.of("src/main/java", "src/main/kotlin"))
                .testDirs(List.of("src/test/java", "src/test/kotlin"))
                .ignorePaths(List.of("**/generated/**", "**/*Dto.java"))
                .includeImplementationTests(false)
                .implementationNaming(List.of("Impl", "Default"))
                .build();

        assertEquals("origin/main", config.baseRef());
        assertFalse(config.includeUncommitted());
        assertFalse(config.includeStaged());
        assertEquals(Set.of("naming"), config.strategies());
        assertEquals(0, config.transitiveDepth());
        assertEquals(List.of("Test"), config.testSuffixes());
        assertEquals(2, config.sourceDirs().size());
        assertEquals(2, config.testDirs().size());
        assertEquals(2, config.ignorePaths().size());
        assertFalse(config.includeImplementationTests());
        assertEquals(2, config.implementationNaming().size());
    }

    @Test
    void transitiveDepthIsClamped() {
        AffectedTestsConfig high = AffectedTestsConfig.builder().transitiveDepth(10).build();
        assertEquals(5, high.transitiveDepth());

        AffectedTestsConfig negative = AffectedTestsConfig.builder().transitiveDepth(-1).build();
        assertEquals(0, negative.transitiveDepth());
    }

    @Test
    void explicitSituationActionWinsOverModeDefault() {
        // Explicit on* setter beats the per-mode default — the whole
        // point of the priority ladder is that a caller who types
        // `onDiscoveryEmpty` can rely on it sticking even when the
        // resolved mode would otherwise flip that branch to FULL_SUITE.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .mode(Mode.CI)
                .onDiscoveryEmpty(Action.SKIPPED)
                .build();
        assertEquals(Action.SKIPPED, config.actionFor(Situation.DISCOVERY_EMPTY));
        assertEquals(ActionSource.EXPLICIT,
                config.actionSourceFor(Situation.DISCOVERY_EMPTY));
        // Sibling situations not pinned explicitly still follow the
        // mode default — explicit is a per-situation override, not a
        // blanket one.
        assertEquals(Action.SKIPPED, config.actionFor(Situation.EMPTY_DIFF));
        assertEquals(ActionSource.MODE_DEFAULT,
                config.actionSourceFor(Situation.EMPTY_DIFF));
    }

    @Test
    void modeCiEscalatesDiscoveryEmptyByDefault() {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .mode(Mode.CI)
                .build();
        assertEquals(Action.FULL_SUITE, config.actionFor(Situation.DISCOVERY_EMPTY));
        assertEquals(Action.SKIPPED, config.actionFor(Situation.EMPTY_DIFF));
        assertEquals(Action.SKIPPED, config.actionFor(Situation.ALL_FILES_OUT_OF_SCOPE));
        assertEquals(Mode.CI, config.effectiveMode());
    }

    @Test
    void modeStrictEscalatesEverythingAmbiguous() {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .mode(Mode.STRICT)
                .build();
        assertEquals(Action.FULL_SUITE, config.actionFor(Situation.EMPTY_DIFF));
        assertEquals(Action.FULL_SUITE, config.actionFor(Situation.ALL_FILES_IGNORED));
        assertEquals(Action.FULL_SUITE, config.actionFor(Situation.UNMAPPED_FILE));
        assertEquals(Action.FULL_SUITE, config.actionFor(Situation.DISCOVERY_EMPTY));
        // ...except out-of-scope, which is always SKIPPED even in STRICT:
        // escalating it would fight the whole point of the knob.
        assertEquals(Action.SKIPPED, config.actionFor(Situation.ALL_FILES_OUT_OF_SCOPE));
    }

    @Test
    void modeCiEscalatesDiscoveryIncompleteByDefault() {
        // Regression for B6-#9 resolver wiring: the new situation has
        // to map to the mode-specific safety net, not silently default
        // to the all-pass legacy value. CI (merge-gate) must escalate
        // a parse-failure run to FULL_SUITE because selection is known
        // to be under-reported; anything else is a silent safety loss.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .mode(Mode.CI)
                .build();
        assertEquals(Action.FULL_SUITE, config.actionFor(Situation.DISCOVERY_INCOMPLETE));
    }

    @Test
    void modeLocalKeepsDiscoveryIncompleteSelectedByDefault() {
        // Mirror of modeCi test — LOCAL prefers iteration speed over
        // safety so defaults to SELECTED (warn-and-continue). If a
        // future refactor flips this to FULL_SUITE, every dev's
        // `./gradlew affectedTest` on a branch with one syntax error
        // suddenly pays a full-suite tax — the regression is painful
        // and quiet without this pin.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .mode(Mode.LOCAL)
                .build();
        assertEquals(Action.SELECTED, config.actionFor(Situation.DISCOVERY_INCOMPLETE));
    }

    @Test
    void modeStrictEscalatesDiscoveryIncompleteToFullSuite() {
        // STRICT must never leave DISCOVERY_INCOMPLETE at SELECTED —
        // the whole point of STRICT is "any doubt means full suite".
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .mode(Mode.STRICT)
                .build();
        assertEquals(Action.FULL_SUITE, config.actionFor(Situation.DISCOVERY_INCOMPLETE));
    }

    @Test
    void explicitOnDiscoveryIncompleteWinsOverModeDefault() {
        // Same contract as every other on* setter: explicit wins.
        // Regression catches a refactor that forgets to route
        // DISCOVERY_INCOMPLETE through the explicit-tier resolver
        // and falls back to mode default.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .mode(Mode.CI)
                .onDiscoveryIncomplete(Action.SKIPPED)
                .build();
        assertEquals(Action.SKIPPED, config.actionFor(Situation.DISCOVERY_INCOMPLETE));
    }

    @Test
    void gradlewTimeoutDefaultsToZero() {
        // Zero means "no deadline" — the pre-v1.9.22 behaviour.
        // Defaulting to any positive value would retroactively fail
        // every long-running CI on the upgrade, which is why the
        // builder defaults to 0 and the Gradle task branches on
        // `> 0` before spawning a watchdog.
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        assertEquals(0L, config.gradlewTimeoutSeconds(),
                "Default must remain 0 (no timeout) — any other default is a silent breaking change");
    }

    @Test
    void gradlewTimeoutAcceptsPositiveValues() {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .gradlewTimeoutSeconds(1800L)
                .build();
        assertEquals(1800L, config.gradlewTimeoutSeconds());
    }

    @Test
    void gradlewTimeoutRejectsNegativeValues() {
        // Negative durations are nonsensical and in practice always
        // mean a user typo (e.g. -1 meaning "no timeout"). Rejecting
        // at builder time surfaces the typo immediately instead of
        // letting it reach ProcessBuilder where the Long gets
        // interpreted as "wait forever" or worse silently overflows
        // the TimeUnit conversion.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AffectedTestsConfig.builder().gradlewTimeoutSeconds(-1L).build());
        assertTrue(e.getMessage().toLowerCase().contains("timeout"),
                "Error must name the offending setting so the user can find it; got: " + e.getMessage());
    }

    @Test
    void actionSourceReflectsResolutionTierOrdering() {
        // Zero-config → AUTO-resolved mode default. Pinning this
        // prevents a future refactor from silently re-introducing the
        // old HARDCODED_DEFAULT tier that v2.0 removed.
        AffectedTestsConfig baseline = AffectedTestsConfig.builder().build();
        assertEquals(ActionSource.MODE_DEFAULT,
                baseline.actionSourceFor(Situation.EMPTY_DIFF));

        // Setting mode keeps unpinned situations at MODE_DEFAULT — the
        // only escape hatch left in v2.0 is an explicit onXxx setter.
        AffectedTestsConfig modeOnly = AffectedTestsConfig.builder()
                .mode(Mode.CI)
                .build();
        assertEquals(ActionSource.MODE_DEFAULT,
                modeOnly.actionSourceFor(Situation.DISCOVERY_EMPTY));

        // Explicit onXxx must win over the mode default — this is the
        // only tier that's guaranteed to survive future default changes
        // and the --explain flag has to be honest about that.
        AffectedTestsConfig explicitOverMode = AffectedTestsConfig.builder()
                .mode(Mode.STRICT)
                .onDiscoveryEmpty(Action.SKIPPED)
                .build();
        assertEquals(ActionSource.EXPLICIT,
                explicitOverMode.actionSourceFor(Situation.DISCOVERY_EMPTY));

        // DISCOVERY_SUCCESS is hardcoded SELECTED regardless of
        // configuration — that contract is reported as EXPLICIT because
        // no default can change it.
        assertEquals(ActionSource.EXPLICIT,
                baseline.actionSourceFor(Situation.DISCOVERY_SUCCESS));
    }

    @Test
    void baseRefRejectsBlank() {
        assertThrows(IllegalArgumentException.class, () ->
                AffectedTestsConfig.builder().baseRef("").build());
        assertThrows(IllegalArgumentException.class, () ->
                AffectedTestsConfig.builder().baseRef("   ").build());
    }

    @Test
    void baseRefRejectsPathTraversal() {
        assertThrows(IllegalArgumentException.class, () ->
                AffectedTestsConfig.builder().baseRef("../../etc/passwd").build());
        assertThrows(IllegalArgumentException.class, () ->
                AffectedTestsConfig.builder().baseRef("refs/../../../secrets").build());
    }

    @Test
    void baseRefRejectsControlCharsInReflogSuffix() {
        // Regression for v1.9.21 #14: the REV_EXPR pattern's reflog
        // segment is {@code @\{[^}]+\}}, which happily matches
        // newline / ESC / CSI / DEL. Pre-fix these inputs passed
        // {@link AffectedTestsConfig.Builder#isAcceptableBaseRef} and
        // then flowed unsanitised into
        //   log.info("Base ref: {}", config.baseRef())
        // inside AffectedTestsEngine and into three IllegalStateException
        // messages in GitChangeDetector — a log-forgery surface when
        // baseRef came from an attacker-controlled CI env var.
        //
        // The fix is a blanket control-char rejection at the entry gate
        // of isAcceptableBaseRef, so every downstream matcher (short
        // SHA, refname, rev-expression) refuses tainted input.
        String fakePluginStatus = "master@{1\n"
                + "\u001b[2JAffected Tests: SELECTED (DISCOVERY_SUCCESS) — 0 tests\u001b[m}";
        assertThrows(IllegalArgumentException.class,
                () -> AffectedTestsConfig.builder().baseRef(fakePluginStatus).build(),
                "baseRef containing newline + ANSI inside @{…} must be rejected at "
                        + "validation time; otherwise an attacker could forge plugin-branded "
                        + "CI status lines by poisoning CI_BASE_REF");
        assertThrows(IllegalArgumentException.class,
                () -> AffectedTestsConfig.builder().baseRef("master@{1\n}").build(),
                "Bare newline in reflog segment must also be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> AffectedTestsConfig.builder().baseRef("master@{\u001b}").build(),
                "Bare ESC in reflog segment must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> AffectedTestsConfig.builder().baseRef("master@{\u007F}").build(),
                "Bare DEL in reflog segment must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> AffectedTestsConfig.builder().baseRef("master\r\noops").build(),
                "CRLF anywhere in the baseRef must be rejected pre-regex");
    }

    @Test
    void baseRefValidationErrorDoesNotEchoRawControlChars() {
        // Regression for v1.9.21 review finding T2-1: the reject branch of
        // isAcceptableBaseRef throws IllegalArgumentException whose message
        // embeds the offending baseRef. Gradle renders that message verbatim
        // into the build log (and into build-scan HTML), so echoing raw
        // newline/ESC/CSI/DEL would reintroduce the exact log-forgery
        // surface that #14 closed on the accept path — just via the
        // validation-failure path instead of log.info("Base ref: …").
        //
        // LogSanitizer must wrap the echoed value so the thrown message
        // contains no C0, C1, or DEL characters.
        String tainted = "master@{1\n\u001b[2JAffected Tests: SELECTED\u001b[m}";
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> AffectedTestsConfig.builder().baseRef(tainted).build());
        String message = thrown.getMessage();
        assertNotNull(message, "exception must carry a diagnostic message");
        // Each forbidden byte class gets its own assertion so a regression
        // that partially re-opens the surface (say, closes newline but leaks
        // ESC) still fails loudly with a pointed message.
        assertFalse(message.indexOf('\n') >= 0,
                "baseRef validation message must not echo raw newline: " + debugEscape(message));
        assertFalse(message.indexOf('\r') >= 0,
                "baseRef validation message must not echo raw CR: " + debugEscape(message));
        assertFalse(message.indexOf('\u001b') >= 0,
                "baseRef validation message must not echo raw ESC: " + debugEscape(message));
        assertFalse(message.indexOf('\u007F') >= 0,
                "baseRef validation message must not echo raw DEL: " + debugEscape(message));
    }

    private static String debugEscape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F || (c >= 0x80 && c <= 0x9F)) {
                out.append(String.format("\\u%04X", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    @Test
    void baseRefAllowsValidRefs() {
        assertDoesNotThrow(() -> AffectedTestsConfig.builder().baseRef("origin/master").build());
        assertDoesNotThrow(() -> AffectedTestsConfig.builder().baseRef("origin/main").build());
        assertDoesNotThrow(() -> AffectedTestsConfig.builder().baseRef("abc123def456").build());
        assertDoesNotThrow(() -> AffectedTestsConfig.builder().baseRef("refs/heads/feature-branch").build());
        assertDoesNotThrow(() -> AffectedTestsConfig.builder().baseRef("HEAD~3").build());
        assertDoesNotThrow(() -> AffectedTestsConfig.builder().baseRef("HEAD").build());
        assertDoesNotThrow(() -> AffectedTestsConfig.builder().baseRef("HEAD^").build());
        assertDoesNotThrow(() -> AffectedTestsConfig.builder().baseRef("master~2").build());
        assertDoesNotThrow(() -> AffectedTestsConfig.builder().baseRef("a".repeat(40)).build());
    }

    @Test
    void baseRefRejectsRefnameSlurGarbage() {
        // Regression: the v1.x check accepted anything without a `/..` in
        // it. JGit's refname rules reject a far richer set of garbage
        // (control chars, @{} outside rev-expressions, trailing `.lock`,
        // leading `/`, etc.). Lock in that those shapes are refused at
        // the builder boundary so downstream JGit calls never see them.
        assertThrows(IllegalArgumentException.class, () ->
                AffectedTestsConfig.builder().baseRef("/leading-slash").build());
        assertThrows(IllegalArgumentException.class, () ->
                AffectedTestsConfig.builder().baseRef("trailing.lock").build());
        assertThrows(IllegalArgumentException.class, () ->
                AffectedTestsConfig.builder().baseRef("contains\nnewline").build());
        assertThrows(IllegalArgumentException.class, () ->
                AffectedTestsConfig.builder().baseRef("..").build());
    }

    @Test
    void builderRejectsNullCollections() {
        assertThrows(NullPointerException.class, () ->
                AffectedTestsConfig.builder().strategies(null).build());
        assertThrows(NullPointerException.class, () ->
                AffectedTestsConfig.builder().testSuffixes(null).build());
        assertThrows(NullPointerException.class, () ->
                AffectedTestsConfig.builder().sourceDirs(null).build());
        assertThrows(NullPointerException.class, () ->
                AffectedTestsConfig.builder().testDirs(null).build());
        assertThrows(NullPointerException.class, () ->
                AffectedTestsConfig.builder().ignorePaths(null).build());
        assertThrows(NullPointerException.class, () ->
                AffectedTestsConfig.builder().implementationNaming(null).build());
    }

    @Test
    void configIsImmutable() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();

        assertThrows(UnsupportedOperationException.class, () ->
                config.testSuffixes().add("Spec"));
        assertThrows(UnsupportedOperationException.class, () ->
                config.ignorePaths().add("foo"));
    }

    @Test
    void testTaskNamesDefaultsToSingleTestEntry() {
        // Backwards compatibility for every adopter that hasn't
        // touched the new knob: the default must be exactly
        // ['test'] so dispatch keeps emitting ':module:test' the
        // way it always has. A regression that changes the
        // default would silently re-route tests to a task that
        // doesn't exist on the consumer's project.
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        assertEquals(List.of("test"), config.testTaskNames(),
                "Default testTaskNames must be ['test'] so existing adopters' "
                        + "dispatch shape (':module:test') is preserved");
    }

    @Test
    void testTaskNamesAcceptsCustomGradleTaskNames() {
        // The happy path #48 enables: a multi-source-set adopter
        // lists the extra task names. Whitespace, separators, and
        // control characters were already covered by the
        // negative tests below; this pin asserts that ordinary
        // Gradle-conformant identifiers (camelCase, digits) round-
        // trip through the builder unchanged.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .testTaskNames(List.of("test", "integrationTest", "e2eTest", "test2"))
                .build();
        assertEquals(List.of("test", "integrationTest", "e2eTest", "test2"),
                config.testTaskNames());
    }

    @Test
    void testTaskNamesRejectsEmptyList() {
        // An empty list at config time is operator error, not a
        // silent "fall back to test". Failing fast at the builder
        // means the misconfiguration surfaces with a clear stack
        // pointing at the DSL line, instead of producing zero
        // dispatched tests at MR time (which would look like a
        // test-run pass).
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AffectedTestsConfig.builder().testTaskNames(List.of()).build());
        assertTrue(ex.getMessage().contains("at least one"),
                "Error must guide the operator toward the default. Got: " + ex.getMessage());
    }

    @Test
    void testTaskNamesRejectsNamesContainingColon() {
        // ':' is the Gradle path separator. An entry like
        // ":app:test" would forge a malformed task path on
        // dispatch (":module::app:test"). The validator catches
        // it at config time with a diagnostic that names the
        // offending character — so the operator can fix the DSL,
        // not chase a Gradle "task not found" error from a
        // garbled path two layers down.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AffectedTestsConfig.builder()
                        .testTaskNames(List.of("test", ":integrationTest"))
                        .build());
        assertTrue(ex.getMessage().contains("illegal character"),
                "Error must call out the character class. Got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(":integrationTest"),
                "Error must echo the offending entry. Got: " + ex.getMessage());
    }

    @Test
    void testTaskNamesRejectsBlankOrWhitespaceEntries() {
        // Blank or whitespace-only names would forge an empty
        // task path on dispatch. Reject them at config time so
        // the operator doesn't see a "task '' not found" error
        // from the nested Gradle invocation.
        assertThrows(IllegalArgumentException.class,
                () -> AffectedTestsConfig.builder()
                        .testTaskNames(List.of("test", ""))
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> AffectedTestsConfig.builder()
                        .testTaskNames(List.of("test", "  "))
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> AffectedTestsConfig.builder()
                        .testTaskNames(List.of("test", "integration test"))
                        .build());
    }

    @Test
    void testTaskNamesRejectsForwardSlashAndBackslash() {
        // '/' would let an attacker-planted DSL forge a path
        // outside the project. Backslash isn't a Gradle separator,
        // but rejecting it keeps the validator portable across
        // Windows and Unix and matches the same posture
        // testDirs takes.
        assertThrows(IllegalArgumentException.class,
                () -> AffectedTestsConfig.builder()
                        .testTaskNames(List.of("test", "../test"))
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> AffectedTestsConfig.builder()
                        .testTaskNames(List.of("test", "test\\evil"))
                        .build());
    }

    @Test
    void testTaskNamesIsImmutable() {
        // Defence in depth: if the caller mutates the list after
        // build(), config.testTaskNames() must not see it. A
        // mutable view would let a delayed thread (e.g. a build
        // listener) re-route dispatch on every MR.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .testTaskNames(List.of("test", "integrationTest"))
                .build();
        assertThrows(UnsupportedOperationException.class,
                () -> config.testTaskNames().add("e2eTest"));
    }

    @Test
    void effectiveModeIsAlwaysConcreteForZeroConfigCallers() {
        // Regression: pre-fix, a zero-config caller got mode()=AUTO and
        // effectiveMode()=null, despite the javadoc's "Always one of
        // LOCAL, CI or STRICT" contract. Any downstream code following
        // the documented shape (`switch(config.effectiveMode())`) would
        // NPE. The fix resolves null to the AUTO-detected mode at the
        // getter.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .baseRef("origin/master")
                .build();
        assertNotNull(config.effectiveMode(),
                "effectiveMode() must never return null — it is the documented "
                        + "input to operator-facing switches on the resolved mode");
        assertTrue(config.effectiveMode() == Mode.LOCAL
                        || config.effectiveMode() == Mode.CI
                        || config.effectiveMode() == Mode.STRICT,
                "effectiveMode() must be one of the three concrete modes");
    }
}
