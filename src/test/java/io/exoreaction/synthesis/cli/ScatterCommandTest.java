package io.exoreaction.synthesis.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ScatterCommand} static helpers.
 */
class ScatterCommandTest {

    @TempDir
    Path workspace;

    // -------------------------------------------------------------------------
    // findEntityDirs
    // -------------------------------------------------------------------------

    @Test
    void findEntityDirs_exactMatch() throws IOException {
        Path dir = Files.createDirectories(workspace.resolve("clients/Mynder"));
        Files.writeString(dir.resolve("README.md"), "hello");

        List<Path> result = ScatterCommand.findEntityDirs(workspace, "Mynder");
        assertEquals(1, result.size());
        assertEquals(dir, result.get(0));
    }

    @Test
    void findEntityDirs_caseInsensitive() throws IOException {
        Path dir1 = Files.createDirectories(workspace.resolve("clients/MYNDER"));
        Files.writeString(dir1.resolve("notes.md"), "content");
        Path dir2 = Files.createDirectories(workspace.resolve("business/mynder"));
        Files.writeString(dir2.resolve("info.txt"), "content");

        List<Path> result = ScatterCommand.findEntityDirs(workspace, "Mynder");
        assertEquals(2, result.size());
    }

    @Test
    void findEntityDirs_subdirsExcluded() throws IOException {
        Path parent = Files.createDirectories(workspace.resolve("clients/Mynder"));
        Files.writeString(parent.resolve("file.md"), "content");
        Path child = Files.createDirectories(workspace.resolve("clients/Mynder/subproject-Mynder"));
        Files.writeString(child.resolve("file.md"), "content");

        List<Path> result = ScatterCommand.findEntityDirs(workspace, "Mynder");
        assertEquals(1, result.size());
        assertEquals(parent, result.get(0));
    }

    @Test
    void findEntityDirs_noMatch() throws IOException {
        Files.createDirectories(workspace.resolve("clients/Acme"));
        Files.writeString(workspace.resolve("clients/Acme/file.md"), "content");

        List<Path> result = ScatterCommand.findEntityDirs(workspace, "Mynder");
        assertTrue(result.isEmpty());
    }

    @Test
    void findEntityDirs_substringMatch() throws IOException {
        Path dir = Files.createDirectories(workspace.resolve("opportunity-Mynder-consulting"));
        Files.writeString(dir.resolve("file.md"), "content");

        List<Path> result = ScatterCommand.findEntityDirs(workspace, "Mynder");
        assertEquals(1, result.size());
        assertTrue(result.get(0).getFileName().toString().contains("Mynder"));
    }

    @Test
    void findEntityDirs_skipsHiddenDirectories() throws IOException {
        Path hidden = Files.createDirectories(workspace.resolve(".hidden/Mynder"));
        Files.writeString(hidden.resolve("file.md"), "content");

        List<Path> result = ScatterCommand.findEntityDirs(workspace, "Mynder");
        assertTrue(result.isEmpty());
    }

    // -------------------------------------------------------------------------
    // normalizeEntityName
    // -------------------------------------------------------------------------

    @Test
    void normalizeEntityName_prefixStripping() {
        assertEquals("mynder", ScatterCommand.normalizeEntityName("opportunity-Mynder"));
        assertEquals("acme", ScatterCommand.normalizeEntityName("client-Acme"));
        assertEquals("corp", ScatterCommand.normalizeEntityName("@active-Corp"));
        assertEquals("old-co", ScatterCommand.normalizeEntityName("@past-Old-Co"));
        assertEquals("bigco", ScatterCommand.normalizeEntityName("@opportunities-BigCo"));
    }

    @Test
    void normalizeEntityName_suffixStripping() {
        assertEquals("mynder", ScatterCommand.normalizeEntityName("Mynder-past"));
        assertEquals("acme", ScatterCommand.normalizeEntityName("Acme-active"));
        assertEquals("corp", ScatterCommand.normalizeEntityName("Corp-old"));
        assertEquals("bigco", ScatterCommand.normalizeEntityName("BigCo-archive"));
    }

    @Test
    void normalizeEntityName_noChange() {
        assertEquals("mynder", ScatterCommand.normalizeEntityName("Mynder"));
        assertEquals("acme-consulting", ScatterCommand.normalizeEntityName("Acme-Consulting"));
    }

    @Test
    void normalizeEntityName_underscoreReplacement() {
        assertEquals("my-entity", ScatterCommand.normalizeEntityName("My_Entity"));
    }

    @Test
    void normalizeEntityName_prefixAndSuffix() {
        assertEquals("mynder", ScatterCommand.normalizeEntityName("opportunity-Mynder-archive"));
    }

    // -------------------------------------------------------------------------
    // countFiles
    // -------------------------------------------------------------------------

