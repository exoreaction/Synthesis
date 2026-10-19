package io.exoreaction.synthesis.confluence;

import io.exoreaction.synthesis.index.FileIndexer;
import io.exoreaction.synthesis.index.SearchIndex;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Polling watcher for Confluence workspace changes. Runs incremental syncs at
 * a configurable interval and indexes changed pages into the Lucene index.
 *
 * <p>Structurally identical to {@code NotionWatcher} — designed to run on a virtual
 * thread alongside the filesystem watch loop in
 * {@link io.exoreaction.synthesis.cli.WatchCommand}. The blocking {@link #start()}
 * method loops until {@link #close()} is called.
 *
 * <p>Error handling: IOExceptions during a poll cycle are logged as warnings
 * but do not terminate the watcher. The next poll cycle will be attempted
 * normally after the sleep interval.
 */
public class ConfluenceWatcher implements Closeable {

    private static final Logger LOG = Logger.getLogger(ConfluenceWatcher.class.getName());

    /** Sleep granularity: check the running flag every 10 seconds. */
    private static final long SLEEP_CHECK_INTERVAL_MS = 10_000;

    private final ConfluenceWorkspaceSource source;
    private final FileIndexer indexer;
    private final Path indexPath;
    private final int pollIntervalMinutes;
    private volatile boolean running = true;

    /**
     * Creates a new ConfluenceWatcher.
     *
     * @param source              the Confluence workspace source for incremental syncs
     * @param indexer             the file indexer for creating virtual documents
     * @param indexPath           path to the Lucene index directory
     * @param pollIntervalMinutes interval in minutes between sync polls
     */
    public ConfluenceWatcher(ConfluenceWorkspaceSource source, FileIndexer indexer,
                              Path indexPath, int pollIntervalMinutes) {
        this.source = source;
        this.indexer = indexer;
        this.indexPath = indexPath;
        this.pollIntervalMinutes = pollIntervalMinutes;
    }

    /**
     * Blocking call: runs incremental sync in a loop until {@link #close()} is called.
     *
     * <p>Each iteration performs an incremental sync via the Confluence source, then
     * indexes any changed pages into the search index. After each poll, sleeps for
     * the configured interval (checking the running flag every 10 seconds to allow
     * prompt shutdown).
     *
     * @throws InterruptedException if the thread is interrupted during sleep
     */
    public void start() throws InterruptedException {
        LOG.info("Confluence watcher started (poll interval: " + pollIntervalMinutes + " min)");

        while (running) {
            try {
                List<ConfluencePageMapper.ConfluencePage> changed = source.incrementalSync();

                if (!changed.isEmpty()) {
                    try (SearchIndex index = new SearchIndex(indexPath)) {
                        for (ConfluencePageMapper.ConfluencePage page : changed) {
                            var doc = indexer.indexVirtualFile(
                                    page.virtualPath(),
                                    page.markdownContent(),
                                    page.lastEditedTime().toEpochMilli(),
                                    "confluence");
                            index.addDocument(doc);
                        }
                        index.commit();
                    }
                    LOG.info("Confluence watcher: re-indexed " + changed.size() + " page(s)");
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Confluence poll failed (will retry next cycle): " + e.getMessage(), e);
            }

            // Sleep for pollIntervalMinutes, but check running flag every 10s
            long sleepEnd = System.currentTimeMillis() + pollIntervalMinutes * 60_000L;
            while (running && System.currentTimeMillis() < sleepEnd) {
                Thread.sleep(SLEEP_CHECK_INTERVAL_MS);
            }
        }

        LOG.info("Confluence watcher stopped");
    }

    @Override
    public void close() {
        running = false;
    }

    /**
     * Returns whether the watcher is still running.
     * Visible for testing.
     */
    boolean isRunning() {
        return running;
    }
}
