package io.exoreaction.synthesis.cli;

import com.sun.net.httpserver.HttpServer;
import io.exoreaction.synthesis.notion.NotionOAuthClient;
import io.exoreaction.synthesis.notion.NotionTokenStore.NotionOAuthToken;
import picocli.CommandLine.Command;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Authenticates with Notion via OAuth 2.0 authorization code flow.
 *
 * <p>Flow:
 * <ol>
 *   <li>Generate CSRF state parameter</li>
 *   <li>Start a local HTTP callback server on port 54321</li>
 *   <li>Open the Notion OAuth authorization URL in the browser</li>
 *   <li>Wait for the callback with the authorization code (120s timeout)</li>
 *   <li>Exchange the code for an access token</li>
 *   <li>Store the token locally</li>
 * </ol>
 *
 * <p>Usage: {@code synthesis notion auth}
 */
@Command(
        name = "auth",
        description = "Authenticate with Notion via OAuth",
        mixinStandardHelpOptions = true
)
public class NotionAuthCommand implements Callable<Integer> {

    private static final Logger LOG = Logger.getLogger(NotionAuthCommand.class.getName());

    private static final String AUTH_ENDPOINT = "https://api.notion.com/v1/oauth/authorize";
    private static final int CALLBACK_PORT = 54321;
    private static final int TIMEOUT_SECONDS = 120;

    private static final String SUCCESS_HTML = """
            <!DOCTYPE html>
            <html>
            <head><title>Synthesis — Notion Auth</title></head>
            <body style="font-family: system-ui, sans-serif; text-align: center; padding: 60px;">
                <h2>Authentication successful!</h2>
                <p>You can close this tab and return to the terminal.</p>
            </body>
            </html>
            """;

    private static final String ERROR_HTML = """
            <!DOCTYPE html>
            <html>
            <head><title>Synthesis — Notion Auth</title></head>
            <body style="font-family: system-ui, sans-serif; text-align: center; padding: 60px;">
                <h2>Authentication failed</h2>
                <p>%s</p>
                <p>Please try again.</p>
            </body>
            </html>
            """;

    @Override
    public Integer call() {
        String state = UUID.randomUUID().toString();
        String authUrl = buildAuthUrl(state);

        // Queue to receive the authorization code from the callback handler
        BlockingQueue<CallbackResult> resultQueue = new LinkedBlockingQueue<>();

        HttpServer server = null;
        try {
            // Start callback server
            server = HttpServer.create(new InetSocketAddress(CALLBACK_PORT), 0);
            server.createContext("/notion/callback", exchange -> {
                try {
                    String query = exchange.getRequestURI().getQuery();
                    var params = parseQuery(query);

                    String callbackState = params.state();
                    String code = params.code();
                    String error = params.error();

                    if (error != null && !error.isEmpty()) {
                        sendHtmlResponse(exchange, 400, String.format(ERROR_HTML, "Notion returned: " + error));
                        resultQueue.put(new CallbackResult(null, "Notion authorization denied: " + error));
                        return;
                    }

                    if (callbackState == null || !callbackState.equals(state)) {
                        sendHtmlResponse(exchange, 400, String.format(ERROR_HTML, "State mismatch (possible CSRF attack)"));
                        resultQueue.put(new CallbackResult(null, "State parameter mismatch"));
                        return;
                    }

                    if (code == null || code.isEmpty()) {
                        sendHtmlResponse(exchange, 400, String.format(ERROR_HTML, "No authorization code received"));
                        resultQueue.put(new CallbackResult(null, "No authorization code in callback"));
                        return;
                    }

                    sendHtmlResponse(exchange, 200, SUCCESS_HTML);
                    resultQueue.put(new CallbackResult(code, null));
                } catch (Exception e) {
                    LOG.warning("Callback handler error: " + e.getMessage());
                    try {
                        resultQueue.put(new CallbackResult(null, "Callback handler error: " + e.getMessage()));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            server.start();

            // Open browser and always print the URL for copy-paste fallback
            System.out.println("Authorization URL:");
            System.out.println();
            System.out.println("  " + authUrl);
            System.out.println();
            openBrowser(authUrl);
            System.out.println("Waiting for authorization (timeout: " + TIMEOUT_SECONDS + "s)...");

            // Wait for callback
            CallbackResult result = resultQueue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (result == null) {
                System.err.println("Error: Authorization timed out after " + TIMEOUT_SECONDS + " seconds.");
                return 1;
            }

            if (result.error() != null) {
                System.err.println("Error: " + result.error());
                return 1;
            }

            // Exchange code for token
            var oauthClient = new NotionOAuthClient();
            NotionOAuthToken token = oauthClient.exchangeCode(result.code());

            System.out.println();
            System.out.println("  Connected to Notion workspace: " + token.workspaceName());
            System.out.println("  Token stored in ~/.synthesis/notion-oauth.json");
            return 0;

        } catch (IOException e) {
            System.err.println("Error: Could not start callback server on port " + CALLBACK_PORT + ": " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Error: Authorization interrupted.");
            return 1;
        } finally {
            if (server != null) {
                server.stop(0);
            }
        }
    }

    /**
     * Builds the Notion OAuth authorization URL with all required parameters.
     *
     * @param state the CSRF protection state parameter
     * @return the full authorization URL
     */
    public static String buildAuthUrl(String state) {
        return AUTH_ENDPOINT
                + "?client_id=" + NotionOAuthClient.CLIENT_ID
                + "&response_type=code"
                + "&owner=user"
                + "&redirect_uri=" + URI.create(NotionOAuthClient.REDIRECT_URI).toASCIIString()
                + "&state=" + state;
    }

    /**
     * Attempts to open a URL in the system's default browser.
     *
     * @param url the URL to open
     * @return true if the browser was opened successfully
     */
    private boolean openBrowser(String url) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("linux")) {
                pb = new ProcessBuilder("xdg-open", url);
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", url);
            } else if (os.contains("win")) {
                pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
            } else {
                return false;
            }
            pb.redirectErrorStream(true);
            pb.start();
            return true;
        } catch (IOException e) {
            LOG.fine("Failed to open browser: " + e.getMessage());
            return false;
        }
    }

    private void sendHtmlResponse(com.sun.net.httpserver.HttpExchange exchange, int statusCode, String html)
            throws IOException {
        byte[] bytes = html.getBytes();
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Parses query parameters from the callback URL.
     */
    private QueryParams parseQuery(String query) {
        String code = null;
        String state = null;
        String error = null;

        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2) {
                    switch (kv[0]) {
                        case "code" -> code = kv[1];
                        case "state" -> state = kv[1];
                        case "error" -> error = kv[1];
                    }
                }
            }
        }
        return new QueryParams(code, state, error);
    }

    private record QueryParams(String code, String state, String error) {}
    private record CallbackResult(String code, String error) {}
}
