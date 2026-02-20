package io.exoreaction.synthesis.org;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Parses and writes directory-level {@code .synthesis.md} metadata files.
 *
 * <p>Uses a simple line-based parser for YAML front matter — no external
 * YAML library dependency required. The front matter format is intentionally
 * kept simple enough for line-by-line parsing.
 */
public class DirectoryIdentityParser {

    /**
     * Reads a directory-level {@code .synthesis.md} file and returns its
     * {@link DirectoryIdentity}. Returns {@link DirectoryIdentity#empty()}
     * if the file doesn't exist or has no front matter.
     *
     * @param synthesisFile path to the {@code .synthesis.md} file
     * @return parsed identity, or empty if file is missing/unparseable
     */
    public DirectoryIdentity parse(Path synthesisFile) {
        if (synthesisFile == null || !Files.exists(synthesisFile)) {
            return DirectoryIdentity.empty();
        }

        String content;
        try {
            content = Files.readString(synthesisFile);
        } catch (IOException e) {
            return DirectoryIdentity.empty();
        }

        if (content.isBlank()) {
            return DirectoryIdentity.empty();
        }

        String yamlBlock = extractYamlFrontMatter(content);
        String markdownBody = extractMarkdownBody(content);

        if (yamlBlock == null || yamlBlock.isBlank()) {
            // No YAML front matter — only markdown body
            return new DirectoryIdentity(
                    List.of(), List.of(), List.of(),
                    ScopeLevel.WORKSPACE, null, null,
                    0.0, null, "",
                    markdownBody.trim()
            );
        }

        return parseYaml(yamlBlock, markdownBody.trim());
    }

    /**
     * Writes or updates a {@code .synthesis.md} file. Preserves existing
     * markdown body below the YAML front matter. Always updates
     * {@code last_synced} to the current instant.
     *
     * @param synthesisFile path to the {@code .synthesis.md} file
     * @param identity      the directory identity to write
     * @throws IOException if writing fails
     */
    public void write(Path synthesisFile, DirectoryIdentity identity) throws IOException {
        // Read existing body if file exists
        String existingBody = "";
        if (Files.exists(synthesisFile)) {
            String existingContent = Files.readString(synthesisFile);
            existingBody = extractMarkdownBody(existingContent).trim();
        }

        // Use identity description if present, otherwise keep existing body
        String body = (identity.description() != null && !identity.description().isBlank())
                ? identity.description()
                : existingBody;

        Instant lastSynced = Instant.now();

        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("synthesis:\n");

        // accepts block
        boolean hasAccepts = !identity.acceptsTypes().isEmpty()
                || !identity.acceptsFormats().isEmpty()
                || !identity.acceptsPatterns().isEmpty();
        if (hasAccepts) {
            sb.append("  accepts:\n");
            if (!identity.acceptsTypes().isEmpty()) {
                sb.append("    types:\n");
                for (String t : identity.acceptsTypes()) {
                    sb.append("      - \"").append(t).append("\"\n");
                }
            }
            if (!identity.acceptsFormats().isEmpty()) {
                sb.append("    formats:\n");
                for (String f : identity.acceptsFormats()) {
                    sb.append("      - \"").append(f).append("\"\n");
                }
            }
            if (!identity.acceptsPatterns().isEmpty()) {
                sb.append("    patterns:\n");
                for (String p : identity.acceptsPatterns()) {
                    sb.append("      - \"").append(p).append("\"\n");
                }
            }
        }

        // scope block
        sb.append("  scope:\n");
        sb.append("    level: \"").append(identity.scopeLevel().name()).append("\"\n");
        if (identity.scopeOrganization() != null) {
            sb.append("    organization: \"").append(identity.scopeOrganization()).append("\"\n");
        } else {
            sb.append("    organization: null\n");
        }
        if (identity.scopeEntity() != null) {
            sb.append("    entity: \"").append(identity.scopeEntity()).append("\"\n");
        } else {
            sb.append("    entity: null\n");
        }

        // confidence
        sb.append("  confidence: ").append(formatDouble(identity.confidence())).append("\n");

        // last_synced — always current
        sb.append("  last_synced: \"").append(lastSynced.toString()).append("\"\n");

        // source
        if (identity.source() != null && !identity.source().isEmpty()) {
            sb.append("  source: \"").append(identity.source()).append("\"\n");
        }

        sb.append("---\n");

        // Markdown body
        if (body != null && !body.isBlank()) {
            sb.append("\n").append(body).append("\n");
        }

        Files.writeString(synthesisFile, sb.toString());
    }

