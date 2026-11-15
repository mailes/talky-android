package org.telegram.messenger.jarvis;

/** Device-only credentials; never pass this object to Talky request serializers. */
public final class JarvisLocalCredential {
    public enum Provider {
        OPENAI("https://api.openai.com/v1/chat/completions"),
        DEEPSEEK("https://api.deepseek.com/chat/completions");
        public final String endpoint;
        Provider(String endpoint) { this.endpoint = endpoint; }
    }

    public final Provider provider;
    public final String model;
    private final String key;

    public JarvisLocalCredential(Provider provider, String model, String key) {
        if (provider == null || model == null || key == null) {
            throw new IllegalArgumentException("Invalid local model configuration");
        }
        model = model.trim();
        key = key.trim();
        if (model.isEmpty() || model.length() > 200 || key.isEmpty() || key.length() > 4096) {
            throw new IllegalArgumentException("Invalid local model configuration");
        }
        for (int i = 0; i < key.length(); i++) {
            if (Character.isISOControl(key.charAt(i))) {
                throw new IllegalArgumentException("Invalid local model configuration");
            }
        }
        this.provider = provider;
        this.model = model;
        this.key = key;
    }

    // Package-private: only encrypted storage and direct provider transport use the key.
    String secret() { return key; }
}
