package io.affectedtests.core.discovery;

import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.config.Mode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage&nbsp;2 contract for {@link ProjectIndexCache}: the per-file
 * {@link FileMetadata} block (issue&nbsp;#41 stage&nbsp;2) must
 * survive a clean round-trip, must invalidate <em>only</em> rows
 * whose file fingerprint drifted (not the whole snapshot), and must
 * reject the snapshot wholesale on a schema-version drift just like
 * stage&nbsp;1 does.
 *
 * <p>Each test follows the same shape: build an index, populate the
 * per-file metadata cache via direct seeding (which is what the
 * engine's strategy fan-out does at runtime, and what the on-disk
 * persist later writes back), persist, mutate the live tree, and
 * load again. The pre/post comparison of
 * {@link ProjectIndex#fileMetadataCacheSize()} is the smoking-gun
 * check for "did we actually rehydrate from disk?" — without it the
 * lazy-extract path would silently re-do the parse work and the
 * cache would be a no-op.
 */
class ProjectIndexCacheStage2Test {

    @TempDir
    Path projectDir;

    private static final AffectedTestsConfig BASE_CONFIG = AffectedTestsConfig.builder()
            .mode(Mode.CI)
            .build();

    @Test
    void persistAndReloadRoundTripsPerFileMetadata() throws Exception {
        // Build, seed the metadata cache (simulating what strategies
        // do at runtime), persist, then load and confirm every seeded
        // entry comes back with the same field shape.
        Path testFile = projectDir.resolve("src/test/java/com/example/FooTest.java");
        writeJava(testFile, """
                package com.example;
                import com.foo.Bar;
                public class FooTest {
                    Bar bar;
                }
                """);

        ProjectIndex first = ProjectIndex.build(projectDir, BASE_CONFIG);
        FileMetadata extracted = first.fileMetadata(testFile.toAbsolutePath());
        assertNotNull(extracted, "Sanity: extractor must succeed on valid source");

        // {@code persist} is idempotent — call it explicitly with the
        // populated metadata cache, mirroring what the engine does
        // after {@code runDiscovery}.
        ProjectIndexCache.persist(projectDir, BASE_CONFIG, first);

        ProjectIndex second = ProjectIndex.build(projectDir, BASE_CONFIG);
        assertEquals(1, second.fileMetadataCacheSize(),
                "Reloaded index must pre-seed the per-file metadata cache from disk; "
                        + "without that the strategies fall through to a fresh parse and "
                        + "the cache is functionally a no-op for AST-derived data");

        FileMetadata reloaded = second.fileMetadata(testFile.toAbsolutePath());
        assertEquals(extracted.packageName(), reloaded.packageName());
        assertEquals(extracted.primaryTypeName(), reloaded.primaryTypeName());
        assertEquals(extracted.imports(), reloaded.imports());
        assertEquals(extracted.typeRefSimpleNames(), reloaded.typeRefSimpleNames());
        assertEquals(extracted.typeRefDottedNames(), reloaded.typeRefDottedNames());
        assertEquals(extracted.typeDeclarations(), reloaded.typeDeclarations());
    }

    @Test
    void perFileFingerprintMismatchInvalidatesOnlyThatRow() throws Exception {
        // Two files cached on first run. Edit one of them in place
        // (mtime changes, dir mtime / child count do NOT change since
        // it's an in-place write — exactly the case Stage 1's dir
        // contract is blind to). On reload the unedited file's row
        // must be reused; the edited file's row must be dropped and
        // the strategy must re-extract on next access.
        Path foo = projectDir.resolve("src/main/java/com/example/Foo.java");
        Path bar = projectDir.resolve("src/main/java/com/example/Bar.java");
        writeJava(foo, "package com.example; public class Foo {}");
        writeJava(bar, "package com.example; public class Bar {}");
        // Pin every dir on the path between the project dir and the
        // package dir to a known mtime BEFORE the first
        // {@code ProjectIndex.build} so the Stage 1 dir-snapshot rows
        // we just persisted carry a deterministic value. Without this
        // pin the dir mtimes track wall-clock at file-creation time,
        // and the post-edit pin we apply later wouldn't be guaranteed
        // to match what landed on disk in the snapshot — a Stage 1
        // mismatch would invalidate the whole cache and the partial-
        // reuse contract this test exists to verify becomes invisible.
        FileTime pinned = FileTime.fromMillis(PINNED_DIR_MTIME_MS);
        pinPathMtimesUnder(projectDir, foo.getParent(), pinned);

        ProjectIndex first = ProjectIndex.build(projectDir, BASE_CONFIG);
        first.fileMetadata(foo.toAbsolutePath());
        first.fileMetadata(bar.toAbsolutePath());
        ProjectIndexCache.persist(projectDir, BASE_CONFIG, first);

        // Edit Foo's content in place. Bumping mtime explicitly avoids
        // depending on filesystem mtime resolution; on macOS APFS in-
        // place writes inside the same second can keep mtime equal.
        Files.writeString(foo, "package com.example; public class Foo { int x; }");
        Files.setLastModifiedTime(foo, FileTime.fromMillis(2_000_000_000_000L));
        // Re-pin the dir chain to the SAME value the persist saw, so
        // Stage 1's (mtime, child-count) gate stays clean. Only Stage 2's
        // per-file fingerprint check can drop Foo's row; Bar's row must
        // survive the load because its file mtime/size did not change.
        pinPathMtimesUnder(projectDir, foo.getParent(), pinned);

        ProjectIndex second = ProjectIndex.build(projectDir, BASE_CONFIG);
        // The smoking-gun assertion for partial reuse: Bar's row
        // survived the cache load and is already seeded in the
        // metadata cache before any strategy runs. Foo's row was
        // dropped on fingerprint mismatch, so its slot stays empty
        // until something requests it. {@code fileMetadataCacheSize}
        // counts only filled slots, so on a clean partial-reuse load
        // it reads exactly 1 (Bar).
        assertEquals(1, second.fileMetadataCacheSize(),
                "Partial-reuse contract: Bar's row must seed (fingerprint matched), "
                        + "Foo's row must not (fingerprint drifted) — without this gap, "
                        + "Stage 2's per-file invalidation collapses into Stage 1's "
                        + "all-or-nothing dir gate");

        FileMetadata fooReloaded = second.fileMetadata(foo.toAbsolutePath());
        assertNotNull(fooReloaded, "Foo's invalidated row must re-extract on lazy access");
        // After the lazy re-extract, the cache holds both Foo (fresh)
        // and Bar (seeded). This pins the rebuild path for the
        // mismatched file alongside the survival of the matching one.
        assertEquals(2, second.fileMetadataCacheSize(),
                "Lazy re-extract must populate Foo's slot without touching Bar's");

        // Edited content adds a {@code int x} field — primary type
        // name is unchanged but the type-ref set picks up {@code int}
        // is a primitive (not a {@code ClassOrInterfaceType}), so
        // the simple-name set stays empty for this shape. Use the
        // top-level type's supertype list as the discriminator: the
        // edited file declares no supertypes either, so we use the
        // import set as the canary instead — the original file had
        // no imports and the edited file still has none. The strict
        // contract is "round-tripped data agrees with re-extraction
        // of the live file", which is the universal invariant.
        FileMetadata fooFresh = FileMetadataExtractor.extract(
                JavaLanguageParser.newParser().parse(foo).getResult().orElseThrow());
        assertEquals(fooFresh.imports(), fooReloaded.imports());
        assertEquals(fooFresh.typeDeclarations(), fooReloaded.typeDeclarations());
    }

    @Test
    void schemaVersionDriftInvalidatesStage2RowsToo() throws Exception {
        // A v1 snapshot pre-seeded on disk must NOT be honoured by a
        // v2 reader, even if every other field looks fine. This is
        // the same contract Stage 1 already pinned — we restate it
        // here for clarity and to lock in that bumping the schema
        // version invalidates BOTH layers, not just stage 1.
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");

        Path cacheDir = projectDir.resolve("build/affected-tests/index/v1");
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("snapshot.tsv"),
                "v\t1\ncfg\tdoesnotmatter\n");

        ProjectIndex index = ProjectIndex.build(projectDir, BASE_CONFIG);
        // The pre-seeded v1 snapshot is rejected; the build path
        // takes the miss branch and indexes from scratch. Strategies
        // haven't run yet so the metadata cache is empty.
        assertEquals(Set.of("com.example.FooTest"), index.testFqns());
        assertEquals(0, index.fileMetadataCacheSize());
    }

    @Test
    void persistWithEmptyMetadataCacheStillWritesValidSnapshot() throws Exception {
        // The contract: a build that never touches the metadata cache
        // (e.g. a no-op rebuild for a test-only diff that never reaches
        // Usage / Implementation / Transitive) must still persist a
        // valid snapshot at the current schema version — otherwise
        // the next run takes the schema-mismatch invalidation path
        // forever and the cache never warms up. PR #1 of issue #76
        // bumped the schema 2 → 3 to invalidate stale Java-only
        // testFqn universes on mixed Java + Kotlin projects; PR #3
        // bumps 3 → 4 to invalidate the on-disk row format ahead of
        // the Kotlin AST parser shipping its first FileMetadata rows
        // (even with the rollout flag default-off, a CI worker that
        // flipped the property once must not surface a half-Kotlin
        // -shaped cache to a worker that ran with the property off).
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");

        ProjectIndex first = ProjectIndex.build(projectDir, BASE_CONFIG);
        // Don't touch fileMetadata() — leave the cache empty.
        ProjectIndexCache.persist(projectDir, BASE_CONFIG, first);

        Path snapshot = projectDir.resolve("build/affected-tests/index/v1/snapshot.tsv");
        assertTrue(Files.isRegularFile(snapshot));
        String contents = Files.readString(snapshot);
        assertTrue(contents.contains("v\t6"),
                "Persisted snapshot must declare current schema version (6 since "
                        + "issue #132's nested-decl FQN follow-up bumped 5 → 6 to add "
                        + "the qualified-name disambiguator to the decls column and "
                        + "switch the header-edges key from simpleName to qualifiedName)");

        ProjectIndex second = ProjectIndex.build(projectDir, BASE_CONFIG);
        assertEquals(first.testFqns(), second.testFqns(),
                "An empty-metadata persist must still serve the Stage 1 aggregates "
                        + "on the next run");
    }

    @Test
    void fileVanishingBetweenExtractionAndPersistSkipsRowCleanly() throws Exception {
        // Defensive: a file that was parsed during discovery but then
        // deleted before persist ran (e.g. a watch-mode tool deleted
        // it) must not surface as a write-time exception. The
        // metadata row is silently skipped — and just-as-importantly
        // the OTHER files' rows still land in the snapshot, so a
        // single vanished file doesn't poison the cache for an
        // unrelated set.
        Path foo = projectDir.resolve("src/main/java/com/example/Foo.java");
        Path bar = projectDir.resolve("src/main/java/com/example/Bar.java");
        writeJava(foo, "package com.example; public class Foo {}");
        writeJava(bar, "package com.example; public class Bar {}");

        ProjectIndex index = ProjectIndex.build(projectDir, BASE_CONFIG);
        index.fileMetadata(foo.toAbsolutePath());
        index.fileMetadata(bar.toAbsolutePath());

        // Foo vanishes after extraction. Its in-memory row was
        // captured with the pre-delete fingerprint via the lazy
        // path, so the snapshot would otherwise carry a row whose
        // file no longer exists.
        Files.delete(foo);

        assertDoesNotThrow(() -> ProjectIndexCache.persist(projectDir, BASE_CONFIG, index));

        // Foo's row was lazily extracted with a fingerprint captured
        // BEFORE the delete (the post-parse readAttributes call), so
        // Stage 2 happily writes that row — and {@code seedFileMetadata}
        // on the next load is what filters it out (the file is gone,
        // {@code Files.readAttributes} throws, the row is skipped).
        // The contract this test pins is: persist stays non-fatal AND
        // the surviving file's row is preserved end-to-end. Reload and
        // assert exactly that.
        ProjectIndex second = ProjectIndex.build(projectDir, BASE_CONFIG);
        // Bar's row must seed; Foo's row must drop on the next load
        // because its file is gone.
        assertEquals(1, second.fileMetadataCacheSize(),
                "Vanished-file row must drop at load time via the readAttributes "
                        + "skip; surviving file's row must seed normally");
    }

    @Test
    void disabledSystemPropertyShortCircuitsStage2PersistToo() throws Exception {
        writeJava(projectDir.resolve("src/main/java/com/example/Foo.java"),
                "package com.example; public class Foo {}");

        String prev = System.getProperty("affected-tests.indexCache.enabled");
        System.setProperty("affected-tests.indexCache.enabled", "false");
        try {
            ProjectIndex index = ProjectIndex.build(projectDir, BASE_CONFIG);
            // The build-time persist already short-circuited; verify
            // the Stage 2 end-of-run persist does too — without this
            // an adopter who flips the kill switch to debug a
            // problem still gets a snapshot written, defeating the
            // point of the disable.
            ProjectIndexCache.persist(projectDir, BASE_CONFIG, index);

            Path snapshot = projectDir.resolve("build/affected-tests/index/v1/snapshot.tsv");
            assertFalse(Files.exists(snapshot),
                    "System-property disable must short-circuit BOTH the build-time "
                            + "persist (stage 1) and the end-of-run persist (stage 2)");
        } finally {
            if (prev == null) {
                System.clearProperty("affected-tests.indexCache.enabled");
            } else {
                System.setProperty("affected-tests.indexCache.enabled", prev);
            }
        }
    }

    @Test
    void malformedNumericFieldInStage2RowDropsRowViaRuntimeExceptionCatch() throws Exception {
        // Distinct from the {@code corruptStage2RowDropsRowWithoutAbortingLoad}
        // case below: that one trips the "fewer than 9 fields" early-return
        // branch in {@link FileMetadataRow#parse}. This one trips the
        // catch (RuntimeException) branch — a row with the right field
        // count but a non-numeric mtime triggers
        // {@link Long#parseLong}'s {@link NumberFormatException}, and
        // the parser must swallow it row-level instead of aborting the
        // whole snapshot read. Without separate coverage that catch
        // arm would be untested and a future refactor could remove
        // it without any test-suite signal.
        Path foo = projectDir.resolve("src/main/java/com/example/Foo.java");
        Path bar = projectDir.resolve("src/main/java/com/example/Bar.java");
        writeJava(foo, "package com.example; public class Foo {}");
        writeJava(bar, "package com.example; public class Bar {}");

        ProjectIndex first = ProjectIndex.build(projectDir, BASE_CONFIG);
        first.fileMetadata(foo.toAbsolutePath());
        first.fileMetadata(bar.toAbsolutePath());
        ProjectIndexCache.persist(projectDir, BASE_CONFIG, first);

        Path snapshot = projectDir.resolve("build/affected-tests/index/v1/snapshot.tsv");
        String original = Files.readString(snapshot);
        // 9 fields, but mtime ("NOT-A-LONG") fails Long.parseLong.
        // Trailing tabs encode empty list fields exactly the way
        // {@link FileMetadataRow#encode} would.
        Files.writeString(snapshot,
                original + "m\t/some/path.java\tNOT-A-LONG\t100\tcom.example\tX\t\t\t\n");

        ProjectIndex second = ProjectIndex.build(projectDir, BASE_CONFIG);
        assertEquals(2, second.fileMetadataCacheSize(),
                "A row with a non-numeric mtime must be silently skipped via "
                        + "FileMetadataRow.parse's catch (RuntimeException) branch — "
                        + "every other file's row stays valid");
    }

    @Test
    void corruptStage2RowDropsRowWithoutAbortingLoad() throws Exception {
        // A single garbled m-row must NOT invalidate the whole snapshot.
        // The reader skips it, every other file's row stays seeded, and
        // discovery falls through to the lazy parse path for the file
        // whose row was dropped.
        Path foo = projectDir.resolve("src/main/java/com/example/Foo.java");
        Path bar = projectDir.resolve("src/main/java/com/example/Bar.java");
        writeJava(foo, "package com.example; public class Foo {}");
        writeJava(bar, "package com.example; public class Bar {}");

        ProjectIndex first = ProjectIndex.build(projectDir, BASE_CONFIG);
        first.fileMetadata(foo.toAbsolutePath());
        first.fileMetadata(bar.toAbsolutePath());
        ProjectIndexCache.persist(projectDir, BASE_CONFIG, first);

        // Inject a garbled m-row at the end of the snapshot. The
        // reader's row-level fallback should skip it without
        // affecting the surrounding rows.
        Path snapshot = projectDir.resolve("build/affected-tests/index/v1/snapshot.tsv");
        String original = Files.readString(snapshot);
        Files.writeString(snapshot, original + "m\tnot-enough-fields\n");

        ProjectIndex second = ProjectIndex.build(projectDir, BASE_CONFIG);
        // Both files' metadata rows still load; the corrupt row is
        // silently ignored. {@code fileMetadataCacheSize()} reports
        // 2 from the rehydrated rows, not 0 from a wholesale
        // invalidation.
        assertEquals(2, second.fileMetadataCacheSize(),
                "A single garbled m-row must not invalidate every other file's "
                        + "cached metadata — partial reuse is the entire point of "
                        + "per-row fingerprinting");
    }

    private static final long PINNED_DIR_MTIME_MS = 1_500_000_000_000L;

    private static void writeJava(Path target, String body) throws Exception {
        Files.createDirectories(target.getParent());
        Files.writeString(target, body);
        Files.setLastModifiedTime(target, FileTime.fromMillis(1_000_000_000_000L));
    }

    /**
     * Pins the mtime of every directory between {@code root} (inclusive)
     * and {@code leaf} (inclusive) to {@code stamp}. Used to neutralise
     * Stage 1's {@code (dir-mtime, child-count)} cache gate in tests
     * that need to verify Stage 2's per-file invalidation in isolation;
     * without this pin, a kernel that reflects file-write activity
     * into ancestor dir mtimes triggers a Stage 1 miss that masks the
     * Stage 2 contract.
     */
    private static void pinPathMtimesUnder(Path root, Path leaf, FileTime stamp) throws Exception {
        Path current = leaf;
        while (current != null && current.startsWith(root)) {
            Files.setLastModifiedTime(current, stamp);
            if (current.equals(root)) break;
            current = current.getParent();
        }
    }
}
