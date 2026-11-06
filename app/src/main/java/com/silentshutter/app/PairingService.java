package com.silentshutter.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.net.InetAddress;

import io.github.muntashirakon.adb.android.AdbMdns;

/**
 * Runs while the user is in the wireless debugging screen. It finds the pairing port and lets the
 * user type the 6-digit code straight into a notification, then pairs and mutes in one go.
 */
public class PairingService extends Service {
    public static final String ACTION_START = "com.silentshutter.app.START";
    private static final String ACTION_REPLY = "com.silentshutter.app.REPLY";
    private static final String ACTION_STOP = "com.silentshutter.app.STOP";
    private static final String KEY_CODE = "code";
    private static final String CHANNEL = "pairing";
    private static final int NOTIFICATION_ID = 1;
    private static final long TIMEOUT_MS = 10 * 60 * 1000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private AdbMdns mMdns;
    private volatile String mHost;
    private volatile int mPort = -1;
    private volatile boolean mBusy;

    public static void start(Context context) {
        Intent intent = new Intent(context, PairingService.class).setAction(ACTION_START);
        context.startForegroundService(intent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            finish(null);
            return START_NOT_STICKY;
        }
        if (ACTION_REPLY.equals(action)) {
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            CharSequence code = results != null ? results.getCharSequence(KEY_CODE) : null;
            onCode(code != null ? code.toString() : "");
            return START_NOT_STICKY;
        }
        createChannel();
        Notification waiting = build("무선 디버깅 화면에서 '페어링 코드로 기기 페어링'을 눌러주세요",
                "코드가 보이면 이 알림에 6자리 코드를 입력하면 끝나요.", false);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, waiting, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, waiting);
        }
        startDiscovery();
        mHandler.removeCallbacksAndMessages(null);
        mHandler.postDelayed(() -> finish(null), TIMEOUT_MS);
        return START_NOT_STICKY;
    }

    private void startDiscovery() {
        if (mMdns != null) mMdns.stop();
        mMdns = new AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING, this::onPairingPort);
        mMdns.start();
    }

    private void onPairingPort(InetAddress host, int port) {
        if (port <= 0 || mBusy) return;
        mHost = host != null ? host.getHostAddress() : null;
        mPort = port;
        notifyNow(build("페어링 코드를 입력해 주세요",
                "알림을 내리거나 '코드 입력'을 눌러 화면의 6자리 Wi-Fi 페어링 코드를 입력하세요.", true));
    }

    private void onCode(String code) {
        if (mBusy) return;
        if (mPort <= 0) {
            notifyNow(build("아직 페어링 화면을 찾지 못했어요",
                    "무선 디버깅에서 '페어링 코드로 기기 페어링'을 먼저 눌러주세요.", false));
            return;
        }
        mBusy = true;
        notifyNow(build("연결 중…", "잠시만 기다려 주세요.", false));
        final String host = mHost;
        final int port = mPort;
        new Thread(() -> {
            try {
                ShutterController.pair(this, host, port, code);
                ShutterController.setMuted(this, true);
                finish(build("✅ 무음 설정 완료",
                        "이제 무음·진동 모드에서 기본 카메라 셔터음이 나지 않아요. 카메라 앱을 껐다 켜 주세요.", false));
            } catch (Throwable t) {
                mBusy = false;
                notifyNow(build("다시 시도해 주세요", String.valueOf(t.getMessage()), true));
            }
        }, "pairing").start();
    }

    private void finish(Notification last) {
        mHandler.post(() -> {
            mHandler.removeCallbacksAndMessages(null);
            if (mMdns != null) {
                mMdns.stop();
                mMdns = null;
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            if (last != null) {
                getSystemService(NotificationManager.class).notify(NOTIFICATION_ID + 1, last);
            }
            sendBroadcast(new Intent(MainActivity.ACTION_REFRESH).setPackage(getPackageName()));
            stopSelf();
        });
    }

    private void notifyNow(Notification notification) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification);
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "페어링",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification build(String title, String text, boolean withInput) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(false)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE));
        if (withInput) {
            RemoteInput input = new RemoteInput.Builder(KEY_CODE).setLabel("6자리 페어링 코드").build();
            Intent reply = new Intent(this, PairingService.class).setAction(ACTION_REPLY);
            PendingIntent replyIntent = PendingIntent.getService(this, 1, reply,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            builder.addAction(new Notification.Action.Builder(null, "코드 입력", replyIntent)
                    .addRemoteInput(input).build());
        }
        Intent stop = new Intent(this, PairingService.class).setAction(ACTION_STOP);
        builder.addAction(new Notification.Action.Builder(null, "취소",
                PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_IMMUTABLE)).build());
        return builder.build();
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        if (mMdns != null) mMdns.stop();
        super.onDestroy();
    }
}
