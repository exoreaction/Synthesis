package io.exoreaction.synthesis.lsp;

import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.SearchIndex;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Synthesis LSP Server.
 */
class SynthesisLanguageServerTest {

    @TempDir
    Path tempDir;

    private SynthesisLanguageServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new SynthesisLanguageServer();
    }

    // -----------------------------------------------------------------------
    // Initialization Tests
    // -----------------------------------------------------------------------

    @Test
    void testInitializeReturnsCapabilities() throws Exception {
        InitializeParams params = new InitializeParams();
        params.setRootUri(tempDir.toUri().toString());

        CompletableFuture<InitializeResult> future = server.initialize(params);
        InitializeResult result = future.get();

        assertNotNull(result);
        assertNotNull(result.getCapabilities());

        ServerCapabilities caps = result.getCapabilities();
        // Workspace symbol support
        assertNotNull(caps.getWorkspaceSymbolProvider());
        // Document link support
        assertNotNull(caps.getDocumentLinkProvider());
        // Hover support
        assertNotNull(caps.getHoverProvider());
        // Definition support
        assertNotNull(caps.getDefinitionProvider());
        // References support
        assertNotNull(caps.getReferencesProvider());
        // Code lens support
        assertNotNull(caps.getCodeLensProvider());

        // Server info
        assertNotNull(result.getServerInfo());
        assertEquals("Synthesis Language Server", result.getServerInfo().getName());
    }

    @Test
    void testInitializeExtractsWorkspaceRoot() throws Exception {
        InitializeParams params = new InitializeParams();
        params.setRootUri(tempDir.toUri().toString());

        server.initialize(params).get();

        assertEquals(tempDir.toAbsolutePath().normalize(),
                server.getWorkspaceRoot().toAbsolutePath().normalize());
    }

    @Test
    void testInitializeWithWorkspaceFolders() throws Exception {
        InitializeParams params = new InitializeParams();
        WorkspaceFolder folder = new WorkspaceFolder(tempDir.toUri().toString(), "test");
        params.setWorkspaceFolders(List.of(folder));

        server.initialize(params).get();

        assertNotNull(server.getWorkspaceRoot());
    }

    @Test
    void testShutdownAndExit() throws Exception {
        CompletableFuture<Object> shutdown = server.shutdown();
        assertNotNull(shutdown);
        assertNull(shutdown.get()); // Should return null
    }

    // -----------------------------------------------------------------------
    // Workspace Symbol Tests
    // -----------------------------------------------------------------------

    @Test
    void testWorkspaceSymbolEmptyQuery() throws Exception {
        initWorkspace(tempDir);
        SynthesisWorkspaceService wsService = new SynthesisWorkspaceService(server);
        wsService.setWorkspaceRoot(tempDir);

        WorkspaceSymbolParams params = new WorkspaceSymbolParams("");
        Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> result =
                wsService.symbol(params).get();

        assertNotNull(result);
        assertTrue(result.isLeft());
        assertTrue(result.getLeft().isEmpty());
    }

    @Test
    void testWorkspaceSymbolWithQuery() throws Exception {
        initWorkspace(tempDir);
        // Create a test file
        Files.writeString(tempDir.resolve("test.md"), "# Hello World\nThis is a test file.");

        SynthesisWorkspaceService wsService = new SynthesisWorkspaceService(server);
        wsService.setWorkspaceRoot(tempDir);

        WorkspaceSymbolParams params = new WorkspaceSymbolParams("hello");
        Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> result =
                wsService.symbol(params).get();

        assertNotNull(result);
        assertTrue(result.isLeft());
        // May return empty if not indexed, but should not throw
    }

    @Test
    void testWorkspaceSymbolWithBlankQuery() throws Exception {
        SynthesisWorkspaceService wsService = new SynthesisWorkspaceService(server);
        wsService.setWorkspaceRoot(tempDir);

        // LSP4J WorkspaceSymbolParams does not accept null query,
        // so we test with blank string which should also return empty.
        WorkspaceSymbolParams params = new WorkspaceSymbolParams("   ");
        Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> result =
                wsService.symbol(params).get();

        assertNotNull(result);
        assertTrue(result.isLeft());
        assertTrue(result.getLeft().isEmpty());
    }

    // -----------------------------------------------------------------------
    // Document Link Tests
    // -----------------------------------------------------------------------

    @Test
    void testDocumentLinkWithMarkdownFile() throws Exception {
        // Create two test files
        Path sourceFile = tempDir.resolve("README.md");
        Path targetFile = tempDir.resolve("GUIDE.md");
        Files.writeString(sourceFile, "See [the guide](GUIDE.md) for details.");
        Files.writeString(targetFile, "# Guide\nContent here.");

        SynthesisTextDocumentService tdService = new SynthesisTextDocumentService(server);
        tdService.setWorkspaceRoot(tempDir);

        // Simulate opening the document
        DidOpenTextDocumentParams openParams = new DidOpenTextDocumentParams(
                new TextDocumentItem(sourceFile.toUri().toString(), "markdown", 1,
                        "See [the guide](GUIDE.md) for details."));
        tdService.didOpen(openParams);

        // Request document links
        DocumentLinkParams linkParams = new DocumentLinkParams(
                new TextDocumentIdentifier(sourceFile.toUri().toString()));
        List<DocumentLink> links = tdService.documentLink(linkParams).get();

        assertNotNull(links);
        assertFalse(links.isEmpty(), "Should find link to GUIDE.md");
        assertEquals(targetFile.toUri().toString(), links.get(0).getTarget());
    }

    @Test
    void testDocumentLinkWithBrokenLink() throws Exception {
        Path sourceFile = tempDir.resolve("README.md");
        Files.writeString(sourceFile, "See [nonexistent](nonexistent.md) file.");

        SynthesisTextDocumentService tdService = new SynthesisTextDocumentService(server);
        tdService.setWorkspaceRoot(tempDir);

        DidOpenTextDocumentParams openParams = new DidOpenTextDocumentParams(
                new TextDocumentItem(sourceFile.toUri().toString(), "markdown", 1,
                        "See [nonexistent](nonexistent.md) file."));
        tdService.didOpen(openParams);

        DocumentLinkParams linkParams = new DocumentLinkParams(
                new TextDocumentIdentifier(sourceFile.toUri().toString()));
        List<DocumentLink> links = tdService.documentLink(linkParams).get();

        assertNotNull(links);
        // Broken link should not produce a document link
        assertTrue(links.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Hover Tests
    // -----------------------------------------------------------------------

    @Test
    void testHoverOverFileReference() throws Exception {
        Path targetFile = tempDir.resolve("GUIDE.md");
        Files.writeString(targetFile, "# Guide\nSome content.");

        Path sourceFile = tempDir.resolve("README.md");
        Files.writeString(sourceFile, "See [guide](GUIDE.md) for details.");

        SynthesisTextDocumentService tdService = new SynthesisTextDocumentService(server);
        tdService.setWorkspaceRoot(tempDir);

        DidOpenTextDocumentParams openParams = new DidOpenTextDocumentParams(
                new TextDocumentItem(sourceFile.toUri().toString(), "markdown", 1,
                        "See [guide](GUIDE.md) for details."));
        tdService.didOpen(openParams);

        // Hover over "GUIDE.md" (column 12-20)
        HoverParams hoverParams = new HoverParams(
                new TextDocumentIdentifier(sourceFile.toUri().toString()),
                new Position(0, 14));
        Hover hover = tdService.hover(hoverParams).get();

        assertNotNull(hover, "Should get hover for file reference");
        assertNotNull(hover.getContents());
    }

    // -----------------------------------------------------------------------
    // Go to Definition Tests
    // -----------------------------------------------------------------------

    @Test
    void testGoToDefinition() throws Exception {
        Path targetFile = tempDir.resolve("GUIDE.md");
        Files.writeString(targetFile, "# Guide");

        Path sourceFile = tempDir.resolve("README.md");
        Files.writeString(sourceFile, "See [guide](GUIDE.md) for details.");

        SynthesisTextDocumentService tdService = new SynthesisTextDocumentService(server);
        tdService.setWorkspaceRoot(tempDir);

        DidOpenTextDocumentParams openParams = new DidOpenTextDocumentParams(
                new TextDocumentItem(sourceFile.toUri().toString(), "markdown", 1,
                        "See [guide](GUIDE.md) for details."));
        tdService.didOpen(openParams);

        DefinitionParams defParams = new DefinitionParams(
                new TextDocumentIdentifier(sourceFile.toUri().toString()),
                new Position(0, 14));
        Either<List<? extends Location>, List<? extends LocationLink>> result =
                tdService.definition(defParams).get();

        assertNotNull(result);
        assertTrue(result.isLeft());
        assertFalse(result.getLeft().isEmpty(), "Should navigate to GUIDE.md");
        assertTrue(result.getLeft().get(0).getUri().contains("GUIDE.md"));
    }

    // -----------------------------------------------------------------------
    // Helper Methods
    // -----------------------------------------------------------------------

    private void initWorkspace(Path root) throws IOException {
        Path synthesisDir = root.resolve(".synthesis");
        Files.createDirectories(synthesisDir.resolve("index"));
        Files.createDirectories(synthesisDir.resolve("reports"));
        Files.writeString(synthesisDir.resolve("config.yaml"),
                "name: test-workspace\ntype: general\n");
    }
}
