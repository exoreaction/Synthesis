package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.org.*;
import io.exoreaction.synthesis.util.AnsiOutput;

import java.io.*;
import java.util.*;

/**
 * Interactive confirmation handler for organization discovery during init.
 *
 * <p>Presents discovered organizations to the user and allows them to:
 * <ul>
 *   <li>Accept all organizations</li>
 *   <li>Reject all organizations</li>
 *   <li>Review each organization individually (Y/n per org)</li>
 *   <li>Correct organization types</li>
 * </ul>
 *
 * <p>Designed for testability: accepts a {@link BufferedReader} for input
 * and a {@link PrintStream} for output, rather than reading from System.in directly.
 */
public class InteractiveConfirmation {

    private final BufferedReader input;
    private final PrintStream output;

    /**
     * Creates a new InteractiveConfirmation using System.in and System.out.
     */
    public InteractiveConfirmation() {
        this(new BufferedReader(new InputStreamReader(System.in)), System.out);
    }

    /**
     * Creates a new InteractiveConfirmation with custom I/O (for testing).
     *
     * @param input  the input reader
     * @param output the output stream
     */
    public InteractiveConfirmation(BufferedReader input, PrintStream output) {
        this.input = input;
        this.output = output;
    }

    /**
     * Result of the interactive confirmation process.
     *
     * @param accepted    the list of accepted organizations
     * @param rejected    the list of rejected organizations
     * @param wasReviewed whether the user reviewed individual organizations
     */
    public record ConfirmationResult(
            List<Organization> accepted,
            List<Organization> rejected,
            boolean wasReviewed
    ) {
        public boolean hasAccepted() {
            return !accepted.isEmpty();
        }
    }

    /**
     * Presents discovered organizations and prompts for confirmation.
     *
     * @param discoveries the discovered organizations with confidence
     * @return the confirmation result with accepted and rejected lists
     */
    public ConfirmationResult confirm(List<DiscoveredOrganization> discoveries) throws IOException {
        if (discoveries.isEmpty()) {
            return new ConfirmationResult(List.of(), List.of(), false);
        }

        // Display discoveries
        displayDiscoveries(discoveries);

        // Prompt for action
        output.println();
        output.print("  Accept all organizations? [Y/n/review] ");
        output.flush();

        String response = readLine();

        if (response.equalsIgnoreCase("n") || response.equalsIgnoreCase("no")) {
            // Reject all
            List<Organization> rejected = discoveries.stream()
                    .map(DiscoveredOrganization::organization)
                    .toList();
            return new ConfirmationResult(List.of(), rejected, false);
        } else if (response.equalsIgnoreCase("review") || response.equalsIgnoreCase("r")) {
            // Review each individually
            return reviewIndividually(discoveries);
        } else {
            // Accept all (Y or empty/enter)
            List<Organization> accepted = discoveries.stream()
                    .map(DiscoveredOrganization::organization)
                    .toList();
            return new ConfirmationResult(accepted, List.of(), false);
        }
    }

    /**
     * Reviews each organization individually, prompting Y/n for each.
     */
    ConfirmationResult reviewIndividually(List<DiscoveredOrganization> discoveries) throws IOException {
        List<Organization> accepted = new ArrayList<>();
        List<Organization> rejected = new ArrayList<>();

        output.println();
        output.println("  Review mode - edit detected organizations:");

        for (DiscoveredOrganization discovery : discoveries) {
            Organization org = discovery.organization();
            String typeName = org.getType().name().toLowerCase();
            String confidence = discovery.normalizedConfidence() + "/10";

            output.printf("    %s (%s, confidence: %s) - %s%n",
                    AnsiOutput.bold(org.getName()),
                    typeName,
                    confidence,
                    discovery.signals());

            output.print("    Accept? [Y/n] ");
            output.flush();

            String response = readLine();

            if (response.equalsIgnoreCase("n") || response.equalsIgnoreCase("no")) {
                rejected.add(org);
                output.println("    " + AnsiOutput.dim("Rejected: " + org.getName()));
            } else {
                accepted.add(org);
                output.println("    " + AnsiOutput.green("Accepted: " + org.getName()));
            }
        }

        return new ConfirmationResult(accepted, rejected, true);
    }

    /**
     * Displays the list of discovered organizations with details.
     */
    void displayDiscoveries(List<DiscoveredOrganization> discoveries) {
        output.printf("%n  Found %s potential organization%s:%n%n",
                AnsiOutput.bold(String.valueOf(discoveries.size())),
                discoveries.size() != 1 ? "s" : "");

        int index = 1;
        for (DiscoveredOrganization discovery : discoveries) {
            Organization org = discovery.organization();
            String typeBadge = formatTypeBadge(org.getType());
            String confidence = "confidence: " + discovery.normalizedConfidence() + "/10";

            output.printf("    %d. %s %s - %s%n",
                    index++,
                    AnsiOutput.bold(org.getName()),
                    typeBadge,
                    AnsiOutput.dim(confidence));

            output.printf("       Path: %s%n", AnsiOutput.dim(org.getBasePath()));
            output.printf("       Detected: %s%n", discovery.signals());

            // Show client summary if available
            if (!org.getClients().isEmpty()) {
                long active = org.getClientsByStatus(ClientStatus.ACTIVE).size();
                long opp = org.getClientsByStatus(ClientStatus.OPPORTUNITY).size();
                long signed = org.getClientsByStatus(ClientStatus.SIGNED).size();
                long past = org.getClientsByStatus(ClientStatus.PAST).size();

                StringBuilder sb = new StringBuilder("       Clients: ");
                List<String> parts = new ArrayList<>();
                if (active > 0) parts.add(active + " active");
                if (signed > 0) parts.add(signed + " signed");
                if (opp > 0) parts.add(opp + " opportunity");
                if (past > 0) parts.add(past + " past");
                sb.append(String.join(", ", parts));
                output.println(sb);
            }

            // Show products summary if available
            if (!org.getProducts().isEmpty()) {
                output.printf("       Products: %s%n",
                        String.join(", ", org.getProducts().stream()
                                .map(Product::getName).toList()));
            }

            output.println();
        }
    }

    /**
     * Formats a type badge with color coding.
     */
    String formatTypeBadge(OrganizationType type) {
        return switch (type) {
            case COMPANY -> AnsiOutput.blue("(COMPANY)");
            case FOUNDATION -> AnsiOutput.green("(FOUNDATION)");
            case HOLDING -> AnsiOutput.magenta("(HOLDING)");
            case CONCEPT -> AnsiOutput.cyan("(CONCEPT)");
            case OTHER -> AnsiOutput.dim("(OTHER)");
        };
    }

    /**
     * Reads a line from the input reader, trimming whitespace.
     * Returns empty string if null (EOF).
     */
    String readLine() throws IOException {
        String line = input.readLine();
        return line != null ? line.trim() : "";
    }
}
