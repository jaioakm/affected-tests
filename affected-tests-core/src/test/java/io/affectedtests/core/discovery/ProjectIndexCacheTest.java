package io.affectedtests.core.discovery;

import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.config.Mode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Persistence behaviour of {@link ProjectIndexCache} (issue #41).
 *
 * <p>Each test follows the same shape: write a tree, build an index
 * to populate the cache, then mutate something (or nothing) and
 * build again. The second build's behaviour — cache hit vs. cache
 * miss — is the assertion. Equivalence between cache-hit results
 * and cache-miss results is what makes the cache safe to ship as
 * always-on; the four invalidation tests pin the safety contract
 * (config drift, file added, file removed, scan-root mtime drift)
 * so a future change cannot silently widen the validity window
 * and serve stale data.
 */
class ProjectIndexCacheTest {

    @TempDir
    Path projectDir;

    private static final AffectedTestsConfig BASE_CONFIG = AffectedTestsConfig.builder()
            .mode(Mode.CI)
            .build();

    @Test
    void freshBuildPersistsSnapshotAndSecondBuildLoadsIt() throws Exception {
        writeJava(projectDir.resolve("src/main/java/com/example/Foo.java"),
                "package com.example; public class Foo {}");
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");

        ProjectIndex first = ProjectIndex.build(projectDir, BASE_CONFIG);

        Path snapshot = projectDir.resolve("build/affected-tests/index/v1/snapshot.tsv");
        assertTrue(Files.isRegularFile(snapshot),
                "First build must persist the snapshot file — without it the cache is "
                        + "a no-op and issue #41's < 100ms warm path never materialises");

        ProjectIndex second = ProjectIndex.build(projectDir, BASE_CONFIG);

        // Equivalence is the safety contract: a cache hit must
        // produce the same observable index as a cache miss would
        // have produced. Test FQNs and source FQNs are the two
        // collections every downstream strategy reads.
        assertEquals(first.testFqns(), second.testFqns(),
                "Cache-loaded index must expose the same testFqns as the original build");
        assertEquals(first.sourceFqns(), second.sourceFqns(),
                "Cache-loaded index must expose the same sourceFqns as the original build");
        assertEquals(first.testFiles().size(), second.testFiles().size());
        assertEquals(first.sourceFiles().size(), second.sourceFiles().size());
    }

    @Test
    void configChangeInvalidatesCache() throws Exception {
        // Two test classes; one normal, one under api-test/**. With
        // outOfScopeTestDirs=['api-test/**'] the dispatch map drops
        // ApiFoo. Building first with the OOS rule → 1 test FQN
        // (FooTest). Then rebuild with the OOS rule REMOVED. The
        // cache key (configHash) covers outOfScopeTestDirs, so the
        // second build must take the miss path and surface ApiFoo.
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");
        writeJava(projectDir.resolve("api-test/src/test/java/com/example/ApiFoo.java"),
                "package com.example; public class ApiFoo {}");

        AffectedTestsConfig narrow = AffectedTestsConfig.builder()
                .mode(Mode.CI)
                .testDirs(List.of("src/test/java", "api-test/src/test/java"))
                .outOfScopeTestDirs(List.of("api-test/**"))
                .build();

        ProjectIndex original = ProjectIndex.build(projectDir, narrow);
        assertFalse(original.testFqns().contains("com.example.ApiFoo"),
                "Sanity: out-of-scope FQN must be filtered on first build");

        AffectedTestsConfig wider = AffectedTestsConfig.builder()
                .mode(Mode.CI)
                .testDirs(List.of("src/test/java", "api-test/src/test/java"))
                // outOfScopeTestDirs intentionally cleared
                .build();

        ProjectIndex broadened = ProjectIndex.build(projectDir, wider);
        assertTrue(broadened.testFqns().contains("com.example.ApiFoo"),
                "Removing outOfScopeTestDirs must invalidate the cached snapshot — "
                        + "otherwise an adopter who broadens scope sees the cache "
                        + "serving the narrower view forever and has to nuke build/ by hand");
    }

    @Test
    void newFileInExistingScanRootInvalidatesCache() throws Exception {
        // Adding a file changes the scan root's mtime AND its child
        // count, so the verifyDirs gate must reject the cache. This
        // is the dominant real-world invalidation shape — adopters
        // adding tests, NOT changing config — so it has to work
        // first try.
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");

        ProjectIndex first = ProjectIndex.build(projectDir, BASE_CONFIG);
        assertEquals(Set.of("com.example.FooTest"), first.testFqns());

        // Sleep to outrun the 1s-resolution mtime on some
        // filesystems, otherwise a same-second add can slip through
        // the mtime gate and the test depends on the child-count
        // belt-and-braces alone.
        Thread.sleep(1100);
        writeJava(projectDir.resolve("src/test/java/com/example/BarTest.java"),
                "package com.example; public class BarTest {}");

        ProjectIndex second = ProjectIndex.build(projectDir, BASE_CONFIG);
        assertTrue(second.testFqns().contains("com.example.BarTest"),
                "A newly-added test under an existing scan root must be picked up — the "
                        + "cache cannot be the reason a freshly-written test fails to run");
        assertEquals(2, second.testFqns().size());
    }

    @Test
    void deletedFileInExistingScanRootInvalidatesCache() throws Exception {
        Path foo = projectDir.resolve("src/test/java/com/example/FooTest.java");
        Path bar = projectDir.resolve("src/test/java/com/example/BarTest.java");
        writeJava(foo, "package com.example; public class FooTest {}");
        writeJava(bar, "package com.example; public class BarTest {}");

        ProjectIndex first = ProjectIndex.build(projectDir, BASE_CONFIG);
        assertEquals(2, first.testFqns().size(), "Sanity: both tests indexed");

        Thread.sleep(1100);
        Files.delete(bar);

        ProjectIndex second = ProjectIndex.build(projectDir, BASE_CONFIG);
        assertEquals(Set.of("com.example.FooTest"), second.testFqns(),
                "Removing a test must drop it from the next index build — a stale cache "
                        + "would dispatch a non-existent FQN and the consumer sees a "
                        + "Gradle 'no tests found for given includes' failure");
    }

    @Test
    void cacheClearWipesSnapshot() throws Exception {
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");

        ProjectIndex.build(projectDir, BASE_CONFIG);
        Path snapshot = projectDir.resolve("build/affected-tests/index/v1/snapshot.tsv");
        assertTrue(Files.isRegularFile(snapshot), "Sanity: build wrote the snapshot");

        ProjectIndexCache.clear(projectDir);
        assertFalse(Files.exists(snapshot),
                "clear() is the documented escape hatch — if it left the snapshot in "
                        + "place, support advice 'clear the cache' would be hollow");
    }

    @Test
    void corruptSnapshotFallsBackToFullBuild() throws Exception {
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");

        // Pre-seed a malformed snapshot. The cache layer must treat
        // any read failure as "miss" rather than propagating an IO
        // exception out of ProjectIndex.build — adopters whose
        // build/ got corrupted by a previous Gradle daemon crash
        // should NOT see affectedTest start failing.
        Path cacheDir = projectDir.resolve("build/affected-tests/index/v1");
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("snapshot.tsv"),
                "this\tis\tnot\ta\tvalid\tsnapshot\nrandom garbage on the second line\n");

        ProjectIndex index = assertDoesNotThrow(() -> ProjectIndex.build(projectDir, BASE_CONFIG),
                "Corrupt snapshot must not surface as an exception — the cache is a perf "
                        + "tier, not a correctness requirement");
        assertEquals(Set.of("com.example.FooTest"), index.testFqns(),
                "After falling back to a full build, the index content must be exactly "
                        + "what a fresh-tree build would have produced");

        // Also: the corrupt snapshot must have been REPLACED by a
        // valid one, otherwise every subsequent run pays the
        // fallback cost forever.
        Path snapshot = cacheDir.resolve("snapshot.tsv");
        assertTrue(Files.isRegularFile(snapshot));
        String contents = Files.readString(snapshot);
        assertTrue(contents.contains("# affected-tests project-index snapshot"),
                "Fallback build must overwrite the corrupt snapshot so the next run "
                        + "takes the fast path again — otherwise the cache fails open "
                        + "permanently after a single corruption event");
    }

    @Test
    void schemaVersionMismatchInvalidatesCache() throws Exception {
        // Pre-seed a snapshot that claims schema v0 (i.e. older
        // than the current). The cache must reject it and rebuild
        // — without this, a forward-incompatible bump (whenever we
        // ship one) would silently serve old-schema data through
        // new-schema readers.
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");

        Path cacheDir = projectDir.resolve("build/affected-tests/index/v1");
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("snapshot.tsv"),
                "v\t0\ncfg\tdoesnotmatter\n");

        ProjectIndex index = ProjectIndex.build(projectDir, BASE_CONFIG);
        assertEquals(Set.of("com.example.FooTest"), index.testFqns());
    }

    @Test
    void prePhase2SchemaSnapshotInvalidatesAndForcesFullRescan() throws Exception {
        // Phase 2 PR #1 of issue #76: bumped SCHEMA_VERSION 2 → 3
        // because configHash() does not include the file-extension
        // scope the scanner walks; on a mixed Java + Kotlin project,
        // a pre-PR-1 snapshot's testFqn universe is missing every
        // Kotlin test FQN, but the configHash is identical pre/post
        // upgrade.
        //
        // The schema bump is the only mechanism that guarantees a
        // clean rescan on warm caches across the upgrade boundary.
        // Verify by hand-writing a v=2 snapshot whose contents
        // claim a stale view of the project, then assert the new
        // build invalidates it and produces fresh data.
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");
        Files.createDirectories(projectDir.resolve("src/test/java/com/example"));
        Files.writeString(projectDir.resolve("src/test/java/com/example/BarTest.kt"),
                "package com.example\nclass BarTest");

        Path cacheDir = projectDir.resolve("build/affected-tests/index/v1");
        Files.createDirectories(cacheDir);
        // Hand-rolled v=2 snapshot. The contents are deliberately
        // wrong (no test FQN entries) — the schemaVersion check is
        // the load-bearing rejection signal we want to assert. If
        // the schema check were permissive, the build would adopt
        // these stale contents and downstream strategies would
        // silently under-select.
        Files.writeString(cacheDir.resolve("snapshot.tsv"),
                "v\t2\ncfg\twillbeoverwritten\n");

        ProjectIndex index = ProjectIndex.build(projectDir, BASE_CONFIG);

        assertTrue(index.testFqns().contains("com.example.FooTest"),
                "Java test FQN must surface from the rescan even when a "
                        + "stale v=2 snapshot exists. Got: " + index.testFqns());
        assertTrue(index.testFqns().contains("com.example.BarTest"),
                "Kotlin test FQN must surface from the rescan — this is "
                        + "the exact case the schema bump exists to catch on "
                        + "the upgrade boundary. Got: " + index.testFqns());
    }

    @Test
    void kotlinEnabledFlagFlipInvalidatesCache() throws Exception {
        // Phase 2 PR #3 of issue #76: the rollout flag
        // {@link AffectedTestsConfig#kotlinEnabled()} feeds into
        // {@link ProjectIndexCache#configHash} so a flag flip
        // (off → on or on → off) forces a cache miss. Without this,
        // the file-extension scope the scanner walks would be
        // unchanged across the flip but the parser-side dispatch
        // ({@link LanguageParsers#forConfig}) would suddenly route
        // {@code .kt} files through the AST extractor — and the
        // strategies' match keys would change overnight while the
        // cache served pre-AST data.
        //
        // Asserting the configHash itself differs is the cheapest
        // pin: the load path's body is heavily exercised by the
        // other tests in this file. If the hash changes, a future
        // refactor that accidentally drops the kotlinEnabled bit
        // from the configHash mix goes red here, not as a
        // mysterious behavioural drift on adopter sites.
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");

        AffectedTestsConfig flagOff = AffectedTestsConfig.builder()
                .mode(Mode.CI)
                .kotlinEnabled(false)
                .build();
        AffectedTestsConfig flagOn = AffectedTestsConfig.builder()
                .mode(Mode.CI)
                .kotlinEnabled(true)
                .build();

        String hashOff = ProjectIndexCache.configHash(flagOff);
        String hashOn = ProjectIndexCache.configHash(flagOn);
        assertNotEquals(hashOff, hashOn,
                "configHash must differ across kotlinEnabled flips. "
                        + "Identical hashes would cause a warm cache to "
                        + "serve pre-flip metadata after the flag flips, "
                        + "which is the silent-stale-data class of bug "
                        + "PR #3's 'fail-closed when escalation is needed' "
                        + "posture is meant to catch.");

        // End-to-end behavioural check: build with flagOff (cache
        // populated under that hash), then build with flagOn — the
        // second build must take the miss path because configHash
        // changed.
        ProjectIndex.build(projectDir, flagOff);
        Path snapshot = projectDir.resolve("build/affected-tests/index/v1/snapshot.tsv");
        assertTrue(Files.isRegularFile(snapshot));
        FileTime initialMtime = Files.getLastModifiedTime(snapshot);

        // Sleep just long enough that filesystems with second-resolution
        // mtime can record a different timestamp on the rewrite. The
        // existing `configChangeInvalidatesCache` test uses the same
        // posture; mirror it.
        Thread.sleep(1100);

        ProjectIndex.build(projectDir, flagOn);
        FileTime afterMtime = Files.getLastModifiedTime(snapshot);
        assertNotEquals(initialMtime, afterMtime,
                "Snapshot mtime must advance: kotlinEnabled flip caused a "
                        + "cache miss → fresh build → snapshot rewrite. If "
                        + "mtime is unchanged the configHash branch is not "
                        + "rejecting the warm cache.");
    }

    @Test
    void prePr3SchemaSnapshotInvalidatesAndForcesFullRescan() throws Exception {
        // Phase 2 PR #3 of issue #76: bumped SCHEMA_VERSION 3 → 4 because
        // FileMetadataRow's serialised shape is about to start carrying
        // Kotlin-AST-derived fields (the `kotlinEnabled` flag in
        // {@link AffectedTestsConfig#configHash()} also feeds into the
        // hash, so flipping the flag forces a separate cache miss). A
        // forward-incompatible row shape served through a pre-PR-3
        // reader would surface as parse exceptions during
        // {@link ProjectIndexCache.FileMetadataRow#parse} or — worse —
        // silently mis-typed metadata that flows into Usage /
        // Implementation / Transitive's match keys.
        //
        // The rescan path is the only mechanism that guarantees the
        // post-PR-3 reader never sees pre-PR-3 row data on warm caches
        // across the upgrade boundary. This test hand-writes a v=3
        // snapshot whose contents are deliberately wrong (no test FQN
        // entries) and asserts the {@link ProjectIndexCache#tryLoad}
        // schemaVersion check is the load-bearing rejection signal —
        // not the dir-fingerprint check or the configHash check or the
        // FileMetadataRow.parse step further down.
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");
        Files.createDirectories(projectDir.resolve("src/test/java/com/example"));
        Files.writeString(projectDir.resolve("src/test/java/com/example/BarTest.kt"),
                "package com.example\nclass BarTest");

        Path cacheDir = projectDir.resolve("build/affected-tests/index/v1");
        Files.createDirectories(cacheDir);
        // Hand-rolled v=3 snapshot. As with the pre-PR-1 test above,
        // the body is deliberately stale (no testFqn entries); a
        // permissive schema check would adopt this and downstream
        // strategies would silently under-select FooTest + BarTest.
        Files.writeString(cacheDir.resolve("snapshot.tsv"),
                "v\t3\ncfg\twillbeoverwritten\n");

        ProjectIndex index = ProjectIndex.build(projectDir, BASE_CONFIG);

        assertTrue(index.testFqns().contains("com.example.FooTest"),
                "Java test FQN must surface from the rescan when a stale "
                        + "v=3 snapshot exists. The schemaVersion check at "
                        + "ProjectIndexCache.tryLoad must reject the v=3 "
                        + "snapshot before the load path even attempts to "
                        + "parse the (deliberately stale) body. Got: "
                        + index.testFqns());
        assertTrue(index.testFqns().contains("com.example.BarTest"),
                "Kotlin test FQN must surface from the rescan — pre-PR-3 "
                        + "→ post-PR-3 upgrade with a warm cache is exactly "
                        + "the scenario the schema bump exists to catch. "
                        + "Got: " + index.testFqns());
    }

    @Test
    void postUpgradeFutureSchemaSnapshotInvalidatesAndForcesFullRescan() throws Exception {
        // Symmetric direction of the schema-version safety check at
        // {@link ProjectIndexCache#tryLoad}: a v=N+1 snapshot
        // (post-upgrade plugin produced a future schema, then the
        // CI matrix downgraded back to the current version, e.g.
        // during a rolling rollout) must also be rejected. The
        // existing pre-PR-1 / pre-Phase-2 / pre-PR-3 tests cover
        // the too-old direction; without this companion the
        // schema check could silently regress to a one-sided
        // {@code <} comparison and accept a future-shaped row
        // shape through a current-shaped reader — exactly the
        // silent-stale-data class of bug the schema bump exists
        // to prevent. The early-out in {@link Snapshot#read}
        // also shoulders the diagnostic-quality contract: a v=5
        // body whose rows are wire-incompatible with v=4 surfaces
        // as a clean "schemaVersion mismatch" log line rather
        // than as "failed to read snapshot".
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");

        Path cacheDir = projectDir.resolve("build/affected-tests/index/v1");
        Files.createDirectories(cacheDir);
        // Hand-rolled v=7 snapshot. Body is deliberately stale (no
        // testFqn entries); a permissive {@code <} check would
        // adopt this and downstream strategies would silently
        // under-select FooTest. v=7 chosen because v=6 is now the
        // current schema (bumped in issue #132's nested-decl FQN
        // follow-up for the QName disambiguator) — the next forward
        // bump will rotate this canary to v=8 + the bump's body
        // description.
        Files.writeString(cacheDir.resolve("snapshot.tsv"),
                "v\t7\ncfg\twillbeoverwritten\n");

        ProjectIndex index = ProjectIndex.build(projectDir, BASE_CONFIG);

        assertTrue(index.testFqns().contains("com.example.FooTest"),
                "Test FQN must surface from the rescan when a v=7 "
                        + "future-schema snapshot exists. The schemaVersion "
                        + "check at tryLoad must reject the v=7 snapshot — "
                        + "downgrade across a CI matrix boundary cannot "
                        + "feed future-shaped rows to a current-shaped "
                        + "reader. Got: " + index.testFqns());
    }

    @Test
    void prePr5SchemaSnapshotInvalidatesAndForcesFullRescan() throws Exception {
        // Issue #132: bumped SCHEMA_VERSION 4 → 5 (HeaderEdges column
        // added to FileMetadataRow) and then 5 → 6 (nested-decl FQN
        // follow-up: qualified-name disambiguator added to the decls
        // column; header-edges key switched from simpleName to
        // qualifiedName). A v=4 OR v=5 snapshot's rows are
        // wire-incompatible with the current reader; a permissive
        // schema check would adopt the stale rows and downstream
        // strategies would silently see either no header edges or
        // mis-keyed header edges, producing a "headerEdges
        // mis-attributes on warm caches but works on cold caches"
        // silent bug class on the upgrade boundary. Pin the rejection.
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");

        Path cacheDir = projectDir.resolve("build/affected-tests/index/v1");
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("snapshot.tsv"),
                "v\t5\ncfg\twillbeoverwritten\n");

        ProjectIndex index = ProjectIndex.build(projectDir, BASE_CONFIG);

        assertTrue(index.testFqns().contains("com.example.FooTest"),
                "Test FQN must surface from the rescan when a stale v=5 "
                        + "snapshot exists. The schemaVersion check at "
                        + "ProjectIndexCache.tryLoad must reject the v=5 "
                        + "snapshot before the load path attempts to parse "
                        + "rows whose decl column is wire-incompatible with "
                        + "the v=6 QName disambiguator shape. Got: "
                        + index.testFqns());
    }

    @Test
    void headerEdgesKnobsContributeToConfigHash() {
        // Issue #132: every new DSL knob must flow into
        // {@link ProjectIndexCache#configHash} so an adopter
        // flipping the knob across consecutive runs forces a clean
        // rescan rather than reusing rows the new flag value would
        // not produce. Without this, the cache would serve cold-side
        // augmentation results for a warm side that produces a
        // different augmentation set, which is the exact silent-
        // stale-data class of bug the configHash check exists to
        // catch.
        AffectedTestsConfig.Builder base = AffectedTestsConfig.builder()
                .mode(Mode.CI);

        String hashBaseline = ProjectIndexCache.configHash(base.build());

        // headerEdgesEnabled flip
        String hashKillSwitch = ProjectIndexCache.configHash(
                AffectedTestsConfig.builder().mode(Mode.CI)
                        .headerEdgesEnabled(false).build());
        assertNotEquals(hashBaseline, hashKillSwitch,
                "headerEdgesEnabled flip must change the configHash — "
                        + "warm cache must be invalidated when the kill switch flips");

        // headerEdgesDepth change
        String hashDepth = ProjectIndexCache.configHash(
                AffectedTestsConfig.builder().mode(Mode.CI)
                        .headerEdgesDepth(2).build());
        assertNotEquals(hashBaseline, hashDepth,
                "headerEdgesDepth must contribute to configHash — "
                        + "different depth produces different augmentation sets");

        // headerEdgesMaxSiblings change
        String hashCap = ProjectIndexCache.configHash(
                AffectedTestsConfig.builder().mode(Mode.CI)
                        .headerEdgesMaxSiblings(99).build());
        assertNotEquals(hashBaseline, hashCap,
                "headerEdgesMaxSiblings must contribute to configHash — "
                        + "different cap produces different suppression sets");

        // headerEdgesExclude change
        String hashExclude = ProjectIndexCache.configHash(
                AffectedTestsConfig.builder().mode(Mode.CI)
                        .headerEdgesExclude(Set.of(
                                AffectedTestsConfig.HEADER_EDGE_ANNOTATIONS)).build());
        assertNotEquals(hashBaseline, hashExclude,
                "headerEdgesExclude must contribute to configHash — "
                        + "different excluded categories produce different augmentation sets");

        // headerEdgesIgnore change
        String hashIgnore = ProjectIndexCache.configHash(
                AffectedTestsConfig.builder().mode(Mode.CI)
                        .headerEdgesIgnore(java.util.List.of("com.adopter.**")).build());
        assertNotEquals(hashBaseline, hashIgnore,
                "headerEdgesIgnore must contribute to configHash — "
                        + "different ignore-glob list produces different "
                        + "filtered edge sets");
    }

    @Test
    void headerEdgesPersistAndReloadFromWarmCache() throws Exception {
        // End-to-end round-trip: a cold build extracts HeaderEdges
        // into {@link FileMetadata.TypeDecl}; a warm build must
        // reload the same shape from the on-disk snapshot. Without
        // this, the encode-side could silently truncate or the
        // decode-side could fall back to {@link
        // FileMetadata.HeaderEdges#EMPTY} on every reload — making
        // the headerEdges strategy behave correctly on cold caches
        // and wrong on every subsequent run, which is the worst
        // possible failure shape (silent + intermittent).
        writeJava(projectDir.resolve("src/main/java/com/example/Iface.java"),
                "package com.example; public interface Iface {}");
        writeJava(projectDir.resolve("src/main/java/com/example/Svc.java"),
                "package com.example;\n"
                        + "@SuppressWarnings(\"unused\")\n"
                        + "public class Svc<T extends Iface> implements Iface {}");

        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .mode(Mode.CI)
                .build();

        // Cold build — writes the snapshot.
        ProjectIndex cold = ProjectIndex.build(projectDir, config);
        Path svcPath = projectDir.resolve("src/main/java/com/example/Svc.java");
        FileMetadata coldMd = cold.fileMetadata(svcPath);
        assertNotNull(coldMd, "cold metadata must be present");
        FileMetadata.TypeDecl coldSvc = coldMd.typeDeclarations().stream()
                .filter(d -> d.simpleName().equals("Svc"))
                .findFirst().orElseThrow();
        assertEquals(java.util.List.of("Iface"),
                coldSvc.headerEdges().implementsSimpleNames(),
                "cold extraction must populate implements category");
        assertEquals(java.util.List.of("Iface"),
                coldSvc.headerEdges().typeBoundSimpleNames(),
                "cold extraction must populate type-bound category");
        assertEquals(java.util.List.of("SuppressWarnings"),
                coldSvc.headerEdges().annotationSimpleNames(),
                "cold extraction must populate annotations category");

        // Warm build — must read the snapshot back. The configHash
        // must match (no config change between runs) and the
        // schemaVersion must match (no SCHEMA_VERSION bump in-flight).
        ProjectIndex warm = ProjectIndex.build(projectDir, config);
        FileMetadata warmMd = warm.fileMetadata(svcPath);
        assertNotNull(warmMd, "warm metadata must be present");
        FileMetadata.TypeDecl warmSvc = warmMd.typeDeclarations().stream()
                .filter(d -> d.simpleName().equals("Svc"))
                .findFirst().orElseThrow();
        assertEquals(coldSvc.headerEdges().implementsSimpleNames(),
                warmSvc.headerEdges().implementsSimpleNames(),
                "warm reload must produce byte-identical implements category");
        assertEquals(coldSvc.headerEdges().typeBoundSimpleNames(),
                warmSvc.headerEdges().typeBoundSimpleNames(),
                "warm reload must produce byte-identical type-bound category");
        assertEquals(coldSvc.headerEdges().annotationSimpleNames(),
                warmSvc.headerEdges().annotationSimpleNames(),
                "warm reload must produce byte-identical annotations category");
    }

    @Test
    void nestedDeclsWithSameSimpleNameKeepDistinctHeaderEdgesAcrossWarmCache() throws Exception {
        // Issue #132 follow-up (correctness C1/ADV-HE-06): the
        // pre-v6 cache keyed the per-file header-edges map on
        // {@code TypeDecl.simpleName()}, so two nested decls sharing
        // a simple name within one file collapsed onto a single map
        // entry on reload. The strategy then saw "the same header
        // edges on whichever nested decl happened to load last" and
        // silently fabricated edges across the wrong outer's body.
        // The v6 schema bump keyed on the qualified name; pin that
        // the warm round-trip preserves both decls' edges distinctly.
        writeJava(projectDir.resolve("src/main/java/com/example/Pair.java"),
                "package com.example;\n"
                        + "public class Pair {\n"
                        + "    public static class A {\n"
                        + "        public static class Z extends RuntimeException {}\n"
                        + "    }\n"
                        + "    public static class B {\n"
                        + "        public static class Z implements java.io.Serializable {}\n"
                        + "    }\n"
                        + "}");

        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .mode(Mode.CI)
                .build();

        ProjectIndex cold = ProjectIndex.build(projectDir, config);
        Path pairPath = projectDir.resolve("src/main/java/com/example/Pair.java");
        FileMetadata coldMd = cold.fileMetadata(pairPath);
        assertNotNull(coldMd, "cold metadata must be present");
        FileMetadata.TypeDecl coldAZ = coldMd.typeDeclarations().stream()
                .filter(d -> d.qualifiedName().equals("Pair.A.Z"))
                .findFirst().orElseThrow(() ->
                        new AssertionError("Pair.A.Z must surface as a distinct TypeDecl. "
                                + "Got " + coldMd.typeDeclarations().stream()
                                .map(FileMetadata.TypeDecl::qualifiedName)
                                .toList()));
        FileMetadata.TypeDecl coldBZ = coldMd.typeDeclarations().stream()
                .filter(d -> d.qualifiedName().equals("Pair.B.Z"))
                .findFirst().orElseThrow(() ->
                        new AssertionError("Pair.B.Z must surface as a distinct TypeDecl"));
        assertEquals(java.util.List.of("RuntimeException"),
                coldAZ.headerEdges().extendsSimpleNames(),
                "cold A.Z extends RuntimeException");
        assertEquals(java.util.List.of("java.io.Serializable"),
                coldBZ.headerEdges().implementsSimpleNames(),
                "cold B.Z implements Serializable");

        // Warm reload — must keep the two Zs distinct.
        ProjectIndex warm = ProjectIndex.build(projectDir, config);
        FileMetadata warmMd = warm.fileMetadata(pairPath);
        assertNotNull(warmMd, "warm metadata must be present");
        FileMetadata.TypeDecl warmAZ = warmMd.typeDeclarations().stream()
                .filter(d -> d.qualifiedName().equals("Pair.A.Z"))
                .findFirst().orElseThrow();
        FileMetadata.TypeDecl warmBZ = warmMd.typeDeclarations().stream()
                .filter(d -> d.qualifiedName().equals("Pair.B.Z"))
                .findFirst().orElseThrow();
        assertEquals(coldAZ.headerEdges().extendsSimpleNames(),
                warmAZ.headerEdges().extendsSimpleNames(),
                "warm A.Z keeps its own extends — pre-v6 it was overwritten by B.Z's");
        assertEquals(coldBZ.headerEdges().implementsSimpleNames(),
                warmBZ.headerEdges().implementsSimpleNames(),
                "warm B.Z keeps its own implements — pre-v6 it was overwritten by A.Z's");
        assertEquals("Z", warmAZ.simpleName(),
                "warm A.Z's simpleName is still the leaf — qualifiedName disambiguates");
        assertEquals("Z", warmBZ.simpleName(),
                "warm B.Z's simpleName is still the leaf — qualifiedName disambiguates");
    }

    @Test
    void disableViaSystemPropertyBypassesCache() throws Exception {
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");

        String prevValue = System.getProperty("affected-tests.indexCache.enabled");
        System.setProperty("affected-tests.indexCache.enabled", "false");
        try {
            ProjectIndex.build(projectDir, BASE_CONFIG);
            Path snapshot = projectDir.resolve("build/affected-tests/index/v1/snapshot.tsv");
            assertFalse(Files.exists(snapshot),
                    "System property must short-circuit BOTH read and write — otherwise "
                            + "an adopter disables the cache to debug a problem and a "
                            + "stale snapshot still influences the next enabled run");
        } finally {
            // Restore for sibling tests in the same class — the
            // property is a JVM-global, and surviving "false" would
            // silently turn every subsequent test in this run into
            // a cache-miss test.
            if (prevValue == null) {
                System.clearProperty("affected-tests.indexCache.enabled");
            } else {
                System.setProperty("affected-tests.indexCache.enabled", prevValue);
            }
        }
    }

    private static void writeJava(Path target, String body) throws Exception {
        Files.createDirectories(target.getParent());
        Files.writeString(target, body);
        // Pin a stable mtime so multiple writes within the same
        // second don't rely on filesystem-resolution luck. Tests
        // that genuinely care about mtime drift call
        // setLastModifiedTime themselves with a different value.
        Files.setLastModifiedTime(target, FileTime.fromMillis(1_000_000_000_000L));
    }
}
