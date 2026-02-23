package io.exoreaction.synthesis.graph;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Extracts declared dependencies from pom.xml files and checks them
 * against a bundled catalog of known vulnerabilities.
 *
 * <p>This is a simple regex-based parser, not a full Maven POM parser.
 * It handles the common case of explicit {@code <dependency>} blocks.
 *
 * @since v1.14.0 (Security)
 */
public class DependencyInventoryExtractor {

    private static final Logger LOG = Logger.getLogger(DependencyInventoryExtractor.class.getName());

    // Regex to extract <dependency>...</dependency> blocks
    private static final Pattern DEPENDENCY_BLOCK = Pattern.compile(
            "<dependency>\\s*(.*?)\\s*</dependency>", Pattern.DOTALL);
    private static final Pattern GROUP_ID = Pattern.compile(
            "<groupId>\\s*([^<]+?)\\s*</groupId>");
    private static final Pattern ARTIFACT_ID = Pattern.compile(
            "<artifactId>\\s*([^<]+?)\\s*</artifactId>");
    private static final Pattern VERSION = Pattern.compile(
            "<version>\\s*([^<]+?)\\s*</version>");
    private static final Pattern SCOPE = Pattern.compile(
            "<scope>\\s*([^<]+?)\\s*</scope>");

    private final SecurityRepository repository;

    public DependencyInventoryExtractor() {
        this.repository = new SecurityRepository();
    }

    public DependencyInventoryExtractor(SecurityRepository repository) {
        this.repository = repository;
    }

    /**
     * Finds all pom.xml files, extracts dependencies, persists them,
     * and checks against known vulnerabilities.
     *
     * @param workspaceRoot workspace root path
     * @param conn          database connection
     * @param wsPath        workspace path string
     * @return list of S010 signals for known vulnerable dependencies
     */
    public List<SecuritySignal> analyzeAndReport(Path workspaceRoot, Connection conn,
                                                   String wsPath) throws IOException, SQLException {
        List<SecuritySignal> signals = new ArrayList<>();
        long now = Instant.now().getEpochSecond();

        // Find all pom.xml files
        List<Path> pomFiles;
        try (Stream<Path> walk = Files.walk(workspaceRoot)) {
            pomFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> !p.toString().contains("/."))
                    .filter(p -> !CodeGraphExtractor.isBuildArtifact(workspaceRoot, p))
                    .toList();
        }

        // Load known vulnerabilities catalog
        List<KnownVulnerability> knownVulns = loadKnownVulnerabilities();

        for (Path pomFile : pomFiles) {
            String relativePath = workspaceRoot.relativize(pomFile).toString();
            try {
                String content = Files.readString(pomFile);
                List<DeclaredDependency> deps = parsePomDependencies(content, relativePath);

                for (DeclaredDependency dep : deps) {
                    repository.upsertDeclaredDependency(conn, wsPath, dep, now);

                    // Check against known vulnerabilities
                    for (KnownVulnerability vuln : knownVulns) {
                        if (vuln.matches(dep)) {
                            signals.add(new SecuritySignal(
                                    "S010_DEPENDENCY_KNOWN_VULN", "HIGH", "CWE-1035",
                                    relativePath, 0, null, null,
                                    vuln.cveId + ": " + vuln.description
                                            + " (" + dep.groupId() + ":" + dep.artifactId()
                                            + ":" + dep.version() + ")",
                                    "Fixed in version " + vuln.fixedVersion,
                                    "Upgrade " + dep.artifactId() + " to " + vuln.fixedVersion + " or later",
                                    null));
                        }
                    }
                }
            } catch (IOException e) {
                LOG.fine("Could not read pom.xml: " + pomFile + " - " + e.getMessage());
            }
        }

