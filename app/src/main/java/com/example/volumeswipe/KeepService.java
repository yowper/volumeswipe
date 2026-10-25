package com.example.volumeswipe;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * 常驻服务：摇一摇触发（短剧模式专用）+ 唤醒词监听（Vosk）。
 *
 * 摇一摇（只在短剧模式）= 只上滑（需要无障碍手势能力）
 * 「手动」= 弹出命令框（豆包语音/打字输入，再说一次「手动」执行）
 */
public class KeepService extends Service {

    private static final String CHANNEL_ID = "keep_alive_v2";
    private static final int NOTI_ID = 1001;

    public static volatile boolean alive = false;
    private static KeepService sInstance;

    public static volatile String voiceState = "未开启";
    public static volatile String lastHeard = "";
    public static volatile String selfTest = "未测试";
    public static volatile int voiceLevel = 0;
    public static volatile String voiceEngine = "唤醒词（Vosk）";
    public static volatile String recent = "";

    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 用户要求（v2.30）：半小时一次后台驻留检查 + 自动恢复本地语音 */
    private static final long RESIDENCY_CHECK_MS = 30L * 60 * 1000;

    private final Runnable residencyCheck = new Runnable() {
        @Override
        public void run() {
            try {
                boolean enabled = getSharedPreferences("cfg", MODE_PRIVATE)
                        .getBoolean("enabled", false);
                android.app.Service me = KeepService.this;
                AutoService svc = AutoService.get();
                note("驻留检查（每 30 分钟）：语音=" + voiceState
                        + " 无障碍=" + (svc == null ? "未连" : "已连")
                        + " 退出标记=" + (enabled ? "无" : "已退出"));
                if (enabled) {
                    // 自动恢复本地语音（引擎死了会在这里重新拉起来）
                    applyVoiceSetting();
                    if (svc != null) {
                        // 音量轮询死了也一并拉起来
                        svc.startVolPoller();
                    }
                }
            } catch (Throwable ignored) {
            }
            ui.postDelayed(this, RESIDENCY_CHECK_MS);
        }
    };
    private WakeEngine wake;
    private String lastLine = "在听命令（短剧里摇一摇=广告）";
    /** 最近 5 条状态（通知展开后可见，方便排查命令执行） */
    private final java.util.ArrayDeque<String> notiLog = new java.util.ArrayDeque<>();
    private boolean accLostNoted = false;
    private int micDownTicks = 0;
    // 看门狗用：现在「应该」在听的词。有些流程把麦克风暂停后忘了恢复
    //（用户反馈：跑久了本地语音不灵，重进 App 才好）→ 照这个强制拉回来
    private String[] wantVocab = null;
    private String wantLabel = null;
    private int pausedTicks = 0;

