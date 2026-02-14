package io.exoreaction.synthesis.skills;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Installs generated Claude Code skills to the global skills directory.
 *
 * <p>Skills are generated in {@code .synthesis/skills/} (workspace-scoped)
 * and can be installed to {@code ~/.claude/skills/} for global availability.
 *
 * <p>Installation options:
 * <ul>
 *   <li><b>Copy</b>: Copies skill files with workspace prefix to avoid conflicts</li>
 *   <li><b>Symlink</b>: Creates a symlink from .claude/skills to .synthesis/skills</li>
 * </ul>
 */
public class SkillInstaller {

    private final Path skillsDir;
    private final Path globalSkillsDir;
    private final String workspacePrefix;

    /**
     * Creates a SkillInstaller.
     *
     * @param skillsDir        path to .synthesis/skills/
     * @param workspacePrefix  prefix for installed skills (e.g., "Documents")
     */
    public SkillInstaller(Path skillsDir, String workspacePrefix) {
        this(skillsDir, workspacePrefix, defaultGlobalSkillsDir());
    }

    /**
     * Creates a SkillInstaller with a custom global skills directory (for testing).
     *
     * @param skillsDir        path to .synthesis/skills/
     * @param workspacePrefix  prefix for installed skills
     * @param globalSkillsDir  path to global skills directory
     */
    public SkillInstaller(Path skillsDir, String workspacePrefix, Path globalSkillsDir) {
        this.skillsDir = skillsDir.toAbsolutePath().normalize();
        this.workspacePrefix = workspacePrefix;
        this.globalSkillsDir = globalSkillsDir.toAbsolutePath().normalize();
    }

    /**
     * Result of installation.
     *
     * @param installed map of source filename to destination filename
     * @param count     number of files installed
     * @param targetDir the target directory
     */
    public record InstallResult(
            Map<String, String> installed,
            int count,
            Path targetDir
    ) {}

    /**
     * Installs all skills from .synthesis/skills/ to the global directory.
     * Files are prefixed with the workspace name to avoid conflicts.
     *
     * @return installation result
     * @throws IOException if file operations fail
     */
    public InstallResult installAll() throws IOException {
        Files.createDirectories(globalSkillsDir);

        Map<String, String> installed = new LinkedHashMap<>();

        if (!Files.isDirectory(skillsDir)) {
            return new InstallResult(installed, 0, globalSkillsDir);
        }

        try (Stream<Path> files = Files.list(skillsDir)) {
            List<Path> skillFiles = files
                    .filter(f -> f.toString().endsWith(".yaml"))
                    .sorted()
                    .toList();

            for (Path skillFile : skillFiles) {
                String sourceName = skillFile.getFileName().toString();
                String destName = prefixedName(sourceName);

                Path destFile = globalSkillsDir.resolve(destName);
                Files.copy(skillFile, destFile, StandardCopyOption.REPLACE_EXISTING);

                installed.put(sourceName, destName);
            }
        }

        return new InstallResult(installed, installed.size(), globalSkillsDir);
    }

    /**
     * Installs a single skill file.
     *
     * @param skillFileName the filename (e.g., "workspace-context.yaml")
     * @return the destination path
     * @throws IOException if the file does not exist or copy fails
     */
    public Path install(String skillFileName) throws IOException {
        Files.createDirectories(globalSkillsDir);

        Path source = skillsDir.resolve(skillFileName);
        if (!Files.exists(source)) {
            throw new IOException("Skill file not found: " + source);
        }

        String destName = prefixedName(skillFileName);
        Path dest = globalSkillsDir.resolve(destName);
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);

        return dest;
    }

    /**
     * Uninstalls all skills for this workspace from the global directory.
     *
     * @return number of files removed
     * @throws IOException if file operations fail
     */
    public int uninstallAll() throws IOException {
        if (!Files.isDirectory(globalSkillsDir)) {
            return 0;
        }

        int removed = 0;
        String prefix = workspacePrefix + "-";

        try (Stream<Path> files = Files.list(globalSkillsDir)) {
            List<Path> toRemove = files
                    .filter(f -> f.getFileName().toString().startsWith(prefix))
                    .filter(f -> f.toString().endsWith(".yaml"))
                    .toList();

            for (Path file : toRemove) {
                Files.deleteIfExists(file);
                removed++;
            }
        }

        return removed;
    }

    /**
     * Lists installed skills for this workspace in the global directory.
     *
     * @return list of installed skill filenames
     * @throws IOException if directory listing fails
     */
    public List<String> listInstalled() throws IOException {
        if (!Files.isDirectory(globalSkillsDir)) {
            return List.of();
        }

        String prefix = workspacePrefix + "-";

        try (Stream<Path> files = Files.list(globalSkillsDir)) {
            return files
                    .filter(f -> f.getFileName().toString().startsWith(prefix))
                    .filter(f -> f.toString().endsWith(".yaml"))
                    .map(f -> f.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    /**
     * Returns the global skills directory path.
     */
    public Path getGlobalSkillsDir() {
        return globalSkillsDir;
    }

    /**
     * Creates a prefixed filename for global installation.
     * E.g., "workspace-context.yaml" -> "Documents-workspace-context.yaml"
     */
    String prefixedName(String filename) {
        // Organization-specific skills use org name as prefix instead
        if (filename.startsWith("organization-")) {
            // Extract org name: "organization-exoreaction.yaml" -> "eXOReaction-context.yaml"
            String orgPart = filename.substring("organization-".length());
            orgPart = orgPart.replace(".yaml", "");
            return orgPart + "-context.yaml";
        }
        return workspacePrefix + "-" + filename;
    }

    /**
     * Returns the default global skills directory (~/.claude/skills/).
     */
    static Path defaultGlobalSkillsDir() {
        return Path.of(System.getProperty("user.home"), ".claude", "skills");
    }
}
