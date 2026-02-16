package io.exoreaction.synthesis.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.Version;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Exports bundled Claude Code skills from the Synthesis JAR to the local filesystem.
 *
 * <p>Synthesis ships with 25 Claude Code skills that provide AI-assisted
 * commands for search, analysis, navigation, and workspace management.
 * This command extracts them to {@code ~/.claude/skills/} (or a custom
 * directory) so Claude Code can discover and use them.
 *
 * <p>Usage:
 * <pre>
 *   synthesis export-skills                          # Export to ~/.claude/skills/
 *   synthesis export-skills --list                   # List bundled skills
 *   synthesis export-skills --output /tmp/skills     # Export to custom directory
 *   synthesis export-skills --overwrite              # Overwrite existing files
 * </pre>
 *
 * @author Thor Henning Hetland / eXOReaction
 */
@Command(
        name = "export-skills",
        description = "Export bundled Claude Code skills to ~/.claude/skills/",
        mixinStandardHelpOptions = true
)
public class ExportSkillsCommand implements Callable<Integer> {

    private static final String SKILLS_RESOURCE_DIR = "claude-skills/";
    private static final String MANIFEST_RESOURCE = SKILLS_RESOURCE_DIR + "skills-manifest.json";

    @Option(
            names = {"--output"},
            description = "Output directory (default: ~/.claude/skills/)"
    )
    private Path outputDir;

    @Option(
            names = {"--overwrite"},
            description = "Overwrite existing skill files",
            defaultValue = "false"
    )
    private boolean overwrite;

    @Option(
            names = {"--list"},
            description = "List bundled skills without exporting",
            defaultValue = "false"
    )
    private boolean list;

    @Option(
            names = {"-v", "--verbose"},
            description = "Show detailed output",
            defaultValue = "false"
    )
    private boolean verbose;

    @Override
    public Integer call() {
        try {
            if (list) {
                return listSkills();
            }
            return exportSkills();
        } catch (Exception e) {
            AnsiOutput.printError("Export failed: " + e.getMessage());
            if (verbose) {
                e.printStackTrace(System.err);
            }
            return 1;
        }
    }

    /**
     * Lists all bundled skills by reading the manifest.
     */
    private int listSkills() throws IOException {
        JsonNode manifest = loadManifest();
        if (manifest == null) {
            AnsiOutput.printError("Skills manifest not found in JAR resources.");
            return 1;
        }

        String manifestVersion = manifest.has("version") ? manifest.get("version").asText() : "unknown";
        JsonNode skillsArray = manifest.get("skills");

        AnsiOutput.printHeader("Synthesis Bundled Skills (v" + manifestVersion + ")");

        // Group by category
        System.out.printf("  %-30s %-15s %-15s %s%n",
                AnsiOutput.bold("Skill"), AnsiOutput.bold("Command"), AnsiOutput.bold("Category"),
                AnsiOutput.bold("Description"));
        System.out.println("  " + "-".repeat(90));

        int count = 0;
        if (skillsArray != null && skillsArray.isArray()) {
            for (JsonNode skill : skillsArray) {
                String name = skill.get("name").asText();
                String command = skill.has("command") ? skill.get("command").asText() : "-";
                String category = skill.has("category") ? skill.get("category").asText() : "-";
                String description = skill.has("description") ? skill.get("description").asText() : "";

                System.out.printf("  %-30s %-15s %-15s %s%n",
                        AnsiOutput.cyan(name), command, AnsiOutput.dim(category), description);
                count++;
            }
        }

        System.out.println();
        System.out.printf("  %s %d skills bundled%n", AnsiOutput.bold("Total:"), count);
        System.out.println();
        System.out.println("  " + AnsiOutput.dim("Export with: ") + AnsiOutput.cyan("synthesis export-skills"));
        System.out.println();

        return 0;
    }

