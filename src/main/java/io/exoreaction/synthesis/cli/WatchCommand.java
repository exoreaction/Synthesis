package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.analyzer.AnalyzerRegistry;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.FileIndexer;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
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
 * Runs as a foreground daemon and shuts down gracefully on Ctrl+C.
 *
 * <p>Usage:
 * <pre>
 *   synthesis watch                # Start watching
 *   synthesis watch --verbose      # Show all events
 *   synthesis watch --debounce 500 # Custom debounce (ms)
 * </pre>
 */
@Command(
        name = "watch",
        description = "Watch workspace for changes and auto-update index",
        mixinStandardHelpOptions = true
)
public class WatchCommand implements Callable<Integer> {

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

            SynthesisConfig config = ConfigLoader.load(workspaceRoot);

            System.out.println();
            AnsiOutput.printInfo("Watching workspace: " + config.getWorkspace().getName());
            AnsiOutput.printInfo("Root: " + workspaceRoot);
            AnsiOutput.printInfo("Press Ctrl+C to stop.");
            System.out.println();

            // Register shutdown hook for graceful termination
            Thread shutdownHook = new Thread(() -> {
                running.set(false);
                System.out.println();
                AnsiOutput.printInfo("Shutting down watcher...");
            });
            Runtime.getRuntime().addShutdownHook(shutdownHook);

            // Start watching
            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                registerDirectories(workspaceRoot, watchService, config.getScan());

                watchLoop(watchService, workspace, config, workspaceRoot);
            } finally {
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
        } catch (Exception e) {
            AnsiOutput.printError("Watch failed: " + e.getMessage());
            return 1;
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
                                index.addDocument(fileIndexer.createDocument(metadata, analysis));
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
        } catch (IOException e) {
            AnsiOutput.printError("Index update failed: " + e.getMessage());
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
