package io.exoreaction.synthesis.jira;

import io.exoreaction.synthesis.index.FileIndexer;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.jira.JiraIssueMapper.JiraIssue;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Polling watcher for Jira workspace changes. Runs incremental syncs at a
 * configurable interval and indexes changed issues into the Lucene index —
 * the Jira equivalent of {@code NotionWatcher}.
 *
 * <p>Designed to run on a virtual thread alongside the filesystem watch loop
 * in {@link io.exoreaction.synthesis.cli.WatchCommand}. The blocking
 * {@link #start()} method loops until {@link #close()} is called.
 *
 * <p>Error handling: IOExceptions during a poll cycle are logged as warnings
 * but do not terminate the watcher. The next poll cycle will be attempted
 * normally after the sleep interval.
 */
public class JiraWatcher implements Closeable {

    private static final Logger LOG = Logger.getLogger(JiraWatcher.class.getName());

    /** Sleep granularity: check the running flag every 10 seconds. */
    private static final long SLEEP_CHECK_INTERVAL_MS = 10_000;

    private final JiraWorkspaceSource source;
    private final FileIndexer indexer;
    private final Path indexPath;
    private final int pollIntervalMinutes;
    private volatile boolean running = true;

    public JiraWatcher(JiraWorkspaceSource source, FileIndexer indexer,
                        Path indexPath, int pollIntervalMinutes) {
        this.source = source;
        this.indexer = indexer;
        this.indexPath = indexPath;
        this.pollIntervalMinutes = pollIntervalMinutes;
    }

    /**
     * Blocking call: runs incremental sync in a loop until {@link #close()} is called.
     *
     * @throws InterruptedException if the thread is interrupted during sleep
     */
    public void start() throws InterruptedException {
        LOG.info("Jira watcher started (poll interval: " + pollIntervalMinutes + " min)");

        while (running) {
            try {
                List<JiraIssue> changed = source.incrementalSync();

                if (!changed.isEmpty()) {
                    try (SearchIndex index = new SearchIndex(indexPath)) {
                        for (JiraIssue issue : changed) {
                            var doc = indexer.indexVirtualFile(
                                    issue.virtualPath(),
                                    issue.markdownContent(),
                                    issue.updated().toEpochMilli(),
                                    "jira");
                            index.addDocument(doc);
                        }
                        index.commit();
                    }
                    LOG.info("Jira watcher: re-indexed " + changed.size() + " issue(s)");
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Jira poll failed (will retry next cycle): " + e.getMessage(), e);
            }

            long sleepEnd = System.currentTimeMillis() + pollIntervalMinutes * 60_000L;
            while (running && System.currentTimeMillis() < sleepEnd) {
                Thread.sleep(SLEEP_CHECK_INTERVAL_MS);
            }
        }

        LOG.info("Jira watcher stopped");
    }

    @Override
    public void close() {
        running = false;
    }

    /** Returns whether the watcher is still running. Visible for testing. */
    boolean isRunning() {
        return running;
    }
}
