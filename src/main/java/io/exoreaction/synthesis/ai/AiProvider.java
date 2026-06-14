package io.exoreaction.synthesis.ai;

import io.exoreaction.synthesis.config.CredentialStore;
import io.exoreaction.synthesis.config.SynthesisConfig;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Supported AI providers and their per-provider defaults.
 *
 * <p>{@code anthropic} uses the Anthropic SDK ({@link ClaudeClient}); {@code openai} and
 * {@code deepseek} speak the OpenAI Chat Completions protocol ({@link OpenAiClient}).
 * The {@code endpoint} config field can point the {@code openai} provider at any
 * OpenAI-compatible server.
 */
public enum AiProvider {

    ANTHROPIC("anthropic", null,
            "claude-sonnet-4-5-20250929", "claude-haiku-4-5-20251001", "ANTHROPIC_API_KEY"),
    OPENAI("openai", "https://api.openai.com/v1",
            "gpt-5", "gpt-5-mini", "OPENAI_API_KEY"),
    DEEPSEEK("deepseek", "https://api.deepseek.com",
            "deepseek-v4-flash", "deepseek-v4-flash", "DEEPSEEK_API_KEY");

    private final String id;
    private final String defaultEndpoint;
    private final String defaultModel;
    private final String fastModel;
    private final String apiKeyName;

    AiProvider(String id, String defaultEndpoint, String defaultModel,
               String fastModel, String apiKeyName) {
        this.id = id;
        this.defaultEndpoint = defaultEndpoint;
        this.defaultModel = defaultModel;
        this.fastModel = fastModel;
        this.apiKeyName = apiKeyName;
    }

    /**
     * Resolves a provider from its config value. Unknown or missing values
     * fall back to {@link #ANTHROPIC} for backward compatibility.
     */
    public static AiProvider fromId(String id) {
        return Optional.ofNullable(id)
                .map(String::trim)
                .flatMap(value -> Arrays.stream(values())
                        .filter(provider -> provider.id.equalsIgnoreCase(value))
                        .findFirst())
                .orElse(ANTHROPIC);
    }

    /** Resolves the provider declared in the AI config (defaults to {@link #ANTHROPIC}). */
    public static AiProvider forConfig(SynthesisConfig.AiConfig config) {
        return fromId(config.getProvider());
    }

    /** True when any provider's API key resolves (environment or credential store). */
    public static boolean anyKeyAvailable() {
        return Arrays.stream(values()).anyMatch(provider -> provider.resolveApiKey().isPresent());
    }

    public String id() { return id; }

    public String defaultEndpoint() { return defaultEndpoint; }

    /** Default model for general-purpose generation. */
    public String defaultModel() { return defaultModel; }

    /** Cheap/fast model for lightweight tasks (replaces hardcoded haiku names). */
    public String fastModel() { return fastModel; }

    /** Environment variable / credential store key holding this provider's API key. */
    public String apiKeyName() { return apiKeyName; }

    /**
     * Maps a configured model name onto this provider. Claude model names configured
     * for an OpenAI-compatible provider are substituted with the provider's defaults
     * (haiku variants map to {@link #fastModel()}) so a stale {@code model} entry
     * never reaches a non-Anthropic endpoint.
     */
    public String resolveModel(String configured) {
        Optional<String> requested = Optional.ofNullable(configured).filter(m -> !m.isBlank());
        if (this == ANTHROPIC) {
            return requested.orElse(defaultModel);
        }
        return requested
                .map(m -> !m.startsWith("claude") ? m
                        : m.contains("haiku") ? fastModel : defaultModel)
                .orElse(defaultModel);
    }

    /**
     * Resolves the API key: environment variable first, then the credential store
     * ({@code ~/.synthesis/credentials}).
     */
    public Optional<String> resolveApiKey() {
        return resolveApiKey(System::getenv);
    }

    Optional<String> resolveApiKey(UnaryOperator<String> env) {
        return Optional.ofNullable(env.apply(apiKeyName))
                .filter(key -> !key.isBlank())
                .or(() -> CredentialStore.retrieve(apiKeyName));
    }
}
