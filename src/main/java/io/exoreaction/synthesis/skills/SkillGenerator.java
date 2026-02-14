package io.exoreaction.synthesis.skills;

import io.exoreaction.synthesis.org.Organization;
import io.exoreaction.synthesis.org.OrganizationRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Generates Claude Code skill files from organizational data.
 *
 * <p>Reads discovered organizations from {@code .synthesis/organizations.json}
 * and generates YAML skill files in {@code .synthesis/skills/}. These skills
 * teach Claude Code about the workspace structure, organizational context,
 * client navigation, and more.
 *
 * <p>Generated skills:
 * <ul>
 *   <li>{@code workspace-context.yaml} - Overall workspace knowledge</li>
 *   <li>{@code organization-*.yaml} - One per organization</li>
 *   <li>{@code navigate-clients.yaml} - Client navigation shortcuts</li>
 *   <li>{@code pipeline-tracker.yaml} - Business pipeline awareness</li>
 *   <li>{@code proof-points.yaml} - Technical achievements</li>
 *   <li>{@code architecture-overview.yaml} - High-level architecture</li>
 *   <li>{@code tech-stack.yaml} - Technologies and frameworks</li>
 *   <li>{@code key-decisions.yaml} - Important decision records</li>
 * </ul>
 */
public class SkillGenerator {

    private final Path workspaceRoot;
    private final Path skillsDir;
    private final OrganizationRegistry registry;

    /**
     * Creates a SkillGenerator for the given workspace.
     *
     * @param workspaceRoot the workspace root directory
     * @param registry      the organization registry (must be loaded)
     */
    public SkillGenerator(Path workspaceRoot, OrganizationRegistry registry) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.skillsDir = this.workspaceRoot.resolve(".synthesis").resolve("skills");
        this.registry = registry;
    }

    /**
     * Result of skill generation.
     *
     * @param skills   map of filename to line count
     * @param totalFiles number of files generated
     * @param skillsDir  path to the skills directory
     */
    public record GenerationResult(
            Map<String, Integer> skills,
            int totalFiles,
            Path skillsDir
    ) {
        public int totalLines() {
            return skills.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /**
     * Generates all skill files.
     *
     * @return result with generated file details
     * @throws IOException if file writing fails
     */
    public GenerationResult generateAll() throws IOException {
        return generateAll(Instant.now());
    }

    /**
     * Generates all skill files with a specific timestamp.
     * Visible for testing.
     *
     * @param timestamp the generation timestamp
     * @return result with generated file details
     * @throws IOException if file writing fails
     */
    public GenerationResult generateAll(Instant timestamp) throws IOException {
        Files.createDirectories(skillsDir);

        Map<String, Integer> skills = new LinkedHashMap<>();
        List<Organization> orgs = registry.getOrganizations();

        if (orgs.isEmpty()) {
            return new GenerationResult(skills, 0, skillsDir);
        }

        // Derive workspace name from root directory
        String workspaceName = workspaceRoot.getFileName().toString();

        // 1. Workspace context
        skills.put("workspace-context.yaml",
                writeSkill("workspace-context.yaml",
                        SkillTemplate.workspaceContext(workspaceName, workspaceRoot,
                                orgs, timestamp)));

        // 2. Organization-specific skills (one per org)
        for (Organization org : orgs) {
            String filename = "organization-"
                    + SkillTemplate.sanitizeForFilename(org.getName()) + ".yaml";
            skills.put(filename,
                    writeSkill(filename,
                            SkillTemplate.organizationContext(org, timestamp)));
        }

        // 3. Navigate clients (only if there are clients)
        boolean hasClients = orgs.stream()
                .anyMatch(o -> !o.getClients().isEmpty());
        if (hasClients) {
            skills.put("navigate-clients.yaml",
                    writeSkill("navigate-clients.yaml",
                            SkillTemplate.navigateClients(orgs, timestamp)));
        }

        // 4. Pipeline tracker (only if there are active/opportunity/signed clients)
        boolean hasPipeline = orgs.stream()
                .anyMatch(o -> o.getClients().stream()
                        .anyMatch(c -> c.getStatus() != io.exoreaction.synthesis.org.ClientStatus.PAST));
        if (hasPipeline) {
            skills.put("pipeline-tracker.yaml",
                    writeSkill("pipeline-tracker.yaml",
                            SkillTemplate.pipelineTracker(orgs, timestamp)));
        }

        // 5. Proof points (only if there are products or codebases)
        boolean hasProof = orgs.stream()
                .anyMatch(o -> !o.getProducts().isEmpty() || !o.getCodebasePaths().isEmpty());
        if (hasProof) {
            skills.put("proof-points.yaml",
                    writeSkill("proof-points.yaml",
                            SkillTemplate.proofPoints(orgs, timestamp)));
        }

        // 6. Architecture overview (only if there are codebases or products)
        boolean hasArchitecture = orgs.stream()
                .anyMatch(o -> !o.getCodebasePaths().isEmpty() || !o.getProducts().isEmpty());
        if (hasArchitecture) {
            skills.put("architecture-overview.yaml",
                    writeSkill("architecture-overview.yaml",
                            SkillTemplate.architectureOverview(orgs, timestamp)));
        }

        // 7. Tech stack (always generate - detects from directory structure)
        skills.put("tech-stack.yaml",
                writeSkill("tech-stack.yaml",
                        SkillTemplate.techStack(orgs, workspaceRoot, timestamp)));

        // 8. Key decisions (always generate - guides users to decision records)
        skills.put("key-decisions.yaml",
                writeSkill("key-decisions.yaml",
                        SkillTemplate.keyDecisions(orgs, timestamp)));

        return new GenerationResult(skills, skills.size(), skillsDir);
    }

    /**
     * Generates only the workspace context skill.
     */
    public int generateWorkspaceContext(Instant timestamp) throws IOException {
        Files.createDirectories(skillsDir);
        String workspaceName = workspaceRoot.getFileName().toString();
        return writeSkill("workspace-context.yaml",
                SkillTemplate.workspaceContext(workspaceName, workspaceRoot,
                        registry.getOrganizations(), timestamp));
    }

    /**
     * Generates skills for a specific organization.
     */
    public int generateOrganizationSkill(Organization org, Instant timestamp) throws IOException {
        Files.createDirectories(skillsDir);
        String filename = "organization-"
                + SkillTemplate.sanitizeForFilename(org.getName()) + ".yaml";
        return writeSkill(filename,
                SkillTemplate.organizationContext(org, timestamp));
    }

    /**
     * Returns the skills directory path.
     */
    public Path getSkillsDir() {
        return skillsDir;
    }

    /**
     * Writes a skill file and returns the line count.
     */
    private int writeSkill(String filename, String content) throws IOException {
        Path skillFile = skillsDir.resolve(filename);
        Files.writeString(skillFile, content);
        return content.split("\n", -1).length;
    }
}
