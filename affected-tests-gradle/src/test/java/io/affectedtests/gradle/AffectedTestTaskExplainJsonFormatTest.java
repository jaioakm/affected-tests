package io.affectedtests.gradle;

import io.affectedtests.core.AffectedTestsEngine.AffectedTestsResult;
import io.affectedtests.core.AffectedTestsEngine.Buckets;
import io.affectedtests.core.AffectedTestsEngine.EscalationReason;
import io.affectedtests.core.config.Action;
import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.config.Mode;
import io.affectedtests.core.config.Situation;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the JSON shape of {@code affectedTest --explain --explain-format=json}.
 * Mirrors {@link AffectedTestTaskExplainFormatTest} for the text trace —
 * the renderer is a pure function over {@link AffectedTestsConfig} and
 * {@link AffectedTestsResult}, so every test calls it directly without
 * spinning up a Gradle test runtime.
 *
 * <p>Assertions are substring-based on purpose: the schema is small,
 * fixed, and string-keyed so a focussed escaper + concatenation is
 * what produces the JSON (the plugin deliberately has no Jackson /
 * Gson dependency). Substring assertions match the same convention
 * the text-format tests use and stay legible at a glance.
 */
class AffectedTestTaskExplainJsonFormatTest {

    @Test
    void emitsSchemaVersionAndBaseRefAndModeBlock() {
        // The schema-version field is the contract that future
        // additions are additive — without it, dashboard consumers
        // can't tell whether they're parsing a v1 trace or a v2
        // trace. Pin it as the first thing we assert so a regression
        // in the version field blows up loudly here.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .baseRef("origin/master")
                .mode(Mode.CI)
                .build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of(), Set.of(), Set.of(),
                Buckets.empty(),
                false, true,
                Situation.EMPTY_DIFF,
                Action.SKIPPED,
                EscalationReason.NONE);

        String json = AffectedTestTask.renderExplainJson(config, result, Map.of());

