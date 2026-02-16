package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.analyzer.AnalyzerRegistry;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.core.SubWorkspaceResolver;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.FileIndexer;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.org.OrganizationRegistry;
import io.exoreaction.synthesis.skills.SkillGenerator;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Watch mode command. Monitors the workspace for file changes and
 * automatically maintains the search index in real-time.
 *
 * <p>Uses Java's {@link WatchService} to efficiently detect file system events.
 * Runs as a foreground process by default, or as a background daemon with {@code --daemon}.
 *
 * <p>Usage:
 * <pre>
 *   synthesis watch                # Start watching (foreground)
 *   synthesis watch --verbose      # Show all events
 *   synthesis watch --debounce 500 # Custom debounce (ms)
 *   synthesis watch --daemon       # Start as background daemon
 *   synthesis watch --stop         # Stop running daemon
 *   synthesis watch --status       # Show daemon status
 * </pre>
 *
 * <p>Daemon mode creates a PID file at {@code .synthesis/daemon.pid} in the workspace.
 * Only one daemon can run per workspace. The PID file is cleaned up on graceful
 * shutdown or detected as stale on next start.
 */
@Command(
        name = "watch",
        description = "Watch workspace for changes and auto-update index",
        mixinStandardHelpOptions = true
)
public class WatchCommand implements Callable<Integer> {

    /** Name of the PID file inside the .synthesis directory. */
    static final String PID_FILE_NAME = "daemon.pid";

    @ParentCommand
    private SynthesisApp parent;

    @Option(
            names = {"-v", "--verbose"},
            description = "Show all file change events",
            defaultValue = "false"
    )
    private boolean verbose;

    @Option(
            names = {"--debounce"},
            description = "Debounce interval in milliseconds (default: 300)",
            defaultValue = "300"
    )
    private int debounceMs;

    @Option(
            names = {"--learn"},
            description = "Regenerate Claude Code skills when organizational data changes",
            defaultValue = "false"
    )
    private boolean learn;

    @Option(
            names = {"--daemon"},
            description = "Run as background daemon process",
            defaultValue = "false"
    )
    private boolean daemon;

    @Option(
            names = {"--stop"},
            description = "Stop a running daemon for this workspace",
            defaultValue = "false"
    )
    private boolean stopDaemon;

    @Option(
            names = {"--status"},
            description = "Show daemon status for this workspace",
            defaultValue = "false"
    )
    private boolean showStatus;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger eventCount = new AtomicInteger(0);
    private final AtomicInteger indexedCount = new AtomicInteger(0);

    // Visible for testing
    boolean isRunning() {
        return running.get();
    }

    void stop() {
        running.set(false);
    }

    int getEventCount() {
        return eventCount.get();
    }

    int getIndexedCount() {
        return indexedCount.get();
    }

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();

            // Validate workspace
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            Path pidFile = workspaceRoot.resolve(WorkspaceManager.SYNTHESIS_DIR).resolve(PID_FILE_NAME);

            // Handle --status: show daemon status and exit
            if (showStatus) {
                return handleStatus(pidFile);
            }

            // Handle --stop: stop running daemon and exit
            if (stopDaemon) {
                return handleStop(pidFile);
            }

            // Handle --daemon: launch as background process
            if (daemon) {
                return handleDaemonStart(workspaceRoot, pidFile);
            }

