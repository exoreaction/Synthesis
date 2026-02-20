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
 * Unit tests for {@link NamingCommand} static analysis helpers.
 */
class NamingCommandTest {

    @TempDir
    Path workspace;

    // -------------------------------------------------------------------------
    // isSingularPlural
    // -------------------------------------------------------------------------

    @Test
    void isSingularPlural_detectsSimplePlural() {
        assertTrue(NamingCommand.isSingularPlural("product", "products"));
        assertTrue(NamingCommand.isSingularPlural("products", "product"));
    }

    @Test
    void isSingularPlural_detectsEsPlural() {
        assertTrue(NamingCommand.isSingularPlural("business", "businesses"));
        assertTrue(NamingCommand.isSingularPlural("businesses", "business"));
    }

    @Test
    void isSingularPlural_returnsFalseForUnrelated() {
        assertFalse(NamingCommand.isSingularPlural("finance", "financials"));
    }

    @Test
    void isSingularPlural_caseInsensitive() {
        assertTrue(NamingCommand.isSingularPlural("Product", "products"));
        assertTrue(NamingCommand.isSingularPlural("PRODUCT", "products"));
    }

    @Test
    void isSingularPlural_sameNameReturnsFalse() {
        assertFalse(NamingCommand.isSingularPlural("product", "product"));
    }

    // -------------------------------------------------------------------------
    // findSingularPluralCollisions
    // -------------------------------------------------------------------------

    @Test
    void findSingularPluralCollisions_detectsCollision() throws IOException {
        Files.createDirectories(workspace.resolve("product"));
        Files.createDirectories(workspace.resolve("products"));

        List<NamingCommand.Pair> collisions = NamingCommand.findSingularPluralCollisions(workspace, 6);
        assertEquals(1, collisions.size());

        NamingCommand.Pair pair = collisions.get(0);
        // Both dirs should be under the workspace
        assertTrue(pair.dir1().getFileName().toString().startsWith("product"));
        assertTrue(pair.dir2().getFileName().toString().startsWith("product"));
    }

    @Test
    void findSingularPluralCollisions_noCollision_returnsEmpty() throws IOException {
        Files.createDirectories(workspace.resolve("finance"));
        Files.createDirectories(workspace.resolve("marketing"));

        List<NamingCommand.Pair> collisions = NamingCommand.findSingularPluralCollisions(workspace, 6);
        assertTrue(collisions.isEmpty());
    }

    @Test
    void findSingularPluralCollisions_nestedCollision() throws IOException {
        // Collision in a subdirectory, not at root
        Files.createDirectories(workspace.resolve("business/client"));
        Files.createDirectories(workspace.resolve("business/clients"));

        List<NamingCommand.Pair> collisions = NamingCommand.findSingularPluralCollisions(workspace, 6);
        assertEquals(1, collisions.size());
    }

    // -------------------------------------------------------------------------
    // findSemanticDuplicates
    // -------------------------------------------------------------------------

    @Test
    void findSemanticDuplicates_detectsNearDuplicates() throws IOException {
        // "report" vs "reports" would be singular/plural -- use names that differ by <= 3 edits
        // "config" vs "configs" is singular/plural. Use "report" vs "raport" (distance 1).
        Files.createDirectories(workspace.resolve("report"));
        Files.createDirectories(workspace.resolve("raport"));

        List<NamingCommand.Pair> dups = NamingCommand.findSemanticDuplicates(workspace, 6);
        assertEquals(1, dups.size());

        // Verify edit distance
        String name1 = dups.get(0).dir1().getFileName().toString();
        String name2 = dups.get(0).dir2().getFileName().toString();
        int dist = NamingCommand.levenshtein(name1.toLowerCase(), name2.toLowerCase());
        assertTrue(dist > 0 && dist <= 3, "Edit distance should be > 0 and <= 3, was: " + dist);
    }