        assertTrue(json.contains("\"version\":3"),
                "Schema version must be present and pinned at v3 — "
                        + "issue #42 bumped v1 → v2 to add the discovery "
                        + "block; issue #132 bumped v2 → v3 to add the "
                        + "additive headerEdges block. All earlier fields "
                        + "remain present. Consumers need the version to "
                        + "detect future additions. Got: " + json);
        assertTrue(json.contains("\"baseRef\":\"origin/master\""),
                "Base ref must be carried so dashboards can correlate "
                        + "traces against the right merge target. Got: " + json);
        assertTrue(json.contains("\"mode\":{\"configured\":\"CI\",\"effective\":\"CI\"}"),
                "Mode block must report both configured + effective so "
                        + "the AUTO -> CI / AUTO -> LOCAL routing is "
                        + "visible to dashboards (the same way the text "
                        + "trace's 'Mode: AUTO (effective: CI)' line is). "
                        + "Got: " + json);
    }

    @Test
    void bucketCountsAndSamplesMirrorTheTextTrace() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        Buckets buckets = new Buckets(
                Set.of("README.md"),
                Set.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of(),
                Set.of("build.gradle", "application.yml"));
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("README.md", "src/main/java/com/example/Foo.java",
                        "build.gradle", "application.yml"),
                Set.of("com.example.Foo"), Set.of(),
                buckets,
                true, false,
                Situation.UNMAPPED_FILE,
                Action.FULL_SUITE,
                EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE);

        String json = AffectedTestTask.renderExplainJson(config, result, Map.of());

        assertTrue(json.contains("\"buckets\":{\"ignored\":1,\"outOfScope\":0,"
                        + "\"production\":1,\"test\":0,\"unmapped\":2}"),
                "Bucket counts must surface every bucket — five fixed "
                        + "fields, fixed order — so dashboards can aggregate "
                        + "without branching on optional keys. Got: " + json);
        assertTrue(json.contains("\"samples\":{\"ignored\":[\"README.md\"],"),
                "Non-empty buckets must produce a sample array. Got: " + json);
        assertTrue(json.contains("\"production\":[\"src/main/java/com/example/Foo.java\"]"),
                "Production sample must be present. Got: " + json);
        assertTrue(json.contains("\"unmapped\":[\"application.yml\",\"build.gradle\"]"),
                "Unmapped sample must be sorted (matches the text trace's "
                        + "deterministic ordering for grep stability). Got: " + json);
        assertFalse(json.contains("\"outOfScope\":[]"),
                "Empty buckets must NOT produce an empty sample array — "
                        + "payload bloat for no signal. The five-field bucket "
                        + "count block already tells consumers which buckets "
                        + "are empty. Got: " + json);
    }

    @Test
    void situationAndActionAndOutcomeAreDecomposedNotStringified() {
        // The text trace's "Outcome:" line stringifies action + reason
        // into one line. Dashboards then have to regex-parse it back
        // out — exactly the brittleness #53 addresses. The JSON
        // schema decomposes those fields up front so consumers can
        // group/aggregate without parsing.
        AffectedTestsConfig config = AffectedTestsConfig.builder().mode(Mode.CI).build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("build.gradle"),
                Set.of(), Set.of(),
                new Buckets(Set.of(), Set.of(), Set.of(), Set.of(),
                        Set.of("build.gradle")),
                true, false,
                Situation.UNMAPPED_FILE,
                Action.FULL_SUITE,
                EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE);

        String json = AffectedTestTask.renderExplainJson(config, result, Map.of());

        assertTrue(json.contains("\"situation\":\"UNMAPPED_FILE\""),
                "Situation must be a top-level field. Got: " + json);
        assertTrue(json.contains("\"action\":{\"name\":\"FULL_SUITE\","),
                "Action name lives in the action block, not stringified "
                        + "into an outcome line. Got: " + json);
        assertTrue(json.contains("\"source\":\"MODE_DEFAULT\""),
                "Action source must surface so dashboards can show "
                        + "'this came from a mode default vs. an explicit "
                        + "knob'. Got: " + json);
        assertTrue(json.contains("\"outcome\":{\"kind\":\"FULL_SUITE\","),
                "Outcome kind must be a separate field, not stringified. "
                        + "Got: " + json);
        assertTrue(json.contains("\"escalationReason\":\"RUN_ALL_ON_NON_JAVA_CHANGE\""),
                "Escalation reason must surface as a separate field — "
                        + "this is the single biggest dashboard-blocker on "
                        + "the text trace. Got: " + json);
    }

    @Test
    void modulesArrayIsPresentEvenWhenEmptySoConsumersCanIterateUnconditionally() {
        // Schema totality: every field always present means consumers
        // never have to null-check / branch. An empty array is still
        // a usable array; an absent field is a special case every
        // consumer has to handle.
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of(), Set.of(), Set.of(),
                Buckets.empty(),
                false, true,
                Situation.EMPTY_DIFF,
                Action.SKIPPED,
                EscalationReason.NONE);

        String json = AffectedTestTask.renderExplainJson(config, result, Map.of());

        assertTrue(json.contains("\"modules\":[]"),
                "Modules must be an empty array on non-SELECTED runs, "
                        + "not absent — schema totality. Got: " + json);
    }

    @Test
    void modulesArrayCarriesTaskAndFqnsForSelectedRuns() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of("com.example.FooTest", "com.example.BarTest"),
                Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"), Set.of(),
                new Buckets(Set.of(), Set.of(),
                        Set.of("src/main/java/com/example/Foo.java"),
                        Set.of(), Set.of()),
                false, false,
                Situation.DISCOVERY_SUCCESS,
                Action.SELECTED,
                EscalationReason.NONE);
        Map<String, List<String>> moduleGroups = new LinkedHashMap<>();
        moduleGroups.put(":application:test",
                List.of("com.example.FooTest", "com.example.BarTest"));

        String json = AffectedTestTask.renderExplainJson(config, result, moduleGroups);

        assertTrue(json.contains("\"modules\":[{\"task\":\":application:test\","),
                "Modules entry must carry the colon-prefixed Gradle "
                        + "task path so dashboards can dispatch the same "
                        + "task the build will. Got: " + json);
        assertTrue(json.contains("\"fqns\":[\"com.example.FooTest\",\"com.example.BarTest\"]"),
                "FQN list must be carried verbatim, no truncation, "
                        + "no sample cap — modules block is for "
                        + "actionable downstream routing, not preview. "
                        + "Got: " + json);
        assertTrue(json.contains("\"selectedClassCount\":2"),
                "Outcome must carry a selectedClassCount that matches "
                        + "the FQN list size. Got: " + json);
    }

    @Test
    void actionMatrixCarriesEverySituationWithSourceTransparency() {
        // The action matrix is the single most powerful debugging
        // aid in the text trace ("why did my explicit setting not
        // win?") — the JSON output must carry it too. Pin every
        // situation key so a future engine that adds a new situation
        // also has to update this test, which forces the renderer to
        // stay in sync.
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of(), Set.of(), Set.of(),
                Buckets.empty(),
                false, true,
                Situation.EMPTY_DIFF,
                Action.SKIPPED,
                EscalationReason.NONE);

        String json = AffectedTestTask.renderExplainJson(config, result, Map.of());

        for (Situation s : Situation.values()) {
            assertTrue(json.contains("\"" + s.name() + "\":{\"action\":\""),
                    "Action matrix must carry an entry for every "
                            + "Situation enum value (missing: " + s + "). "
                            + "Got: " + json);
        }
    }

    @Test
    void resolveExplainFormatDefaultsToTextWhenUnset() {
        assertEquals("text", AffectedTestTask.resolveExplainFormat(null));
        assertEquals("text", AffectedTestTask.resolveExplainFormat(""));
        assertEquals("text", AffectedTestTask.resolveExplainFormat("  "));
    }

    @Test
    void resolveExplainFormatNormalisesCaseAndAccepts() {
        assertEquals("text", AffectedTestTask.resolveExplainFormat("TEXT"));
        assertEquals("json", AffectedTestTask.resolveExplainFormat("Json"));
        assertEquals("json", AffectedTestTask.resolveExplainFormat("  json  "));
    }

    @Test
    void resolveExplainFormatRejectsUnknownValuesLoudly() {
        // Silently falling back to text would let a typo's
        // dashboard pipeline silently never receive JSON. Surface
        // the typo as a build failure so the operator sees it
        // immediately.
        GradleException ex = assertThrows(GradleException.class,
                () -> AffectedTestTask.resolveExplainFormat("yaml"));
        assertTrue(ex.getMessage().contains("Unsupported --explain-format"),
                "Error must name the offending option. Got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("'text'") && ex.getMessage().contains("'json'"),
                "Error must list the supported values so the operator "
                        + "doesn't have to read the source. Got: " + ex.getMessage());
    }

    @Test
    void jsonAddsDiscoveryBlockWithParallelMetadataAndPerStrategyArray() {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .baseRef("origin/master").mode(Mode.LOCAL).build();
        Buckets buckets = new Buckets(Set.of(), Set.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of(), Set.of());
        io.affectedtests.core.discovery.DiscoveryProfile profile =
                new io.affectedtests.core.discovery.DiscoveryProfile(
                        true, 4,
                        java.time.Duration.ofMillis(13),
                        java.util.Map.of(
                                "naming",     java.time.Duration.ofMillis(2),
                                "transitive", java.time.Duration.ofMillis(11)),
                        java.util.Map.of("naming", 2, "transitive", 3));
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of("com.example.FooTest"), Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"), Set.of(),
                buckets,
                false, false,
                Situation.DISCOVERY_SUCCESS, Action.SELECTED,
                EscalationReason.NONE,
                Map.of(),
                profile);

        String json = AffectedTestTask.renderExplainJson(config, result, Map.of());

        assertTrue(json.contains("\"discovery\":{\"parallelEnabled\":true,\"concurrencyLevel\":4"),
                "Discovery block must carry parallelEnabled (boolean) and "
                        + "concurrencyLevel (number). Got: " + json);
        assertTrue(json.contains("\"totalMillis\":13"),
                "Total wall time must be present so dashboards can chart "
                        + "discovery latency over time. Got: " + json);
        assertTrue(json.contains("\"perStrategy\":["),
                "Per-strategy array must be a JSON array (not an object) "
                        + "so consumers can iterate without converting key "
                        + "ordering across runs. Got: " + json);
        assertTrue(json.contains("\"name\":\"naming\",\"millis\":2,\"tests\":2"),
                "Per-strategy entry must carry name + ms + test count. Got: " + json);
    }

    @Test
    void jsonDiscoveryBlockIsAlwaysPresentEvenOnEmptyProfile() {
        // Total schema: a consumer iterating .discovery.perStrategy
        // must not have to null-check the discovery field on
        // empty-diff runs. We always emit it; an empty profile
        // shows up as an empty array — never absent.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .baseRef("origin/master").mode(Mode.LOCAL).build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of(), Set.of(), Set.of(),
                Buckets.empty(),
                false, true,
                Situation.EMPTY_DIFF, Action.SKIPPED,
                EscalationReason.NONE);

        String json = AffectedTestTask.renderExplainJson(config, result, Map.of());

        assertTrue(json.contains("\"discovery\":{\"parallelEnabled\":false,\"concurrencyLevel\":0"),
                "Empty-profile JSON must still carry the discovery block "
                        + "with default-zero values. Got: " + json);
        assertTrue(json.contains("\"perStrategy\":[]"),
                "Empty-profile perStrategy must be an empty array, not absent. "
                        + "Got: " + json);
    }

    @Test
    void jsonEscapeHandlesQuotesAndBackslashAndControlCharacters() {
        // The strings flowing into the JSON come from filenames and
        // FQNs derived from a (potentially attacker-influenced) MR
        // tree. A bare unsanitised newline in a sample path would
        // terminate the JSON line and let an attacker forge a fake
        // trace into a dashboard pipeline. Pin every escape
        // explicitly.
        assertEquals("\\\"", AffectedTestTask.jsonEscape("\""));
        assertEquals("\\\\", AffectedTestTask.jsonEscape("\\"));
        assertEquals("\\n", AffectedTestTask.jsonEscape("\n"));
        assertEquals("\\r", AffectedTestTask.jsonEscape("\r"));
        assertEquals("\\t", AffectedTestTask.jsonEscape("\t"));
        assertEquals("\\b", AffectedTestTask.jsonEscape("\b"));
        assertEquals("\\f", AffectedTestTask.jsonEscape("\f"));
        // Below 0x20 control character: NUL.
        assertEquals("\\u0000", AffectedTestTask.jsonEscape("\u0000"));
        // Forward slash is NOT required to be escaped by RFC 8259
        // and we don't, on purpose — it'd be churn for parsers
        // that strict-equality-compare unescaped strings.
        assertEquals("a/b", AffectedTestTask.jsonEscape("a/b"));
        // Round-trip a typical FQN: should pass through unchanged.
        assertEquals("com.example.FooTest",
                AffectedTestTask.jsonEscape("com.example.FooTest"));
    }
}