    /**
     * Merges discovered patterns into an existing identity, preserving
     * user-declared values.
     *
     * <p>Merge rules:
     * <ul>
     *   <li>{@code acceptsTypes/Formats/Patterns}: union (append discovered items not already present)</li>
     *   <li>{@code scopeLevel/Organization/Entity}: keep existing if non-null/non-default, otherwise use discovered</li>
     *   <li>{@code confidence}: use max of existing and discovered</li>
     *   <li>{@code source}: keep existing if present, use discovered otherwise</li>
     *   <li>{@code description}: keep existing if non-empty</li>
     * </ul>
     *
     * @param existing   the current identity (may be empty)
     * @param discovered the newly discovered identity
     * @return merged identity
     */
    public DirectoryIdentity merge(DirectoryIdentity existing, DirectoryIdentity discovered) {
        List<String> mergedTypes = mergeList(existing.acceptsTypes(), discovered.acceptsTypes());
        List<String> mergedFormats = mergeList(existing.acceptsFormats(), discovered.acceptsFormats());
        List<String> mergedPatterns = mergeList(existing.acceptsPatterns(), discovered.acceptsPatterns());

        ScopeLevel scopeLevel = (existing.scopeLevel() != null && existing.scopeLevel() != ScopeLevel.WORKSPACE)
                ? existing.scopeLevel()
                : discovered.scopeLevel();

        String scopeOrg = (existing.scopeOrganization() != null && !existing.scopeOrganization().isEmpty())
                ? existing.scopeOrganization()
                : discovered.scopeOrganization();

        String scopeEntity = (existing.scopeEntity() != null && !existing.scopeEntity().isEmpty())
                ? existing.scopeEntity()
                : discovered.scopeEntity();

        double confidence = Math.max(existing.confidence(), discovered.confidence());

        Instant lastSynced = existing.lastSynced() != null ? existing.lastSynced() : discovered.lastSynced();

        String source = (existing.source() != null && !existing.source().isEmpty())
                ? existing.source()
                : discovered.source();

        String description = (existing.description() != null && !existing.description().isEmpty())
                ? existing.description()
                : discovered.description();

        return new DirectoryIdentity(
                mergedTypes, mergedFormats, mergedPatterns,
                scopeLevel, scopeOrg, scopeEntity,
                confidence, lastSynced, source, description
        );
    }

    // ---- Internal helpers ----

    private String extractYamlFrontMatter(String content) {
        String trimmed = content.stripLeading();
        if (!trimmed.startsWith("---")) {
            return null;
        }
        int firstDelim = trimmed.indexOf("---");
        int secondDelim = trimmed.indexOf("---", firstDelim + 3);
        if (secondDelim < 0) {
            return null;
        }
        return trimmed.substring(firstDelim + 3, secondDelim).trim();
    }

    private String extractMarkdownBody(String content) {
        String trimmed = content.stripLeading();
        if (!trimmed.startsWith("---")) {
            return content;
        }
        int firstDelim = trimmed.indexOf("---");
        int secondDelim = trimmed.indexOf("---", firstDelim + 3);
        if (secondDelim < 0) {
            return "";
        }
        // Everything after the closing ---
        String after = trimmed.substring(secondDelim + 3);
        return after.stripLeading();
    }

