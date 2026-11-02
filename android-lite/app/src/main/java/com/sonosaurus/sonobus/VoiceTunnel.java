// SPDX-License-Identifier: GPL-3.0-or-later
package com.sonosaurus.sonobus;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;
import java.io.*;
import java.lang.ref.WeakReference;
import java.net.*;
import java.security.*;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.transport.verification.FingerprintVerifier;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import net.schmizz.sshj.userauth.UserAuthException;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

final class VoiceTunnel {
    static {
        // Android's built-in BC is a reduced fork without SSHJ's X25519 support.
        // Keep platform/Keystore providers ahead of our complete bundled provider.
        Provider existing = Security.getProvider("BC");
        if (!(existing instanceof BouncyCastleProvider)) {
            Security.removeProvider("BC");
            Security.addProvider(new BouncyCastleProvider());
        }
    }
    static final int LOCAL_PORT = 39021, MAX_PACKET = 65507;
    private static final String KEY_ALIAS = "codex_voice_password_v1";
    private final SharedPreferences prefs;
    private final Context applicationContext;
    private final WeakReference<SonoBusActivity> activity;
    private volatile String password = "";
    private volatile Connection current;
    private long generation;
    private Thread reconnectThread;
    private ConnectivityManager networkManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Network observedNetwork;
    volatile int state = 0; // idle / connecting / SSH ready / error
    volatile String message = "准备连接";

    VoiceTunnel(Context context) {
        applicationContext = context.getApplicationContext();
        activity = new WeakReference<>((SonoBusActivity)context);
        prefs = context.getSharedPreferences("voice_connection", Context.MODE_PRIVATE);
        if (remembersPassword()) {
            try { password = decrypt(prefs.getString("secret", ""), getHost(), getPort()); }
            catch (Exception e) { message = "请重新输入 SSH 密码"; }
        }
    }
    private void updateState(int nextState, String nextMessage) {
        message = nextMessage;
        state = nextState;
        VoiceConnectionTileService.notifyStateChanged();
    }
    String getHost() { return prefs.getString("host", ""); }
    int getPort() { return prefs.getInt("port", 22); }
    String getFingerprint() { return prefs.getString("fingerprint", ""); }
    String getUser() { return prefs.getString("user", ""); }
    String getPassword() { return password; }
    boolean hasPassword() { return !password.isEmpty(); }
    boolean remembersPassword() { return prefs.contains("secret"); }
    synchronized void configure(String host, int port, String user, String pass, boolean remember) throws Exception {
        if (state == 1 || state == 2) throw new IllegalStateException("请先断开连接再修改设置");
        if (host.isEmpty() || port < 1 || port > 65535 || user.isEmpty())
            throw new IllegalArgumentException("invalid SSH settings");
        String secret = remember && !pass.isEmpty() ? encrypt(pass, host, port) : null;
        SharedPreferences.Editor editor = prefs.edit().putString("host", host).putInt("port", port)
            .putString("user", user);
        // A saved key belongs to exactly one configured host and port.
        if (!host.equals(getHost()) || port != getPort()) editor.remove("fingerprint");
        if (secret == null) editor.remove("secret"); else editor.putString("secret", secret);
        if (!editor.commit()) throw new IOException("unable to save settings");
        password = pass;
    }
    synchronized void clearFingerprint() throws IOException {
        if (state == 1 || state == 2) throw new IllegalStateException("请先断开连接再清除指纹");
        if (!prefs.edit().remove("fingerprint").commit()) throw new IOException("unable to clear host key");
    }

