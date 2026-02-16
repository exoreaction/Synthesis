package io.exoreaction.synthesis.org;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Registry of all discovered organizations, clients, and products.
 *
 * <p>Persists to {@code .synthesis/organizations.json} and provides
 * lookup methods for resolving which organization/client a file belongs to.
 *
 * <p>Thread safety: This class is NOT thread-safe. Use from a single thread.
 */
public class OrganizationRegistry {

    private static final String ORGS_FILE = "organizations.json";

    private final Path workspaceRoot;
    private final List<Organization> organizations;
    private Instant lastScanTime;

    public OrganizationRegistry(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.organizations = new ArrayList<>();
    }

    /**
     * Returns all organizations in the registry.
     */
    public List<Organization> getOrganizations() {
        return Collections.unmodifiableList(organizations);
    }

    /**
     * Returns the last scan timestamp.
     */
    public Instant getLastScanTime() {
        return lastScanTime;
    }

    /**
     * Adds an organization to the registry.
     */
    public void addOrganization(Organization org) {
        organizations.add(org);
    }

    /**
     * Clears all organizations (before a fresh scan).
     */
    public void clear() {
        organizations.clear();
        lastScanTime = null;
    }

    /**
     * Sets the scan timestamp.
     */
    public void setLastScanTime(Instant time) {
        this.lastScanTime = time;
    }

    /**
     * Returns whether any organizations are registered.
     */
    public boolean hasOrganizations() {
        return !organizations.isEmpty();
    }

    /**
     * Finds an organization by name (case-insensitive).
     */
    public Optional<Organization> findOrganization(String name) {
        return organizations.stream()
                .filter(o -> o.getName().equalsIgnoreCase(name))
                .findFirst();
    }

    /**
     * Finds a client across all organizations (case-insensitive).
     */
    public Optional<Client> findClient(String clientName) {
        for (Organization org : organizations) {
            Optional<Client> client = org.findClient(clientName);
            if (client.isPresent()) return client;
        }
        return Optional.empty();
    }

    /**
     * Returns all clients across all organizations.
     */
    public List<Client> getAllClients() {
        List<Client> all = new ArrayList<>();
        for (Organization org : organizations) {
            all.addAll(org.getClients());
        }
        return all;
    }

    /**
     * Resolves which organization a file path belongs to.
     *
     * @param path the file path to check
     * @return the organization name, or null if not within any organization
     */
    public String resolveOrganization(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        for (Organization org : organizations) {
            if (org.containsPath(normalized)) {
                return org.getName();
            }
        }
        return null;
    }

    /**
     * Resolves which client a file path belongs to.
     *
     * @param path the file path to check
     * @return the client name, or null if not within any client directory
     */
    public String resolveClient(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        for (Organization org : organizations) {
            Optional<Client> client = org.resolveClient(normalized);
            if (client.isPresent()) {
                return client.get().getName();
            }
        }
        return null;
    }

    /**
     * Returns all organization keywords for classification.
     * Map from keyword (lowercase) to organization name.
     */
    public Map<String, String> buildKeywordIndex() {
        Map<String, String> index = new LinkedHashMap<>();
        for (Organization org : organizations) {
            // Organization name itself
            index.put(org.getName().toLowerCase(), org.getName());
            // Configured keywords
            for (String keyword : org.getKeywords()) {
                index.put(keyword.toLowerCase(), org.getName());
            }
            // Client names as keywords
            for (Client client : org.getClients()) {
                index.put(client.getName().toLowerCase(), org.getName());
            }
            // Product names as keywords
            for (Product product : org.getProducts()) {
                index.put(product.getName().toLowerCase(), org.getName());
            }
        }
        return index;
    }

    // --- Persistence ---

    /**
     * Resolves the path to the organizations.json file.
     */
    public Path getOrgsFilePath() {
        return workspaceRoot.resolve(".synthesis").resolve(ORGS_FILE);
    }

    /**
     * Loads organization data from .synthesis/organizations.json.
     */
    public void load() throws IOException {
        Path orgsFile = getOrgsFilePath();
        if (!Files.exists(orgsFile)) {
            return;
        }
        String json = Files.readString(orgsFile);
        parseJson(json);
    }

    /**
     * Saves organization data to .synthesis/organizations.json.
     */
    public void save() throws IOException {
        Path orgsFile = getOrgsFilePath();
        Files.createDirectories(orgsFile.getParent());
        try (Writer writer = Files.newBufferedWriter(orgsFile)) {
            writeJson(writer);
        }
    }

    // --- JSON Writing ---

