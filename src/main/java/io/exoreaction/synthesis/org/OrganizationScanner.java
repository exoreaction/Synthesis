package io.exoreaction.synthesis.org;

import io.exoreaction.synthesis.config.SynthesisConfig.SubWorkspaceConfig;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Auto-discovers organizational structure from a workspace directory.
 *
 * <p>Scans the top-level directories in the workspace for organizational patterns:
 * <ul>
 *   <li>Companies/foundations with README.md, CODEBASE-INDEX.md, clients/, products/</li>
 *   <li>Client directories with naming conventions (opportunity-*, *-past)</li>
 *   <li>Product directories</li>
 *   <li>Codebase references from CODEBASE-INDEX.md</li>
 * </ul>
 *
 * <p>Uses a confidence scoring system to distinguish real organizations
 * from other directories (archive, personal, etc.).
 */
public class OrganizationScanner {

    /** Minimum confidence score to auto-detect as organization. */
    private static final int CONFIDENCE_THRESHOLD = 3;

    /** Directories that are never organizations. */
    private static final Set<String> SKIP_DIRECTORIES = Set.of(
            ".synthesis", ".claude", ".git", "archive", "personal",
            "node_modules", "target", "build", "__pycache__"
    );

    private final Path workspaceRoot;

    public OrganizationScanner(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    /**
     * Scans the workspace directory for organizations and populates the registry.
     *
     * @return populated registry with all discovered organizations
     */
    public OrganizationRegistry scan() throws IOException {
        OrganizationRegistry registry = new OrganizationRegistry(workspaceRoot);

        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(workspaceRoot)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) continue;

                String dirName = dir.getFileName().toString();
                if (dirName.startsWith(".") || SKIP_DIRECTORIES.contains(dirName)) continue;

                int confidence = computeConfidence(dir);
                if (confidence >= CONFIDENCE_THRESHOLD) {
                    Organization org = discoverOrganization(dir);
                    if (org != null) {
                        registry.addOrganization(org);
                    }
                }
            }
        }

        registry.setLastScanTime(Instant.now());
        return registry;
    }

    /**
     * Discovers all potential organizations with confidence scores.
     *
     * <p>Unlike {@link #scan()}, this method returns ALL potential organizations
     * including those below the confidence threshold, along with their scores
     * and detection signals. Used for interactive init where the user can
     * review and confirm/reject each discovery.
     *
     * @return list of discovered organizations with confidence metadata,
     *         sorted by confidence (highest first)
     */
    public List<DiscoveredOrganization> discoverWithConfidence() throws IOException {
        List<DiscoveredOrganization> results = new ArrayList<>();

        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(workspaceRoot)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) continue;

                String dirName = dir.getFileName().toString();
                if (dirName.startsWith(".") || SKIP_DIRECTORIES.contains(dirName)) continue;

                int confidence = computeConfidence(dir);
                if (confidence >= CONFIDENCE_THRESHOLD) {
                    Organization org = discoverOrganization(dir);
                    if (org != null) {
                        String signals = buildSignalsSummary(dir, org);
                        results.add(new DiscoveredOrganization(org, confidence, signals));
                    }
                }
            }
        }

        // Sort by confidence descending, then by name
        results.sort(Comparator.comparingInt(DiscoveredOrganization::confidence).reversed()
                .thenComparing(d -> d.organization().getName()));

        return results;
    }

    /**
     * Builds a human-readable summary of detection signals for a directory.
     */
    String buildSignalsSummary(Path dir, Organization org) {
        List<String> signals = new ArrayList<>();

        if (Files.exists(dir.resolve("CODEBASE-INDEX.md"))) signals.add("CODEBASE-INDEX.md");
        if (Files.isDirectory(dir.resolve("clients"))) {
            int count = org.getClients().size();
            signals.add(count + " client" + (count != 1 ? "s" : ""));
        }
        if (Files.isDirectory(dir.resolve("products"))) {
            int count = org.getProducts().size();
            signals.add(count + " product" + (count != 1 ? "s" : ""));
        }
        if (Files.isDirectory(dir.resolve("business"))) signals.add("business/ directory");
        if (Files.isDirectory(dir.resolve("marketing"))) signals.add("marketing/ directory");
        if (Files.isDirectory(dir.resolve("methodology"))) signals.add("methodology/ directory");
        if (Files.isDirectory(dir.resolve("media"))) signals.add("media/ directory");
        if (Files.exists(dir.resolve("README.md"))) signals.add("README.md");

        return String.join(", ", signals);
    }

    /**
     * Counts the total number of files in a directory (non-recursive, up to limit).
     * Used for workspace statistics during init.
     *
     * @param dir the directory to count
     * @param maxDepth maximum depth to scan (0 = top-level only)
     * @return approximate file count
     */
    public long countFiles(Path dir, int maxDepth) {
        try (Stream<Path> stream = Files.walk(dir, maxDepth)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("/."))
                    .filter(p -> !p.toString().contains("/node_modules/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .count();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Computes a confidence score for whether a directory is an organization.
     *
     * @param dir the directory to evaluate
     * @return confidence score (higher = more likely an organization)
     */
    public int computeConfidence(Path dir) {
        int score = 0;

        if (Files.exists(dir.resolve("README.md"))) score += 1;
        if (Files.exists(dir.resolve("CODEBASE-INDEX.md"))) score += 3;
        if (Files.isDirectory(dir.resolve("clients"))) score += 2;
        if (Files.isDirectory(dir.resolve("products"))) score += 2;
        if (Files.isDirectory(dir.resolve("business"))) score += 2;
        if (Files.isDirectory(dir.resolve("marketing"))) score += 1;
        if (Files.isDirectory(dir.resolve("methodology"))) score += 1;
        if (Files.isDirectory(dir.resolve("codebase"))) score += 1;
        if (Files.isDirectory(dir.resolve("media"))) score += 1;

        return score;
    }

    /**
     * Discovers full organizational structure from a directory.
     */
    Organization discoverOrganization(Path dir) throws IOException {
        String name = dir.getFileName().toString();
        OrganizationType type = detectType(dir, name);

        Organization org = new Organization(name, type, dir);

        // Extract description from README
        Path readme = dir.resolve("README.md");
        if (Files.exists(readme)) {
            org.setDescription(extractDescription(readme));
        }

        // Discover clients
        Path clientsDir = dir.resolve("clients");
        if (Files.isDirectory(clientsDir)) {
            List<Client> clients = discoverClients(clientsDir, name);
            org.setClients(clients);
        }

        // Discover products
        Path productsDir = dir.resolve("products");
        if (Files.isDirectory(productsDir)) {
            List<Product> products = discoverProducts(productsDir, name);
            org.setProducts(products);
        }

        // Discover codebase paths from CODEBASE-INDEX.md
        Path codebaseIndex = dir.resolve("CODEBASE-INDEX.md");
        if (Files.exists(codebaseIndex)) {
            List<String> codebasePaths = extractCodebasePaths(codebaseIndex);
            org.setCodebasePaths(codebasePaths);
        }

        // Generate keywords from name, clients, products
        org.setKeywords(generateKeywords(org));

        return org;
    }

    /**
     * Detects the organization type from directory contents and name.
     */
    OrganizationType detectType(Path dir, String name) {
        String nameLower = name.toLowerCase();

        // Known patterns
        if (nameLower.contains("holding") || nameLower.equals("t-hex")) {
            return OrganizationType.HOLDING;
        }
        if (nameLower.equals("cantara")) {
            // Has frameworks/ directory = foundation
            if (Files.isDirectory(dir.resolve("frameworks"))) {
                return OrganizationType.FOUNDATION;
            }
        }

        // Check directory contents
        if (Files.isDirectory(dir.resolve("theory")) || Files.isDirectory(dir.resolve("implementation"))) {
            return OrganizationType.CONCEPT;
        }
        if (Files.isDirectory(dir.resolve("clients")) || Files.isDirectory(dir.resolve("business"))) {
            return OrganizationType.COMPANY;
        }

        return OrganizationType.OTHER;
    }

    /**
     * Extracts a description from the first meaningful line of a README.md.
     * Looks for the first line after the heading that is not blank.
     */
    String extractDescription(Path readme) throws IOException {
        List<String> lines = Files.readAllLines(readme);
        boolean pastFirstHeading = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                pastFirstHeading = true;
                continue;
            }
            if (pastFirstHeading && !trimmed.isEmpty() &&
                !trimmed.startsWith("- ") && !trimmed.startsWith("#") &&
                !trimmed.startsWith("|") && !trimmed.startsWith("```") &&
                !trimmed.startsWith("---")) {
                // Clean markdown formatting
                String clean = trimmed
                        .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                        .replaceAll("\\*([^*]+)\\*", "$1");
                if (clean.length() > 150) {
                    clean = clean.substring(0, 147) + "...";
                }
                return clean;
            }
        }
        return null;
    }

    /**
     * Discovers clients from a clients/ directory.
     */
    List<Client> discoverClients(Path clientsDir, String orgName) throws IOException {
        List<Client> clients = new ArrayList<>();

        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(clientsDir)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) continue;
                String dirName = dir.getFileName().toString();

                // Skip archive, screenshots, and other non-client directories
                if (dirName.equals("archive") || dirName.equals("screenshots") ||
                    dirName.equals("lib-pcb-manufacturing") || dirName.startsWith(".")) {
                    continue;
                }

                ClientStatus status = ClientStatus.fromDirectoryName(dirName);
                String clientName = ClientStatus.extractClientName(dirName);

                Client client = new Client(clientName, orgName, dir, status, dirName);
                clients.add(client);
            }
        }

        // Sort: active first, then opportunity, then past
        clients.sort(Comparator.comparingInt((Client c) -> switch (c.getStatus()) {
            case ACTIVE -> 0;
            case SIGNED -> 1;
            case OPPORTUNITY -> 2;
            case PAST -> 3;
        }).thenComparing(Client::getName));

        return clients;
    }

    /**
     * Discovers products from a products/ directory.
     */
    List<Product> discoverProducts(Path productsDir, String orgName) throws IOException {
        List<Product> products = new ArrayList<>();

        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(productsDir)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) continue;
                String dirName = dir.getFileName().toString();
                if (dirName.startsWith(".")) continue;

                Product product = new Product(dirName, orgName, dir);
                products.add(product);
            }
        }

        products.sort(Comparator.comparing(Product::getName));
        return products;
    }

    /**
     * Extracts codebase paths from a CODEBASE-INDEX.md file.
     * Looks for patterns like /src/exoreaction/, ~/src/quadim/, etc.
     */
    List<String> extractCodebasePaths(Path codebaseIndex) throws IOException {
        Set<String> paths = new LinkedHashSet<>();
        String content = Files.readString(codebaseIndex);

        // Match /src/<name>/ patterns
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?:~|/home/\\w+)?(/src/[\\w-]+/)");
        java.util.regex.Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            String srcPath = matcher.group(1);
            // Resolve ~ to home directory
            String homePath = System.getProperty("user.home");
            String fullPath = homePath + srcPath;
            paths.add(fullPath);
        }

        return new ArrayList<>(paths);
    }

    /**
     * Generates keywords for an organization based on its name, clients, and products.
     */
    List<String> generateKeywords(Organization org) {
        Set<String> keywords = new LinkedHashSet<>();

        // Organization name
        keywords.add(org.getName());

        // Client names
        for (Client client : org.getClients()) {
            keywords.add(client.getName());
        }

        // Product names
        for (Product product : org.getProducts()) {
            keywords.add(product.getName());
        }

        return new ArrayList<>(keywords);
    }

    /**
     * Converts discovered organizations to sub-workspace configurations.
     *
     * <p>Each organization above the confidence threshold is converted to a
     * {@link SubWorkspaceConfig} with appropriate metadata. This method bridges
     * the legacy organization scanning with the new sub-workspace architecture.
     *
     * @return list of sub-workspace configurations generated from organizational scan
     * @since v1.4.0
     */
    public List<SubWorkspaceConfig> toSubWorkspaceConfigs() throws IOException {
        List<DiscoveredOrganization> discovered = discoverWithConfidence();
        List<SubWorkspaceConfig> configs = new ArrayList<>();

        for (DiscoveredOrganization disc : discovered) {
            Organization org = disc.organization();
            SubWorkspaceConfig swConfig = new SubWorkspaceConfig(
                    org.getName(),
                    org.getName()  // path prefix matches directory name
            );
            swConfig.setDescription(org.getDescription() != null
                    ? org.getDescription() : org.getType().name().toLowerCase() + " organization");

            // Determine type based on organization type
            String type = switch (org.getType()) {
                case COMPANY -> "general";
                case FOUNDATION -> "general";
                case HOLDING -> "general";
                case CONCEPT -> "general";
                case OTHER -> "general";
            };
            swConfig.setType(type);

            // Generate tags from organizational metadata
            List<String> tags = new ArrayList<>();
            tags.add(org.getType().name().toLowerCase());
            if (!org.getClients().isEmpty()) {
                tags.add("has-clients");
            }
            if (!org.getProducts().isEmpty()) {
                tags.add("has-products");
            }
            swConfig.setTags(tags);

            configs.add(swConfig);
        }

        return configs;
    }
}
