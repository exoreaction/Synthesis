package io.exoreaction.synthesis.org;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A client relationship within an organization.
 *
 * <p>Clients are detected from directory naming conventions:
 * <ul>
 *   <li>{@code clients/Elprint/} -> ACTIVE client "Elprint"</li>
 *   <li>{@code clients/Entra-past/} -> PAST client "Entra"</li>
 *   <li>{@code clients/opportunity-SpareBank1/} -> OPPORTUNITY "SpareBank1"</li>
 * </ul>
 */
public class Client {

    private String name;
    private String organization;
    private String basePath;
    private ClientStatus status;
    private String directoryName;
    private Map<String, String> metadata;

    /** No-arg constructor for JSON deserialization. */
    public Client() {
        this.metadata = new LinkedHashMap<>();
    }

    /**
     * Creates a Client from discovered directory information.
     *
     * @param name           clean client name (without prefix/suffix)
     * @param organization   parent organization name
     * @param basePath       absolute path to client directory
     * @param status         detected client status
     * @param directoryName  raw directory name
     */
    public Client(String name, String organization, Path basePath,
                  ClientStatus status, String directoryName) {
        this.name = name;
        this.organization = organization;
        this.basePath = basePath.toString();
        this.status = status;
        this.directoryName = directoryName;
        this.metadata = new LinkedHashMap<>();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getOrganization() { return organization; }
    public void setOrganization(String organization) { this.organization = organization; }

    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }

    public Path resolvedPath() { return Path.of(basePath); }

    public ClientStatus getStatus() { return status; }
    public void setStatus(ClientStatus status) { this.status = status; }

    public String getDirectoryName() { return directoryName; }
    public void setDirectoryName(String directoryName) { this.directoryName = directoryName; }

    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }

    /**
     * Returns whether the given path is within this client's directory.
     */
    public boolean containsPath(Path path) {
        Path resolved = resolvedPath().toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(resolved);
    }

    @Override
    public String toString() {
        return String.format("Client{name='%s', org='%s', status=%s}", name, organization, status);
    }
}
