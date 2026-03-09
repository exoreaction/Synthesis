package io.exoreaction.synthesis.skills;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Matches Claude Code skill files against a query using keyword scoring.
 *
 * <p>Scans a directory of {@code *.yaml} skill files and scores each against
 * the query using:
 * <ul>
 *   <li>Trigger phrase exact match → 5 pts each</li>
 *   <li>Name / description keyword overlap → 2 pts per match</li>
 *   <li>Instructions keyword overlap → 1 pt per match</li>
 * </ul>
 *
 * <p>No Lucene dependency — skills directories are small (~200 files) and
 * an in-memory linear scan is fast enough.
 */
public class SkillMatcher {

    /** A single skill match result. */
    public record SkillMatch(
            String skillName,
            Path filePath,
            double score,
            List<String> matchedTerms,
            String firstLine
    ) {}

    /**
     * Scans {@code skillsDir} and returns up to {@code topN} skills ranked
     * by relevance to {@code query}.
     *
     * @param skillsDir directory containing {@code *.yaml} skill files
     * @param query     natural-language task description
     * @param topN      maximum number of results to return
     * @return ranked list of matches, highest score first; empty list if dir absent
     */
    public static List<SkillMatch> match(Path skillsDir, String query, int topN) {
        if (skillsDir == null || !Files.isDirectory(skillsDir)) {
            return List.of();
        }

        Set<String> queryTerms = tokenise(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        List<SkillMatch> results = new ArrayList<>();

        try (Stream<Path> paths = Files.list(skillsDir)) {
            List<Path> yamlFiles = paths
                    .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .sorted()
                    .collect(Collectors.toList());

            for (Path file : yamlFiles) {
                SkillMatch m = scoreFile(file, queryTerms);
                if (m != null && m.score() > 0) {
                    results.add(m);
                }
            }
        } catch (IOException e) {
            // Best-effort: return whatever we collected
        }

        return results.stream()
                .sorted(Comparator.comparingDouble(SkillMatch::score).reversed())
                .limit(topN)
                .collect(Collectors.toList());
    }

    /**
     * Scores a pre-built list of skill file paths against {@code query}.
     * Use this when you want to avoid re-scanning the directory (e.g. to exclude
     * files created during the current run).
     *
     * @param skillFiles pre-collected list of {@code *.yaml} skill file paths
     * @param query      natural-language task description
     * @param topN       maximum number of results to return
     * @return ranked list of matches, highest score first; empty list if input is empty
     */
    public static List<SkillMatch> match(Collection<Path> skillFiles, String query, int topN) {
        if (skillFiles == null || skillFiles.isEmpty()) {
            return List.of();
        }
        Set<String> queryTerms = tokenise(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        List<SkillMatch> results = new ArrayList<>();
        for (Path file : skillFiles) {
            SkillMatch m = scoreFile(file, queryTerms);
            if (m != null && m.score() > 0) {
                results.add(m);
            }
        }
        return results.stream()
                .sorted(Comparator.comparingDouble(SkillMatch::score).reversed())
                .limit(topN)
                .collect(Collectors.toList());
    }

    /**
     * Lists all skills in {@code skillsDir} with name and first line of description.
     *
     * @param skillsDir directory containing {@code *.yaml} skill files
     * @return all skill matches with score 0 (unranked), empty list if dir absent
     */
    public static List<SkillMatch> list(Path skillsDir) {
        if (skillsDir == null || !Files.isDirectory(skillsDir)) {
            return List.of();
        }

        List<SkillMatch> results = new ArrayList<>();

        try (Stream<Path> paths = Files.list(skillsDir)) {
            paths.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .sorted()
                    .forEach(file -> {
                        SkillMatch m = parseSkillMeta(file);
                        if (m != null) results.add(m);
                    });
        } catch (IOException e) {
            // Best-effort
        }

        return results;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static SkillMatch scoreFile(Path file, Set<String> queryTerms) {
        Map<String, Object> data = parseYaml(file);
        if (data == null) return null;

        String name = str(data, "name");
        if (name == null || name.isBlank()) {
            name = file.getFileName().toString().replaceAll("\\.ya?ml$", "");
        }
        String description = str(data, "description");
        String instructions = str(data, "instructions");

        // trigger_phrases
        List<String> triggers = new ArrayList<>();
        Object tp = data.get("trigger_phrases");
        if (tp instanceof List<?> tpList) {
            for (Object item : tpList) {
                if (item instanceof String s) triggers.add(s.toLowerCase());
            }
        }

        double score = 0;
        Set<String> matched = new LinkedHashSet<>();

        // Trigger phrase match: 5 pts per matched term (ensuring triggers outrank description matches)
        for (String trigger : triggers) {
            Set<String> trigTerms = tokenise(trigger);
            if (!Collections.disjoint(trigTerms, queryTerms)) {
                long overlap = trigTerms.stream().filter(queryTerms::contains).count();
                double fraction = trigTerms.isEmpty() ? 0 : (double) overlap / trigTerms.size();
                if (fraction >= 0.5) {
                    score += 5 * overlap;
                    matched.addAll(trigTerms.stream().filter(queryTerms::contains).collect(Collectors.toList()));
                }
            }
        }

        // Name + description: 2 pts per matched term
        Set<String> nameTerms = tokenise(name + " " + (description != null ? description : ""));
        for (String term : queryTerms) {
            if (nameTerms.contains(term)) {
                score += 2;
                matched.add(term);
            }
        }

        // Instructions: 1 pt per matched term
        if (instructions != null && !instructions.isBlank()) {
            Set<String> instrTerms = tokenise(instructions);
            for (String term : queryTerms) {
                if (instrTerms.contains(term) && !matched.contains(term)) {
                    score += 1;
                    matched.add(term);
                }
            }
        }

        if (score == 0) return null;

        String firstLine = description != null ? description : name;
        return new SkillMatch(name, file, score, List.copyOf(matched), firstLine);
    }

    @SuppressWarnings("unchecked")
    private static SkillMatch parseSkillMeta(Path file) {
        Map<String, Object> data = parseYaml(file);
        if (data == null) return null;

        String name = str(data, "name");
        if (name == null || name.isBlank()) {
            name = file.getFileName().toString().replaceAll("\\.ya?ml$", "");
        }
        String description = str(data, "description");
        String firstLine = description != null ? description : name;

        return new SkillMatch(name, file, 0, List.of(), firstLine);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYaml(Path file) {
        try (InputStream is = Files.newInputStream(file)) {
            Yaml yaml = new Yaml();
            Object parsed = yaml.load(is);
            if (parsed instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
        } catch (Exception ignored) {
            // Malformed YAML or IO error — skip file
        }
        return null;
    }

    private static String str(Map<String, Object> data, String key) {
        Object v = data.get(key);
        return v instanceof String s ? s : null;
    }

    /** Splits text into lowercase tokens, filtering short stop words. */
    static Set<String> tokenise(String text) {
        if (text == null || text.isBlank()) return Set.of();
        Set<String> tokens = new HashSet<>();
        for (String word : text.toLowerCase().split("[^a-z0-9]+")) {
            if (word.length() >= 3 && !STOP_WORDS.contains(word)) {
                tokens.add(word);
            }
        }
        return tokens;
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "from", "this", "that", "are", "was",
            "all", "can", "use", "used", "how", "what", "when", "where", "which",
            "not", "but", "you", "your", "its", "has", "have", "will", "each"
    );
}
