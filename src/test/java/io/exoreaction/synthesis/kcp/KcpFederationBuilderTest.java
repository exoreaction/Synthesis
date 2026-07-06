package io.exoreaction.synthesis.kcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link KcpFederationBuilder} — catalog.yaml + federation root
 * manifest generation, cross-repo dependency derivation, cycle detection, and
 * >50-repo sharding (issue #358).
 */
class KcpFederationBuilderTest {

    @TempDir
    Path tempDir;

    private static final String TODAY = "2026-07-06";

    // -----------------------------------------------------------------------
    // Dependency derivation
    // -----------------------------------------------------------------------

    @Test
    void derivesPomDependencyEdgesSkippingOwnArtifact() {
        String pom = """
                <project>
                  <artifactId>svc-api</artifactId>
                  <dependencies>
                    <dependency><artifactId>svc-auth</artifactId></dependency>
                    <!-- <dependency><artifactId>svc-ghost</artifactId></dependency> -->
                  </dependencies>
                </project>
                """;
        // Write the pom so the builder can read it
        Path repo = tempDir.resolve("svc-api");
        writeFile(repo.resolve("pom.xml"), pom);
        var candidates = KcpFederationBuilder.deriveDependencyCandidates(repo, null);
        assertTrue(candidates.contains("svc-auth"), "Dependency artifactId derived: " + candidates);
        assertFalse(candidates.contains("svc-api"), "Project's own artifactId excluded");
        assertFalse(candidates.contains("svc-ghost"), "Commented dependencies ignored");
    }

    // -----------------------------------------------------------------------
    // Cycle detection
    // -----------------------------------------------------------------------

    @Test
    void dropsCyclicDependencyEdges() {
        List<KcpFederationBuilder.RepoEntry> entries = List.of(
                entry("a", List.of("b")),
                entry("b", List.of("a")));   // a → b → a is a cycle

        List<String> cycles = new ArrayList<>();
        var acyclic = KcpFederationBuilder.withAcyclicDeps(entries, cycles);

        int totalEdges = acyclic.stream().mapToInt(e -> e.dependsOn().size()).sum();
        assertEquals(1, totalEdges, "One of the two cyclic edges must be dropped");
        assertEquals(1, cycles.size(), "The dropped edge is reported");
    }

    @Test
    void keepsAcyclicDiamond() {
        // a → b, a → c, b → d, c → d  (no cycle)
        List<KcpFederationBuilder.RepoEntry> entries = List.of(
                entry("a", List.of("b", "c")),
                entry("b", List.of("d")),
                entry("c", List.of("d")),
                entry("d", List.of()));
        List<String> cycles = new ArrayList<>();
        var acyclic = KcpFederationBuilder.withAcyclicDeps(entries, cycles);
        assertTrue(cycles.isEmpty(), "Diamond has no cycle: " + cycles);
        int totalEdges = acyclic.stream().mapToInt(e -> e.dependsOn().size()).sum();
        assertEquals(4, totalEdges);
    }

    // -----------------------------------------------------------------------
    // catalog.yaml
    // -----------------------------------------------------------------------

    @Test
    void catalogYamlHasSpecFields() {
        var entries = List.of(
                fullEntry("acme-auth", "git+https://github.com/acme/auth.git//knowledge.yaml@v1.2.0",
                        "1.2.0", "a".repeat(40), List.of()),
                fullEntry("acme-api", "git+https://github.com/acme/api.git//knowledge.yaml@v0.9.1",
                        "0.9.1", "b".repeat(40), List.of("acme-auth")));
        String yaml = KcpFederationBuilder.toCatalogYaml(entries, "acme", "team@acme.example");

        assertTrue(yaml.contains("catalog_version: \"0.1\""));
        assertTrue(yaml.contains("maintainer: team@acme.example"));
        assertTrue(yaml.contains("- name: acme-auth"));
        assertTrue(yaml.contains("source: git+https://github.com/acme/api.git//knowledge.yaml@v0.9.1"));
        assertTrue(yaml.contains("source_commit: " + "b".repeat(40)));
        assertTrue(yaml.contains("    depends_on:\n      - acme-auth"),
                "depends_on emitted with catalog indentation: " + yaml);
    }

    @Test
    void catalogNameMatchesSpecPattern() {
        // entry names must match [a-z0-9][a-z0-9\\-]*
        var entries = List.of(fullEntry("my-service-1", "./knowledge.yaml", "1.0.0", null, List.of()));
        String yaml = KcpFederationBuilder.toCatalogYaml(entries, "cat", null);
        assertTrue(yaml.matches("(?s).*- name: [a-z0-9][a-z0-9\\-]*\\n.*"));
    }

    // -----------------------------------------------------------------------
    // Federation root + sharding
    // -----------------------------------------------------------------------

    @Test
    void federationRootCarriesManifestsAndExternalRelationships() {
        var entries = List.of(
                fullEntry("svc-api", "git+https://x/api.git//knowledge.yaml@v1", "1.0.0", null,
                        List.of("svc-auth")),
                fullEntry("svc-auth", "git+https://x/auth.git//knowledge.yaml@v1", "1.0.0", null,
                        List.of()));
        var files = KcpFederationBuilder.toFederationManifests(entries, "estate", TODAY);

        assertEquals(1, files.size(), "Small estate → single root manifest");
        String root = files.get(0).yaml();
        assertEquals("knowledge.yaml", files.get(0).relativePath());
        assertTrue(root.contains("version: 1.0.0"), "Root must declare a version");
        assertTrue(root.contains("- id: svc-api"));
        assertTrue(root.contains("relationship: references"));
        assertTrue(root.contains("local_mirror: ./svc-api/knowledge.yaml"));
        assertTrue(root.contains("external_relationships:\n  - from: svc-api\n"
                + "    to: svc-auth\n    type: depends_on"),
                "external_relationships from deps: " + root);
    }

    @Test
    void largeEstateIsShardedNotTruncated() {
        List<KcpFederationBuilder.RepoEntry> entries = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            entries.add(fullEntry("repo-" + i, "https://x/r" + i + "/knowledge.yaml",
                    "1.0.0", null, List.of()));
        }
        var files = KcpFederationBuilder.toFederationManifests(entries, "big", TODAY);

        // 1 top root + ceil(120/50)=3 shards
        assertEquals(4, files.size(), "120 repos → 1 root + 3 shards");
        assertEquals("knowledge.yaml", files.get(0).relativePath());
        assertTrue(files.get(0).yaml().contains("knowledge.shard-1.yaml"));

        // No single manifest exceeds the follower ceiling
        for (var f : files) {
            long ids = f.yaml().lines().filter(l -> l.strip().startsWith("- id:")).count();
            assertTrue(ids <= KcpFederationBuilder.MAX_MANIFESTS_PER_ROOT,
                    f.relativePath() + " lists " + ids + " manifests (over limit)");
        }
        // Every repo appears across the shards (nothing silently dropped)
        long totalListed = files.stream().skip(1)
                .flatMap(f -> f.yaml().lines())
                .filter(l -> l.strip().startsWith("- id: repo-"))
                .count();
        assertEquals(120, totalListed, "All 120 repos federated across shards");
    }

    // -----------------------------------------------------------------------
    // Git source / url normalization
    // -----------------------------------------------------------------------

    @Test
    void gitSourceAndHttpsUrlFromScpRemote() {
        String git = KcpFederationBuilder.toGitSource(
                "git@github.com:acme/svc.git", "v1.0.0", tempDir);
        assertEquals("git+https://github.com/acme/svc.git//knowledge.yaml@v1.0.0", git);

        String https = KcpFederationBuilder.toHttpsRawUrl("git@github.com:acme/svc.git", "v1.0.0");
        assertEquals("https://raw.githubusercontent.com/acme/svc/v1.0.0/knowledge.yaml", https);
    }

    @Test
    void noRemoteFallsBackToLocalPath() {
        Path repo = tempDir.resolve("local-repo");
        assertEquals("./local-repo/knowledge.yaml",
                KcpFederationBuilder.toGitSource(null, null, repo));
        assertNull(KcpFederationBuilder.toHttpsRawUrl(null, null));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private KcpFederationBuilder.RepoEntry entry(String name, List<String> deps) {
        return new KcpFederationBuilder.RepoEntry(name, tempDir.resolve(name), name,
                "./" + name + "/knowledge.yaml", null, "1.0.0", null, TODAY, null,
                new ArrayList<>(deps));
    }

    private KcpFederationBuilder.RepoEntry fullEntry(String name, String source, String version,
                                                     String commit, List<String> deps) {
        String https = "https://raw.githubusercontent.com/x/" + name + "/HEAD/knowledge.yaml";
        return new KcpFederationBuilder.RepoEntry(name, tempDir.resolve(name), name, source,
                https, version, commit, TODAY, null, new ArrayList<>(deps));
    }

    private void writeFile(Path path, String content) {
        try {
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, content);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
