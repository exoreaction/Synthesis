package io.exoreaction.synthesis.kcp;

import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.analyzer.YamlAnalyzer;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Governed-skill generation (issue #477): discovery across all three skill
 * layouts, fail-closed action_scope inference, marker-block manifest surgery,
 * and refresh-safe round-trips. Follows the KcpScaffolderTest pattern:
 * fake repos in a temp dir, direct calls, substring asserts with full output
 * in the message.
 */
class KcpSkillScaffolderTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------

    private Path skillsDir() throws IOException {
        Path dir = tempDir.resolve(".claude/skills");
        Files.createDirectories(dir);
        return dir;
    }

    private void writeYamlSkill(Path skillsDir) throws IOException {
        Files.writeString(skillsDir.resolve("demo-release.yaml"), """
                name: demo-release
                version: 1.0.0
                description: |
                  How do I cut a release safely? Version bump, changelog, tag.
                trigger_phrases:
                  - "cut a release"
                  - "release and tag"
                instructions: |
                  # Release
                  Bump the version in pom.xml, then:
                  ```bash
                  mvn clean package
                  git tag v1.0.0
                  ```
                """);
    }

    private void writeMdSkill(Path skillsDir) throws IOException {
        Files.writeString(skillsDir.resolve("demo-patterns.md"), """
                # Agent Patterns

                ## Pattern One

                Read docs/guide.md before starting.
                """);
    }

    private void writeSubdirSkill(Path skillsDir) throws IOException {
        Path sub = skillsDir.resolve("demo-deploy");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("SKILL.md"), """
                ---
                name: demo-deploy
                description: Deploy the service.
                ---
                # Deploy

                Run git push, then check the workflow.
                """);
    }

    private void writeRepoFiles() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Files.createDirectories(tempDir.resolve("docs"));
        Files.writeString(tempDir.resolve("docs/guide.md"), "# Guide");
    }

    // -------------------------------------------------------------------
    // Discovery
    // -------------------------------------------------------------------

    @Test
    void collectsAllThreeSkillLayouts() throws IOException {
        Path dir = skillsDir();
        writeYamlSkill(dir);
        writeMdSkill(dir);
        writeSubdirSkill(dir);

        var skills = KcpSkillScaffolder.collectSkills(tempDir);
        var names = skills.stream().map(KcpSkillScaffolder.SkillSource::name).sorted().toList();
        assertEquals(List.of("demo-deploy", "demo-patterns", "demo-release"), names,
                "all three layouts (flat yaml, flat md, dir/SKILL.md) must be discovered: " + names);
    }

    @Test
    void noSkillsDirectoryYieldsEmpty() {
        assertTrue(KcpSkillScaffolder.collectSkills(tempDir).isEmpty());
    }

    @Test
    void malformedYamlSkillIsSkippedNotGuessed() throws IOException {
        Path dir = skillsDir();
        Files.writeString(dir.resolve("broken.yaml"), "name: [unclosed");
        writeMdSkill(dir);
        var skills = KcpSkillScaffolder.collectSkills(tempDir);
        assertEquals(1, skills.size(), "broken yaml must be skipped: " + skills);
        assertEquals("demo-patterns", skills.get(0).name());
    }

    // -------------------------------------------------------------------
    // Inference — deterministic, evidence-based, fail-closed
    // -------------------------------------------------------------------

    @Test
    void inferToolsFromPlaybookEvidence() {
        assertEquals(List.of("read"), KcpSkillScaffolder.inferTools("just prose"));
        assertEquals(List.of("read", "bash"),
                KcpSkillScaffolder.inferTools("```bash\nmvn test\n```"));
        assertEquals(List.of("read", "bash", "git"),
                KcpSkillScaffolder.inferTools("run `mvn package` then git tag v1"));
        assertEquals(List.of("read", "git"), KcpSkillScaffolder.inferTools("use git rebase"));
        assertEquals(List.of("read"), KcpSkillScaffolder.inferTools(null));
    }

    @Test
    void inferPathsOnlyKeepsExistingRepoPaths() throws IOException {
        writeRepoFiles();
        String body = "Edit pom.xml and docs/guide.md; ignore missing/thing.md, "
                + "/etc/passwd, ../escape.md, and https://example.com/a.md";
        List<String> paths = KcpSkillScaffolder.inferPaths(tempDir, body);
        assertEquals(List.of("docs/guide.md", "pom.xml"), paths,
                "only repo-existing, relative, non-escaping paths survive: " + paths);
    }

    @Test
    void inferPathsFailsClosedOnNoEvidence() {
        assertTrue(KcpSkillScaffolder.inferPaths(tempDir, "no file mentions here").isEmpty());
    }

    // -------------------------------------------------------------------
    // Block emission
    // -------------------------------------------------------------------

    @Test
    void skillsBlockEmitsSpecConformantUnits() throws IOException {
        Path dir = skillsDir();
        writeYamlSkill(dir);
        writeRepoFiles();

        String block = KcpSkillScaffolder.skillsBlock(tempDir,
                KcpSkillScaffolder.collectSkills(tempDir));

        assertTrue(block.startsWith(KcpSkillScaffolder.BLOCK_BEGIN), block);
        assertTrue(block.endsWith(KcpSkillScaffolder.BLOCK_END + "\n"), block);
        assertTrue(block.contains("  - id: demo-release\n"), block);
        assertTrue(block.contains("    path: .claude/skills/demo-release.yaml\n"), block);
        assertTrue(block.contains("    kind: skill\n"), block);
        assertTrue(block.contains("    audience: [agent, operator]\n"), block);
        assertTrue(block.contains("    intent: \"How do I cut a release safely?\""), block);
        assertTrue(block.contains("    triggers: [cut-a-release, release-and-tag]\n"), block);
        assertTrue(block.contains("      tools: [read, bash, git]\n"), block);
        assertTrue(block.contains("      paths: [\"pom.xml\"]\n"), block);
        assertFalse(block.contains("capabilities"),
                "capabilities are never invented (fail-closed): " + block);
        assertTrue(block.contains("      algorithm: sha256\n"), block);
    }

    @Test
    void emptySkillListYieldsEmptyBlock() {
        assertEquals("", KcpSkillScaffolder.skillsBlock(tempDir, List.of()));
    }

    // -------------------------------------------------------------------
    // Manifest surgery
    // -------------------------------------------------------------------

    private String scaffoldedManifest() throws IOException {
        Files.writeString(tempDir.resolve("README.md"), "# Demo\n\nA demo repo.\n");
        String manifest = KcpScaffolder.scaffold(tempDir, "9.9.9", java.util.Map.of());
        assertNotNull(manifest);
        return manifest;
    }

    @Test
    void mergeAppendsBlockAndBumpsVersionAndCount() throws IOException {
        String manifest = scaffoldedManifest();
        Path dir = skillsDir();
        writeYamlSkill(dir);
        String block = KcpSkillScaffolder.skillsBlock(tempDir,
                KcpSkillScaffolder.collectSkills(tempDir));

        String merged = KcpSkillScaffolder.mergeSkillsBlock(manifest, block);

        assertTrue(merged.contains("kcp_version: \"0.26\""), merged);
        assertTrue(merged.contains("(KCP) v0.26"), merged);
        assertTrue(merged.contains("  - id: demo-release\n"), merged);
        long unitCount = merged.lines().filter(l -> l.startsWith("  - id: ")).count();
        assertTrue(merged.contains("  unit_count: " + unitCount + "\n"),
                "unit_count must be recomputed to " + unitCount + ": " + merged);
    }

    @Test
    void mergeIsIdempotentOnRegeneration() throws IOException {
        String manifest = scaffoldedManifest();
        Path dir = skillsDir();
        writeYamlSkill(dir);
        String block = KcpSkillScaffolder.skillsBlock(tempDir,
                KcpSkillScaffolder.collectSkills(tempDir));

        String once = KcpSkillScaffolder.mergeSkillsBlock(manifest, block);
        String twice = KcpSkillScaffolder.mergeSkillsBlock(once, block);
        assertEquals(once, twice, "re-merging the same block must be a no-op");
    }

    @Test
    void mergeInsertsBeforeTopLevelSigningBlock() throws IOException {
        String manifest = scaffoldedManifest()
                + "signing:\n  algorithm: ed25519\n  key_id: test\n";
        Path dir = skillsDir();
        writeYamlSkill(dir);
        String block = KcpSkillScaffolder.skillsBlock(tempDir,
                KcpSkillScaffolder.collectSkills(tempDir));

        String merged = KcpSkillScaffolder.mergeSkillsBlock(manifest, block);
        int blockIdx = merged.indexOf(KcpSkillScaffolder.BLOCK_BEGIN);
        int signingIdx = merged.indexOf("\nsigning:");
        assertTrue(blockIdx >= 0 && signingIdx > blockIdx,
                "skills block must sit before the top-level signing block: " + merged);
    }

    @Test
    void stripAndExtractRoundTrip() throws IOException {
        String manifest = scaffoldedManifest();
        Path dir = skillsDir();
        writeYamlSkill(dir);
        String block = KcpSkillScaffolder.skillsBlock(tempDir,
                KcpSkillScaffolder.collectSkills(tempDir));
        String merged = KcpSkillScaffolder.mergeSkillsBlock(manifest, block);

        assertEquals(block, KcpSkillScaffolder.extractSkillsBlock(merged),
                "extract must return the exact block that was merged");
        String stripped = KcpSkillScaffolder.stripSkillsBlock(merged);
        assertFalse(stripped.contains("kind: skill"), stripped);
        assertNull(KcpSkillScaffolder.extractSkillsBlock(stripped));
    }

    @Test
    void mergingEmptyBlockRemovesExistingOne() throws IOException {
        String manifest = scaffoldedManifest();
        Path dir = skillsDir();
        writeYamlSkill(dir);
        String block = KcpSkillScaffolder.skillsBlock(tempDir,
                KcpSkillScaffolder.collectSkills(tempDir));
        String merged = KcpSkillScaffolder.mergeSkillsBlock(manifest, block);

        String removed = KcpSkillScaffolder.mergeSkillsBlock(merged, "");
        assertFalse(removed.contains("kind: skill"), removed);
        long unitCount = removed.lines().filter(l -> l.startsWith("  - id: ")).count();
        assertTrue(removed.contains("  unit_count: " + unitCount + "\n"), removed);
    }

    @Test
    void standaloneManifestIsSelfContained() throws IOException {
        Path dir = skillsDir();
        writeYamlSkill(dir);
        String block = KcpSkillScaffolder.skillsBlock(tempDir,
                KcpSkillScaffolder.collectSkills(tempDir));

        String manifest = KcpSkillScaffolder.standaloneManifest(tempDir, "9.9.9", block);
        assertTrue(manifest.contains("kcp_version: \"0.26\""), manifest);
        assertTrue(manifest.contains("generated_by: synthesis@9.9.9"), manifest);
        assertTrue(manifest.contains("  unit_count: 1\n"), manifest);
        assertTrue(manifest.contains("  - id: demo-release\n"), manifest);
    }

    // -------------------------------------------------------------------
    // Round trip through the KCP parser — kind: skill must survive
    // -------------------------------------------------------------------

    @Test
    void mergedManifestParsesAsKcpWithSkillUnit() throws Exception {
        String manifest = scaffoldedManifest();
        Path dir = skillsDir();
        writeYamlSkill(dir);
        writeRepoFiles();
        String merged = KcpSkillScaffolder.mergeSkillsBlock(manifest,
                KcpSkillScaffolder.skillsBlock(tempDir, KcpSkillScaffolder.collectSkills(tempDir)));

        Path out = tempDir.resolve("knowledge.yaml");
        Files.writeString(out, merged);
        FileMetadata metadata = new FileMetadata(
                out, "knowledge.yaml", "knowledge.yaml",
                ".yaml", FileUtils.FileType.YAML, null,
                Files.size(out), Instant.now(), null);
        AnalysisResult result = new YamlAnalyzer().analyze(metadata);

        assertEquals("kcp-manifest", result.metrics().get("yamlType"),
                "merged manifest must still be detected as KCP: " + merged);
        assertEquals("0.26", result.metrics().get("kcpVersion"), merged);

        @SuppressWarnings("unchecked")
        var units = (java.util.List<KcpUnit>) result.metrics().get("kcpUnits");
        KcpUnit skillUnit = units.stream().filter(u -> "demo-release".equals(u.unitId()))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "skill unit must survive the parser round trip: " + units));
        assertNotNull(skillUnit.extensionsJson(),
                "kind/action_scope land in extensions (lossless ingestion): " + skillUnit);
        assertTrue(skillUnit.extensionsJson().contains("skill"),
                "kind: skill must be preserved in extensions: " + skillUnit.extensionsJson());
        assertTrue(skillUnit.extensionsJson().contains("action_scope"),
                "action_scope must be preserved in extensions: " + skillUnit.extensionsJson());
    }
}
