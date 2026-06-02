package io.affectedtests.core.discovery;

import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Persists and reloads the cheap-to-derive-but-expensive-to-walk parts
 * of {@link ProjectIndex} between {@code affectedTest} runs.
 *
 * <p>Issue <a href="https://github.com/vedanthvdev/affected-tests/issues/41">#41</a>
 * tracks the broader perf goal: on a 10k-class monorepo the index
 * phase ({@code SourceFileScanner.collectSourceFiles} +
 * {@code collectTestFiles} + {@code scanTestFqnsWithFiles} +
 * {@code findAllMatchingDirs}) dominates wall time of a small-diff
 * invocation, and there is no persistence — two consecutive runs
 * against an unchanged source tree do the same work twice.
 *
 * <p>Stage 1 caches four path-derived index aggregates:
 * <ul>
 *   <li>{@code sourceFiles} — absolute paths of every source file
 *       (extensions in {@link SourceExtensions#EXTENSIONS}) under
 *       {@code sourceDirs}</li>
 *   <li>{@code testFiles} — absolute paths of every source file
 *       under {@code testDirs}</li>
 *   <li>{@code testFqnToPath} — ordered map of test FQN to its file</li>
 *   <li>{@code sourceFqns} — set of production FQNs</li>
 * </ul>
 *
 * <p>Stage 2 ({@code SCHEMA_VERSION = 2}, since 2024-Q4) cached per-file
 * AST-derived data — declared package, primary type, imports, type-ref
 * simple/dotted names, and per-decl supertype names — captured as
 * {@link FileMetadata} records so {@link UsageStrategy},
 * {@link ImplementationStrategy}, and {@link TransitiveStrategy} can
 * read them without re-parsing on a warm run. Each row carries a
 * {@code (mtime, size)} fingerprint so a single edited file
 * invalidates only its own row, not the whole snapshot.
 *
 * <p>Cache file layout: line-based TSV at {@code
 * <projectDir>/build/affected-tests/index/v1/snapshot.tsv}. Hand-rolled
 * format keeps the runtime dependency footprint flat (no Jackson) and
 * stays grep-friendly under {@code build/} for debugging. Forward-
 * compatible by ignoring unknown line types: future snapshot writers
 * can add new sections without breaking older readers.
 *
 * <p><strong>Validity contract.</strong> The cache is considered
 * usable when, in order:
 * <ol>
 *   <li>The snapshot's {@code schemaVersion} matches {@link #SCHEMA_VERSION}.</li>
 *   <li>The snapshot's {@code configHash} matches the current config
 *       (any change to {@code sourceDirs}, {@code testDirs},
 *       {@code outOfScopeSourceDirs}, {@code outOfScopeTestDirs} forces
 *       a full rebuild — those are the inputs to file selection).</li>
 *   <li>Every recorded directory under any scan root (recursively)
 *       still exists, has the same mtime, and has the same direct
 *       child count. We snapshot every reachable directory — not
 *       just the scan roots themselves — because adding a file to
 *       {@code src/test/java/com/example/} updates {@code com/example/}'s
 *       mtime but leaves {@code src/test/java/}'s mtime untouched.
 *       Verifying a few hundred dir stats on a 10k-file project costs
 *       ~5-15ms; re-walking 10k files would cost ~150ms. Mtime is
 *       the canonical "did anything add or remove inside me" signal
 *       on POSIX/HFS+/APFS/NTFS, and child count catches the rare
 *       coalesced-add-and-remove case where mtime resolution rounds
 *       two changes into the same second.</li>
 *   <li>The total number of directories under each scan root has not
 *       changed — catches the case where a brand-new package
 *       directory was added (the new dir was not in the snapshot,
 *       so per-dir checks alone would miss it).</li>
 * </ol>
 * Any single failure aborts the load and falls through to a full
 * rebuild — partial-cache reuse is intentionally NOT supported in
 * Stage 1 to keep the safety contract trivial to reason about.
 *
 * <p><strong>What the dir contract deliberately does NOT verify.</strong>
 * Per-file mtimes for {@code .java} content edits do not affect the
 * Stage 1 aggregates, because those are all path-derived (FQN comes
 * from relativising the file path against its source root, not from
 * parsing the {@code package} declaration). A vim save inside an
 * existing file does not change the dir mtime and leaves the file
 * list unchanged, so Stage 1 happily reuses the aggregates. Stage 2
 * (AST-derived data) carries its own per-file {@code (mtime, size)}
 * fingerprint and skips a row whose fingerprint drifted, so a content
 * edit forces the affected file to re-extract while every other
 * file's record stays valid.
 */
public final class ProjectIndexCache {

    private static final Logger log = LoggerFactory.getLogger(ProjectIndexCache.class);

    /**
     * Version of the on-disk format. Bumped when any line shape — or
     * any pre-cache scan-result shape — changes incompatibly.
     *
     * <p>{@code v3} (PR #1 of issue #76, Phase 2 Kotlin AST):
     * {@link SourceFileScanner} now collects {@code .kt} files
     * alongside {@code .java} under every configured source / test
     * dir. The on-disk row format did not change, but
     * {@link #configHash(io.affectedtests.core.config.AffectedTestsConfig)}
     * hashes only the declared dir-list strings — not the
     * file-extension scope the scanner applies inside those dirs —
     * so a pre-PR-1 cache from a mixed Java + Kotlin project is
     * functionally stale: its {@code testFqnToPath} universe is
     * missing every Kotlin test FQN. Bumping the schema was the only
     * mechanism that guaranteed a clean rescan on warm caches across
     * the upgrade boundary.
     *
     * <p>{@code v4} (PR #3 of issue #76, Phase 2 Kotlin AST):
     * marker bump that establishes a hard upgrade boundary
     * independent of {@code configHash}. The wire format in v=4
     * is byte-compatible with v=3 — the {@code m} row layout did
     * not change in this PR. Cross-flag invalidation (e.g. a CI
     * worker that ran once with {@code kotlinEnabled = true} for
     * a smoke test must not feed a half-Kotlin-shaped cache to
     * a worker that ran with the DSL flag off) is delegated to
     * the {@code |kotlinEnabled:<bool>} term added to
     * {@link #configHash(io.affectedtests.core.config.AffectedTestsConfig)}
     * in this same PR, which is mathematically sufficient for
     * every flag-flip scenario. The schema bump itself is a
     * forward-looking defensive marker: PR #4's default-flip plus
     * any future shape change to {@code m} rows (e.g. honouring
     * {@code @file:JvmName}, adding sealed-hierarchy supertype
     * lists) will piggyback on a v=5 bump rather than rely on
     * {@code configHash} to catch the rewire. The trade-off is
     * one CI cache-miss per adopter on the v=3→v=4 upgrade.
     * See {@code docs/PHASE-2-KOTLIN-AST.md} §7.
     *
     * <p>{@code v5} (issue&nbsp;#132, header-edges discovery strategy):
     * the {@code m} per-file row gains a tenth field carrying the
     * six header-edge categories for every
     * {@link FileMetadata.TypeDecl} as a pipe-delimited list of
     * {@code [decl@category:simple1,simple2;category:simple3]}
     * groups. Older readers ignore the extra field via the
     * {@code split(SEP, -1)} call already returning whatever
     * tail-fields exist, but they cannot synthesise it on a warm
     * run — bumping the version forces a clean rebuild rather than
     * letting strategies consult {@link FileMetadata.HeaderEdges#EMPTY}
     * for every cached row. Each {@code headerEdges*} DSL knob
     * also participates in {@link #configHash} so a runtime opt-out
     * still flips the cache to a miss, but the schema bump catches
     * the binary-format upgrade boundary independently.
     *
     * <p>{@code v6} (issue&nbsp;#132 follow-up, nested-decl FQN fix):
     * the {@code decls} field gains a third sub-element per entry
     * carrying the in-CU qualified name
     * ({@code simpleName=qualifiedName?supers}); the
     * {@code headerEdges} field's per-decl key is now the
     * qualified name rather than the simple name. Two nested
     * decls sharing a simple name within one file
     * ({@code class A { class Z {} } class B { class Z {} }}) used
     * to collapse onto a single {@code Z} key when re-loading the
     * cache, so warm runs silently fabricated header edges across
     * the two outers. The schema bump forces a clean rebuild — the
     * previously-encoded {@code Z} entries cannot be split
     * post-hoc into the correct {@code A.Z} / {@code B.Z} keys.
     */
    static final int SCHEMA_VERSION = 6;

    private static final String CACHE_DIR_REL = "build/affected-tests/index/v1";
    private static final String SNAPSHOT_FILE = "snapshot.tsv";

    /** TSV separator. Tabs are vanishingly rare in source-tree paths and make hand-debugging trivial. */
    private static final char SEP = '\t';

    /**
     * Inner separator for list fields inside a per-file metadata
     * row ({@code m} type — see {@link Snapshot#read} for the
     * line-type dispatch). Pipe is not legal in any Java identifier,
     * package name, FQN, or path component on POSIX, so it splits
     * cleanly without escaping. Tabs are reserved for the outer
     * TSV layer.
     */
    private static final char LIST_SEP = '|';

    /**
     * Inner separator inside a single import entry: {@code name:flag}.
     * Colons are illegal in Java type names and import targets, so
     * they unambiguously separate the import name from its kind flag
     * inside the pipe-delimited list.
     */
    private static final char IMPORT_KIND_SEP = ':';

    /**
     * Inner separator between a type-declaration's simple name and
     * its supertype list inside the per-decl row: {@code name=sup1,sup2}.
     * Equals is not legal in Java identifiers.
     */
    private static final char DECL_SEP = '=';

    /**
     * Inner separator inside a supertype list: {@code sup1,sup2,sup3}.
     * Comma is not legal in identifiers; the list is empty for
     * declarations with no extends/implements clause.
     */
    private static final char SUPER_SEP = ',';

    /**
     * Issue&nbsp;#132 — inner separator between a category name
     * and its simple-name list inside a single header-edges decl
     * group: {@code extends:Foo,Bar;implements:Baz}. Reuses
     * colon so a future grep against persisted snapshots can
     * spot category labels without bespoke parsing knowledge.
     */
    private static final char HEADER_CAT_SEP = ':';

    /**
     * Issue&nbsp;#132 — inner separator between consecutive
     * category groups in a single header-edges decl: {@code
     * extends:Foo;implements:Bar;annotations:Component}.
     */
    private static final char HEADER_GROUP_SEP = ';';

    /**
     * Issue&nbsp;#132 — inner separator between a decl's simple
     * name and its header-edges category list in the per-file
     * row's new tenth field: {@code Decl1@extends:Foo;implements:Bar|Decl2@...}.
     */
    private static final char HEADER_DECL_SEP = '@';

    /**
     * Issue&nbsp;#132 (schema v6) — separates a decl's simple name from
     * its in-CU qualified name in the {@code decls} field. Two nested
     * types with the same simple name within one file used to collapse
     * onto a single cache key on reload (correctness finding C1).
     * Wire form per entry: {@code simpleName[~qualifiedName]=super1,super2}.
     * The {@code ~qualifiedName} suffix is omitted when the qualified
     * name equals the simple name (the dominant top-level case), so
     * snapshots produced by projects with no nested types stay
     * byte-identical to the v5 shape. {@code ~} is not a legal Java
     * identifier character (JLS §3.8) so it cannot collide with any
     * decl name we encode.
     */
    private static final char QNAME_SEP = '~';

    private ProjectIndexCache() {}

    /**
     * Snapshot of the directories that contributed to a {@link ProjectIndex} build.
     * Captured during {@link ProjectIndex#build} so {@link #persist} can fingerprint
     * exactly what was walked, without {@link ProjectIndexCache} needing to re-resolve
     * the {@code sourceDirs} / {@code testDirs} glob / suffix shapes itself.
     */
    public record ScannedDirs(List<Path> sourceRoots, List<Path> testRoots) {
        public ScannedDirs {
            sourceRoots = List.copyOf(sourceRoots);
            testRoots = List.copyOf(testRoots);
        }
    }

    /**
     * Attempts to load and verify a cached snapshot for {@code projectDir} against the
     * current {@code config}. Returns {@link Optional#empty()} on any verification
     * failure, missing file, schema drift, or I/O error — the caller falls through
     * to a full rebuild.
     *
     * <p>This method is deliberately exception-safe at the IO boundary: any
     * surfaced failure returns empty rather than propagating. The cost of an
     * unnecessary rebuild is bounded; the cost of a stale cache is unbounded
     * coverage drift, so we err toward rebuild on any uncertainty.
     */
    public static Optional<ProjectIndex> tryLoad(Path projectDir, AffectedTestsConfig config) {
        Path snapshot = projectDir.resolve(CACHE_DIR_REL).resolve(SNAPSHOT_FILE);
        if (!Files.isRegularFile(snapshot)) {
            return Optional.empty();
        }
        Snapshot loaded;
        try (BufferedReader reader = Files.newBufferedReader(snapshot, StandardCharsets.UTF_8)) {
            loaded = Snapshot.read(reader);
        } catch (IOException | RuntimeException e) {
            log.debug("ProjectIndex cache: failed to read snapshot at {}: {} — falling back to full build",
                    LogSanitizer.sanitize(snapshot.toString()),
                    LogSanitizer.sanitize(e.getMessage()));
            return Optional.empty();
        }
        if (loaded.schemaVersion != SCHEMA_VERSION) {
            log.debug("ProjectIndex cache: schemaVersion mismatch (cached={}, expected={}) — full build",
                    loaded.schemaVersion, SCHEMA_VERSION);
            return Optional.empty();
        }
        String currentHash = configHash(config);
        if (!currentHash.equals(loaded.configHash)) {
            log.debug("ProjectIndex cache: configHash mismatch — full build");
            return Optional.empty();
        }
        if (!verifyDirs(loaded.dirSnapshots)) {
            return Optional.empty();
        }
        ProjectIndex index = materialise(loaded, config);
        int metadataSeeded = seedFileMetadata(index, loaded.fileMetadataRows);
        log.info("ProjectIndex cache HIT: {} source files, {} test files, {} test FQNs, {} source FQNs"
                        + " (per-file metadata seeded: {} of {})",
                loaded.sourceFiles.size(), loaded.testFiles.size(),
                loaded.testFqnToPath.size(), loaded.sourceFqns.size(),
                metadataSeeded, loaded.fileMetadataRows.size());
        return Optional.of(index);
    }

    /**
     * Walks the cached per-file metadata rows, validates each one's
     * {@code (mtime, size)} fingerprint against the live file, and
     * pre-seeds {@link ProjectIndex#seedFileMetadata(Path, FileMetadata)}
     * for every match. A fingerprint mismatch (or a vanished file) is
     * a row-level miss: the row is skipped and the strategy that later
     * consults that file falls through to {@link FileMetadataExtractor}
     * via the lazy parse path. The other files' rows stay valid.
     *
     * <p>Returns the count of successfully-seeded entries so the load
     * log can surface "I reused N of M rows" for diagnostics. Rows
     * dropped on fingerprint mismatch are not logged individually —
     * they're noisy on large repos and the count is the
     * actionable signal.
     */
    private static int seedFileMetadata(ProjectIndex index, List<FileMetadataRow> rows) {
        int seeded = 0;
        for (FileMetadataRow row : rows) {
            Path path;
            try {
                path = Path.of(row.path);
            } catch (java.nio.file.InvalidPathException ipe) {
                // A garbled `m` row that survived FileMetadataRow.parse
                // (path field non-empty but not a syntactically-valid
                // path on this platform) shouldn't break load — skip
                // the row and let the rest of the snapshot seed.
                continue;
            }
            long currentMtime;
            long currentSize;
            try {
                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                currentMtime = attrs.lastModifiedTime().toMillis();
                currentSize = attrs.size();
            } catch (IOException e) {
                continue;
            }
            if (currentMtime != row.mtime || currentSize != row.size) {
                continue;
            }
            // Forward the row's verified (mtime, size) so end-of-run
            // persist can re-emit the same fingerprint without a
            // second readAttributes call — the fingerprint we just
            // matched is exactly the one we'd capture again.
            index.seedFileMetadata(path, row.metadata, row.mtime, row.size);
            seeded++;
        }
        return seeded;
    }

    /**
     * Persists the {@link ProjectIndex} for re-use on the next run.
     * Reads the {@link ScannedDirs} from {@code index.scannedDirs()} —
     * the index carries them through both the cache-miss and cache-hit
     * paths so end-of-run persistence is uniform.
     *
     * <p>Honours the {@code affected-tests.indexCache.enabled} system
     * property (defaulting on); a {@code false} value short-circuits
     * the write so a debug run can't influence later enabled runs.
     *
     * <p>Failures here are intentionally non-fatal: a project that
     * cannot write under {@code build/} (read-only mount, permission
     * gap, full disk) should still succeed at the
     * {@code affectedTest} task itself — caching is a perf win, not
     * a correctness requirement.
     */
    public static void persist(Path projectDir,
                               AffectedTestsConfig config,
                               ProjectIndex index) {
        if (!Boolean.parseBoolean(System.getProperty("affected-tests.indexCache.enabled", "true"))) {
            return;
        }
        ScannedDirs dirs = index.scannedDirs();
        if (dirs == null) {
            return;
        }
        persist(projectDir, config, index, dirs);
    }

    /**
     * Lower-level overload used by {@link ProjectIndex#build} to
     * persist immediately on a cache miss with whatever metadata
     * exists at that moment (initially empty Stage&nbsp;2 block;
     * strategies populate it later and the engine re-persists at end
     * of run). Public so existing call sites and tests can supply
     * {@link ScannedDirs} explicitly without depending on
     * {@code index.scannedDirs()} being non-null.
     */
    public static void persist(Path projectDir,
                               AffectedTestsConfig config,
                               ProjectIndex index,
                               ScannedDirs dirs) {
        Path cacheDir = projectDir.resolve(CACHE_DIR_REL);
        Path snapshot = cacheDir.resolve(SNAPSHOT_FILE);
        // Per-writer unique tmp filename: PID + nanoTime makes simultaneous
        // persists from two affectedTest invocations on the same checkout
        // (parallel CI matrix, two terminals) write into disjoint staging
        // files instead of clobbering each other on the shared
        // SNAPSHOT_FILE + ".tmp" path. The atomic-rename target is still
        // shared (only one of the racers will land on disk), but neither
        // racer's write can corrupt the other's in-flight stream.
        Path tmp = cacheDir.resolve(SNAPSHOT_FILE
                + ".tmp." + ProcessHandle.current().pid()
                + "." + Long.toHexString(System.nanoTime()));
        try {
            Files.createDirectories(cacheDir);
            // Atomic-ish write: stage to the unique tmp file then move
            // into place, so a partial write cannot leave the cache
            // half-written and trip the next reader.
            try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                Snapshot.write(writer, configHash(config), dirs, index);
            }
            try {
                Files.move(tmp, snapshot,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // Some FUSE / NFS-on-CI mounts reject ATOMIC_MOVE. Fall back
                // to plain replace — the .tmp staging still keeps a partial
                // write off the canonical filename.
                Files.move(tmp, snapshot, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            log.debug("ProjectIndex cache: persisted snapshot to {}", LogSanitizer.sanitize(snapshot.toString()));
        } catch (IOException | RuntimeException e) {
            log.warn("ProjectIndex cache: failed to persist snapshot at {}: {} — next run will rebuild",
                    LogSanitizer.sanitize(snapshot.toString()),
                    LogSanitizer.sanitize(e.getMessage()));
        } finally {
            // Best-effort cleanup of the per-writer tmp file. On the happy
            // path the rename above moved it; on a write failure (disk
            // full, permission gap, OOM mid-serialise) the file is still
            // sitting there. Leaving it leaks build/-tree garbage and
            // makes a `du` against build/affected-tests/ misleading.
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // Already best-effort; a failed cleanup of a failed write
                // is not worth a second log line.
            }
        }
    }

    /**
     * Removes the on-disk snapshot for this project. Used by tests and as an
     * escape hatch for adopters who want a clean rebuild without flipping the
     * disable knob ({@code rm -rf build/affected-tests/index} is the obvious
     * alternative; this method just centralises the path logic).
     */
    public static void clear(Path projectDir) {
        Path snapshot = projectDir.resolve(CACHE_DIR_REL).resolve(SNAPSHOT_FILE);
        try {
            Files.deleteIfExists(snapshot);
        } catch (IOException e) {
            log.debug("ProjectIndex cache: failed to clear snapshot at {}: {}",
                    LogSanitizer.sanitize(snapshot.toString()),
                    LogSanitizer.sanitize(e.getMessage()));
        }
    }

    // ── Verification ────────────────────────────────────────────────────

    private static boolean verifyDirs(List<DirSnapshot> dirSnapshots) {
        // Group cached snapshots by their scan-root prefix so we can
        // re-walk each tree once and answer two questions in a single
        // pass: (a) does every cached dir still exist, with the same
        // mtime + child count? (b) has the total number of dirs under
        // the root changed? Question (b) catches new dirs that didn't
        // exist when the snapshot was written and so are not in the
        // per-dir map.
        Map<String, List<DirSnapshot>> byRoot = new LinkedHashMap<>();
        for (DirSnapshot d : dirSnapshots) {
            byRoot.computeIfAbsent(d.scanRoot, k -> new ArrayList<>()).add(d);
        }
        for (Map.Entry<String, List<DirSnapshot>> entry : byRoot.entrySet()) {
            String scanRoot = entry.getKey();
            Map<String, DirSnapshot> cachedByPath = new LinkedHashMap<>();
            for (DirSnapshot d : entry.getValue()) {
                cachedByPath.put(d.path, d);
            }
            Path rootPath = Path.of(scanRoot);
            if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
                log.debug("ProjectIndex cache: scan root {} no longer exists — full build",
                        LogSanitizer.sanitize(scanRoot));
                return false;
            }
            // Walk the live tree, ticking off cached entries and
            // counting current dirs at the same time.
            int[] currentDirCount = {0};
            boolean[] mismatched = {false};
            try {
                Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (attrs.isSymbolicLink()) {
                            // Don't follow symlinks — same security posture
                            // as SourceFileScanner uses on its own walk.
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        currentDirCount[0]++;
                        DirSnapshot cached = cachedByPath.get(dir.toAbsolutePath().toString());
                        if (cached == null) {
                            // Live dir was not in the snapshot — a new
                            // directory was added since the cache was
                            // written. Could be a new package, could be
                            // a new module under a glob root.
                            log.debug("ProjectIndex cache: new dir {} appeared under {} — full build",
                                    LogSanitizer.sanitize(dir.toString()),
                                    LogSanitizer.sanitize(scanRoot));
                            mismatched[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        long currentMtime = attrs.lastModifiedTime().toMillis();
                        if (currentMtime != cached.mtime) {
                            log.debug("ProjectIndex cache: dir {} mtime drifted ({} -> {}) — full build",
                                    LogSanitizer.sanitize(dir.toString()),
                                    cached.mtime, currentMtime);
                            mismatched[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        int currentChildren = countDirectChildren(dir);
                        if (currentChildren != cached.fileCount) {
                            log.debug("ProjectIndex cache: dir {} child count drifted ({} -> {}) — full build",
                                    LogSanitizer.sanitize(dir.toString()),
                                    cached.fileCount, currentChildren);
                            mismatched[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                log.debug("ProjectIndex cache: walk failed under {}: {} — full build",
                        LogSanitizer.sanitize(scanRoot),
                        LogSanitizer.sanitize(e.getMessage()));
                return false;
            }
            if (mismatched[0]) {
                return false;
            }
            if (currentDirCount[0] != cachedByPath.size()) {
                // A dir from the snapshot is no longer present (e.g.
                // a package was deleted). The per-dir tick above only
                // visits LIVE dirs, so a missing cached dir would not
                // mark mismatched[0] — the totals check catches it.
                log.debug("ProjectIndex cache: dir count under {} drifted ({} -> {}) — full build",
                        LogSanitizer.sanitize(scanRoot),
                        cachedByPath.size(), currentDirCount[0]);
                return false;
            }
        }
        return true;
    }

    /** Count of *direct* entries (files + subdirs) under {@code dir}, excluding the dir itself. */
    private static int countDirectChildren(Path dir) {
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            return (int) stream.count();
        } catch (IOException e) {
            return -1;
        }
    }

    // ── Hash computation ────────────────────────────────────────────────

    /**
     * SHA-256 of a canonical, stable serialisation of the config keys that
     * actually drive index contents. We deliberately exclude knobs that do
     * NOT influence what files are scanned (e.g. {@code mode}, {@code
     * baseRef}, {@code testTaskNames}) — those affect dispatch but not
     * indexing, so cache reuse across mode flips is safe and cheap.
     */
    static String configHash(AffectedTestsConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("sourceDirs:");
        appendList(sb, config.sourceDirs());
        sb.append("|testDirs:");
        appendList(sb, config.testDirs());
        sb.append("|outOfScopeSourceDirs:");
        appendList(sb, config.outOfScopeSourceDirs());
        sb.append("|outOfScopeTestDirs:");
        appendList(sb, config.outOfScopeTestDirs());
        // Issue #76 PR #3 — Kotlin AST gate participates in the hash so
        // a flip across consecutive runs forces a full rescan. Without
        // this, a worker that ran once with the DSL flag {@code
        // kotlinEnabled = true} and persisted Kotlin {@code m} rows
        // would have its cache reused by a subsequent worker running
        // with the flag off — the off-mode strategies would then
        // consume Kotlin FileMetadata they were not configured to
        // expect. The schema bump (v3 → v4) catches the row-shape
        // change across the PR boundary; this hash term catches the
        // same-binary, same-cache, different-flag-value case. (PR #4
        // dropped the {@code -Daffected-tests.kotlin.enabled} system
        // property, but the DSL flag still flips the cache shape.)
        sb.append("|kotlinEnabled:").append(config.kotlinEnabled());
        // Issue #132 — header-edges DSL knobs participate so any
        // runtime change to the strategy's behaviour (kill switch,
        // category opt-out, depth, sibling cap, ignore globs) forces
        // a clean cache rebuild. The schema bump (v=4 → v=5) catches
        // the binary-format change introduced for the {@code m} row's
        // new tenth field; this hash catches the same-binary,
        // different-config case (an adopter flipping
        // {@code headerEdgesEnabled} between runs).
        sb.append("|headerEdgesEnabled:").append(config.headerEdgesEnabled());
        sb.append("|headerEdgesDepth:").append(config.headerEdgesDepth());
        sb.append("|headerEdgesMaxSiblings:").append(config.headerEdgesMaxSiblings());
        sb.append("|headerEdgesExclude:");
        List<String> excludeSorted = new ArrayList<>(config.headerEdgesExclude());
        Collections.sort(excludeSorted);
        sb.append(excludeSorted);
        sb.append("|headerEdgesIgnore:");
        appendList(sb, config.headerEdgesIgnore());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK spec — this branch is
            // unreachable on any sane runtime, but we throw so a
            // genuinely-broken JDK fails loudly instead of silently
            // disabling the cache via a degenerate hash.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void appendList(StringBuilder sb, List<String> values) {
        // Sort for stability — a config that lists ['a','b'] and one that
        // lists ['b','a'] index identical files, so they MUST hash the
        // same. Without sorting, a benign DSL reorder would force a
        // cache miss.
        List<String> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        sb.append(sorted);
    }

    // ── Materialisation ─────────────────────────────────────────────────

    private static ProjectIndex materialise(Snapshot s, AffectedTestsConfig config) {
        List<Path> sourceFiles = new ArrayList<>(s.sourceFiles.size());
        for (String p : s.sourceFiles) sourceFiles.add(Path.of(p));
        List<Path> testFiles = new ArrayList<>(s.testFiles.size());
        for (String p : s.testFiles) testFiles.add(Path.of(p));
        LinkedHashMap<String, Path> testFqnToPath = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : s.testFqnToPath.entrySet()) {
            testFqnToPath.put(e.getKey(), Path.of(e.getValue()));
        }
        Set<String> sourceFqns = new LinkedHashSet<>(s.sourceFqns);

        // Reconstruct ScannedDirs from the cached d-rows. The original
        // build distinguished sourceRoots / testRoots, but the on-disk
        // shape collapses both into a single d-row scanRoot field —
        // and downstream {@code persist} only iterates the union to
        // re-walk subtrees, so packing every reconstructed root into
        // sourceRoots and leaving testRoots empty produces the same
        // walk. The split exists for the original-build call site's
        // ergonomics, not for an on-disk invariant.
        Set<String> uniqueRootStrings = new LinkedHashSet<>();
        for (DirSnapshot d : s.dirSnapshots) {
            uniqueRootStrings.add(d.scanRoot);
        }
        List<Path> reconstructedRoots = new ArrayList<>(uniqueRootStrings.size());
        for (String root : uniqueRootStrings) {
            reconstructedRoots.add(Path.of(root));
        }
        ScannedDirs dirs = new ScannedDirs(reconstructedRoots, List.of());

        return ProjectIndex.fromCache(sourceFiles, testFiles, testFqnToPath, sourceFqns, dirs, config);
    }

    // ── On-disk schema ──────────────────────────────────────────────────

    /**
     * In-memory representation of the on-disk snapshot. Internal — kept
     * package-private to keep the public API surface ({@link #tryLoad} /
     * {@link #persist}) thin.
     */
    static final class Snapshot {
        int schemaVersion;
        String configHash;
        List<DirSnapshot> dirSnapshots = new ArrayList<>();
        List<String> sourceFiles = new ArrayList<>();
        List<String> testFiles = new ArrayList<>();
        LinkedHashMap<String, String> testFqnToPath = new LinkedHashMap<>();
        List<String> sourceFqns = new ArrayList<>();

        /**
         * Stage&nbsp;2 (issue&nbsp;#41) per-file metadata rows. Each row
         * carries a {@code (path, mtime, size)} fingerprint so a single
         * edited file invalidates only its own row and the rest of the
         * cache stays usable.
         */
        List<FileMetadataRow> fileMetadataRows = new ArrayList<>();

        static Snapshot read(BufferedReader reader) throws IOException {
            Snapshot s = new Snapshot();
            s.schemaVersion = -1;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int firstSep = line.indexOf(SEP);
                if (firstSep < 0) continue;
                String type = line.substring(0, firstSep);
                String rest = line.substring(firstSep + 1);
                switch (type) {
                    case "v"     -> {
                        s.schemaVersion = Integer.parseInt(rest);
                        // Short-circuit on schema mismatch — both
                        // forward-incompatibility (a future v=5
                        // body would surface as
                        // {@link UncheckedIOException} from
                        // {@link DirSnapshot#parse} or as a silent
                        // null-row drop in {@link FileMetadataRow#parse},
                        // both of which obscure the actual cause)
                        // and backward-incompatibility (a v=3 body
                        // whose shape changed in v=4) bail out
                        // here. The caller's schema check at
                        // {@link #tryLoad} then logs the canonical
                        // "schemaVersion mismatch" line. Relies on
                        // {@link Snapshot#write} writing the
                        // {@code v\t...} line as the first
                        // non-comment line in the snapshot, which
                        // it does — see {@link Snapshot#write}.
                        if (s.schemaVersion != SCHEMA_VERSION) {
                            return s;
                        }
                    }
                    case "cfg"   -> s.configHash = rest;
                    case "d"     -> s.dirSnapshots.add(DirSnapshot.parse(rest));
                    case "sf"    -> s.sourceFiles.add(rest);
                    case "tf"    -> s.testFiles.add(rest);
                    case "tfqn"  -> {
                        int sep = rest.indexOf(SEP);
                        if (sep > 0) {
                            s.testFqnToPath.put(rest.substring(0, sep), rest.substring(sep + 1));
                        }
                    }
                    case "sfqn"  -> s.sourceFqns.add(rest);
                    case "m"     -> {
                        FileMetadataRow row = FileMetadataRow.parse(rest);
                        if (row != null) s.fileMetadataRows.add(row);
                    }
                    default      -> { /* forward-compat: ignore unknown line type */ }
                }
            }
            if (s.schemaVersion < 0) {
                throw new IOException("ProjectIndex cache: missing schemaVersion line");
            }
            return s;
        }

        static void write(BufferedWriter writer,
                          String configHash,
                          ScannedDirs dirs,
                          ProjectIndex index) throws IOException {
            writer.write("# affected-tests project-index snapshot v" + SCHEMA_VERSION);
            writer.newLine();
            writer.write("v" + SEP + SCHEMA_VERSION);
            writer.newLine();
            writer.write("cfg" + SEP + configHash);
            writer.newLine();
            // Snapshot every directory reachable from each scan root.
            // The per-root grouping keys the on-disk row to its scan
            // root so verifyDirs can re-walk one root at a time and
            // ask "did this tree change?" without needing to also
            // re-resolve the sourceDirs / testDirs glob shapes.
            for (Path d : dirs.sourceRoots()) writeRootSubtree(writer, d);
            for (Path d : dirs.testRoots())   writeRootSubtree(writer, d);
            for (Path p : index.sourceFiles()) {
                writer.write("sf" + SEP + p.toAbsolutePath().toString());
                writer.newLine();
            }
            for (Path p : index.testFiles()) {
                writer.write("tf" + SEP + p.toAbsolutePath().toString());
                writer.newLine();
            }
            for (Map.Entry<String, Path> e : index.testFqnToPath().entrySet()) {
                writer.write("tfqn" + SEP + e.getKey() + SEP + e.getValue().toAbsolutePath().toString());
                writer.newLine();
            }
            for (String fqn : index.sourceFqns()) {
                writer.write("sfqn" + SEP + fqn);
                writer.newLine();
            }
            // Stage 2: per-file metadata rows. Pulled from the index
            // at write time — on a cache-miss build this is whatever
            // strategies extracted lazily, on a cache-hit-then-rebuild
            // path it's the union of seeded entries plus any
            // refreshed-on-fingerprint-mismatch entries. Each entry
            // already carries the (mtime, size) captured at extract
            // time (lazy path) or inherited from the verified on-disk
            // row (seed path); persist re-emits that fingerprint
            // directly. Two wins: no second {@link Files#readAttributes}
            // call per cached file (halves the per-file syscall budget
            // on large repos), and the persisted fingerprint binds
            // the row to the file version we actually parsed instead
            // of to whatever the file looked like at the moment the
            // engine returned (TOCTOU narrowing).
            //
            // Entries whose fingerprint capture failed at extract time
            // are filtered out by {@link ProjectIndex#metadataCacheSnapshot()}
            // before reaching this loop — a row without a verifiable
            // fingerprint would silently survive past a real change
            // and is strictly worse than re-extracting next run.
            Map<Path, ProjectIndex.CachedMetadata> liveMetadata = index.metadataCacheSnapshot();
            for (Map.Entry<Path, ProjectIndex.CachedMetadata> e : liveMetadata.entrySet()) {
                Path file = e.getKey();
                ProjectIndex.CachedMetadata cm = e.getValue();
                writer.write("m" + SEP + FileMetadataRow.encode(file, cm.mtime(), cm.size(), cm.metadata()));
                writer.newLine();
            }
        }

        private static void writeRootSubtree(BufferedWriter writer, Path scanRoot) throws IOException {
            String scanRootAbs = scanRoot.toAbsolutePath().toString();
            Files.walkFileTree(scanRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink()) {
                        // Skip symlinked subtrees — same posture as
                        // SourceFileScanner. Without this, an attacker-
                        // influenced branch could plant a symlink to
                        // /etc and the cache would happily snapshot it.
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    int children;
                    try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
                        children = (int) stream.count();
                    }
                    writer.write("d" + SEP + scanRootAbs
                            + SEP + dir.toAbsolutePath().toString()
                            + SEP + attrs.lastModifiedTime().toMillis()
                            + SEP + children);
                    writer.newLine();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    record DirSnapshot(String scanRoot, String path, long mtime, int fileCount) {
        static DirSnapshot parse(String row) {
            String[] parts = row.split(String.valueOf(SEP), -1);
            if (parts.length < 4) {
                throw new UncheckedIOException(new IOException(
                        "Malformed ProjectIndex cache 'd' row: " + LogSanitizer.sanitize(row)));
            }
            return new DirSnapshot(parts[0], parts[1], Long.parseLong(parts[2]), Integer.parseInt(parts[3]));
        }
    }

    /**
     * One on-disk row of Stage&nbsp;2 per-file metadata.
     *
     * <p>Wire shape (single line, tab-separated outer fields):
     * <pre>
     *   m\t&lt;absPath&gt;\t&lt;mtime&gt;\t&lt;size&gt;\t&lt;package&gt;\t&lt;primaryType?&gt;\t&lt;imports&gt;\t&lt;refSimples&gt;\t&lt;refDotted&gt;\t&lt;decls&gt;\t&lt;headerEdges&gt;
     * </pre>
     *
     * <p>Inner list separators avoid any character that can legally
     * appear inside a Java identifier or qualified name:
     * <ul>
     *   <li>{@code |} between list entries (and between {@code headerEdges} per-decl groups)</li>
     *   <li>{@code :} between an import name and its kind flag (n / s / w / sw),
     *       and between a {@code headerEdges} category label and its names</li>
     *   <li>{@code =} between a declaration's simple name and its supertype list</li>
     *   <li>{@code ,} between supertype names within a single declaration</li>
     *   <li>{@code @} between a {@code headerEdges} decl name and its category groups</li>
     *   <li>{@code ;} between {@code headerEdges} category groups within one decl</li>
     * </ul>
     * <p>Empty list fields write as the empty string between two tabs;
     * the reader produces {@link Set#of()} / {@link List#of()} for those.
     */
    record FileMetadataRow(String path, long mtime, long size, FileMetadata metadata) {

        /** Encodes a single per-file row. Caller adds the leading {@code m\t} prefix. */
        static String encode(Path file, long mtime, long size, FileMetadata md) {
            StringBuilder sb = new StringBuilder();
            sb.append(file.toAbsolutePath().toString());
            sb.append(SEP).append(mtime);
            sb.append(SEP).append(size);
            sb.append(SEP).append(md.packageName());
            sb.append(SEP).append(md.primaryTypeName() == null ? "" : md.primaryTypeName());
            sb.append(SEP).append(encodeImports(md.imports()));
            sb.append(SEP).append(joinList(md.typeRefSimpleNames()));
            sb.append(SEP).append(joinList(md.typeRefDottedNames()));
            sb.append(SEP).append(encodeDecls(md.typeDeclarations()));
            sb.append(SEP).append(encodeHeaderEdges(md.typeDeclarations()));
            return sb.toString();
        }

        /**
         * Parses a single per-file row body (the part after the
         * leading {@code m\t}). Returns {@code null} for any row that
         * does not have the expected number of fields, falls through
         * to a row-skip in the reader rather than aborting the whole
         * cache load — a single garbled row should not invalidate
         * every other file's cached metadata.
         */
        static FileMetadataRow parse(String row) {
            String[] parts = row.split(String.valueOf(SEP), -1);
            if (parts.length < 9) {
                return null;
            }
            try {
                String path = parts[0];
                long mtime = Long.parseLong(parts[1]);
                long size = Long.parseLong(parts[2]);
                String pkg = parts[3];
                String primary = parts[4].isEmpty() ? null : parts[4];
                List<FileMetadata.Import> imports = decodeImports(parts[5]);
                Set<String> refSimples = decodeSet(parts[6]);
                Set<String> refDotted = decodeSet(parts[7]);
                List<FileMetadata.TypeDecl> decls = decodeDecls(parts[8]);
                // Issue #132 — tenth field carries the per-decl
                // {@link FileMetadata.HeaderEdges}. Older snapshots
                // (v=4) lack the field; the schema-version check at
                // {@link Snapshot#read} short-circuits before any
                // m-row reaches here, but the defensive shape still
                // tolerates a missing tenth field by leaving every
                // decl's headerEdges at its EMPTY default — keeping
                // the parser usable on hand-edited snapshots.
                if (parts.length >= 10 && !parts[9].isEmpty()) {
                    decls = mergeHeaderEdges(decls, decodeHeaderEdges(parts[9]));
                }
                FileMetadata md = new FileMetadata(pkg, primary, imports, refSimples, refDotted, decls);
                return new FileMetadataRow(path, mtime, size, md);
            } catch (RuntimeException e) {
                return null;
            }
        }

        private static String encodeImports(List<FileMetadata.Import> imports) {
            if (imports.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < imports.size(); i++) {
                if (i > 0) sb.append(LIST_SEP);
                FileMetadata.Import imp = imports.get(i);
                sb.append(imp.name()).append(IMPORT_KIND_SEP).append(importFlag(imp));
            }
            return sb.toString();
        }

        private static String importFlag(FileMetadata.Import imp) {
            if (imp.isStatic() && imp.isAsterisk()) return "sw";
            if (imp.isStatic()) return "s";
            if (imp.isAsterisk()) return "w";
            return "n";
        }

        private static List<FileMetadata.Import> decodeImports(String raw) {
            if (raw.isEmpty()) return List.of();
            List<FileMetadata.Import> out = new ArrayList<>();
            for (String entry : raw.split("\\" + LIST_SEP, -1)) {
                int kindIdx = entry.lastIndexOf(IMPORT_KIND_SEP);
                if (kindIdx <= 0 || kindIdx == entry.length() - 1) continue;
                String name = entry.substring(0, kindIdx);
                String flag = entry.substring(kindIdx + 1);
                boolean isStatic = flag.equals("s") || flag.equals("sw");
                boolean isAsterisk = flag.equals("w") || flag.equals("sw");
                out.add(new FileMetadata.Import(name, isStatic, isAsterisk));
            }
            return out;
        }

        private static String joinList(Set<String> values) {
            if (values.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (String v : values) {
                if (!first) sb.append(LIST_SEP);
                sb.append(v);
                first = false;
            }
            return sb.toString();
        }

        private static Set<String> decodeSet(String raw) {
            if (raw.isEmpty()) return Set.of();
            Set<String> out = new LinkedHashSet<>();
            for (String entry : raw.split("\\" + LIST_SEP, -1)) {
                if (!entry.isEmpty()) out.add(entry);
            }
            return out;
        }

        private static String encodeDecls(List<FileMetadata.TypeDecl> decls) {
            if (decls.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < decls.size(); i++) {
                if (i > 0) sb.append(LIST_SEP);
                FileMetadata.TypeDecl d = decls.get(i);
                sb.append(d.simpleName());
                // Issue #132 (schema v6) — emit the qualified-name
                // disambiguator only when it differs from the simple
                // name, keeping the wire compact for the dominant
                // top-level-only case.
                if (!d.qualifiedName().equals(d.simpleName())) {
                    sb.append(QNAME_SEP).append(d.qualifiedName());
                }
                sb.append(DECL_SEP);
                List<String> sup = d.supertypeSimpleNames();
                for (int j = 0; j < sup.size(); j++) {
                    if (j > 0) sb.append(SUPER_SEP);
                    sb.append(sup.get(j));
                }
            }
            return sb.toString();
        }

        private static List<FileMetadata.TypeDecl> decodeDecls(String raw) {
            if (raw.isEmpty()) return List.of();
            List<FileMetadata.TypeDecl> out = new ArrayList<>();
            for (String entry : raw.split("\\" + LIST_SEP, -1)) {
                int eq = entry.indexOf(DECL_SEP);
                if (eq < 0) continue;
                String head = entry.substring(0, eq);
                String supRaw = entry.substring(eq + 1);
                String simple;
                String qualified;
                int tilde = head.indexOf(QNAME_SEP);
                if (tilde < 0) {
                    simple = head;
                    qualified = head;
                } else {
                    simple = head.substring(0, tilde);
                    qualified = head.substring(tilde + 1);
                }
                List<String> sup;
                if (supRaw.isEmpty()) {
                    sup = List.of();
                } else {
                    sup = new ArrayList<>();
                    for (String s : supRaw.split(String.valueOf(SUPER_SEP), -1)) {
                        if (!s.isEmpty()) sup.add(s);
                    }
                }
                out.add(new FileMetadata.TypeDecl(
                        simple, qualified, sup, FileMetadata.HeaderEdges.EMPTY));
            }
            return out;
        }

        /**
         * Issue&nbsp;#132 — encodes the per-decl
         * {@link FileMetadata.HeaderEdges} as a pipe-delimited list
         * of groups, one per decl that has non-empty header edges:
         * {@code Decl1@extends:Foo;implements:Bar|Decl2@type-bounds:T}.
         * Decls without header edges are omitted entirely (the
         * {@link FileMetadata.HeaderEdges#EMPTY} sentinel covers
         * them on decode). The empty string is the legal "no decl
         * has header edges" encoding.
         */
        private static String encodeHeaderEdges(List<FileMetadata.TypeDecl> decls) {
            if (decls.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (FileMetadata.TypeDecl d : decls) {
                FileMetadata.HeaderEdges he = d.headerEdges();
                if (he == null || he.isEmpty()) continue;
                if (!first) sb.append(LIST_SEP);
                first = false;
                // Issue #132 (schema v6) — key on the in-CU qualified
                // name so nested decls with the same simple name don't
                // collide on cache decode (correctness finding C1).
                sb.append(d.qualifiedName()).append(HEADER_DECL_SEP);
                boolean firstGroup = true;
                firstGroup = appendCategory(sb, firstGroup,
                        AffectedTestsConfig.HEADER_EDGE_EXTENDS, he.extendsSimpleNames());
                firstGroup = appendCategory(sb, firstGroup,
                        AffectedTestsConfig.HEADER_EDGE_IMPLEMENTS, he.implementsSimpleNames());
                firstGroup = appendCategory(sb, firstGroup,
                        AffectedTestsConfig.HEADER_EDGE_PERMITS, he.permittedSimpleNames());
                firstGroup = appendCategory(sb, firstGroup,
                        AffectedTestsConfig.HEADER_EDGE_TYPE_BOUNDS, he.typeBoundSimpleNames());
                firstGroup = appendCategory(sb, firstGroup,
                        AffectedTestsConfig.HEADER_EDGE_RECORD_COMPONENTS,
                        he.recordComponentSimpleNames());
                appendCategory(sb, firstGroup,
                        AffectedTestsConfig.HEADER_EDGE_ANNOTATIONS, he.annotationSimpleNames());
            }
            return sb.toString();
        }

        private static boolean appendCategory(StringBuilder sb, boolean first,
                                              String category, List<String> names) {
            if (names.isEmpty()) return first;
            if (!first) sb.append(HEADER_GROUP_SEP);
            sb.append(category).append(HEADER_CAT_SEP);
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) sb.append(SUPER_SEP);
                sb.append(names.get(i));
            }
            return false;
        }

        /**
         * Issue&nbsp;#132 (schema v6) — decodes the
         * {@link #encodeHeaderEdges} payload into a map of
         * {@code declQualifiedName -> HeaderEdges}. The map key is the
         * in-CU qualified name (e.g. {@code "Outer.Inner"} for nested
         * decls) so {@link #mergeHeaderEdges} can disambiguate two
         * nested decls with the same simple name (correctness finding
         * C1). Garbled groups are skipped (a single bad group inside an
         * otherwise-fine row shouldn't lose the whole row's header
         * edges); a malformed top-level segment is silently dropped via
         * the same {@code indexOf == -1} branch the supertype decoder
         * uses on the line above.
         */
        private static Map<String, FileMetadata.HeaderEdges> decodeHeaderEdges(String raw) {
            Map<String, FileMetadata.HeaderEdges> out = new LinkedHashMap<>();
            for (String entry : raw.split("\\" + LIST_SEP, -1)) {
                if (entry.isEmpty()) continue;
                int at = entry.indexOf(HEADER_DECL_SEP);
                if (at < 0) continue;
                String qualified = entry.substring(0, at);
                String groups = entry.substring(at + 1);
                List<String> extendsN = new ArrayList<>();
                List<String> implementsN = new ArrayList<>();
                List<String> permitsN = new ArrayList<>();
                List<String> boundsN = new ArrayList<>();
                List<String> componentsN = new ArrayList<>();
                List<String> annotationsN = new ArrayList<>();
                for (String group : groups.split(String.valueOf(HEADER_GROUP_SEP), -1)) {
                    int colon = group.indexOf(HEADER_CAT_SEP);
                    if (colon < 0) continue;
                    String category = group.substring(0, colon);
                    String namesRaw = group.substring(colon + 1);
                    List<String> sink = switch (category) {
                        case AffectedTestsConfig.HEADER_EDGE_EXTENDS -> extendsN;
                        case AffectedTestsConfig.HEADER_EDGE_IMPLEMENTS -> implementsN;
                        case AffectedTestsConfig.HEADER_EDGE_PERMITS -> permitsN;
                        case AffectedTestsConfig.HEADER_EDGE_TYPE_BOUNDS -> boundsN;
                        case AffectedTestsConfig.HEADER_EDGE_RECORD_COMPONENTS -> componentsN;
                        case AffectedTestsConfig.HEADER_EDGE_ANNOTATIONS -> annotationsN;
                        default -> null;
                    };
                    if (sink == null) continue;
                    for (String n : namesRaw.split(String.valueOf(SUPER_SEP), -1)) {
                        if (!n.isEmpty()) sink.add(n);
                    }
                }
                out.put(qualified, new FileMetadata.HeaderEdges(
                        extendsN, implementsN, permitsN,
                        boundsN, componentsN, annotationsN));
            }
            return out;
        }

        /**
         * Issue&nbsp;#132 (schema v6) — merges the post-pass header-edges
         * map back into the decl list produced by {@link #decodeDecls}.
         * Keyed on {@link FileMetadata.TypeDecl#qualifiedName()} so two
         * nested decls sharing a simple name (e.g.
         * {@code class A { class Z {} } class B { class Z {} }}) keep
         * their own header edges instead of collapsing onto a single
         * {@code Z} cache entry (correctness finding C1/ADV-HE-06).
         *
         * <p>Decls that don't appear in {@code byQualifiedName} keep
         * their default {@link FileMetadata.HeaderEdges#EMPTY}; decls
         * that do appear get a fresh {@link FileMetadata.TypeDecl}
         * carrying the loaded header edges. The original supertype list
         * and qualified name are preserved verbatim.
         */
        private static List<FileMetadata.TypeDecl> mergeHeaderEdges(
                List<FileMetadata.TypeDecl> decls,
                Map<String, FileMetadata.HeaderEdges> byQualifiedName) {
            if (byQualifiedName.isEmpty()) return decls;
            List<FileMetadata.TypeDecl> out = new ArrayList<>(decls.size());
            for (FileMetadata.TypeDecl d : decls) {
                FileMetadata.HeaderEdges he = byQualifiedName.get(d.qualifiedName());
                if (he == null) {
                    out.add(d);
                } else {
                    out.add(new FileMetadata.TypeDecl(
                            d.simpleName(), d.qualifiedName(),
                            d.supertypeSimpleNames(), he));
                }
            }
            return out;
        }
    }
}