            // Normal foreground watch mode
            return runForeground(workspaceRoot, workspace, pidFile);
        } catch (Exception e) {
            AnsiOutput.printError("Watch failed: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Runs the watcher in normal foreground mode.
     */
    private Integer runForeground(Path workspaceRoot, WorkspaceManager workspace,
                                   Path pidFile) throws Exception {
        SynthesisConfig config = ConfigLoader.load(workspaceRoot);

        // Check if a daemon is already running for this workspace
        Optional<Long> existingPid = readPid(pidFile);
        if (existingPid.isPresent() && isProcessAlive(existingPid.get())) {
            AnsiOutput.printError("A watcher daemon is already running for this workspace (PID: "
                    + existingPid.get() + ").");
            AnsiOutput.printInfo("Stop it with: synthesis watch --stop");
            return 1;
        }

        System.out.println();
        AnsiOutput.printInfo("Watching workspace: " + config.getWorkspace().getName());
        AnsiOutput.printInfo("Root: " + workspaceRoot);
        if (learn) {
            AnsiOutput.printInfo("Learning mode enabled - will regenerate skills on org changes");
        }
        AnsiOutput.printInfo("Press Ctrl+C to stop.");
        System.out.println();

        // Write PID file (even in foreground mode, prevents duplicate watchers)
        writePid(pidFile);

        // Register shutdown hook for graceful termination
        Thread shutdownHook = new Thread(() -> {
            running.set(false);
            cleanupPidFile(pidFile);
            System.out.println();
            AnsiOutput.printInfo("Shutting down watcher...");
        });
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        // Start watching
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            registerDirectories(workspaceRoot, watchService, config.getScan());

            watchLoop(watchService, workspace, config, workspaceRoot);
        } finally {
            cleanupPidFile(pidFile);
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                // JVM is already shutting down, ignore
            }
        }

        System.out.println();
        AnsiOutput.printSuccess("Watcher stopped. Processed " + eventCount.get() +
                " events, indexed " + indexedCount.get() + " files.");
        System.out.println();

        return 0;
    }

    /**
     * Handles --daemon: spawns the watcher as a background process.
     *
     * <p>Uses ProcessBuilder to re-launch Synthesis with the same arguments
     * but without --daemon, redirecting output to a log file. The spawned
     * process writes its own PID file.
     */
    private Integer handleDaemonStart(Path workspaceRoot, Path pidFile) throws IOException {
        // Check if already running
        Optional<Long> existingPid = readPid(pidFile);
        if (existingPid.isPresent() && isProcessAlive(existingPid.get())) {
            AnsiOutput.printError("Daemon already running (PID: " + existingPid.get() + ").");
            AnsiOutput.printInfo("Stop it with: synthesis watch --stop");
            return 1;
        }

        // Clean up stale PID file
        cleanupPidFile(pidFile);

        // Build the command to re-launch ourselves without --daemon
        Path synthesisHome = Path.of(System.getProperty("user.home"), ".synthesis");
        Path jarPath = synthesisHome.resolve("lib/current.jar");

        if (!Files.exists(jarPath)) {
            AnsiOutput.printError("Cannot find Synthesis JAR at: " + jarPath);
            return 1;
        }

        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        command.add("-jar");
        command.add(jarPath.toAbsolutePath().toString());
        command.add("-d");
        command.add(workspaceRoot.toAbsolutePath().toString());
        command.add("watch");
        if (verbose) command.add("--verbose");
        if (learn) command.add("--learn");
        command.add("--debounce");
        command.add(String.valueOf(debounceMs));

        // Log file for daemon output
        Path logFile = pidFile.getParent().resolve("daemon.log");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
        pb.redirectErrorStream(true);

        // Inherit environment (including SYNTHESIS_EDITION)
        Process process = pb.start();

        // Write the PID of the spawned process
        long pid = process.pid();
        Files.writeString(pidFile, String.valueOf(pid), StandardCharsets.UTF_8);

        System.out.println();
        AnsiOutput.printSuccess("Daemon started (PID: " + pid + ")");
        AnsiOutput.printInfo("Log: " + logFile);
        AnsiOutput.printInfo("Stop: synthesis watch --stop");
        AnsiOutput.printInfo("Status: synthesis watch --status");
        System.out.println();

        return 0;
    }

    /**
     * Handles --stop: sends SIGTERM to the daemon process.
     */
    private Integer handleStop(Path pidFile) {
        Optional<Long> pid = readPid(pidFile);
        if (pid.isEmpty()) {
            AnsiOutput.printInfo("No daemon running for this workspace.");
            return 0;
        }

        if (!isProcessAlive(pid.get())) {
            AnsiOutput.printInfo("Daemon PID " + pid.get() + " is no longer running (stale PID file).");
            cleanupPidFile(pidFile);
            return 0;
        }

        // Send graceful shutdown signal
        Optional<ProcessHandle> handle = ProcessHandle.of(pid.get());
        if (handle.isPresent()) {
            boolean destroyed = handle.get().destroy();
            if (destroyed) {
                AnsiOutput.printSuccess("Daemon stopped (PID: " + pid.get() + ").");
                cleanupPidFile(pidFile);
                return 0;
            } else {
                AnsiOutput.printError("Failed to stop daemon (PID: " + pid.get() + ").");
                AnsiOutput.printInfo("Try: kill " + pid.get());
                return 1;
            }
        } else {
            AnsiOutput.printInfo("Process " + pid.get() + " not found (already stopped).");
            cleanupPidFile(pidFile);
            return 0;
        }
    }

    /**
     * Handles --status: shows whether a daemon is running.
     */
    private Integer handleStatus(Path pidFile) {
        Optional<Long> pid = readPid(pidFile);

        System.out.println();
        if (pid.isEmpty()) {
            AnsiOutput.printInfo("No daemon configured for this workspace.");
        } else if (isProcessAlive(pid.get())) {
            AnsiOutput.printSuccess("Daemon running (PID: " + pid.get() + ")");
            Path logFile = pidFile.getParent().resolve("daemon.log");
            if (Files.exists(logFile)) {
                AnsiOutput.printInfo("Log: " + logFile);
            }
        } else {
            AnsiOutput.printInfo("Daemon PID " + pid.get() + " is no longer running (stale).");
            cleanupPidFile(pidFile);
        }
        System.out.println();

        return 0;
    }

    // ---- PID File Management ----

    /**
     * Writes the current process PID to the PID file.
     */
    void writePid(Path pidFile) throws IOException {
        long pid = ProcessHandle.current().pid();
        Files.createDirectories(pidFile.getParent());
        Files.writeString(pidFile, String.valueOf(pid), StandardCharsets.UTF_8);
    }

    /**
     * Reads the PID from the PID file.
     *
     * @return the PID, or empty if the file does not exist or is unreadable
     */
    static Optional<Long> readPid(Path pidFile) {
        try {
            if (!Files.exists(pidFile)) return Optional.empty();
            String content = Files.readString(pidFile, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) return Optional.empty();
            return Optional.of(Long.parseLong(content));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Checks if a process with the given PID is still alive.
     */
    static boolean isProcessAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /**
     * Removes the PID file if it exists. Never throws.
     */
    static void cleanupPidFile(Path pidFile) {
        try {
            Files.deleteIfExists(pidFile);
        } catch (IOException e) {
            // Best effort -- PID file cleanup should never fail the operation
        }
    }

    /**
     * Registers all directories in the workspace for watching.
     * Walks the directory tree and registers each directory with the WatchService.
     */
    void registerDirectories(Path root, WatchService watchService,
                             SynthesisConfig.ScanConfig scanConfig) throws IOException {
        Set<String> excludedDirs = Set.of(
                ".git", "node_modules", "target", "build", "__pycache__",
                ".venv", ".idea", ".vscode", ".synthesis", "dist", "out"
        );

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String dirName = dir.getFileName().toString();
                if (excludedDirs.contains(dirName) && !dir.equals(root)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Main watch loop. Polls for events and processes them.
     */
    void watchLoop(WatchService watchService, WorkspaceManager workspace,
                   SynthesisConfig config, Path workspaceRoot) throws IOException, InterruptedException {
        AnalyzerRegistry analyzers = new AnalyzerRegistry();
        FileIndexer fileIndexer = new FileIndexer();

        // Batch changes for debouncing
        Map<Path, WatchEvent.Kind<?>> pendingChanges = new LinkedHashMap<>();
        long lastEventTime = 0;

        while (running.get()) {
            WatchKey key = watchService.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);

            if (key != null) {
                Path dir = (Path) key.watchable();

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == OVERFLOW) continue;

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path changed = dir.resolve(pathEvent.context());

                    // Skip non-indexable files
                    if (Files.isDirectory(changed)) {
                        // If a new directory is created, register it
                        if (kind == ENTRY_CREATE) {
                            try {
                                changed.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
                                if (verbose) {
                                    logEvent("DIR+", changed, workspaceRoot);
                                }
                            } catch (IOException e) {
                                // May not be watchable
                            }
                        }
                        continue;
                    }

                    // Skip hidden files and non-matching extensions
                    if (changed.getFileName().toString().startsWith(".")) continue;

                    pendingChanges.put(changed, kind);
                    lastEventTime = System.currentTimeMillis();
                    eventCount.incrementAndGet();
                }

                key.reset();
            }

            // Process pending changes after debounce period
            if (!pendingChanges.isEmpty() &&
                    System.currentTimeMillis() - lastEventTime >= debounceMs) {
                processChanges(pendingChanges, workspace, config, workspaceRoot, analyzers, fileIndexer);
                pendingChanges.clear();
            }
        }
    }

    /**
     * Processes a batch of file changes, updating the index.
     */
    void processChanges(Map<Path, WatchEvent.Kind<?>> changes,
                        WorkspaceManager workspace, SynthesisConfig config,
                        Path workspaceRoot, AnalyzerRegistry analyzers,
                        FileIndexer fileIndexer) {
        try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
            // Initialize sub-workspace resolver for tagging
            SubWorkspaceResolver subWsResolver = new SubWorkspaceResolver(config);

            for (var entry : changes.entrySet()) {
                Path file = entry.getKey();
                WatchEvent.Kind<?> kind = entry.getValue();

                try {
                    if (kind == ENTRY_DELETE) {
                        String relativePath = workspaceRoot.relativize(file).toString();
                        index.deleteByRelativePath(relativePath);
                        indexedCount.incrementAndGet();
                        if (verbose) {
                            logEvent("DEL", file, workspaceRoot);
                        }
                    } else if (kind == ENTRY_CREATE || kind == ENTRY_MODIFY) {
                        if (Files.exists(file) && Files.isRegularFile(file)) {
                            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                            if (attrs.size() <= config.getScan().getMaxFileSizeBytes()) {
                                FileMetadata metadata = FileMetadata.of(
                                        file, workspaceRoot, attrs.size(),
                                        attrs.lastModifiedTime().toInstant(), null
                                );
                                AnalysisResult analysis = analyzers.analyze(metadata);

                                // Resolve sub-workspace for this file
                                String subWorkspace = subWsResolver.resolve(metadata.relativePath());

                                index.addDocument(fileIndexer.createDocument(
                                        metadata, analysis, null, null, null, subWorkspace));
                                indexedCount.incrementAndGet();
                                if (verbose) {
                                    logEvent(kind == ENTRY_CREATE ? "ADD" : "MOD", file, workspaceRoot);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    if (verbose) {
                        System.err.println("    " + AnsiOutput.dim(timestamp()) +
                                " " + AnsiOutput.red("ERR") + " " +
                                workspaceRoot.relativize(file) + ": " + e.getMessage());
                    }
                }
            }
            index.commit();

            // Summary line (non-verbose)
            if (!verbose) {
                System.out.println("  " + AnsiOutput.dim(timestamp()) +
                        " Indexed " + AnsiOutput.bold(String.valueOf(changes.size())) +
                        " change" + (changes.size() != 1 ? "s" : ""));
            }

            // Run architecture check on code file changes (debounced by batch)
            boolean hasCodeChanges = changes.keySet().stream()
                    .anyMatch(p -> FileUtils.classifyFile(p) == FileUtils.FileType.CODE);
            if (hasCodeChanges) {
                try {
                    io.exoreaction.synthesis.architecture.ArchitectureMonitor archMonitor =
                            new io.exoreaction.synthesis.architecture.ArchitectureMonitor(workspaceRoot);
                    for (Map.Entry<Path, WatchEvent.Kind<?>> entry : changes.entrySet()) {
                        if (entry.getValue() != ENTRY_DELETE &&
                                FileUtils.classifyFile(entry.getKey()) == FileUtils.FileType.CODE) {
                            var alerts = archMonitor.analyzeFile(entry.getKey(), index);
                            if (!alerts.isEmpty() && verbose) {
                                for (var alert : alerts) {
                                    System.out.println("    " + AnsiOutput.dim(timestamp()) +
                                            " " + AnsiOutput.yellow("ARCH") + " " + alert.toSummaryLine());
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    if (verbose) {
                        System.err.println("    " + AnsiOutput.dim(timestamp()) +
                                " " + AnsiOutput.red("ARCH-ERR") + " " + e.getMessage());
                    }
                }
            }

            // Regenerate skills if learn mode and organizational files changed
            if (learn) {
                boolean orgFilesChanged = changes.keySet().stream()
                        .anyMatch(p -> isOrganizationalFile(p, workspaceRoot));
                if (orgFilesChanged) {
                    regenerateSkills(workspaceRoot);
                }
            }
        } catch (IOException e) {
            AnsiOutput.printError("Index update failed: " + e.getMessage());
        }
    }

    /**
     * Checks if a file change is related to organizational data.
     * Triggers skill regeneration for changes to client/product directories,
     * pipeline files, README files in org directories, etc.
     */
    boolean isOrganizationalFile(Path file, Path root) {
        String relative = root.relativize(file).toString();
        String fileName = file.getFileName().toString();

        // Direct org data file
        if (relative.equals(".synthesis/organizations.json")) return true;

        // README.md in first-level directories (org directories)
        if (fileName.equals("README.md")) {
            Path parent = file.getParent();
            if (parent != null && parent.getParent() != null
                    && parent.getParent().equals(root)) {
                return true;
            }
        }

        // Pipeline and proof point files
        if (fileName.equals("PIPELINE-STATUS.md")) return true;
        if (fileName.equals("PROOF-POINTS.md")) return true;
        if (fileName.equals("CODEBASE-INDEX.md")) return true;

        // Client directory changes (*/clients/*)
        if (relative.contains("/clients/")) return true;

        // Product directory changes (*/products/*)
        if (relative.contains("/products/")) return true;

        return false;
    }

    /**
     * Regenerates Claude Code skills from current organizational data.
     */
    void regenerateSkills(Path workspaceRoot) {
        try {
            OrganizationRegistry registry = new OrganizationRegistry(workspaceRoot);
            registry.load();

            if (!registry.hasOrganizations()) return;

            SkillGenerator generator = new SkillGenerator(workspaceRoot, registry);
            SkillGenerator.GenerationResult result = generator.generateAll();

            System.out.println("  " + AnsiOutput.dim(timestamp()) +
                    " " + AnsiOutput.cyan("LEARN") +
                    " Regenerated " + result.totalFiles() + " skills");
        } catch (Exception e) {
            System.err.println("  " + AnsiOutput.dim(timestamp()) +
                    " " + AnsiOutput.red("ERR") +
                    " Skill regeneration failed: " + e.getMessage());
        }
    }

    private void logEvent(String type, Path file, Path workspaceRoot) {
        String color = switch (type) {
            case "ADD", "DIR+" -> AnsiOutput.green(type);
            case "MOD" -> AnsiOutput.yellow(type);
            case "DEL" -> AnsiOutput.red(type);
            default -> type;
        };
        System.out.println("  " + AnsiOutput.dim(timestamp()) + " " + color + " " +
                workspaceRoot.relativize(file));
    }

    private String timestamp() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }
}
