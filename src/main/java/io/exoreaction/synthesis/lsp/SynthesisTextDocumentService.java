package io.exoreaction.synthesis.lsp;

import io.exoreaction.synthesis.cli.RelateCommand;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.util.FileUtils;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles text document-level LSP requests for Synthesis.
 *
 * <p>Implements:
 * <ul>
 *   <li><b>Document Links</b> -- Clickable links to referenced files</li>
 *   <li><b>Hover</b> -- File metadata, relationship stats on hover over paths</li>
 *   <li><b>Diagnostics</b> -- Warn about broken file references</li>
 *   <li><b>Go to Definition</b> -- Navigate to referenced file</li>
 *   <li><b>Find References</b> -- Find all files that reference the current file</li>
 *   <li><b>Code Lens</b> -- Show relationship counts inline</li>
 * </ul>
 */
public class SynthesisTextDocumentService implements TextDocumentService {

    private final Logger log;
    private final SynthesisLanguageServer server;

    // Pattern to detect file references in text
    private static final Pattern FILE_REF_PATTERN = Pattern.compile(
            "(?:(?:import\\s+|from\\s+|require\\s*\\(?['\"])|" +    // import/require
            "\\[(?:[^\\]]*)]\\(|" +                                   // markdown link [text](
            "['\"`])([\\w./-]+\\.(?:java|py|js|ts|tsx|jsx|md|yaml|yml|json|xml|go|rs|kt|sh|sql|toml|html|css|scss))" +
            "(?:['\"`\\)])?");

    // Markdown-specific link pattern for detecting references
    private static final Pattern MARKDOWN_LINK = Pattern.compile(
            "\\[([^\\]]*)]\\(([^)]+)\\)");

    private LanguageClient client;
    private Path workspaceRoot;

    // Track open documents for diagnostics
    private final Map<String, String> openDocuments = new HashMap<>();

    public SynthesisTextDocumentService(SynthesisLanguageServer server) {
        this.server = server;
        this.log = Logger.getLogger("io.exoreaction.synthesis.lsp.textdoc");
    }

    public void setClient(LanguageClient client) {
        this.client = client;
    }

    public void setWorkspaceRoot(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    // -----------------------------------------------------------------------
    // Document Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        String content = params.getTextDocument().getText();
        openDocuments.put(uri, content);
        log.fine("Document opened: " + uri);

        // Run diagnostics on open
        publishDiagnostics(uri, content);
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        // Apply incremental changes
        for (TextDocumentContentChangeEvent change : params.getContentChanges()) {
            if (change.getRange() == null) {
                // Full document update
                openDocuments.put(uri, change.getText());
            }
            // For incremental, we would need to apply the range change
            // For now, just use the last full text if available
        }
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        openDocuments.remove(uri);
        log.fine("Document closed: " + uri);

        // Clear diagnostics
        if (client != null) {
            client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
        }
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        log.fine("Document saved: " + uri);

        // Re-run diagnostics on save
        String content = openDocuments.get(uri);
        if (content != null) {
            publishDiagnostics(uri, content);
        }
    }

    // -----------------------------------------------------------------------
    // Document Links
    // -----------------------------------------------------------------------

    @Override
    public CompletableFuture<List<DocumentLink>> documentLink(DocumentLinkParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.getTextDocument().getUri();
            log.fine("Document link request: " + uri);

            List<DocumentLink> links = new ArrayList<>();

            try {
                Path filePath = Path.of(URI.create(uri));
                if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
                    return links;
                }

                String content = openDocuments.getOrDefault(uri, "");
                if (content.isEmpty()) {
                    content = Files.readString(filePath);
                }

                String[] lines = content.split("\n", -1);

                // Find file references and create clickable links
                for (int lineNum = 0; lineNum < lines.length; lineNum++) {
                    String line = lines[lineNum];

                    // Markdown links
                    Matcher mdMatcher = MARKDOWN_LINK.matcher(line);
                    while (mdMatcher.find()) {
                        String linkTarget = mdMatcher.group(2);
                        if (linkTarget.startsWith("http") || linkTarget.startsWith("#")) continue;

                        // Remove anchor fragments
                        String cleanTarget = linkTarget.contains("#")
                                ? linkTarget.substring(0, linkTarget.indexOf('#'))
                                : linkTarget;
                        if (cleanTarget.isEmpty()) continue;

                        // Resolve relative path
                        Path targetPath = filePath.getParent().resolve(cleanTarget).normalize();
                        if (Files.exists(targetPath)) {
                            Range range = new Range(
                                    new Position(lineNum, mdMatcher.start(2)),
                                    new Position(lineNum, mdMatcher.end(2))
                            );
                            DocumentLink link = new DocumentLink(range, targetPath.toUri().toString());
                            links.add(link);
                        }
                    }

                    // Generic file references
                    Matcher refMatcher = FILE_REF_PATTERN.matcher(line);
                    while (refMatcher.find()) {
                        String ref = refMatcher.group(1);
                        if (ref == null || ref.isEmpty()) continue;

                        // Try to resolve the reference
                        Path targetPath = resolveFileRef(ref, filePath);
                        if (targetPath != null && Files.exists(targetPath)) {
                            Range range = new Range(
                                    new Position(lineNum, refMatcher.start(1)),
                                    new Position(lineNum, refMatcher.end(1))
                            );
                            DocumentLink link = new DocumentLink(range, targetPath.toUri().toString());
                            links.add(link);
                        }
                    }
                }

                log.fine("Found " + links.size() + " document links");
            } catch (Exception e) {
                log.warning("Document link analysis failed: " + e.getMessage());
            }

