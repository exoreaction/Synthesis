package io.exoreaction.synthesis.lsp;

import io.exoreaction.synthesis.util.Version;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.logging.*;

/**
 * Synthesis Language Server implementing the Language Server Protocol (LSP 3.17).
 *
 * <p>Provides IDE integration for Synthesis functionality:
 * <ul>
 *   <li><b>Workspace Symbols</b> (Cmd+T / Ctrl+T) -- Search across workspace</li>
 *   <li><b>Document Links</b> -- Clickable references between files</li>
 *   <li><b>Hover</b> -- File metadata and relationship stats on hover</li>
 *   <li><b>Diagnostics</b> -- Warn about broken links</li>
 * </ul>
 *
 * <h2>IDE Setup</h2>
 *
 * <h3>VSCode</h3>
 * <pre>
 * // .vscode/settings.json
 * {
 *   "synthesis.server.path": "synthesis-lsp-server",
 *   "synthesis.workspace": "/path/to/workspace"
 * }
 * </pre>
 *
 * <h3>IntelliJ</h3>
 * Uses the IntelliJ LSP support (2023.2+) or the LSP4IJ plugin.
 *
 * <h3>Vim/Neovim (coc.nvim or nvim-lspconfig)</h3>
 * <pre>
 * lua require('lspconfig').synthesis.setup{ cmd = { "synthesis-lsp-server" } }
 * </pre>
 *
 * @author Thor Henning Hetland / eXOReaction
 * @see <a href="https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/">LSP 3.17</a>
 */
public class SynthesisLanguageServer implements LanguageServer, LanguageClientAware {

    private final SynthesisTextDocumentService textDocumentService;
    private final SynthesisWorkspaceService workspaceService;
    private final Logger log;

    private LanguageClient client;
    private Path workspaceRoot;
    private int errorCode = 1;

    public SynthesisLanguageServer() {
        this.log = Logger.getLogger("io.exoreaction.synthesis.lsp");
        this.textDocumentService = new SynthesisTextDocumentService(this);
        this.workspaceService = new SynthesisWorkspaceService(this);
    }

    // -----------------------------------------------------------------------
    // Entry Point
    // -----------------------------------------------------------------------

