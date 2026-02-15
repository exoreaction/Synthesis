package io.exoreaction.synthesis.lsp;

import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Handles workspace-level LSP requests for Synthesis.
 *
 * <p>Primary feature: <b>Workspace Symbol Search</b> (Cmd+T / Ctrl+T in IDEs).
 * Searches across the entire Synthesis index and returns matching files as symbols.
 *
 * <p>Also handles workspace configuration changes and file watch events.
 */
public class SynthesisWorkspaceService implements WorkspaceService {

    private final Logger log;
    private final SynthesisLanguageServer server;

    private LanguageClient client;
    private Path workspaceRoot;

    public SynthesisWorkspaceService(SynthesisLanguageServer server) {
        this.server = server;
        this.log = Logger.getLogger("io.exoreaction.synthesis.lsp.workspace");
    }

    public void setClient(LanguageClient client) {
        this.client = client;
    }

    public void setWorkspaceRoot(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    // -----------------------------------------------------------------------
    // Workspace Symbol Search (Cmd+T / Ctrl+T)
    // -----------------------------------------------------------------------

    @Override
    public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(WorkspaceSymbolParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String query = params.getQuery();
            log.fine("Workspace symbol search: " + query);

            if (query == null || query.isBlank()) {
                return Either.forLeft(List.of());
            }

            List<SymbolInformation> symbols = new ArrayList<>();

            try {
                WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
                if (workspace.validate().isPresent()) {
                    log.warning("Workspace not initialized: " + workspaceRoot);
                    return Either.forLeft(List.of());
                }

                try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                    List<SearchResult> results = index.search(query, 50);

                    for (SearchResult result : results) {
                        SymbolInformation symbol = new SymbolInformation();
                        symbol.setName(result.fileName());
                        symbol.setKind(mapFileTypeToSymbolKind(result.fileType()));
                        symbol.setContainerName(result.relativePath());

                        // Location: file URI at line 1, column 1
                        String fileUri = result.path().toUri().toString();
                        Location location = new Location(
                                fileUri,
                                new Range(new Position(0, 0), new Position(0, 0))
                        );
                        symbol.setLocation(location);

                        symbols.add(symbol);
                    }
                }

                log.fine("Found " + symbols.size() + " symbols for: " + query);
            } catch (Exception e) {
                log.warning("Symbol search failed: " + e.getMessage());
            }

            return Either.forLeft(symbols);
        });
    }

    // -----------------------------------------------------------------------
    // Configuration Changes
    // -----------------------------------------------------------------------

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        log.fine("Configuration changed");
        // Could re-read workspace settings here
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        log.fine("Watched files changed: " + params.getChanges().size() + " changes");
        // Could trigger re-indexing here in the future
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    /**
     * Maps Synthesis file types to LSP SymbolKind for proper icon display in IDEs.
     */
    private SymbolKind mapFileTypeToSymbolKind(String fileType) {
        if (fileType == null) return SymbolKind.File;
        return switch (fileType) {
            case "CODE" -> SymbolKind.Class;
            case "MARKDOWN" -> SymbolKind.String;
            case "YAML", "JSON", "CONFIG" -> SymbolKind.Object;
            case "PDF", "DOCUMENT" -> SymbolKind.Constant;
            case "IMAGE" -> SymbolKind.Null;
            case "VIDEO", "AUDIO" -> SymbolKind.Event;
            default -> SymbolKind.File;
        };
    }
}