    @Test
    void countFiles_recursiveCounting() throws IOException {
        Path dir = Files.createDirectories(workspace.resolve("project"));
        Files.writeString(dir.resolve("file1.md"), "content");
        Files.writeString(dir.resolve("file2.txt"), "content");
        Path sub = Files.createDirectories(dir.resolve("sub"));
        Files.writeString(sub.resolve("file3.java"), "content");

        assertEquals(3, ScatterCommand.countFiles(dir));
    }

    @Test
    void countFiles_emptyDirectory() throws IOException {
        Path dir = Files.createDirectories(workspace.resolve("empty"));
        assertEquals(0, ScatterCommand.countFiles(dir));
    }

    @Test
    void countFiles_singleFile() throws IOException {
        Path dir = Files.createDirectories(workspace.resolve("single"));
        Files.writeString(dir.resolve("only.txt"), "content");
        assertEquals(1, ScatterCommand.countFiles(dir));
    }

    @Test
    void countFiles_nonExistentDirectory() throws IOException {
        assertEquals(0, ScatterCommand.countFiles(workspace.resolve("nonexistent")));
    }

    // -------------------------------------------------------------------------
    // findContentMentions
    // -------------------------------------------------------------------------

    @Test
    void findContentMentions_findsMentions() throws IOException {
        // Create entity dir (to exclude)
        Path entityDir = Files.createDirectories(workspace.resolve("clients/Mynder"));
        Files.writeString(entityDir.resolve("internal.md"), "Mynder internal doc");

        // Create file outside with mentions
        Path outsideFile = workspace.resolve("PIPELINE-STATUS.md");
        Files.writeString(outsideFile, "Working with Mynder on project.\nMynder is a priority client.\n");

        Map<Path, Long> mentions = ScatterCommand.findContentMentions(
                workspace, "Mynder", List.of(entityDir));

        assertFalse(mentions.isEmpty());
        assertTrue(mentions.containsKey(outsideFile));
        assertEquals(2, mentions.get(outsideFile));
    }

    @Test
    void findContentMentions_caseInsensitive() throws IOException {
        Path file = workspace.resolve("notes.md");
        Files.writeString(file, "mynder is great. MYNDER is the best.");

        Map<Path, Long> mentions = ScatterCommand.findContentMentions(
                workspace, "Mynder", List.of());

        assertTrue(mentions.containsKey(file));
        assertEquals(2, mentions.get(file));
    }

    @Test
    void findContentMentions_excludesEntityDirs() throws IOException {
        Path entityDir = Files.createDirectories(workspace.resolve("clients/Mynder"));
        Path insideFile = Files.createDirectories(entityDir).resolve("report.md");
        Files.writeString(insideFile, "Mynder Mynder Mynder");

        Path outsideFile = workspace.resolve("other.txt");
        Files.writeString(outsideFile, "No mentions here.");

        Map<Path, Long> mentions = ScatterCommand.findContentMentions(
                workspace, "Mynder", List.of(entityDir));

        // Inside file should be excluded, outside file has no mentions
        assertFalse(mentions.containsKey(insideFile));
    }

    @Test
    void findContentMentions_onlyMdAndTxtFiles() throws IOException {
        Path mdFile = workspace.resolve("notes.md");
        Files.writeString(mdFile, "Mynder is mentioned here");

        Path txtFile = workspace.resolve("log.txt");
        Files.writeString(txtFile, "Mynder appears in logs");

        Path javaFile = workspace.resolve("Code.java");
        Files.writeString(javaFile, "String client = \"Mynder\";");

        Map<Path, Long> mentions = ScatterCommand.findContentMentions(
                workspace, "Mynder", List.of());

        assertTrue(mentions.containsKey(mdFile));
        assertTrue(mentions.containsKey(txtFile));
        assertFalse(mentions.containsKey(javaFile));
    }

    @Test
    void findContentMentions_noMentions() throws IOException {
        Path file = workspace.resolve("empty.md");
        Files.writeString(file, "Nothing relevant here at all.");

        Map<Path, Long> mentions = ScatterCommand.findContentMentions(
                workspace, "Mynder", List.of());

        assertTrue(mentions.isEmpty());
    }

    // -------------------------------------------------------------------------
    // findFragmentedEntities
    // -------------------------------------------------------------------------

    @Test
    void findFragmentedEntities_groupsByNormalizedName() throws IOException {
        // Create two dirs with same normalized name in different locations
        Path dir1 = Files.createDirectories(workspace.resolve("clients/opportunity-Acme"));
        Files.writeString(dir1.resolve("file1.md"), "content");
        Files.writeString(dir1.resolve("file2.md"), "content");

        Path dir2 = Files.createDirectories(workspace.resolve("business/acme"));
        Files.writeString(dir2.resolve("file3.md"), "content");

        List<ScatterCommand.EntityGroup> groups = ScatterCommand.findFragmentedEntities(workspace, 10);

        assertFalse(groups.isEmpty());
        // Find the acme group
        ScatterCommand.EntityGroup acmeGroup = groups.stream()
                .filter(g -> g.canonicalName().equalsIgnoreCase("acme"))
                .findFirst()
                .orElse(null);
        assertNotNull(acmeGroup, "Should find 'acme' fragmented entity");
        assertEquals(2, acmeGroup.locations().size());
        assertEquals(3, acmeGroup.totalFiles());
    }

