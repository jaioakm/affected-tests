package io.affectedtests.core;

import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.discovery.HeaderEdgesStrategy;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue&nbsp;#132 — end-to-end coverage of the {@code headerEdges}
 * strategy wired through {@link AffectedTestsEngine}. The
 * {@link io.affectedtests.core.discovery.HeaderEdgesStrategy}'s own
 * unit tests pin the augmentation algorithm in isolation; this file
 * proves the augmented FQN set actually reaches the
 * naming / usage / impl / transitive strategies and surfaces tests
 * that the pre-issue-#132 pipeline silently missed.
 *
 * <p>Each test commits a project tree, runs the engine, and asserts
 * on {@link AffectedTestsEngine.AffectedTestsResult#testClassFqns()}
 * — exactly the contract Gradle's dispatch path consumes.
 */
class AffectedTestsEngineHeaderEdgesTest {

    @TempDir
    Path tempDir;

    private Git initRepoWithInitialCommit() throws Exception {
        Git git = Git.init().setDirectory(tempDir.toFile()).call();
        File readme = tempDir.resolve("README.md").toFile();
        Files.writeString(readme.toPath(), "# init");
        git.add().addFilepattern("README.md").call();
        git.commit().setMessage("initial commit").call();
        return git;
    }

    private void writeSource(String relativePath, String source) throws Exception {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    @Test
    void springDiGapClosedByImplementsEdge() throws Exception {
        // Issue #132 headline motivating case. {@code StripeGateway
        // implements PaymentGateway} — the diff changes only
        // {@code StripeGateway}, but {@code PaymentGatewayTest}
        // (the interface contract test) must fire. Without the
        // implements edge, the four pre-existing strategies see
        // {@code StripeGateway} only and the interface contract
        // test is silently missed.
        try (Git git = initRepoWithInitialCommit()) {
            // Baseline: both files exist, both tests exist, all on
            // master. The diff is added in a second commit so the
            // engine has a non-empty changeset to scan.
            writeSource("src/main/java/com/example/api/PaymentGateway.java",
                    "package com.example.api;\npublic interface PaymentGateway {}");
            writeSource("src/main/java/com/example/impl/StripeGateway.java",
                    "package com.example.impl;\n"
                            + "import com.example.api.PaymentGateway;\n"
                            + "public class StripeGateway implements PaymentGateway {}");
            writeSource("src/test/java/com/example/api/PaymentGatewayTest.java",
                    "package com.example.api;\npublic class PaymentGatewayTest {}");
            writeSource("src/test/java/com/example/impl/StripeGatewayTest.java",
                    "package com.example.impl;\npublic class StripeGatewayTest {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("baseline").call();
            String base = git.log().call().iterator().next().getName();

            // The diff: change a single line in StripeGateway only.
            // PaymentGatewayTest must still fire because of the
            // implements edge.
            writeSource("src/main/java/com/example/impl/StripeGateway.java",
                    "package com.example.impl;\n"
                            + "import com.example.api.PaymentGateway;\n"
                            + "// touched\npublic class StripeGateway implements PaymentGateway {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("touch StripeGateway").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    // Use the issue-#132 augmentation feeding naming
                    // (naming maps {@code PaymentGateway} →
                    // {@code PaymentGatewayTest}).
                    .strategies(Set.of(
                            AffectedTestsConfig.STRATEGY_NAMING,
                            AffectedTestsConfig.STRATEGY_HEADER_EDGES))
                    .transitiveDepth(0)
                    // Wipe the default ignore globs — the unit-test
                    // tree uses {@code com.example.**} which isn't on
                    // the default list, but a future change adding
                    // {@code com.example.**} would silently break
                    // this test. Explicit empty list keeps the test
                    // independent of the default list.
                    .headerEdgesIgnore(List.of())
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertTrue(result.testClassFqns().contains("com.example.impl.StripeGatewayTest"),
                    "the direct test for the changed class must still fire — "
                            + "the strategy is purely additive");
            assertTrue(result.testClassFqns().contains("com.example.api.PaymentGatewayTest"),
                    "issue #132 headline case: PaymentGatewayTest must fire because "
                            + "StripeGateway implements PaymentGateway — without the headerEdges "
                            + "strategy this test is silently missed");

            HeaderEdgesStrategy.AugmentationResult he = result.headerEdgesAugmentation();
            assertNotNull(he, "result must thread the diagnostic augmentation through");
            assertTrue(he.augmentedTypes().contains("com.example.api.PaymentGateway"),
                    "the diagnostic side-channel must record the augmentation that fired");
        }
    }

    @Test
    void killSwitchRestoresPreIssue132Behaviour() throws Exception {
        // {@code headerEdgesEnabled = false} is the one-flag escape
        // hatch. With the kill switch on, the same Spring DI Gap
        // scenario must NOT pick up PaymentGatewayTest — that's the
        // pre-issue-#132 behaviour adopters can fall back to if the
        // augmentation regresses something in their tree.
        try (Git git = initRepoWithInitialCommit()) {
            writeSource("src/main/java/com/example/api/PaymentGateway.java",
                    "package com.example.api;\npublic interface PaymentGateway {}");
            writeSource("src/main/java/com/example/impl/StripeGateway.java",
                    "package com.example.impl;\n"
                            + "import com.example.api.PaymentGateway;\n"
                            + "public class StripeGateway implements PaymentGateway {}");
            writeSource("src/test/java/com/example/api/PaymentGatewayTest.java",
                    "package com.example.api;\npublic class PaymentGatewayTest {}");
            writeSource("src/test/java/com/example/impl/StripeGatewayTest.java",
                    "package com.example.impl;\npublic class StripeGatewayTest {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("baseline").call();
            String base = git.log().call().iterator().next().getName();

            writeSource("src/main/java/com/example/impl/StripeGateway.java",
                    "package com.example.impl;\n"
                            + "import com.example.api.PaymentGateway;\n"
                            + "// touched\npublic class StripeGateway implements PaymentGateway {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("touch StripeGateway").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .strategies(Set.of(AffectedTestsConfig.STRATEGY_NAMING))
                    .transitiveDepth(0)
                    .headerEdgesEnabled(false)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertTrue(result.testClassFqns().contains("com.example.impl.StripeGatewayTest"),
                    "the direct test for the changed class must still fire");
            assertFalse(result.testClassFqns().contains("com.example.api.PaymentGatewayTest"),
                    "kill switch must restore pre-issue-#132 behaviour — "
                            + "PaymentGatewayTest is the silently-missed test the strategy "
                            + "exists to rescue, so killing the strategy must un-rescue it");
        }
    }

    @Test
    void siblingCapSuppressesImplWalkExplosionInTheEngine() throws Exception {
        // End-to-end version of the BaseController explosion case.
        // When PaymentController (the diff) extends BaseController
        // (52 subtypes), {@code impl} would walk DOWN from
        // BaseController through every {@code *Controller} and
        // select every {@code *ControllerTest} in the codebase.
        // The sibling cap suppresses that walk, so the impl strategy
        // skips the explosion — PaymentControllerTest still fires
        // via naming on PaymentController, and BaseControllerTest
        // still fires via naming on the header-edge-augmented
        // BaseController, but the unrelated subtypes' tests do NOT.
        try (Git git = initRepoWithInitialCommit()) {
            writeSource("src/main/java/com/example/BaseController.java",
                    "package com.example;\npublic class BaseController {}");
            writeSource("src/test/java/com/example/BaseControllerTest.java",
                    "package com.example;\npublic class BaseControllerTest {}");
            // Six unrelated controllers + their tests. With
            // maxSiblings=5, the cap fires.
            for (int i = 0; i < 6; i++) {
                writeSource("src/main/java/com/example/Sub" + i + "Controller.java",
                        "package com.example;\n"
                                + "public class Sub" + i + "Controller extends BaseController {}");
                writeSource("src/test/java/com/example/Sub" + i + "ControllerTest.java",
                        "package com.example;\npublic class Sub" + i + "ControllerTest {}");
            }
            // PaymentController: the only one in the diff.
            writeSource("src/main/java/com/example/PaymentController.java",
                    "package com.example;\n"
                            + "public class PaymentController extends BaseController {}");
            writeSource("src/test/java/com/example/PaymentControllerTest.java",
                    "package com.example;\npublic class PaymentControllerTest {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("baseline").call();
            String base = git.log().call().iterator().next().getName();

            writeSource("src/main/java/com/example/PaymentController.java",
                    "package com.example;\n"
                            + "// touched\npublic class PaymentController extends BaseController {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("touch PaymentController").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .strategies(Set.of(
                            AffectedTestsConfig.STRATEGY_NAMING,
                            AffectedTestsConfig.STRATEGY_IMPL,
                            AffectedTestsConfig.STRATEGY_HEADER_EDGES))
                    .transitiveDepth(0)
                    .headerEdgesMaxSiblings(5)
                    .headerEdgesIgnore(List.of())
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertTrue(result.testClassFqns().contains("com.example.PaymentControllerTest"),
                    "the directly-changed class's test must still fire");
            assertTrue(result.testClassFqns().contains("com.example.BaseControllerTest"),
                    "the header-edge-added type still participates in naming — "
                            + "BaseControllerTest fires for free");
            for (int i = 0; i < 6; i++) {
                assertFalse(result.testClassFqns().contains("com.example.Sub" + i + "ControllerTest"),
                        "sibling-cap suppression must keep the impl walk from "
                                + "selecting unrelated subtype tests");
            }

            HeaderEdgesStrategy.AugmentationResult he = result.headerEdgesAugmentation();
            assertTrue(he.suppressedFromImplWalk().contains("com.example.BaseController"),
                    "the diagnostic side-channel must surface the suppressed type so "
                            + "--explain can show why the explosion didn't happen");
        }
    }
}
