package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.telemetry.ApprovalService;
import io.exoreaction.synthesis.telemetry.ClientUUID;
import io.exoreaction.synthesis.telemetry.TelemetryConfig;
import io.exoreaction.synthesis.telemetry.TelemetryService;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Displays telemetry and pilot approval status.
 *
 * <p>Telemetry is mandatory for pilot program participation and cannot be disabled.
 * This command provides transparency into what data is sent and the current
 * approval status.
 *
 * <pre>
 *   synthesis telemetry                     Show status and approval
 *   synthesis telemetry --show              Show what data is sent
 *   synthesis telemetry --check-approval    Force refresh of approval status
 *   synthesis telemetry --reset-uuid        Generate a new client UUID
 * </pre>
 */
@Command(
        name = "telemetry",
        description = "View pilot telemetry and approval status",
        mixinStandardHelpOptions = true
)
public class TelemetryCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(
            names = {"--show"},
            description = "Show what data telemetry sends",
            defaultValue = "false"
    )
    private boolean show;

    @Option(
            names = {"--check-approval"},
            description = "Force refresh of pilot approval status from Slack",
            defaultValue = "false"
    )
    private boolean checkApproval;

    @Option(
            names = {"--reset-uuid"},
            description = "Generate a new client UUID (requires re-approval)",
            defaultValue = "false"
    )
    private boolean resetUuid;

    @Override
    public Integer call() {
        try {
            if (resetUuid) {
                return handleResetUuid();
            } else if (checkApproval) {
                return handleCheckApproval();
            } else if (show) {
                return handleShow();
            } else {
                return handleStatus();
            }
        } catch (Exception e) {
            AnsiOutput.printError("Telemetry operation failed: " + e.getMessage());
            return 1;
        }
    }

    private int handleResetUuid() throws IOException {
        Path uuidPath = ClientUUID.getUuidPath();
        if (Files.exists(uuidPath)) {
            Files.delete(uuidPath);
        }
        String newUuid = ClientUUID.getOrCreate();
        AnsiOutput.printSuccess("New client UUID generated: " + newUuid);
        AnsiOutput.printWarning("You will need to request re-approval for this UUID.");
        System.out.println();
        System.out.println("  Provide this UUID to the Synthesis maintainer for pilot approval.");
        System.out.println();

        // Report the new UUID
        TelemetryService service = TelemetryService.create();
        service.reportInstall();
        service.shutdown();

        return 0;
    }

    private int handleCheckApproval() {
        String uuid = ClientUUID.read();
        if (uuid == null) {
            AnsiOutput.printError("No client UUID found. Run 'synthesis init' first.");
            return 1;
        }

        AnsiOutput.printHeader("Synthesis - Checking Pilot Approval");
        System.out.printf("  Client UUID: %s%n", uuid);
        System.out.println("  Checking approval channel...");

        ApprovalService approval = ApprovalService.create();
        try {
            approval.refreshApprovalStatus(uuid);

            if (approval.getCachedApproval() != null && approval.getCachedApproval()) {
                System.out.println();
                AnsiOutput.printSuccess("Pilot approved! Your UUID is in the approval list.");
            } else {
                System.out.println();
                AnsiOutput.printWarning("Pilot approval pending.");
                System.out.println("  Provide your UUID to the maintainer: " + uuid);
            }
        } catch (IOException e) {
            AnsiOutput.printWarning("Could not check approval: " + e.getMessage());
            System.out.println("  This may be a network issue or the approval system is not configured.");

            if (approval.getCachedApproval() != null) {
                System.out.println("  Cached status: " + (approval.getCachedApproval() ? "Approved" : "Pending"));
            }
        }

        System.out.println();
        return 0;
    }

    private int handleShow() {
        TelemetryService service = TelemetryService.create();
        System.out.println();
        System.out.println(service.describeWhatIsSent());
        service.shutdown();
        return 0;
    }

    private int handleStatus() {
        AnsiOutput.printHeader("Synthesis - Pilot Status");

        String uuid = ClientUUID.read();
        TelemetryConfig config = TelemetryConfig.load();

        // UUID
        System.out.printf("  %-22s %s%n", "Client UUID:",
                uuid != null ? uuid : AnsiOutput.dim("(not generated -- run 'synthesis init')"));

        // Telemetry
        System.out.printf("  %-22s %s%n", "Telemetry:",
                config.isWebhookConfigured()
                        ? AnsiOutput.success("Active (mandatory)")
                        : AnsiOutput.warning("No webhook configured"));

        // Approval
        ApprovalService approval = ApprovalService.create();
        Boolean approvedStatus = approval.getCachedApproval();
        if (approvedStatus != null) {
            System.out.printf("  %-22s %s%n", "Pilot Approval:",
                    approvedStatus
                            ? AnsiOutput.success("Approved")
                            : AnsiOutput.warning("Pending Approval"));
            if (approval.getLastCheck() != null) {
                System.out.printf("  %-22s %s%n", "Last Check:",
                        AnsiOutput.dim(approval.getLastCheck().toString()));
            }
        } else {
            System.out.printf("  %-22s %s%n", "Pilot Approval:",
                    AnsiOutput.dim("Not checked yet"));
        }

        // Config file locations
        System.out.println();
        Path telemetryPath = TelemetryConfig.getConfigPath();
        System.out.printf("  %-22s %s%n", "Telemetry config:",
                Files.exists(telemetryPath) ? telemetryPath : AnsiOutput.dim("(not created)"));

        System.out.println();
        System.out.println("  " + AnsiOutput.cyan("synthesis telemetry --show")
                + "             See what data is sent");
        System.out.println("  " + AnsiOutput.cyan("synthesis telemetry --check-approval")
                + "   Refresh approval status");
        System.out.println();

        return 0;
    }
}