    @Test
    void findFragmentedEntities_requires2PlusLocations() throws IOException {
        // Only one location should not show up
        Path dir = Files.createDirectories(workspace.resolve("clients/UniqueEntity"));
        Files.writeString(dir.resolve("file.md"), "content");

        List<ScatterCommand.EntityGroup> groups = ScatterCommand.findFragmentedEntities(workspace, 10);

        boolean found = groups.stream()
                .anyMatch(g -> g.canonicalName().equalsIgnoreCase("uniqueentity"));
        assertFalse(found, "Single-location entities should not appear");
    }

    @Test
    void findFragmentedEntities_ancestorDescendantNotFragmented() throws IOException {
        // Parent and child with same normalized name should deduplicate
        Path parent = Files.createDirectories(workspace.resolve("clients/acme"));
        Files.writeString(parent.resolve("file.md"), "content");
        Path child = Files.createDirectories(workspace.resolve("clients/acme/sub-acme"));
        Files.writeString(child.resolve("file.md"), "content");

        List<ScatterCommand.EntityGroup> groups = ScatterCommand.findFragmentedEntities(workspace, 10);

        // After dedup, "acme" should have only 1 root location (parent),
        // so it should NOT appear as fragmented
        boolean found = groups.stream()
                .anyMatch(g -> g.canonicalName().equalsIgnoreCase("acme"));
        assertFalse(found, "Ancestor/descendant pairs should not count as fragmented");
    }

    @Test
    void findFragmentedEntities_sortsByTotalFilesDescending() throws IOException {
        // Create two fragmented entities with different file counts
        Path bigDir1 = Files.createDirectories(workspace.resolve("area1/bigco"));
        for (int i = 0; i < 5; i++) Files.writeString(bigDir1.resolve("f" + i + ".md"), "c");
        Path bigDir2 = Files.createDirectories(workspace.resolve("area2/client-bigco"));
        for (int i = 0; i < 5; i++) Files.writeString(bigDir2.resolve("f" + i + ".md"), "c");

        Path smallDir1 = Files.createDirectories(workspace.resolve("area1/smallco"));
        Files.writeString(smallDir1.resolve("f.md"), "c");
        Path smallDir2 = Files.createDirectories(workspace.resolve("area2/client-smallco"));
        Files.writeString(smallDir2.resolve("f.md"), "c");

        List<ScatterCommand.EntityGroup> groups = ScatterCommand.findFragmentedEntities(workspace, 10);

        assertTrue(groups.size() >= 2);
        // First group should have more total files than second
        assertTrue(groups.get(0).totalFiles() >= groups.get(1).totalFiles(),
                "Should be sorted by total files descending");
    }

    @Test
    void findFragmentedEntities_respectsTopN() throws IOException {
        // Create 3 fragmented entities
        for (String name : List.of("alpha", "beta", "gamma")) {
            Path d1 = Files.createDirectories(workspace.resolve("area1/" + name));
            Files.writeString(d1.resolve("f.md"), "c");
            Path d2 = Files.createDirectories(workspace.resolve("area2/" + name));
            Files.writeString(d2.resolve("f.md"), "c");
        }

        List<ScatterCommand.EntityGroup> groups = ScatterCommand.findFragmentedEntities(workspace, 2);
        assertTrue(groups.size() <= 2, "Should respect topN limit");
    }

    @Test
    void findFragmentedEntities_emptyWorkspace() throws IOException {
        List<ScatterCommand.EntityGroup> groups = ScatterCommand.findFragmentedEntities(workspace, 10);
        assertTrue(groups.isEmpty());
    }

    @Test
    void findFragmentedEntities_skipsHiddenDirectories() throws IOException {
        Path hidden1 = Files.createDirectories(workspace.resolve(".hidden/secret"));
        Files.writeString(hidden1.resolve("f.md"), "c");
        Path hidden2 = Files.createDirectories(workspace.resolve("area2/.config/secret"));
        Files.writeString(hidden2.resolve("f.md"), "c");

        List<ScatterCommand.EntityGroup> groups = ScatterCommand.findFragmentedEntities(workspace, 10);
        boolean found = groups.stream()
                .anyMatch(g -> g.canonicalName().equalsIgnoreCase("secret"));
        assertFalse(found, "Hidden directories should be skipped");
    }
}
