package io.exoreaction.synthesis.notion;

import io.exoreaction.synthesis.notion.NotionTokenStore.NotionOAuthToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NotionOAuthClient} — OAuth authorization code exchange.
 *
 * <p>Uses the same stub HTTP client pattern as {@link NotionClientTest}.
 */
class NotionOAuthClientTest {

    @TempDir
    java.nio.file.Path tempDir;

    // -----------------------------------------------------------------------
    // 1. Successful code exchange returns correct token fields
    // -----------------------------------------------------------------------

    @Test
    void exchangeCode_success_returnsTokenWithCorrectFields() throws Exception {
        String responseJson = """
                {
                    "access_token": "ntn_oauth_abc123",
                    "token_type": "bearer",
                    "bot_id": "bot-id-456",
                    "workspace_name": "Test Workspace",
                    "workspace_id": "ws-id-789",
                    "owner": {"type": "user"}
                }
                """;

        StubHttpClient stub = new StubHttpClient((request) ->
                new StubHttpResponse(200, responseJson));

        var tokenFile = tempDir.resolve("notion-oauth.json");
        var tokenStore = new NotionTokenStore(tokenFile);
        var client = new NotionOAuthClient(stub, tokenStore);

        NotionOAuthToken token = client.exchangeCode("auth-code-xyz");

        assertEquals("ntn_oauth_abc123", token.accessToken());
        assertEquals("Test Workspace", token.workspaceName());
        assertEquals("ws-id-789", token.workspaceId());
        assertEquals("bot-id-456", token.botId());
        assertEquals(Long.MAX_VALUE, token.expiresAtEpochMs());
    }

    // -----------------------------------------------------------------------
    // 2. HTTP 400 response throws IOException
    // -----------------------------------------------------------------------

    @Test
    void exchangeCode_http400_throwsIOException() {
        String errorJson = "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid code\"}";

        StubHttpClient stub = new StubHttpClient((request) ->
                new StubHttpResponse(400, errorJson));

        var tokenFile = tempDir.resolve("notion-oauth.json");
        var tokenStore = new NotionTokenStore(tokenFile);
        var client = new NotionOAuthClient(stub, tokenStore);

        IOException ex = assertThrows(IOException.class,
                () -> client.exchangeCode("bad-code"));
        assertTrue(ex.getMessage().contains("400"));
        assertTrue(ex.getMessage().contains("invalid_grant"));
    }

    // -----------------------------------------------------------------------
    // 3. Token is saved via NotionTokenStore after exchange
    // -----------------------------------------------------------------------

    @Test
    void exchangeCode_success_tokenIsSavedToStore() throws Exception {
        String responseJson = """
                {
                    "access_token": "ntn_saved_token",
                    "token_type": "bearer",
                    "bot_id": "bot-saved",
                    "workspace_name": "Saved Workspace",
                    "workspace_id": "ws-saved",
                    "owner": {"type": "user"}
                }
                """;

        StubHttpClient stub = new StubHttpClient((request) ->
                new StubHttpResponse(200, responseJson));

        var tokenFile = tempDir.resolve("notion-oauth.json");
        var tokenStore = new NotionTokenStore(tokenFile);
        var client = new NotionOAuthClient(stub, tokenStore);

        client.exchangeCode("auth-code");

        // Verify the token was persisted
        assertTrue(tokenStore.exists());
        Optional<NotionOAuthToken> loaded = tokenStore.load();
        assertTrue(loaded.isPresent());
        assertEquals("ntn_saved_token", loaded.get().accessToken());
        assertEquals("Saved Workspace", loaded.get().workspaceName());
    }

    // -----------------------------------------------------------------------
    // 4. Request uses Basic auth with correct credentials
    // -----------------------------------------------------------------------

    @Test
    void exchangeCode_sendsBasicAuthHeader() throws Exception {
        String responseJson = """
                {
                    "access_token": "tok",
                    "token_type": "bearer",
                    "bot_id": "bot",
                    "workspace_name": "ws",
                    "workspace_id": "wsid",
                    "owner": {"type": "user"}
                }
                """;

        List<HttpRequest> capturedRequests = new ArrayList<>();

        StubHttpClient stub = new StubHttpClient((request) -> {
            capturedRequests.add(request);
            return new StubHttpResponse(200, responseJson);
        });

        var tokenFile = tempDir.resolve("notion-oauth.json");
        var tokenStore = new NotionTokenStore(tokenFile);
        var client = new NotionOAuthClient(stub, tokenStore);

        client.exchangeCode("code");

        assertEquals(1, capturedRequests.size());
        HttpRequest captured = capturedRequests.get(0);

        // Verify Basic auth header
        Optional<String> auth = captured.headers().firstValue("Authorization");
        assertTrue(auth.isPresent());

        String expectedCredentials = Base64.getEncoder().encodeToString(
                (NotionOAuthClient.CLIENT_ID + ":" + NotionOAuthClient.CLIENT_SECRET).getBytes());
        assertEquals("Basic " + expectedCredentials, auth.get());

        // Verify POST method
        assertEquals("POST", captured.method());

        // Verify URL
        assertEquals(NotionOAuthClient.TOKEN_ENDPOINT, captured.uri().toString());
    }

    // -----------------------------------------------------------------------
    // 5. HTTP 500 throws IOException
    // -----------------------------------------------------------------------

    @Test
    void exchangeCode_http500_throwsIOException() {
        StubHttpClient stub = new StubHttpClient((request) ->
                new StubHttpResponse(500, "Internal Server Error"));

        var tokenFile = tempDir.resolve("notion-oauth.json");
        var tokenStore = new NotionTokenStore(tokenFile);
        var client = new NotionOAuthClient(stub, tokenStore);

        IOException ex = assertThrows(IOException.class,
                () -> client.exchangeCode("code"));
        assertTrue(ex.getMessage().contains("500"));
    }

    // =====================================================================
    // Stub infrastructure (same pattern as NotionClientTest)
    // =====================================================================

    @FunctionalInterface
    interface RequestHandler {
        StubHttpResponse handle(HttpRequest request) throws IOException;
    }

    record StubHttpResponse(int statusCode, String body) {}

    static class StubHttpClient extends HttpClient {
        private final RequestHandler handler;

        StubHttpClient(RequestHandler handler) {
            this.handler = handler;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
                throws IOException, InterruptedException {
            StubHttpResponse stubResponse = handler.handle(request);
            return (HttpResponse<T>) new StubStringResponse(request, stubResponse.statusCode(), stubResponse.body());
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return null; }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_2; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> bodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> bodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }
    }

    static class StubStringResponse implements HttpResponse<String> {
        private final HttpRequest request;
        private final int statusCode;
        private final String body;

        StubStringResponse(HttpRequest request, int statusCode, String body) {
            this.request = request;
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override public int statusCode() { return statusCode; }
        @Override public HttpRequest request() { return request; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }
        @Override public String body() { return body; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public java.net.URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
    }
}
