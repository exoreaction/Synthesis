package io.exoreaction.synthesis.org;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates initial {@link DirectoryWants} for directories during cold start.
 *
 * <p>Uses a four-tier signal precedence:
 * <ol>
 *   <li><b>README.md</b> in directory: extract topics/entities from headings
 *       and first paragraph (confidence 0.5-0.7)</li>
 *   <li><b>Directory name inference</b> via {@link DirectoryNameVocabulary}:
 *       generate topic keywords from the vocabulary (confidence 0.2-0.4)</li>
 *   <li><b>Parent directory centroid</b>: inherit scope topics with lower
 *       confidence (confidence 0.1-0.2)</li>
 *   <li><b>Explicit overrides</b>: confidence 1.0 (Phase 2-10, not yet implemented)</li>
 * </ol>
 *
 * <p>Higher tiers take priority. If a README is found, name inference is still
 * combined but at lower weight. The resulting wants include a {@code source}
 * field describing provenance.
 */
public class WantsBootstrapper {

    /** Pattern for Markdown headings. */
    private static final Pattern HEADING_PATTERN = Pattern.compile("^#{1,3}\\s+(.+)$");

    /** Pattern for capitalized multi-word names (potential entities). */
    private static final Pattern ENTITY_PATTERN =
            Pattern.compile("\\b([A-Z][a-zA-Z]+(?:\\s+[A-Z][a-zA-Z]+)+)\\b");

