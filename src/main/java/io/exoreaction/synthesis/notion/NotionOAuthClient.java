package io.exoreaction.synthesis.notion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.exoreaction.synthesis.notion.NotionTokenStore.NotionOAuthToken;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.logging.Logger;

/**
 * Handles the Notion OAuth 2.0 authorization code exchange.
 *
 * <p>After the user completes the browser-based OAuth consent flow,
 * this client exchanges the authorization code for an access token
 * by calling the Notion token endpoint with Basic authentication.
 *
 * <p>Notion OAuth tokens do not expire — there is no refresh_token
 * or expires_in field in the response.
 */
public class NotionOAuthClient {

    private static final Logger LOG = Logger.getLogger(NotionOAuthClient.class.getName());

    public static final String CLIENT_ID = "349d872b-594c-81aa-88c8-003703674ded";
    public static final String REDIRECT_URI = "https://localhost:54321/notion/callback";
    public static final String TOKEN_ENDPOINT = "https://api.notion.com/v1/oauth/token";

    /** Environment variable holding the Notion OAuth app's client secret. */
    public static final String CLIENT_SECRET_ENV_VAR = "NOTION_CLIENT_SECRET";

    private final HttpClient httpClient;
    private final NotionTokenStore tokenStore;
    private final ObjectMapper objectMapper;
    private final String clientSecret;

    /**
     * Creates an OAuth client with default HTTP client and token store.
     *
     * @throws IllegalStateException if {@value #CLIENT_SECRET_ENV_VAR} is not set
     */
    public NotionOAuthClient() {
        this(HttpClient.newHttpClient(), new NotionTokenStore());
    }

    /**
     * Creates an OAuth client with custom HTTP client and token store (for testing).
     *
     * <p>Resolves the client secret from the {@value #CLIENT_SECRET_ENV_VAR}
     * environment variable.
     *
     * @param httpClient the HTTP client to use for token exchange
     * @param tokenStore the store to persist the resulting token
     * @throws IllegalStateException if {@value #CLIENT_SECRET_ENV_VAR} is not set
     */
    public NotionOAuthClient(HttpClient httpClient, NotionTokenStore tokenStore) {
        this(httpClient, tokenStore, resolveClientSecret());
    }

    /**
     * Creates an OAuth client with an explicit client secret.
     *
     * <p>Primarily for tests, so they don't depend on {@value #CLIENT_SECRET_ENV_VAR}
     * being set in the test environment.
     *
     * @param httpClient   the HTTP client to use for token exchange
     * @param tokenStore   the store to persist the resulting token
     * @param clientSecret the Notion OAuth app's client secret
     */
    NotionOAuthClient(HttpClient httpClient, NotionTokenStore tokenStore, String clientSecret) {
        this.httpClient = httpClient;
        this.tokenStore = tokenStore;
        this.objectMapper = new ObjectMapper();
        this.clientSecret = clientSecret;
    }

    /**
     * Resolves the Notion OAuth app's client secret from the environment.
     *
     * @return the client secret
     * @throws IllegalStateException if {@value #CLIENT_SECRET_ENV_VAR} is unset or blank
     */
    private static String resolveClientSecret() {
        String secret = System.getenv(CLIENT_SECRET_ENV_VAR);
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "Notion OAuth client secret not configured. Set the "
                    + CLIENT_SECRET_ENV_VAR + " environment variable.");
        }
        return secret;
    }

    /**
     * Exchanges an authorization code for a Notion OAuth access token.
     *
     * <p>Sends a POST to the Notion token endpoint with:
     * <ul>
     *   <li>Basic auth header: base64(client_id:client_secret)</li>
     *   <li>Body: grant_type=authorization_code&amp;code=...&amp;redirect_uri=...</li>
     * </ul>
     *
     * <p>On success, parses the token response and persists it via {@link NotionTokenStore}.
     *
     * @param code the authorization code received from the OAuth callback
     * @return the parsed and stored OAuth token
     * @throws IOException          if the HTTP request fails or returns an error status
     * @throws InterruptedException if the thread is interrupted during the request
     */
    public NotionOAuthToken exchangeCode(String code) throws IOException, InterruptedException {
        String credentials = Base64.getEncoder().encodeToString(
                (CLIENT_ID + ":" + clientSecret).getBytes());

        String body = "grant_type=authorization_code"
                + "&code=" + code
                + "&redirect_uri=" + REDIRECT_URI;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_ENDPOINT))
                .header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            throw new IOException("Notion OAuth token exchange failed (HTTP "
                    + response.statusCode() + "): " + response.body());
        }

        JsonNode json = objectMapper.readTree(response.body());

        String accessToken = json.get("access_token").asText();
        String botId = json.get("bot_id").asText();
        String workspaceName = json.get("workspace_name").asText();
        String workspaceId = json.get("workspace_id").asText();

        var token = new NotionOAuthToken(
                accessToken,
                workspaceName,
                workspaceId,
                botId,
                Long.MAX_VALUE
        );

        tokenStore.save(token);
        LOG.info("Notion OAuth token saved for workspace: " + workspaceName);

        return token;
    }
}
