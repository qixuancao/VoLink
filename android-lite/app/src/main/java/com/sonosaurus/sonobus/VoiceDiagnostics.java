// SPDX-License-Identifier: GPL-3.0-or-later
package com.sonosaurus.sonobus;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Metadata only. No audio samples, addresses, credentials or exception messages. */
final class VoiceDiagnostics {
    private final Context context;
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(120);
    private final String session = UUID.randomUUID().toString();
    private final AtomicLong dropped = new AtomicLong();
    volatile boolean enabled;
    volatile boolean uploading;
    volatile String lastError = "";

    VoiceDiagnostics(Context context) {
        this.context = context.getApplicationContext();
        enabled = context.getSharedPreferences("voice_ui", Context.MODE_PRIVATE)
            .getBoolean("diagnostics", true);
    }
    void setEnabled(boolean value) {
        enabled = value;
        context.getSharedPreferences("voice_ui", Context.MODE_PRIVATE).edit()
            .putBoolean("diagnostics", value).apply();
        if (!value) queue.clear();
        else event("diagnostics_enabled");
    }
    void event(String name, Object... fields) {
        if (!enabled) return;
        try {
            JSONObject item = new JSONObject();
            item.put("event", name); item.put("version", BuildConfig.VERSION_NAME);
            item.put("session", session); item.put("time_ms", System.currentTimeMillis());
            item.put("uptime_ms", SystemClock.elapsedRealtime());
            item.put("dropped_events", dropped.get());
            for (int i = 0; i + 1 < fields.length; i += 2)
                item.put((String) fields[i], fields[i + 1]);
            String line = item.toString();
            if (!queue.offer(line)) {
                queue.poll(); dropped.incrementAndGet(); queue.offer(line);
            }
        } catch (Exception ignored) {}
    }
    String poll() throws InterruptedException { return queue.poll(2, TimeUnit.SECONDS); }
    void retry(String line) { if (enabled && line != null) queue.offer(line); }
    String receiverCommand() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = context.getAssets().open("voice_diagnostics_receiver.py")) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = input.read(buffer)) >= 0) bytes.write(buffer, 0, n);
        }
        // Only bundled source is encoded here, never a user setting or password.
        String encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
        return "python3 -u -c 'import base64;exec(base64.b64decode(\"" + encoded + "\"))'";
    }
}
