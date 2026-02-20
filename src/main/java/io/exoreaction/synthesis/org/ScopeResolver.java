package io.exoreaction.synthesis.org;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves a directory path to a scope level (workspace, organization, or entity).
 *
 * <p>Given a path and an {@link OrganizationRegistry}, determines the most specific
 * scope that the path belongs to:
 * <ol>
 *   <li>If the path is within a known client directory → {@link ScopeLevel#ENTITY}</li>
 *   <li>If the path contains an {@code opportunity-*} component or is under a
 *       {@code clients/} directory → {@link ScopeLevel#ENTITY}</li>
 *   <li>If the path is within a known organization but not a client → {@link ScopeLevel#ORGANIZATION}</li>
 *   <li>Otherwise → {@link ScopeLevel#WORKSPACE}</li>
 * </ol>
 */
public class ScopeResolver {

    /**
     * Immutable result of scope resolution.
     *
     * @param level        the resolved scope level
     * @param organization the organization name (null at WORKSPACE level)
     * @param entity       the entity/client name (null at WORKSPACE and ORGANIZATION levels)
     */
    public record ResolvedScope(
            ScopeLevel level,
            String organization,
            String entity
    ) {}

    private final OrganizationRegistry registry;

    /**
     * Creates a ScopeResolver backed by the given registry.
     *
     * @param registry the organization registry (may be null, in which case
     *                 all paths resolve to WORKSPACE scope)
     */
    public ScopeResolver(OrganizationRegistry registry) {
        this.registry = registry;
    }

    /**
     * Resolves the scope for a given directory path.
     *
     * @param directoryPath the path to resolve
     * @return the resolved scope with level, organization, and entity information
     */
    public ResolvedScope resolve(Path directoryPath) {
        if (registry == null) {
            return new ResolvedScope(ScopeLevel.WORKSPACE, null, null);
        }

        Path normalized = directoryPath.toAbsolutePath().normalize();

        for (Organization org : registry.getOrganizations()) {
            if (!org.containsPath(normalized)) {
                continue;
            }

            // Path is inside this organization — check for client first
            Optional<Client> client = org.resolveClient(normalized);
            if (client.isPresent()) {
                return new ResolvedScope(ScopeLevel.ENTITY, org.getName(), client.get().getName());
            }

            // Walk path components between org basePath and the given path
            // to detect opportunity-* or clients/ patterns
            Path orgBase = Path.of(org.getBasePath()).toAbsolutePath().normalize();
            Path relative = orgBase.relativize(normalized);

            for (int i = 0; i < relative.getNameCount(); i++) {
                String component = relative.getName(i).toString();

                // Check for opportunity-* prefix
                if (component.startsWith("opportunity-")) {
                    String entityName = ClientStatus.extractClientName(component);
                    return new ResolvedScope(ScopeLevel.ENTITY, org.getName(), entityName);
                }

                // Check if parent directory is "clients" and we have a subdirectory
                if (i > 0 && relative.getName(i - 1).toString().equals("clients")) {
                    return new ResolvedScope(ScopeLevel.ENTITY, org.getName(), component);
                }
            }

            // Inside org but not a specific entity
            return new ResolvedScope(ScopeLevel.ORGANIZATION, org.getName(), null);
        }

        // Not inside any known organization
        return new ResolvedScope(ScopeLevel.WORKSPACE, null, null);
    }
}
