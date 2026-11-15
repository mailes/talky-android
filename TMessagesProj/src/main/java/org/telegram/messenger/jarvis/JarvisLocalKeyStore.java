package org.telegram.messenger.jarvis;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Account-isolated encrypted storage excluded from Android backup. */
public final class JarvisLocalKeyStore {
    private static final java.util.Map<String, Object> locks = new java.util.HashMap<>();
    private final Object accountLock;
    private final String alias;
    private final AtomicFile file;

    public JarvisLocalKeyStore(Context context, long userId) {
        if (userId <= 0) { throw new IllegalArgumentException("Account required"); }
        alias = "talky.jarvis.local-model.v1." + userId;
        synchronized (locks) {
            Object existing = locks.get(alias);
            if (existing == null) { existing = new Object(); locks.put(alias, existing); }
            accountLock = existing;
        }
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), alias));
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(alias)) { return (SecretKey) store.getKey(alias, null); }
        if (!create) { throw new IllegalStateException("Local credential unavailable"); }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    public void save(JarvisLocalCredential credential) throws Exception {
        synchronized (accountLock) {
            JSONObject value = new JSONObject();
            value.put("provider", credential.provider.name());
            value.put("model", credential.model);
            value.put("key", credential.secret());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            // Authenticate the account and schema so encrypted files cannot be exchanged between accounts.
            cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            FileOutputStream output = null;
            try {
                output = file.startWrite();
                output.write(iv.length);
                output.write(iv);
                output.write(encrypted);
                file.finishWrite(output);
            } catch (Exception ignored) {
                if (output != null) { file.failWrite(output); }
                throw new IllegalStateException("Local credential unavailable");
            }
        }
    }

    public JarvisLocalCredential load() throws Exception {
        synchronized (accountLock) {
            if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) { return null; }
            try {
                byte[] stored = file.readFully();
                int ivLength = stored.length == 0 ? 0 : stored[0] & 255;
                if (ivLength != 12 || stored.length < 1 + ivLength + 16 || stored.length > 32768) {
                    throw new IllegalStateException();
                }
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, stored, 1, ivLength));
                cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
                byte[] plain = cipher.doFinal(stored, 1 + ivLength, stored.length - 1 - ivLength);
                JSONObject value = new JSONObject(new String(plain, StandardCharsets.UTF_8));
                return new JarvisLocalCredential(JarvisLocalCredential.Provider.valueOf(value.getString("provider")),
                        value.getString("model"), value.getString("key"));
            } catch (Exception ignored) {
                // Never include raw JSON, provider keys, or underlying exceptions in diagnostics.
                throw new IllegalStateException("Local credential unavailable");
            }
        }
    }

    public void delete() throws Exception {
        synchronized (accountLock) {
            // Delete the decryption key first; encrypted remnants cannot remain usable.
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            store.deleteEntry(alias);
            file.delete();
        }
    }
}
