package io.exoreaction.synthesis.org;

import java.time.Instant;
import java.util.List;

/**
 * Metadata identity for a directory, parsed from a directory-level {@code .synthesis.md} file.
 *
 * <p>Describes what kinds of files a directory accepts, its organizational scope,
 * and how confident the inference is.
 *
 * @param acceptsTypes     content types accepted, e.g. {@code ["meeting-notes", "minutes"]}
 * @param acceptsFormats   file extensions accepted, e.g. {@code ["md", "pdf", "docx"]}
 * @param acceptsPatterns  glob patterns accepted, e.g. {@code ["*meeting*", "*minutes*"]}
 * @param scopeLevel       inferred or declared scope level
 * @param scopeOrganization inferred or declared organization name, may be {@code null}
 * @param scopeEntity      inferred or declared entity name, may be {@code null}
 * @param confidence       confidence score 0.0-1.0
 * @param lastSynced       last sync timestamp, may be {@code null}
 * @param source           provenance description, e.g. {@code "inferred from 12 existing files"}
 * @param description      human-readable markdown body (may be empty)
 * @param rejectsTypes     hard reject list, e.g. {@code ["video", "media", "audio"]}
 * @param aliases          alternate names for subject matching, e.g. {@code ["synthesis", "knowledge-infrastructure"]}
 * @param transient_       soft landing zone flag; if true, files can live here temporarily
 * @param movedFiles       forwarding pointers for files that have been migrated elsewhere
 */
public record DirectoryIdentity(
        List<String> acceptsTypes,
        List<String> acceptsFormats,
        List<String> acceptsPatterns,
        ScopeLevel scopeLevel,
        String scopeOrganization,
        String scopeEntity,
        double confidence,
        Instant lastSynced,
        String source,
        String description,
        List<String> rejectsTypes,
        List<String> aliases,
        boolean transient_,
        List<ForwardingPointer> movedFiles
) {

    /**
     * Canonical constructor with all 14 fields.
     * Ensures list fields are never null.
     */
    public DirectoryIdentity {
        acceptsTypes = acceptsTypes != null ? acceptsTypes : List.of();
        acceptsFormats = acceptsFormats != null ? acceptsFormats : List.of();
        acceptsPatterns = acceptsPatterns != null ? acceptsPatterns : List.of();
        rejectsTypes = rejectsTypes != null ? rejectsTypes : List.of();
        aliases = aliases != null ? aliases : List.of();
        movedFiles = movedFiles != null ? movedFiles : List.of();
    }

    /**
     * Backward-compatible constructor with the original 10 fields.
     * New fields default to empty/false.
     */
    public DirectoryIdentity(
            List<String> acceptsTypes,
            List<String> acceptsFormats,
            List<String> acceptsPatterns,
            ScopeLevel scopeLevel,
            String scopeOrganization,
            String scopeEntity,
            double confidence,
            Instant lastSynced,
            String source,
            String description
    ) {
        this(acceptsTypes, acceptsFormats, acceptsPatterns,
                scopeLevel, scopeOrganization, scopeEntity,
                confidence, lastSynced, source, description,
                List.of(), List.of(), false, List.of());
    }

    /**
     * Returns a DirectoryIdentity with all lists empty, {@link ScopeLevel#WORKSPACE},
     * nulls, 0.0 confidence, null lastSynced, and empty strings.
     */
    public static DirectoryIdentity empty() {
        return new DirectoryIdentity(
                List.of(),
                List.of(),
                List.of(),
                ScopeLevel.WORKSPACE,
                null,
                null,
                0.0,
                null,
                "",
                "",
                List.of(),
                List.of(),
                false,
                List.of()
        );
    }
}