    static final class HostKeyRequest {
        final String host, algorithm, fingerprint;
        final int port;
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicBoolean answered = new AtomicBoolean(false);
        private volatile boolean accepted;
        HostKeyRequest(String host, int port, String algorithm, String fingerprint) {
            this.host = host; this.port = port; this.algorithm = algorithm; this.fingerprint = fingerprint;
        }
        void respond(boolean yes) {
            if (answered.compareAndSet(false, true)) {
                accepted = yes;
                done.countDown();
            }
        }
        boolean isPending() { return !answered.get(); }
        boolean await() throws InterruptedException {
            return done.await(90, TimeUnit.SECONDS) && accepted;
        }
    }
    private SecretKey getKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return (SecretKey)store.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    private String encrypt(String plain, String host, int port) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey());
        cipher.updateAAD((host + ":" + port).getBytes("UTF-8"));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
            + Base64.encodeToString(cipher.doFinal(plain.getBytes("UTF-8")), Base64.NO_WRAP);
    }
    private String decrypt(String secret, String host, int port) throws Exception {
        String[] parts = secret.split(":", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        cipher.updateAAD((host + ":" + port).getBytes("UTF-8"));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), "UTF-8");
    }
    synchronized void start() {
        stop();
        long token = ++generation;
        updateState(1, "正在通过 SSH 连接…");
        final String host = getHost(), user = getUser(), pass = password;
        final int port = getPort();
        reconnectThread = new Thread(() -> reconnect(token, host, port, user, pass), "voice-ssh");
        observeNetwork(token);
        reconnectThread.start();
    }
    synchronized void stop() {
        ++generation;
        stopObservingNetwork();
        Thread previousThread = reconnectThread; reconnectThread = null;
        Connection previous = current; current = null;
        if (previous != null) previous.close();
        if (previousThread != null) previousThread.interrupt();
        updateState(0, "已断开");
    }
    private void observeNetwork(long token) {
        try {
            networkManager = applicationContext.getSystemService(ConnectivityManager.class);
            observedNetwork = networkManager.getActiveNetwork();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { networkChanged(token, network, false); }
                @Override public void onLost(Network network) { networkChanged(token, network, true); }
            };
            networkManager.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException e) {
            networkCallback = null;
            Log.w("CodexVoice", "Cannot monitor network changes; periodic reconnect remains enabled");
        }
    }
    private void stopObservingNetwork() {
        if (networkManager != null && networkCallback != null) {
            try { networkManager.unregisterNetworkCallback(networkCallback); }
            catch (IllegalArgumentException ignored) {}
        }
        networkCallback = null;
        observedNetwork = null;
    }
    private synchronized void networkChanged(long token, Network network, boolean lost) {
        if (token != generation) return;
        if (lost) {
            if (!network.equals(observedNetwork)) return;
            observedNetwork = null;
            if (current != null) {
                updateState(1, "网络变化，正在重新连接…");
                current.close();
            }
        } else {
            Network old = observedNetwork;
            observedNetwork = network;
            if (old != null && !old.equals(network) && current != null) {
                updateState(1, "网络变化，正在重新连接…");
                current.close();
            }
            // When a connection returns while waiting between attempts, retry promptly.
            if (current == null && (old == null || !old.equals(network))) notifyAll();
        }
    }
    private void reconnect(long token, String host, int port, String user, String pass) {
        int failures = 0;
        for (;;) {
            Connection connection = new Connection(host, port, user, pass);
            synchronized (this) {
                if (token != generation) return;
                current = connection;
            }
            connection.run();
            synchronized (this) {
                if (token != generation) return;
                current = null;
                if (connection.terminalMessage != null) {
                    ++generation;
                    reconnectThread = null;
                    stopObservingNetwork();
                    updateState(3, connection.terminalMessage);
                    return;
                }
                if (connection.connectedAtNanos != 0 &&
                        System.nanoTime() - connection.connectedAtNanos >= 30_000_000_000L) failures = 0;
                else failures = Math.min(failures + 1, 6);
                updateState(1, "连接中断，正在自动重连…");
                long delay = Math.min(30_000L, 1_000L << Math.max(0, failures - 1));
                try { wait(delay); }
                catch (InterruptedException ignored) { if (token != generation) return; }
            }
        }
    }
    private synchronized boolean connectionReady(Connection connection) {
        if (connection != current || connection.closed) return false;
        connection.connectedAtNanos = System.nanoTime();
        updateState(2, "SSH 已连接，正在建立音频…");
        return true;
    }
    private boolean verifyHostKey(Connection connection, String host, int port, PublicKey key) {
        final String fingerprint;
        final String algorithm;
        try {
            byte[] encoded = new Buffer.PlainBuffer().putPublicKey(key).getCompactData();
            fingerprint = "SHA256:" + Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(encoded), Base64.NO_WRAP | Base64.NO_PADDING);
            algorithm = KeyType.fromKey(key).toString();
        } catch (GeneralSecurityException | RuntimeException e) {
            connection.terminalMessage = "无法读取服务器指纹，连接已拒绝";
            return false;
        }
        HostKeyRequest request;
        synchronized (this) {
            if (connection != current || connection.closed || !connection.host.equals(getHost())
                    || connection.port != getPort()) return false;
            String saved = getFingerprint();
            if (!saved.isEmpty()) {
                try {
                    if (FingerprintVerifier.getInstance(saved).verify(host, port, key)) return true;
                    connection.hostKeyRejected = true;
                } catch (RuntimeException e) {
                    connection.terminalMessage = "已保存的服务器指纹无效，连接已拒绝";
                }
                return false;
            }
            request = new HostKeyRequest(connection.host, connection.port, algorithm, fingerprint);
            connection.hostKeyRequest = request;
            updateState(1, "请核对并确认服务器指纹…");
        }
        SonoBusActivity owner = activity.get();
        if (owner == null) request.respond(false); else owner.showHostKeyRequest(request);
        boolean accepted = false;
        try { accepted = request.await(); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        if (owner != null) owner.dismissHostKeyRequest(request);
        synchronized (this) {
            synchronized (connection) {
                connection.hostKeyRequest = null;
                if (connection != current || connection.closed || !connection.host.equals(getHost())
                        || connection.port != getPort()) return false;
                if (!accepted) {
                    connection.terminalMessage = "已取消服务器指纹确认，连接已停止";
                    return false;
                }
                // Recheck under the same lock before committing the accepted key.
                String saved = getFingerprint();
                if (!saved.isEmpty() && !FingerprintVerifier.getInstance(saved).verify(host, port, key)) {
                    connection.hostKeyRejected = true;
                    return false;
                }
                if (!prefs.edit().putString("fingerprint", fingerprint).commit()) {
                    connection.terminalMessage = "无法保存服务器指纹，连接已停止";
                    return false;
                }
                return true;
            }
        }
    }
    private final class Connection implements Runnable {
        final String host, user, pass;
        final int port;
        volatile SSHClient ssh;
        volatile DatagramSocket udp;
        volatile boolean closed;
        volatile boolean hostKeyRejected;
        volatile HostKeyRequest hostKeyRequest;
        volatile String terminalMessage;
        volatile long connectedAtNanos;
        volatile InetSocketAddress peer;
        Connection(String host, int port, String user, String pass) {
            this.host = host; this.port = port; this.user = user; this.pass = pass;
        }
        synchronized void close() {
            if (closed) return;
            closed = true;
            HostKeyRequest pending = hostKeyRequest;
            if (pending != null) {
                pending.respond(false);
                SonoBusActivity owner = activity.get();
                if (owner != null) owner.dismissHostKeyRequest(pending);
            }
            if (udp != null) udp.close();
            // SSH shutdown can block. Never run it on the Android/JUCE UI thread.
            SSHClient old = ssh;
            if (old != null) new Thread(() -> {
                // A graceful SSH close can wait on a broken network, leaving the
                // remote bridge alive. Interrupt the transport first so both
                // sides promptly release the previous connection.
                try {
                    Socket transport = old.getSocket();
                    if (transport != null) transport.close();
                } catch (IOException ignored) {}
                try { old.close(); } catch (IOException ignored) {}
            }, "voice-close").start();
        }
        @Override public void run() {
            String phase = "连接 SSH";
            try (SSHClient client = new SSHClient()) {
                ssh = client;
                if (closed) return;
                client.addHostKeyVerifier(new HostKeyVerifier() {
                    public boolean verify(String h, int p, PublicKey key) {
                        return verifyHostKey(Connection.this, h, p, key);
                    }
                    public java.util.List<String> findExistingAlgorithms(String h, int p) { return Collections.singletonList("ssh-ed25519"); }
                });
                // Give the user time to verify a first-seen key before authentication.
                client.setConnectTimeout(10000); client.setTimeout(120000);
                client.getConnection().getKeepAlive().setKeepAliveInterval(10);
                client.connect(host, port);
                if (closed || current != this) return;
                client.setTimeout(15000);
                client.getSocket().setTcpNoDelay(true);
                phase = "验证账号密码";
                client.authPassword(user, pass);
                if (closed || current != this) return;
                phase = "启动电脑音频桥";
                try (Session session = client.startSession();
                     Session.Command command = session.exec("if [ -x /usr/bin/sonobus-ssh-bridge ]; then bridge=/usr/bin/sonobus-ssh-bridge; else bridge=\"$HOME/.local/bin/sonobus-ssh-bridge\"; fi; if \"$bridge\" --supports-heartbeat >/dev/null 2>&1; then exec \"$bridge\" --stdio --heartbeat; else exec \"$bridge\" --stdio; fi");
                     DatagramSocket socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", LOCAL_PORT))) {
                    udp = socket;
                    if (closed || current != this) return;
                    socket.setReceiveBufferSize(262144);
                    DataOutputStream output = new DataOutputStream(command.getOutputStream());
                    Thread send = new Thread(() -> {
                        try {
                            byte[] data = new byte[MAX_PACKET];
                            while (!closed) {
                                DatagramPacket packet = new DatagramPacket(data, data.length);
                                socket.receive(packet);
                                InetSocketAddress source = (InetSocketAddress)packet.getSocketAddress();
                                if (peer == null) peer = source;
                                if (!source.equals(peer) || packet.getLength() == 0) continue;
                                synchronized (output) {
                                    output.writeInt(packet.getLength());
                                    output.write(data, 0, packet.getLength()); output.flush();
                                }
                            }
                        } catch (IOException e) {
                            if (!closed && current == this) {
                                close();
                            }
                        }
                    }, "voice-udp-send");
                    send.start();
                    // Drain bounded diagnostics so a remote error cannot fill the SSH stderr window.
                    new Thread(() -> {
                        try { InputStream err = command.getErrorStream(); byte[] b = new byte[512]; while (err.read(b) >= 0) {} }
                        catch (IOException ignored) {}
                    }, "voice-stderr").start();
                    if (!connectionReady(this)) return;
                    phase = "音频传输";
                    DataInputStream input = new DataInputStream(command.getInputStream());
                    boolean heartbeatStarted = false;
                    while (!closed) {
                        int length = input.readInt();
                        if (length == 0 && !heartbeatStarted) {
                            heartbeatStarted = true;
                            new Thread(() -> {
                                try {
                                    while (!closed && current == this) {
                                        synchronized (output) { output.writeInt(0); output.flush(); }
                                        Thread.sleep(10000);
                                    }
                                } catch (IOException e) {
                                    if (!closed && current == this) close();
                                } catch (InterruptedException ignored) {}
                            }, "voice-heartbeat").start();
                            continue;
                        }
                        if (length <= 0 || length > MAX_PACKET) throw new IOException("invalid packet size");
                        byte[] data = new byte[length]; input.readFully(data);
                        InetSocketAddress destination = peer;
                        if (destination != null) socket.send(new DatagramPacket(data, data.length, destination));
                    }
                }
            } catch (Exception e) {
                if (!closed && current == this) {
                    if (hostKeyRejected) terminalMessage = "服务器指纹已变化，连接已拒绝！请核实电脑的 SSH 密钥";
                    else if (e instanceof UserAuthException && e.getCause() == null)
                        terminalMessage = "SSH 用户名或密码错误，请检查设置";
                    Throwable cause = e;
                    for (int i = 0; cause != null && i < 6; ++i, cause = cause.getCause()) {
                        String detail = cause.getClass().getSimpleName() + ": " + cause.getMessage();
                        if (!pass.isEmpty()) detail = detail.replace(pass, "[redacted]");
                        Log.w("CodexVoice", phase + ": " + detail);
                    }
                }
            } finally {
                close();
            }
        }
    }
}
