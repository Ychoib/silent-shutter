package com.silentshutter.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetAddress;

import io.github.muntashirakon.adb.android.AdbMdns;

public class MainActivity extends Activity {
    public static final String ACTION_REFRESH = "com.silentshutter.app.REFRESH";

    private TextView mStatus;
    private TextView mMessage;
    private EditText mCode;
    private Button mPairButton;
    private AdbMdns mMdns;
    private volatile String mPairHost;
    private volatile int mPairPort = -1;

    private final BroadcastReceiver mRefresh = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshStatus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ACTION_REFRESH);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mRefresh, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mRefresh, filter);
        }
        // Visible (also in split screen next to Settings): watch for the pairing code dialog.
        mMdns = new AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING, this::onPairingPort);
        mMdns.start();
        refreshStatus();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(mRefresh);
        if (mMdns != null) {
            mMdns.stop();
            mMdns = null;
        }
        super.onStop();
    }

    private void onPairingPort(InetAddress host, int port) {
        mPairHost = host != null ? host.getHostAddress() : null;
        mPairPort = port;
        runOnUiThread(() -> mPairButton.setText(port > 0
                ? "코드로 페어링하고 무음 켜기 (페어링 화면 찾음)"
                : "코드로 페어링하고 무음 켜기"));
    }

    private void refreshStatus() {
        String value = ShutterController.readLocal(this);
        String text;
        if ("0".equals(value)) {
            text = "현재 상태: 무음 설정됨 ✅\n무음·진동 모드에서 셔터음이 나지 않아요.";
        } else if ("1".equals(value)) {
            text = "현재 상태: 셔터음 강제 (한국 기본값)";
        } else {
            text = ShutterController.isPaired(this)
                    ? "현재 상태: 페어링 완료. 아래 버튼으로 무음을 켜고 끌 수 있어요."
                    : "현재 상태: 아직 설정 전";
        }
        mStatus.setText(text);
    }

    private void startPairing() {
        PairingService.start(this);
        openWirelessDebugging();
        Toast.makeText(this, "무선 디버깅을 켜고 '페어링 코드로 기기 페어링'을 눌러주세요", Toast.LENGTH_LONG).show();
    }

    private void openWirelessDebugging() {
        // Samsung/AOSP wireless debugging screen; fall back to Developer options.
        Intent[] candidates = {
                new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),
                new Intent(":android:show_fragment").setClassName("com.android.settings",
                        "com.android.settings.SubSettings")
                        .putExtra(":settings:show_fragment",
                                "com.android.settings.development.WirelessDebuggingFragment"),
                new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        };
        for (Intent intent : candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
                startActivity(intent);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        Toast.makeText(this, "개발자 옵션이 아직 꺼져 있어요. 아래 안내의 0단계를 먼저 해주세요.", Toast.LENGTH_LONG).show();
    }

    private void pairFromApp() {
        String code = mCode.getText().toString().trim();
        if (code.length() != 6) {
            show("6자리 페어링 코드를 입력해 주세요.");
            return;
        }
        if (mPairPort <= 0) {
            show("페어링 화면을 찾지 못했어요. 무선 디버깅에서 '페어링 코드로 기기 페어링'을 눌러 코드 창을 띄운 상태에서 시도해 주세요. "
                    + "(분할 화면으로 설정과 이 앱을 함께 띄우면 편해요)");
            return;
        }
        final String host = mPairHost;
        final int port = mPairPort;
        run("연결 중…", () -> {
            ShutterController.pair(this, host, port, code);
            ShutterController.setMuted(this, true);
            return "✅ 무음 설정 완료! 카메라 앱을 껐다 켠 뒤 무음 또는 진동 모드에서 촬영해 보세요.";
        });
    }

    private void setMuted(boolean muted) {
        run(muted ? "무음 켜는 중…" : "원래대로 되돌리는 중…", () -> {
            ShutterController.setMuted(this, muted);
            return muted
                    ? "✅ 무음 설정 완료! 카메라 앱을 껐다 켜 주세요."
                    : "셔터음이 원래대로(항상 소리 남) 돌아왔어요. 휴대폰을 재시작하면 확실히 적용돼요.";
        });
    }

    private interface Task {
        String call() throws Exception;
    }

    private void run(String progress, Task task) {
        show(progress);
        new Thread(() -> {
            String result;
            try {
                result = task.call();
            } catch (Throwable t) {
                result = "⚠️ " + t.getMessage();
            }
            final String message = result;
            runOnUiThread(() -> {
                show(message);
                refreshStatus();
            });
        }, "adb").start();
    }

    private void show(String message) {
        mMessage.setText(message);
        mMessage.setVisibility(View.VISIBLE);
    }

    // ---------- UI ----------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = text("무음 셔터", 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(text("갤럭시 기본 카메라의 강제 셔터음을 끕니다. 카메라 기능은 그대로예요.", 15));

        mStatus = text("", 16);
        mStatus.setTypeface(Typeface.DEFAULT_BOLD);
        mStatus.setPadding(0, dp(16), 0, dp(8));
        root.addView(mStatus);

        mMessage = text("", 15);
        mMessage.setVisibility(View.GONE);
        mMessage.setPadding(0, 0, 0, dp(8));
        root.addView(mMessage);

        root.addView(header("처음 한 번만: 페어링"));
        root.addView(text("0. 설정 → 휴대전화 정보 → 소프트웨어 정보 → '빌드번호'를 7번 눌러 개발자 옵션을 켜주세요. "
                + "와이파이에 연결되어 있어야 해요.", 14));
        root.addView(text("1. 아래 버튼을 누르면 무선 디버깅 화면이 열려요. 무선 디버깅을 켜고 "
                + "'페어링 코드로 기기 페어링'을 누르세요.", 14));
        root.addView(text("2. 알림창에 뜬 '무음 셔터' 알림에서 '코드 입력'을 눌러 6자리 코드를 입력하면 끝! "
                + "(또는 분할 화면에서 아래 칸에 직접 입력)", 14));

        Button start = button("무선 디버깅 열고 페어링 시작");
        start.setOnClickListener(v -> startPairing());
        root.addView(start);

        mCode = new EditText(this);
        mCode.setHint("6자리 페어링 코드 (직접 입력할 때)");
        mCode.setInputType(InputType.TYPE_CLASS_NUMBER);
        mCode.setGravity(Gravity.CENTER);
        root.addView(mCode);

        mPairButton = button("코드로 페어링하고 무음 켜기");
        mPairButton.setOnClickListener(v -> pairFromApp());
        root.addView(mPairButton);

        root.addView(header("페어링 후"));
        root.addView(text("무선 디버깅이 켜져 있을 때 누르세요. 휴대폰 업데이트 후 셔터음이 다시 나면 '무음 켜기'만 다시 누르면 돼요.", 14));
        Button mute = button("무음 켜기");
        mute.setOnClickListener(v -> setMuted(true));
        root.addView(mute);
        Button restore = button("원래대로 (셔터음 켜기)");
        restore.setOnClickListener(v -> setMuted(false));
        root.addView(restore);

        root.addView(header("꼭 알아두세요"));
        root.addView(text("• 설정 후 셔터음은 '무음' 또는 '진동' 모드에서 완전히 꺼져요. '소리' 모드에서는 시스템 음량에 따라 작게 날 수 있어요.\n"
                + "• 항상 무음으로 쓰려면: 설정 → 모드 및 루틴 → 루틴 → '카메라 앱을 열 때' → '소리 모드: 진동'을 추가하세요.\n"
                + "• 설정이 끝나면 무선 디버깅과 개발자 옵션은 꺼도 무음이 유지돼요.\n"
                + "• 공장 초기화를 하면 다시 설정해야 해요.\n"
                + "• 공공장소에서의 무음 촬영은 타인의 사생활을 침해하지 않도록 주의해 주세요.", 14));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private TextView text(String s, int sp) {
        TextView view = new TextView(this);
        view.setText(s);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private TextView header(String s) {
        TextView view = text(s, 18);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(20), 0, dp(4));
        return view;
    }

    private Button button(String s) {
        Button button = new Button(this);
        button.setText(s);
        button.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        button.setLayoutParams(lp);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
