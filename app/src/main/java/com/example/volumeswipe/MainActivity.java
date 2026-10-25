package com.example.volumeswipe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private TextView tvStatus;
    private CheckBox cbDoubao;
    private SharedPreferences sp;
    private boolean syncingDoubao = false;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    /** 语音说了「打开豆包 / 关闭豆包」之后，设置里那个勾也要跟着变 */
    private final Runnable syncDoubaoTask = new Runnable() {
        @Override
        public void run() {
            syncDoubaoCheck();
            ui.postDelayed(this, 800);
        }
    };

    private void syncDoubaoCheck() {
        if (cbDoubao == null || sp == null) {
            return;
        }
        boolean want = sp.getBoolean("useDoubao", false);
        if (cbDoubao.isChecked() != want) {
            syncingDoubao = true;
            try {
                cbDoubao.setChecked(want);
            } finally {
                syncingDoubao = false;
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            initUi();
        } catch (Throwable t) {
            showCrash(t);
        }
    }

    private void initUi() {
        setContentView(R.layout.activity_main);

        sp = getSharedPreferences("cfg", MODE_PRIVATE);
        tvStatus = findViewById(R.id.tvStatus);
        // 唤醒/监听不再提供开关（界面已去掉那行），永远开启
        sp.edit().putBoolean("voiceOn", true).apply();

        // 录音 + 通知栏权限一起要（Android 13+ 不给通知权限，常驻通知根本不显示）
        askAudioPermission();

        // ① 后台运行
        findViewById(R.id.btnBattery).setOnClickListener(v -> {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Exception ignored) {
                }
            }
        });

        // ② 悬浮窗权限
        findViewById(R.id.btnOverlayPerm).setOnClickListener(v -> {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                toast("打不开授权页，请手动到设置里授予「显示在其他应用上层」");
            }
        });

        // ③ 无障碍
        findViewById(R.id.btnOpen).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        // ④ 常驻
        findViewById(R.id.btnKeep).setOnClickListener(v -> {
            askAudioPermission();
            KeepService.start(this);
            toast("已请求常驻，看通知栏那条「运行中」");
            tvStatus.postDelayed(this::refreshStatus, 800);
        });

        // 摇一摇开关
        CheckBox cbShake = findViewById(R.id.cbShake);
        cbShake.setChecked(sp.getBoolean("shakeOn", true));
        cbShake.setOnCheckedChangeListener((b, checked) -> {
            sp.edit().putBoolean("shakeOn", checked).apply();
            KeepService.applyVoiceSetting(this);
            toast(checked ? "摇一摇已开启" : "摇一摇已关闭");
        });

        // 唤醒动作：什么都不做（不静音、不点屏幕）

        // 🎬 短剧模式没有勾：语音说「短剧」切换（像「豆包」一样），状态只在下面状态区里看
        // 🔴 录制动作：给一串点/划起个语音命令名，以后说一声就自动做
        findViewById(R.id.btnRecord).setOnClickListener(v -> showRecordDialog());

        // 豆包语音输入法开关（默认勾选）
        // 用户要求（v2.30）：去掉豆包输入法勾选 —— 不再展示，豆包固定不用做勾选开关
        //（豆包 IME 本身仍留给文本命令框点 Mic 用）
        cbDoubao = findViewById(R.id.cbDoubao);
        cbDoubao.setVisibility(android.view.View.GONE);
        sp.edit().putBoolean("useDoubao", false).apply();

        // ⚙ 参数
        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettings());

        // ☀ 允许修改系统设置（亮/暗命令需要）
        findViewById(R.id.btnWrite).setOnClickListener(v -> {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
                toast("把「允许修改系统设置」打开，然后返回");
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS));
                    toast("把「允许修改系统设置」打开，然后返回");
                } catch (Exception e2) {
                    toast("打不开系统设置页，请手动到 设置 → 应用 → 手机助手 → 修改系统设置");
                }
            }
        });

        // 📋 最近日志：通知栏看不全时用这个看（排错用）
        findViewById(R.id.btnLog).setOnClickListener(v -> {
            refreshStatusInner();
            String log = KeepService.recentLog();
            showTextDialog("最近日志（最近 5 条）",
                    log.length() > 0 ? log
                            : "还没有记录。\n\n先点 ④ 开启后台常驻服务，再说一句「嗨小白」试试。");
        });

        // ▶ 测试Mic：弹出编辑框 + 豆包键盘，你点一下键盘上的 Mic，程序记住它的位置
        findViewById(R.id.btnTestMic).setOnClickListener(v -> {
            AutoService svc = AutoService.get();
            if (svc == null) {
                toast("请先在第 ③ 步里打开无障碍服务");
                return;
            }
            svc.startMicTest();
        });

        // ⑤ 退出
        findViewById(R.id.btnExit).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("退出")
                .setMessage("退出：命令条消失、语音暂停、通知变成「已暂停」。\n"
                        + "不会去改系统设置里的无障碍开关（服务保持活着，避免被手机自动关）；"
                        + "下次打开 App 自动恢复。")
                .setPositiveButton("退出", (d, w) -> doExit())
                .setNegativeButton("取消", null)
                .show());

        // 打开 App = 恢复运行
        sp.edit().putBoolean("enabled", true).apply();
        KeepService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 打开 App = 恢复运行：服务被杀（含 ✕ 退出后重进）就自动拉起
        if (sp != null && sp.getBoolean("enabled", true) && !KeepService.alive) {
            KeepService.start(this);
        }
        // 用户反馈：非豆包模式下本地语音没启动。每次回到 App 都重新应用一次语音设置，
        // 引擎没起就起（applyVoiceSetting 内部有“正在识别时不打断”保护）
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                KeepService.applyVoiceSetting(MainActivity.this);
                // 用户反馈：退过一次程序后蓝牙音量键完全没反应且日志什么都没有——
                // 根因：小白再见会停掉音量轮询，重开 App 时服务不会重新连接，
                // 轮询就再也没人重启。这里每次回 App 都强制重启一次（幂等，不会重复）
                AutoService svc = AutoService.get();
                if (svc != null && sp.getBoolean("enabled", true)) {
                    svc.startVolPoller();
                }
            }
        }, 800);
        syncDoubaoCheck();
        ui.removeCallbacks(syncDoubaoTask);
        ui.postDelayed(syncDoubaoTask, 800);
        refreshStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(syncDoubaoTask);
    }

    private void showSettings() {
        View v = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        final EditText etDur = v.findViewById(R.id.dlgDur);
        final EditText etGap = v.findViewById(R.id.dlgGap);
        final EditText etMicX = v.findViewById(R.id.dlgMicX);
        final EditText etMicY = v.findViewById(R.id.dlgMicY);
        final EditText etListen = v.findViewById(R.id.dlgListen);
        final EditText etMicDelay = v.findViewById(R.id.dlgMicDelay);
        final EditText etVolMs = v.findViewById(R.id.dlgVolMs);

        etDur.setText(String.valueOf(sp.getInt("dur", 300)));
        etGap.setText(String.valueOf(sp.getInt("gap", 2000)));
        etMicX.setText(String.valueOf(sp.getInt("micx", 32)));
        etMicY.setText(String.valueOf(sp.getInt("micy", 66)));
        etListen.setText(String.valueOf(sp.getInt("listenms", 3000) / 1000));
        etMicDelay.setText(String.valueOf(sp.getInt("micdelay", 200)));
        etVolMs.setText(String.valueOf(sp.getInt("volDoubleMs", 1500)));

        new AlertDialog.Builder(this)
                .setTitle("参数设置")
                .setView(v)
                .setPositiveButton("保存", (d, which) -> {
                    int durMs = clamp(parseInt(etDur.getText().toString(), 300), 50, 5000);
                    int gapMs = clamp(parseInt(etGap.getText().toString(), 2000), 800, 10000);
                    int micX = clamp(parseInt(etMicX.getText().toString(), 22), 0, 100);
                    int micY = clamp(parseInt(etMicY.getText().toString(), 66), 0, 100);
                    int listenSec = clamp(parseInt(etListen.getText().toString(), 3), 3, 20);
                    int micDelayMs = clamp(parseInt(etMicDelay.getText().toString(), 200), 0, 3000);
                    int volMs = clamp(parseInt(etVolMs.getText().toString(), 200), 200, 5000);

                    sp.edit()
                            .putInt("dur", durMs)
                            .putInt("gap", gapMs)
                            .putInt("micx", micX)
                            .putInt("micy", micY)
                            .putInt("listenms", listenSec * 1000)
                            .putInt("micdelay", micDelayMs)
                            .putInt("volDoubleMs", volMs)
                            .apply();

                    toast("已保存");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 🎬 短剧模式 + 🔴 录制动作：列表 / 删除 / 新建 */
    private void showRecordDialog() {
        RecordedCmds.reload(this);
        final String[] names = RecordedCmds.readNames(this);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(36, 36, 36, 24);

        TextView tip = new TextView(this);
        tip.setTextSize(13f);
        tip.setText("用语音说「录制」就能开始（最快）：开始后直接去做动作，\n"
                + "每个动作都会【真的执行】（点哪点哪、滑哪滑哪，包括上滑/左滑）并被记下来，\n"
                + "做完再说一声「录制」或「录制结束」→ 弹出文本框起个命令名 → 保存。\n"
                + "以后说这个名字，程序就自动重做这串动作（含间隔，每步再加 0.2 秒延时）。\n"
                + "【管理】下面的列表就是全部录制命令：录得不要了/不满意，\n"
                + "点右边的「删除」去掉再重录一遍就行。\n"
                + "短剧模式里自己录的命令也能用。名字最好 2 个字以上（单字容易被台词撞上）。\n"
                + "（录制时屏幕最下面只有一条小字，不挡操作；每一步在日志里都有记录。）");
        col.addView(tip);

        TextView listTitle = new TextView(this);
        listTitle.setTextSize(13f);
        listTitle.setPadding(0, 24, 0, 6);
        listTitle.setText(names.length == 0 ? "已录制：（还没有）" : "已录制（点右边的「删除」可去掉，去掉后可重新录）：");
        col.addView(listTitle);

        final AlertDialog[] holder = new AlertDialog[1];
        for (final String n : names) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView tv = new TextView(this);
            tv.setTextSize(13f);
            tv.setText(n + " —— " + RecordedCmds.describe(this, n));
            row.addView(tv, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            Button del = new Button(this);
            del.setText("删除");
            del.setOnClickListener(v -> {
                RecordedCmds.remove(MainActivity.this, n);
                RecordedCmds.reload(MainActivity.this);
                AutoService svc = AutoService.get();
                if (svc != null) {
                    svc.refreshVocabNow();
                }
                toast("已删除「" + n + "」");
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                showRecordDialog();
            });
            Button ren = new Button(this);
            ren.setText("改名");
            ren.setOnClickListener(v -> {
                final EditText input = new EditText(MainActivity.this);
                input.setText(n);
                // 用户反馈：默认浅底配白字看不见 → 黑底白字，肯定看得清
                input.setTextColor(0xFFFFFFFF);
                input.setHintTextColor(0xFFAAAAAA);
                input.setBackgroundColor(0xFF222222);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("改命令名：「" + n + "」")
                        .setView(input)
                        .setPositiveButton("保存", (d, w) -> {
                            String nn = Commands.normalize(input.getText().toString());
                            if (nn.length() == 0) {
                                toast("名字不能为空");
                                return;
                            }
                            RecordedCmds.rename(MainActivity.this, n, nn);
                            RecordedCmds.reload(MainActivity.this);
                            AutoService svc = AutoService.get();
                            if (svc != null) {
                                svc.refreshVocabNow();
                            }
                            toast("已改名：「" + n + "」→「" + nn + "」");
                            if (holder[0] != null) {
                                holder[0].dismiss();
                            }
                            showRecordDialog();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
            row.addView(ren);
            row.addView(del);
            col.addView(row);
        }

        holder[0] = new AlertDialog.Builder(this)
                .setTitle("🔴 录制动作（自定义语音命令）")
                .setView(col)
                .setPositiveButton("▶ 现在就开始录制", (d, w) -> {
                    AutoService svc = AutoService.get();
                    if (svc == null) {
                        toast("请先在第 ③ 步里打开无障碍服务");
                        return;
                    }
                    // 名字先不起：做完动作【双击屏幕】结束 → 弹出文本框再起名
                    svc.startRecordingCmd("");
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void doExit() {
        sp.edit().putBoolean("enabled", false).apply();
        AutoService svc = AutoService.get();
        if (svc != null) {
            svc.shutdownAll();
        }
        KeepService.stop(this);
        finishAffinity();
    }

    private void refreshStatus() {
        try {
            refreshStatusInner();
        } catch (Throwable t) {
            if (tvStatus != null) {
                tvStatus.setText("状态刷新出错：" + t);
            }
        }
    }

    private void refreshStatusInner() {
        boolean acc = isServiceEnabled();
        boolean keep = KeepService.alive;
        boolean ignored = isIgnoringBattery();
        boolean canOverlay = canDrawOverlays();
        boolean shakeOn = sp.getBoolean("shakeOn", true);
        boolean doubao = sp.getBoolean("useDoubao", false);

        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e2) {
        }
        String s = "版本：" + ver + "（v20）\n"
                + "无障碍服务：" + (acc ? "已开启 ✓" : "未开启 ✗  请点 ③") + "\n"
                + "后台常驻服务：" + (keep ? "运行中 ✓" : "未运行 ✗  请点 ④") + "\n"
                + "后台运行权限：" + (ignored ? "已允许 ✓" : "未允许 ✗  请点 ①") + "\n"
                + "悬浮窗权限：" + (canOverlay ? "已授予 ✓" : "未授予 ✗  请点 ②") + "\n"
                + "摇一摇：" + (shakeOn ? "已开启 ✓（只在短剧模式里起作用：只上滑）" : "已关闭") + "\n"
                + "短剧模式：" + (sp.getBoolean("shortDrama", false)
                        ? "已开启（只听 停·播放 和自己录的命令；上键=上拉 下键=下集 摇一摇=广告；大/小、亮/暗、禁音/声音、录制 全局能用；说「短剧」退出）"
                        : "已关闭（收所有命令；说「短剧」开启；退出程序自动关）") + "\n"
                + "唤醒动作：什么都不做（不静音、不点屏幕）\n"
                + "修改系统设置（亮/暗）：" + (Settings.System.canWrite(this)
                        ? "已允许 ✓" : "未允许 ✗  请点 ☀") + "\n"
                + "语音监听：开启 ✓\n"
                + "豆包输入法：" + (doubao ? "使用 ✓" : "不用（纯本地识别）") + "\n"
                + "语音状态：" + KeepService.voiceState + "\n"
                + "上次听到：" + (KeepService.lastHeard.length() > 0
                        ? KeepService.lastHeard : "（无）") + "\n"
                + (acc ? "\n摇一摇或说「嗨小白」即可触发。" : "");
        // 最近发生了什么（通知栏只能看到几行，这里能看到全部）
        String log = KeepService.recentLog();
        if (log.length() > 0) {
            s = s + "\n\n— 最近日志 —\n" + log;
        }
        tvStatus.setText(s);
    }

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return Settings.canDrawOverlays(this);
    }

    private void askAudioPermission() {
        java.util.ArrayList<String> need = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            need.add("android.permission.POST_NOTIFICATIONS");
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 101);
        }
    }

    private int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private int parseInt(String s, int def) {
        try {
            return (int) Math.round(Double.parseDouble(s.trim()));
        } catch (Exception e) {
            return def;
        }
    }

    private double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        ComponentName cn = new ComponentName(this, AutoService.class);
        return enabled.contains(cn.flattenToString())
                || enabled.contains(cn.flattenToShortString());
    }

    private void toast(String s) {
        try {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    private boolean isIgnoringBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void showTextDialog(String title, String msg) {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        TextView tv = new TextView(this);
        tv.setPadding(36, 48, 36, 36);
        tv.setTextSize(13f);
        tv.setTextIsSelectable(true);
        tv.setText(msg);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showCrash(Throwable t) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            String msg = sw.toString();
            if (msg.length() > 2500) {
                msg = msg.substring(0, 2500);
            }

            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            TextView tv = new TextView(this);
            tv.setPadding(36, 72, 36, 36);
            tv.setTextSize(12f);
            tv.setText("启动出错，把这段截图发我：\n\n" + msg);
            sv.addView(tv);
            setContentView(sv);
        } catch (Throwable ignored) {
        }
    }
}
