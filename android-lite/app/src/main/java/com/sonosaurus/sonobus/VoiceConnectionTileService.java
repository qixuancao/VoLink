// SPDX-License-Identifier: GPL-3.0-or-later
package com.sonosaurus.sonobus;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;
import java.lang.ref.WeakReference;

/** Quick Settings controls the SSH connection; the notification controls capture. */
public final class VoiceConnectionTileService extends TileService {
    static final String ACTION_CONNECT = "com.sharkhome.codexvoice.CONNECT_FROM_TILE";
    static final String ENTRY_CLASS = "com.sonosaurus.sonobus.VoiceTileEntry";
    private static WeakReference<VoiceConnectionTileService> listening = new WeakReference<>(null);

    static void notifyStateChanged() {
        new Handler(Looper.getMainLooper()).post(() -> {
            VoiceConnectionTileService service = listening.get();
            if (service != null) service.updateTile();
        });
    }

    @Override public void onStartListening() {
        super.onStartListening();
        listening = new WeakReference<>(this);
        Log.d("CodexVoiceTile", "Listening for connection state");
        updateTile();
    }

    @Override public void onStopListening() {
        if (listening.get() == this) listening.clear();
        super.onStopListening();
    }

    @Override public void onClick() {
        super.onClick();
        int state = SonoBusActivity.currentConnectionState();
        Log.d("CodexVoiceTile", "Tile clicked; connection state: " + state);
        if (state == 1 || state == 2) {
            SonoBusActivity.disconnectFromTile();
            updateTile();
            return;
        }
        if (isSecure() && isLocked()) unlockAndRun(this::openConnectionActivity);
        else openConnectionActivity();
    }

    private void openConnectionActivity() {
        // Android 14+ restricts starting a microphone service in the background.
        // Bring the activity into view and let it start the connection there.
        Intent intent = new Intent().setClassName(this, ENTRY_CLASS)
            .setAction(ACTION_CONNECT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pending = PendingIntent.getActivity(this, 73, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            startActivityAndCollapse(pending);
        } else {
            startActivityAndCollapse(intent);
        }
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        int state = SonoBusActivity.currentConnectionState();
        Log.d("CodexVoiceTile", "Updating connection state: " + state);
        tile.setState(state == 1 || state == 2 ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(state == 1 ? "VoLink 连接中" : state == 2 ? "VoLink 已连接" : "VoLink 连接");
        tile.updateTile();
    }
}