    /**
     * Launches the LSP server over stdio.
     */
    public static void main(String[] args) {
        Path defaultWorkspace = Path.of(".").toAbsolutePath().normalize();
        String logLevel = "WARNING";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--workspace", "-w" -> {
                    if (i + 1 < args.length) {
                        defaultWorkspace = Path.of(args[++i]).toAbsolutePath().normalize();
                    }
                }
                case "--log-level" -> {
                    if (i + 1 < args.length) {
                        logLevel = args[++i].toUpperCase();
                    }
                }
                case "--version", "-v" -> {
                    System.err.println(Version.getFullVersion() + " (LSP Server)");
                    System.exit(0);
                }
                case "--help", "-h" -> {
                    printHelp();
                    System.exit(0);
                }
            }
        }

        setupLogging(logLevel);

        Logger log = Logger.getLogger("io.exoreaction.synthesis.lsp");
        log.info("Starting Synthesis LSP Server v" + Version.getVersion());
        log.info("Workspace: " + defaultWorkspace);

        SynthesisLanguageServer server = new SynthesisLanguageServer();
        server.workspaceRoot = defaultWorkspace;

        InputStream in = System.in;
        OutputStream out = System.out;

        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, in, out);
        LanguageClient client = launcher.getRemoteProxy();
        server.connect(client);

        launcher.startListening();
    }

    // -----------------------------------------------------------------------
    // LanguageServer Interface
    // -----------------------------------------------------------------------

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        log.info("LSP Initialize request received");

        // Extract workspace root from params
        if (params.getRootUri() != null) {
            String uri = params.getRootUri();
            if (uri.startsWith("file://")) {
                workspaceRoot = Path.of(java.net.URI.create(uri)).toAbsolutePath().normalize();
                log.info("Workspace from client: " + workspaceRoot);
            }
        } else if (params.getWorkspaceFolders() != null && !params.getWorkspaceFolders().isEmpty()) {
            String uri = params.getWorkspaceFolders().get(0).getUri();
            if (uri.startsWith("file://")) {
                workspaceRoot = Path.of(java.net.URI.create(uri)).toAbsolutePath().normalize();
                log.info("Workspace from folder: " + workspaceRoot);
            }
        }

        // Initialize services with workspace
        textDocumentService.setWorkspaceRoot(workspaceRoot);
        workspaceService.setWorkspaceRoot(workspaceRoot);

        // Server capabilities
        ServerCapabilities capabilities = new ServerCapabilities();

        // Workspace symbol support (Cmd+T search)
        capabilities.setWorkspaceSymbolProvider(true);

        // Document link support (clickable references)
        capabilities.setDocumentLinkProvider(new DocumentLinkOptions(true));

        // Hover support (file metadata on hover)
        capabilities.setHoverProvider(true);

        // Text document sync (need to track open files for diagnostics)
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental);

        // Go to definition (navigate to referenced files)
        capabilities.setDefinitionProvider(true);

        // Find references
        capabilities.setReferencesProvider(true);

        // Code lens (inline relationship counts)
        capabilities.setCodeLensProvider(new CodeLensOptions(true));

        ServerInfo serverInfo = new ServerInfo("Synthesis Language Server", Version.getVersion());
        InitializeResult result = new InitializeResult(capabilities, serverInfo);

        log.info("LSP Server initialized. Capabilities registered.");
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public void initialized(InitializedParams params) {
        log.info("LSP client initialized notification received");

        // Optionally: send initial diagnostics for the workspace
        if (client != null) {
            log.info("Client connected, ready for requests");
        }
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        log.info("LSP shutdown requested");
        errorCode = 0;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        log.info("LSP exit notification, shutting down");
        System.exit(errorCode);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    // -----------------------------------------------------------------------
    // LanguageClientAware
    // -----------------------------------------------------------------------

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
        textDocumentService.setClient(client);
        workspaceService.setClient(client);
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public LanguageClient getClient() {
        return client;
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    public Logger getLog() {
        return log;
    }

    // -----------------------------------------------------------------------
    // Logging Setup
    // -----------------------------------------------------------------------

    private static void setupLogging(String logLevel) {
        Logger logger = Logger.getLogger("io.exoreaction.synthesis.lsp");
        logger.setLevel(Level.parse(logLevel));

        try {
            Path logDir = Path.of(System.getProperty("user.home"), ".synthesis", "logs");
            java.nio.file.Files.createDirectories(logDir);

            FileHandler fileHandler = new FileHandler(
                    logDir.resolve("lsp-server.log").toString(),
                    5_000_000, 3, true);
            fileHandler.setFormatter(new SimpleFormatter());
            logger.addHandler(fileHandler);

            // Remove console handlers to avoid interfering with LSP stdio
            Logger rootLogger = Logger.getLogger("");
            for (Handler handler : rootLogger.getHandlers()) {
                if (handler instanceof ConsoleHandler) {
                    rootLogger.removeHandler(handler);
                }
            }
            logger.setUseParentHandlers(false);
        } catch (Exception e) {
            System.err.println("Warning: Could not set up file logging: " + e.getMessage());
        }
    }

    private static void printHelp() {
        System.err.println("Synthesis LSP Server v" + Version.getVersion());
        System.err.println();
        System.err.println("Usage: synthesis-lsp-server [OPTIONS]");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  --workspace, -w <path>  Default workspace root (overridden by IDE)");
        System.err.println("  --log-level <level>     Logging level: FINE, INFO, WARNING, SEVERE");
        System.err.println("  --version, -v           Print version and exit");
        System.err.println("  --help, -h              Print this help and exit");
        System.err.println();
        System.err.println("LSP Protocol: JSON-RPC 2.0 over stdio (LSP 3.17)");
        System.err.println("Features: workspace symbols, document links, hover, diagnostics,");
        System.err.println("          go to definition, find references, code lens");
    }
}
