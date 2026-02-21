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
        return parseProfile(synthesisFile).identity();
    }

    /**
     * Reads a directory-level {@code .synthesis.md} file and returns a full
     * {@link DirectoryProfile} including centroid and wants blocks.
     *
     * <p>Backward compatible: parsing a legacy file (no centroid/wants blocks)
     * returns {@link DirectoryCentroid#empty()} and {@link DirectoryWants#empty()}.
     *
     * @param synthesisFile path to the {@code .synthesis.md} file
     * @return parsed profile, or profile with empty identity/centroid/wants if missing
     */
    public DirectoryProfile parseProfile(Path synthesisFile) {
        if (synthesisFile == null || !Files.exists(synthesisFile)) {
            return DirectoryProfile.fromIdentity(DirectoryIdentity.empty());
        }

        String content;
        try {
            content = Files.readString(synthesisFile);
        } catch (IOException e) {
            return DirectoryProfile.fromIdentity(DirectoryIdentity.empty());
        }

        if (content.isBlank()) {
            return DirectoryProfile.fromIdentity(DirectoryIdentity.empty());
        }

        String yamlBlock = extractYamlFrontMatter(content);
        String markdownBody = extractMarkdownBody(content);

        if (yamlBlock == null || yamlBlock.isBlank()) {
            // No YAML front matter — only markdown body
            DirectoryIdentity identity = new DirectoryIdentity(
                    List.of(), List.of(), List.of(),
                    ScopeLevel.WORKSPACE, null, null,
                    0.0, null, "",
                    markdownBody.trim()
            );
            return DirectoryProfile.fromIdentity(identity);
        }

        return parseYamlFull(yamlBlock, markdownBody.trim());
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

        // transient — only emit when true
        if (identity.transient_()) {
            sb.append("  transient: true\n");
        }

        // aliases — only emit when non-empty
        if (!identity.aliases().isEmpty()) {
            sb.append("  aliases:\n");
            for (String alias : identity.aliases()) {
                sb.append("    - \"").append(alias).append("\"\n");
            }
        }

        // rejects_types — only emit when non-empty
        if (!identity.rejectsTypes().isEmpty()) {
            sb.append("  rejects_types:\n");
            for (String rt : identity.rejectsTypes()) {
                sb.append("    - \"").append(rt).append("\"\n");
            }
        }

        // moved_files — only emit when non-empty
        if (!identity.movedFiles().isEmpty()) {
            sb.append("  moved_files:\n");
            for (ForwardingPointer fp : identity.movedFiles()) {
                sb.append("    - file: \"").append(fp.fileName()).append("\"\n");
                if (fp.movedTo() != null) {
                    sb.append("      moved_to: \"").append(fp.movedTo()).append("\"\n");
                }
                if (fp.movedAt() != null) {
                    sb.append("      moved_at: \"").append(fp.movedAt().toString()).append("\"\n");
                }
                if (fp.movedBy() != null) {
                    sb.append("      moved_by: \"").append(fp.movedBy()).append("\"\n");
                }
                if (fp.reason() != null) {
                    sb.append("      reason: \"").append(fp.reason()).append("\"\n");
                }
            }
        }

        sb.append("---\n");

        // Markdown body
        if (body != null && !body.isBlank()) {
            sb.append("\n").append(body).append("\n");
        }

        Files.writeString(synthesisFile, sb.toString());
    }

    /**
     * Writes or updates a {@code .synthesis.md} file with full profile data
     * including centroid and wants blocks. Preserves existing markdown body.
     * Always updates {@code last_synced} to the current instant.
     *
     * @param synthesisFile path to the {@code .synthesis.md} file
     * @param profile       the full directory profile to write
     * @throws IOException if writing fails
     */
    public void writeProfile(Path synthesisFile, DirectoryProfile profile) throws IOException {
        // Read existing body if file exists
        String existingBody = "";
        if (Files.exists(synthesisFile)) {
            String existingContent = Files.readString(synthesisFile);
            existingBody = extractMarkdownBody(existingContent).trim();
        }

        DirectoryIdentity identity = profile.identity();

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

        // centroid block (Phase 2)
        DirectoryCentroid centroid = profile.centroid();
        if (centroid != null && !centroid.isEmpty()) {
            sb.append("  centroid:\n");
            if (!centroid.topics().isEmpty()) {
                sb.append("    topics:\n");
                for (String t : centroid.topics()) {
                    sb.append("      - \"").append(t).append("\"\n");
                }
            }
            if (!centroid.entities().isEmpty()) {
                sb.append("    entities:\n");
                for (String e : centroid.entities()) {
                    sb.append("      - \"").append(e).append("\"\n");
                }
            }
            if (centroid.timeframe() != null) {
                sb.append("    timeframe: \"").append(centroid.timeframe()).append("\"\n");
            }
            if (!centroid.documentTypes().isEmpty()) {
                sb.append("    document_types:\n");
                for (String dt : centroid.documentTypes()) {
                    sb.append("      - \"").append(dt).append("\"\n");
                }
            }
            sb.append("    confidence: ").append(formatDouble(centroid.confidence())).append("\n");
            sb.append("    contributing_files: ").append(centroid.contributingFiles()).append("\n");
            if (centroid.lastUpdated() != null) {
                sb.append("    last_updated: \"").append(centroid.lastUpdated().toString()).append("\"\n");
            }
        }

        // wants block (Phase 2)
        DirectoryWants wants = profile.wants();
        if (wants != null && !wants.isEmpty()) {
            sb.append("  wants:\n");
            if (!wants.topics().isEmpty()) {
                sb.append("    topics:\n");
                for (String t : wants.topics()) {
                    sb.append("      - \"").append(t).append("\"\n");
                }
            }
            if (!wants.entities().isEmpty()) {
                sb.append("    entities:\n");
                for (String e : wants.entities()) {
                    sb.append("      - \"").append(e).append("\"\n");
                }
            }
            if (wants.source() != null) {
                sb.append("    source: \"").append(wants.source()).append("\"\n");
            }
            sb.append("    satisfaction: ").append(formatDouble(wants.satisfaction())).append("\n");
        }

        // transient — only emit when true
        if (identity.transient_()) {
            sb.append("  transient: true\n");
        }

        // aliases — only emit when non-empty
        if (!identity.aliases().isEmpty()) {
            sb.append("  aliases:\n");
            for (String alias : identity.aliases()) {
                sb.append("    - \"").append(alias).append("\"\n");
            }
        }

        // rejects_types — only emit when non-empty
        if (!identity.rejectsTypes().isEmpty()) {
            sb.append("  rejects_types:\n");
            for (String rt : identity.rejectsTypes()) {
                sb.append("    - \"").append(rt).append("\"\n");
            }
        }

        // moved_files — only emit when non-empty
        if (!identity.movedFiles().isEmpty()) {
            sb.append("  moved_files:\n");
            for (ForwardingPointer fp : identity.movedFiles()) {
                sb.append("    - file: \"").append(fp.fileName()).append("\"\n");
                if (fp.movedTo() != null) {
                    sb.append("      moved_to: \"").append(fp.movedTo()).append("\"\n");
                }
                if (fp.movedAt() != null) {
                    sb.append("      moved_at: \"").append(fp.movedAt().toString()).append("\"\n");
                }
                if (fp.movedBy() != null) {
                    sb.append("      moved_by: \"").append(fp.movedBy()).append("\"\n");
                }
                if (fp.reason() != null) {
                    sb.append("      reason: \"").append(fp.reason()).append("\"\n");
                }
            }
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

        // New fields: preserve from existing, fall back to discovered
        List<String> mergedRejectsTypes = mergeList(existing.rejectsTypes(), discovered.rejectsTypes());
        List<String> mergedAliases = mergeList(existing.aliases(), discovered.aliases());

        // Confidence-weighted transient merge (P1-01).
        // Practical signal confidences: 0.5 (≤3 files), 0.7 (≤10), 0.9 (>10).
        // Vocabulary confidence is typically 0.6 (DEFAULT_CONFIDENCE).
        //
        // Three cases when they disagree:
        //
        // A) discovered=true (vocabulary says "this type is transient"): propagate
        //    the designation unless the existing directory is significantly more
        //    confident — i.e. it has many files and has clearly settled. A settled
        //    directory (existing.confidence=0.94) beats a vocabulary update (0.6).
        //
        // B) discovered=false (signals say "not transient"): only clear the flag
        //    when signals have substantially higher confidence than the vocabulary
        //    (threshold: > 0.2). This means 11+ files (0.9) override vocab (0.6),
        //    but 1-3 files (0.5) do not.
        boolean transientFlag;
        if (existing.transient_() == discovered.transient_()) {
            // Both agree — use that value
            transientFlag = existing.transient_();
        } else if (discovered.transient_()) {
            // Case A: vocabulary update propagates unless existing is more confident
            transientFlag = discovered.confidence() >= existing.confidence();
        } else {
            // Case B: signals clear the flag only with significantly higher confidence
            transientFlag = !(discovered.confidence() > existing.confidence() + 0.2);
        }
        // movedFiles: always preserve existing (forwarding pointers are append-only)
        List<ForwardingPointer> mergedMovedFiles = existing.movedFiles().isEmpty()
                ? discovered.movedFiles()
                : existing.movedFiles();

        return new DirectoryIdentity(
                mergedTypes, mergedFormats, mergedPatterns,
                scopeLevel, scopeOrg, scopeEntity,
                confidence, lastSynced, source, description,
                mergedRejectsTypes, mergedAliases, transientFlag, mergedMovedFiles
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

    /**
     * Parses YAML front matter and returns a full {@link DirectoryProfile}
     * with identity, centroid, and wants. Backward compatible: missing centroid/wants
     * blocks produce empty records.
     */
    private DirectoryProfile parseYamlFull(String yamlBlock, String description) {
        // Parse identity (delegates to existing method)
        DirectoryIdentity identity = parseYaml(yamlBlock, description);

        // Parse centroid and wants blocks
        DirectoryCentroid centroid = parseCentroidBlock(yamlBlock);
        DirectoryWants wants = parseWantsBlock(yamlBlock);

        return new DirectoryProfile(identity, centroid, wants);
    }

    /**
     * Parses the {@code centroid:} block from YAML content.
     * Returns {@link DirectoryCentroid#empty()} if the block is absent.
     */
    private DirectoryCentroid parseCentroidBlock(String yamlBlock) {
        List<String> topics = new ArrayList<>();
        List<String> entities = new ArrayList<>();
        String timeframe = null;
        List<String> documentTypes = new ArrayList<>();
        double confidence = 0.0;
        int contributingFiles = 0;
        Instant lastUpdated = null;

        boolean inCentroid = false;
        String centroidListContext = null;

        String[] lines = yamlBlock.split("\n");
        for (String line : lines) {
            String stripped = line.stripTrailing();
            String trimmedLine = stripped.stripLeading();
            int indent = stripped.length() - trimmedLine.length();

            // Detect entering/leaving centroid block
            if (trimmedLine.startsWith("centroid:") && indent <= 2) {
                inCentroid = true;
                centroidListContext = null;
                continue;
            }

            // Exit centroid on a same-level or higher-level key
            if (inCentroid && indent <= 2 && !trimmedLine.isEmpty()
                    && !trimmedLine.startsWith("-") && !trimmedLine.startsWith("#")) {
                inCentroid = false;
                centroidListContext = null;
            }

            if (!inCentroid) continue;

            // List items within centroid
            if (trimmedLine.startsWith("- ")) {
                String value = extractListItemValue(stripped);
                if (value != null && centroidListContext != null) {
                    switch (centroidListContext) {
                        case "topics" -> topics.add(value);
                        case "entities" -> entities.add(value);
                        case "document_types" -> documentTypes.add(value);
                    }
                }
                continue;
            }

            // Centroid sub-keys
            if (trimmedLine.startsWith("topics:")) {
                centroidListContext = "topics";
            } else if (trimmedLine.startsWith("entities:")) {
                centroidListContext = "entities";
            } else if (trimmedLine.startsWith("document_types:")) {
                centroidListContext = "document_types";
            } else if (trimmedLine.startsWith("timeframe:")) {
                centroidListContext = null;
                timeframe = extractScalarValue(trimmedLine, "timeframe:");
            } else if (trimmedLine.startsWith("confidence:")) {
                centroidListContext = null;
                String val = extractRawValue(trimmedLine, "confidence:");
                if (val != null) {
                    try { confidence = Double.parseDouble(val); } catch (NumberFormatException ignored) {}
                }
            } else if (trimmedLine.startsWith("contributing_files:")) {
                centroidListContext = null;
                String val = extractRawValue(trimmedLine, "contributing_files:");
                if (val != null) {
                    try { contributingFiles = Integer.parseInt(val); } catch (NumberFormatException ignored) {}
                }
            } else if (trimmedLine.startsWith("last_updated:")) {
                centroidListContext = null;
                String val = extractScalarValue(trimmedLine, "last_updated:");
                if (val != null) {
                    try { lastUpdated = Instant.parse(val); } catch (Exception ignored) {}
                }
            }
        }

        if (topics.isEmpty() && entities.isEmpty() && documentTypes.isEmpty()
                && confidence == 0.0 && contributingFiles == 0) {
            return DirectoryCentroid.empty();
        }

        return new DirectoryCentroid(
                List.copyOf(topics),
                List.copyOf(entities),
                timeframe,
                List.copyOf(documentTypes),
                confidence,
                contributingFiles,
                0, // virtualMembers (Phase 3)
                lastUpdated
        );
    }

    /**
     * Parses the {@code wants:} block from YAML content.
     * Returns {@link DirectoryWants#empty()} if the block is absent.
     */
    private DirectoryWants parseWantsBlock(String yamlBlock) {
        List<String> topics = new ArrayList<>();
        List<String> entities = new ArrayList<>();
        String source = null;
        double satisfaction = 0.0;

        boolean inWants = false;
        String wantsListContext = null;

        String[] lines = yamlBlock.split("\n");
        for (String line : lines) {
            String stripped = line.stripTrailing();
            String trimmedLine = stripped.stripLeading();
            int indent = stripped.length() - trimmedLine.length();

            // Detect entering wants block -- must not be inside centroid's sub-keys
            // The wants: key appears at indent 2 (under synthesis:)
            if (trimmedLine.startsWith("wants:") && indent <= 2) {
                inWants = true;
                wantsListContext = null;
                continue;
            }

            // Exit wants on a same-level or higher-level key
            if (inWants && indent <= 2 && !trimmedLine.isEmpty()
                    && !trimmedLine.startsWith("-") && !trimmedLine.startsWith("#")) {
                inWants = false;
                wantsListContext = null;
            }

            if (!inWants) continue;

            // List items within wants
            if (trimmedLine.startsWith("- ")) {
                String value = extractListItemValue(stripped);
                if (value != null && wantsListContext != null) {
                    switch (wantsListContext) {
                        case "topics" -> topics.add(value);
                        case "entities" -> entities.add(value);
                    }
                }
                continue;
            }

            // Wants sub-keys
            if (trimmedLine.startsWith("topics:")) {
                wantsListContext = "topics";
            } else if (trimmedLine.startsWith("entities:")) {
                wantsListContext = "entities";
            } else if (trimmedLine.startsWith("source:")) {
                wantsListContext = null;
                source = extractScalarValue(trimmedLine, "source:");
            } else if (trimmedLine.startsWith("satisfaction:")) {
                wantsListContext = null;
                String val = extractRawValue(trimmedLine, "satisfaction:");
                if (val != null) {
                    try { satisfaction = Double.parseDouble(val); } catch (NumberFormatException ignored) {}
                }
            }
        }

        if (topics.isEmpty() && entities.isEmpty() && source == null) {
            return DirectoryWants.empty();
        }

        return new DirectoryWants(
                List.copyOf(topics),
                List.copyOf(entities),
                List.of(), // alsoLookingFor (Phase 4)
                source,
                satisfaction
        );
    }

    private DirectoryIdentity parseYaml(String yamlBlock, String description) {
        List<String> acceptsTypes = new ArrayList<>();
        List<String> acceptsFormats = new ArrayList<>();
        List<String> acceptsPatterns = new ArrayList<>();
        List<String> rejectsTypes = new ArrayList<>();
        List<String> aliases = new ArrayList<>();
        List<ForwardingPointer> movedFiles = new ArrayList<>();
        ScopeLevel scopeLevel = ScopeLevel.WORKSPACE;
        String scopeOrg = null;
        String scopeEntity = null;
        double confidence = 0.0;
        Instant lastSynced = null;
        String source = "";
        boolean transientFlag = false;

        // Track which list section we're in
        String currentListContext = null;

        // moved_files sub-object parsing state
        boolean inMovedFilesBlock = false;
        String mfFile = null;
        String mfMovedTo = null;
        Instant mfMovedAt = null;
        String mfMovedBy = null;
        String mfReason = null;

        // Phase 2: skip centroid: and wants: blocks (parsed separately by parseYamlFull)
        boolean inSkippedBlock = false;

        String[] lines = yamlBlock.split("\n");
        for (String line : lines) {
            String stripped = line.stripTrailing();
            String trimmedLine = stripped.stripLeading();
            int indent = stripped.length() - trimmedLine.length();

            // Phase 2: detect entering/leaving centroid: or wants: blocks
            if ((trimmedLine.startsWith("centroid:") || trimmedLine.startsWith("wants:")) && indent <= 2) {
                inSkippedBlock = true;
                currentListContext = null;
                inMovedFilesBlock = false;
                continue;
            }
            // Exit skipped block when we see a top-level key (indent <= 2) that isn't a list item
            if (inSkippedBlock && indent <= 2 && !trimmedLine.isEmpty()
                    && !trimmedLine.startsWith("-") && !trimmedLine.startsWith("#")) {
                // Check if this is a new section that identity cares about
                inSkippedBlock = false;
                // Fall through to normal parsing below
            }
            if (inSkippedBlock) continue;

            // Detect list items
            if (trimmedLine.startsWith("- ")) {
                if (inMovedFilesBlock) {
                    // Flush any previous moved_files entry
                    if (mfFile != null) {
                        movedFiles.add(new ForwardingPointer(mfFile, mfMovedTo, mfMovedAt, mfMovedBy, mfReason));
                    }
                    // Start a new entry — the "- file:" line
                    String value = extractListItemValue(stripped);
                    if (trimmedLine.startsWith("- file:")) {
                        mfFile = extractScalarValue(trimmedLine.substring(2).strip(), "file:");
                    } else {
                        mfFile = value;
                    }
                    mfMovedTo = null;
                    mfMovedAt = null;
                    mfMovedBy = null;
                    mfReason = null;
                    continue;
                }

                String value = extractListItemValue(stripped);
                if (value != null && currentListContext != null) {
                    switch (currentListContext) {
                        case "types" -> acceptsTypes.add(value);
                        case "formats" -> acceptsFormats.add(value);
                        case "patterns" -> acceptsPatterns.add(value);
                        case "rejects_types" -> rejectsTypes.add(value);
                        case "aliases" -> aliases.add(value);
                    }
                }
                continue;
            }

            // Inside moved_files block: parse sub-fields of a list entry
            if (inMovedFilesBlock && mfFile != null) {
                if (trimmedLine.startsWith("moved_to:")) {
                    mfMovedTo = extractScalarValue(trimmedLine, "moved_to:");
                    continue;
                } else if (trimmedLine.startsWith("moved_at:")) {
                    String val = extractScalarValue(trimmedLine, "moved_at:");
                    if (val != null) {
                        try { mfMovedAt = Instant.parse(val); } catch (Exception ignored) {}
                    }
                    continue;
                } else if (trimmedLine.startsWith("moved_by:")) {
                    mfMovedBy = extractScalarValue(trimmedLine, "moved_by:");
                    continue;
                } else if (trimmedLine.startsWith("reason:")) {
                    mfReason = extractScalarValue(trimmedLine, "reason:");
                    continue;
                }
            }

            // Detect key-value or section headers
            if (trimmedLine.startsWith("types:")) {
                currentListContext = "types";
                inMovedFilesBlock = false;
            } else if (trimmedLine.startsWith("formats:")) {
                currentListContext = "formats";
                inMovedFilesBlock = false;
            } else if (trimmedLine.startsWith("patterns:")) {
                currentListContext = "patterns";
                inMovedFilesBlock = false;
            } else if (trimmedLine.startsWith("rejects_types:") || trimmedLine.startsWith("rejectsTypes:")) {
                currentListContext = "rejects_types";
                inMovedFilesBlock = false;
            } else if (trimmedLine.startsWith("aliases:")) {
                currentListContext = "aliases";
                inMovedFilesBlock = false;
            } else if (trimmedLine.startsWith("moved_files:")) {
                // Flush previous context
                currentListContext = null;
                inMovedFilesBlock = true;
                mfFile = null;
            } else if (trimmedLine.startsWith("transient:")) {
                currentListContext = null;
                inMovedFilesBlock = false;
                String val = extractRawValue(trimmedLine, "transient:");
                transientFlag = "true".equalsIgnoreCase(val);
            } else if (trimmedLine.startsWith("level:")) {
                currentListContext = null;
                inMovedFilesBlock = false;
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
                inMovedFilesBlock = false;
                scopeOrg = extractScalarValue(trimmedLine, "organization:");
            } else if (trimmedLine.startsWith("entity:")) {
                currentListContext = null;
                inMovedFilesBlock = false;
                scopeEntity = extractScalarValue(trimmedLine, "entity:");
            } else if (trimmedLine.startsWith("confidence:")) {
                currentListContext = null;
                inMovedFilesBlock = false;
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
                inMovedFilesBlock = false;
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
                inMovedFilesBlock = false;
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
                inMovedFilesBlock = false;
            }
        }

        // Flush last moved_files entry if pending
        if (inMovedFilesBlock && mfFile != null) {
            movedFiles.add(new ForwardingPointer(mfFile, mfMovedTo, mfMovedAt, mfMovedBy, mfReason));
        }

        return new DirectoryIdentity(
                List.copyOf(acceptsTypes),
                List.copyOf(acceptsFormats),
                List.copyOf(acceptsPatterns),
                scopeLevel, scopeOrg, scopeEntity,
                confidence, lastSynced, source, description,
                List.copyOf(rejectsTypes),
                List.copyOf(aliases),
                transientFlag,
                List.copyOf(movedFiles)
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