    private DirectoryIdentity parseYaml(String yamlBlock, String description) {
        List<String> acceptsTypes = new ArrayList<>();
        List<String> acceptsFormats = new ArrayList<>();
        List<String> acceptsPatterns = new ArrayList<>();
        ScopeLevel scopeLevel = ScopeLevel.WORKSPACE;
        String scopeOrg = null;
        String scopeEntity = null;
        double confidence = 0.0;
        Instant lastSynced = null;
        String source = "";

        // Track which list section we're in
        String currentListContext = null;

        String[] lines = yamlBlock.split("\n");
        for (String line : lines) {
            String stripped = line.stripTrailing();

            // Detect list items
            if (stripped.stripLeading().startsWith("- ")) {
                String value = extractListItemValue(stripped);
                if (value != null && currentListContext != null) {
                    switch (currentListContext) {
                        case "types" -> acceptsTypes.add(value);
                        case "formats" -> acceptsFormats.add(value);
                        case "patterns" -> acceptsPatterns.add(value);
                    }
                }
                continue;
            }

            // Detect key-value or section headers
            String trimmedLine = stripped.stripLeading();

            if (trimmedLine.startsWith("types:")) {
                currentListContext = "types";
            } else if (trimmedLine.startsWith("formats:")) {
                currentListContext = "formats";
            } else if (trimmedLine.startsWith("patterns:")) {
                currentListContext = "patterns";
            } else if (trimmedLine.startsWith("level:")) {
                currentListContext = null;
                String val = extractScalarValue(trimmedLine, "level:");
                if (val != null) {
                    try {
                        scopeLevel = ScopeLevel.valueOf(val.toUpperCase());
                    } catch (IllegalArgumentException ignored) {
                        // keep default
                    }
                }
            } else if (trimmedLine.startsWith("organization:")) {
                currentListContext = null;
                scopeOrg = extractScalarValue(trimmedLine, "organization:");
            } else if (trimmedLine.startsWith("entity:")) {
                currentListContext = null;
                scopeEntity = extractScalarValue(trimmedLine, "entity:");
            } else if (trimmedLine.startsWith("confidence:")) {
                currentListContext = null;
                String val = extractRawValue(trimmedLine, "confidence:");
                if (val != null) {
                    try {
                        confidence = Double.parseDouble(val);
                    } catch (NumberFormatException ignored) {
                        // keep default
                    }
                }
            } else if (trimmedLine.startsWith("last_synced:")) {
                currentListContext = null;
                String val = extractScalarValue(trimmedLine, "last_synced:");
                if (val != null) {
                    try {
                        lastSynced = Instant.parse(val);
                    } catch (Exception ignored) {
                        // keep null
                    }
                }
            } else if (trimmedLine.startsWith("source:")) {
                currentListContext = null;
                String val = extractScalarValue(trimmedLine, "source:");
                if (val != null) {
                    source = val;
                }
            } else if (trimmedLine.startsWith("synthesis:") || trimmedLine.startsWith("accepts:")
                    || trimmedLine.startsWith("scope:") || trimmedLine.startsWith("role:")) {
                // Section headers — don't reset currentListContext for accepts sub-keys
                if (!trimmedLine.startsWith("accepts:")) {
                    currentListContext = null;
                }
            }
        }

        return new DirectoryIdentity(
                List.copyOf(acceptsTypes),
                List.copyOf(acceptsFormats),
                List.copyOf(acceptsPatterns),
                scopeLevel, scopeOrg, scopeEntity,
                confidence, lastSynced, source, description
        );
    }

    /**
     * Extracts a scalar value from a YAML line like {@code key: "value"} or {@code key: value}.
     * Returns {@code null} for the literal string {@code null}.
     */
    private String extractScalarValue(String line, String prefix) {
        String raw = extractRawValue(line, prefix);
        if (raw == null || raw.equalsIgnoreCase("null")) {
            return null;
        }
        // Strip surrounding quotes
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
            return raw.substring(1, raw.length() - 1);
        }
        if (raw.startsWith("'") && raw.endsWith("'") && raw.length() >= 2) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    private String extractRawValue(String line, String prefix) {
        int idx = line.indexOf(prefix);
        if (idx < 0) return null;
        String after = line.substring(idx + prefix.length()).strip();
        return after.isEmpty() ? null : after;
    }

    /**
     * Extracts the value from a YAML list item like {@code - "value"} or {@code - value}.
     */
    private String extractListItemValue(String line) {
        String trimmed = line.stripLeading();
        if (!trimmed.startsWith("- ")) return null;
        String val = trimmed.substring(2).strip();
        // Strip surrounding quotes
        if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
            return val.substring(1, val.length() - 1);
        }
        if (val.startsWith("'") && val.endsWith("'") && val.length() >= 2) {
            return val.substring(1, val.length() - 1);
        }
        return val;
    }

    private List<String> mergeList(List<String> existing, List<String> discovered) {
        Set<String> seen = new LinkedHashSet<>(existing);
        seen.addAll(discovered);
        return List.copyOf(seen);
    }

    private String formatDouble(double value) {
        if (value == (long) value) {
            return String.valueOf((long) value);
        }
        // Avoid trailing zeros but keep meaningful precision
        String formatted = String.valueOf(value);
        // Remove trailing zeros after decimal point
        if (formatted.contains(".")) {
            formatted = formatted.replaceAll("0+$", "");
            formatted = formatted.replaceAll("\\.$", ".0");
        }
        return formatted;
    }
}
