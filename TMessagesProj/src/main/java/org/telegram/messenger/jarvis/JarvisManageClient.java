package org.telegram.messenger.jarvis;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/** Account-bound management transport. Never give this client a local model key. */
public final class JarvisManageClient {
    public enum Mode { PLATFORM("platform"), HOSTED("hosted_byok"), LOCAL("local_byok");
        public final String wire;
        Mode(String wire) { this.wire = wire; }
        public static Mode parse(String value) {
            for (Mode mode : values()) { if (mode.wire.equals(value)) { return mode; } }
            throw new IllegalArgumentException("Unsupported model mode");
        }
    }
    public enum Failure { INVALID_CONFIGURATION, AUTHENTICATION_REQUIRED, FORBIDDEN, HOSTED_KEY_REQUIRED, OPERATION_FAILED, UNAVAILABLE, INCOMPATIBLE_RESPONSE }
    public static final class ManageException extends Exception {
        private static final long serialVersionUID = 1L;
        public final Failure failure;
        ManageException(Failure failure) { super(failure.name()); this.failure = failure; }
    }
    private final URI origin;
    public JarvisManageClient(String trustedAccountOrigin) throws Exception {
        URI value = new URI(trustedAccountOrigin);
        if (!"https".equals(value.getScheme()) || value.getHost() == null || value.getUserInfo() != null
                || value.getQuery() != null || value.getFragment() != null
                || !(value.getPath().isEmpty() || "/".equals(value.getPath()))) {
            throw new IllegalArgumentException("Trusted HTTPS origin required");
        }
        origin = value;
    }
    private static boolean isAllowedAction(String action) {
        return action.equals("model_config") || action.equals("model_capabilities") || action.equals("overview")
                || action.equals("local_context") || action.equals("hosted_key") || action.equals("delete_hosted_key")
                || action.equals("grant") || action.equals("revoke");
    }
    private static boolean isAcknowledgement(String action) {
        return action.equals("delete_hosted_key") || action.equals("hosted_key")
                || action.equals("grant") || action.equals("revoke");
    }
    public URI endpoint(String action) throws Exception {
        return endpoint(action, null);
    }
    private URI endpoint(String action, String extraQuery) throws Exception {
        if (!isAllowedAction(action)) {
            throw new IllegalArgumentException("Unsupported management action");
        }
        String query = "action=" + action + (extraQuery == null || extraQuery.isEmpty() ? "" : "&" + extraQuery);
        return new URI("https", null, origin.getHost(), origin.getPort(), "/api/jarvis/manage", query, null);
    }
    public JSONObject capabilities(String ticket) throws ManageException {
        JSONObject result = call("model_capabilities", null, ticket, null);
        try {
            if (result.getInt("schema_version") != 1 || !(result.get("modes") instanceof JSONArray)) {
                throw new Exception();
            }
        } catch (Exception ignored) { throw new ManageException(Failure.INCOMPATIBLE_RESPONSE); }
        return result;
    }
    public JSONObject configuration(String ticket) throws ManageException {
        JSONObject result = call("model_config", null, ticket, null);
        try { Mode.parse(result.getString("mode")); result.getBoolean("hosted_key_present"); }
        catch (Exception ignored) { throw new ManageException(Failure.INCOMPATIBLE_RESPONSE); }
        return result;
    }
    public JSONObject select(String ticket, Mode mode) throws ManageException {
        try {
            JSONObject result = call("model_config", null, ticket, new JSONObject().put("mode", mode.wire));
            Mode.parse(result.getString("mode")); result.getBoolean("hosted_key_present");
            return result;
        }
        catch (ManageException error) { throw error; }
        catch (Exception ignored) { throw new ManageException(Failure.INCOMPATIBLE_RESPONSE); }
    }
    public JSONObject overview(String ticket) throws ManageException {
        JSONObject result = call("overview", null, ticket, null);
        try {
            if (result.getInt("schema_version") != 1
                    || !(result.get("chats") instanceof JSONArray)
                    || !(result.get("available") instanceof JSONArray)) {
                throw new Exception();
            }
        } catch (Exception ignored) { throw new ManageException(Failure.INCOMPATIBLE_RESPONSE); }
        return result;
    }
    public JSONObject context(String ticket, long peerId, String query) throws ManageException {
        try {
            if (peerId <= 0 || query == null || query.codePointCount(0, query.length()) > 200) {
                throw new ManageException(Failure.INVALID_CONFIGURATION);
            }
            JSONObject result = call("local_context", null, ticket, new JSONObject().put("peer_id", peerId).put("query", query));
            org.json.JSONArray sources = result.getJSONArray("sources");
            if (sources.length() > 24) { throw new ManageException(Failure.INCOMPATIBLE_RESPONSE); }
            for (int i = 0; i < sources.length(); ++i) {
                JSONObject source = sources.getJSONObject(i);
                long messageId = source.getLong("message_id");
                if (source.getLong("peer_id") != peerId || messageId <= 0
                        || !source.getString("peer_type").equals("channel")
                        || !source.getString("source_id").equals("channel:" + peerId + ":" + messageId)) {
                    throw new ManageException(Failure.INCOMPATIBLE_RESPONSE);
                }
                source.getString("text"); source.getBoolean("truncated");
            }
            return result;
        } catch (ManageException error) { throw error; }
        catch (Exception ignored) { throw new ManageException(Failure.INCOMPATIBLE_RESPONSE); }
    }
    public JSONObject hostedKey(String ticket, String provider, String model, String key) throws ManageException {
        provider = provider == null ? "" : provider.trim();
        model = model == null ? "" : model.trim();
        key = key == null ? "" : key.trim();
        if (provider.isEmpty() || provider.length() > 64
                || model.isEmpty() || model.length() > 200
                || key.isEmpty() || key.length() > 4096) {
            throw new ManageException(Failure.INVALID_CONFIGURATION);
        }
        for (int i = 0; i < key.length(); i++) {
            if (Character.isISOControl(key.charAt(i))) {
                throw new ManageException(Failure.INVALID_CONFIGURATION);
            }
        }
        try {
            JSONObject result = call("hosted_key", null, ticket,
                    new JSONObject().put("provider", provider).put("model", model).put("key", key));
            if (!result.optBoolean("ok", false)) { throw new ManageException(Failure.INCOMPATIBLE_RESPONSE); }
            return result;
        } catch (ManageException error) { throw error; }
        catch (Exception ignored) { throw new ManageException(Failure.INCOMPATIBLE_RESPONSE); }
    }
    public JSONObject grant(String ticket, long peerId) throws ManageException {
        return writeChannel("grant", ticket, peerId);
    }
    public JSONObject revoke(String ticket, long peerId) throws ManageException {
        return writeChannel("revoke", ticket, peerId);
    }
    private JSONObject writeChannel(String action, String ticket, long peerId) throws ManageException {
        if (peerId <= 0) { throw new ManageException(Failure.INVALID_CONFIGURATION); }
        try {
            JSONObject result = call(action, "peer_type=channel&peer_id=" + peerId, ticket, new JSONObject());
            if (!result.optBoolean("ok", false)) { throw new ManageException(Failure.INCOMPATIBLE_RESPONSE); }
            return result;
        } catch (ManageException error) { throw error; }
        catch (Exception ignored) { throw new ManageException(Failure.INCOMPATIBLE_RESPONSE); }
    }
    public JSONObject deleteHostedKey(String ticket) throws ManageException {
        JSONObject result = call("delete_hosted_key", null, ticket, new JSONObject());
        if (!result.optBoolean("ok", false)) { throw new ManageException(Failure.INCOMPATIBLE_RESPONSE); }
        return result;
    }
    public static void validateStatus(int status) throws ManageException {
        if (status == 401) { throw new ManageException(Failure.AUTHENTICATION_REQUIRED); }
        if (status == 403) { throw new ManageException(Failure.FORBIDDEN); }
        if (status == 409) { throw new ManageException(Failure.HOSTED_KEY_REQUIRED); }
        if (status == 422) { throw new ManageException(Failure.OPERATION_FAILED); }
        if (status < 200 || status >= 300) { throw new ManageException(Failure.UNAVAILABLE); }
    }
    private JSONObject call(String action, String extraQuery, String ticket, JSONObject body) throws ManageException {
        HttpURLConnection connection = null;
        try {
            if (ticket == null || ticket.isEmpty()) { throw new ManageException(Failure.AUTHENTICATION_REQUIRED); }
            for (int i = 0; i < ticket.length(); ++i) {
                if (Character.isISOControl(ticket.charAt(i))) { throw new ManageException(Failure.AUTHENTICATION_REQUIRED); }
            }
            connection = (HttpURLConnection) endpoint(action, extraQuery).toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestMethod(body == null ? "GET" : "POST");
            connection.setRequestProperty("Authorization", "Bearer " + ticket);
            if (body != null) {
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(payload); }
            }
            validateStatus(connection.getResponseCode());
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) { throw new ManageException(Failure.UNAVAILABLE); }
                    if (output.size() + count > 1048576) { throw new ManageException(Failure.INCOMPATIBLE_RESPONSE); }
                    output.write(buffer, 0, count);
                }
                JSONObject result = new JSONObject(output.toString("UTF-8"));
                if (!isAcknowledgement(action) && result.optInt("schema_version", -1) != 1) {
                    throw new ManageException(Failure.INCOMPATIBLE_RESPONSE);
                }
                return result;
            }
        } catch (ManageException error) { throw error; }
        catch (Exception ignored) { throw new ManageException(Failure.UNAVAILABLE); }
        finally { if (connection != null) { connection.disconnect(); } }
    }
}
