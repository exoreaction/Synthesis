package io.exoreaction.synthesis.org;

import java.nio.file.Path;
import java.util.*;

/**
 * An organization (company, foundation, holding) discovered in the workspace.
 *
 * <p>Organizations are the top-level grouping entity. They contain clients,
 * products, and reference codebases. Each corresponds to a top-level directory
 * in the workspace (e.g., ~/Documents/eXOReaction/).
 *
 * <p>Discovery signals (from directory structure):
 * <ul>
 *   <li>Has README.md (+1 confidence)</li>
 *   <li>Has CODEBASE-INDEX.md (+3 confidence)</li>
 *   <li>Has clients/ directory (+2 confidence)</li>
 *   <li>Has products/ directory (+2 confidence)</li>
 *   <li>Has business/ directory (+2 confidence)</li>
 *   <li>Has marketing/ directory (+1 confidence)</li>
 * </ul>
 */
public class Organization {

    private String name;
    private OrganizationType type;
    private String basePath;
    private String description;
    private List<Client> clients;
    private List<Product> products;
    private List<String> codebasePaths;
    private List<String> keywords;
    private Map<String, String> metadata;

    /** No-arg constructor for JSON deserialization. */
    public Organization() {
        this.type = OrganizationType.COMPANY;
        this.clients = new ArrayList<>();
        this.products = new ArrayList<>();
        this.codebasePaths = new ArrayList<>();
        this.keywords = new ArrayList<>();
        this.metadata = new LinkedHashMap<>();
    }

    /**
     * Creates an Organization from discovered directory information.
     *
     * @param name     organization name (directory name)
     * @param type     organization type
     * @param basePath absolute path to organization directory
     */
    public Organization(String name, OrganizationType type, Path basePath) {
        this();
        this.name = name;
        this.type = type;
        this.basePath = basePath.toString();
    }

    // --- Getters and setters ---

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public OrganizationType getType() { return type; }
    public void setType(OrganizationType type) { this.type = type; }

    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }

    public Path resolvedPath() { return Path.of(basePath); }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<Client> getClients() { return clients; }
    public void setClients(List<Client> clients) { this.clients = clients; }

    public List<Product> getProducts() { return products; }
    public void setProducts(List<Product> products) { this.products = products; }

    public List<String> getCodebasePaths() { return codebasePaths; }
    public void setCodebasePaths(List<String> codebasePaths) { this.codebasePaths = codebasePaths; }

    public List<String> getKeywords() { return keywords; }
    public void setKeywords(List<String> keywords) { this.keywords = keywords; }

    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }

    // --- Convenience methods ---

    /**
     * Adds a client to this organization.
     */
    public void addClient(Client client) {
        clients.add(client);
    }

    /**
     * Adds a product to this organization.
     */
    public void addProduct(Product product) {
        products.add(product);
    }

    /**
     * Adds a codebase path to this organization.
     */
    public void addCodebasePath(String path) {
        if (!codebasePaths.contains(path)) {
            codebasePaths.add(path);
        }
    }

    /**
     * Returns clients filtered by status.
     */
    public List<Client> getClientsByStatus(ClientStatus status) {
        return clients.stream()
                .filter(c -> c.getStatus() == status)
                .toList();
    }

    /**
     * Finds a client by name (case-insensitive).
     */
    public Optional<Client> findClient(String clientName) {
        return clients.stream()
                .filter(c -> c.getName().equalsIgnoreCase(clientName))
                .findFirst();
    }

    /**
     * Returns whether the given path is within this organization's directory.
     */
    public boolean containsPath(Path path) {
        Path resolved = resolvedPath().toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(resolved);
    }

    /**
     * Resolves which client (if any) a file path belongs to.
     *
     * @param path the file path to check
     * @return the matching client, or empty if file is not within any client directory
     */
    public Optional<Client> resolveClient(Path path) {
        return clients.stream()
                .filter(c -> c.containsPath(path))
                .findFirst();
    }

    @Override
    public String toString() {
        return String.format("Organization{name='%s', type=%s, clients=%d, products=%d}",
                name, type, clients.size(), products.size());
    }
}
