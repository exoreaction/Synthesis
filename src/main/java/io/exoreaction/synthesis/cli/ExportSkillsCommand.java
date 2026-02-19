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

@Command(
        name = "export-skills",
        description = "Export bundled Claude Code skills to ~/.claude/skills/",
        mixinStandardHelpOptions = true
)
public class ExportSkillsCommand implements Callable<Integer> {

    private static final String SKILLS_RESOURCE_DIR = "claude-skills/";
    private static final String MANIFEST_RESOURCE = SKILLS_RESOURCE_DIR + "skills-manifest.json";

    @Option(names = {"--output"}, description = "Output directory (default: ~/.claude/skills/)")
    private Path outputDir;

    @Option(names = {"--overwrite"}, description = "Overwrite existing skill files", defaultValue = "false")
    private boolean overwrite;

    @Option(names = {"--list"}, description = "List bundled skills without exporting", defaultValue = "false")
    private boolean list;

    @Option(names = {"--tier"}, description = "Export only skills at or below this tier (1=core, 2=core+commands, 3=all)", defaultValue = "3")
    private int maxTier;

    @Option(names = {"-v", "--verbose"}, description = "Show detailed output", defaultValue = "false")
    private boolean verbose;

    @Override
    public Integer call() {
        try {
            if (list) { return listSkills(); }
            return exportSkills();
        } catch (Exception e) {
            AnsiOutput.printError("Export failed: " + e.getMessage());
            if (verbose) { e.printStackTrace(System.err); }
            return 1;
        }
    }

    private int listSkills() throws IOException {
        JsonNode manifest = loadManifest();
        if (manifest == null) { AnsiOutput.printError("Skills manifest not found in JAR resources."); return 1; }

        String ver = manifest.has("version") ? manifest.get("version").asText() : "unknown";
        JsonNode skills = manifest.get("skills");
        AnsiOutput.printHeader("Synthesis Bundled Skills (v" + ver + ")");

        System.out.printf("  %-30s %-6s %-15s %-15s %s%n",
                AnsiOutput.bold("Skill"), AnsiOutput.bold("Tier"), AnsiOutput.bold("Command"),
                AnsiOutput.bold("Category"), AnsiOutput.bold("Description"));
        System.out.println("  " + "-".repeat(95));

        int count = 0;
        int[] tierCounts = new int[4];
        if (skills != null && skills.isArray()) {
            for (JsonNode skill : skills) {
                String name = skill.get("name").asText();
                int tier = skill.has("tier") ? skill.get("tier").asInt(3) : 3;
                String command = skill.has("command") ? skill.get("command").asText() : "-";
                String category = skill.has("category") ? skill.get("category").asText() : "-";
                String desc = skill.has("description") ? skill.get("description").asText() : "";
                String tierLabel = switch (tier) {
                    case 1 -> AnsiOutput.green("T1");
                    case 2 -> AnsiOutput.yellow("T2");
                    case 3 -> AnsiOutput.dim("T3");
                    default -> AnsiOutput.dim("T?");
                };
                System.out.printf("  %-30s %-6s %-15s %-15s %s%n",
                        AnsiOutput.cyan(name), tierLabel, command, AnsiOutput.dim(category), desc);
                count++;
                if (tier >= 1 && tier <= 3) tierCounts[tier]++;
            }
        }

        System.out.println();
        System.out.printf("  %s %d skills bundled  (%s %d core, %s %d command, %s %d reference)%n",
                AnsiOutput.bold("Total:"), count,
                AnsiOutput.green("T1:"), tierCounts[1],
                AnsiOutput.yellow("T2:"), tierCounts[2],
                AnsiOutput.dim("T3:"), tierCounts[3]);
        System.out.println();
        System.out.println("  " + AnsiOutput.dim("Export with: ") + AnsiOutput.cyan("synthesis export-skills"));
        System.out.println("  " + AnsiOutput.dim("Core only:  ") + AnsiOutput.cyan("synthesis export-skills --tier 1"));
        System.out.println();
        return 0;
    }

    private int exportSkills() throws IOException {
        Path destDir = resolveOutputDir();
        JsonNode manifest = loadManifest();
        if (manifest == null) { AnsiOutput.printError("Skills manifest not found in JAR resources."); return 1; }

        JsonNode skillsArray = manifest.get("skills");
        if (skillsArray == null || !skillsArray.isArray() || skillsArray.isEmpty()) {
            AnsiOutput.printError("No skills found in manifest."); return 1;
        }

        Files.createDirectories(destDir);
        AnsiOutput.printHeader("Synthesis Skills Export" + (maxTier < 3 ? " (tier " + maxTier + " and below)" : ""));

        int copied = 0, skipped = 0, filtered = 0, errors = 0;
        List<String> copiedNames = new ArrayList<>(), skippedNames = new ArrayList<>(), errorNames = new ArrayList<>();

        for (JsonNode skill : skillsArray) {
            String fileName = skill.get("file").asText();
            String skillName = skill.get("name").asText();
            int skillTier = skill.has("tier") ? skill.get("tier").asInt(3) : 3;
            Path destFile = destDir.resolve(fileName);

            if (skillTier > maxTier) {
                filtered++;
                if (verbose) System.out.println("  " + AnsiOutput.dim("  filter") + " " + fileName + AnsiOutput.dim(" (tier " + skillTier + " > max " + maxTier + ")"));
                continue;
            }

            try {
                if (Files.exists(destFile) && !overwrite) {
                    skipped++; skippedNames.add(skillName);
                    if (verbose) System.out.println("  " + AnsiOutput.dim("  skip  ") + fileName + AnsiOutput.dim(" (already exists)"));
                    continue;
                }
                String resourcePath = SKILLS_RESOURCE_DIR + fileName;
                try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                    if (is == null) {
                        errors++; errorNames.add(skillName);
                        if (verbose) System.out.println("  " + AnsiOutput.error("  fail  ") + fileName + AnsiOutput.dim(" (not found in JAR)"));
                        continue;
                    }
                    Files.copy(is, destFile, StandardCopyOption.REPLACE_EXISTING);
                    copied++; copiedNames.add(skillName);
                    if (verbose) System.out.println("  " + AnsiOutput.success("  copy  ") + fileName);
                }
            } catch (IOException e) {
                errors++; errorNames.add(skillName);
                if (verbose) System.out.println("  " + AnsiOutput.error("  fail  ") + fileName + ": " + e.getMessage());
            }
        }

        System.out.println("  " + AnsiOutput.success("\u2713") + " Exported skills to " + AnsiOutput.bold(destDir.toString()));
        System.out.println();
        System.out.println("  " + AnsiOutput.bold("Summary:"));
        System.out.printf("    Copied:   %d skills%n", copied);
        if (skipped > 0) System.out.printf("    Skipped:  %d skills " + AnsiOutput.dim("(already exist, use --overwrite)") + "%n", skipped);
        else System.out.printf("    Skipped:  %d skills%n", skipped);
        if (filtered > 0) System.out.printf("    Filtered: %d skills " + AnsiOutput.dim("(above tier " + maxTier + ")") + "%n", filtered);
        if (errors > 0) System.out.printf("    Errors:   %s%n", AnsiOutput.error(errors + " skills"));
        else System.out.printf("    Errors:   %d skills%n", errors);

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

    private Path resolveOutputDir() {
        if (outputDir != null) return outputDir.toAbsolutePath().normalize();
        return Paths.get(System.getProperty("user.home"), ".claude", "skills");
    }

    private JsonNode loadManifest() throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(MANIFEST_RESOURCE)) {
            if (is == null) return null;
            return new ObjectMapper().readTree(is);
        }
    }
}
