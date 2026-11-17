package org.telegram.messenger.jarvis;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONObject;

public final class JarvisLocalModelTests {
    private static void check(boolean value, String label) {
        if (!value) { throw new AssertionError(label); }
    }
    private static void failure(JarvisLocalModelClient.Failure expected, int status, String data) throws Exception {
        try {
            JarvisLocalModelClient.response(JarvisLocalCredential.Format.OPENAI, status, data.getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("Provider failure accepted");
        } catch (JarvisLocalModelClient.ModelException error) {
            check(error.failure == expected, "Wrong failure category");
            check(!error.toString().contains(data), "Raw provider response leaked");
        }
    }
    public static void main(String[] args) throws Exception {
        JarvisLocalCredential first = new JarvisLocalCredential(JarvisLocalCredential.Provider.OPENAI, "test-model", "synthetic-key");
        JarvisLocalCredential second = new JarvisLocalCredential(JarvisLocalCredential.Provider.DEEPSEEK, "deepseek-chat", "second-synthetic-key");
        check(first.endpoint().equals("https://api.openai.com/v1/chat/completions"), "Wrong provider endpoint");
        check(second.endpoint().equals("https://api.deepseek.com/chat/completions"), "Wrong provider endpoint");
        check(!first.secret().equals(second.secret()), "Cross-credential key reuse");
        for (String invalid : new String[]{"", " ", "a\r\nb"}) {
            try { new JarvisLocalCredential(JarvisLocalCredential.Provider.OPENAI, "test", invalid); throw new AssertionError("Invalid key accepted"); }
            catch (IllegalArgumentException expected) { check(!expected.getMessage().contains("synthetic"), "Credential leaked"); }
        }

        // Custom transport: Base URL, format and model list are independent of the preset.
        JarvisLocalCredential custom = new JarvisLocalCredential(
                JarvisLocalCredential.Provider.CUSTOM,
                JarvisLocalCredential.Format.ANTHROPIC,
                "https://gateway.example.com/v1/",
                Arrays.asList("claude-3-5-haiku-latest", "claude-sonnet-4-20250514", "claude-3-5-haiku-latest"),
                "claude-sonnet-4-20250514",
                "synthetic-custom-key");
        check(custom.endpoint().equals("https://gateway.example.com/v1/messages"), "Wrong custom Anthropic endpoint");
        check(custom.models.size() == 2, "Duplicate models not removed");
        check(custom.model.equals("claude-sonnet-4-20250514"), "Selected model lost");
        JSONObject anthropicBody = new JSONObject(new String(
                JarvisLocalModelClient.body(custom, "[channel:7:2] Proposal", "What was decided?"),
                StandardCharsets.UTF_8));
        check(anthropicBody.getString("model").equals("claude-sonnet-4-20250514"), "Anthropic model missing");
        check(anthropicBody.has("system") && anthropicBody.getString("system").contains("source IDs"), "Anthropic system prompt missing");
        check(anthropicBody.getJSONArray("messages").getJSONObject(0).getString("content").contains("channel:7:2"), "Anthropic lost evidence");
        check(!anthropicBody.toString().contains(custom.secret()), "Key leaked into Anthropic body");
        String anthropicAnswer = JarvisLocalModelClient.response(JarvisLocalCredential.Format.ANTHROPIC, 200,
                "{\"content\":[{\"type\":\"text\",\"text\":\"Proposal [channel:7:2]\"}]}".getBytes(StandardCharsets.UTF_8));
        check(anthropicAnswer.contains("channel:7:2"), "Lost Anthropic answer");

        JarvisLocalCredential gemini = new JarvisLocalCredential(
                JarvisLocalCredential.Provider.GEMINI,
                JarvisLocalCredential.Format.GEMINI,
                "https://generativelanguage.googleapis.com/v1beta",
                Arrays.asList("gemini-2.0-flash"), "gemini-2.0-flash", "synthetic-gemini-key");
        check(gemini.endpoint().endsWith("/models/gemini-2.0-flash:generateContent"), "Wrong Gemini endpoint");
        JSONObject geminiBody = new JSONObject(new String(
                JarvisLocalModelClient.body(gemini, "evidence", "question"), StandardCharsets.UTF_8));
        check(geminiBody.has("systemInstruction") && geminiBody.has("contents"), "Gemini body shape wrong");
        check(geminiBody.getJSONObject("generationConfig").getInt("maxOutputTokens") == 2048, "Gemini output limit missing");
        String geminiAnswer = JarvisLocalModelClient.response(JarvisLocalCredential.Format.GEMINI, 200,
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Answer\"}]}}]}".getBytes(StandardCharsets.UTF_8));
        check(geminiAnswer.equals("Answer"), "Lost Gemini answer");

        try {
            new JarvisLocalCredential(JarvisLocalCredential.Provider.CUSTOM,
                    JarvisLocalCredential.Format.OPENAI, "ftp://example.com", Arrays.asList("m"), "m", "k");
            throw new AssertionError("Insecure base URL accepted");
        } catch (IllegalArgumentException expected) { }
        JarvisLocalCredential cleartext = new JarvisLocalCredential(
                JarvisLocalCredential.Provider.CUSTOM, JarvisLocalCredential.Format.OPENAI,
                "http://192.168.1.2:1234/v1", Arrays.asList("local-model"), "local-model", "k");
        check(cleartext.isCleartext(), "Cleartext URL not flagged");

        byte[] payload = JarvisLocalModelClient.body(first, "[channel:7:2] Proposal", "What was decided?");
        JSONObject body = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        check(!body.has("key") && !body.toString().contains(first.secret()), "Key leaked into model input");
        check(body.getJSONArray("messages").getJSONObject(1).getString("content").contains("channel:7:2"), "Lost evidence ID");
        String answer = JarvisLocalModelClient.response(JarvisLocalCredential.Format.OPENAI, 200,
                "{\"choices\":[{\"message\":{\"content\":\"Proposal [channel:7:2]\"}}]}".getBytes(StandardCharsets.UTF_8));
        check(answer.contains("channel:7:2"), "Lost answer evidence");
        failure(JarvisLocalModelClient.Failure.PROVIDER_REJECTED, 401, "private auth error");
        failure(JarvisLocalModelClient.Failure.PROVIDER_REJECTED, 403, "private forbidden error");
        failure(JarvisLocalModelClient.Failure.RATE_LIMITED, 429, "private rate error");
        failure(JarvisLocalModelClient.Failure.UNAVAILABLE, 302, "private redirect");
        failure(JarvisLocalModelClient.Failure.UNAVAILABLE, 500, "private provider error");
        failure(JarvisLocalModelClient.Failure.INVALID_RESPONSE, 200, "invalid json");
        failure(JarvisLocalModelClient.Failure.INVALID_RESPONSE, 200, "{\"choices\":[{\"message\":{\"content\":\"done\",\"tool_calls\":[]}}]}");
        try { JarvisLocalModelClient.body(first, new String(new char[100001]).replace('\0', 'x'), "Hi"); throw new AssertionError("Oversized context accepted"); }
        catch (JarvisLocalModelClient.ModelException expected) { check(expected.failure == JarvisLocalModelClient.Failure.INVALID_CONFIGURATION, "Wrong size error"); }
        JarvisManageClient manage = new JarvisManageClient("https://service.example");
        check(!manage.endpoint("local_context").toString().contains("ticket"), "Ticket must not enter URL");
        check(manage.endpoint("hosted_key").toString().contains("action=hosted_key"), "Hosted key action missing");
        check(!manage.endpoint("hosted_key").toString().contains("synthetic"), "Key must not enter URL");
        check(JarvisManageClient.Mode.parse("local_byok") == JarvisManageClient.Mode.LOCAL, "Wrong mode contract");
        try { new JarvisManageClient("http://service.example"); throw new AssertionError("Insecure origin accepted"); }
        catch (IllegalArgumentException expected) { }
        try { manage.endpoint("unknown_action"); throw new AssertionError("Unknown action accepted"); }
        catch (IllegalArgumentException expected) { }
        try { JarvisManageClient.validateStatus(422); throw new AssertionError("Provider rejection accepted"); }
        catch (JarvisManageClient.ManageException expected) { check(expected.failure == JarvisManageClient.Failure.OPERATION_FAILED, "Wrong 422 category"); }
        for (int status : new int[]{301, 401, 403, 409, 500}) {
            try { JarvisManageClient.validateStatus(status); throw new AssertionError("Management failure accepted"); }
            catch (JarvisManageClient.ManageException expected) { }
        }
        for (String command : new String[]{"/setkey", "/setkey@jarvis", "/setkey deepseek sk-synthetic", "  /setkey  "}) {
            check(JarvisCommandText.isSetKeyCommand(command), "Missed /setkey command: " + command);
        }
        for (String text : new String[]{"/setkeyfoo", "hello /setkey", "/set key", "", "setkey", "/setkey custom https://example model synthetic", "/setkey@jarvis custom https://example model synthetic"}) {
            check(!JarvisCommandText.isSetKeyCommand(text), "Swallowed ordinary text or custom command: " + text);
        }
        check("hello".equals(JarvisCommandText.askQuestion("/ask hello")), "Lost /ask question");
        check("hello".equals(JarvisCommandText.askQuestion("/ask@jarvis hello")), "Lost /ask@jarvis question");
        check("".equals(JarvisCommandText.askQuestion("/ask")), "Empty /ask must open the prompt");
        check("".equals(JarvisCommandText.askQuestion("/ask   ")), "Empty /ask must open the prompt");
        check(JarvisCommandText.askQuestion("/askfoo") == null, "Swallowed ordinary text");
        check(JarvisCommandText.askQuestion("hello /ask") == null, "Swallowed ordinary text");
        check(JarvisCommandText.askQuestion("") == null, "Swallowed empty text");
        JSONObject validation = new JSONObject(new String(JarvisLocalModelClient.validationBody(first), StandardCharsets.UTF_8));
        check(validation.getInt("max_tokens") == 1, "Validation must be minimal");
        check(!validation.has("key") && !validation.toString().contains(first.secret()), "Key leaked into validation body");
        System.out.println("Jarvis local model Android core checks passed");
    }
}
