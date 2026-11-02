// SPDX-License-Identifier: GPL-3.0-or-later
package com.sonosaurus.sonobus;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.*;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.*;
import java.lang.ref.WeakReference;

public class SonoBusActivity extends Activity {
    private native void constructNativeClass();
    private native void destroyNativeClass();
    private native void appNewIntent(Intent intent);
    private native void appOnResume();
    private long cppCounterpartInstance;
    private VoiceTunnel tunnel;
    private long lastDiagnosticSnapshot;
    private volatile float inputPeak;
    private volatile int recordingSession;
    private AudioManager audio;
    private boolean changedSpeakerphone;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;
    private volatile String echoStatus = "系统通话模式";
    private boolean foregroundActive;
    private boolean activityResumed;
    private boolean connectAfterPermission;
    private AlertDialog hostKeyDialog;
    private VoiceTunnel.HostKeyRequest displayedHostKey;
    private static volatile boolean microphoneRequested = true;
    private static volatile String microphoneStatus = "正在准备麦克风";
    private static WeakReference<SonoBusActivity> currentActivity = new WeakReference<>(null);

    static boolean isMicrophoneRequested() { return microphoneRequested; }
    static String microphoneStatus() { return microphoneStatus; }
    static int currentConnectionState() {
        SonoBusActivity activity = currentActivity.get();
        return activity == null || activity.tunnel == null ? 0 : activity.tunnel.state;
    }
    static void disconnectFromTile() {
        SonoBusActivity activity = currentActivity.get();
        if (activity != null) activity.runOnUiThread(activity::stopVoiceTunnel);
    }
    static void toggleMicrophoneFromNotification() {
        SonoBusActivity activity = currentActivity.get();
        if (activity != null) activity.setMicrophoneRequested(!microphoneRequested);
    }
    public boolean getMicrophoneRequested() { return microphoneRequested; }
    public void setMicrophoneRequested(boolean enabled) {
        microphoneRequested = enabled;
        microphoneStatus = enabled ? "正在开启麦克风" : "正在关闭麦克风";
        runOnUiThread(() -> {
            if (foregroundActive) {
                Intent intent = new Intent(this, SonoBusService.class);
                startService(intent);
            }
        });
    }
    public void onMicrophoneCaptureChanged(boolean enabled, boolean success) {
        tunnel.diagnostics.event("capture_changed", "capture", enabled, "success", success);
        if (!enabled && success) releaseVoiceEffects();
        if (!success) echoStatus = enabled ? "无法开启麦克风，请返回应用重试" : "无法释放麦克风，请断开连接";
        else if (!enabled) echoStatus = "麦克风已关闭并释放";
        microphoneStatus = success ? (enabled ? "麦克风开启" : "麦克风关闭（已释放）")
                                   : "麦克风切换失败，请返回应用";
        runOnUiThread(() -> {
            if (foregroundActive) startService(new Intent(this, SonoBusService.class));
        });
    }
    public void onVoiceDiagnostics(int capture, boolean muted, boolean ptt, float peak, float rms,
                                   int peers, long tx, long rx) {
        inputPeak = peak;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastDiagnosticSnapshot < 5000) return;
        lastDiagnosticSnapshot = now;
        tunnel.diagnostics.event("audio", "capture", capture, "muted", muted, "ptt", ptt,
            "peak", peak, "rms", rms, "peers", peers, "native_tx_bytes", tx, "native_rx_bytes", rx,
            "foreground", activityResumed, "requested", microphoneRequested,
            "permission", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            "system_mic_muted", audio.isMicrophoneMute());
        tunnel.sampleTransport();
        if (tunnel.diagnostics.enabled) {
            try {
                boolean found = false;
                for (AudioRecordingConfiguration config : audio.getActiveRecordingConfigurations()) {
                    if (recordingSession <= 0 || config.getClientAudioSessionId() != recordingSession) continue;
                    found = true;
                    tunnel.diagnostics.event("recording_status", "recording_session", recordingSession,
                        "recording_active", true, "sample_rate", config.getClientFormat().getSampleRate(),
                        "audio_source", config.getClientAudioSource(),
                        "client_silenced", Build.VERSION.SDK_INT >= 29 ? (config.isClientSilenced() ? 1 : 0) : -1);
                }
                if (!found) tunnel.diagnostics.event("recording_status", "recording_session", recordingSession,
                    "recording_active", false);
            } catch (RuntimeException e) {
                tunnel.diagnostics.event("diagnostics_error", "phase", "recording_status", "error", e.getClass().getSimpleName());
            }
        }
    }
    public String getVoiceDiagnostics() {
        if (!tunnel.diagnostics.enabled) return "诊断日志已关闭";
        String delivery = tunnel.diagnostics.uploading ? "日志已连接电脑" : "日志等待回传";
        if (!tunnel.diagnostics.lastError.isEmpty()) delivery = "日志回传失败：" + tunnel.diagnostics.lastError;
        return String.format(java.util.Locale.ROOT, "麦克风电平 %.1f%% · %s\n%s", inputPeak * 100,
            tunnel.packetSummary(), delivery);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        currentActivity = new WeakReference<>(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        audio = (AudioManager)getSystemService(AUDIO_SERVICE);
        audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
        // Tablets should use the loudspeaker unless a headset is connected.
        if (!audio.isWiredHeadsetOn() && !audio.isBluetoothScoOn() && !audio.isSpeakerphoneOn()) {
            audio.setSpeakerphoneOn(true);
            changedSpeakerphone = true;
        }
        setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        tunnel = new VoiceTunnel(this);
        constructNativeClass();
    }

    public void startVoiceTunnel() {
        runOnUiThread(() -> {
            if (tunnel.state == 1 || tunnel.state == 2) return;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                connectAfterPermission = true;
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 71);
                return;
            }
            if (!tunnel.hasPassword()) { showVoiceSettings(); return; }
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                android.content.SharedPreferences ui = getSharedPreferences("voice_ui", MODE_PRIVATE);
                if (!ui.getBoolean("notification_requested", false)) {
                    ui.edit().putBoolean("notification_requested", true).apply();
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 72);
                }
            }
            setForegroundServiceActive(true);
            tunnel.start();
        });
    }
    public void stopVoiceTunnel() {
        tunnel.stop();
        setForegroundServiceActive(false);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 71 && connectAfterPermission) {
            connectAfterPermission = false;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
                startVoiceTunnel();
        }
        // The first notification may have been posted before Android 13's
        // permission prompt was answered. Repost it after permission is granted.
        if (requestCode == 72 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED
                && foregroundActive)
            startService(new Intent(this, SonoBusService.class));
    }
    public int getVoiceState() {
        int state = tunnel.state;
        if (state == 3) setForegroundServiceActive(false);
        return state;
    }
    public String getVoiceMessage() { return tunnel.message; }
    public String getVoiceEchoStatus() { return echoStatus; }

    void showHostKeyRequest(VoiceTunnel.HostKeyRequest request) {
        runOnUiThread(() -> {
            // Never present a trust prompt over another app or a destroyed activity.
            if (!request.isPending()) return;
            if (!activityResumed || isFinishing() || isDestroyed()) {
                request.respond(false);
                return;
            }
            if (hostKeyDialog != null) hostKeyDialog.cancel();
            String message = "电脑：" + request.host + ":" + request.port
                + "\n密钥类型：" + request.algorithm + "\n\n" + request.fingerprint
                + "\n\n请通过可信渠道与电脑的 SSH 指纹核对。只有确认一致后才继续连接。";
            AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("确认电脑的 SSH 指纹")
                .setMessage(message)
                .setPositiveButton("确认并连接", (d, which) -> request.respond(true))
                .setNegativeButton("取消连接", (d, which) -> request.respond(false))
                .create();
            hostKeyDialog = dialog;
            displayedHostKey = request;
            dialog.setOnDismissListener(d -> {
                request.respond(false);
                if (displayedHostKey == request) { hostKeyDialog = null; displayedHostKey = null; }
            });
            dialog.show();
        });
    }
    void dismissHostKeyRequest(VoiceTunnel.HostKeyRequest request) {
        runOnUiThread(() -> {
            if (displayedHostKey == request && hostKeyDialog != null) hostKeyDialog.dismiss();
        });
    }

    public void showVoiceSettings() {
        runOnUiThread(() -> {
            if (tunnel.state == 1 || tunnel.state == 2) {
                Toast.makeText(this, "请先断开连接再修改设置", Toast.LENGTH_LONG).show();
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert);
            Context dialogContext = builder.getContext();
            LinearLayout box = new LinearLayout(dialogContext);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundColor(Color.WHITE);
            int pad = (int)(24 * getResources().getDisplayMetrics().density);
            box.setPadding(pad, pad / 2, pad, 0);
            TextView host = new TextView(dialogContext);
            host.setTextColor(Color.BLACK);
            host.setTextSize(16);
            host.setText("填写电脑的 SSH 连接信息。首次连接时核对服务器指纹。");
            box.addView(host);
            EditText hostname = settingsField(dialogContext, box, "SSH 地址", tunnel.getHost());
            EditText port = settingsField(dialogContext, box, "SSH 端口", Integer.toString(tunnel.getPort()));
            port.setInputType(InputType.TYPE_CLASS_NUMBER);
            TextView userLabel = new TextView(dialogContext);
            userLabel.setText("SSH 用户名"); userLabel.setTextColor(Color.BLACK); userLabel.setTextSize(17);
            box.addView(userLabel);
            EditText user = new EditText(dialogContext);
            user.setTextColor(Color.BLACK); user.setHintTextColor(Color.DKGRAY); user.setTextSize(18);
            user.setBackgroundTintList(ColorStateList.valueOf(Color.BLACK));
            user.setHint("SSH 用户名"); user.setSingleLine(true);
            user.setText(tunnel.getUser()); box.addView(user);
            TextView passwordLabel = new TextView(dialogContext);
            passwordLabel.setText("SSH 密码"); passwordLabel.setTextColor(Color.BLACK); passwordLabel.setTextSize(17);
            box.addView(passwordLabel);
            EditText password = new EditText(dialogContext);
            password.setTextColor(Color.BLACK); password.setHintTextColor(Color.DKGRAY); password.setTextSize(18);
            password.setBackgroundTintList(ColorStateList.valueOf(Color.BLACK));
            password.setHint("SSH 密码"); password.setSingleLine(true);
            password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            password.setText(tunnel.getPassword()); box.addView(password);
            CheckBox remember = new CheckBox(dialogContext);
            remember.setText("记住密码（在本机加密保存）");
            remember.setTextColor(Color.BLACK);
            remember.setButtonTintList(ColorStateList.valueOf(Color.BLACK));
            remember.setChecked(tunnel.remembersPassword()); box.addView(remember);
            CheckBox diagnostics = new CheckBox(dialogContext);
            diagnostics.setText("诊断日志自动回传电脑（不含录音和密码）");
            diagnostics.setTextColor(Color.BLACK);
            diagnostics.setChecked(tunnel.diagnostics.enabled);
            diagnostics.setOnCheckedChangeListener((button, checked) -> tunnel.diagnostics.setEnabled(checked));
            box.addView(diagnostics);
            if (!tunnel.getFingerprint().isEmpty()) {
                Button clearTrust = new Button(dialogContext);
                clearTrust.setText("清除已保存的电脑指纹");
                clearTrust.setOnClickListener(view -> new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle("清除已保存的指纹？")
                    .setMessage("电脑 " + tunnel.getHost() + ":" + tunnel.getPort()
                        + " 的指纹将被清除。下次连接时必须重新核对。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("清除", (d, which) -> {
                        try { tunnel.clearFingerprint(); clearTrust.setEnabled(false); }
                        catch (Exception e) { Toast.makeText(this, "无法清除指纹，请先断开连接", Toast.LENGTH_LONG).show(); }
                    }).show());
                box.addView(clearTrust);
            }
            ScrollView scroll = new ScrollView(dialogContext);
            scroll.setBackgroundColor(Color.WHITE);
            scroll.addView(box);
            AlertDialog settingsDialog = builder.setTitle("连接设置").setView(scroll)
                .setPositiveButton("保存", null).setNegativeButton("取消", null).create();
            settingsDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            settingsDialog.show();
            settingsDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    try {
                        tunnel.configure(hostname.getText().toString().trim(), Integer.parseInt(port.getText().toString().trim()),
                            user.getText().toString().trim(), password.getText().toString(), remember.isChecked());
                        Toast.makeText(this, "已保存，点击连接即可", Toast.LENGTH_SHORT).show();
                        settingsDialog.dismiss();
                    } catch (IllegalStateException e) {
                        Toast.makeText(this, "请先断开连接再修改设置", Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "请检查地址、端口和用户名", Toast.LENGTH_LONG).show();
                    }
                });
            settingsDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.BLACK);
            settingsDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.BLACK);
        });
    }

    private EditText settingsField(Context context, LinearLayout box, String label, String value) {
        TextView title = new TextView(context);
        title.setText(label); title.setTextColor(Color.BLACK); title.setTextSize(17); box.addView(title);
        EditText field = new EditText(context);
        field.setSingleLine(true); field.setTextColor(Color.BLACK); field.setHintTextColor(Color.DKGRAY);
        field.setTextSize(18); field.setBackgroundTintList(ColorStateList.valueOf(Color.BLACK));
        field.setHint(label); field.setText(value); box.addView(field);
        return field;
    }

    // Called with the actual Oboe capture stream's allocated audio session ID.
    public synchronized void attachVoiceEffects(int session) {
        releaseVoiceEffects();
        recordingSession = session;
        if (session <= 0) { echoStatus = "系统通话处理；可切换按住说话"; return; }
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(session);
                if (aec != null) aec.setEnabled(true);
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(session);
                if (ns != null) ns.setEnabled(true);
            }
            echoStatus = aec != null && aec.getEnabled()
                ? "系统回声消除已启用" : "系统通话处理；可切换按住说话";
        } catch (RuntimeException e) { echoStatus = "系统通话处理；可切换按住说话"; }
    }
    private synchronized void releaseVoiceEffects() {
        if (aec != null) { aec.release(); aec = null; }
        if (ns != null) { ns.release(); ns = null; }
    }

    public void setForegroundServiceActive(boolean active) {
        runOnUiThread(() -> {
            if (active == foregroundActive) return;
            if (active) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
                Intent intent = new Intent(this, SonoBusService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
            } else stopService(new Intent(this, SonoBusService.class));
            foregroundActive = active;
        });
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); appNewIntent(intent);
        if (activityResumed) connectFromTileIfRequested();
    }
    @Override protected void onResume() {
        super.onResume();
        activityResumed = true;
        tunnel.diagnostics.event("activity_resumed");
        connectFromTileIfRequested();
    }
    @Override protected void onPause() {
        activityResumed = false;
        tunnel.diagnostics.event("activity_paused");
        if (hostKeyDialog != null) hostKeyDialog.cancel();
        super.onPause();
    }
    private void connectFromTileIfRequested() {
        Intent intent = getIntent();
        if (intent != null && VoiceConnectionTileService.ACTION_CONNECT.equals(intent.getAction())
                && intent.getComponent() != null
                && VoiceConnectionTileService.ENTRY_CLASS.equals(intent.getComponent().getClassName())) {
            intent.setAction(null);
            // A microphone foreground service must be started while the activity is visible.
            new Handler(Looper.getMainLooper()).post(this::startVoiceTunnel);
        }
    }
    @Override protected void onDestroy() {
        if (currentActivity.get() == this) currentActivity.clear();
        tunnel.stop();
        setForegroundServiceActive(false);
        destroyNativeClass();
        releaseVoiceEffects();
        if (changedSpeakerphone) audio.setSpeakerphoneOn(false);
        // Release this app's mode request; Android then selects the appropriate
        // mode for other apps rather than claiming their previous mode as ours.
        audio.setMode(AudioManager.MODE_NORMAL);
        super.onDestroy();
    }
}
