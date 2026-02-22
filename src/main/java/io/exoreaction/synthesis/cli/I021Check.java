package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.org.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * I021 health check: detects want conflicts -- multiple directories bidding for
 * the same type of content.
 *
 * <p>A want conflict occurs when two or more directories have overlapping wants
 * (similar topics or entities), indicating ambiguous organizational structure.
 * This can lead to files being routed inconsistently or split across directories
 * without clear ownership.
 *
 * <p>The check compares all directory wants/centroids pairwise and reports pairs
 * with significant topic or entity overlap. Virtual membership can resolve some
 * conflicts (one directory gets physical, others get virtual), but unresolved
 * conflicts indicate a need for structural review.
 *
 * @since v2.0 (P4-03)
 */
public class I021Check {

    /** Minimum topic Jaccard overlap to report a conflict. */
    static final double TOPIC_OVERLAP_THRESHOLD = 0.3;

    /** Minimum entity overlap to report a conflict. */
    static final double ENTITY_OVERLAP_THRESHOLD = 0.4;

    /**
     * An I021 finding: a want conflict between two directories.
     *
     * @param directoryA       first directory
     * @param directoryB       second directory
     * @param topicOverlap     Jaccard similarity of topic sets (0.0-1.0)
     * @param entityOverlap    Jaccard similarity of entity sets (0.0-1.0)
     * @param sharedTopics     topics present in both directories
     * @param sharedEntities   entities present in both directories
     * @param message          human-readable description
     */
    public record I021Finding(
            Path directoryA,
            Path directoryB,
            double topicOverlap,
            double entityOverlap,
            List<String> sharedTopics,
            List<String> sharedEntities,
            String message
    ) {}

    /**
     * Checks for want conflicts across the workspace.
     *
     * @param workspaceRoot the workspace root directory
     * @return list of I021 findings
     */
    public List<I021Finding> check(Path workspaceRoot) {
        List<I021Finding> findings = new ArrayList<>();
        DirectoryIdentityParser parser = new DirectoryIdentityParser();

        // Collect all directory profiles with wants or centroids
        List<DirectoryEntry> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(workspaceRoot)) {
            walk.filter(Files::isDirectory)
                    .filter(dir -> !dir.equals(workspaceRoot))
                    .filter(dir -> !dir.getFileName().toString().startsWith("."))
                    .forEach(dir -> {
                        Path synthesisFile = dir.resolve(".synthesis.md");
                        if (!Files.exists(synthesisFile)) return;

                        DirectoryProfile profile = parser.parseProfile(synthesisFile);
                        List<String> topics = extractTopics(profile);
                        List<String> entities = extractEntities(profile);

                        // Only include directories with meaningful wants or centroid
                        if (!topics.isEmpty() || !entities.isEmpty()) {
                            entries.add(new DirectoryEntry(dir, topics, entities));
                        }
                    });
        } catch (IOException e) {
            // Return whatever was collected
        }

        // Pairwise comparison
        for (int i = 0; i < entries.size(); i++) {
            for (int j = i + 1; j < entries.size(); j++) {
                DirectoryEntry a = entries.get(i);
                DirectoryEntry b = entries.get(j);

                // Skip if one is a subdirectory of the other (parent-child is normal)
                if (isParentChild(a.directory, b.directory)) continue;

                Set<String> aTopicsSet = toLowerSet(a.topics);
                Set<String> bTopicsSet = toLowerSet(b.topics);
                double topicOverlap = jaccard(aTopicsSet, bTopicsSet);

                Set<String> aEntitiesSet = toLowerSet(a.entities);
                Set<String> bEntitiesSet = toLowerSet(b.entities);
                double entityOverlap = jaccard(aEntitiesSet, bEntitiesSet);

                if (topicOverlap >= TOPIC_OVERLAP_THRESHOLD
                        || entityOverlap >= ENTITY_OVERLAP_THRESHOLD) {

                    List<String> sharedTopics = intersection(aTopicsSet, bTopicsSet);
                    List<String> sharedEntities = intersection(aEntitiesSet, bEntitiesSet);

                    String relA = workspaceRoot.relativize(a.directory).toString();
                    String relB = workspaceRoot.relativize(b.directory).toString();
                    String message = String.format(
                            "[I021] Want conflict between %s and %s — "
                                    + "topic overlap: %.2f, entity overlap: %.2f, "
                                    + "shared: %s",
                            relA, relB, topicOverlap, entityOverlap,
                            formatShared(sharedTopics, sharedEntities));

                    findings.add(new I021Finding(
                            a.directory, b.directory,
                            topicOverlap, entityOverlap,
                            sharedTopics, sharedEntities,
                            message));
                }
            }
        }

        return findings;
    }

    /**
     * Extracts effective topics from a profile (prefers centroid, falls back to wants).
     */
    static List<String> extractTopics(DirectoryProfile profile) {
        DirectoryCentroid centroid = profile.centroid();
        if (centroid != null && !centroid.isEmpty() && !centroid.topics().isEmpty()) {
            return centroid.topics();
        }
        DirectoryWants wants = profile.wants();
        if (wants != null && !wants.isEmpty()) {
            return wants.topics();
        }
        return List.of();
    }

    /**
     * Extracts effective entities from a profile (prefers centroid, falls back to wants).
     */
    static List<String> extractEntities(DirectoryProfile profile) {
        DirectoryCentroid centroid = profile.centroid();
        if (centroid != null && !centroid.isEmpty() && !centroid.entities().isEmpty()) {
            return centroid.entities();
        }
        DirectoryWants wants = profile.wants();
        if (wants != null && !wants.isEmpty()) {
            return wants.entities();
        }
        return List.of();
    }

    /**
     * Returns true if a is an ancestor of b or b is an ancestor of a.
     */
    static boolean isParentChild(Path a, Path b) {
        return a.startsWith(b) || b.startsWith(a);
    }

    /**
     * Computes Jaccard similarity between two sets.
     */
    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;

        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);

        Set<String> union = new HashSet<>(a);
        union.addAll(b);

        return (double) inter.size() / union.size();
    }

    /**
     * Computes intersection of two sets, returned as sorted list.
     */
    static List<String> intersection(Set<String> a, Set<String> b) {
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        List<String> result = new ArrayList<>(inter);
        Collections.sort(result);
        return result;
    }

    /**
     * Converts a list to a lowercase set.
     */
    static Set<String> toLowerSet(List<String> items) {
        if (items == null || items.isEmpty()) return Set.of();
        Set<String> result = new HashSet<>();
        for (String item : items) {
            result.add(item.toLowerCase());
        }
        return result;
    }

    private static String formatShared(List<String> topics, List<String> entities) {
        List<String> parts = new ArrayList<>();
        if (!topics.isEmpty()) {
            parts.add("topics=[" + String.join(", ", topics) + "]");
        }
        if (!entities.isEmpty()) {
            parts.add("entities=[" + String.join(", ", entities) + "]");
        }
        return parts.isEmpty() ? "(none)" : String.join(", ", parts);
    }

    private record DirectoryEntry(
            Path directory,
            List<String> topics,
            List<String> entities
    ) {}
}
