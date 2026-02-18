# Synthesis Interactive CLI

## Context

Synthesis includes interactive CLI modes that enable multi-turn conversations with
AI about workspace content. The `ask --interactive` command provides a REPL-like
experience with conversation history, slash commands, and context-aware responses.

Use this skill when you need to:
- Understand the interactive conversation architecture
- Add new interactive commands or modes
- Implement conversation history management
- Build terminal UI features for Synthesis

## Key Patterns

- Interactive mode activated via `synthesis ask --interactive` flag
- Conversation history stored as `List<ConversationTurn>` (max 10 turns, FIFO)
- Slash commands (`/help`, `/exit`, `/clear`, `/history`) for session control
- Context built from Lucene search results + conversation history
- Terminal UI uses `AnsiOutput` utility for colors, bold, dim text
- Scanner-based input with Ctrl+D support for graceful exit
- Each question triggers a fresh index search for relevant files

## Code Examples

### Interactive Session Flow

```java
// From AskCommand.runInteractive()
Scanner scanner = new Scanner(System.in);
List<ConversationTurn> history = new ArrayList<>();

while (true) {
    System.out.print(AnsiOutput.bold("You: "));
    System.out.flush();

    if (!scanner.hasNextLine()) break;  // Ctrl+D

    String input = scanner.nextLine().trim();
    if (input.isEmpty()) continue;

    // Handle slash commands
    if (input.startsWith("/")) {
        handleSlashCommand(input, history);
        continue;
    }

    // Search index for relevant files
    List<SearchResult> results;
    try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
        results = index.search(input, contextFiles);
    }

    // Build context: files + conversation history
    String fileContext = buildContext(results, workspaceRoot);
    String conversationContext = buildConversationContext(history);

    // Generate and send prompt to Claude
    String prompt = PromptTemplates.buildAskPrompt(input,
        fileContext + "\n\nPrevious conversation:\n" + conversationContext);
    String answer = client.generate(prompt, maxTokens);

    // Store in history (keep last 10)
    history.add(new ConversationTurn(input, answer));
    if (history.size() > 10) history.remove(0);

    // Display answer
    System.out.println(AnsiOutput.cyan("Assistant:"));
    for (String line : answer.split("\n")) {
        System.out.println("  " + line);
    }
}
```

### ConversationTurn Record

```java
private record ConversationTurn(String question, String answer) {}
```

### Slash Command Handling

```java
if (input.startsWith("/")) {
    switch (input) {
        case "/exit", "/quit" -> { return; }
        case "/help" -> showInteractiveHelp();
        case "/clear" -> {
            history.clear();
            System.out.println(AnsiOutput.dim("  Conversation history cleared."));
        }
        case "/history" -> showHistory(history);
        default -> {
            System.out.println(AnsiOutput.warning("  Unknown command: " + input));
            System.out.println("  Type /help for available commands.");
        }
    }
    continue;
}
```

### Building Conversation Context

```java
private String buildConversationContext(List<ConversationTurn> history) {
    if (history.isEmpty()) return "";

    StringBuilder context = new StringBuilder();
    for (ConversationTurn turn : history) {
        context.append("Q: ").append(turn.question()).append("\n");
        context.append("A: ").append(turn.answer()).append("\n\n");
    }
    return context.toString();
}
```

### File Context Building (with line numbers)

```java
String buildContext(List<SearchResult> results, Path workspaceRoot) {
    StringBuilder context = new StringBuilder();
    int maxBytesPerFile = 4096;

    for (SearchResult result : results) {
        context.append("\n--- File: ").append(result.relativePath());
        if (result.language() != null) {
            context.append(" (").append(result.language()).append(")");
        }
        context.append(" ---\n");

        String content = FileUtils.readPreview(result.path(), maxBytesPerFile);
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            context.append(String.format("L%d: %s\n", i + 1, lines[i]));
        }
    }
    return context.toString();
}
```

### Welcome Banner

```java
System.out.println(AnsiOutput.bold(
    "+=================================================================+"));
System.out.println(AnsiOutput.bold(
    "| Synthesis Interactive Q&A                                        |"));
System.out.println(AnsiOutput.bold(
    "| Workspace: " + String.format("%-49s", config.getWorkspace().getName()) + "|"));
System.out.println(AnsiOutput.bold(
    "+=================================================================+"));
System.out.println();
System.out.println("  Type your question or " + AnsiOutput.cyan("/help") + " for commands.");
System.out.println("  Press " + AnsiOutput.cyan("Ctrl+D") + " or type " + AnsiOutput.cyan("/exit") + " to quit.");
```

## Common Tasks

### Add a New Slash Command

1. Add the command handling in the slash command block in `AskCommand.runInteractive()`:

   ```java
   } else if (input.equals("/context")) {
       showContextFiles(lastResults);
       continue;
   }
   ```