    private void writeJson(Writer w) throws IOException {
        w.write("{\n");
        w.write("  \"version\": 1,\n");
        w.write("  \"lastScanTime\": " +
                (lastScanTime != null ? "\"" + lastScanTime + "\"" : "null") + ",\n");
        w.write("  \"organizations\": [\n");

        var orgIt = organizations.iterator();
        while (orgIt.hasNext()) {
            Organization org = orgIt.next();
            w.write("    {\n");
            w.write("      \"name\": \"" + escapeJson(org.getName()) + "\",\n");
            w.write("      \"type\": \"" + org.getType().name() + "\",\n");
            w.write("      \"basePath\": \"" + escapeJson(org.getBasePath()) + "\",\n");
            w.write("      \"description\": " +
                    (org.getDescription() != null ? "\"" + escapeJson(org.getDescription()) + "\"" : "null") + ",\n");

            // Keywords
            w.write("      \"keywords\": [");
            var kwIt = org.getKeywords().iterator();
            while (kwIt.hasNext()) {
                w.write("\"" + escapeJson(kwIt.next()) + "\"");
                if (kwIt.hasNext()) w.write(", ");
            }
            w.write("],\n");

            // Codebase paths
            w.write("      \"codebasePaths\": [");
            var cpIt = org.getCodebasePaths().iterator();
            while (cpIt.hasNext()) {
                w.write("\"" + escapeJson(cpIt.next()) + "\"");
                if (cpIt.hasNext()) w.write(", ");
            }
            w.write("],\n");

            // Clients
            w.write("      \"clients\": [\n");
            var clientIt = org.getClients().iterator();
            while (clientIt.hasNext()) {
                Client client = clientIt.next();
                w.write("        {\n");
                w.write("          \"name\": \"" + escapeJson(client.getName()) + "\",\n");
                w.write("          \"status\": \"" + client.getStatus().name() + "\",\n");
                w.write("          \"basePath\": \"" + escapeJson(client.getBasePath()) + "\",\n");
                w.write("          \"directoryName\": \"" + escapeJson(client.getDirectoryName()) + "\",\n");

                // Codebases array
                w.write("          \"codebases\": [");
                var cbIt = client.getCodebases().iterator();
                while (cbIt.hasNext()) {
                    w.write("\"" + escapeJson(cbIt.next()) + "\"");
                    if (cbIt.hasNext()) w.write(", ");
                }
                w.write("]\n");

                w.write("        }");
                if (clientIt.hasNext()) w.write(",");
                w.write("\n");
            }
            w.write("      ],\n");

            // Products
            w.write("      \"products\": [\n");
            var prodIt = org.getProducts().iterator();
            while (prodIt.hasNext()) {
                Product prod = prodIt.next();
                w.write("        {\n");
                w.write("          \"name\": \"" + escapeJson(prod.getName()) + "\",\n");
                w.write("          \"basePath\": \"" + escapeJson(prod.getBasePath()) + "\"\n");
                w.write("        }");
                if (prodIt.hasNext()) w.write(",");
                w.write("\n");
            }
            w.write("      ]\n");

            w.write("    }");
            if (orgIt.hasNext()) w.write(",");
            w.write("\n");
        }

        w.write("  ]\n");
        w.write("}\n");
    }

    // --- JSON Parsing (minimal, dependency-free) ---

    private void parseJson(String json) {
        organizations.clear();
        try {
            // Parse lastScanTime
            String timeStr = extractStringValue(json, "lastScanTime");
            if (timeStr != null) {
                lastScanTime = Instant.parse(timeStr);
            }

            // Find organizations array
            int orgsStart = json.indexOf("\"organizations\"");
            if (orgsStart < 0) return;
            int arrayStart = json.indexOf('[', orgsStart);
            if (arrayStart < 0) return;

            // Parse each organization object
            int pos = arrayStart + 1;
            while (pos < json.length()) {
                int objStart = json.indexOf('{', pos);
                if (objStart < 0) break;

                // Find matching closing brace (handling nested objects)
                int objEnd = findMatchingBrace(json, objStart);
                if (objEnd < 0) break;

                String orgJson = json.substring(objStart, objEnd + 1);
                Organization org = parseOrganization(orgJson);
                if (org != null) {
                    organizations.add(org);
                }

                pos = objEnd + 1;
                // Check if we've passed the end of the organizations array
                int nextBracket = json.indexOf(']', pos);
                int nextBrace = json.indexOf('{', pos);
                if (nextBracket >= 0 && (nextBrace < 0 || nextBracket < nextBrace)) {
                    break; // End of array
                }
            }
        } catch (Exception e) {
            // If parsing fails, start fresh
            organizations.clear();
        }
    }

