package io.exoreaction.synthesis.ai;

import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generates README.md files for directories using Claude AI.
 *
 * <p>Collects directory contents and file previews, sends them to Claude
 * with the README generation prompt, and writes the result.
 *
 * <p>Designed to be called during scan (with --with-readme flag) or
 * independently for specific directories.
 */
public class ReadmeGenerator {

    private final AiClient client;
    private final int maxTokens;

    public ReadmeGenerator(AiClient client, int maxTokens) {
        this.client = client;
        this.maxTokens = maxTokens;
    }

    /**
     * Generates a README.md for the given directory.
     *
     * @param directory     the directory to document
     * @param workspaceRoot the workspace root for relative paths
     * @param force         if true, overwrite existing README.md
     * @return true if README was generated, false if skipped
     */
    public boolean generate(Path directory, Path workspaceRoot, boolean force) throws IOException {
        Path readmePath = directory.resolve("README.md");

        // Skip if README already exists (unless forced)
        if (Files.exists(readmePath) && !force) {
            return false;
        }

        // Skip if directory is empty or contains only hidden files
        try (Stream<Path> entries = Files.list(directory)) {
            boolean hasVisibleFiles = entries.anyMatch(p -> !p.getFileName().toString().startsWith("."));
            if (!hasVisibleFiles) {
                return false;
            }
        }

        String relativePath = workspaceRoot.relativize(directory).toString();
        String contents = listDirectoryContents(directory);
        String previews = collectFilePreviews(directory);

        String prompt = PromptTemplates.README_GENERATION.formatted(relativePath, contents, previews);

        try {
            String readme = client.generate(prompt, maxTokens);

            // Check for SKIP response
            if (readme.trim().startsWith("SKIP:")) {
                return false;
            }

            Files.writeString(readmePath, readme);
            AnsiOutput.printSuccess("Generated README: " + relativePath + "/README.md");
            return true;
        } catch (Exception e) {
            AnsiOutput.printWarning("Failed to generate README for " + relativePath + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Lists the contents of a directory in a format suitable for the prompt.
     */
    private String listDirectoryContents(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .map(p -> {
                        String name = p.getFileName().toString();
                        if (Files.isDirectory(p)) {
                            return "  [DIR]  " + name + "/";
                        } else {
                            try {
                                long size = Files.size(p);
                                return "  [FILE] " + name + " (" + FileUtils.formatSize(size) + ")";
                            } catch (IOException e) {
                                return "  [FILE] " + name;
                            }
                        }
                    })
                    .sorted()
                    .collect(Collectors.joining("\n"));
        }
    }

    /**
     * Collects content previews from key files in the directory.
     * Reads the first 500 bytes of up to 5 text files.
     */
    private String collectFilePreviews(Path directory) throws IOException {
        StringBuilder previews = new StringBuilder();

        try (Stream<Path> entries = Files.list(directory)) {
            entries
                    .filter(Files::isRegularFile)
                    .filter(p -> !FileUtils.isBinaryFile(p))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .limit(5)
                    .forEach(p -> {
                        try {
                            String preview = FileUtils.readPreview(p, 500);
                            if (!preview.isBlank()) {
                                previews.append("\n--- ").append(p.getFileName()).append(" ---\n");
                                previews.append(preview);
                                if (preview.length() >= 500) {
                                    previews.append("\n[... truncated]");
                                }
                                previews.append("\n");
                            }
                        } catch (IOException e) {
                            // Skip unreadable files
                        }
                    });
        }

        return previews.length() > 0 ? previews.toString() : "(no text files to preview)";
    }
}
