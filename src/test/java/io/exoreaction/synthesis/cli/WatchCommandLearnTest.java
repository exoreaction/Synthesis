package io.exoreaction.synthesis.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link WatchCommand} learn mode (--learn flag) file detection.
 */
class WatchCommandLearnTest {

    @TempDir
    Path tempDir;

    private final WatchCommand command = new WatchCommand();

    // --- isOrganizationalFile ---

    @Test
    void isOrganizationalFile_organizationsJson() {
        Path file = tempDir.resolve(".synthesis/organizations.json");
        assertTrue(command.isOrganizationalFile(file, tempDir));
    }

    @Test
    void isOrganizationalFile_pipelineStatus() {
        Path file = tempDir.resolve("eXOReaction/business/PIPELINE-STATUS.md");
        assertTrue(command.isOrganizationalFile(file, tempDir));
    }

    @Test
    void isOrganizationalFile_proofPoints() {
        Path file = tempDir.resolve("eXOReaction/PROOF-POINTS.md");
        assertTrue(command.isOrganizationalFile(file, tempDir));
    }

    @Test
    void isOrganizationalFile_codebaseIndex() {
        Path file = tempDir.resolve("Cantara/CODEBASE-INDEX.md");
        assertTrue(command.isOrganizationalFile(file, tempDir));
    }

    @Test
    void isOrganizationalFile_clientsDirectory() {
        Path file = tempDir.resolve("eXOReaction/clients/opportunity-NewClient/README.md");
        assertTrue(command.isOrganizationalFile(file, tempDir));
    }

    @Test
    void isOrganizationalFile_productsDirectory() {
        Path file = tempDir.resolve("eXOReaction/products/new-product/README.md");
        assertTrue(command.isOrganizationalFile(file, tempDir));
    }

    @Test
    void isOrganizationalFile_readmeInOrgDir() {
        Path file = tempDir.resolve("eXOReaction/README.md");
        assertTrue(command.isOrganizationalFile(file, tempDir));
    }

    @Test
    void isOrganizationalFile_regularFile_false() {
        Path file = tempDir.resolve("eXOReaction/marketing/linkedin/post.md");
        assertFalse(command.isOrganizationalFile(file, tempDir));
    }

    @Test
    void isOrganizationalFile_deepReadme_false() {
        Path file = tempDir.resolve("eXOReaction/business/strategy/README.md");
        // This is not a first-level org README, and not in clients/ or products/
        assertFalse(command.isOrganizationalFile(file, tempDir));
    }

    @Test
    void isOrganizationalFile_configFile_false() {
        Path file = tempDir.resolve(".synthesis/config.yaml");
        assertFalse(command.isOrganizationalFile(file, tempDir));
    }
}