    /** 看门狗：每 2.5 秒检查悬浮框是否还在，被 ROM 收走就自动补回 */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            try {
                AutoService svc = AutoService.get();
                if (svc != null) {
                    accLostNoted = false;
                    svc.ensureOverlayAlive();
                } else if (!accLostNoted) {
                    accLostNoted = true;
                    updateNoti("无障碍服务未连接：悬浮框无法显示，请到设置里重新开启");
                }
            } catch (Throwable ignored) {
            }
            try {
                ensureMicAlive();
            } catch (Throwable ignored) {
            }
            ui.postDelayed(this, 2500);
        }
    };

    /**
     * 看门狗第二件事：麦克风健康检查。
     * 「关豆包 / 开豆包」来回切几次之后，最容易出的毛病就是语音引擎拿不到麦克风句柄，
     * 于是唤醒词再也听不到 → 豆包的 Mic 再也没人点（用户就以为豆包输入法坏了）。
     * 这里发现聋了就自动把引擎拉起来。
     */
    private void ensureMicAlive() {
        if (!getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("voiceOn", true)) {
            micDownTicks = 0;
            return;
        }
        AutoService svc = AutoService.get();
        if (svc != null && !svc.isIdle()) {
            micDownTicks = 0; // 正在识别一轮命令，别打断
            return;
        }
        String st = voiceState == null ? "" : voiceState;
        if (st.contains("启动中") || st.contains("加载") || st.contains("准备")) {
            micDownTicks = 0; // 首次解包/加载模型要几秒，别当成故障
            return;
        }
        if (wake == null || !wake.isAlive()) {
            // 引擎整个没了（线程退出/模型没加载）：等约 20 秒还没回来就重启
            micDownTicks++;
            if (micDownTicks >= 8) {
                micDownTicks = 0;
                stopWake();
                applyVoiceSetting();
                updateNoti("语音引擎已自动重启");
            }
            return;
        }
        if (wake.isMicOpen()) {
            micDownTicks = 0;
            pausedTicks = 0;
            return;
        }
        if (wake.isCapturePaused()) {
            // 采音被暂停：如果这时本来应该在听命令，说明有流程停完忘了恢复
            //（用户反馈：运行一段时间后本地语音不灵，重进 App 才好）→ 连续 2 拍（约 5 秒）就强制恢复
            if (wantVocab != null
                    && (AutoService.get() == null || AutoService.get().isIdle())) {
                if (++pausedTicks >= 2) {
                    pausedTicks = 0;
                    updateNoti("麦克风停着忘了恢复，已自动恢复");
                    note("麦克风被暂停超过 5 秒且没流程在用 → 自动恢复本地语音");
                    applyVocab(wantVocab, wantLabel);
                }
                return;
            }
            micDownTicks = 0;
            pausedTicks = 0;
            return;
        }
        // 引擎在跑但麦克风句柄丢了：WakeEngine 内部会自愈重试；约 15 秒还没好就重启引擎
        micDownTicks++;
        if (micDownTicks >= 6) {
            micDownTicks = 0;
            stopWake();
            applyVoiceSetting();
            updateNoti("麦克风一直拿不到，已重启语音引擎");
        }
    }

    private SensorManager sensorManager;
    private SensorEventListener shakeListener;
    private long lastShake = 0;
    /** 摇晃计数（单次超过 1.8g 算一个峰，700ms 内要凑够 3 个才算“真摇”） */
    private long shakeWindowAt = 0;
    private int shakePeaks = 0;

    public static KeepService instance() {
        return sInstance;
    }

    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, KeepService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception ignored) {
        }
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, KeepService.class));
        } catch (Exception ignored) {
        }
    }

    /** 退出程序时不再整个停掉前台服务：
     *  服务一死，有的手机会把无障碍开关也一起关掉（用户反馈：再次启动无障碍总被自动关）。
     *  改成暂停采音（麦克风让出去）、通知改成「已暂停」，打开 App 再自动恢复。 */
    public static void pauseForExit(Context ctx) {
        if (sInstance == null) {
            return;
        }
        try {
            sInstance.stopWake();
            sInstance.voiceState = "已暂停：打开「手机助手」恢复";
            sInstance.updateNoti(sInstance.voiceState);
        } catch (Throwable ignored) {
        }
    }

    public static void applyVoiceSetting(Context ctx) {
        if (sInstance != null) {
            sInstance.applyVoiceSetting();
        } else {
            start(ctx);
        }
    }

    public static void pauseWake() {
        listenVocab(null, "语音识别暂停（麦克风让给输入法）");
    }

    public static void resumeWake() {
        AutoService svc = AutoService.get();
        if (svc != null && !svc.isIdle()) {
            return; // 正在识别命令中，别打断
        }
        if (sInstance != null) {
            sInstance.applyVoiceSetting();
        }
    }

    /** 外部接口：切换引擎词表；vocab = null 表示暂停采音（麦克风让给豆包） */
    public static void listenVocab(String[] vocab, String label) {
        if (sInstance != null) {
            sInstance.applyVocab(vocab, label);
        }
    }

    /** 更新常驻通知文字（AutoService 汇报状态用） */
    public static void note(String line) {
        if (sInstance != null) {
            sInstance.updateNoti(line);
        }
    }

    /** 最近的日志（主界面直接显示，通知栏看不全时用它） */
    public static String recentLog() {
        if (sInstance == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String s : sInstance.notiLog) {
            sb.append(s).append('\n');
        }
        return sb.toString().trim();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        alive = true;
        createChannel();
        promoteForeground();
        startShake();
        // 用户要求（v2.30）：每半小时检查一次后台驻留，自动恢复本地语音
        ui.postDelayed(residencyCheck, RESIDENCY_CHECK_MS);
        applyVoiceSetting();
        ui.postDelayed(watchdog, 2500);
    }

    /**
     * 前台通知。Android 14 上带麦克风类型的前台服务在录音权限没批时启动会直接挦掉，
     * 所以分层兑底，保证服务和通知栏那条一定活下来。
     */
    private void promoteForeground() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTI_ID, buildNotification(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                                | android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTI_ID, buildNotification());
            }
            return;
        } catch (Throwable ignored) {
        }
        try {
            startForeground(NOTI_ID, buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } catch (Throwable t) {
            try {
                startForeground(NOTI_ID, buildNotification());
            } catch (Throwable ignored2) {
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        applyVoiceSetting();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        alive = false;
        sInstance = null;
        ui.removeCallbacks(watchdog);
        stopShake();
        stopWake();
        super.onDestroy();
    }

    // ==================== 语音识别（Vosk 本地引擎） ====================

    public void applyVoiceSetting() {
        // 正在识别一轮命令时不打断（打断会导致麦克风/词表状态错乱）
        AutoService svc = AutoService.get();
        if (svc != null && !svc.isIdle()) {
            return;
        }
        if (getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("useDoubao", false)) {
            applyVocab(WakeEngine.idleVocab(), "在听命令");
        } else {
            // 不用豆包：不需要唤醒词，直接听命令
            applyVocab(WakeEngine.commandVocab(), "直接听命令（不用豆包）");
        }
        // 免豆包模式屏幕上不显示编辑框；切回豆包模式时恢复
        if (svc != null) {
            svc.refreshOverlayForMode();
        }
    }

    /**
     * 引擎词表切换（模型不重载，几百毫秒内生效）。
     * vocab = null → 暂停采音（麦克风让给豆包输入法），模型留在内存；
     * 否则切到给定词表继续听（引擎没起就先起）。
     */
    public void applyVocab(String[] vocab, String label) {
        boolean micFree = (vocab == null);
        boolean on = !micFree
                && getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("voiceOn", true);
        // 记住现在应该在听什么（看门狗在麦克风被忘了恢复时用）
        wantVocab = micFree ? null : vocab;
        wantLabel = label;

        if (!on) {
            if (micFree && wake != null) {
                wake.pauseCapture();
                voiceState = label == null ? "语音识别暂停" : label;
            } else {
                stopWake();
                voiceState = "未开启";
                voiceLevel = 0;
            }
            updateNoti(voiceState);
            return;
        }

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            voiceState = "缺少录音权限";
            updateNoti("缺少录音权限（授权后几秒自动开）");
            // 用户在系统弹窗里授权后没有回调：过 5 秒自己再试一次，
            // 直到拿到权限（用户反馈：开 App 后本地语音不启动，就是这个原因卡住没重试）
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    applyVoiceSetting();
                }
            }, 5000);
            return;
        }

        if (wake == null) {
            voiceState = "唤醒词启动中…";
            updateNoti("唤醒词启动中（首次需解包模型）");
            wake = new WakeEngine(this, engineListener());
            wake.setVocab(vocab);
            wake.start();
            // 引擎起来了，麦克风真的要用：把前台服务的麦克风类型补齐
            promoteForeground();
        } else {
            wake.setVocab(vocab);
            wake.resumeCapture();
        }

        if (label != null) {
            voiceState = label;
            updateNoti(label);
        }
    }

    private WakeEngine.Listener engineListener() {
        return new WakeEngine.Listener() {
            @Override
            public void onReady() {
                voiceState = "在听唤醒词";
                updateNoti("在听命令");
            }

            @Override
            public void onStatus(String msg) {
                voiceState = msg;
                updateNoti(msg);
            }

            @Override
            public void onWake(String text) {
                lastHeard = text;
                String h = recent == null ? "" : recent;
                recent = h.length() == 0 ? text : (h + "  /  " + text);
                if (recent.length() > 120) {
                    recent = recent.substring(recent.length() - 120);
                }
                updateNoti("听到：" + text);

                AutoService svc = AutoService.get();
                if (svc != null) {
                    svc.onEnginePhrase(text);
                }
            }

            /**
             * 用户要求：日志要记录【听到了什么声音】，不只是记录执行了什么。
             * 这里只负责转发；日志由 AutoService.onHeardPhrase 写
             * （它会把“在不在命令表里”一并写清楚）。
             */
            @Override
            public void onHeard(String text) {
                lastHeard = text;
                AutoService svc = AutoService.get();
                if (svc != null) {
                    svc.onHeardPhrase(text);
                } else {
                    note("听到：「" + text + "」");
                }
            }

            @Override
            public void onLevel(int level) {
                voiceLevel = level;
            }

            @Override
            public void onError(String msg) {
                voiceState = msg;
                updateNoti(msg);
                stopWake();
            }
        };
    }

    private void stopWake() {
        if (wake != null) {
            wake.stop();
            wake = null;
        }
    }

    // ==================== 摇一摇 ====================

    private void startShake() {
        try {
            sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
            if (sensorManager == null) {
                return;
            }
            Sensor acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (acc == null) {
                return;
            }
            shakeListener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    float x = event.values[0];
                    float y = event.values[1];
                    float z = event.values[2];
                    float g = (float) Math.sqrt(x * x + y * y + z * z) / 9.81f;
                    // 真摇 = 900ms 内连续凑够 3 个超过 2.2g 的峰（用户要求：不用用力摇，
                    // 以前是 2.6g，太费劲）。仍要 3 个峰 + 冷却，防拿放手机/振动误触发。
                    if (g > 2.2f) {
                        long now = System.currentTimeMillis();
                        if (now - shakeWindowAt > 900) {
                            shakeWindowAt = now;
                            shakePeaks = 0;
                        }
                        shakePeaks++;
                        if (shakePeaks >= 3 && now - lastShake > 2500) {
                            lastShake = now;
                            shakePeaks = 0;
                            onShake();
                        }
                    }
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                }
            };
            sensorManager.registerListener(shakeListener, acc, SensorManager.SENSOR_DELAY_GAME);
        } catch (Exception ignored) {
        }
    }

    private void onShake() {
        try {
            if (!getSharedPreferences("cfg", MODE_PRIVATE)
                    .getBoolean("shakeOn", true)) {
                return;
            }
            AutoService svc = AutoService.get();
            if (svc == null) {
                return;
            }
            // 用户要求：摇一摇只在【短剧模式】里起作用
            if (!svc.shortDramaOn()) {
                KeepService.note("摇一摇忽略了：短剧模式才起作用（说「短剧」开启）");
                return;
            }
            // 用户要求（v2.30）：摇一摇 = 广告命令（短剧模式）
            if (!svc.isIdle()) {
                return;
            }
            KeepService.note("摇一摇（短剧模式）→ 广告命令");
            if (!svc.executeCommand(Commands.CMD_SKIP_AD, "摇一摇")) {
                KeepService.note("摇一摇的广告命令被拦（原因看上一条日志）");
            }
        } catch (Exception ignored) {
        }
    }

    private void stopShake() {
        try {
            if (sensorManager != null && shakeListener != null) {
                sensorManager.unregisterListener(shakeListener);
            }
        } catch (Exception ignored) {
        }
    }

    // ==================== 通知 ====================

    private void updateNoti(String line) {
        lastLine = line;
        // 把最近几条留起来：平时只看到最后一行（“在听唤醒词”会把前面的事全盖掉），
        // 展开通知就能看到刚才到底执行了什么、又听到了什么
        try {
            String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date());
            notiLog.addLast(ts + "  " + line);
            while (notiLog.size() > 5) {
                notiLog.removeFirst();
            }
        } catch (Throwable ignored) {
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTI_ID, buildNotification());
            }
        } catch (Exception ignored) {
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "后台常驻", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        // 不展开也能看到最近 3 条（以前只显示最后一行，全被“在听唤醒词”盖掉了）
        StringBuilder plain = new StringBuilder();
        int skip = Math.max(0, notiLog.size() - 3);
        int idx = 0;
        for (String s : notiLog) {
            if (idx++ < skip) {
                continue;
            }
            if (plain.length() > 0) {
                plain.append('\n');
            }
            plain.append(s);
        }
        String content = plain.length() > 0 ? plain.toString() : lastLine;

        b.setContentTitle("手机助手 · 运行中")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);

        // 展开后能看到最近几行（排查命令到底执行了什么）
        StringBuilder sb = new StringBuilder();
        for (String s : notiLog) {
            sb.append(s).append('\n');
        }
        if (sb.length() > 0) {
            try {
                b.setStyle(new Notification.BigTextStyle().bigText(sb.toString().trim()));
            } catch (Throwable ignored) {
            }
        }

        if (Build.VERSION.SDK_INT < 26) {
            b.setPriority(Notification.PRIORITY_MIN);
        }
        return b.build();
    }
}