        return signals;
    }

    /**
     * Parses dependency declarations from a pom.xml content string.
     */
    List<DeclaredDependency> parsePomDependencies(String content, String buildFile) {
        List<DeclaredDependency> deps = new ArrayList<>();
        // Strip XML comments so commented-out dependencies are not flagged as vulnerabilities
        String cleaned = content.replaceAll("(?s)<!--.*?-->", "");
        Matcher blockMatcher = DEPENDENCY_BLOCK.matcher(cleaned);

        while (blockMatcher.find()) {
            String block = blockMatcher.group(1);

            Matcher gm = GROUP_ID.matcher(block);
            Matcher am = ARTIFACT_ID.matcher(block);
            if (!gm.find() || !am.find()) continue;

            String groupId = gm.group(1);
            String artifactId = am.group(1);

            Matcher vm = VERSION.matcher(block);
            String version = vm.find() ? vm.group(1) : null;

            Matcher sm = SCOPE.matcher(block);
            String scope = sm.find() ? sm.group(1) : null;

            // Skip property references like ${project.version}
            if (version != null && version.startsWith("${")) {
                version = null;
            }

            deps.add(new DeclaredDependency(groupId, artifactId, version, scope, buildFile));
        }

        return deps;
    }

    /**
     * Loads the known vulnerabilities catalog from the classpath resource.
     */
    List<KnownVulnerability> loadKnownVulnerabilities() {
        List<KnownVulnerability> vulns = new ArrayList<>();
        try (InputStream is = getClass().getResourceAsStream("/security/known-vulnerabilities.json")) {
            if (is == null) {
                LOG.warning("Known vulnerabilities catalog not found on classpath");
                return vulns;
            }
            String json = new String(is.readAllBytes());
            // Simple JSON array parser (avoid dependency on Jackson)
            vulns = parseVulnJson(json);
        } catch (IOException e) {
            LOG.warning("Could not load known vulnerabilities: " + e.getMessage());
        }
        return vulns;
    }

    /**
     * Simple JSON parser for the vulnerability catalog. Avoids external library dependency.
     */
    private List<KnownVulnerability> parseVulnJson(String json) {
        List<KnownVulnerability> vulns = new ArrayList<>();
        // Match each object in the array
        Pattern objPattern = Pattern.compile("\\{([^}]+)}", Pattern.DOTALL);
        Pattern fieldPattern = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"");

        Matcher objMatcher = objPattern.matcher(json);
        while (objMatcher.find()) {
            String obj = objMatcher.group(1);
            Map<String, String> fields = new HashMap<>();
            Matcher fieldMatcher = fieldPattern.matcher(obj);
            while (fieldMatcher.find()) {
                fields.put(fieldMatcher.group(1), fieldMatcher.group(2));
            }
            if (fields.containsKey("groupId") && fields.containsKey("artifactId")) {
                vulns.add(new KnownVulnerability(
                        fields.get("groupId"),
                        fields.get("artifactId"),
                        fields.getOrDefault("fixedVersion", ""),
                        fields.getOrDefault("cveId", ""),
                        fields.getOrDefault("description", "")
                ));
            }
        }
        return vulns;
    }

    /**
     * A known vulnerability record from the bundled catalog.
     */
    record KnownVulnerability(
            String groupId,
            String artifactId,
            String fixedVersion,
            String cveId,
            String description
    ) {
        /**
         * Returns true if the declared dependency matches this vulnerability.
         * Matches on groupId + artifactId, and version is less than fixedVersion
         * (simple string comparison -- good enough for major.minor.patch format).
         */
        boolean matches(DeclaredDependency dep) {
            if (!groupId.equals(dep.groupId()) || !artifactId.equals(dep.artifactId())) {
                return false;
            }
            if (dep.version() == null || dep.version().isBlank()) {
                return false; // Can't determine if vulnerable without version
            }
            // Simple version comparison: vulnerable if version < fixedVersion
            return compareVersions(dep.version(), fixedVersion) < 0;
        }

        /**
         * Simple semantic version comparison.
         * Returns negative if v1 < v2, 0 if equal, positive if v1 > v2.
         */
        private static int compareVersions(String v1, String v2) {
            String[] parts1 = v1.split("[.\\-]");
            String[] parts2 = v2.split("[.\\-]");
            int len = Math.max(parts1.length, parts2.length);
            for (int i = 0; i < len; i++) {
                int p1 = i < parts1.length ? parseIntSafe(parts1[i]) : 0;
                int p2 = i < parts2.length ? parseIntSafe(parts2[i]) : 0;
                if (p1 != p2) return Integer.compare(p1, p2);
            }
            return 0;
        }

        private static int parseIntSafe(String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }
}
