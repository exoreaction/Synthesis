package io.exoreaction.synthesis.util;

import java.time.Duration;
import java.time.Instant;

/**
 * Terminal progress reporter for long-running operations.
 * Displays a progress bar with percentage, count, and estimated time remaining.
 */
public final class ProgressReporter {

    private final String label;
    private final int total;
    private final Instant startTime;
    private int current;
    private int lastPercent = -1;

    public ProgressReporter(String label, int total) {
        this.label = label;
        this.total = total;
        this.startTime = Instant.now();
        this.current = 0;
    }

    /**
     * Increment progress by one and update the display.
     */
    public void tick() {
        tick(1);
    }

    /**
     * Increment progress by the given amount and update the display.
     */
    public void tick(int amount) {
        current += amount;
        int percent = total > 0 ? (current * 100 / total) : 100;

        // Only redraw when percentage changes (reduces flicker)
        if (percent != lastPercent) {
            lastPercent = percent;
            render(percent);
        }
    }

    /**
     * Mark progress as complete and print final summary.
     */
    public void complete() {
        current = total;
        render(100);
        System.out.println(); // newline after progress bar

        Duration elapsed = Duration.between(startTime, Instant.now());
        System.out.printf("  %s complete: %d items in %s%n",
                label, total, formatDuration(elapsed));
    }

    /**
     * Complete with an error message.
     */
    public void fail(String reason) {
        System.out.println();
        AnsiOutput.printError(label + " failed: " + reason);
    }

    private void render(int percent) {
        int barWidth = 30;
        int filled = barWidth * percent / 100;
        int empty = barWidth - filled;

        StringBuilder bar = new StringBuilder();
        bar.append('\r'); // carriage return to overwrite line
        bar.append("  ");
        bar.append(AnsiOutput.isEnabled() ? AnsiOutput.blue(label) : label);
        bar.append(" [");

        if (AnsiOutput.isEnabled()) {
            bar.append(AnsiOutput.green("=".repeat(filled)));
            bar.append(" ".repeat(empty));
        } else {
            bar.append("=".repeat(filled));
            bar.append(" ".repeat(empty));
        }

        bar.append("] ");
        bar.append(String.format("%3d%% (%d/%d)", percent, current, total));

        // Estimated time remaining
        if (percent > 0 && percent < 100) {
            Duration elapsed = Duration.between(startTime, Instant.now());
            long estimatedTotal = elapsed.toMillis() * 100 / percent;
            long remaining = estimatedTotal - elapsed.toMillis();
            if (remaining > 1000) {
                bar.append(" ~").append(formatDuration(Duration.ofMillis(remaining))).append(" remaining");
            }
        }

        // Pad with spaces to clear any previous longer line
        bar.append("   ");

        System.out.print(bar);
        System.out.flush();
    }

    private static String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return String.format("%dm %ds", seconds / 60, seconds % 60);
        return String.format("%dh %dm", seconds / 3600, (seconds % 3600) / 60);
    }
}
