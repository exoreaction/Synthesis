package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.org.DirectoryIdentity;
import io.exoreaction.synthesis.org.DirectoryIdentityParser;
import io.exoreaction.synthesis.org.SubjectBasedRouter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * E010 health check: detects media files in transient or hard-reject directories.
 *
 * <p>Three-level findings:
 * <ul>
 *   <li><b>ERROR</b>: file in a directory whose {@code rejectsTypes} matches the file's type</li>
 *   <li><b>WARNING</b>: file in a transient directory with a subject-based router match >= 0.8</li>
 *   <li><b>INFO</b>: file in a transient directory with no suitable destination above threshold</li>
 * </ul>
 *
 * @since v1.9.9 (issue #200)
 */
public class E010Check {

    /**
     * Severity levels for E010 findings.
     */
    public enum E010Level { INFO, WARNING, ERROR }

    /**
     * A single E010 finding.
     *
     * @param file                the file that triggered the finding
     * @param currentDirectory    the directory where the file currently lives
     * @param level               severity level
     * @param proposedDestination where the file should go (if applicable)
     * @param proposedScore       the routing score (0 if no destination)
     * @param message             human-readable description
     */
    public record E010Finding(
            Path file,
            Path currentDirectory,
            E010Level level,
            Optional<Path> proposedDestination,
            double proposedScore,
            String message
    ) {}

    private static final Set<String> MEDIA_EXTENSIONS = Set.of(
            "mp4", "mov", "avi", "mkv", "webm",
            "mp3", "wav", "flac", "ogg", "aac",
            "jpg", "jpeg", "png", "gif", "svg", "bmp"
    );

    /**
     * Maps file extensions to broad type categories for rejectsTypes checking.
     */
    private static final Map<String, Set<String>> EXTENSION_REJECT_TYPE_MAP = Map.ofEntries(
            Map.entry("mp4", Set.of("video", "media")),
            Map.entry("mov", Set.of("video", "media")),
            Map.entry("avi", Set.of("video", "media")),
            Map.entry("mkv", Set.of("video", "media")),
            Map.entry("webm", Set.of("video", "media")),
            Map.entry("mp3", Set.of("audio", "media")),
            Map.entry("wav", Set.of("audio", "media")),
            Map.entry("flac", Set.of("audio", "media")),
            Map.entry("ogg", Set.of("audio", "media")),
            Map.entry("aac", Set.of("audio", "media")),
            Map.entry("jpg", Set.of("image", "media")),
            Map.entry("jpeg", Set.of("image", "media")),
            Map.entry("png", Set.of("image", "media")),
            Map.entry("gif", Set.of("image", "media")),
            Map.entry("svg", Set.of("image", "media")),
            Map.entry("bmp", Set.of("image", "media")),
            Map.entry("pdf", Set.of("document")),
            Map.entry("docx", Set.of("document")),
            Map.entry("doc", Set.of("document")),
            Map.entry("md", Set.of("document"))
    );

    private final DirectoryIdentityParser parser = new DirectoryIdentityParser();
    private final SubjectBasedRouter router = new SubjectBasedRouter();

    /**
     * Runs the E010 check across the entire workspace.
     *
     * @param workspaceRoot the workspace root directory
     * @return list of findings, sorted by severity (ERROR first, then WARNING, then INFO)
     */
    public List<E010Finding> check(Path workspaceRoot) {
        List<E010Finding> findings = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(workspaceRoot, 6)) {
            List<Path> dirs = walk
                    .filter(Files::isDirectory)
                    .filter(dir -> !dir.equals(workspaceRoot))
                    .filter(dir -> !dir.getFileName().toString().startsWith("."))
                    .filter(dir -> Files.exists(dir.resolve(".synthesis.md")))
                    .toList();

            for (Path dir : dirs) {
                DirectoryIdentity identity = parser.parse(dir.resolve(".synthesis.md"));

                // Check 1: transient directories — look for media files
                if (identity.transient_()) {
                    findings.addAll(checkTransientDirectory(dir, identity, workspaceRoot));
                }

                // Check 2: directories with rejectsTypes — look for violations
                if (!identity.rejectsTypes().isEmpty()) {
                    findings.addAll(checkRejectsTypesViolations(dir, identity, workspaceRoot));
                }
            }
        } catch (IOException e) {
            // Best effort — return what we have
        }

        // Sort: ERROR first, then WARNING, then INFO
        findings.sort(Comparator.comparingInt(f -> switch (f.level()) {
            case ERROR -> 0;
            case WARNING -> 1;
            case INFO -> 2;
        }));

        return findings;
    }

    /**
     * Checks a transient directory for media files that could be routed elsewhere.
     */
    private List<E010Finding> checkTransientDirectory(
            Path dir, DirectoryIdentity identity, Path workspaceRoot) {
        List<E010Finding> findings = new ArrayList<>();

        try (Stream<Path> files = Files.list(dir)) {
            List<Path> mediaFiles = files
                    .filter(Files::isRegularFile)
                    .filter(this::isMediaFile)
                    .toList();

            for (Path file : mediaFiles) {
                Optional<SubjectBasedRouter.RoutingDecision> match =
                        router.findBestMatch(file, workspaceRoot, 0.4);

                if (match.isPresent() && match.get().score() >= 0.4) {
                    // WARNING: we found a better home
                    findings.add(new E010Finding(
                            file, dir, E010Level.WARNING,
                            Optional.of(match.get().destination()),
                            match.get().score(),
                            "Media file in transient directory has a better home: "
                                    + workspaceRoot.relativize(match.get().destination())
                                    + " (score " + String.format("%.2f", match.get().score()) + ")"
                    ));
                } else {
                    // INFO: no better home found yet
                    findings.add(new E010Finding(
                            file, dir, E010Level.INFO,
                            Optional.empty(),
                            0.0,
                            "Media file in transient directory — no better home found yet"
                    ));
                }
            }
        } catch (IOException e) {
            // Skip this directory
        }

        return findings;
    }

    /**
     * Checks a directory with rejectsTypes for files that violate the rejection rules.
     */
    private List<E010Finding> checkRejectsTypesViolations(
            Path dir, DirectoryIdentity identity, Path workspaceRoot) {
        List<E010Finding> findings = new ArrayList<>();

        try (Stream<Path> files = Files.list(dir)) {
            List<Path> allFiles = files
                    .filter(Files::isRegularFile)
                    .filter(f -> !f.getFileName().toString().endsWith(".synthesis.md"))
                    .toList();

            for (Path file : allFiles) {
                String ext = extractExtension(file.getFileName().toString());
                if (ext.isEmpty()) continue;

                Set<String> fileTypes = EXTENSION_REJECT_TYPE_MAP.getOrDefault(ext, Set.of());
                if (fileTypes.isEmpty()) continue;

                boolean rejected = identity.rejectsTypes().stream()
                        .anyMatch(rt -> fileTypes.contains(rt.toLowerCase(Locale.ROOT)));

                if (rejected) {
                    findings.add(new E010Finding(
                            file, dir, E010Level.ERROR,
                            Optional.empty(),
                            0.0,
                            "File type rejected by directory: "
                                    + file.getFileName() + " violates rejectsTypes="
                                    + identity.rejectsTypes()
                    ));
                }
            }
        } catch (IOException e) {
            // Skip this directory
        }

        return findings;
    }

    private boolean isMediaFile(Path file) {
        String ext = extractExtension(file.getFileName().toString());
        return MEDIA_EXTENSIONS.contains(ext);
    }

    private static String extractExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