    @Test
    void findSemanticDuplicates_ignoresSingularPluralPairs() throws IOException {
        Files.createDirectories(workspace.resolve("product"));
        Files.createDirectories(workspace.resolve("products"));

        List<NamingCommand.Pair> dups = NamingCommand.findSemanticDuplicates(workspace, 6);
        assertTrue(dups.isEmpty(), "Singular/plural pairs should not appear in semantic duplicates");
    }

    @Test
    void findSemanticDuplicates_ignoresDistantNames() throws IOException {
        Files.createDirectories(workspace.resolve("alpha"));
        Files.createDirectories(workspace.resolve("zzzzz"));

        List<NamingCommand.Pair> dups = NamingCommand.findSemanticDuplicates(workspace, 6);
        assertTrue(dups.isEmpty(), "Very different names should not match");
    }

    // -------------------------------------------------------------------------
    // detectNamingConventions
    // -------------------------------------------------------------------------

    @Test
    void detectNamingConventions_detectsOpportunityPrefix() throws IOException {
        Files.createDirectories(workspace.resolve("opportunity-Mynder"));
        Files.createDirectories(workspace.resolve("opportunity-Tvimenning"));

        Map<String, List<Path>> conventions = NamingCommand.detectNamingConventions(workspace, 6);
        assertTrue(conventions.containsKey("opportunity-{Name}"));
        assertEquals(2, conventions.get("opportunity-{Name}").size());
    }

    @Test
    void detectNamingConventions_detectsSuffixPattern() throws IOException {
        Files.createDirectories(workspace.resolve("Client-past"));
        Files.createDirectories(workspace.resolve("Acme-past"));

        Map<String, List<Path>> conventions = NamingCommand.detectNamingConventions(workspace, 6);
        assertTrue(conventions.containsKey("{Name}-past"));
        assertEquals(2, conventions.get("{Name}-past").size());
    }

    @Test
    void detectNamingConventions_detectsVirtualCategoryPattern() throws IOException {
        Files.createDirectories(workspace.resolve("@active/Elprint"));
        Files.createDirectories(workspace.resolve("@active/Mynder"));

        Map<String, List<Path>> conventions = NamingCommand.detectNamingConventions(workspace, 6);
        assertTrue(conventions.containsKey("@{status}/{Name}"));
        assertEquals(2, conventions.get("@{status}/{Name}").size());
    }

    @Test
    void detectNamingConventions_detectsPlainPattern() throws IOException {
        Files.createDirectories(workspace.resolve("Elprint"));
        Files.createDirectories(workspace.resolve("Opplysningen"));

        Map<String, List<Path>> conventions = NamingCommand.detectNamingConventions(workspace, 6);
        assertTrue(conventions.containsKey("{Name}"));
        assertEquals(2, conventions.get("{Name}").size());
    }

    @Test
    void detectNamingConventions_emptyWorkspace_returnsEmpty() throws IOException {
        Map<String, List<Path>> conventions = NamingCommand.detectNamingConventions(workspace, 6);
        assertTrue(conventions.isEmpty());
    }

    // -------------------------------------------------------------------------
    // levenshtein
    // -------------------------------------------------------------------------

    @Test
    void levenshtein_basicCases() {
        assertEquals(0, NamingCommand.levenshtein("", ""));
        assertEquals(0, NamingCommand.levenshtein("abc", "abc"));
        assertEquals(1, NamingCommand.levenshtein("abc", "ab"));       // deletion
        assertEquals(1, NamingCommand.levenshtein("ab", "abc"));       // insertion
        assertEquals(1, NamingCommand.levenshtein("abc", "axc"));      // substitution
        assertEquals(3, NamingCommand.levenshtein("abc", "xyz"));      // all different
        assertEquals(3, NamingCommand.levenshtein("", "abc"));         // empty vs 3 chars
        assertEquals(3, NamingCommand.levenshtein("abc", ""));         // 3 chars vs empty
    }

    @Test
    void levenshtein_financeFinancials() {
        // "finance" -> "financials": replace 'e'->'i', insert 'a', insert 'l', insert 's' => distance 4
        int dist = NamingCommand.levenshtein("finance", "financials");
        assertEquals(4, dist);
    }
}
