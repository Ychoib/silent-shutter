package com.silentshutter.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import io.github.muntashirakon.adb.AdbStream;

/** Pairs with and talks to the phone's own wireless debugging to flip the shutter-sound setting. */
public final class ShutterController {
    /** Samsung CSC setting: 1 = shutter sound forced (Korea/Japan), 0 = follows the sound mode. */
    public static final String KEY = "csc_pref_camera_forced_shuttersound_key";

    private static final String PREFS = "state";
    private static final String PREF_PAIRED = "paired";
    private static final String LOOPBACK = "127.0.0.1";

    private ShutterController() {
    }

    public static boolean isPaired(Context context) {
        return prefs(context).getBoolean(PREF_PAIRED, false);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Pairs this app with wireless debugging using the 6-digit code. */
    @WorkerThread
    public static void pair(Context context, @Nullable String host, int port, String code) throws Exception {
        AdbManager manager = AdbManager.get(context);
        boolean ok;
        try {
            ok = manager.pair(host != null ? host : LOOPBACK, port, code.trim());
        } catch (Exception first) {
            if (host == null || LOOPBACK.equals(host)) throw first;
            ok = manager.pair(LOOPBACK, port, code.trim());
        }
        if (!ok) throw new IllegalStateException("페어링에 실패했어요. 코드가 맞는지 확인해 주세요.");
        prefs(context).edit().putBoolean(PREF_PAIRED, true).apply();
    }

    /** Sets the shutter to follow the sound mode (muted = true) or restores the forced sound. Returns the new value. */
    @WorkerThread
    public static String setMuted(Context context, boolean muted) throws Exception {
        String value = muted ? "0" : "1";
        String out = runShell(context, "settings put system " + KEY + " " + value
                + "; settings get system " + KEY);
        String[] lines = out.trim().split("\\s+");
        String result = lines.length == 0 ? "" : lines[lines.length - 1];
        if (!value.equals(result)) {
            throw new IllegalStateException("설정이 적용되지 않았어요. 응답: " + out.trim());
        }
        return result;
    }

    /** Reads the current value without ADB if the system allows it. null = unknown. */
    @Nullable
    public static String readLocal(Context context) {
        try {
            return Settings.System.getString(context.getContentResolver(), KEY);
        } catch (Exception e) {
            return null;
        }
    }

    @WorkerThread
    private static String runShell(Context context, String command) throws Exception {
        AdbManager manager = AdbManager.get(context);
        if (!manager.isConnected()) {
            boolean connected;
            try {
                connected = manager.autoConnect(context, 10_000);
            } catch (io.github.muntashirakon.adb.AdbPairingRequiredException e) {
                prefs(context).edit().putBoolean(PREF_PAIRED, false).apply();
                throw new IllegalStateException("먼저 페어링이 필요해요. 1단계를 진행해 주세요.");
            } catch (InterruptedException e) {
                throw new IllegalStateException("무선 디버깅을 찾지 못했어요. 와이파이에 연결된 상태에서 "
                        + "개발자 옵션 → 무선 디버깅이 켜져 있는지 확인해 주세요.");
            }
            if (!connected && !manager.isConnected()) {
                throw new IllegalStateException("무선 디버깅에 연결하지 못했어요. 무선 디버깅을 껐다 켠 뒤 다시 시도해 주세요.");
            }
        }
        try (AdbStream stream = manager.openStream("shell:" + command)) {
            InputStream in = stream.openInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int n;
            try {
                while ((n = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, n);
                }
            } catch (java.io.IOException e) {
                // The shell closes its stream when the command ends; that can surface as an exception.
                if (buffer.size() == 0) throw e;
            }
            return buffer.toString(StandardCharsets.UTF_8.name());
        } finally {
            try {
                manager.disconnect();
            } catch (Exception ignored) {
            }
        }
    }
}
