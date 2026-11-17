package org.telegram.messenger.jarvis;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Device-only credentials; never pass this object to Talky request serializers.
 *
 * A credential is a provider preset plus the concrete transport it uses:
 * a Base URL, an API format (OpenAI chat completions, Anthropic messages or
 * Gemini generateContent), one or more model names and the device-held key.
 * The Base URL may point at a proxy or gateway, which is why the format is
 * stored separately from the preset.
 */
public final class JarvisLocalCredential {
    /** Stable server-side identity of the built-in @jarvis account. */
    public static final long BOT_USER_ID = 1250000025L;

    public static final int MAX_MODELS = 20;
    public static final int MAX_MODEL_LENGTH = 200;
    public static final int MAX_BASE_URL_LENGTH = 300;

    public enum Format {
        OPENAI("OpenAI Chat Completions", "/chat/completions"),
        ANTHROPIC("Anthropic Messages", "/messages"),
        GEMINI("Gemini generateContent", ":generateContent");

        public final String label;
        /** Suffix this format expects after the Base URL. */
        public final String suffix;

        Format(String label, String suffix) {
            this.label = label;
            this.suffix = suffix;
        }

        public static Format parse(String value) {
            if (value != null) {
                String normalized = value.trim().toUpperCase(Locale.ROOT);
                for (Format format : values()) {
                    if (format.name().equals(normalized)) {
                        return format;
                    }
                }
            }
            return null;
        }
    }

    /** Presets only prefill the form; the stored credential keeps the concrete values. */
    public enum Provider {
        OPENAI(Format.OPENAI, "https://api.openai.com/v1", "gpt-4o-mini"),
        DEEPSEEK(Format.OPENAI, "https://api.deepseek.com", "deepseek-chat"),
        ANTHROPIC(Format.ANTHROPIC, "https://api.anthropic.com/v1", "claude-3-5-haiku-latest"),
        GEMINI(Format.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "gemini-2.0-flash"),
        CUSTOM(Format.OPENAI, "", "");

        public final Format format;
        public final String baseUrl;
        public final String model;

        Provider(Format format, String baseUrl, String model) {
            this.format = format;
            this.baseUrl = baseUrl;
            this.model = model;
        }

        public static Provider parse(String value) {
            if (value != null) {
                String normalized = value.trim().toUpperCase(Locale.ROOT);
                for (Provider provider : values()) {
                    if (provider.name().equals(normalized)) {
                        return provider;
                    }
                }
            }
            return CUSTOM;
        }
    }

    public final Provider provider;
    public final Format format;
    public final String baseUrl;
    /** Non-empty, de-duplicated, in the order the user entered them. */
    public final List<String> models;
    /** The model used for requests; always one of {@link #models}. */
    public final String model;
    private final String key;

    /** Legacy shorthand: preset transport with a single model. */
    public JarvisLocalCredential(Provider provider, String model, String key) {
        this(provider, provider != null ? provider.format : null,
                provider != null ? provider.baseUrl : null,
                null, model, key);
    }

    public JarvisLocalCredential(Provider provider, Format format, String baseUrl,
                                 List<String> models, String selectedModel, String key) {
        if (provider == null || format == null) {
            throw new IllegalArgumentException("Invalid local model configuration");
        }
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        List<String> normalizedModels = normalizeModels(
                models != null ? models : Collections.singletonList(selectedModel));
        String normalizedSelected = normalizeModel(selectedModel);
        if (!normalizedModels.contains(normalizedSelected)) {
            normalizedSelected = normalizedModels.get(0);
        }
        this.provider = provider;
        this.format = format;
        this.baseUrl = normalizedBaseUrl;
        this.models = Collections.unmodifiableList(normalizedModels);
        this.model = normalizedSelected;
        this.key = normalizeKey(key);
    }

    private static String normalizeKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException("Invalid local model configuration");
        }
        key = key.trim();
        if (key.isEmpty() || key.length() > 4096) {
            throw new IllegalArgumentException("Invalid local model configuration");
        }
        for (int i = 0; i < key.length(); i++) {
            if (Character.isISOControl(key.charAt(i))) {
                throw new IllegalArgumentException("Invalid local model configuration");
            }
        }
        return key;
    }

    private static String normalizeModel(String model) {
        if (model == null) {
            throw new IllegalArgumentException("Invalid local model configuration");
        }
        model = model.trim();
        if (model.isEmpty() || model.length() > MAX_MODEL_LENGTH) {
            throw new IllegalArgumentException("Invalid local model configuration");
        }
        for (int i = 0; i < model.length(); i++) {
            char c = model.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                throw new IllegalArgumentException("Invalid local model configuration");
            }
        }
        return model;
    }

    private static List<String> normalizeModels(List<String> models) {
        if (models == null) {
            throw new IllegalArgumentException("Invalid local model configuration");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String candidate : models) {
            unique.add(normalizeModel(candidate));
        }
        if (unique.isEmpty() || unique.size() > MAX_MODELS) {
            throw new IllegalArgumentException("Invalid local model configuration");
        }
        return new ArrayList<>(unique);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            throw new IllegalArgumentException("Invalid local model configuration");
        }
        baseUrl = baseUrl.trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (baseUrl.isEmpty() || baseUrl.length() > MAX_BASE_URL_LENGTH) {
            throw new IllegalArgumentException("Invalid local model configuration");
        }
        String lower = baseUrl.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            throw new IllegalArgumentException("Invalid local model configuration");
        }
        for (int i = 0; i < baseUrl.length(); i++) {
            char c = baseUrl.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                throw new IllegalArgumentException("Invalid local model configuration");
            }
        }
        return baseUrl;
    }

    /** Request URL for the selected model, without query parameters or secrets. */
    public String endpoint() {
        return endpointFor(model);
    }

    public String endpointFor(String requestModel) {
        String base = baseUrl;
        switch (format) {
            case ANTHROPIC:
                if (!base.endsWith("/messages")) {
                    base = base + "/messages";
                }
                return base;
            case GEMINI: {
                String encoded = URLEncoder.encode(requestModel, StandardCharsets.UTF_8).replace("+", "%20");
                if (base.contains("/models/")) {
                    if (base.endsWith(":generateContent")) {
                        return base;
                    }
                    return base + ":generateContent";
                }
                return base + "/models/" + encoded + ":generateContent";
            }
            case OPENAI:
            default:
                if (!base.endsWith("/chat/completions")) {
                    base = base + "/chat/completions";
                }
                return base;
        }
    }

    public boolean isCleartext() {
        return baseUrl.toLowerCase(Locale.ROOT).startsWith("http://");
    }

    // Package-private: only encrypted storage and direct provider transport use the key.
    String secret() {
        return key;
    }

    /**
     * Only for the on-device editor, which keeps the saved key when its field
     * is left untouched. The editor never displays it. Never serialize, log,
     * upload or pass this value to any Talky request.
     */
    public String secretForEditing() {
        return key;
    }
}