2. Update the help text in `showInteractiveHelp()`:

   ```java
   System.out.println("    " + AnsiOutput.cyan("/context") + "  - Show files used as context");
   ```

3. Implement the command logic.

### Add a New Interactive Mode (e.g., explore)

1. Create a new command class (e.g., `ExploreCommand.java`) or add a mode flag to an existing command.

2. Follow the pattern from `AskCommand.runInteractive()`:
   - Validate workspace and AI configuration
   - Print welcome banner
   - Enter input loop with Scanner
   - Handle slash commands
   - Process input and display results
   - Handle Ctrl+D gracefully

3. Register the command in `SynthesisApp.java`:
   ```java
   @Command(subcommands = {
       // ...
       ExploreCommand.class
   })
   ```

### Implement Conversation Export

```java
// Add /export command to save conversation
} else if (input.startsWith("/export")) {
    String filename = input.length() > 8 ? input.substring(8).trim() : "conversation.md";
    Path exportPath = Path.of(filename);
    StringBuilder md = new StringBuilder("# Synthesis Conversation\n\n");
    for (ConversationTurn turn : history) {
        md.append("## Q: ").append(turn.question()).append("\n\n");
        md.append(turn.answer()).append("\n\n---\n\n");
    }
    Files.writeString(exportPath, md.toString());
    System.out.println(AnsiOutput.dim("  Exported to " + exportPath));
    continue;
}
```

### Terminal UI Best Practices

- Use `AnsiOutput` for all color/formatting (centralized ANSI control)
- `AnsiOutput.bold()` - Headers and important text
- `AnsiOutput.cyan()` - Commands, file paths, clickable items
- `AnsiOutput.dim()` - Secondary info, hints
- `AnsiOutput.success()` / `AnsiOutput.green()` - Success indicators
- `AnsiOutput.warning()` / `AnsiOutput.yellow()` - Warnings
- `AnsiOutput.error()` - Error messages
- `AnsiOutput.blue()` - Source code type badge
- Always `System.out.flush()` after prompts (before reading input)
- Support both `/exit` and Ctrl+D for exiting
- Indent output by 2 spaces for visual clarity
- Use box-drawing characters for banners (Unicode safe in modern terminals)

## Architecture

```
AskCommand
  |-- runSingleQuestion()     Single Q&A (default mode)
  |-- runInteractive()        Multi-turn REPL mode
  |     |-- ConversationTurn  (record: question, answer)
  |     |-- showInteractiveHelp()
  |     |-- showHistory()
  |     |-- buildConversationContext()
  |-- buildContext()           Shared: builds file context from search results
  |
  +-- Dependencies:
      |-- SearchIndex          Lucene full-text search
      |-- ClaudeClient         Anthropic API wrapper
      |-- PromptTemplates      Prompt construction
      |-- AnsiOutput           Terminal formatting
      |-- FileUtils            File reading utilities
```

## Related Files

- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/cli/AskCommand.java` - Interactive Q&A implementation
- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/util/AnsiOutput.java` - Terminal color/formatting
- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/ai/ClaudeClient.java` - Claude API client
- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/ai/PromptTemplates.java` - Prompt construction
- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/index/SearchIndex.java` - Lucene search
- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/util/FileUtils.java` - File reading
- `/src/exoreaction/Synthesis/src/test/java/io/exoreaction/synthesis/cli/AskCommandTest.java` - Tests

## Testing

```bash
# Run AskCommand tests
cd /src/exoreaction/Synthesis
mvn test -Dtest="AskCommandTest"

# Manual testing (requires ANTHROPIC_API_KEY):
export ANTHROPIC_API_KEY=your-key
synthesis -d /src/exoreaction ask --interactive

# Test slash commands:
# /help     -> Shows available commands
# /clear    -> Clears history
# /history  -> Shows conversation turns
# /exit     -> Exits cleanly

# Test context building (non-interactive, no AI needed):
synthesis -d /src/exoreaction ask "How does scanning work?" --verbose
# --verbose shows which files were used as context
```

## Design Decisions

- **Max 10 conversation turns**: Prevents prompt from growing too large (each turn adds ~2-4KB)
- **Fresh search per question**: Each question triggers a new index search, ensuring context relevance
- **Scanner over BufferedReader**: Simpler API, sufficient for line-by-line input
- **Slash commands start with /**: Standard REPL convention, easy to parse
- **Conversation context appended to file context**: AI sees both code context and conversation history
- **Perspectives suggestion**: For ambiguous questions, suggests `synthesis perspectives` command

## See Also

- `synthesis-workspace-management.md` - Workspaces that interactive mode operates on
- `synthesis-metrics-tracking.md` - Interactive sessions generate metrics
- `synthesis-development.md` - General development patterns
