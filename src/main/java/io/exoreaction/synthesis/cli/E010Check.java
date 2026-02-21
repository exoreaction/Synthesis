package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.org.DirectoryIdentity;
import io.exoreaction.synthesis.org.DirectoryIdentityParser;
import io.exoreaction.synthesis.org.DirectoryIdentityRouter;
import io.exoreaction.synthesis.util.MediaTypes;

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
 *   <li><b>WARNING</b>: file in a transient directory with a routing match >= 0.25</li>
 *   <li><b>INFO</b>: file in a transient directory with no suitable destination above threshold</li>
 * </ul>
 *
 * <p>As of P1-05, uses the unified {@link DirectoryIdentityRouter} instead of the
 * retired SubjectBasedRouter.
 *
 * <p><b>Threshold mapping (P1-05):</b> The old SubjectBasedRouter used 0.4 for E010
 * (pure token overlap * confidence). The DirectoryScorer provides richer scoring, so
 * the equivalent threshold is 0.25 (WEAK match).
 *
 * @since v1.9.9 (issue #200), unified routing in P1-05
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

    private final DirectoryIdentityParser parser = new DirectoryIdentityParser();

    /**
     * Runs the E010 check across the entire workspace.
     *
     * @param workspaceRoot the workspace root directory
     * @return list of findings, sorted by severity (ERROR first, then WARNING, then INFO)
     */
    public List<E010Finding> check(Path workspaceRoot) {
        List<E010Finding> findings = new ArrayList<>();

        // Create a unified router with skipTransient for finding permanent homes
        DirectoryIdentityRouter router = new DirectoryIdentityRouter(workspaceRoot, null);

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
                    findings.addAll(checkTransientDirectory(dir, identity, workspaceRoot, router));
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
     *
     * <p>Per-file WARNINGs are emitted for each file with a routing match >= 0.25.
     * Files with no routing match are batched into a single INFO per directory
     * to avoid noise when a transient directory accumulates many unrouted files.
     */
    private List<E010Finding> checkTransientDirectory(
            Path dir, DirectoryIdentity identity, Path workspaceRoot,
            DirectoryIdentityRouter router) {
        List<E010Finding> findings = new ArrayList<>();
        int unmatchedCount = 0;

        try (Stream<Path> files = Files.list(dir)) {
            List<Path> mediaFiles = files
                    .filter(Files::isRegularFile)
                    .filter(this::isMediaFile)
                    .toList();

            for (Path file : mediaFiles) {
                // Use unified router with skipTransient=true, threshold 0.25
                Optional<DirectoryIdentityRouter.RouteResult> match =
                        router.route(file, 0.25, true);

                if (match.isPresent()) {
                    // WARNING: we found a better home — emit one finding per file
                    findings.add(new E010Finding(
                            file, dir, E010Level.WARNING,
                            Optional.of(match.get().directory()),
                            match.get().score(),
                            "Media file in transient directory has a better home: "
                                    + workspaceRoot.relativize(match.get().directory())
                                    + " (score " + String.format("%.2f", match.get().score()) + ")"
                    ));
                } else {
                    // Count unmatched files — emit ONE INFO per directory (not per file)
                    unmatchedCount++;
                }
            }
        } catch (IOException e) {
            // Skip this directory
        }

        // Emit a single batched INFO for all unmatched media files
        if (unmatchedCount > 0) {
            String dirName = workspaceRoot.relativize(dir).toString();
            findings.add(new E010Finding(
                    dir, dir, E010Level.INFO,
                    Optional.empty(),
                    0.0,
                    unmatchedCount + " media file(s) in transient directory '" + dirName
                            + "' — no routing match found yet"
            ));
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

                Set<String> fileTypes = MediaTypes.EXTENSION_REJECT_TYPE_MAP.getOrDefault(ext, Set.of());
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
        return MediaTypes.MEDIA_EXTENSIONS.contains(ext);
    }

    private static String extractExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
