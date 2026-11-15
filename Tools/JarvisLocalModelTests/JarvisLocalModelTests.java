package org.telegram.messenger.jarvis;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class JarvisLocalModelTests {
    private static void check(boolean value, String label) {
        if (!value) { throw new AssertionError(label); }
    }
    private static void failure(JarvisLocalModelClient.Failure expected, int status, String data) throws Exception {
        try {
            JarvisLocalModelClient.response(status, data.getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("Provider failure accepted");
        } catch (JarvisLocalModelClient.ModelException error) {
            check(error.failure == expected, "Wrong failure category");
            check(!error.toString().contains(data), "Raw provider response leaked");
        }
    }
    public static void main(String[] args) throws Exception {
        JarvisLocalCredential first = new JarvisLocalCredential(JarvisLocalCredential.Provider.OPENAI, "test-model", "synthetic-key");
        JarvisLocalCredential second = new JarvisLocalCredential(JarvisLocalCredential.Provider.DEEPSEEK, "deepseek-chat", "second-synthetic-key");
        check(first.provider.endpoint.startsWith("https://api.openai.com/"), "Wrong provider endpoint");
        check(second.provider.endpoint.startsWith("https://api.deepseek.com/"), "Wrong provider endpoint");
        check(!first.secret().equals(second.secret()), "Cross-credential key reuse");
        for (String invalid : new String[]{"", " ", "a\r\nb"}) {
            try { new JarvisLocalCredential(JarvisLocalCredential.Provider.OPENAI, "test", invalid); throw new AssertionError("Invalid key accepted"); }
            catch (IllegalArgumentException expected) { check(!expected.getMessage().contains("synthetic"), "Credential leaked"); }
        }
        byte[] payload = JarvisLocalModelClient.body(first, "[channel:7:2] Proposal", "What was decided?");
        JSONObject body = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        check(!body.has("key") && !body.toString().contains(first.secret()), "Key leaked into model input");
        check(body.getJSONArray("messages").getJSONObject(1).getString("content").contains("channel:7:2"), "Lost evidence ID");
        String answer = JarvisLocalModelClient.response(200, "{\"choices\":[{\"message\":{\"content\":\"Proposal [channel:7:2]\"}}]}".getBytes(StandardCharsets.UTF_8));
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
        check(JarvisManageClient.Mode.parse("local_byok") == JarvisManageClient.Mode.LOCAL, "Wrong mode contract");
        try { new JarvisManageClient("http://service.example"); throw new AssertionError("Insecure origin accepted"); }
        catch (IllegalArgumentException expected) { }
        try { manage.endpoint("hosted_key"); throw new AssertionError("Local client can upload provider keys"); }
        catch (IllegalArgumentException expected) { }
        for (int status : new int[]{301, 401, 403, 409, 500}) {
            try { JarvisManageClient.validateStatus(status); throw new AssertionError("Management failure accepted"); }
            catch (JarvisManageClient.ManageException expected) { }
        }
        System.out.println("Jarvis local model Android core checks passed");
    }
}