    /**
     * Exports all bundled skills to the output directory.
     */
    private int exportSkills() throws IOException {
        Path destDir = resolveOutputDir();
        JsonNode manifest = loadManifest();

        if (manifest == null) {
            AnsiOutput.printError("Skills manifest not found in JAR resources.");
            return 1;
        }

        JsonNode skillsArray = manifest.get("skills");
        if (skillsArray == null || !skillsArray.isArray() || skillsArray.isEmpty()) {
            AnsiOutput.printError("No skills found in manifest.");
            return 1;
        }

        // Ensure destination directory exists
        Files.createDirectories(destDir);

        AnsiOutput.printHeader("Synthesis Skills Export");

        int copied = 0;
        int skipped = 0;
        int errors = 0;
        List<String> copiedNames = new ArrayList<>();
        List<String> skippedNames = new ArrayList<>();
        List<String> errorNames = new ArrayList<>();

        for (JsonNode skill : skillsArray) {
            String fileName = skill.get("file").asText();
            String skillName = skill.get("name").asText();
            Path destFile = destDir.resolve(fileName);

            try {
                if (Files.exists(destFile) && !overwrite) {
                    skipped++;
                    skippedNames.add(skillName);
                    if (verbose) {
                        System.out.println("  " + AnsiOutput.dim("  skip  ") + fileName
                                + AnsiOutput.dim(" (already exists)"));
                    }
                    continue;
                }

                // Read from JAR resources
                String resourcePath = SKILLS_RESOURCE_DIR + fileName;
                try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                    if (is == null) {
                        errors++;
                        errorNames.add(skillName);
                        if (verbose) {
                            System.out.println("  " + AnsiOutput.error("  fail  ") + fileName
                                    + AnsiOutput.dim(" (not found in JAR)"));
                        }
                        continue;
                    }

                    Files.copy(is, destFile, StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                    copiedNames.add(skillName);
                    if (verbose) {
                        System.out.println("  " + AnsiOutput.success("  copy  ") + fileName);
                    }
                }
            } catch (IOException e) {
                errors++;
                errorNames.add(skillName);
                if (verbose) {
                    System.out.println("  " + AnsiOutput.error("  fail  ") + fileName + ": " + e.getMessage());
                }
            }
        }

        // Summary
        System.out.println("  " + AnsiOutput.success("\u2713") + " Exported skills to " +
                AnsiOutput.bold(destDir.toString()));
        System.out.println();
        System.out.println("  " + AnsiOutput.bold("Summary:"));
        System.out.printf("    Copied:  %d skills%n", copied);
        if (skipped > 0) {
            System.out.printf("    Skipped: %d skills " + AnsiOutput.dim("(already exist, use --overwrite)") + "%n", skipped);
        } else {
            System.out.printf("    Skipped: %d skills%n", skipped);
        }
        if (errors > 0) {
            System.out.printf("    Errors:  %s%n", AnsiOutput.error(errors + " skills"));
        } else {
            System.out.printf("    Errors:  %d skills%n", errors);
        }

        // Next steps
        if (copied > 0) {
            System.out.println();
            System.out.println("  " + AnsiOutput.bold("Next steps:"));
            System.out.println("    1. Restart Claude Code to load new skills");
            System.out.println("    2. Try: " + AnsiOutput.cyan("\"ask synthesis about authentication\""));
            System.out.println("    3. See: " + AnsiOutput.cyan("synthesis export-skills --list"));
        }

        System.out.println();
        return errors > 0 ? 1 : 0;
    }

    /**
     * Resolves the output directory, defaulting to ~/.claude/skills/.
     */
    private Path resolveOutputDir() {
        if (outputDir != null) {
            return outputDir.toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home"), ".claude", "skills");
    }

    /**
     * Loads the skills manifest from JAR resources.
     */
    private JsonNode loadManifest() throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(MANIFEST_RESOURCE)) {
            if (is == null) {
                return null;
            }
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readTree(is);
        }
    }
}
