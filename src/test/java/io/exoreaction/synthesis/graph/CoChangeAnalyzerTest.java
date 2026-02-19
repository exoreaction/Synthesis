package io.exoreaction.synthesis.graph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CoChangeAnalyzer using pre-parsed commit data (analyzeCommits).
 * Tests do NOT call parseGitLog() — they supply commits directly for determinism.
 */
class CoChangeAnalyzerTest {

    private final CoChangeAnalyzer analyzer = new CoChangeAnalyzer();

    // 1. High coupling: A+B changed in 5/5 commits -> ratio=1.0
    @Test
    void analyzeCommits_highCoupling() {
        List<List<String>> commits = List.of(
            List.of("src/Foo.java", "src/Bar.java"),
            List.of("src/Foo.java", "src/Bar.java"),
            List.of("src/Foo.java", "src/Bar.java"),
            List.of("src/Foo.java", "src/Bar.java"),
            List.of("src/Foo.java", "src/Bar.java")
        );
        CoChangeAnalyzer.CoChangeReport report = analyzer.analyzeCommits(commits, 2, Set.of());
        assertEquals(1, report.highCoupling().size());
        CoChangeAnalyzer.CoChangePair pair = report.highCoupling().get(0);
        assertEquals(5, pair.coCommitCount());
        assertEquals(1.0, pair.ratio(), 0.01);
        assertTrue(report.mediumCoupling().isEmpty());
    }

    // 2. Medium coupling: A+B co-changed 3 times, each appears 5 times -> ratio=3/5=0.6
    @Test
    void analyzeCommits_mediumCoupling() {
        // A and B each appear in 5 commits total; they co-change in only 3 of them.
        // ratio = 3 / min(5,5) = 0.6 -> medium coupling (0.5 <= ratio <= 0.8)
        List<List<String>> commits = List.of(
            List.of("src/Alpha.java", "src/Beta.java"),
            List.of("src/Alpha.java", "src/Beta.java"),
            List.of("src/Alpha.java", "src/Beta.java"),
            List.of("src/Alpha.java"),
            List.of("src/Alpha.java"),
            List.of("src/Beta.java"),
            List.of("src/Beta.java")
        );
        CoChangeAnalyzer.CoChangeReport report = analyzer.analyzeCommits(commits, 2, Set.of());
        assertTrue(report.highCoupling().isEmpty(),
            "ratio=0.6 should not be in high coupling (>0.8 required)");
        assertEquals(1, report.mediumCoupling().size());
        CoChangeAnalyzer.CoChangePair pair = report.mediumCoupling().get(0);
        assertEquals(3, pair.coCommitCount());
        assertEquals(0.6, pair.ratio(), 0.01);
    }

    // 3. Below minSupport: co-changed only twice, minSupport=3 -> not included
    @Test
    void analyzeCommits_belowMinSupport_excluded() {
        List<List<String>> commits = List.of(
            List.of("src/Foo.java", "src/Bar.java"),
            List.of("src/Foo.java", "src/Bar.java"),
            List.of("src/Foo.java"),
            List.of("src/Foo.java")
        );
        CoChangeAnalyzer.CoChangeReport report = analyzer.analyzeCommits(commits, 3, Set.of());
        assertTrue(report.highCoupling().isEmpty());
        assertTrue(report.mediumCoupling().isEmpty());
        assertTrue(report.unexpected().isEmpty());
    }

    // 4. Unexpected coupling: A<->B coupled but no import link -> in unexpected
    @Test
    void analyzeCommits_unexpectedCoupling_noImportLink() {
        List<List<String>> commits = List.of(
            List.of("src/Foo.java", "src/Bar.java"),
            List.of("src/Foo.java", "src/Bar.java"),
            List.of("src/Foo.java", "src/Bar.java"),
            List.of("src/Foo.java", "src/Bar.java"),
            List.of("src/Foo.java", "src/Bar.java")
        );
        // No import links provided
        CoChangeAnalyzer.CoChangeReport report = analyzer.analyzeCommits(commits, 2, Set.of());
        assertFalse(report.unexpected().isEmpty(), "Expected unexpected coupling with no import link");
        CoChangeAnalyzer.CoChangePair pair = report.unexpected().get(0);
        assertFalse(pair.hasImportLink());
    }

