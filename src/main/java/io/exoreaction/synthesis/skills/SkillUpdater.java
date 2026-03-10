package io.exoreaction.synthesis.skills;

import io.exoreaction.synthesis.skills.SessionAnalyzer.ExtractedPattern;
import io.exoreaction.synthesis.skills.SkillMatcher.SkillMatch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Applies extracted patterns to the skill library by creating new skill YAML
 * files or updating existing ones.
 *
 * <p>Match logic: uses {@link SkillMatcher#match} to find existing skills that
 * overlap with a pattern. If a match scores >= 5.0, the existing skill is
 * updated; otherwise a new skill file is created.
 *
 * <p>Update logic operates on plain text (no SnakeYAML dependency for writes)
 * to keep the approach simple and avoid formatting side-effects.
 *
 * <p>Bloat control: a {@code maxNewSkills} cap prevents unbounded skill
 * creation from a single reflect run.
 */
public class SkillUpdater {

    // -----------------------------------------------------------------------
    // Public record types
    // -----------------------------------------------------------------------

    public enum ChangeType { CREATED, UPDATED, SKIPPED }

    /**
     * Describes a single change made (or not made) to the skill library.
     */
    public record SkillChange(
            ChangeType type,
            String skillName,
            Path filePath,
            String description,
            String previousVersion,
            String newVersion
    ) {}

    /**
     * Summary result of a reflect-apply run.
     */
    public record ReflectResult(
            List<SkillChange> changes,
            int sessionsAnalyzed,
            int patternsExtracted,
            int skillsCreated,
            int skillsUpdated,
            int skillsSkipped,
            Instant reflectedAt
    ) {}

    private static final double MATCH_THRESHOLD = 5.0;

    /** Pairs a skill match with a pattern that targets it. */
    private record PatternMatch(SkillMatch match, ExtractedPattern pattern) {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Applies extracted patterns to the skill library.
     *
     * <p>Patterns targeting the same existing skill are batched into a single
     * file write with one version bump, preventing version inflation (#307).
     *
     * @param patterns     patterns from {@link SessionAnalyzer#analyze}
     * @param skillsDir    directory containing skill YAML files
     * @param dryRun       if true, no files are written
     * @param maxNewSkills maximum number of new skills to create
     * @return a summary of all changes
     */
    public static ReflectResult apply(List<ExtractedPattern> patterns, Path skillsDir,
                                       boolean dryRun, int maxNewSkills) throws IOException {
        if (patterns == null || patterns.isEmpty()) {
            return new ReflectResult(List.of(), 0, 0, 0, 0, 0, Instant.now());
        }

        // Ensure skills directory exists
        if (!dryRun && !Files.isDirectory(skillsDir)) {
            Files.createDirectories(skillsDir);
        }

        // Snapshot existing skill files BEFORE the loop so that newly created
        // reflect-*.yaml files don't interfere with matching for subsequent patterns.
        List<Path> existingSkillFiles = snapshotSkillFiles(skillsDir);

        // Partition patterns: those matching an existing skill vs. new ones.
        // Group by skill file path to batch multiple patterns into one write (#307).
        Map<Path, List<PatternMatch>> bySkillFile = new LinkedHashMap<>();
        List<ExtractedPattern> newPatterns = new ArrayList<>();

        for (ExtractedPattern pattern : patterns) {
            Optional<SkillMatch> existing = findMatchingSkill(pattern, existingSkillFiles);
            if (existing.isPresent()) {
                bySkillFile
                        .computeIfAbsent(existing.get().filePath(), k -> new ArrayList<>())
                        .add(new PatternMatch(existing.get(), pattern));
            } else {
                newPatterns.add(pattern);
            }
        }

        List<SkillChange> changes = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int skipped = 0;

        // Apply batched updates — one write per skill file
        for (List<PatternMatch> group : bySkillFile.values()) {
            SkillChange change = updateSkillBatched(group, dryRun);
            changes.add(change);
            if (change.type() == ChangeType.UPDATED) updated++;
            else skipped++;
        }

        // Create new skills up to the cap
        for (ExtractedPattern pattern : newPatterns) {
            if (created >= maxNewSkills) {
                changes.add(new SkillChange(
                        ChangeType.SKIPPED,
                        pattern.suggestedName(),
                        null,
                        "Max new skills limit reached (" + maxNewSkills + ")",
                        null, null));
                skipped++;
            } else {
                SkillChange change = createSkill(pattern, skillsDir, dryRun);
                changes.add(change);
                if (change.type() == ChangeType.CREATED) created++;
                else skipped++;
            }
        }

        return new ReflectResult(changes, 0, patterns.size(), created, updated, skipped, Instant.now());
    }

    // -----------------------------------------------------------------------
    // Match logic
    // -----------------------------------------------------------------------

    /**
     * Snapshots the YAML files present in {@code skillsDir} at call time.
     * Returns an empty list if the directory does not exist.
     */
    static List<Path> snapshotSkillFiles(Path skillsDir) {
        if (skillsDir == null || !Files.isDirectory(skillsDir)) {
            return List.of();
        }
        try (var stream = Files.list(skillsDir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Finds an existing skill that matches the pattern using SkillMatcher.
     * Searches only within {@code existingSkillFiles} (a pre-snapshot) so that
     * newly created reflect-*.yaml files from the current run are not considered.
     * Returns the match if score >= threshold, empty otherwise.
     */
    static Optional<SkillMatch> findMatchingSkill(ExtractedPattern pattern,
                                                   List<Path> existingSkillFiles) {
        String query = pattern.description() + " " + String.join(" ", pattern.triggerPhrases());
        List<SkillMatch> matches = SkillMatcher.match(existingSkillFiles, query, 1);
        if (!matches.isEmpty() && matches.get(0).score() >= MATCH_THRESHOLD) {
            return Optional.of(matches.get(0));
        }
        return Optional.empty();
    }

    // -----------------------------------------------------------------------
    // Update logic
    // -----------------------------------------------------------------------

    /**
     * Updates an existing skill file with new trigger phrases and instructions
     * from the pattern. Bumps the patch version.
     */
    static SkillChange updateSkill(SkillMatch existing, ExtractedPattern pattern,
                                    boolean dryRun) throws IOException {
        Path filePath = existing.filePath();
        String content = Files.readString(filePath);
        String originalContent = content;

        // Find new trigger phrases not already present
        List<String> newPhrases = new ArrayList<>();
        String contentLower = content.toLowerCase();
        for (String phrase : pattern.triggerPhrases()) {
            if (!contentLower.contains(phrase.toLowerCase())) {
                newPhrases.add(phrase);
            }
        }

        // Find new instruction text
        List<String> newInstructions = new ArrayList<>();
        for (String instr : pattern.instructions()) {
            if (!contentLower.contains(instr.toLowerCase().substring(0, Math.min(30, instr.length())))) {
                newInstructions.add(instr);
            }
        }

        // Skip if nothing new to add
        if (newPhrases.isEmpty() && newInstructions.isEmpty()) {
            return new SkillChange(
                    ChangeType.SKIPPED,
                    existing.skillName(),
                    filePath,
                    "No new content to add",
                    null, null);
        }

        // Append new trigger phrases
        if (!newPhrases.isEmpty()) {
            int triggerIdx = content.indexOf("trigger_phrases:");
            if (triggerIdx >= 0) {
                // Find the end of the trigger_phrases block
                int insertAt = findBlockEnd(content, triggerIdx);
                StringBuilder insert = new StringBuilder();
                for (String phrase : newPhrases) {
                    insert.append("  - \"").append(escapeYaml(phrase)).append("\"\n");
                }
                content = content.substring(0, insertAt) + insert + content.substring(insertAt);
            }
        }

        // Append reflected instructions section
        if (!newInstructions.isEmpty()) {
            String reflectedSection = "\n  ## Reflected " + LocalDate.now() + "\n";
            for (String instr : newInstructions) {
                reflectedSection += "  " + instr + "\n";
            }

            int instrIdx = content.indexOf("instructions:");
            if (instrIdx >= 0) {
                // Append at end of file (instructions is typically last)
                content = content.stripTrailing() + "\n" + reflectedSection;
            }
        }

        // Bump patch version
        String previousVersion = null;
        String newVersion = null;
        Pattern versionPattern = Pattern.compile("version:\\s*([0-9]+)\\.([0-9]+)\\.([0-9]+)");
        Matcher vMatcher = versionPattern.matcher(content);
        if (vMatcher.find()) {
            previousVersion = vMatcher.group(1) + "." + vMatcher.group(2) + "." + vMatcher.group(3);
            int patch = Integer.parseInt(vMatcher.group(3));
            newVersion = vMatcher.group(1) + "." + vMatcher.group(2) + "." + (patch + 1);
            content = content.substring(0, vMatcher.start())
                    + "version: " + newVersion
                    + content.substring(vMatcher.end());
        }

        if (!dryRun) {
            atomicWrite(filePath, content);
        }

        return new SkillChange(
                ChangeType.UPDATED,
                existing.skillName(),
                filePath,
                "Added " + newPhrases.size() + " trigger phrases, "
                        + newInstructions.size() + " instructions",
                previousVersion, newVersion);
    }

    /**
     * Updates an existing skill file with content from multiple patterns in a single
     * write and a single version bump. Prevents version inflation when many session
     * patterns all match the same skill (#307).
     */
    static SkillChange updateSkillBatched(List<PatternMatch> group, boolean dryRun) throws IOException {
        if (group == null || group.isEmpty()) {
            return new SkillChange(ChangeType.SKIPPED, "unknown", null, "Empty group", null, null);
        }
        SkillMatch existing = group.get(0).match();
        Path filePath = existing.filePath();
        String content = Files.readString(filePath);
        String contentLower = content.toLowerCase();

        // Collect all new phrases and instructions across all patterns in the group
        List<String> allNewPhrases = new ArrayList<>();
        List<String> allNewInstructions = new ArrayList<>();
        for (PatternMatch pm : group) {
            for (String phrase : pm.pattern().triggerPhrases()) {
                if (!contentLower.contains(phrase.toLowerCase())
                        && !allNewPhrases.contains(phrase)) {
                    allNewPhrases.add(phrase);
                }
            }
            for (String instr : pm.pattern().instructions()) {
                String prefix = instr.toLowerCase().substring(0, Math.min(30, instr.length()));
                if (!contentLower.contains(prefix) && !allNewInstructions.contains(instr)) {
                    allNewInstructions.add(instr);
                }
            }
        }

        if (allNewPhrases.isEmpty() && allNewInstructions.isEmpty()) {
            return new SkillChange(ChangeType.SKIPPED, existing.skillName(), filePath,
                    "No new content to add", null, null);
        }

        // Append all new trigger phrases in one block
        if (!allNewPhrases.isEmpty()) {
            int triggerIdx = content.indexOf("trigger_phrases:");
            if (triggerIdx >= 0) {
                int insertAt = findBlockEnd(content, triggerIdx);
                StringBuilder insert = new StringBuilder();
                for (String phrase : allNewPhrases) {
                    insert.append("  - \"").append(escapeYaml(phrase)).append("\"\n");
                }
                content = content.substring(0, insertAt) + insert + content.substring(insertAt);
            }
        }

        // Append all new instructions in one reflected section
        if (!allNewInstructions.isEmpty()) {
            String reflectedSection = "\n  ## Reflected " + LocalDate.now() + "\n";
            for (String instr : allNewInstructions) {
                reflectedSection += "  " + instr + "\n";
            }
            if (content.contains("instructions:")) {
                content = content.stripTrailing() + "\n" + reflectedSection;
            }
        }

        // Single version bump for all patterns in this group
        String previousVersion = null;
        String newVersion = null;
        Pattern versionPattern = Pattern.compile("version:\\s*([0-9]+)\\.([0-9]+)\\.([0-9]+)");
        Matcher vMatcher = versionPattern.matcher(content);
        if (vMatcher.find()) {
            previousVersion = vMatcher.group(1) + "." + vMatcher.group(2) + "." + vMatcher.group(3);
            int patch = Integer.parseInt(vMatcher.group(3));
            newVersion = vMatcher.group(1) + "." + vMatcher.group(2) + "." + (patch + 1);
            content = content.substring(0, vMatcher.start())
                    + "version: " + newVersion
                    + content.substring(vMatcher.end());
        }

        if (!dryRun) {
            atomicWrite(filePath, content);
        }

        return new SkillChange(
                ChangeType.UPDATED,
                existing.skillName(),
                filePath,
                "Added " + allNewPhrases.size() + " trigger phrases, "
                        + allNewInstructions.size() + " instructions"
                        + (group.size() > 1 ? " (batched from " + group.size() + " patterns)" : ""),
                previousVersion, newVersion);
    }

    // -----------------------------------------------------------------------
    // Create logic
    // -----------------------------------------------------------------------

    /**
     * Creates a new skill YAML file from an extracted pattern.
     */
    static SkillChange createSkill(ExtractedPattern pattern, Path skillsDir,
                                    boolean dryRun) throws IOException {
        String filename = "reflect-" + pattern.suggestedName() + ".yaml";
        Path filePath = skillsDir.resolve(filename);

        StringBuilder yaml = new StringBuilder();
        yaml.append("name: ").append(pattern.suggestedName()).append("\n");
        yaml.append("version: 0.1.0\n");
        yaml.append("description: \"").append(escapeYaml(pattern.description())).append("\"\n");

        // Tags
        yaml.append("tags:\n");
        for (String tag : pattern.tags()) {
            yaml.append("  - \"").append(escapeYaml(tag)).append("\"\n");
        }

        // Trigger phrases
        yaml.append("trigger_phrases:\n");
        for (String phrase : pattern.triggerPhrases()) {
            yaml.append("  - \"").append(escapeYaml(phrase)).append("\"\n");
        }

        // Instructions
        yaml.append("instructions: |\n");
        yaml.append("  Auto-generated from session reflection on ").append(LocalDate.now()).append(".\n");
        yaml.append("\n");
        for (String instr : pattern.instructions()) {
            yaml.append("  ").append(instr).append("\n");
        }

        if (!dryRun) {
            atomicWrite(filePath, yaml.toString());
        }

        return new SkillChange(
                ChangeType.CREATED,
                pattern.suggestedName(),
                filePath,
                pattern.description(),
                null, "0.1.0");
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    /**
     * Finds the end of a YAML list block starting at the given offset.
     * Returns the offset after the last {@code "  - "} line.
     */
    private static int findBlockEnd(String content, int blockStart) {
        int pos = content.indexOf('\n', blockStart);
        if (pos < 0) return content.length();
        pos++; // skip the newline after "trigger_phrases:"

        while (pos < content.length()) {
            // Check if this line starts with "  - " (list item)
            if (content.startsWith("  - ", pos)) {
                // Skip to end of this line
                int eol = content.indexOf('\n', pos);
                if (eol < 0) return content.length();
                pos = eol + 1;
            } else {
                break;
            }
        }
        return pos;
    }

    private static String escapeYaml(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void atomicWrite(Path path, String content) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
