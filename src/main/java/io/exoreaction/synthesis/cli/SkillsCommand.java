package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.skills.SkillMatcher;
import io.exoreaction.synthesis.skills.SkillMatcher.SkillMatch;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI command for discovering and matching Claude Code skills.
 *
 * <p>Usage:
 * <pre>
 *   synthesis skills match "parse Gerber format"      # top-5 relevant skills
 *   synthesis skills match "query" --top 10           # more results
 *   synthesis skills match "query" --compact          # one skill name per line
 *   synthesis skills list                             # list all skills
 *   synthesis skills list --compact                   # names only
 * </pre>
 *
 * <p>Solves the skill discovery problem: agents must currently know which
 * skills to load manually. This command ranks skills by relevance so an
 * agent can discover the right ones from a task description.
 */
@Command(
        name = "skills",
        description = "Discover and match Claude Code skills by relevance",
        mixinStandardHelpOptions = true,
        subcommands = {
                SkillsCommand.MatchSubcommand.class,
                SkillsCommand.ListSubcommand.class
        }
)
public class SkillsCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Override
    public Integer call() {
        picocli.CommandLine.usage(this, System.out);
        return 0;
    }

    // -----------------------------------------------------------------------
    // Subcommand: match
    // -----------------------------------------------------------------------

    @Command(name = "match",
             description = "Find skills relevant to a task description (ranked by relevance)",
             mixinStandardHelpOptions = true)
    static class MatchSubcommand implements Callable<Integer> {

        @ParentCommand
        private SkillsCommand parent;

        @Parameters(index = "0", description = "Task description to match skills against")
        private String query;

        @Option(names = {"--top"}, description = "Number of results (default: 5)", defaultValue = "5")
        private int top;

        @Option(names = {"--compact"}, description = "Output skill names only (one per line)", defaultValue = "false")
        private boolean compact;

        @Option(names = {"--skills-dir"}, description = "Skills directory (default: ~/.claude/skills/)")
        private Path skillsDir;

        @Override
        public Integer call() {
            Path dir = resolveSkillsDir(skillsDir);

            if (!Files.isDirectory(dir)) {
                AnsiOutput.printError("Skills directory not found: " + dir);
                return 1;
            }

            warnIfSubdirectorySkillsIgnored(dir, compact);

            List<SkillMatch> matches = SkillMatcher.match(dir, query, top);

            if (matches.isEmpty()) {
                if (!compact) {
                    System.out.println("No matching skills found for: " + query);
                }
                return 0;
            }

            if (compact) {
                matches.forEach(m -> System.out.println(m.skillName()));
            } else {
                AnsiOutput.printHeader("Synthesis - Skill Match");
                System.out.printf("Query: %s%n", AnsiOutput.highlight(query));
                System.out.printf("Skills dir: %s%n%n", dir);
                System.out.printf("%-30s  %5s  %-30s  %s%n",
                        "Skill", "Score", "Matched Terms", "Description");
                System.out.println("-".repeat(100));
                for (SkillMatch m : matches) {
                    String terms = String.join(", ", m.matchedTerms());
                    if (terms.length() > 28) terms = terms.substring(0, 27) + "…";
                    String desc = m.firstLine();
                    if (desc.length() > 50) desc = desc.substring(0, 49) + "…";
                    System.out.printf("%-30s  %5.1f  %-30s  %s%n",
                            m.skillName(), m.score(), terms, desc);
                }
                System.out.printf("%n%s match(es) found.%n", matches.size());
                System.out.printf("Load with: /skill %s%n",
                        matches.isEmpty() ? "" : matches.get(0).skillName());
            }

            return 0;
        }
    }

    // -----------------------------------------------------------------------
    // Subcommand: list
    // -----------------------------------------------------------------------

    @Command(name = "list",
             description = "List all available Claude Code skills",
             mixinStandardHelpOptions = true)
    static class ListSubcommand implements Callable<Integer> {

        @ParentCommand
        private SkillsCommand parent;

        @Option(names = {"--compact"}, description = "Output skill names only (one per line)", defaultValue = "false")
        private boolean compact;

        @Option(names = {"--skills-dir"}, description = "Skills directory (default: ~/.claude/skills/)")
        private Path skillsDir;

        @Override
        public Integer call() {
            Path dir = resolveSkillsDir(skillsDir);

            if (!Files.isDirectory(dir)) {
                AnsiOutput.printError("Skills directory not found: " + dir);
                return 1;
            }

            warnIfSubdirectorySkillsIgnored(dir, compact);

            List<SkillMatch> skills = SkillMatcher.list(dir);

            if (skills.isEmpty()) {
                if (!compact) {
                    System.out.println("No skills found in: " + dir);
                }
                return 0;
            }

            if (compact) {
                skills.forEach(m -> System.out.println(m.skillName()));
            } else {
                AnsiOutput.printHeader("Synthesis - Skills List");
                System.out.printf("Skills dir: %s  (%d skills)%n%n", dir, skills.size());
                System.out.printf("%-35s  %s%n", "Skill", "Description");
                System.out.println("-".repeat(90));
                for (SkillMatch m : skills) {
                    String desc = m.firstLine();
                    if (desc.length() > 52) desc = desc.substring(0, 51) + "…";
                    System.out.printf("%-35s  %s%n", m.skillName(), desc);
                }
                System.out.printf("%n%d skill(s) total.%n", skills.size());
            }

            return 0;
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    static Path resolveSkillsDir(Path override) {
        if (override != null) return override.toAbsolutePath().normalize();
        return Path.of(System.getProperty("user.home"), ".claude", "skills");
    }

    static void warnIfSubdirectorySkillsIgnored(Path dir, boolean compact) {
        if (compact) return;
        int count = SkillMatcher.countSubdirectorySkills(dir);
        if (count > 0) {
            AnsiOutput.printWarning(count + " skill(s) found in subdirectory format (name/SKILL.md) in "
                    + dir + " -- these are not indexed. Migrate to flat YAML to enable discovery.");
        }
    }
}