    // 5. With import link: A<->B coupled WITH import link -> NOT in unexpected
    @Test
    void analyzeCommits_withImportLink_notInUnexpected() {
        List<List<String>> commits = List.of(
            List.of("src/Foo.java", "src/Bar.java"),
            List.of("src/Foo.java", "src/Bar.java"),
            List.of("src/Foo.java", "src/Bar.java"),
            List.of("src/Foo.java", "src/Bar.java"),
            List.of("src/Foo.java", "src/Bar.java")
        );
        Set<String> importLinks = Set.of("src/Foo.java|src/Bar.java");
        CoChangeAnalyzer.CoChangeReport report = analyzer.analyzeCommits(commits, 2, importLinks);
        // The pair should have an import link so it must NOT appear in unexpected
        CoChangeAnalyzer.CoChangePair highPair = report.highCoupling().get(0);
        assertTrue(highPair.hasImportLink());
        assertTrue(report.unexpected().isEmpty(),
            "No unexpected coupling expected when import link exists");
    }

    // 6. Ratio calculation: verify ratio = coCount / min(totalA, totalB)
    @Test
    void analyzeCommits_ratioCalculation() {
        // A appears in 4 commits, B in 3 commits, co-appear in 3
        // ratio = 3 / min(4,3) = 3/3 = 1.0
        List<List<String>> commits = List.of(
            List.of("src/A.java", "src/B.java"),
            List.of("src/A.java", "src/B.java"),
            List.of("src/A.java", "src/B.java"),
            List.of("src/A.java")
        );
        CoChangeAnalyzer.CoChangeReport report = analyzer.analyzeCommits(commits, 2, Set.of());
        assertFalse(report.highCoupling().isEmpty());
        CoChangeAnalyzer.CoChangePair pair = report.highCoupling().get(0);
        assertEquals(3, pair.coCommitCount());
        int minTotal = Math.min(pair.totalCommitsA(), pair.totalCommitsB());
        assertEquals(3, minTotal);
        assertEquals(1.0, pair.ratio(), 0.01);
    }

    // 7. Three files: A+B+C all change together -> 3 pairs (AB, AC, BC)
    @Test
    void analyzeCommits_threeFiles_pairsCorrect() {
        List<List<String>> commits = List.of(
            List.of("src/A.java", "src/B.java", "src/C.java"),
            List.of("src/A.java", "src/B.java", "src/C.java"),
            List.of("src/A.java", "src/B.java", "src/C.java")
        );
        CoChangeAnalyzer.CoChangeReport report = analyzer.analyzeCommits(commits, 2, Set.of());
        int totalPairs = report.highCoupling().size() + report.mediumCoupling().size();
        assertEquals(3, totalPairs, "Expected 3 pairs from 3 files all changing together");
    }

    // 8. Empty commits -> empty report
    @Test
    void analyzeCommits_emptyCommits_returnsEmpty() {
        CoChangeAnalyzer.CoChangeReport report = analyzer.analyzeCommits(List.of(), 1, Set.of());
        assertTrue(report.highCoupling().isEmpty());
        assertTrue(report.mediumCoupling().isEmpty());
        assertTrue(report.unexpected().isEmpty());
    }

    // 9. format() output contains "HIGH coupling"
    @Test
    void format_highCoupling_containsExpectedOutput() {
        List<List<String>> commits = List.of(
            List.of("src/Foo.java", "src/Bar.java"),
            List.of("src/Foo.java", "src/Bar.java"),
            List.of("src/Foo.java", "src/Bar.java"),
            List.of("src/Foo.java", "src/Bar.java"),
            List.of("src/Foo.java", "src/Bar.java")
        );
        CoChangeAnalyzer.CoChangeReport report = analyzer.analyzeCommits(commits, 2, Set.of());
        String output = analyzer.format(report, 2);
        assertTrue(output.contains("HIGH coupling"), "Output should contain 'HIGH coupling'");
        assertTrue(output.contains("CO-CHANGE CLUSTERS"), "Output should contain cluster header");
    }
}
