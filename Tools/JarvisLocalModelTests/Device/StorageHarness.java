package org.telegram.messenger.jarvis;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

/** Dedicated test APK with no network permission and no real account credentials. */
public final class StorageHarness extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        final String runId = getIntent().getStringExtra("run_id");
        new Thread(() -> {
            JarvisLocalKeyStore first = new JarvisLocalKeyStore(this, 919159001);
            JarvisLocalKeyStore other = new JarvisLocalKeyStore(this, 919159002);
            try {
                first.delete(); other.delete();
                first.save(new JarvisLocalCredential(JarvisLocalCredential.Provider.OPENAI, "test-model", "synthetic-key"));
                JarvisLocalCredential loaded = new JarvisLocalKeyStore(this, 919159001).load();
                require(loaded != null && loaded.secret().equals("synthetic-key"));
                require(other.load() == null);
                first.save(new JarvisLocalCredential(JarvisLocalCredential.Provider.DEEPSEEK, "updated-model", "synthetic-updated"));
                require(first.load().provider == JarvisLocalCredential.Provider.DEEPSEEK);
                File encrypted = new File(getNoBackupFilesDir(), "talky.jarvis.local-model.v1.919159001");
                byte[] original = Files.readAllBytes(encrypted.toPath());
                byte[] tampered = original.clone(); tampered[tampered.length - 1] ^= 1;
                Files.write(encrypted.toPath(), tampered);
                boolean rejected = false;
                try { first.load(); } catch (IllegalStateException expected) { rejected = true; }
                require(rejected);
                Files.write(encrypted.toPath(), original);
                // Exercise AtomicFile's recovery from a committed legacy backup.
                File backup = new File(encrypted.getPath() + ".bak");
                Files.write(backup.toPath(), original); require(encrypted.delete());
                require(first.load() != null);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread[] writers = new Thread[8];
                for (int i = 0; i < writers.length; ++i) {
                    final int index = i;
                    writers[i] = new Thread(() -> {
                        try {
                            JarvisLocalKeyStore independent = new JarvisLocalKeyStore(this, 919159001);
                            independent.save(new JarvisLocalCredential(JarvisLocalCredential.Provider.OPENAI,
                                    "parallel-" + index, "synthetic-parallel-" + index));
                            require(independent.load() != null);
                        } catch (Throwable error) { failure.compareAndSet(null, error); }
                    });
                    writers[i].start();
                }
                for (Thread writer : writers) { writer.join(); }
                require(failure.get() == null);
                first.delete(); require(first.load() == null); first.delete();
                Log.i("JarvisStorageHarness", "JARVIS_KEYSTORE_DEVICE_TEST_PASS " + runId);
            } catch (Throwable ignored) {
                Log.e("JarvisStorageHarness", "JARVIS_KEYSTORE_DEVICE_TEST_FAIL " + runId);
            } finally {
                try { first.delete(); other.delete(); } catch (Exception ignored) { }
                runOnUiThread(this::finish);
            }
        }, "JarvisStorageTest").start();
    }
    private static void require(boolean value) {
        if (!value) { throw new AssertionError("Storage invariant failed"); }
    }
}