            return links;
        });
    }

    // -----------------------------------------------------------------------
    // Hover (File Metadata)
    // -----------------------------------------------------------------------

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.getTextDocument().getUri();
            Position pos = params.getPosition();
            log.fine("Hover at " + uri + " line " + pos.getLine());

            try {
                Path filePath = Path.of(URI.create(uri));
                String content = openDocuments.getOrDefault(uri, "");
                if (content.isEmpty()) {
                    if (Files.exists(filePath)) {
                        content = Files.readString(filePath);
                    } else {
                        return null;
                    }
                }

                String[] lines = content.split("\n", -1);
                if (pos.getLine() >= lines.length) return null;

                String line = lines[pos.getLine()];
                int col = pos.getCharacter();

                // Find reference at cursor position
                String ref = findReferenceAtPosition(line, col);
                if (ref == null) return null;

                // Try to find file info from index
                Path targetPath = resolveFileRef(ref, filePath);
                if (targetPath == null) return null;

                StringBuilder hoverContent = new StringBuilder();
                hoverContent.append("**Synthesis:** `").append(ref).append("`\n\n");

                if (Files.exists(targetPath)) {
                    long size = Files.size(targetPath);
                    String fileType = FileUtils.classifyFile(targetPath).name();
                    String language = FileUtils.detectLanguage(targetPath);

                    hoverContent.append("| Property | Value |\n");
                    hoverContent.append("|----------|-------|\n");
                    hoverContent.append("| **Type** | ").append(fileType).append(" |\n");
                    if (language != null) {
                        hoverContent.append("| **Language** | ").append(language).append(" |\n");
                    }
                    hoverContent.append("| **Size** | ").append(FileUtils.formatSize(size)).append(" |\n");
                    hoverContent.append("| **Path** | `").append(targetPath).append("` |\n");

                    // Try to get relationship stats from index
                    appendRelationshipStats(hoverContent, targetPath);
                } else {
                    hoverContent.append("*File not found*");
                }

                MarkupContent markup = new MarkupContent();
                markup.setKind("markdown");
                markup.setValue(hoverContent.toString());

                return new Hover(markup);
            } catch (Exception e) {
                log.fine("Hover failed: " + e.getMessage());
                return null;
            }
        });
    }

    // -----------------------------------------------------------------------
    // Go to Definition
    // -----------------------------------------------------------------------

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.getTextDocument().getUri();
            Position pos = params.getPosition();
            log.fine("Go to definition: " + uri + " at " + pos.getLine() + ":" + pos.getCharacter());

            try {
                Path filePath = Path.of(URI.create(uri));
                String content = openDocuments.getOrDefault(uri, "");
                if (content.isEmpty() && Files.exists(filePath)) {
                    content = Files.readString(filePath);
                }

                String[] lines = content.split("\n", -1);
                if (pos.getLine() >= lines.length) return Either.forLeft(List.of());

                String line = lines[pos.getLine()];
                String ref = findReferenceAtPosition(line, pos.getCharacter());
                if (ref == null) return Either.forLeft(List.of());

                Path targetPath = resolveFileRef(ref, filePath);
                if (targetPath != null && Files.exists(targetPath)) {
                    Location location = new Location(
                            targetPath.toUri().toString(),
                            new Range(new Position(0, 0), new Position(0, 0))
                    );
                    return Either.forLeft(List.of(location));
                }
            } catch (Exception e) {
                log.fine("Definition lookup failed: " + e.getMessage());
            }

            return Either.forLeft(List.of());
        });
    }

    // -----------------------------------------------------------------------
    // Find References
    // -----------------------------------------------------------------------

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.getTextDocument().getUri();
            log.fine("Find references for: " + uri);

            List<Location> locations = new ArrayList<>();

            try {
                Path filePath = Path.of(URI.create(uri));
                String fileName = filePath.getFileName().toString();

                if (workspaceRoot == null) return locations;

                WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
                if (workspace.validate().isPresent()) return locations;

                // Find all files that reference this file
                try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                    List<SearchResult> allFiles = index.listAll(null, 5000);

                    RelateCommand relateCmd = new RelateCommand();
                    SearchResult target = null;
                    for (SearchResult r : allFiles) {
                        if (r.path().equals(filePath) ||
                            r.path().toAbsolutePath().equals(filePath.toAbsolutePath())) {
                            target = r;
                            break;
                        }
                    }

                    if (target != null) {
                        RelateCommand.RelationshipMap relMap = new RelateCommand.RelationshipMap(target.relativePath());
                        relateCmd.analyzeIncomingRefs(target, allFiles, workspaceRoot, relMap);

                        for (String relPath : relMap.incoming().keySet()) {
                            for (SearchResult f : allFiles) {
                                if (f.relativePath().equals(relPath)) {
                                    locations.add(new Location(
                                            f.path().toUri().toString(),
                                            new Range(new Position(0, 0), new Position(0, 0))
                                    ));
                                    break;
                                }
                            }
                        }
                    }
                }

                log.fine("Found " + locations.size() + " references");
            } catch (Exception e) {
                log.warning("Find references failed: " + e.getMessage());
            }

            return locations;
        });
    }

    // -----------------------------------------------------------------------
    // Code Lens (relationship counts)
    // -----------------------------------------------------------------------

    @Override
    public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.getTextDocument().getUri();
            log.fine("Code lens request: " + uri);

            List<CodeLens> lenses = new ArrayList<>();

            try {
                Path filePath = Path.of(URI.create(uri));

                if (workspaceRoot == null) return lenses;

                WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
                if (workspace.validate().isPresent()) return lenses;

                try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                    List<SearchResult> allFiles = index.listAll(null, 5000);

                    // Find this file in the index
                    SearchResult target = null;
                    for (SearchResult r : allFiles) {
                        if (r.path().toAbsolutePath().equals(filePath.toAbsolutePath())) {
                            target = r;
                            break;
                        }
                    }

                    if (target != null) {
                        RelateCommand relateCmd = new RelateCommand();
                        Map<String, List<String>> fileNameIndex = new HashMap<>();
                        for (SearchResult f : allFiles) {
                            fileNameIndex.computeIfAbsent(f.fileName(), k -> new ArrayList<>())
                                    .add(f.relativePath());
                        }

                        RelateCommand.RelationshipMap relMap = new RelateCommand.RelationshipMap(target.relativePath());
                        relateCmd.analyzeOutgoingRefs(target, workspaceRoot, relMap, fileNameIndex);
                        relateCmd.analyzeIncomingRefs(target, allFiles, workspaceRoot, relMap);

                        int outgoing = relMap.outgoing().size();
                        int incoming = relMap.incoming().size();

                        if (outgoing + incoming > 0) {
                            // Show at top of file
                            Range range = new Range(new Position(0, 0), new Position(0, 0));
                            Command command = new Command(
                                    "Synthesis: " + outgoing + " outgoing, " + incoming + " incoming references",
                                    ""
                            );
                            lenses.add(new CodeLens(range, command, null));
                        }
                    }
                }
            } catch (Exception e) {
                log.fine("Code lens failed: " + e.getMessage());
            }

            return lenses;
        });
    }

    // -----------------------------------------------------------------------
    // Diagnostics (broken link warnings)
    // -----------------------------------------------------------------------

    private void publishDiagnostics(String uri, String content) {
        if (client == null || workspaceRoot == null) return;

        CompletableFuture.runAsync(() -> {
            try {
                Path filePath = Path.of(URI.create(uri));
                List<Diagnostic> diagnostics = new ArrayList<>();

                String[] lines = content.split("\n", -1);
                for (int lineNum = 0; lineNum < lines.length; lineNum++) {
                    String line = lines[lineNum];

                    // Check markdown links for broken references
                    Matcher matcher = MARKDOWN_LINK.matcher(line);
                    while (matcher.find()) {
                        String linkTarget = matcher.group(2);
                        if (linkTarget.startsWith("http") || linkTarget.startsWith("#")) continue;

                        String cleanTarget = linkTarget.contains("#")
                                ? linkTarget.substring(0, linkTarget.indexOf('#'))
                                : linkTarget;
                        if (cleanTarget.isEmpty()) continue;

                        Path targetPath = filePath.getParent().resolve(cleanTarget).normalize();
                        if (!Files.exists(targetPath)) {
                            Range range = new Range(
                                    new Position(lineNum, matcher.start(2)),
                                    new Position(lineNum, matcher.end(2))
                            );
                            Diagnostic diag = new Diagnostic(
                                    range,
                                    "Broken link: file not found - " + cleanTarget,
                                    DiagnosticSeverity.Warning,
                                    "synthesis"
                            );
                            diagnostics.add(diag);
                        }
                    }
                }

                client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
                log.fine("Published " + diagnostics.size() + " diagnostics for " + uri);
            } catch (Exception e) {
                log.fine("Diagnostics failed: " + e.getMessage());
            }
        });
    }

    // -----------------------------------------------------------------------
    // Utility Methods
    // -----------------------------------------------------------------------

    /**
     * Finds a file reference at the given column position in a line of text.
     */
    private String findReferenceAtPosition(String line, int col) {
        // Check markdown links
        Matcher mdMatcher = MARKDOWN_LINK.matcher(line);
        while (mdMatcher.find()) {
            if (col >= mdMatcher.start(2) && col <= mdMatcher.end(2)) {
                return mdMatcher.group(2);
            }
        }

        // Check generic file references
        Matcher refMatcher = FILE_REF_PATTERN.matcher(line);
        while (refMatcher.find()) {
            String ref = refMatcher.group(1);
            if (ref != null && col >= refMatcher.start(1) && col <= refMatcher.end(1)) {
                return ref;
            }
        }

        return null;
    }

    /**
     * Resolves a file reference to an absolute path.
     * Tries relative resolution first, then searches the index.
     */
    private Path resolveFileRef(String ref, Path fromFile) {
        if (ref == null || ref.isBlank()) return null;

        // Clean up the reference
        ref = ref.replace("\\", "/").trim();
        if (ref.startsWith("./")) ref = ref.substring(2);

        // Remove anchor fragments
        if (ref.contains("#")) ref = ref.substring(0, ref.indexOf('#'));
        if (ref.isEmpty()) return null;

        // Try relative resolution from the file's directory
        Path parent = fromFile.getParent();
        if (parent != null) {
            Path resolved = parent.resolve(ref).normalize();
            if (Files.exists(resolved)) return resolved;
        }

        // Try from workspace root
        if (workspaceRoot != null) {
            Path resolved = workspaceRoot.resolve(ref).normalize();
            if (Files.exists(resolved)) return resolved;
        }

        // Try searching the index by filename
        if (workspaceRoot != null) {
            String fileName = ref.contains("/") ? ref.substring(ref.lastIndexOf('/') + 1) : ref;
            try {
                WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
                if (workspace.validate().isEmpty()) {
                    try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                        List<SearchResult> results = index.search(fileName, 5);
                        for (SearchResult result : results) {
                            if (result.fileName().equals(fileName)) {
                                return result.path();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.fine("Index search for ref failed: " + e.getMessage());
            }
        }

        return null;
    }

    /**
     * Appends relationship statistics from the Synthesis index to hover content.
     */
    private void appendRelationshipStats(StringBuilder sb, Path targetPath) {
        if (workspaceRoot == null) return;

        try {
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            if (workspace.validate().isPresent()) return;

            try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                List<SearchResult> allFiles = index.listAll(null, 5000);

                SearchResult target = null;
                for (SearchResult r : allFiles) {
                    if (r.path().toAbsolutePath().equals(targetPath.toAbsolutePath())) {
                        target = r;
                        break;
                    }
                }

                if (target != null) {
                    RelateCommand relateCmd = new RelateCommand();
                    Map<String, List<String>> fileNameIndex = new HashMap<>();
                    for (SearchResult f : allFiles) {
                        fileNameIndex.computeIfAbsent(f.fileName(), k -> new ArrayList<>())
                                .add(f.relativePath());
                    }

                    RelateCommand.RelationshipMap relMap = new RelateCommand.RelationshipMap(target.relativePath());
                    relateCmd.analyzeOutgoingRefs(target, workspaceRoot, relMap, fileNameIndex);
                    relateCmd.analyzeIncomingRefs(target, allFiles, workspaceRoot, relMap);

                    int outgoing = relMap.outgoing().size();
                    int incoming = relMap.incoming().size();

                    if (outgoing + incoming > 0) {
                        sb.append("\n**Relationships:**\n");
                        sb.append("- ").append(outgoing).append(" outgoing references\n");
                        sb.append("- ").append(incoming).append(" incoming references\n");
                    }
                }
            }
        } catch (Exception e) {
            log.fine("Relationship stats failed: " + e.getMessage());
        }
    }
}
