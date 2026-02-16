package io.exoreaction.synthesis.cli;

import picocli.CommandLine.Command;

/**
 * Dashboard command - alias for status command.
 *
 * <p>Provides a user-friendly name for the comprehensive workspace dashboard.
 * This is functionally identical to {@link StatusCommand}.
 *
 * @see StatusCommand
 * @since v1.7.1
 */
@Command(
        name = "dashboard",
        description = "Show comprehensive workspace dashboard (alias for 'status')",
        mixinStandardHelpOptions = true
)
public class DashboardCommand extends StatusCommand {
    // Inherits all functionality from StatusCommand
}
