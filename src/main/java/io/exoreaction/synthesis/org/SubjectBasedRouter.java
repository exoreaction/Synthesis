package io.exoreaction.synthesis.org;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Matches a file to the best subject directory by comparing filename tokens
 * against directory name + aliases.
 *
 * <p>Token extraction splits the filename (without extension) on {@code -}, {@code _},
 * spaces, and camelCase boundaries, then lowercases all tokens.
 *
 * <p>Scoring: for each candidate directory with a {@code .synthesis.md}:
 * <ol>
 *   <li>Get directory tokens: tokenize directory name + all aliases</li>
 *   <li>Overlap score = (matching file tokens / total unique file tokens)</li>
 *   <li>Multiply by identity confidence to weight well-established directories higher</li>
 * </ol>
 *
 * @since v1.9.9 (issue #201)
 */
public class SubjectBasedRouter {

    /**
     * A routing decision with destination, score, and identity.
     */
    public record RoutingDecision(Path destination, double score, DirectoryIdentity identity) {}

    private final DirectoryIdentityParser parser = new DirectoryIdentityParser();

    /**
     * Finds the best matching directory for a file, above the given threshold.
     *
     * @param file          the file to route
     * @param workspaceRoot the workspace root directory
     * @param threshold     minimum score to accept (0.7 for sweep, 0.8 for rebalance/E010)
     * @return the best match above threshold, or empty if none qualifies
     */
    public Optional<RoutingDecision> findBestMatch(Path file, Path workspaceRoot, double threshold) {
        String fileName = file.getFileName().toString();
        Set<String> fileTokens = tokenizeFileName(fileName);

        if (fileTokens.isEmpty()) {
            return Optional.empty();
        }

        List<RoutingDecision> candidates = new ArrayList<>();

        // Scan workspace for directories with .synthesis.md that accept media types
        try (Stream<Path> walk = Files.walk(workspaceRoot, 6)) {
            List<Path> dirs = walk
                    .filter(Files::isDirectory)
                    .filter(dir -> !dir.equals(workspaceRoot))
                    .filter(dir -> !dir.getFileName().toString().startsWith("."))
                    .filter(dir -> Files.exists(dir.resolve(".synthesis.md")))
                    .toList();

            for (Path dir : dirs) {
                DirectoryIdentity identity = parser.parse(dir.resolve(".synthesis.md"));

                // Skip directories with empty identity
                if (identity.acceptsTypes().isEmpty() && identity.acceptsFormats().isEmpty()) {
                    continue;
                }

                // Skip transient directories as destinations (they are sources, not targets)
                if (identity.transient_()) {
                    continue;
                }

                // Build directory tokens from dir name + aliases
                Set<String> dirTokens = new HashSet<>();
                dirTokens.addAll(tokenize(dir.getFileName().toString()));
                for (String alias : identity.aliases()) {
                    dirTokens.addAll(tokenize(alias));
                }
                // Also tokenize parent path segments for deeper context
                Path relativePath = workspaceRoot.relativize(dir);
                for (int i = 0; i < relativePath.getNameCount(); i++) {
                    dirTokens.addAll(tokenize(relativePath.getName(i).toString()));
                }

                if (dirTokens.isEmpty()) {
                    continue;
                }

                // Compute overlap score
                long matches = fileTokens.stream().filter(dirTokens::contains).count();
                if (matches == 0) {
                    continue;
                }

                double overlapRatio = (double) matches / fileTokens.size();
                double score = overlapRatio * identity.confidence();

                candidates.add(new RoutingDecision(dir, score, identity));
            }
        } catch (IOException e) {
            return Optional.empty();
        }

        // Find best match above threshold
        return candidates.stream()
                .filter(c -> c.score() >= threshold)
                .max(Comparator.comparingDouble(RoutingDecision::score));
    }

    /**
     * Tokenizes a filename (with extension) by stripping the extension first,
     * then splitting on {@code -}, {@code _}, spaces, and camelCase boundaries.
     * Returns lowercase tokens with length >= 3.
     *
     * @param fileName the file name (e.g. "synthesis-taming-the-ai-torrent.mp4")
     * @return set of lowercase tokens (length >= 3)
     */
    static Set<String> tokenizeFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) return Set.of();
        // Strip extension
        int lastDot = fileName.lastIndexOf('.');
        String nameWithoutExt = lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
        return tokenize(nameWithoutExt);
    }

    /**
     * Tokenizes a string by splitting on {@code -}, {@code _}, spaces, and camelCase boundaries.
     * Returns lowercase tokens with length >= 3.
     *
     * @param input the string to tokenize
     * @return set of lowercase tokens (length >= 3)
     */
    static Set<String> tokenize(String input) {
        if (input == null || input.isEmpty()) return Set.of();

        // First split camelCase: insert separator before uppercase letters
        String withSeparators = input.replaceAll("([a-z])([A-Z])", "$1-$2");

        String[] parts = withSeparators.toLowerCase(Locale.ROOT).split("[-_. ]+");
        Set<String> tokens = new HashSet<>();
        for (String part : parts) {
            if (part.length() >= 3) {
                tokens.add(part);
            }
        }
        return tokens;
    }
}
