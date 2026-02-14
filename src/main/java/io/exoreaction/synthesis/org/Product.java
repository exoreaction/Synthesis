package io.exoreaction.synthesis.org;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A product owned by an organization.
 *
 * <p>Detected from {@code products/<name>/} directories within an organization.
 */
public class Product {

    private String name;
    private String organization;
    private String basePath;
    private Map<String, String> metadata;

    /** No-arg constructor for JSON deserialization. */
    public Product() {
        this.metadata = new LinkedHashMap<>();
    }

    /**
     * Creates a Product from discovered directory information.
     *
     * @param name         product name (directory name)
     * @param organization parent organization name
     * @param basePath     absolute path to product directory
     */
    public Product(String name, String organization, Path basePath) {
        this.name = name;
        this.organization = organization;
        this.basePath = basePath.toString();
        this.metadata = new LinkedHashMap<>();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getOrganization() { return organization; }
    public void setOrganization(String organization) { this.organization = organization; }

    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }

    public Path resolvedPath() { return Path.of(basePath); }

    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }

    @Override
    public String toString() {
        return String.format("Product{name='%s', org='%s'}", name, organization);
    }
}