    private Organization parseOrganization(String json) {
        String name = extractStringValue(json, "name");
        String typeStr = extractStringValue(json, "type");
        String basePath = extractStringValue(json, "basePath");
        String description = extractStringValue(json, "description");

        if (name == null || basePath == null) return null;

        OrganizationType type = OrganizationType.COMPANY;
        if (typeStr != null) {
            try { type = OrganizationType.valueOf(typeStr); }
            catch (IllegalArgumentException e) { /* keep default */ }
        }

        Organization org = new Organization(name, type, Path.of(basePath));
        org.setDescription(description);

        // Parse keywords
        org.setKeywords(extractStringArray(json, "keywords"));

        // Parse codebase paths
        org.setCodebasePaths(extractStringArray(json, "codebasePaths"));

        // Parse clients
        List<String> clientJsons = extractObjectArray(json, "clients");
        for (String clientJson : clientJsons) {
            Client client = parseClient(clientJson, name);
            if (client != null) {
                org.addClient(client);
            }
        }

        // Parse products
        List<String> productJsons = extractObjectArray(json, "products");
        for (String productJson : productJsons) {
            Product product = parseProduct(productJson, name);
            if (product != null) {
                org.addProduct(product);
            }
        }

        return org;
    }

    private Client parseClient(String json, String orgName) {
        String name = extractStringValue(json, "name");
        String statusStr = extractStringValue(json, "status");
        String basePath = extractStringValue(json, "basePath");
        String dirName = extractStringValue(json, "directoryName");

        if (name == null || basePath == null) return null;

        ClientStatus status = ClientStatus.ACTIVE;
        if (statusStr != null) {
            try { status = ClientStatus.valueOf(statusStr); }
            catch (IllegalArgumentException e) { /* keep default */ }
        }

        Client client = new Client(name, orgName, Path.of(basePath), status,
                dirName != null ? dirName : name);

        // Parse codebases array
        List<String> codebases = extractStringArray(json, "codebases");
        client.setCodebases(codebases);

        return client;
    }

    private Product parseProduct(String json, String orgName) {
        String name = extractStringValue(json, "name");
        String basePath = extractStringValue(json, "basePath");

        if (name == null || basePath == null) return null;

        return new Product(name, orgName, Path.of(basePath));
    }

    // --- JSON utility methods ---

    private static String extractStringValue(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + search.length());
        if (colonIdx < 0) return null;

        int start = colonIdx + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return null;

        if (json.charAt(start) == 'n') return null; // null value

        if (json.charAt(start) != '"') return null;

        // Find end quote, handling escaped quotes
        int end = start + 1;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '"') break;
            if (c == '\\') {
                end++; // skip escaped character
            }
            end++;
        }
        if (end >= json.length()) return null;

        return unescapeJson(json.substring(start + 1, end));
    }

    private static String unescapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"': sb.append('"'); i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case 'n': sb.append('\n'); i++; break;
                    case 'r': sb.append('\r'); i++; break;
                    case 't': sb.append('\t'); i++; break;
                    default: sb.append(c); break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static List<String> extractStringArray(String json, String key) {
        List<String> result = new ArrayList<>();
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return result;

        int arrayStart = json.indexOf('[', idx);
        if (arrayStart < 0) return result;
        int arrayEnd = json.indexOf(']', arrayStart);
        if (arrayEnd < 0) return result;

        String arrayContent = json.substring(arrayStart + 1, arrayEnd);
        int pos = 0;
        while (pos < arrayContent.length()) {
            int strStart = arrayContent.indexOf('"', pos);
            if (strStart < 0) break;
            int strEnd = arrayContent.indexOf('"', strStart + 1);
            if (strEnd < 0) break;
            result.add(arrayContent.substring(strStart + 1, strEnd)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\"));
            pos = strEnd + 1;
        }
        return result;
    }

    private static List<String> extractObjectArray(String json, String key) {
        List<String> result = new ArrayList<>();
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return result;

        int arrayStart = json.indexOf('[', idx);
        if (arrayStart < 0) return result;

        // Find matching ]
        int depth = 1;
        int pos = arrayStart + 1;
        while (pos < json.length() && depth > 0) {
            char c = json.charAt(pos);
            if (c == '[') depth++;
            else if (c == ']') depth--;
            pos++;
        }
        int arrayEnd = pos - 1;

        String arrayContent = json.substring(arrayStart + 1, arrayEnd);

        // Extract individual objects
        int objPos = 0;
        while (objPos < arrayContent.length()) {
            int objStart = arrayContent.indexOf('{', objPos);
            if (objStart < 0) break;
            int objEnd = findMatchingBrace(arrayContent, objStart);
            if (objEnd < 0) break;
            result.add(arrayContent.substring(objStart, objEnd + 1));
            objPos = objEnd + 1;
        }

        return result;
    }

    private static int findMatchingBrace(String json, int openBrace) {
        int depth = 1;
        boolean inString = false;
        for (int i = openBrace + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