    /** Stop words for topic extraction. */
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "from", "this", "that",
            "are", "was", "were", "been", "have", "has", "had",
            "not", "but", "all", "any", "can", "did", "get",
            "will", "new", "use", "how", "its", "our", "you",
            "readme", "about", "overview", "introduction"
    );

    /** Common false-positive entities to filter out. */
    private static final Set<String> COMMON_PHRASES = Set.of(
            "The", "This", "That", "These", "Those",
            "New York", "Last Updated", "Table Of",
            "See Also", "For More", "How To"
    );

    /**
     * Bootstraps wants for a directory using the four-tier signal precedence.
     *
     * @param directory       the directory path
     * @param parentCentroid  the parent directory's centroid (may be null or empty)
     * @return bootstrapped wants, or {@link DirectoryWants#empty()} if no signals found
     */
    public DirectoryWants bootstrap(Path directory, DirectoryCentroid parentCentroid) {
        if (directory == null || !Files.isDirectory(directory)) {
            return DirectoryWants.empty();
        }

        List<String> topics = new ArrayList<>();
        List<String> entities = new ArrayList<>();
        List<String> sources = new ArrayList<>();

        // Tier 1: README.md
        Path readme = directory.resolve("README.md");
        if (!Files.exists(readme)) {
            readme = directory.resolve("readme.md");
        }
        if (Files.exists(readme)) {
            ReadmeSignals readmeSignals = extractFromReadme(readme);
            if (!readmeSignals.topics.isEmpty() || !readmeSignals.entities.isEmpty()) {
                topics.addAll(readmeSignals.topics);
                entities.addAll(readmeSignals.entities);
                sources.add("README.md");
            }
        }

        // Tier 2: Directory name inference
        String dirName = directory.getFileName().toString();
        List<String> nameTopics = inferTopicsFromName(dirName);
        List<String> nameEntities = inferEntitiesFromName(dirName);

        if (!nameTopics.isEmpty() || !nameEntities.isEmpty()) {
            // Add name-inferred topics that aren't already present
            for (String t : nameTopics) {
                if (!containsIgnoreCase(topics, t)) {
                    topics.add(t);
                }
            }
            for (String e : nameEntities) {
                if (!containsIgnoreCase(entities, e)) {
                    entities.add(e);
                }
            }
            sources.add("directory name");
        }

        // Tier 3: Parent directory centroid
        if (parentCentroid != null && !parentCentroid.isEmpty()) {
            // Inherit scope-level topics from parent (those not already present)
            for (String parentTopic : parentCentroid.topics()) {
                if (topics.size() < 5 && !containsIgnoreCase(topics, parentTopic)) {
                    topics.add(parentTopic);
                }
            }
            if (!parentCentroid.topics().isEmpty()) {
                sources.add("parent centroid");
            }
        }

        if (topics.isEmpty() && entities.isEmpty()) {
            return DirectoryWants.empty();
        }

        // Deduplicate
        List<String> dedupedTopics = new ArrayList<>(new LinkedHashSet<>(topics));
        List<String> dedupedEntities = new ArrayList<>(new LinkedHashSet<>(entities));

        String source = "inferred from " + String.join(" + ", sources);

        return new DirectoryWants(
                List.copyOf(dedupedTopics),
                List.copyOf(dedupedEntities),
                List.of(), // alsoLookingFor (Phase 4)
                source,
                0.0 // satisfaction starts at 0
        );
    }

    /**
     * Extracts topics and entities from a README.md file.
     * Reads headings and the first paragraph (up to 20 lines).
     */
    ReadmeSignals extractFromReadme(Path readme) {
        List<String> topics = new ArrayList<>();
        Set<String> entitySet = new LinkedHashSet<>();

        try {
            List<String> lines = Files.readAllLines(readme);
            int maxLines = Math.min(lines.size(), 30);
            boolean foundFirstParagraph = false;

            for (int i = 0; i < maxLines; i++) {
                String line = lines.get(i).trim();

                // Extract heading topics
                Matcher headingMatcher = HEADING_PATTERN.matcher(line);
                if (headingMatcher.matches()) {
                    String heading = headingMatcher.group(1).trim();
                    // Tokenize heading into topic words
                    for (String token : heading.toLowerCase(Locale.ROOT).split("[-_\\s:]+")) {
                        if (token.length() >= 3 && !STOP_WORDS.contains(token)) {
                            topics.add(token);
                        }
                    }
                    foundFirstParagraph = false;
                    continue;
                }

                // First non-empty, non-heading line is the start of first paragraph
                if (!line.isEmpty() && !foundFirstParagraph) {
                    foundFirstParagraph = true;
                }

                // Extract entities from body text
                if (foundFirstParagraph && !line.isEmpty()) {
                    Matcher entityMatcher = ENTITY_PATTERN.matcher(line);
                    while (entityMatcher.find()) {
                        String entity = entityMatcher.group(1);
                        if (!COMMON_PHRASES.contains(entity) && entity.length() >= 4) {
                            entitySet.add(entity);
                        }
                    }
                }
            }
        } catch (IOException e) {
            // Return what we have
        }

        // Deduplicate and limit
        List<String> dedupedTopics = new ArrayList<>(new LinkedHashSet<>(topics));
        if (dedupedTopics.size() > 10) {
            dedupedTopics = dedupedTopics.subList(0, 10);
        }

        List<String> entityList = new ArrayList<>(entitySet);
        if (entityList.size() > 5) {
            entityList = entityList.subList(0, 5);
        }

        return new ReadmeSignals(List.copyOf(dedupedTopics), List.copyOf(entityList));
    }

    /**
     * Infers topic keywords from a directory name.
     * Splits on common separators, filters short tokens and stop words.
     */
    List<String> inferTopicsFromName(String dirName) {
        if (dirName == null || dirName.isBlank()) {
            return List.of();
        }

        List<String> topics = new ArrayList<>();

        // Split on common separators: hyphen, underscore, space, period
        String[] parts = dirName.toLowerCase(Locale.ROOT).split("[-_. ]+");
        for (String part : parts) {
            if (part.length() >= 3 && !STOP_WORDS.contains(part)) {
                topics.add(part);
            }
        }

        return List.copyOf(topics);
    }

    /**
     * Infers entity names from a directory name.
     * Looks for capitalized segments that could be company/person names.
     * E.g., "opportunity-GreenField" -> "GreenField"
     */
    List<String> inferEntitiesFromName(String dirName) {
        if (dirName == null || dirName.isBlank()) {
            return List.of();
        }

        List<String> entities = new ArrayList<>();

        // Split on separators but preserve case
        String[] parts = dirName.split("[-_ .]+");
        for (String part : parts) {
            // If the part starts with uppercase and is not a common word, treat as entity candidate
            if (part.length() >= 3 && Character.isUpperCase(part.charAt(0))
                    && !STOP_WORDS.contains(part.toLowerCase(Locale.ROOT))) {
                // Check if it's a proper name (has mixed case like "GreenField" or is all caps)
                boolean hasLowercase = part.chars().anyMatch(Character::isLowerCase);
                if (hasLowercase) {
                    entities.add(part);
                }
            }
        }

        return List.copyOf(entities);
    }

    private static boolean containsIgnoreCase(List<String> list, String item) {
        String lower = item.toLowerCase(Locale.ROOT);
        return list.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).equals(lower));
    }

    /** Signals extracted from a README.md file. */
    record ReadmeSignals(List<String> topics, List<String> entities) {}
}
