package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Generate Claude Code hook configurations that inject Synthesis context into sessions.
 *
 * <p>This is the direct bridge between Synthesis (codebase knowledge) and the
 * Ars Contexta session lifecycle model.
 *
 * <p>Usage:
 * <pre>
 *   synthesis hooks generate                          # Generate and write to ~/.claude/settings.json
 *   synthesis hooks generate --dry-run                # Print merged JSON without writing
 *   synthesis hooks generate --type PreToolUse         # Use PreToolUse hook type
 *   synthesis hooks generate -o /path/to/settings.json # Write to custom path
 * </pre>
 */
@Command(
        name = "hooks",
        description = "Manage Claude Code hook configurations",
        mixinStandardHelpOptions = true,
        subcommands = {
                HooksCommand.GenerateSubcommand.class
        }
)
public class HooksCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    // Package-private setter for testing
    void setParent(SynthesisApp parent) { this.parent = parent; }

    @Override
    public Integer call() {
        System.out.println("Usage: synthesis hooks generate [options]");
        System.out.println();
        System.out.println("Subcommands:");
        System.out.println("  generate    Generate Claude Code hook configuration");
        return 0;
    }

    /**
     * The 'synthesis hooks generate' subcommand.
     */
    @Command(
            name = "generate",
            description = "Generate Claude Code hook configuration",
            mixinStandardHelpOptions = true
    )
    public static class GenerateSubcommand implements Callable<Integer> {

        @ParentCommand
        private HooksCommand parent;

        @Option(names = {"--type"}, description = "Hook type: UserPromptSubmit (default) or PreToolUse",
                defaultValue = "UserPromptSubmit")
        private String hookType;

        @Option(names = {"-o", "--output"}, description = "Output file (default: ~/.claude/settings.json)")
        private Path output;

        @Option(names = {"--dry-run"}, description = "Print merged JSON to stdout without writing",
                defaultValue = "false")
        private boolean dryRun;

        // Package-private setters for testing
        void setParent(HooksCommand parent) { this.parent = parent; }
        void setHookType(String hookType) { this.hookType = hookType; }
        void setOutput(Path output) { this.output = output; }
        void setDryRun(boolean dryRun) { this.dryRun = dryRun; }

        @Override
        public Integer call() {
            try {
                Path workspaceRoot = parent.parent != null ? parent.parent.getWorkspaceRoot() : null;

                // Build the command to inject
                String command = "synthesis session-context --compact";
                if (workspaceRoot != null) {
                    // Check if non-default workspace (has -d flag)
                    Path defaultWorkspace = Path.of(".").toAbsolutePath().normalize();
                    if (!workspaceRoot.equals(defaultWorkspace)) {
                        command += " -d " + workspaceRoot;
                    }
                }

                // Determine output file
                Path settingsPath = output;
                if (settingsPath == null) {
                    settingsPath = Path.of(System.getProperty("user.home"), ".claude", "settings.json");
                }

                // Read existing settings
                String existingJson = "";
                if (Files.exists(settingsPath)) {
                    existingJson = Files.readString(settingsPath);
                }

                // Build merged JSON
                String mergedJson;
                try {
                    mergedJson = mergeHookEntry(existingJson, hookType, command);
                } catch (MalformedJsonException e) {
                    AnsiOutput.printError("Malformed JSON in " + settingsPath + ": " + e.getMessage());
                    AnsiOutput.printError("File will not be modified. Fix the JSON manually first.");
                    return 1;
                }

                if (mergedJson == null) {
                    // Hook already exists (idempotent)
                    AnsiOutput.printInfo("Hook already exists in " + settingsPath + " (skipping, idempotent).");
                    return 0;
                }

                if (dryRun) {
                    System.out.print(mergedJson);
                    return 0;
                }

                // Write merged result
                Files.createDirectories(settingsPath.getParent());
                Files.writeString(settingsPath, mergedJson);
                AnsiOutput.printSuccess("Hook added to " + settingsPath);
                AnsiOutput.printInfo("Hook type: " + hookType);
                AnsiOutput.printInfo("Command: " + command);

                return 0;

            } catch (Exception e) {
                AnsiOutput.printError("Hook generation failed: " + e.getMessage());
                return 1;
            }
        }

        /**
         * Merges a hook entry into existing JSON content.
         *
         * @param existingJson the existing settings.json content (may be empty)
         * @param hookType     the hook type (e.g., UserPromptSubmit)
         * @param command      the command to inject
         * @return merged JSON string, or null if hook already exists
         * @throws MalformedJsonException if existing JSON is malformed
         */
        static String mergeHookEntry(String existingJson, String hookType, String command)
                throws MalformedJsonException {

            // Build the hook entry
            String hookEntry = buildHookEntry(hookType, command);

            if (existingJson == null || existingJson.isBlank()) {
                // No existing file -- create fresh
                return "{\n  \"hooks\": " + hookEntry + "\n}\n";
            }

            // Validate that existing JSON is parseable (basic brace matching)
            String trimmed = existingJson.trim();
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
                throw new MalformedJsonException("File does not start with '{' and end with '}'");
            }

            // Check balanced braces
            int braceCount = 0;
            boolean inString = false;
            boolean escaped = false;
            for (char c : trimmed.toCharArray()) {
                if (escaped) { escaped = false; continue; }
                if (c == '\\') { escaped = true; continue; }
                if (c == '"') { inString = !inString; continue; }
                if (inString) continue;
                if (c == '{') braceCount++;
                if (c == '}') braceCount--;
            }
            if (braceCount != 0) {
                throw new MalformedJsonException("Unbalanced braces in JSON");
            }

            // Check if hook already exists (exact command match)
            if (existingJson.contains("\"" + command + "\"")) {
                return null; // Already exists
            }

            // Check if "hooks" key already exists
            if (existingJson.contains("\"hooks\"")) {
                // Check if hookType already exists inside hooks
                if (existingJson.contains("\"" + hookType + "\"")) {
                    // Add our hook entry to existing hookType array
                    // Find the hookType array and add our entry
                    return insertHookIntoExistingType(existingJson, hookType, command);
                } else {
                    // Add new hookType to hooks object
                    return insertNewHookType(existingJson, hookType, command);
                }
            } else {
                // Add "hooks" key to the top-level object
                return insertHooksKey(existingJson, hookType, command);
            }
        }

        private static String buildHookEntry(String hookType, String command) {
            return "{\n" +
                    "    \"" + hookType + "\": [\n" +
                    "      {\n" +
                    "        \"matcher\": \"\",\n" +
                    "        \"hooks\": [\n" +
                    "          {\n" +
                    "            \"type\": \"command\",\n" +
                    "            \"command\": \"" + command + "\"\n" +
                    "          }\n" +
                    "        ]\n" +
                    "      }\n" +
                    "    ]\n" +
                    "  }";
        }

        private static String insertHooksKey(String existingJson, String hookType, String command) {
            String trimmed = existingJson.trim();
            // Find the last '}' and insert before it
            int lastBrace = trimmed.lastIndexOf('}');
            String before = trimmed.substring(0, lastBrace).trim();

            // Check if there are existing keys (need a comma)
            boolean hasExistingKeys = before.length() > 1; // More than just '{'
            // Check if we need a comma: if the content before the brace doesn't end with '{' or ','
            String prefix = before;
            if (hasExistingKeys && !prefix.endsWith("{") && !prefix.endsWith(",")) {
                prefix += ",";
            }

            return prefix + "\n  \"hooks\": " + buildHookEntry(hookType, command) + "\n}\n";
        }

        private static String insertNewHookType(String existingJson, String hookType, String command) {
            // Find the hooks object and add the new hookType
            int hooksIdx = existingJson.indexOf("\"hooks\"");
            int hooksObjStart = existingJson.indexOf('{', hooksIdx + 7);
            if (hooksObjStart < 0) return insertHooksKey(existingJson, hookType, command);

            // Find the matching closing brace for the hooks object
            int braceCount = 1;
            int pos = hooksObjStart + 1;
            boolean inStr = false;
            boolean esc = false;
            int hooksObjEnd = -1;
            while (pos < existingJson.length()) {
                char c = existingJson.charAt(pos);
                if (esc) { esc = false; pos++; continue; }
                if (c == '\\') { esc = true; pos++; continue; }
                if (c == '"') { inStr = !inStr; pos++; continue; }
                if (inStr) { pos++; continue; }
                if (c == '{') braceCount++;
                if (c == '}') { braceCount--; if (braceCount == 0) { hooksObjEnd = pos; break; } }
                pos++;
            }
            if (hooksObjEnd < 0) return insertHooksKey(existingJson, hookType, command);

            // Insert before the closing brace of hooks object
            String beforeClose = existingJson.substring(0, hooksObjEnd).trim();
            if (!beforeClose.endsWith("{") && !beforeClose.endsWith(",")) {
                beforeClose += ",";
            }
            String hookTypeEntry = "\n    \"" + hookType + "\": [\n" +
                    "      {\n" +
                    "        \"matcher\": \"\",\n" +
                    "        \"hooks\": [\n" +
                    "          {\n" +
                    "            \"type\": \"command\",\n" +
                    "            \"command\": \"" + command + "\"\n" +
                    "          }\n" +
                    "        ]\n" +
                    "      }\n" +
                    "    ]\n  ";
            return beforeClose + hookTypeEntry + existingJson.substring(hooksObjEnd);
        }

        private static String insertHookIntoExistingType(String existingJson, String hookType, String command) {
            // Find the hookType array and add our matcher entry
            int typeIdx = existingJson.indexOf("\"" + hookType + "\"");
            int arrayStart = existingJson.indexOf('[', typeIdx);
            if (arrayStart < 0) return existingJson;

            // Find the matching closing bracket
            int bracketCount = 1;
            int pos = arrayStart + 1;
            boolean inStr = false;
            boolean esc = false;
            int arrayEnd = -1;
            while (pos < existingJson.length()) {
                char c = existingJson.charAt(pos);
                if (esc) { esc = false; pos++; continue; }
                if (c == '\\') { esc = true; pos++; continue; }
                if (c == '"') { inStr = !inStr; pos++; continue; }
                if (inStr) { pos++; continue; }
                if (c == '[') bracketCount++;
                if (c == ']') { bracketCount--; if (bracketCount == 0) { arrayEnd = pos; break; } }
                pos++;
            }
            if (arrayEnd < 0) return existingJson;

            // Insert new entry before the closing bracket
            String newEntry = ",\n      {\n" +
                    "        \"matcher\": \"\",\n" +
                    "        \"hooks\": [\n" +
                    "          {\n" +
                    "            \"type\": \"command\",\n" +
                    "            \"command\": \"" + command + "\"\n" +
                    "          }\n" +
                    "        ]\n" +
                    "      }\n    ";
            return existingJson.substring(0, arrayEnd) + newEntry + existingJson.substring(arrayEnd);
        }

        /**
         * Exception for malformed JSON input.
         */
        static class MalformedJsonException extends Exception {
            MalformedJsonException(String message) {
                super(message);
            }
        }
    }
}
