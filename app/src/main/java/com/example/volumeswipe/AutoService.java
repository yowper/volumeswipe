package com.example.volumeswipe;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 手势与命令条服务（无障碍）。
 *
 * 屏幕上常驻一条小小的命令条（待命时只显示“待命中·嗨小白”，不抢焦点）。
 *
 * 两套识别路线，由主界面「使用豆包语音输入法」开关决定：
 *
 * ① 勾选豆包（默认）：
 *    听到「嗨小白」→ 点 (50%,15%) 暂停视频 → 输入框原地出现，但【不弹键盘】；
 *    本地引擎换到命令词表继续听，听到的第一句（去掉唤醒词）自动放进输入框；
 *    本地听清了 → 不点豆包Mic，很快收尾执行；
 *    本地没听清（约 1 秒）→ 这时才抢焦点、弹键盘，并尽快点一次豆包输入法的
 *    麦克风，本地引擎立刻暂停（麦克风让给豆包）；
 *    豆包识别最长 10 秒（文字稳定后可提前结束）→ 输入框自动失去焦点、键盘收起
 *    → 框里的内容就是命令 → 执行 → 回到待命。
 *    一个唤醒周期内麦克风最多点一次，绝不再找第二次。
 *
 * ② 不勾选豆包：
 *    全部由本地引擎识别。
 *    听到命令直接执行，不放输入框。
 *
 * 另外提供：跳过广告/退出直播的「监视读秒 → 自动上滑」：
 * 盯屏幕下方 20% 的文字，文字还在变 = 读秒在走；不再变 = 读秒结束 → 上滑；
 * 滑完 3 秒发现还在原页（读秒还在 / 文字几乎没变）→ 再滑，最多 3 次。
 */
public class AutoService extends AccessibilityService {

    private static final int PHASE_IDLE = 0;    // 在听唤醒词
    private static final int PHASE_ARMED = 1;   // 已唤醒：本地引擎在听命令
    private static final int PHASE_DOUBAO = 2;  // 豆包输入法在识别（本地引擎已暂停）

    /** 唤醒后先让本地语音包听命令的时间：这段时间【不】弹输入法，本地识别直接落框 */
    private static final long VOSK_WINDOW_MS = 1000;
    /** 本地没听清、要交给豆包时：弹出输入法后隔多久点它的麦克风（键盘一出来就点，不用等） */
    private static final long MIC_CLICK_DELAY_MS = 0;
    /** 找麦克风按钮（等键盘出现）的重试间隔 */
    private static final long MIC_POLL_MS = 80;
    /** 点完麦克风后，给豆包语音界面留的启动时间 */
    private static final long DOUBAO_GRACE_MS = 2500;
    /** 豆包往框里打字停止多久算“说完了” */
    private static final long STABLE_MS = 3500;
    /** 框里少于等于这么多字 = 一个命令的长度：停一下就立即当命令收尾，不干等总时长 */
    private static final int SHORT_CMD_CHARS = 6;
    /** 已经能对上命令的：不变这么久就收尾 */
    private static final long SHORT_MATCH_MS = 300;
    /** 短命令要求的不变时长（还认不出是哪个命令时多等一会儿，避免只执行半截话） */
    private static final long SHORT_STABLE_MS = 800;
    /** 点开 Mic 后给豆包的默认时间（毫秒）；设置里可改，最小 3 秒 */
    private static final int DOUBAO_WINDOW_DEF_MS = 3000;
    /** 本地语音包已经把命令放进框：多久就收尾执行（不等豆包） */
    private static final long MIC_QUIET_MS = 800;

    /** 同一条命令 1.5 秒内只执行一次（本地引擎同一句会反复上报） */
    private static final long REPEAT_GUARD_MS = 1500;
    /** 成对命令（声音大/小、上一集/下一集、上页/下页）3 秒内不许反着再来 */
    private static final long OPPOSITE_GUARD_MS = 3000;
    /**
     * 用户要求：不管什么命令，上滑/下滑都只能滑一次，不能连着好几次。
     * 本地识别把同一句话反复报出来时，间隔往往超过 1.5 秒（REPEAT_GUARD 盖不住），
     * 所以滑动类命令单独用 3 秒的大窗口。
     */
    private static final long SWIPE_GUARD_MS = 3000;
    /** 手势层面的硬锁：上滑/下滑两次之间至少隔 0.9 秒（不管是哪条路径叫的） */
    private static final long SWIPE_MIN_GAP_MS = 900;
    /** 录制重放：相邻两个手势至少隔 0.3 秒（用户要求动作之间要有一定的延时） */
    private static final long REPLAY_GAP_MS = 300;
    /** 录制重放：在录制间隔的基础上额外多等 0.2 秒（页面动画需要时间） */
    private static final long REPLAY_EXTRA_MS = 200;

    /** 暂停 / 恢复播放的点击点：X 50%、Y 20%（用户规定） */
    private static final int PAUSE_X = 50;
    private static final int PAUSE_Y = 20;
    /** 读秒区域：屏幕下方 20% 起算（节点中心 Y ≥ 屏高 × 该值）
     *  只取这一小块：视频下方才显示读秒，取大了会把视频里的东西也算进来 */
    private static final float REGION_TOP_FRAC = 0.80f;
    /** 读秒像素比对区域：屏幕下方 12% 起算。
     *  实测读秒那一行（如「10 秒后可继续看下一集」／「5s 跳过」）就在屏幕最底下，
     *  只比最底下这一条，不会被视频画面 / 用户名 / 标题的动画干扰 */
    private static final float SEC_TOP_FRAC = 0.88f;

    private static AutoService sInstance;

    public static AutoService get() {
        return sInstance;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int phase = PHASE_IDLE;
    // 状态卡死看门狗用：从非待命开始到现在的时间（超过 2 分钟没在做事就强制回待命）
    private long nonIdleSince = 0;
    private boolean busy = false;
    /** 上一次上滑/下滑的时间：用户要求一次只能滑一次，不能连滑 */
    private long lastSwipeAt = 0;
    private boolean skipWatching = false;
    private boolean micClicked = false;      // 本轮唤醒周期里是否已点过豆包麦克风
    private boolean micTestMode = false;     // 「测试Mic」标定中：不自动点Mic、不扫豆包面板
    private boolean selfSettingText = false; // 程序自己往框里放字，不算用户输入
    private long armAt = 0;                  // 进入已唤醒的时刻（丢弃换词表残句）
    private long armedDeadline = 0;          // 已唤醒阶段超时
    private long doubaoStartAt = 0;          // 豆包阶段开始时刻
    private long doubaoDeadline = 0;         // 豆包最长识别时刻
    private long lastBoxChangeAt = 0;        // 框里文字最后一次变化的时刻
    private String lastBoxText = "";
    private String snapAtMic = "";           // 点麦克风那一刻框里已有的文字
    private long micQuietDeadline = 0;       // 豆包一直没动静时的收尾时刻
    private int micAttempts = 0;             // 点Mic尝试次数
    private boolean micBlindDone = false;    // 是否已按标定坐标盲点过
    private boolean micDelayDone = false;    // 「点 Mic 前延时」是否已等过
    private String lastLocalText = "";       // 免豆包模式：同一句话去重
    private long lastLocalAt = 0;
    private int lastLocalCmd = 0;            // 上一次执行的命令（反向抑制用）
    private long lastLocalCmdAt = 0;
    /** 刚执行过的命令（防重复/防反向误听，见 executeCommand 开头） */
    private int lastExecCmd = 0;
    private long lastExecAt = 0;
    private String lastExecText = "";      // 唤醒时点了暂停，命令执行前要先恢复播放
    private long busyUntil = 0;

    private WindowManager wm;

    // 屏幕上的命令条（待命） / 输入框（唤醒后）
    private FrameLayout box;
    private TextView idleTv;
    private EditText boxEdit;
    private WindowManager.LayoutParams boxLp;
    private FrameLayout pickerMic;
    private FrameLayout probePanel;   // 「测试读秒」的结果面板
    private int[] lastStripSig;        // 上一张截屏「下方 20%」的灰度指纹（用于比较像素变化）

    // 「监视读秒 → 上滑」的状态（同一时刻只有一个）
    private int pwSame;                // 连续几轮像素没变
    private boolean pwSawChange;       // 看到过像素变化
    private int pwWaited;              // 已等了多少毫秒
    private boolean pwSawCountdown;    // OCR 读到过读秒
    private int pwOcrMiss;             // 连着几轮 OCR 没读到「秒」
    private long pwEndAt;              // 按读到的秒数算出的结束时刻
    private int pwSeenSec = -1;        // 上一次读到的秒数
    private String pwTag = "";

    private final Runnable tickTask = new Runnable() {
        @Override
        public void run() {
            if (phase == PHASE_IDLE) {
                nonIdleSince = 0;
                return;
            }
            long now = System.currentTimeMillis();
            if (nonIdleSince == 0) {
                nonIdleSince = now;
            } else if (now - nonIdleSince > 120000
                    && !skipWatching && recLayer == null && namingLayer == null) {
                // 用户反馈：跑一段时间后本地语音不灵，重进 App 才好。
                // 八成是状态卡在非待命（麦克风一直让出去）→ 强制复位。
                KeepService.note("命令状态卡了 2 分钟没结束 → 强制复位到待命，语音重新听");
                phase = PHASE_IDLE;
                nonIdleSince = 0;
                KeepService.applyVoiceSetting(getApplicationContext());
                return;
            }
            tickOnce();
            if (phase != PHASE_IDLE) {
                handler.postDelayed(this, 300);
            }
        }
    };

    private final Runnable matchTask = new Runnable() {
        @Override
        public void run() {
            // ARMED：用户手动打字（豆包阶段不走这条快路：豆包的字是边说边出来的，
            // 太早试执行会“半天就关框”，把还在说的话截断）
            if (phase != PHASE_ARMED) {
                return;
            }
            String t = boxText();
            if (t.length() == 0) {
                return;
            }
            int cmd = resolveCmd(t, true);
            if (cmd != Commands.CMD_NONE) {
                if (executeCommand(cmd, t)) {
                    toastResult(cmd, "执行：" + t);
                    KeepService.note("已执行：" + t);
                }
                backToIdle();
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        phase = PHASE_IDLE;
        // 已退出（enabled=false）时被系统重新连上：不要拉起待命编辑框，
        // 保持「无障碍开关不动 + 屏幕干净」（用户要求：退出后待命编辑框要退）
        if (!getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("enabled", true)) {
            removeBox();
            removeStatusStripNow();
            return;
        }
        // 用户要求：软件开的时候，豆包模式、短剧模式都是【关】的
        try {
            getSharedPreferences("cfg", MODE_PRIVATE).edit()
                    .putBoolean("useDoubao", false)
                    .putBoolean("shortDrama", false).apply();
        } catch (Throwable ignored) {
        }
        // 蓝牙耳机音量下调两次（短剧模式=上拉）靠轮询媒体音量
        startVolPoller();
        // 录制模式：用户自己录的命令名要先进词表（说得出、听得懂）
        try {
            RecordedCmds.reload(this);
        } catch (Throwable ignored) {
        }
        try {
            showIdleBox();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    // ==================== 蓝牙耳机音量下调两次（只在短剧模式） ====================
    // 用户要求：手机音量键双击那条命令删掉（没实现）；但蓝牙耳机在耳机上把音量调小，
    // 手机这边看得到媒体音量在变小 —— 所以改成监听系统音量变化：
    //   短剧模式 + 一定时间内（⚙ 可改，默认 1500ms）连续两次音量下调
    //   = 执行上拉命令（下一集），同时把音量还原（等于音量大一点按两次）。
    // 非短剧模式：音量变化就是调音量，不做任何动作。

    /** 音量下调两次的识别窗口（用户可改、自动记住；默认 1000，限 200~5000） */
    private long volDoubleMs() {
        int v = getSharedPreferences("cfg", MODE_PRIVATE).getInt("volDoubleMs", 200);
        if (v < 200) {
            v = 200;
        }
        if (v > 5000) {
            v = 5000;
        }
        return v;
    }

    private int lastKnownVol = -1;
    // 音量心跳计数（蓝牙连着时约 10 秒报一次当前音量，方便用户看日志排查）
    private int volBeat = 0;
    // 音量在 0 的提示只报一次（0 时耳机再往下调手机收不到）
    private boolean volZeroNoted = false;

    /** 蓝牙音频（A2DP）现在是否连着（诊断日志用） */
    private static boolean btAudioOn(AudioManager am) {
        try {
            return am.isBluetoothA2dpOn();
        } catch (Throwable t) {
            return false;
        }
    }
    /** 下调前的媒体音量，触发后还原用它 */
    private int volBeforeBtDrops = -1;

    /** 每 100ms 读一次媒体音量，只在屏幕亮时采（比广播可靠：
     *  部分机型 VOLUME_CHANGED_ACTION 收不到或一次按键发好几条，都会漏/误判） */
    private static final long VOL_POLL_MS = 100;

    /** 程序自己改的音量（语音 大/小、翻页补回、还原）——轮询跳过这次变化，不触发动作 */
    private boolean volSelfChange = false;

    private void markVolSelf() {
        volSelfChange = true;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                volSelfChange = false;
            }
        }, 500);
    }

    private final Runnable volPollTask = new Runnable() {
        @Override
        public void run() {
            try {
                android.os.PowerManager pm =
                        (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                boolean screenOn = pm == null || pm.isInteractive();
                if (screenOn) {
                    AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        int v = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                        if (lastKnownVol >= 0 && v != lastKnownVol) {
                            // 用户要求：日志里记下到底收到了什么，方便排查耳机音量键
                            KeepService.note("音量采样：" + lastKnownVol + " → " + v
                                    + (v < lastKnownVol ? "（下调）" : "（上调）")
                                    + (btAudioOn(am) ? "（蓝牙已连）" : "（蓝牙未连）")
                                    + (volSelfChange ? "（程序自己调的，忽略）" : ""));
                            if (volSelfChange) {
                                // 程序自己调的（语音 大/小、翻页补回、还原音量）——不当按键处理
                            } else if (v < lastKnownVol) {
                                onVolumeDropped(lastKnownVol);
                            } else {
                                onVolumeRaised(lastKnownVol);
                            }
                        }
                        lastKnownVol = v;
                        if (v > 0) {
                            volZeroNoted = false;
                        } else if (btAudioOn(am) && !volZeroNoted) {
                            // 音量留在 0 的话，耳机再往下调手机这边根本看不到（就翻不了页/上拉不了）
                            volZeroNoted = true;
                            KeepService.note("媒体音量在 0：耳机再往下调手机收不到（无法触发）；"
                                    + "按一下耳机音量大键抬到 1 以上就恢复了");
                        }
                        if (++volBeat >= 100) { // 100ms × 100 ≈ 10 秒一次心跳
                            volBeat = 0;
                            // 用户反馈：日志里什么都看不到 → 心跳无条件发，
                            // 轮询死没死、当前什么模式、静音没静音，一眼可见
                            KeepService.note("音量监视在跑：当前 " + v
                                    + (btAudioOn(am) ? "（蓝牙已连）" : "（蓝牙未连）")
                                    + " 阅读=" + (readingOn() ? "开" : "关")
                                    + " 短剧=" + (shortDramaOn() ? "开" : "关")
                                    + " 静音=" + (getSharedPreferences("cfg", MODE_PRIVATE)
                                            .getBoolean("soundMuted", false) ? "开" : "关")
                                    + "（按耳机音量小键后这个数应该变）");
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            handler.postDelayed(this, VOL_POLL_MS);
        }
    };

    public void startVolPoller() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                lastKnownVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            }
        } catch (Throwable ignored) {
        }
        handler.removeCallbacks(volPollTask);
        handler.postDelayed(volPollTask, VOL_POLL_MS);
        KeepService.note("已在监听音量变化（短剧/阅读模式：下调一下=上拉/下页）");
    }

    private void stopVolPoller() {
        handler.removeCallbacks(volPollTask);
    }

    // ==================== 蓝牙音量键（v2.33） ====================
    // 短剧模式：上键按一下 = 播放/暂停（音量 -1 补回上调前）；
    //           下键按一下 = 摇一摇 = 广告命令（先静音→截图读秒→上拉→恢复声音）。
    //           音量还原规则（用户要求 v2.33 定稿）：
    //           · 摇一摇/语音的广告命令 → 恢复到禁音前的音量，不加一；
    //           · 只有蓝牙下键触发的那一轮 → 恢复时音量 +1 回到按下前的值
    //             （因为按下键系统已经把音量 -1 了，+1 才等于回到原音量）。

    /** 短剧模式、蓝牙音量下键：和摇一摇同一套广告命令流程。
     *  按下后系统已经把音量 -1，所以恢复时音量要 +1 回到按下前。 */
    private void fireVolDownDrama(final int orig) {
        if (!isIdle()) {
            KeepService.note("耳机音量下调忽略了：正在识别一轮命令");
            return;
        }
        KeepService.note("短剧：蓝牙耳机音量下键 → 广告命令（先静音 → 截图读秒 → 上拉 → 恢复声音），"
                + "恢复时音量 +1 还原到 " + orig);
        btVolRestoreTarget = orig;
        if (!executeCommand(Commands.CMD_SKIP_AD, "蓝牙耳机音量下键")) {
            btVolRestoreTarget = -1;
            KeepService.note("下键的广告命令被防重/守卫拦住（原因看上一条日志）");
            toast("广告命令被拦：可能刚执行过一次，原因看日志");
        }
    }

    /** 检测到一次音量上调（before = 上调前的音量） */
    private void onVolumeRaised(int before) {
        KeepService.note("音量上调进入处理：阅读=" + (readingOn() ? "开" : "关")
                + " 短剧=" + (shortDramaOn() ? "开" : "关")
                + " 待命=" + (isIdle() ? "是" : "否")
                + "（上调前音量 " + before + "）");
        if (!isIdle()) {
            KeepService.note("耳机音量上调忽略了：正在识别一轮命令");
            return;
        }
        // 阅读模式：蓝牙音量上键【一下】= 上一页 + 音量 -1 补回
        if (readingOn()) {
            fireVolPageUp(before);
            return;
        }
        if (shortDramaOn()) {
            // 用户要求 v2.33：短剧模式上键【一下】= 播放/暂停 + 音量 -1 补回上调前
            fireVolTogglePlay(before);
            return;
        }
        KeepService.note("耳机音量上调（普通模式只调音量，不触发动作）");
    }

    /** 蓝牙【下键】的音量还原目标（按下前的值）；-1 = 没有 override。
     *  摇一摇/语音的广告命令不设它（恢复到禁音前原音量，不加一）；
     *  只有下键那轮设：按下时系统已经 -1，恢复时 +1 回到这个值。 */
    private int btVolRestoreTarget = -1;

    /** 阅读模式、蓝牙音量上键：上一页 + 音量 -1 补回上调前 */
    private void fireVolPageUp(final int restore) {
        KeepService.note("蓝牙耳机音量上调了一下 → 上一页（翻书），音量 -1 补回 " + restore);
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        int now2 = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                        if (now2 > restore) {
                            markVolSelf();
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0);
                            KeepService.note("音量已补回：" + now2 + " → "
                                    + am.getStreamVolume(AudioManager.STREAM_MUSIC));
                        }
                    }
                } catch (Throwable t2) {
                    KeepService.note("音量补回失败：" + t2.getClass().getSimpleName());
                }
                if (executeCommand(Commands.CMD_PAGE_UP, "耳机音量上调")) {
                    toastResult(Commands.CMD_PAGE_UP, "耳机上调音量：上一页（翻书）");
                }
            }
        });
    }

    /** 短剧模式、蓝牙音量上键【一下】（用户要求 v2.33）：播放/暂停 + 音量 -1 补回上调前。
     *  结构和阅读模式的 fireVolPageUp 一样：先把系统按上键多出来的 +1 退回去，
     *  再在 (50%,20%) 点一下（播放/暂停命令就点这里）。 */
    private void fireVolTogglePlay(final int restore) {
        KeepService.note("蓝牙耳机音量上调了一下 → 播放/暂停，音量 -1 补回 " + restore);
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        int now2 = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                        if (now2 > restore) {
                            markVolSelf();
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0);
                            KeepService.note("音量已补回：" + now2 + " → "
                                    + am.getStreamVolume(AudioManager.STREAM_MUSIC));
                        }
                    }
                } catch (Throwable t2) {
                    KeepService.note("音量补回失败：" + t2.getClass().getSimpleName());
                }
                if (executeCommand(Commands.CMD_TOGGLE_PLAY, "耳机音量上调")) {
                    toastResult(Commands.CMD_TOGGLE_PLAY, "耳机上键：播放/暂停");
                }
            }
        });
    }

    /** 非短剧模式、按一下就触发：发下页命令（翻书）+ 音量补回下调前（用户要求） */
    private void fireVolPageOnce(final int restore) {
        if (!isIdle()) {
            return; // 正在识别一轮命令时不折腾
        }
        KeepService.note("蓝牙耳机音量下调了一下 → 下页（翻书），音量补回 " + restore);
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        // 补回下调前的音量；至少补到 1：
                        // 音量留在 0 的话，耳机再往下调手机这边根本看不到（就翻不了页了）
                        int target = Math.max(restore, 1);
                        int now2 = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                        if (now2 < target) {
                            markVolSelf();
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
                            KeepService.note("音量已补回：" + now2 + " → "
                                    + am.getStreamVolume(AudioManager.STREAM_MUSIC));
                        }
                    }
                } catch (Throwable t2) {
                    KeepService.note("音量补回失败：" + t2.getClass().getSimpleName());
                }
                if (executeCommand(Commands.CMD_PAGE_DOWN, "耳机音量下调一次")) {
                    toastResult(Commands.CMD_PAGE_DOWN, "耳机下调音量：下一页（翻书）");
                }
            }
        });
    }

    /** 检测到一次音量下调（before = 下调前的音量） */
    private void onVolumeDropped(int before) {
        boolean muted = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("soundMuted", false);
        // 用户反馈：短剧模式按一下没反应且日志什么都没有 → 每次下调都先写一条状态日志，
        // 是哪个环节拦的一眼就能看出来
        KeepService.note("音量下调进入处理：阅读=" + (readingOn() ? "开" : "关")
                + " 短剧=" + (shortDramaOn() ? "开" : "关")
                + " 静音标记=" + (muted ? "开" : "关")
                + " 待命=" + (isIdle() ? "是" : "否")
                + "（下调前音量 " + before + "）");
        // 静音期间的下调：只有广告读秒流程自己禁的音才忽略；
        // 用户自己「禁音」后的下调当真实按键处理（不然短剧/阅读永远没反应）
        if (muted) {
            if (skipWatching) {
                KeepService.note("静音标记是广告读秒流程开的 → 这次下调忽略");
                return;
            }
            getSharedPreferences("cfg", MODE_PRIVATE).edit()
                    .putBoolean("soundMuted", false).apply();
            KeepService.note("静音标记不是广告流程开的 → 解除，当真实按键处理");
        }
        // 阅读模式：蓝牙音量下键【一下】就翻页 + 音量补回
        if (readingOn()) {
            fireVolPageOnce(before);
            return;
        }
        if (!shortDramaOn()) {
            // 普通模式只是调音量
            KeepService.note("耳机音量下调（普通模式只调音量，不开阅读/短剧不触发动作）");
            return;
        }
        // 短剧模式（用户要求 v2.32）：【一下】就触发：蓝牙下键 = 广告命令 + 音量 +1 还原
        KeepService.note("蓝牙耳机音量下调了一下 → 广告命令，");
        fireVolDownDrama(before);
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        stopVolPoller();
        removeStatusStripNow();
        removeRecLayer();
        removeManualBox();
        removeNamingLayer();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sInstance = null;
        return super.onUnbind(intent);
    }

    public boolean isIdle() {
        return phase == PHASE_IDLE;
    }

    private boolean useDoubao() {
        // 用户要求：豆包模式默认【关】（免豆包直接听命令）
        return getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("useDoubao", false);
    }

    // ==================== 本地引擎回调 ====================

    /**
     * 用户要求：日志里要记录【听到了什么声音】，不只是记录执行了什么。
     * 本地引擎每识别出一句话就调这里（不管是不是命令、也不管有没有执行）。
     * 只写日志：不在命令表里的话不执行，也不在屏幕下方显示。
     */
    public void onHeardPhrase(String heard) {
        String t = Commands.normalize(heard);
        if (t.length() == 0) {
            return;
        }
        if (Commands.containsWakeWord(t)) {
            KeepService.note("听到：「" + t + "」（含唤醒词，不当命令）");
            return;
        }
        int cmd = Commands.match(t);
        if (cmd == Commands.CMD_NONE) {
            KeepService.note("听到：「" + t + "」→ 不在命令表里（不执行、不提示）");
            return;
        }
        KeepService.note("听到：「" + t + "」→ 命令：" + Commands.nameOf(cmd));
    }

    /**
     * KeepService 的本地识别引擎每命中一个词表词就调这里（主线程）。
     * 待命阶段命中的是唤醒词；已唤醒阶段命中的是命令词。
     */
    public void onEnginePhrase(String heard) {
        String t = Commands.normalize(heard);
        if (t.length() == 0) {
            return;
        }
        if (phase == PHASE_IDLE) {
            if (useDoubao()) {
                // 用户要求：在没听到「嗨小白」之前，本地先自己认一遍 ——
                // 只要能对上【任何】命令，就直接执行，不必先说唤醒词。
                // 只认短句（≤ 8 字）：长句多半是视频里的话，容易误触发。
                int quick = resolveCmd(t, true);
                if (quick != Commands.CMD_NONE) {
                    if (t.length() <= 8) {
                        if (executeCommand(quick, t)) {
                            toastResult(quick, "执行：" + t);
                            KeepService.note("已执行（未唤醒）：" + t);
                        }
                        return;
                    }
                    KeepService.note("听到了「" + t + "」，但太长（>8 字），不当作命令");
                }
                if (containsWake(t)) {
                    enterArmed();
                }
            } else {
                // 不用豆包：不需要唤醒词，直接听命令、直接执行，不进编辑框
                // 退出指令先于唤醒词剥离（“小白再见”里含唤醒词“小白”）
                if (resolveCmd(t, true) == Commands.CMD_EXIT) {
                    executeCommand(Commands.CMD_EXIT, t);
                    return;
                }
                if (containsWake(t)) {
                    // 用户要求：唤醒词既不执行任何动作，也不和命令组合。
                    // 所以「嗨小白」「小白广告」「嗨小白下一集」这类一律不当命令。
                    KeepService.note("听到唤醒词「" + t + "」：免豆包模式直接听命令，唤醒词本身不做动作");
                    return;
                }
                String cmdText = t;
                long now = System.currentTimeMillis();
                // 1.2 秒内不连发任何命令（识别抖动：中间结果和最终结果常不一样）
                if (now - lastLocalAt < 1200) {
                    return;
                }
                int preCmd = resolveCmd(cmdText, false);
                if (preCmd != Commands.CMD_NONE
                        && isOppositePair(lastLocalCmd, preCmd)
                        && now - lastLocalCmdAt < 3000) {
                    // 相反动作抑制：声音大/小、上一集/下一集这类成对命令，
                    // 识别抖到反方向时 3 秒内不执行
                    return;
                }
                lastLocalText = cmdText;
                lastLocalAt = now;
                int cmd = resolveCmd(cmdText, true);
                if (cmd != Commands.CMD_NONE) {
                    lastLocalCmd = cmd;
                    lastLocalCmdAt = now;
                    if (executeCommand(cmd, cmdText)) {
                        toastResult(cmd, "执行：" + cmdText);
                        KeepService.note("已执行：" + cmdText);
                    }
                } else {
                    // 用户要求：不在规定的命令表里 → 不执行、也不在屏幕下方显示
                    // （只写进日志，方便你自己排查它到底听成了什么）
                    KeepService.note("听到了「" + cmdText + "」，不在命令表里 → 不执行、不提示");
                }
            }
            return;
        }
        if (phase == PHASE_ARMED) {
            if (System.currentTimeMillis() - armAt < 250) {
                return; // 刚换完词表，上一句的残尾不可信
            }
            // 退出指令先于唤醒词剥离（“小白再见”里含唤醒词“小白”）
            if (resolveCmd(t, true) == Commands.CMD_EXIT) {
                executeCommand(Commands.CMD_EXIT, t);
                return;
            }
            if (containsWake(t)) {
                return; // 又把唤醒词说了一遍（唤醒词不与命令组合）
            }
            String cmdText = t;
            if (useDoubao()) {
                // 用豆包时，唤醒之后不再用本地语音听命令：这里只记一笔，不改框
                KeepService.note("(本地语音包已让位给豆包，忽略：" + cmdText + ")");
            } else {
                // 不用豆包：直接执行，不进编辑框
                int cmd = resolveCmd(cmdText, true);
                if (cmd != Commands.CMD_NONE) {
                    if (executeCommand(cmd, cmdText)) {
                        toastResult(cmd, "执行：" + cmdText);
                    }
                    backToIdle();
                } else {
                    // 不在命令表里 → 不执行、不提示（只记日志）
                    KeepService.note("听到了「" + cmdText + "」，不在命令表里 → 不执行、不提示");
                }
            }
        }
        if (phase == PHASE_DOUBAO) {
            // 用户规定：用豆包时，本地语音包不许听命令（它会听错方向，
            // 比如把你说的「声音大一点」听成「声音小」，就会反着调一次）。
            // 这里只留着听「小白再见」。
            if (resolveCmd(t, true) == Commands.CMD_EXIT) {
                executeCommand(Commands.CMD_EXIT, t);
                return;
            }
            if (containsWake(t)) {
                KeepService.note("(豆包阶段又听到唤醒词，已忽略：" + t + ")");
                return;
            }
            KeepService.note("(豆包阶段本地不接命令，已忽略：" + t + ")");
        }
    }

    // ==================== 短剧模式 ====================

    /** 短剧模式是不是开着（用户要求：短剧模式只听 停/暂停/播放） */
    public boolean shortDramaOn() {
        return getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("shortDrama", false);
    }

    /** 开 / 关短剧模式（语音「短剧」切换：没开就进、开着就退） */
    public void setShortDrama(boolean on, String why) {
        boolean old = shortDramaOn();
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("shortDrama", on).apply();
        if (manualBoxOpen) {
            manualBoxOpen = false;
            // 手动框开着时本地引擎只听「手动」，强制关框要把完整词表换回来（v2.35）
            KeepService.applyVoiceSetting(this);
        }
        if (old != on) {
            postStatus(on ? "短剧模式：已开启（只收 蓝牙上键=播放/暂停、下键/摇一摇=广告、停/暂停/播放 和自己录的命令）"
                    : "短剧模式：已关闭（恢复接收所有命令）");
            showIdleBox(); // 模式切了：短剧里不显示手动命令框（用户要求 v2.33）
            KeepService.note((on ? "短剧模式已开启" : "短剧模式已关闭")
                    + (why == null || why.length() == 0 ? "" : "：" + why));
        }
    }

    /** 短剧模式允许的命令（用户要求 v2.32：其他命令无效）：
     *  只收 停/暂停/播放；
     *  + 「短剧」（再说一次就退出短剧模式）+「小白再见」（安全阀）。
     *  自己录的命令不在这里面 —— resolveCmd 里它优先放行；
     *  蓝牙上键(播放/暂停)、下键/摇一摇不走这里（poller/传感器直接触发）。 */
    private static boolean isDramaCmd(int cmd) {
        return cmd == Commands.CMD_DRAMA_TOGGLE
                || cmd == Commands.CMD_TOGGLE_PLAY || cmd == Commands.CMD_RESUME
                || cmd == Commands.CMD_EXIT;
    }

    /** 阅读模式白名单（用户要求）：上页/下页 + 「阅读」开关 + 小白再见（安全阀） */
    private static boolean isReadingCmd(int cmd) {
        return cmd == Commands.CMD_PAGE_UP || cmd == Commands.CMD_PAGE_DOWN
                || cmd == Commands.CMD_READING_TOGGLE || cmd == Commands.CMD_EXIT;
    }

    /** 阅读模式开关（prefs "reading"，退出自动关） */
    private boolean readingOn() {
        return getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("reading", false);
    }

    /**
     * 统一的命令解析（所有语音路径都走这里，不要再直接用 Commands.match）：
     * ① 录制模式：用户自己录的语音命令名（RecordedCmds）→ CMD_RECORDED
     *    （用户要求：录的就是拿来退广告的，【短剧模式里也要能用】，所以最先判）
     * ② 短剧模式：只收 停/暂停/播放 + 「短剧」（退出用）+「小白再见」，
     *    其他命令一律不接收（不执行、不在屏幕下方显示）
     * ③ 正常命令表
     *
     * verbose=true 时把“不接收”的原因写进日志；tick 里的判断用 false，免得刷屏。
     */
    private int resolveCmd(String text, boolean verbose) {
        String t = Commands.normalize(text);
        if (t.length() == 0) {
            return Commands.CMD_NONE;
        }
        int cmd = Commands.match(t);
        String rec = RecordedCmds.find(this, t);
        if (rec != null && cmd != Commands.CMD_EXIT && !readingOn()) {
            // 安全阀：录制名字不许把「小白再见」抢走；阅读模式里录制命令也不听
            return Commands.CMD_RECORDED;
        }
        if (readingOn()) {
            // 用户要求：阅读模式只听 上页/下页 和「阅读」（小白再见保留），
            // 其他所有命令不收（避免阅读被视频里的语音误触发）
            if (rec != null) {
                if (verbose) {
                    KeepService.note("阅读模式：「" + t + "」不接收（只听 上页/下页/「阅读」/小白再见）");
                }
                return Commands.CMD_NONE;
            }
            if (cmd != Commands.CMD_NONE && !isReadingCmd(cmd)) {
                if (verbose) {
                    KeepService.note("阅读模式：「" + t + "」（" + Commands.nameOf(cmd)
                            + "）不接收；说「阅读」可退出阅读模式");
                }
                return Commands.CMD_NONE;
            }
            return cmd;
        }
        // 上页/下页只在阅读模式里有效（用户要求：其他模式不执行也不显示）
        if ((cmd == Commands.CMD_PAGE_UP || cmd == Commands.CMD_PAGE_DOWN)
                && !shortDramaOn()) {
            if (verbose) {
                KeepService.note("「" + t + "」（上页/下页）只在阅读模式里有效；说「阅读」开启");
            }
            return Commands.CMD_NONE;
        }
        if (shortDramaOn()) {
            if (cmd != Commands.CMD_NONE && !isDramaCmd(cmd)) {
                if (verbose) {
                    KeepService.note("短剧模式：只听 停/播放 和自己录的命令，「"
                            + t + "」（" + Commands.nameOf(cmd) + "）不接收；说「短剧」可退出短剧模式");
                }
                return Commands.CMD_NONE;
            }
        }
        return cmd;
    }

    /** 成对命令：3 秒内不许执行相反方向（识别抖动的保险） */
    private boolean isOppositePair(int last, int cur) {
        if (last == 0 || cur == 0 || last == cur) {
            return false;
        }
        return (last == Commands.CMD_VOL_UP && cur == Commands.CMD_VOL_DOWN)
                || (last == Commands.CMD_VOL_DOWN && cur == Commands.CMD_VOL_UP)
                || (last == Commands.CMD_NEXT && cur == Commands.CMD_PREV)
                || (last == Commands.CMD_PREV && cur == Commands.CMD_NEXT)
                || (last == Commands.CMD_PAGE_UP && cur == Commands.CMD_PAGE_DOWN)
                || (last == Commands.CMD_PAGE_DOWN && cur == Commands.CMD_PAGE_UP);
    }

    private boolean containsWake(String t) {
        for (String w : WakeEngine.WAKES) {
            if (t.contains(w)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 状态流转 ====================

    /** 听到「嗨小白」：进入已唤醒 */
    private void enterArmed() {
        armUp(true);
    }

    /**
     * 进入「准备识别」状态。
     *
     * @param pauseVideo 是否先点 (50%,15%) 暂停视频。
     *                   唤醒词进来要暂停；说「打开豆包」进来的不要乱点。
     */
    private void armUp(boolean pauseVideo) {
        phase = PHASE_ARMED;
        micClicked = false;
        micAttempts = 0;
        micBlindDone = false;
        micDelayDone = false;
        micExtraTaps = 0;
        lastBoxText = "";
        lastBoxChangeAt = 0;
        armAt = System.currentTimeMillis();
        armedDeadline = armAt + VOSK_WINDOW_MS;

        // 用户定稿：唤醒时【什么都不做】—— 不静音、不点屏幕；
        // (50%,20%) 点击只存在于「停/播放」命令里（用户要求）。

        if (useDoubao()) {
            // 直接用豆包识别：本地语音包只在待命时听唤醒词。
            // 框一出来就抢焦点、弹键盘（不等任何窗口）
            KeepService.note(pauseVideo
                    ? "已唤醒：正在弹出豆包键盘…"
                    : "打开豆包：正在弹出豆包键盘…");
            showActiveBox(true);
            enterDoubao(pauseVideo ? "已唤醒，交给豆包识别" : "打开豆包，直接进入识别");
        } else {
            // 免豆包模式不会走到这里（命令在待命阶段直接执行），保底复位
            backToIdle();
            return;
        }
        handler.removeCallbacks(tickTask);
        handler.postDelayed(tickTask, 200);
    }

    /** 说「打开豆包」：除了勾上设置，还立刻把豆包语音叫出来（弹键盘 + 点 Mic），好马上说话 */
    private void startDoubaoVoiceNow() {
        if (!useDoubao() || phase != PHASE_IDLE) {
            return;
        }
        armUp(false);
    }

    /** 转入豆包识别：停本地引擎（麦克风让出来）→ 尽快点一次豆包麦克风 */
    private void enterDoubao(String reason) {
        if (phase != PHASE_ARMED) {
            return;
        }
        phase = PHASE_DOUBAO;
        doubaoStartAt = System.currentTimeMillis();
        doubaoDeadline = doubaoStartAt
                + Math.max(DOUBAO_WINDOW_DEF_MS, getSharedPreferences("cfg", MODE_PRIVATE)
                .getInt("listenms", DOUBAO_WINDOW_DEF_MS));
        micQuietDeadline = doubaoStartAt + MIC_QUIET_MS;

        // 点麦克风那一刻框里已有的文字，豆包加没加字以它为基准
        snapAtMic = boxText();

        if (snapAtMic.length() > 0) {
            // 本地语音包已经把命令放进框：不弹输入法、不点Mic，很快就收尾执行
            KeepService.note(reason);
            toast(reason + "，命令已在框中，不点Mic");
            return;
        }

        // 本地没听清 → 现在才抢焦点、弹键盘，并尽快点豆包麦克风
        KeepService.listenVocab(null, "豆包识别中…");
        KeepService.note(reason);
        focusBoxForIme();
        toast(reason);
        // 一个周期只点一次麦克风；点中之后绝不再点
        if (!micClicked) {
            micClicked = true;
            handler.post(micClickTask);
        }
    }

    /**
     * 点豆包麦克风：直接按【标定/默认坐标】点一下，不再先去扫节点。
     * 扫节点容易扫到输入法里同名的假节点：点空还不算，还把真正该点的位置掠过了。
     * 坐标就是「测试Mic」里你亲手点的那一下（默认 22% / 66%）。
     */
    private final Runnable micClickTask = new Runnable() {
        @Override
        public void run() {
            if (phase != PHASE_DOUBAO || micTestMode) {
                return;
            }
            if (snapAtMic.length() > 0 || boxText().length() > 0) {
                return;
            }
            if (micBlindDone) {
                return;
            }
            // 豆包键盘弹得慢：一直等到它真的出现才点（最多约 4 秒）。
            // 不等的话就点在视频画面上了，效果＝没点 Mic。
            if (!imeWindowVisible() && micAttempts < 50) {
                micAttempts++;
                if (micAttempts == 1 || micAttempts % 13 == 0) {
                    KeepService.note("还在等豆包键盘弹出来…");
                }
                if (micAttempts % 6 == 0) {
                    showIme();   // 再催一下系统把键盘弹出来
                }
                handler.postDelayed(this, MIC_POLL_MS);
                return;
            }
            // 键盘出来了 → 再按设置里的「点 Mic 前延时」等一下（默认 200ms，越小越快）
            if (!micDelayDone) {
                micDelayDone = true;
                int delay = Math.max(0, Math.min(3000,
                        getSharedPreferences("cfg", MODE_PRIVATE).getInt("micdelay", 200)));
                handler.postDelayed(this, delay);
                return;
            }
            boolean imeSeen = imeWindowVisible();
            micBlindDone = true;
            blindTapMic();
            // 关键：识别时间从「Mic 真的被点中」这一刻开始算，
            // 不是从唤醒那一刻算（否则等键盘、等窗口的时间会白白吃掉用户说话的时间）
            long tapAt = System.currentTimeMillis();
            int listen = Math.max(DOUBAO_WINDOW_DEF_MS, getSharedPreferences("cfg", MODE_PRIVATE)
                    .getInt("listenms", DOUBAO_WINDOW_DEF_MS));
            doubaoStartAt = tapAt;
            doubaoDeadline = tapAt + listen;
            micQuietDeadline = tapAt + MIC_QUIET_MS;
            lastBoxChangeAt = tapAt;
            int[] mp = micPos();
            KeepService.note((imeSeen ? "键盘已出来，已点豆包 Mic（" : "键盘一直没出来，仍按坐标点了一次 Mic（")
                    + mp[0] + "%," + mp[1] + "%），听 " + (listen / 1000) + " 秒");
            // 保险：本地引擎留着（但只听唤醒词，不听命令——用豆包时本地听命令会听错方向）
            KeepService.listenVocab(WakeEngine.idleVocab(), "本地引擎只留着听唤醒词");
            // 看看豆包那边有没有真的开始听
            micVerifyTries = 0;
            handler.removeCallbacks(micVerifyTask);
            handler.postDelayed(micVerifyTask, 1000);
        }
    };

    /**
     * 点完 Mic 后确认豆包真的开始听了。
     * 特别按用户要求：点完 1 秒编辑框还是空的 → 再补点一下 Mic（只补一次，
     * 而且只有在“豆包语音界面没出来”时才补，免得把已经开始的录音又停掉）。
     */
    private int micVerifyTries = 0;
    private int micExtraTaps = 0;
    private final Runnable micVerifyTask = new Runnable() {
        @Override
        public void run() {
            if (phase != PHASE_DOUBAO || micTestMode) {
                return;
            }
            if (boxText().length() > 0) {
                KeepService.note("豆包已在往输入框写字 ✓");
                return;
            }
            if (voicePanelVisible()) {
                if (micVerifyTries == 0) {
                    KeepService.note("豆包语音界面已出现 ✓ 开始说话吧");
                }
                return;
            }
            micVerifyTries++;
            // 1 秒过去还一点文字都没有，而且豆包界面也没出来 → 补点一次
            if (micVerifyTries == 1 && micExtraTaps < 1) {
                micExtraTaps++;
                int[] mp = micPos();
                tapAt(mp[0], mp[1]);
                KeepService.note("1 秒没动静，又补点了一下 Mic（" + mp[0] + "%," + mp[1] + "%）");
                handler.postDelayed(this, 500);
                return;
            }
            if (micVerifyTries == 5) {
                KeepService.note("点了 Mic 但豆包界面没出现（坐标可能不对，请点「测试Mic」重新标定）");
            }
            if (micVerifyTries < 12) {
                handler.postDelayed(this, 500);
            }
        }
    };

    /** 豆包语音界面是否已经出来了（看输入法窗口里有没有“轻触结束/说话”这类字样） */
    private boolean voicePanelVisible() {
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins == null) {
                return false;
            }
            for (AccessibilityWindowInfo w : wins) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    continue;
                }
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) {
                    continue;
                }
                if (hasAnyText(root, 0)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static final String[] VOICE_PANEL_WORDS = {
            "轻触结束", "松开结束", "松手结束", "点击结束", "正在听", "识别中",
            "说话", "录音", "取消", "完成"
    };

    private boolean hasAnyText(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 25) {
            return false;
        }
        CharSequence text = n.getText();
        CharSequence desc = n.getContentDescription();
        String all = ((text == null ? "" : text.toString()) + " "
                + (desc == null ? "" : desc.toString())).trim();
        if (all.length() > 0 && all.length() <= 20) {
            for (String k : VOICE_PANEL_WORDS) {
                if (all.contains(k)) {
                    return true;
                }
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            if (hasAnyText(n.getChild(i), depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /** 当前生效的 Mic 坐标（标定值，没标定就是默认 22%/66%） */
    private int[] micPos() {
        int mx = getSharedPreferences("cfg", MODE_PRIVATE).getInt("micx", MIC_DEF_X);
        int my = getSharedPreferences("cfg", MODE_PRIVATE).getInt("micy", MIC_DEF_Y);
        return new int[]{mx, my};
    }

    /** 豆包收尾：输入框自动失去焦点（键盘收起）→ 框里的内容就是命令 → 执行 */
    private void finishDoubao() {
        if (phase != PHASE_DOUBAO) {
            return;
        }
        phase = PHASE_IDLE; // 先改状态，tick 不再进来
        String t = boxText();
        try {
            if (boxEdit != null) {
                boxEdit.clearFocus();
            }
        } catch (Exception ignored) {
        }
        hideIme();

        // 收尾：框里的内容就是命令 → 执行
        if (t.length() > 0) {
            // 用户要求：日志也要记录【听到了什么】，不只是执行了什么
            KeepService.note("豆包输入框里的字：「" + t + "」");
            int cmd = resolveCmd(t, true);
            if (cmd != Commands.CMD_NONE) {
                if (executeCommand(cmd, t)) {
                    toastResult(cmd, "执行：" + t);
                    KeepService.note("已执行：" + t);
                }
            } else {
                // 用户要求：不在规定的命令表里 → 不执行、也不在屏幕下方显示（只记日志）
                KeepService.note("没匹配到命令（不在命令表里，不执行）：「" + t + "」");
            }
        } else {
            KeepService.note("豆包没听清，回到待命");
        }
        backToIdle();
    }

    /** 回到待命：命令条复原，本地引擎换回唤醒词表 */
    private void backToIdle() {
        phase = PHASE_IDLE;
        micClicked = false;
        micTestMode = false;
        micAttempts = 0;
        micBlindDone = false;
        micDelayDone = false;
        micExtraTaps = 0;
        handler.removeCallbacks(tickTask);
        handler.removeCallbacks(matchTask);
        handler.removeCallbacks(micVerifyTask);
        if (!getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("enabled", true)) {
            // 已经「小白再见」退出了：不要再把命令条/引擎拉回来
            removeBox();
            return;
        }
        if (useDoubao()) {
            showIdleBox();
            KeepService.listenVocab(WakeEngine.idleVocab(), "在听唤醒词：说「嗨小白」");
        } else if (manualBoxOpen) {
            // 免豆包模式：「手动」开的命令框留在屏幕上（v2.35）
            showIdleBox();
            KeepService.listenVocab(WakeEngine.commandVocab(), "直接听命令（不用豆包）");
        } else {
            // 免豆包模式屏幕上不该有编辑框
            removeBox();
            KeepService.listenVocab(WakeEngine.commandVocab(), "直接听命令（不用豆包）");
        }
    }

    private void tickOnce() {
        long now = System.currentTimeMillis();
        if (phase == PHASE_ARMED) {
            String t = boxText();
            if (t.length() > 0) {
                // 框里已经有字（本地语音包听出来了）→ 立刻进豆包收尾
                enterDoubao("命令已在框中");
            } else if (now > armedDeadline) {
                // 本地语音包没听清 → 这时才让豆包听
                enterDoubao("本地没听清，让豆包直接听");
            }
            return;
        }
        if (phase == PHASE_DOUBAO) {
            String cur = boxText();
            if (!cur.equals(lastBoxText)) {
                lastBoxText = cur;
                lastBoxChangeAt = now;
            }
            boolean timeout = now > doubaoDeadline;
            // 豆包真的加了新字，且停了 STABLE_MS → 说完了，马上执行
            boolean stable = !cur.equals(snapAtMic)
                    && cur.length() > 0
                    && now - doubaoStartAt >= DOUBAO_GRACE_MS
                    && now - lastBoxChangeAt >= STABLE_MS;
            // 关键：框里已经有一小段字（≤6 个字 = 一个命令的长度）→ 立即当命令执行
            // （哪怕匹配不到也要收尾）。已能对上命令的只要 300ms 不变就先走；
            // 还没对上的等 800ms，免得把“继续/声音大”这种半截句子提前执行了
            boolean shortDone = cur.length() > 0
                    && cur.length() <= SHORT_CMD_CHARS
                    && (now - lastBoxChangeAt >= (resolveCmd(cur, false) != Commands.CMD_NONE
                    ? SHORT_MATCH_MS : SHORT_STABLE_MS));
            // 本地引擎已先把命令放进框，豆包一直没动静 → 不用干等
            boolean quiet = snapAtMic.length() > 0
                    && cur.equals(snapAtMic)
                    && now > micQuietDeadline;

            // 兑底：豆包的字可能只显示在它自己的语音面板里没进框，
            // 直接扫输入法窗口，看到命令文字就执行
            if (!timeout && !stable && !quiet && !micTestMode
                    && now - doubaoStartAt >= DOUBAO_GRACE_MS) {
                String scraped = scrapeImeCommand();
                if (scraped.length() > 0) {
                    setBoxTextSelf(scraped);
                    KeepService.note("在豆包面板里看到命令：" + scraped);
                    finishDoubao();
                    return;
                }
            }

            if (timeout || stable || quiet || shortDone) {
                if (shortDone && !stable && !quiet) {
                    KeepService.note("框里有 " + cur.length() + " 个字（" + cur + "），直接当命令收尾");
                }
                finishDoubao();
            }
        }
    }

    // ==================== 屏幕工具 ====================

    private int[] screen() {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager w = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (w == null) {
            return null;
        }
        w.getDefaultDisplay().getRealMetrics(dm);
        if (dm.widthPixels <= 0 || dm.heightPixels <= 0) {
            return null;
        }
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    private void clampLp(WindowManager.LayoutParams p) {
        int[] s = screen();
        if (s == null) {
            return;
        }
        int w = p.width > 0 ? p.width : 120;
        int h = p.height > 0 ? p.height : 60;
        if (p.x < 0) {
            p.x = 0;
        }
        if (p.y < 0) {
            p.y = 0;
        }
        if (p.x > s[0] - w) {
            p.x = s[0] - w;
        }
        if (p.y > s[1] - h) {
            p.y = s[1] - h;
        }
    }

    private int[] boxPos() {
        SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
        int[] s = screen();
        int x = sp.getInt("box_x", 24);
        int y = s == null ? 200 : s[1] / 3;
        if (s != null) {
            if (x > s[0] - 40) {
                x = s[0] - 40;
            }
            if (y > s[1] - 160) {
                y = s[1] - 160;
            }
        }
        return new int[]{x, y};
    }

    private int winType() {
        if (Build.VERSION.SDK_INT >= 26
                && android.provider.Settings.canDrawOverlays(this)) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
    }

    private String boxText() {
        if (boxEdit == null) {
            return "";
        }
        try {
            CharSequence c = boxEdit.getText();
            return Commands.normalize(c == null ? "" : c.toString());
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 待命命令条 ====================

    /** 待命小框（v2.33 改版，用户要求）：
     *  只干一件事 = 手动命令编辑框：说「手动」弹出（仅普通模式），
     *  说「手动」再关掉并执行框里的命令。
     *  之前短剧/阅读模式的「小字提示」整个删了——那两种模式屏幕上什么都不显示
     *  （用户要求：编辑框只用于接收手动命令）。 */
    public void showIdleBox() {
        removeBox();
        // 只在「手动」框开着、且非短剧/非阅读时才显示；其他情况一律不出框
        if (!manualBoxOpen || shortDramaOn() || readingOn()) {
            return;
        }
        if (wm == null) {
            wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        }
        if (wm == null) {
            return;
        }
        final int density = (int) getResources().getDisplayMetrics().density;
        final int boxW = 150 * density;
        final int boxH = 30 * density;

        box = new FrameLayout(this);
        idleTv = null;
        boxEdit = null;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(0xDD000000);

        final EditText et = new EditText(this);
        et.setHint("输入命令，说「手动」执行");
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFFAAAAAA);
        et.setBackgroundColor(0x00000000);
        et.setPadding(8, 2, 4, 2);
        et.setTextSize(11f);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);

        row.addView(et, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        box.addView(row, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, boxH));
        boxEdit = et;

        int[] pos = boxPos();
        boxLp = new WindowManager.LayoutParams(
                boxW, boxH, winType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        boxLp.gravity = Gravity.TOP | Gravity.START;
        boxLp.x = pos[0];
        boxLp.y = pos[1];
        boxLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;

        try {
            wm.addView(box, boxLp);
        } catch (Exception e) {
            box = null;
            boxEdit = null;
            return;
        }

        // v2.34（借用嗨小白）：抢焦点 → 等 200ms 让键盘弹出来 → 探测到豆包输入法再点 Mic（idleMicTask）。
        // 没有任何时限收框：框一直开着，直到再听到「手动」才关框并执行（用户要求）。
        et.setFocusable(true);
        et.setFocusableInTouchMode(true);
        et.requestFocus();
        idleMicTries = 0;
        idleMicDelayDone = false;
        idleMicExtraTaps = 0;
        handler.removeCallbacks(idleMicTask);
        handler.removeCallbacks(idleMicVerifyTask);
        handler.postDelayed(idleMicTask, 200);
    }

    /** 手动命令框：等键盘真的弹出来再点豆包 Mic（v2.33，借嗨小白 micClickTask 的逻辑）。
     *  豆包键盘弹得慢，不等就点＝点在视频画面上——这正是「手动」点不开 Mic 的根因。 */
    private int idleMicTries = 0;
    private boolean idleMicDelayDone = false;
    private int idleMicExtraTaps = 0;

    private final Runnable idleMicTask = new Runnable() {
        @Override
        public void run() {
            if (boxEdit == null || !manualBoxOpen) {
                return; // 框没了 / 「手动」已把框关了：不点
            }
            // 豆包键盘弹得慢：一直等到它真的出现才点（最多约 4 秒，每 6 次催一下键盘）
            if (!imeWindowVisible() && idleMicTries < 50) {
                idleMicTries++;
                if (idleMicTries == 1 || idleMicTries % 13 == 0) {
                    KeepService.note("手动命令框：还在等键盘弹出来…");
                }
                if (idleMicTries % 6 == 0) {
                    showIme();   // 再催一下系统把键盘弹出来
                }
                handler.postDelayed(this, MIC_POLL_MS);
                return;
            }
            // 键盘出来了 → 再按设置里的「点 Mic 前延时」等一下（默认 200ms）
            if (!idleMicDelayDone) {
                idleMicDelayDone = true;
                int delay = Math.max(0, Math.min(3000,
                        getSharedPreferences("cfg", MODE_PRIVATE)
                                .getInt("micdelay", 200)));
                handler.postDelayed(this, delay);
                return;
            }
            boolean imeSeen = imeWindowVisible();
            blindTapMic();
            int[] mp = micPos();
            KeepService.note((imeSeen ? "手动命令框：键盘已出来，已点豆包 Mic（"
                    : "手动命令框：键盘一直没出来，仍按坐标点了一次 Mic（")
                    + mp[0] + "%," + mp[1] + "%）。说也行、打也行，说「手动」就执行");
            // 嗨小白的保险：点完 1 秒还没动静（框没字、语音界面也没出来）→ 补点一次
            handler.removeCallbacks(idleMicVerifyTask);
            handler.postDelayed(idleMicVerifyTask, 1000);
        }
    };

    private final Runnable idleMicVerifyTask = new Runnable() {
        @Override
        public void run() {
            if (boxEdit == null || !manualBoxOpen) {
                return;
            }
            if (boxText().length() > 0) {
                KeepService.note("豆包已在往输入框写字 ✓");
                return;
            }
            if (voicePanelVisible()) {
                KeepService.note("豆包语音界面已出现 ✓");
                return;
            }
            if (idleMicExtraTaps < 1) {
                idleMicExtraTaps++;
                int[] mp = micPos();
                tapAt(mp[0], mp[1]);
                KeepService.note("1 秒没动静，又补点了一下 Mic（" + mp[0] + "%," + mp[1] + "%）");
            }
        }
    };

    private void setIdleBarText(String s) {
        if (idleTv != null) {
            idleTv.setText(s);
        }
    }

    /** 移除屏幕上的条/框 */
    public void removeBox() {
        // 手动命令框的等键盘/补点任务一起停掉，免得框没了还在点屏幕
        handler.removeCallbacks(idleMicTask);
        handler.removeCallbacks(idleMicVerifyTask);
        if (box != null && wm != null) {
            try {
                wm.removeView(box);
            } catch (Exception ignored) {
            }
        }
        box = null;
        boxEdit = null;
        idleTv = null;
    }

    /** 豆包开关切换后调用：按新模式决定悬浮框去留 */
    public void refreshOverlayForMode() {
        try {
            if (phase != PHASE_IDLE) {
                return; // 激活中的框不动，这轮结束后 backToIdle 会按新模式处理
            }
            if (useDoubao() || manualBoxOpen) {
                showIdleBox();
            } else {
                removeBox();
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== 唤醒后的输入框 ====================

    /**
     * 命令条原地变成输入框。
     * withIme = false 时只出框、不抢焦点，键盘不会弹（留给本地语音包听命令）；
     * 真正弹键盘、点豆包麦克风只发生在 enterDoubao + focusBoxForIme 里，一次。
     */
    private void showActiveBox(boolean withIme) {
        removeBox();
        if (wm == null) {
            wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        }
        if (wm == null) {
            return;
        }
        final int density = (int) getResources().getDisplayMetrics().density;
        final int boxW = 150 * density;
        final int boxH = 30 * density;
        final int closeW = 26 * density;

        FrameLayout fl = new FrameLayout(this);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(0xDD000000);

        final EditText et = new EditText(this);
        et.setHint("说命令…");
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFFAAAAAA);
        et.setBackgroundColor(0x00000000);
        et.setPadding(8, 2, 4, 2);
        et.setTextSize(11f);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);

        TextView okBtn = new TextView(this);
        okBtn.setText("OK");
        okBtn.setTextColor(0xFFFFFFFF);
        okBtn.setTextSize(13f);
        okBtn.setGravity(Gravity.CENTER);
        okBtn.setBackgroundColor(0xFF2E7D32);
        okBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 用户要求：OK 点击就执行，错误命令也下达
                executeTextCommand(boxEdit == null ? "" : boxEdit.getText().toString());
            }
        });

        TextView closeBtn = new TextView(this);
        closeBtn.setText("✕");
        closeBtn.setTextColor(0xFFFFFFFF);
        closeBtn.setTextSize(13f);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setBackgroundColor(0xFF666666);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 点叉 = 整个程序退出：框消失、语音停、常驻服务停
                shutdownAll();
            }
        });

        row.addView(et, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        row.addView(okBtn, new LinearLayout.LayoutParams(
                closeW * 2, LinearLayout.LayoutParams.MATCH_PARENT));
        row.addView(closeBtn, new LinearLayout.LayoutParams(
                closeW, LinearLayout.LayoutParams.MATCH_PARENT));

        fl.addView(row, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, boxH));

        int[] pos = boxPos();
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (!withIme) {
            // 先出框、不抢焦点：输入法（键盘）不会弹出来，把这段时间留给本地语音包
            flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        boxLp = new WindowManager.LayoutParams(
                boxW, boxH, winType(), flags, PixelFormat.TRANSLUCENT);
        boxLp.gravity = Gravity.TOP | Gravity.START;
        boxLp.x = pos[0];
        boxLp.y = pos[1];
        boxLp.softInputMode = withIme
                ? (WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                : WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;

        try {
            wm.addView(fl, boxLp);
        } catch (Exception e) {
            showIdleBox();
            return;
        }

        box = fl;
        boxEdit = et;
        if (withIme) {
            focusBoxForIme();
        }

        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (selfSettingText) {
                    return; // 程序自己放的字，不算输入
                }
                lastBoxChangeAt = System.currentTimeMillis();
                if (phase == PHASE_ARMED || phase == PHASE_DOUBAO) {
                    // 豆包阶段也走快路：字一停 350ms 就试执行，不等 tick 的稳定判断
                    handler.removeCallbacks(matchTask);
                    handler.postDelayed(matchTask, 350);
                }
            }
        });
    }

    private void setBoxTextSelf(String text) {
        if (boxEdit == null) {
            return;
        }
        selfSettingText = true;
        try {
            boxEdit.setText(text);
            boxEdit.setSelection(text.length());
        } catch (Exception ignored) {
        } finally {
            selfSettingText = false;
        }
        lastBoxText = Commands.normalize(text);
        lastBoxChangeAt = System.currentTimeMillis();
    }

    private void showIme() {
        try {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && boxEdit != null) {
                imm.showSoftInput(boxEdit, InputMethodManager.SHOW_FORCED);
            }
        } catch (Exception ignored) {
        }
    }

    private void hideIme() {
        hideImeFor(boxEdit);
    }

    /** 收起指定输入框的键盘（hideIme 的通用版：老 hideIme 只认 boxEdit 的 token） */
    private void hideImeFor(View v) {
        try {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && v != null) {
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 到豆包这一步才真正抢焦点、弹键盘：
     * 先去掉窗口的 NOT_FOCUSABLE，再给输入框焦点，最后显示输入法。
     */
    private void focusBoxForIme() {
        try {
            if (boxLp != null
                    && (boxLp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
                boxLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                boxLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
                if (box != null && wm != null) {
                    wm.updateViewLayout(box, boxLp);
                }
            }
            if (boxEdit != null) {
                boxEdit.setFocusable(true);
                boxEdit.setFocusableInTouchMode(true);
                boxEdit.requestFocus();
            }
        } catch (Exception ignored) {
        }
        // 弹键盘：失败/没弹出来就重试几次（以前只试一次，弹不出来就整轮没键盘可点）
        final int[] tries = {0};
        final Runnable[] again = new Runnable[1];
        again[0] = new Runnable() {
            @Override
            public void run() {
                if (phase != PHASE_DOUBAO && phase != PHASE_ARMED) {
                    return;
                }
                if (boxEdit == null) {
                    return;
                }
                if (imeWindowVisible()) {
                    if (tries[0] > 0) {
                        KeepService.note("豆包键盘已出现 ✓");
                    }
                    tries[0] = 0;
                    return; // 已经弹出来了
                }
                showIme();
                if (++tries[0] < 16) {
                    handler.postDelayed(again[0], 200);
                }
            }
        };
        handler.post(again[0]);   // 立刻弹，不等
    }

    // ==================== 手势 ====================

    public void tapAt(int xPercent, int yPercent) {
        int[] s = screen();
        if (s == null) {
            return;
        }
        float x = s[0] * xPercent / 100f;
        float y = s[1] * yPercent / 100f;

        int tapMs = Math.max(80, getSharedPreferences("cfg", MODE_PRIVATE)
                .getInt("tapms", 120));

        Path p = new Path();
        p.moveTo(x, y);
        final GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, tapMs);
        if (!dispatchTap(stroke)) {
            // 手势被系统拒了（上一次手势还没结束等）→ 220ms 后再补发一次
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    dispatchTap(stroke);
                }
            }, 220);
        }
    }

    /** 上滑/下滑类命令（用户要求：这几条只能滑一次） */
    private static boolean isSwipeCmd(int cmd) {
        return cmd == Commands.CMD_NEXT || cmd == Commands.CMD_PREV
                || cmd == Commands.CMD_SKIP_AD || cmd == Commands.CMD_EXIT_LIVE;
    }

    private boolean dispatchTap(GestureDescription.StrokeDescription stroke) {
        try {
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(stroke);
            return dispatchGesture(b.build(), null, null);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 手势前先确保键盘收干净。
     * 键盘还在屏幕上时，上滑（50%,75%）→（50%,25%）是从键盘上开始滑的，
     * 视频根本没动，看起来就是“拉到一半又弹回去了”。
     */
    private void whenImeGone(final Runnable action) {
        // 手动命令框开着时：手势做完把焦点和键盘还给编辑框（用户要求 v2.34：编辑框不失焦）
        final Runnable act = new Runnable() {
            @Override
            public void run() {
                action.run();
                if (manualBoxOpen && boxEdit != null) {
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (manualBoxOpen && boxEdit != null) {
                                boxEdit.requestFocus();
                                showIme();
                                KeepService.note("手动命令框：命令做完，焦点和键盘已还给编辑框");
                            }
                        }
                    }, 400);
                }
            }
        };
        if (!imeWindowVisible() && !isImeShowing()) {
            act.run();
            return;
        }
        hideIme();
        final int[] tries = {0};
        final Runnable[] again = new Runnable[1];
        again[0] = new Runnable() {
            @Override
            public void run() {
                if (!imeWindowVisible() && !isImeShowing()) {
                    act.run();
                    return;
                }
                hideIme();
                if (++tries[0] < 8) {
                    handler.postDelayed(again[0], 300);
                } else {
                    act.run();   // 一直收不掉也照做
                }
            }
        };
        handler.postDelayed(again[0], 300);
    }

    public void swipeUpOnly() {
        if (busy) {
            // 用户反馈：短剧蓝牙上拉没反应且日志什么都没有 → 静默拦截全改写日志
            KeepService.note("上滑没发：上一个手势还没做完（busy=" + busyUntil + "）");
            return;
        }
        // 用户要求：上滑/下滑只能一次，不能连滑
        long nowSw = System.currentTimeMillis();
        if (nowSw - lastSwipeAt < SWIPE_MIN_GAP_MS) {
            KeepService.note("上滑忽略了：" + (nowSw - lastSwipeAt) + "ms 前刚滑过一次"
                    + "（用户要求一次命令只能滑一次）");
            return;
        }
        lastSwipeAt = nowSw;
        int[] s = screen();
        if (s == null) {
            KeepService.note("上滑没发：拿不到屏幕尺寸");
            return;
        }
        final int dur = Math.max(80, getSharedPreferences("cfg", MODE_PRIVATE)
                .getInt("dur", 300));
        busy = true;
        busyUntil = System.currentTimeMillis() + dur + 200;

        Path p = new Path();
        p.moveTo(s[0] / 2f, s[1] * 0.75f);
        p.lineTo(s[0] / 2f, s[1] * 0.25f);

        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, dur));
        dispatchGesture(b.build(), null, null);

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                busy = false;
            }
        }, dur + 200);
    }

    public void swipeDownOnly() {
        if (busy) {
            KeepService.note("下滑没发：上一个手势还没做完（busy）");
            return;
        }
        // 用户要求：上滑/下滑只能一次，不能连滑
        long nowSw = System.currentTimeMillis();
        if (nowSw - lastSwipeAt < SWIPE_MIN_GAP_MS) {
            KeepService.note("下滑忽略了：" + (nowSw - lastSwipeAt) + "ms 前刚滑过一次"
                    + "（用户要求一次命令只能滑一次）");
            return;
        }
        lastSwipeAt = nowSw;
        int[] s = screen();
        if (s == null) {
            KeepService.note("下滑没发：拿不到屏幕尺寸");
            return;
        }
        final int dur = Math.max(80, getSharedPreferences("cfg", MODE_PRIVATE)
                .getInt("dur", 300));
        busy = true;
        busyUntil = System.currentTimeMillis() + dur + 200;

        Path p = new Path();
        p.moveTo(s[0] / 2f, s[1] * 0.25f);
        p.lineTo(s[0] / 2f, s[1] * 0.75f);

        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, dur));
        dispatchGesture(b.build(), null, null);

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                busy = false;
            }
        }, dur + 200);
    }

    /** 向左划：从屏幕最右边缘开始往左（退出直播用，必须是边缘手势） */
    public void swipeLeftOnly() {
        if (busy) {
            KeepService.note("左划没发：上一个手势还没做完（busy）");
            return;
        }
        int[] s = screen();
        if (s == null) {
            KeepService.note("左划没发：拿不到屏幕尺寸");
            return;
        }
        final int dur = Math.max(80, getSharedPreferences("cfg", MODE_PRIVATE)
                .getInt("dur", 300));
        busy = true;
        busyUntil = System.currentTimeMillis() + dur + 200;

        Path p = new Path();
        p.moveTo(s[0] - 2, s[1] / 2f);   // 最右边缘起手
        p.lineTo(s[0] * 0.2f, s[1] / 2f);

        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, dur));
        dispatchGesture(b.build(), null, null);

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                busy = false;
            }
        }, dur + 200);
    }

    // ==================== 跳过广告监视 ====================

    /**
     * 跳过广告：不找「跳过」按钮，而是盯屏幕下方 20% 的文字——
     * 文字还在变 = 读秒在走，继续等；不再变 = 读秒结束 → 上滑；
     * 读不到文字时（API 30+）改用无障碍截屏比像素：画面在变 = 读秒在走；
     * 滑完 3 秒还在原页 → 再滑，最多 3 次。
     */
    /** 退出广告：不左划，直接进入读秒监视（屏幕下方 20% 截屏读秒）→ 读到了就上滑 */
    public void startSkipWatch() {
        startSkipSequence("退出广告");
    }

    /** 退出直播：比「退出广告」多一个左划 */
    public void startExitLiveWatch() {
        startAdOrLiveWatch("退出直播");
    }

    /** 用户要求：广告/直播流程【先静音，上拉后再恢复音量】。
     *  只记流程自己禁的音：之前已经手动禁音的话不重复禁（防止把「禁音前音量」记成 0）。 */
    private void muteForSkipFlow(String tag) {
        if (skipFlowMuted) {
            return;
        }
        try {
            SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
            if (sp.getBoolean("soundMuted", false)) {
                KeepService.note(tag + "：本来就是禁音状态，流程不再重复禁音");
                return;
            }
            KeepService.note(tag + "：先禁音（上拉后自动恢复音量）");
            setMediaMute(true);
            skipFlowMuted = true;
        } catch (Throwable ignored) {
        }
    }

    /** 上拉之后恢复音量（只恢复流程自己禁的音，手动禁的不动） */
    private void unmuteForSkipFlow(String tag) {
        if (!skipFlowMuted) {
            return;
        }
        skipFlowMuted = false;
        KeepService.note(tag + "：上拉完成，恢复音量");
        setMediaMute(false);
    }

    /**
     * 退出直播专用：先边缘左划，再进入读秒监视。
     * （「退出广告」不左划，直接走 startSkipSequence。）
     */
    private void startAdOrLiveWatch(final String tag) {
        if (skipWatching) {
            return;
        }
        // 用户要求：直播左划之后的动作【和广告完全一样】——
        // 所以左划完成后就直接走 startSkipSequence，和「退出广告」是同一条代码路径：
        // 先静音 → 截图 → 读下方读秒 → 上拉前恢复音量 → 只上拉一次（不点 (50%,20%) 暂停）。
        muteForSkipFlow(tag);
        postStatus(tag + "：先静音 → 左划，再看读秒");
        runAfterResume(new Runnable() {
            @Override
            public void run() {
                swipeLeftOnly();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        postStatus(tag + "：左划完成 → 接下来和「退出广告」完全一样"
                                + "（已静音 → 截图 → 读秒 → 上拉前恢复音量 → 只上拉一次）");
                        startSkipSequence(tag);
                    }
                }, 1200);
            }
        });
    }

    /**
     * 监视屏幕下方 20% 区域的文字变化：
     * 区域文字在变 = 读秒在走 → 继续等；
     * 连续两轮（1 秒）文字没变 = 读秒结束或没有读秒 → 上滑；
     * 上滑后 3 秒验证：还在原页（读秒还在/文字没变）→ 再滑，最多重试 3 次。
     */
    private void startSkipSequence(final String tag) {
        if (skipWatching) {
            return;
        }
        skipWatching = true;
        swipeChainActive = false;
        swipesDone = 0;
        // 用户要求：广告/直播动作里【不要点 (50%,20%) 暂停】——
        // 改成开始时先静音、上拉前恢复音量；画面照常播，不影响截图读秒。
        muteForSkipFlow(tag);
        postStatus(tag + "：已静音，不点暂停，直接截屏读读秒（屏幕下方 20%）");
        // Android 11+ 一律先走截屏 + OCR：只有它能真的读到
        // 「10 秒后可继续看下一集」里的秒数。
        // 以前先看“下方文字稳不稳定”，结果把固定不动的标题当成“读秒结束”，
        // 广告锁定期间就提前上滑 → 白滑一次（用户 19:53 的日志就是这个）。
        if (Build.VERSION.SDK_INT >= 30) {
            startPixelWatch(tag);
            return;
        }
        final String[] lastJoin = {null};
        final int[] stable = {0};
        final int[] waited = {0};
        final int[] blind = {0};
        final int gapMs = Math.max(800,
                getSharedPreferences("cfg", MODE_PRIVATE).getInt("gap", 2000));
        final java.util.ArrayList<String>[] snap = new java.util.ArrayList[1];
        final Runnable[] watch = new Runnable[1];
        watch[0] = new Runnable() {
            @Override
            public void run() {
                if (!skipWatching) {
                    return;
                }
                String cur = bottomTextJoin();
                waited[0] += 500;
                if (cur.length() == 0) {
                    // 无障碍一个字都读不到 → 改用无障碍截屏，看下方像素有没有在变
                    if (Build.VERSION.SDK_INT >= 30) {
                        postStatus(tag + "：无障碍读不到文字，改用截屏探测下方");
                        startPixelWatch(tag);
                        return;
                    }
                    // 老系统（<Android 11）没法截屏：按参数里的「gap」等一等
                    blind[0] += 500;
                    lastJoin[0] = cur;
                    stable[0] = 0;
                    if (blind[0] < gapMs && waited[0] < 30000) {
                        handler.postDelayed(watch[0], 500);
                        return;
                    }
                    postStatus(tag + "：读不到文字，按延时（" + gapMs + "ms）上滑");
                } else {
                    blind[0] = 0;
                    if (lastJoin[0] != null && cur.equals(lastJoin[0])) {
                        stable[0]++;
                    } else {
                        stable[0] = 0;
                    }
                    lastJoin[0] = cur;
                    boolean observed = waited[0] >= 1000;
                    boolean stableNow = stable[0] >= 1;
                    // 文字一直在变 = 读秒在走 → 继续等
                    boolean counting = observed && !stableNow;
                    if (counting && waited[0] < 30000) {
                        handler.postDelayed(watch[0], 500);
                        return;
                    }
                    postStatus(tag + "：读秒结束，上滑");
                }
                swipeAndVerify(tag);
            }
        };
        handler.postDelayed(watch[0], 500);
    }

    /**
     * 用户要求（定稿）：广告 / 退出直播【只能上拉一次】。
     * 上拉之前 confirmNoSecondsThenSwipe 已经【再截一张图比较过】、
     * 确认下方没有读秒了，所以这里就只是滑这一次，滑完立即收尾：
     * 不再截图核对、不再补滑（拉多次是不对的）。
     */
    private void swipeAndVerify(final String tag) {
        if (swipeChainActive) {
            // 已经有上滑流程在跑：不允许两条路径各滑一次（那就是“连着上拉”）
            postStatus(tag + "：已经在滑了，忽略重复触发");
            return;
        }
        swipeChainActive = true;
        swipesDone = 0;
        // 用户要求 v2.33：【先上拉，滑完再恢复音量】（含蓝牙下键带来的 +1 还原）
        postStatus(tag + "：确认没有读秒 → 上滑（只滑这一次；滑完恢复音量）");
        runAfterResume(new Runnable() {
            @Override
            public void run() {
                whenImeGone(new Runnable() {
                    @Override
                    public void run() {
                        swipeUpOnly();
                        swipesDone = 1;
                        // 用户要求 v2.33：上拉发出去之后才恢复音量。
                        // 恢复值：摇一摇/语音的广告 → 禁音前原音量（不加一）；
                        //         蓝牙下键那轮 → btVolRestoreTarget=按下前的值（相当于 +1）
                        unmuteForSkipFlow(tag);
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                finishSwipeOnce(tag);
                            }
                        }, 600);
                    }
                });
            }
        });
    }

    private int swipesDone = 0;
    private boolean swipeChainActive = false;

    /** 上滑一次就收尾（用户要求：不许再补滑） */
    private void finishSwipeOnce(String tag) {
        unmuteForSkipFlow(tag); // 保险：万一哪条路没走上拉，也把音量还回来
        skipWatching = false;
        swipeChainActive = false;
        handler.removeCallbacks(pwDeadlineTask);
        handler.removeCallbacks(pixelLoop);
        postStatus(tag + "：完成（上滑 1 次）");
    }

    /**
     * 无障碍读不到文字时的退路：无障碍截屏取整屏缩略图，
     * ① 先用 OCR 把「最下方 20%」那一块的字读出来（能读到「10 秒后可继续看下一集」就按秒数等），
     * ② 同时看那一块像素有没有在变（OCR 不可用时的兼底）。
     * 注意：takeScreenshot 有频率限制，两次至少隔 1 秒，所以这里每 1.3 秒采一次。
     * 调用前 skipWatching 必须已经是 true。
     */
    private void startPixelWatch(final String tag) {
        lastStripSig = null;
        pwSame = 0;
        pwSawChange = false;
        pwWaited = 0;
        pwSawCountdown = false;
        pwEndAt = 0;
        pwOcrMiss = 0;
        pwSeenSec = -1;
        pwTag = tag;
        pwWaitSec = -1;
        pwSeenSecShown = -1;
        handler.removeCallbacks(pwDeadlineTask);
        handler.postDelayed(pixelLoop, 1100);
    }

    private final Runnable pixelLoop = new Runnable() {
        @Override
        public void run() {
            if (!skipWatching) {
                return;
            }
            pwWaited += 1300;
            captureScreenThumb(new BitmapCallback() {
                @Override
                public void onBitmap(Bitmap bmp, String error) {
                    if (!skipWatching) {
                        if (bmp != null) {
                            bmp.recycle();
                        }
                        return;
                    }
                    int[] sig = stripSignature(bmp);
                    Bitmap strip = cropBottom(bmp, REGION_TOP_FRAC);
                    if (bmp != null) {
                        bmp.recycle();
                    }

                    // 用户要求：广告/直播流程不点 (50%,20%) 暂停——开始时已静音。

                    // ① 像素判据：先看到过变化、又连续 2 次没变 = 读秒结束
                    int diff = sigMeanDiff(lastStripSig, sig);
                    lastStripSig = sig;
                    if (diff >= 1) {
                        pwSawChange = true;
                        pwSame = 0;
                    } else if (diff == 0) {
                        pwSame++;
                    }
                    if (pwSawChange && pwSame >= 2) {
                        confirmNoSecondsThenSwipe("下方画面连续不动了");
                        return;
                    }

                    if (strip == null) {
                        handler.postDelayed(pixelLoop, 1300);
                        return;
                    }

                    // ② OCR：读出那一行里的秒数
                    Ocr.run(strip, new Ocr.Cb() {
                        @Override
                        public void onText(String text) {
                            if (!skipWatching) {
                                strip.recycle();
                                return;
                            }
                            String s = Commands.normalize(text);
                            int sec = readSeconds(s);
                            long now = System.currentTimeMillis();
                            boolean done = false;
                            if (sec >= 0) {
                                pwSawCountdown = true;
                                pwOcrMiss = 0;
                                pwSeenSec = sec;
                                // 用户要求：不减 1 秒 —— 读到几秒就等几秒
                                //（以前减 1 秒是怕读秒过时，用户实测早滑会撞上广告锁，不减更稳）。
                                int wait = sec;
                                pwWaitSec = wait;
                                handler.removeCallbacks(pwDeadlineTask);
                                if (wait <= 0) {
                                    done = true;
                                    strip.recycle();
                                    finishPixelWatch("读秒只剩 " + sec
                                            + " 秒，不等了直接上滑");
                                } else {
                                    pwEndAt = now + (long) wait * 1000;
                                    handler.postDelayed(pwDeadlineTask, (long) wait * 1000);
                                    if (sec != pwSeenSecShown) {
                                        pwSeenSecShown = sec;
                                        postStatus(pwTag + "：读到读秒 " + sec + " 秒 → 等 "
                                                + wait + " 秒后上滑（"
                                                + (s.length() > 24 ? s.substring(0, 24) : s) + "）");
                                    }
                                }
                            } else {
                                pwOcrMiss++;
                                // 用户要求：读不到秒数不能直接滑，要【再截一张图】确认真的没秒
                                // 才上拉（跳秒结束也一样）。
                                if (!pwSawCountdown) {
                                    done = true;
                                    strip.recycle();
                                    confirmNoSecondsThenSwipe("下方读不到读秒（无秒）");
                                }
                            }
                            if (!done) {
                                strip.recycle();
                            }
                            if (done) {
                                return;
                            }
                            // 之前读到过读秒、现在没了 = 跳秒结束了 → 再截图确认
                            if (pwSawCountdown && pwOcrMiss >= 1) {
                                confirmNoSecondsThenSwipe("读秒突然消失（跳秒结束）");
                                return;
                            }
                            if (pwEndAt > 0 && now >= pwEndAt) {
                                finishPixelWatch("按读到的 " + pwSeenSec + " 秒等到了");
                                return;
                            }
                            if (pwWaited >= 45000) {
                                finishPixelWatch("等太久了（45 秒）");
                                return;
                            }
                            handler.postDelayed(pixelLoop, 1300);
                        }
                    });
                }
            });
        }
    };

    private void finishPixelWatch(String why) {
        postStatus(pwTag + "：" + why + "，上滑");
        handler.removeCallbacks(pixelLoop);
        handler.removeCallbacks(pwDeadlineTask);
        swipeAndVerify(pwTag);
    }

    /** 读秒到时（不用等下一轮采样，时刻更准）：
     *  用户要求：延时结束后也不能盲滑 —— 要再截一张图确认没秒了才上拉 */
    private final Runnable pwDeadlineTask = new Runnable() {
        @Override
        public void run() {
            if (!skipWatching) {
                return;
            }
            confirmNoSecondsThenSwipe("按读到的 " + pwSeenSec + " 秒（实际等 " + pwWaitSec + " 秒）延时结束");
        }
    };

    /** 广告/直播流程自己禁的音（上拉前要恢复；用户手动禁的音不动） */
    private boolean skipFlowMuted = false;

    /**
     * 用户要求：上拉之前【再截一张图确认下方已经没有读秒了】。
     * - 还能读到秒（≥ 2 秒）→ 说明读秒没走完（或又跳回来）→ 继续等
     * - 读到 1 秒或读不到 → 确认没秒了 → 上滑
     * 跳秒结束、无秒、延时结束、画面不动，都走这里。
     */
    private void confirmNoSecondsThenSwipe(final String why) {
        if (!skipWatching) {
            return;
        }
        handler.removeCallbacks(pixelLoop);
        postStatus(pwTag + "：" + why + " → 再截一张图确认没有读秒");
        captureScreenThumb(new BitmapCallback() {
            @Override
            public void onBitmap(Bitmap bmp, String error) {
                if (!skipWatching) {
                    if (bmp != null) {
                        bmp.recycle();
                    }
                    return;
                }
                final Bitmap strip = bmp == null ? null : cropBottom(bmp, REGION_TOP_FRAC);
                if (bmp != null) {
                    bmp.recycle();
                }
                if (strip == null) {
                    postStatus(pwTag + "：截图没拿到，按上次判断上滑");
                    finishPixelWatch(why);
                    return;
                }
                Ocr.run(strip, new Ocr.Cb() {
                    @Override
                    public void onText(String text) {
                        if (!skipWatching) {
                            strip.recycle();
                            return;
                        }
                        int sec = readSeconds(Commands.normalize(text));
                        strip.recycle();
                        if (sec >= 2) {
                            // 还有秒：读秒没走完 → 再等
                            int wait = sec - 1;
                            pwSawCountdown = true;
                            pwSeenSec = sec;
                            pwWaitSec = wait;
                            pwOcrMiss = 0;
                            postStatus(pwTag + "：再截图还是读到 " + sec + " 秒 → 再等 "
                                    + wait + " 秒");
                            pwEndAt = System.currentTimeMillis() + (long) wait * 1000;
                            handler.removeCallbacks(pwDeadlineTask);
                            handler.postDelayed(pwDeadlineTask, (long) wait * 1000);
                            handler.postDelayed(pixelLoop, (long) wait * 1000 + 300);
                            return;
                        }
                        finishPixelWatch("再截图确认没有读秒了");
                    }
                });
            }
        });
    }

    private int pwWaitSec = -1;
    private int pwSeenSecShown = -1;

    /** 截出屏幕下方的一块（topFrac=0.8 → 下面 20%），给 OCR 认字用 */
    private Bitmap cropBottom(Bitmap bmp, float topFrac) {
        if (bmp == null) {
            return null;
        }
        try {
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            int top = Math.max(0, (int) (h * topFrac));
            if (w < 8 || h - top < 8) {
                return null;
            }
            return Bitmap.createBitmap(bmp, 0, top, w, h - top);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 从认出来的文字里抠出「N 秒」的 N（读不到返回 -1） */
    private int readSeconds(String s) {
        if (s == null || s.length() == 0) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([0-9]{1,2})\\s*[秒sS]")
                .matcher(s);
        if (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1));
                if (v >= 0 && v <= 99) {
                    return v;
                }
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    private interface BitmapCallback {
        void onBitmap(Bitmap bmp, String error);
    }

    /**
     * API 30+：用无障碍自带的 takeScreenshot 截屏（不需要 MediaProjection 授权），
     * 拿当前整屏的缩略图。系统限制：两次截屏至少隔 1 秒。
     */
    private void captureScreenThumb(final BitmapCallback cb) {
        if (Build.VERSION.SDK_INT < 30) {
            cb.onBitmap(null, "系统低于 Android 11，没有无障碍截屏");
            return;
        }
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            Bitmap out = null;
                            try {
                                android.hardware.HardwareBuffer buf = result.getHardwareBuffer();
                                if (buf != null) {
                                    Bitmap wrapped = Bitmap.wrapHardwareBuffer(
                                            buf, result.getColorSpace());
                                    if (wrapped != null) {
                                        out = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                                    }
                                    buf.close();
                                }
                            } catch (Throwable t) {
                                out = null;
                            }
                            cb.onBitmap(out, out == null ? "截屏结果转换失败" : null);
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            String why;
                            if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                                why = "两次截屏间隔太短（系统要求 ≥1 秒）";
                            } else if (errorCode == ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS) {
                                why = "无障碍服务缺少截屏能力（检查 canTakeScreenshot）";
                            } else if (errorCode == ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY) {
                                why = "显示器无效";
                            } else {
                                why = "错误码 " + errorCode;
                            }
                            cb.onBitmap(null, why);
                        }
                    });
        } catch (Throwable t) {
            cb.onBitmap(null, "截屏异常：" + t.getClass().getSimpleName());
        }
    }

    /** 把整屏缩略图压成「屏幕下方 20%」的灰度指纹（64 宽，越小越好比） */
    private int[] stripSignature(Bitmap thumb) {
        if (thumb == null || thumb.getWidth() <= 0 || thumb.getHeight() <= 0) {
            return null;
        }
        try {
            int w = 64;
            int h = Math.max(8,
                    (int) ((long) thumb.getHeight() * w / Math.max(1, thumb.getWidth())));
            Bitmap small = Bitmap.createScaledBitmap(thumb, w, h, true);
            int top = (int) (h * SEC_TOP_FRAC);
            int sh = Math.max(1, h - top);
            int[] sig = new int[w * sh];
            int i = 0;
            for (int y = top; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = small.getPixel(x, y);
                    sig[i++] = ((p >> 16 & 0xFF) + (p >> 8 & 0xFF) + (p & 0xFF)) / 3;
                }
            }
            small.recycle();
            return sig;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 两个灰度指纹的平均差：0 = 完全没变，越大变化越多，-1 = 没法比 */
    private int sigMeanDiff(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return -1;
        }
        long sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += Math.abs(a[i] - b[i]);
        }
        return (int) (sum / a.length);
    }

    /** 把屏幕下方 20% 区域的文字拼成一个字符串（用于检测读秒变化） */
    private String bottomTextJoin() {
        StringBuilder sb = new StringBuilder();
        try {
            int[] s = screen();
            if (s == null) {
                return "";
            }
            int top = (int) (s[1] * REGION_TOP_FRAC);
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins == null) {
                return "";
            }
            for (AccessibilityWindowInfo w : wins) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    continue;
                }
                collectRegionText(w.getRoot(), 0, top, sb);
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    /** 只收集中心点在指定 Y 之下的节点文字 */
    private void collectRegionText(AccessibilityNodeInfo node, int depth, int minY, StringBuilder sb) {
        if (node == null || depth > 25 || sb.length() > 3000) {
            return;
        }
        try {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.height() > 0 && r.centerY() >= minY) {
                CharSequence t = node.getText();
                if (t != null && t.length() > 0) {
                    sb.append(t).append('\n');
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                collectRegionText(node.getChild(i), depth + 1, minY, sb);
            }
        } catch (Exception ignored) {
        }
    }

    /** 屏幕上所有应用窗口的文字快照 */
    private java.util.ArrayList<String> screenTextSnapshot() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins == null) {
                return out;
            }
            for (AccessibilityWindowInfo w : wins) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    continue;
                }
                collectTextList(w.getRoot(), 0, out);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void collectTextList(AccessibilityNodeInfo node, int depth, java.util.ArrayList<String> out) {
        if (node == null || depth > 25 || out.size() > 300) {
            return;
        }
        try {
            CharSequence t = node.getText();
            if (t != null && t.length() > 0) {
                out.add(t.toString());
            }
            CharSequence d = node.getContentDescription();
            if (d != null && d.length() > 0) {
                out.add(d.toString());
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                collectTextList(node.getChild(i), depth + 1, out);
            }
        } catch (Exception ignored) {
        }
    }
    /** 判断刚才那次上滑是不是没成功：读秒还在，或页面文字几乎没变 */
    private boolean swipeLooksFailed(java.util.ArrayList<String> prev) {
        try {
            if (countdownOnScreen()) {
                return true;
            }
            if (prev == null || prev.isEmpty()) {
                return false;
            }
            java.util.ArrayList<String> now = screenTextSnapshot();
            if (now.isEmpty()) {
                return false;
            }
            int totalChars = 0;
            int keptChars = 0;
            for (String s : prev) {
                totalChars += s.length();
                if (now.contains(s)) {
                    keptChars += s.length();
                }
            }
            if (totalChars < 20) {
                return false; // 文本太少没法比，视为成功
            }
            return keptChars * 100 / totalChars >= 60;
        } catch (Exception ignored) {
            return false;
        }
    }
    /** 广告读秒样式：9秒、9 s、9s、9S */
    private static final java.util.regex.Pattern COUNTDOWN =
            java.util.regex.Pattern.compile("[0-9]{1,2}[\u79d2sS]");

    /** 下方 20% 区域有没有「数字+秒」读秒：只扫这一小块，不碰视频里的文字 */
    private boolean countdownOnScreen() {
        try {
            int[] s = screen();
            if (s == null) {
                return false;
            }
            int top = (int) (s[1] * REGION_TOP_FRAC);
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins == null) {
                return false;
            }
            for (AccessibilityWindowInfo w : wins) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                collectRegionText(w.getRoot(), 0, top, sb);
                String all = sb.toString().replace(" ", "");
                if (all.length() == 0) {
                    continue;
                }
                java.util.regex.Matcher m = COUNTDOWN.matcher(all);
                if (m.find()) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }
    /**
     * 读秒探测：把「现在到底能读到什么文字」摊开给用户看。
     * 注意：这里【不是】截屏 —— 走的是无障碍节点文字（AccessibilityNodeInfo.getText()）。
     * 如果 App 把读秒画在画面/SurfaceView 里，无障碍拿不到，这里就会显示 0 字。
     */
    public String probeCountdownReport() {
        StringBuilder sb = new StringBuilder();
        try {
            int[] s = screen();
            sb.append("屏幕：").append(s == null ? "未知" : (s[0] + " × " + s[1])).append('\n');
            List<AccessibilityWindowInfo> wins = getWindows();
            sb.append("无障碍窗口：").append(wins == null ? 0 : wins.size()).append(" 个\n");
            if (wins != null) {
                for (AccessibilityWindowInfo w : wins) {
                    String type;
                    switch (w.getType()) {
                        case AccessibilityWindowInfo.TYPE_APPLICATION:
                            type = "应用";
                            break;
                        case AccessibilityWindowInfo.TYPE_INPUT_METHOD:
                            type = "输入法";
                            break;
                        case AccessibilityWindowInfo.TYPE_SYSTEM:
                            type = "系统";
                            break;
                        default:
                            type = "类型" + w.getType();
                            break;
                    }
                    AccessibilityNodeInfo root = w.getRoot();
                    CharSequence pkg = root == null ? null : root.getPackageName();
                    sb.append("  · ").append(pkg == null ? "?" : pkg)
                            .append("（").append(type).append("）");
                    if (root == null) {
                        sb.append("  root = 空（拿不到窗口内容）");
                    } else {
                        StringBuilder tmp = new StringBuilder();
                        collectRegionText(root, 0, 0, tmp);
                        sb.append("  文字 ").append(tmp.length()).append(" 字");
                    }
                    sb.append('\n');
                }
            }
            AccessibilityNodeInfo act = getRootInActiveWindow();
            if (act != null) {
                StringBuilder tmp = new StringBuilder();
                collectRegionText(act, 0, 0, tmp);
                sb.append("焦点窗口：").append(act.getPackageName())
                        .append("  文字 ").append(tmp.length()).append(" 字\n");
            } else {
                sb.append("焦点窗口：空\n");
            }
            String bottom = bottomTextJoin().replace('\n', ' ');
            sb.append("\n下方 20% 区域读到文字：").append(bottom.length()).append(" 字\n");
            sb.append("内容：").append(truncate(bottom, 300)).append('\n');
            sb.append("读秒样式（数字+秒）：")
                    .append(countdownOnScreen() ? "命中 ✓" : "未命中 ✗");
            String hits = countdownMatches(bottom);
            if (hits.length() > 0) {
                sb.append("  [").append(hits).append(']');
            }
            sb.append('\n');
            String full = fullScreenTextJoin().replace('\n', ' ');
            sb.append("全屏（仅统计）：").append(full.length()).append(" 字");
            String fullHits = countdownMatches(full);
            if (fullHits.length() > 0) {
                sb.append("，读秒片段 [").append(fullHits).append(']');
            }
            sb.append('\n');
            if (full.length() == 0 && bottom.length() == 0) {
                sb.append("\n结论：无障碍读不到任何文字（这个 App 把界面画在画面里了）")
                        .append("，只能改走截屏+OCR 才能读读秒。\n");
            }
        } catch (Throwable t) {
            sb.append("\n探测出错：").append(t.getClass().getSimpleName())
                    .append(' ').append(t.getMessage());
        }
        return sb.toString();
    }

    /** 屏幕上所有应用窗口的文字拼一起（全屏，用来判断到底能不能读到字） */
    private String fullScreenTextJoin() {
        StringBuilder sb = new StringBuilder();
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins == null) {
                return "";
            }
            for (AccessibilityWindowInfo w : wins) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    continue;
                }
                collectRegionText(w.getRoot(), 0, 0, sb);
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : (s.substring(0, max) + "…（共 " + s.length() + " 字）");
    }

    /** 从一段文字里把「数字+秒」的片段挑出来（最多 6 个），方便肉眼核对 */
    private String countdownMatches(String all) {
        if (all == null || all.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher m = COUNTDOWN.matcher(all.replace(" ", ""));
        int n = 0;
        while (m.find() && n < 6) {
            if (n > 0) {
                sb.append(", ");
            }
            sb.append(m.group());
            n++;
        }
        return sb.toString();
    }

    /**
     * 「测试读秒」命令：把探测结果做成一块悬浮面板盖在当前屏幕上，点一下关掉。
     * 这样你在别的 App（视频 App）里也能看清楚到底读到了什么。
     */
    public void startCountdownProbe() {
        // 关键：先把键盘收起来，不然截屏拍到的“下方 20%”是键盘，不是视频
        try {
            if (boxEdit != null) {
                boxEdit.clearFocus();
            }
        } catch (Exception ignored) {
        }
        hideIme();
        KeepService.note("读秒探测：收起键盘后截屏…");
        final String report = probeCountdownReport();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                doProbeCapture(report);
            }
        }, 800);
    }

    private void doProbeCapture(final String report) {
        captureScreenThumb(new BitmapCallback() {
            @Override
            public void onBitmap(Bitmap bmp, String error) {
                String extra;
                Bitmap strip = null;
                if (bmp != null) {
                    int[] sig = stripSignature(bmp);
                    int diff = sigMeanDiff(lastStripSig, sig);
                    lastStripSig = sig;
                    int top = (int) (bmp.getHeight() * SEC_TOP_FRAC);
                    int h = Math.max(1, bmp.getHeight() - top);
                    try {
                        strip = Bitmap.createBitmap(bmp, 0, top, bmp.getWidth(), h);
                    } catch (Throwable ignored) {
                        strip = null;
                    }
                    // OCR 认字用「最下方 20%」，像素比对用「最下方 12%」
                    strip = cropBottom(bmp, REGION_TOP_FRAC);
                    extra = "无障碍截屏：成功（" + bmp.getWidth() + " × " + bmp.getHeight() + "）\n"
                            + "截屏区域：屏幕最下方 20%（含读秒那一行）\n"
                            + "该区域像素变化："
                            + (diff < 0 ? "第一张，没有上一次可比"
                            : (diff + "（0=完全没变，越大变得越多）"));
                    bmp.recycle();
                } else {
                    extra = "无障碍截屏：失败 —— " + error;
                }
                if (strip == null) {
                    KeepService.note("读秒探测：结果已弹出面板");
                    showProbePanel(report + "\n" + extra
                            + "\nOCR：截图没拿到，没法认字（"
                            + (Ocr.available() ? "OCR 可用" : "OCR 不可用") + "）", null);
                    return;
                }
                final Bitmap shot = strip;
                Ocr.run(shot, new Ocr.Cb() {
                    @Override
                    public void onText(String text) {
                        String s = Commands.normalize(text);
                        int sec = readSeconds(s);
                        String line = "OCR（中文）读到："
                                + (s.length() == 0 ? "（一个字都没读到）" : s)
                                + "\n读到读秒：" + (sec >= 0 ? (sec + " 秒") : "没读到秒数");
                        KeepService.note("读秒探测：" + (sec >= 0
                                ? ("读到读秒 " + sec + " 秒")
                                : "本次没读到读秒"));
                        showProbePanel(report + "\n" + extra + "\n" + line, shot);
                    }
                });
            }
        });
    }

    private void showProbePanel(String report, Bitmap strip) {
        hideProbePanel();
        if (wm == null) {
            wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        }
        int[] s = screen();
        if (wm == null || s == null) {
            return;
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xEE000000);
        col.setPadding(20, 20, 20, 20);

        TextView tv = new TextView(this);
        tv.setText(report + "\n\n（点一下关掉，20 秒后自动关）");
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(11f);
        tv.setTextIsSelectable(true);
        col.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (strip != null) {
            TextView label = new TextView(this);
            label.setText("上面是「无障碍读到的文字」；下面是「无障碍截屏」拍到的最下方 20%（含读秒那一行，OCR 就是认这一块）：");
            label.setTextColor(0xFF9BE7FF);
            label.setTextSize(10f);
            col.addView(label, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            ImageView iv = new ImageView(this);
            iv.setImageBitmap(strip);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            col.addView(iv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (int) (s[1] * 0.18f)));
        }

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(col);

        FrameLayout fl = new FrameLayout(this);
        fl.addView(sv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        View.OnClickListener close = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideProbePanel();
            }
        };
        fl.setOnClickListener(close);
        sv.setOnClickListener(close);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                (int) (s[0] * 0.92f),
                (int) (s[1] * 0.6f),
                winType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (int) (s[0] * 0.04f);
        lp.y = (int) (s[1] * 0.2f);

        try {
            wm.addView(fl, lp);
            probePanel = fl;
        } catch (Exception e) {
            probePanel = null;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                hideProbePanel();
            }
        }, 20000);
    }

    private void hideProbePanel() {
        if (probePanel != null && wm != null) {
            try {
                wm.removeView(probePanel);
            } catch (Exception ignored) {
            }
        }
        probePanel = null;
    }

    private void postStatus(final String s) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                KeepService.note(s);
            }
        });
        showStatusStrip(s);
    }

    // ==================== 屏幕下方状态条 ====================

    /**
     * 屏幕下方的状态条（自己画，不用 Toast）。
     *
     * 为什么不用 Toast：Toast 一定出现在屏幕最下方，正好盖住读秒那一行，
     * 而且它会被我们的截屏拍进去，OCR 可能把提示文字当成读秒 → 所以
     * 「退出广告 / 退出直播 / 测试读秒」以前根本没任何屏幕提示。
     *
     * 现在这条子：
     * ① 位置在屏幕 79% 处（底边留出 21%）——读秒行（约 96%）能完整看见；
     * ② 它没进 OCR 区域（只读最下方 20%）也没进像素比对区域（最下方 12%），
     *    所以不会干扰读秒判断；
     * ③ 不抢触摸（FLAG_NOT_TOUCHABLE），不抢焦点；
     * ④ 3 秒后自动消失。
     */
    private android.widget.TextView statusStrip;

    private void showStatusStrip(final String text) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (wm == null) {
                        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                    }
                    int[] s = screen();
                    if (wm == null || s == null || text == null || text.length() == 0) {
                        return;
                    }
                    if (statusStrip == null) {
                        android.widget.TextView tv = new android.widget.TextView(AutoService.this);
                        tv.setTextColor(0xFFFFFFFF);
                        tv.setTextSize(12f);
                        tv.setPadding(28, 12, 28, 12);
                        tv.setBackgroundColor(0xCC000000);
                        tv.setMaxWidth((int) (s[0] * 0.94f));
                        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                                WindowManager.LayoutParams.WRAP_CONTENT,
                                WindowManager.LayoutParams.WRAP_CONTENT,
                                winType(),
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                                PixelFormat.TRANSLUCENT);
                        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                        lp.y = (int) (s[1] * 0.21f);
                        statusStrip = tv;
                        wm.addView(statusStrip, lp);
                    }
                    statusStrip.setText(text);
                    handler.removeCallbacks(hideStatusStripTask);
                    handler.postDelayed(hideStatusStripTask, 3000);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private final Runnable hideStatusStripTask = new Runnable() {
        @Override
        public void run() {
            removeStatusStripNow();
        }
    };

    private void removeStatusStripNow() {
        try {
            handler.removeCallbacks(hideStatusStripTask);
        } catch (Exception ignored) {
        }
        if (statusStrip != null && wm != null) {
            try {
                wm.removeView(statusStrip);
            } catch (Exception ignored) {
            }
        }
        statusStrip = null;
    }

    // ==================== 豆包面板兑底 ====================

    /**
     * 豆包语音的字可能只显示在它自己的语音面板里，没真正进我们的编辑框。
     * 这里直接扫输入法窗口里的文字，看到短命令就返回。
     * 只扫输入法窗口（TYPE_INPUT_METHOD），不会误伤视频 App 界面上的“下一集”按钮。
     */
    private String scrapeImeCommand() {
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins == null) {
                return "";
            }
            for (AccessibilityWindowInfo w : wins) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    continue;
                }
                String found = scrapeTextRec(w.getRoot(), 0);
                if (found.length() > 0) {
                    return found;
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private String scrapeTextRec(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 25) {
            return "";
        }
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        String all = (t == null ? "" : t.toString()) + (d == null ? "" : d.toString());
        if (all.length() > 0) {
            String n = Commands.normalize(all);
            // 只认短文本，避免把输入法自己的长提示语当命令
            if (n.length() > 0 && n.length() <= 12 && resolveCmd(n, false) != Commands.CMD_NONE) {
                return n;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null) {
                continue;
            }
            String r = scrapeTextRec(c, depth + 1);
            if (r.length() > 0) {
                return r;
            }
        }
        return "";
    }

    // ==================== 悬浮框自愈 ====================

    /**
     * 看门狗调用：有些 ROM 在切换到其他应用后会把悬浮窗收走。
     * 发现框没了就自动补回来；激活中的框丢了就复位到待命，保证屏幕上始终有框。
     */
    public void ensureOverlayAlive() {
        try {
            // 用户要求 v2.33（半小时环境检查的补充，这里每 2.5 秒就能查一次）：
            // 识别状态卡死（ARMED/DOUBAO 超时 1 分钟没回待命）会让本地语音一直停在
            // 「豆包识别中…」的暂停词表上，命令再也收不到 → 强制回待命、换回命令词表
            long nowMs = System.currentTimeMillis();
            if ((phase == PHASE_DOUBAO && nowMs > doubaoDeadline + 60000L)
                    || (phase == PHASE_ARMED && nowMs > armedDeadline + 60000L)) {
                KeepService.note("识别状态卡住超过 1 分钟 → 强制回待命，恢复本地语音");
                backToIdle();
                return;
            }
            if (!useDoubao() && !manualBoxOpen) {
                // 免豆包模式：屏幕上不该有任何框，有残留就清掉。
                // 「手动」开的命令框除外（v2.35）：它是用户点名要的输入通道，
                // 不受豆包开关影响——否则看门狗每 2.5 秒就把框清一次，看起来就是"弹一下就没了"
                if (box != null) {
                    removeBox();
                }
                return;
            }
            boolean alive = box != null && box.isAttachedToWindow();
            if (alive) {
                return;
            }
            if (phase == PHASE_IDLE) {
                showIdleBox();
                if (box != null) {
                    KeepService.note("悬浮框被系统收走了，已自动恢复");
                }
            } else {
                // 激活中的框丢了：状态复位，回到待命（重新有框）
                phase = PHASE_IDLE;
                handler.removeCallbacks(tickTask);
                handler.removeCallbacks(matchTask);
                showIdleBox();
                KeepService.note("输入框丢了，已复位到待命");
                if (useDoubao()) {
                    KeepService.listenVocab(WakeEngine.idleVocab(), "在听唤醒词：说「嗨小白」");
                } else {
                    KeepService.listenVocab(WakeEngine.commandVocab(), "直接听命令（不用豆包）");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== 输入法麦克风 ====================

    private static final int MIC_MIN_Y = 45;   // 节点扫描时：Mic 键一定在屏幕下半部（低于这个百分比的不算）
    /** 键盘上豆包麦克风/语音键的默认位置（用户标定 v2.34：横向 32%，纵向 66%） */
    private static final int MIC_DEF_X = 32;
    private static final int MIC_DEF_Y = 66;

    private static final String[] MIC_WORDS = {
            "麦克风", "语音", "说话", "录音", "按住", "话筒",
            "mic", "voice", "speak", "record"
    };

    /** 输入法是否正在显示（拿不准时按“没显示”处理，宁可不点） */
    private boolean isImeShowing() {
        try {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) {
                return false;
            }
            if (boxEdit != null && boxEdit.hasFocus() && imm.isAcceptingText()) {
                return true;
            }
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (AccessibilityWindowInfo w : wins) {
                    if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 输入法窗口是否真的在屏幕上（TYPE_INPUT_METHOD 窗口存在才算） */
    private boolean imeWindowVisible() {
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (AccessibilityWindowInfo w : wins) {
                    if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 在输入法窗口里扫「麦克风/语音」按钮，找不到返回 null */
    private AccessibilityNodeInfo scanMicNode() {
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (AccessibilityWindowInfo w : wins) {
                    if (w.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        continue;
                    }
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root == null) {
                        continue;
                    }
                    AccessibilityNodeInfo btn = findMicNode(root, 0);
                    if (btn != null) {
                        return btn;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 点中麦克风节点：优先无障碍点击，不行就按它的坐标点；成功返回 true */
    private boolean clickMicNode(AccessibilityNodeInfo btn) {
        String label = describeNode(btn);
        try {
            if (btn.isClickable()) {
                btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                KeepService.note("已点中键盘麦克风（" + label + "）");
                return true;
            }
            Rect r = new Rect();
            btn.getBoundsInScreen(r);
            int[] s = screen();
            if (s != null && r.width() > 0 && r.height() > 0) {
                int px = r.centerX() * 100 / s[0];
                int py = r.centerY() * 100 / s[1];
                // 只在下半屏才把坐标记下来当标定值，千万别用上半屏的节点覆盖掉用户标定的 Mic 位置
                if (py >= MIC_MIN_Y && px > 0 && px < 100) {
                    getSharedPreferences("cfg", MODE_PRIVATE).edit()
                            .putInt("micx", px)
                            .putInt("micy", py)
                            .putBoolean("micCalibrated", true)
                            .apply();
                }
                tapAt(px, py);
                KeepService.note("已点中键盘麦克风（" + label + "），坐标 " + px + "%," + py + "%");
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 按标定坐标盲点一次麦克风位置 */
    private void blindTapMic() {
        int mx = getSharedPreferences("cfg", MODE_PRIVATE).getInt("micx", MIC_DEF_X);
        int my = getSharedPreferences("cfg", MODE_PRIVATE).getInt("micy", MIC_DEF_Y);
        // 只挡明显是垃圾的值（0/0、贴边）；合理范围内的值一律尊重，不擅自覆盖
        if (mx < 5 || mx > 95 || my < 20 || my > 99) {
            mx = MIC_DEF_X;
            my = MIC_DEF_Y;
            getSharedPreferences("cfg", MODE_PRIVATE).edit()
                    .putInt("micx", mx).putInt("micy", my).apply();
        }
        tapAt(mx, my);
        KeepService.note("已按标定/默认坐标 " + mx + "%," + my + "% 点一次豆包麦克风");
    }

    /** 兑底界面用：一次性的点Mic尝试 */
    public String tryTapImeMic() {
        AccessibilityNodeInfo btn = scanMicNode();
        if (btn != null && clickMicNode(btn)) {
            return "已点中键盘麦克风";
        }
        if (!imeWindowVisible()) {
            return "键盘没显示，跳过点击";
        }
        blindTapMic();
        return "没找到麦克风按钮，已按标定坐标点";
    }

    private AccessibilityNodeInfo findMicNode(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 25) {
            return null;
        }
        if (matchMicNode(node)) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            AccessibilityNodeInfo found = findMicNode(child, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * 这个节点像不像键盘上的麦克风键。
     * 除了名字对得上，还必须是「下半屏 + 按钮大小」：
     * 以前只看名字，加了 flagRetrieveInteractiveWindows 之后窗口能看到了，
     * 结果会把输入法里其他带「语音」字样的节点（或一大块容器）当成 Mic，
     * 点不成还会把它的坐标写进 micx/micy，把标定坐标弄坏 → 以后再也点不到 Mic。
     */
    private boolean matchMicNode(AccessibilityNodeInfo n) {
        CharSequence text = n.getText();
        CharSequence desc = n.getContentDescription();
        CharSequence hint = n.getHintText();
        String all = (text == null ? "" : text.toString())
                + " " + (desc == null ? "" : desc.toString())
                + " " + (hint == null ? "" : hint.toString());
        all = all.toLowerCase();
        if (all.trim().length() == 0) {
            return false;
        }
        boolean nameOk = false;
        for (String k : MIC_WORDS) {
            if (all.contains(k)) {
                nameOk = true;
                break;
            }
        }
        if (!nameOk) {
            return false;
        }
        // 位置和大小要像个键盘按键
        try {
            int[] s = screen();
            if (s == null || s[0] <= 0 || s[1] <= 0) {
                return false;
            }
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() <= 0 || r.height() <= 0) {
                return false;
            }
            if (r.centerY() * 100 / s[1] < MIC_MIN_Y) {
                return false;   // 不在键盘区
            }
            if (r.width() * 100 / s[0] > 30) {
                return false;   // 太宽，不是单个按键
            }
            if (r.height() * 100 / s[1] > 18) {
                return false;   // 太高，不是单个按键
            }
        } catch (Exception ignored) {
            return false;
        }
        return true;
    }

    private String describeNode(AccessibilityNodeInfo n) {
        CharSequence desc = n.getContentDescription();
        if (desc != null && desc.length() > 0) {
            return desc.toString();
        }
        CharSequence text = n.getText();
        if (text != null && text.length() > 0) {
            return text.toString();
        }
        return "按钮";
    }

    /**
     * 「测试Mic」按钮（主菜单）：先把输入框、豆包键盘弹出来，然后【你自己】点一下
     * 键盘上的麦克风按钮，我按你点的位置把 Mic 坐标记下来（并补一下那次点击，
     * 让豆包真的开始听）。这样就不用猜坐标了。
     */
    public void startMicTest() {
        try {
            if (phase != PHASE_IDLE) {
                backToIdle();
            }
            // 这一轮不自动点 Mic：Mic 由你亲手点，程序只负责记位置
            micClicked = true;
            micAttempts = 99;
            micBlindDone = true;
            micTestMode = true;
            showActiveBox(true);          // 出输入框 + 弹出豆包键盘

            // 识别结果落框后照常处理（匹配到命令就执行，没匹配就只留在框里）
            phase = PHASE_DOUBAO;
            long now = System.currentTimeMillis();
            doubaoStartAt = now;
            doubaoDeadline = now + 30000;   // 测试模式给足 30 秒
            micQuietDeadline = now + 30000;
            snapAtMic = "";
            lastBoxText = "";
            lastBoxChangeAt = now;
            handler.removeCallbacks(tickTask);
            handler.postDelayed(tickTask, 300);

            // 麦克风让给豆包：本地语音包先安静（你自己点 Mic 之后豆包就开始了）
            KeepService.listenVocab(null, "测试Mic：麦克风让给豆包");

            toast("键盘出来后，点一下键盘上的麦克风按钮，我会记住它的位置");
            KeepService.note("测试Mic：请点一下键盘上的麦克风按钮");

            // 等键盘真的弹出来了，再挂上「点哪记哪」的浮层
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startPickMic();
                }
            }, 1000);
        } catch (Throwable t) {
            toast("测试Mic 启动失败：" + t.getClass().getSimpleName());
        }
    }

    /** 手动标定麦克风位置 */
    public void startPickMic() {
        if (pickerMic != null) {
            return;
        }
        if (wm == null) {
            wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        }
        if (wm == null) {
            return;
        }
        int[] s = screen();
        if (s == null) {
            return;
        }
        final int sw = s[0];
        final int sh = s[1];

        FrameLayout fl = new FrameLayout(this);
        fl.setBackgroundColor(0x33000000);

        TextView tv = new TextView(this);
        tv.setText("点一下键盘上的「麦克风」按钮");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16f);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundColor(0xAA000000);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = 120;
        fl.addView(tv, lp);

        fl.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    int px = (int) (e.getRawX() * 100f / sw);
                    int py = (int) (e.getRawY() * 100f / sh);
                    px = px < 0 ? 0 : (px > 100 ? 100 : px);
                    py = py < 0 ? 0 : (py > 100 ? 100 : py);
                    getSharedPreferences("cfg", MODE_PRIVATE).edit()
                            .putInt("micx", px)
                            .putInt("micy", py)
                            .putBoolean("micCalibrated", true)
                            .apply();
                    removePickerMic();
                    tapAt(px, py);
                    toast("已记住麦克风位置：" + px + "% , " + py + "%");
                    KeepService.note("已记住麦克风(Mic)位置：" + px + "% , " + py + "%");
                }
                return true;
            }
        });

        WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                winType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        wlp.gravity = Gravity.TOP | Gravity.START;

        try {
            wm.addView(fl, wlp);
            pickerMic = fl;
        } catch (Exception e) {
            pickerMic = null;
        }
    }

    private void removePickerMic() {
        if (pickerMic != null && wm != null) {
            try {
                wm.removeView(pickerMic);
            } catch (Exception ignored) {
            }
        }
        pickerMic = null;
    }

    // ==================== 录制模式 ====================
    // 用户要求：录下自己的一串动作（移动位置、点击、多次重复的简单动作），
    // 绑到一个语音命令名上；以后一听到这个名字，就自动把这串动作做一遍。
    // 用户要求：录制时的动作要【真的执行】（点哪点哪、滑哪滑哪，包括上滑/左滑），
    // 做法 = 浮层接住触摸 → 记下来 → 立刻用无障碍手势原样转发给系统（边录边执行）。

    private FrameLayout recLayer;
    private TextView recHud;
    // 转发手势期间把浮层临时设成「不挡触摸」，让动作真正落到下面的应用
    //（用户反馈：以前浮层一直挡着，只记到第一步、后面的动作也没真执行）
    private WindowManager.LayoutParams recLp = null;
    private volatile boolean recPassThrough = false;
    private String recName = "";
    private final java.util.List<int[]> recSteps = new java.util.ArrayList<>();
    private long recLastAt = 0;
    // 转发队列：一个手势做完（onCompleted）才发下一个，免得互相取消
    private final java.util.ArrayDeque<GestureDescription> recQueue = new java.util.ArrayDeque<>();
    private boolean recPumping = false;
    // 录完起名的弹框（悬浮窗，能弹键盘输入）
    private LinearLayout namingLayer;

    /**
     * 开始录制。name 可以为空（语音「录制」开始的就是这种）：
     * 做完动作【双击屏幕】结束 → 弹框起名 → 保存。
     */
    public void startRecordingCmd(final String name) {
        final String n = Commands.normalize(name);
        if (wm == null) {
            wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        }
        int[] s = screen();
        if (wm == null || s == null) {
            toast("悬浮窗还没准备好：请先点 ② 授予悬浮窗权限");
            return;
        }
        removeRecLayer();
        recName = n;
        recSteps.clear();
        recLastAt = System.currentTimeMillis();
        final int sw = s[0];
        final int sh = s[1];

        FrameLayout fl = new FrameLayout(this);
        // 完全透明：不影响你看下面的界面（动作由我们接住后原样转发，真的生效）
        fl.setBackgroundColor(0x00000000);

        // 用户反馈：原来的大黑框（带 3 个按钮）挡住屏幕没法录。
        // 改成只在【屏幕最下方】显示一条小字「录制中」，不要按钮；
        // 结束 = 再说一次「录制」或「录制结束」→ 弹框起名。
        recHud = new TextView(this);
        recHud.setTextColor(0xFFFFFFFF);
        recHud.setTextSize(11f);
        recHud.setBackgroundColor(0xB3000000);
        recHud.setPadding(20, 6, 20, 6);
        recHud.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        clp.bottomMargin = 40; // 贴屏幕最底下，不挡画面
        fl.addView(recHud, clp);

        fl.setOnTouchListener(new View.OnTouchListener() {
            float dx = 0;
            float dy = 0;
            long downAt = 0;
            boolean moved = false;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                // 转发手势期间浮层已不挡触摸，正常不会收到事件；保险起见直接忽略
                if (recPassThrough) {
                    return true;
                }
                try {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            dx = e.getRawX();
                            dy = e.getRawY();
                            downAt = System.currentTimeMillis();
                            moved = false;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            if (Math.abs(e.getRawX() - dx) > 40
                                    || Math.abs(e.getRawY() - dy) > 40) {
                                moved = true;
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL: {
                            // CANCEL 也当一次完整动作记（多半是被转发手势打断的，别丢步）
                            long now = System.currentTimeMillis();
                            long dur = now - downAt;
                            int delay = (int) Math.max(0,
                                    Math.min(8000, downAt - recLastAt));
                            float fx1 = dx;
                            float fy1 = dy;
                            float fx2 = e.getRawX();
                            float fy2 = e.getRawY();
                            int x1 = pct(fx1, sw);
                            int y1 = pct(fy1, sh);
                            int x2 = pct(fx2, sw);
                            int y2 = pct(fy2, sh);
                            boolean isSwipe = moved && dur > 120;
                            // 用户要求：不再用「双击屏幕」结束录制（有的手机双击=截图），
                            // 结束靠说「录制」/「录制结束」或点 HUD 上的「完成」。
                            if (isSwipe) {
                                recSteps.add(new int[]{RecordedCmds.TYPE_SWIPE,
                                        x1, y1, x2, y2,
                                        (int) Math.max(60, Math.min(3000, dur)), delay});
                            } else {
                                recSteps.add(new int[]{RecordedCmds.TYPE_TAP,
                                        x2, y2, 0, 0, 0, delay});
                            }
                            recLastAt = now;
                            // 用户要求：录制时也要真的点/真的滑
                            // → 原样转发给系统（浮层在转发期间会临时不挡触摸）
                            forwardNow(fx1, fy1, fx2, fy2, dur, isSwipe);
                            refreshRecHud();
                            KeepService.note("已记第 " + recSteps.size() + " 步："
                                    + (isSwipe
                                            ? "滑 (" + x1 + "," + y1 + ")→(" + x2 + "," + y2 + ") 用时 " + dur + "ms"
                                            : "点 (" + x2 + "," + y2 + ")")
                                    + "，距上一步 " + delay + "ms");
                            return true;
                        }
                        default:
                            return false;
                    }
                } catch (Throwable t) {
                    return true;
                }
            }
        });
        refreshRecHud();

        WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                winType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        wlp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(fl, wlp);
            recLp = wlp;
            recLayer = fl;
        } catch (Exception e) {
            recLayer = null;
            toast("录制浮层加不上（是不是没有悬浮窗权限？）");
            return;
        }
        KeepService.note("开始录制动作（还没起名）：现在做的动作会真的执行并被记下来，做完【双击屏幕】结束");
        toast(n.length() > 0
                ? "开始录制「" + n + "」：动作会真的执行并记下来，做完双击屏幕结束"
                : "开始录制：去做动作（会真的执行），做完双击屏幕结束");
    }

    /** 把刚录到的动作原样转发给系统（真的点/真的滑） */
    private void forwardNow(float x1, float y1, float x2, float y2,
                            long durMs, boolean isSwipe) {
        // 转发期间让浮层不挡触摸，注入的手势才能真正落到下面的应用上
        setRecPassThrough(true);
        long d;
        if (isSwipe) {
            d = Math.max(60, Math.min(3000, durMs));
        } else {
            d = Math.max(60, Math.min(600, durMs));
        }
        Path p = new Path();
        p.moveTo(x1, y1);
        // 点击时起止点重合：零长度路径会被系统拒，挪 0.5px
        if (Math.abs(x2 - x1) < 1 && Math.abs(y2 - y1) < 1) {
            p.lineTo(x2 + 0.5f, y2);
        } else {
            p.lineTo(x2, y2);
        }
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, d));
        recQueue.add(b.build());
        pumpRecQueue();
    }

    /** 一个转发完再发下一个（dispatchGesture 同时发两段会互相取消） */
    private void pumpRecQueue() {
        if (recPumping) {
            return;
        }
        final GestureDescription g = recQueue.poll();
        if (g == null) {
            return;
        }
        recPumping = true;
        // 先确保浮层不挡触摸。窗口 flag 改动是异步生效的，立刻发手势
        // 可能被我们自己的浮层吃掉（应用就没反应）→ 等 120ms 再发
        setRecPassThrough(true);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                dispatchRec(g);
            }
        }, 120);
    }

    private void dispatchRec(final GestureDescription g) {
        boolean sent = false;
        try {
            sent = dispatchGesture(g, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    KeepService.note("录制转发完成：动作已真正落到应用上");
                    recPumping = false;
                    pumpRecQueue();
                    maybeReleasePassThrough();
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    KeepService.note("录制转发被取消，重新排队");
                    recPumping = false;
                    pumpRecQueue();
                    maybeReleasePassThrough();
                }
            }, handler);
        } catch (Throwable t) {
            sent = false;
        }
        if (!sent) {
            recPumping = false;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    pumpRecQueue();
                }
            }, 120);
        }
    }

    /** 屏幕坐标 → 百分比 */
    private static int pct(float raw, int total) {
        if (total <= 0) {
            return 0;
        }
        int p = (int) (raw * 100f / total);
        return p < 0 ? 0 : (p > 100 ? 100 : p);
    }

    private void refreshRecHud() {
        if (recHud == null) {
            return;
        }
        int tap = 0, up = 0, down = 0, left = 0, right = 0;
        for (int[] st : recSteps) {
            if (st[0] == RecordedCmds.TYPE_TAP) {
                tap++;
            } else if (Math.abs(st[4] - st[2]) >= Math.abs(st[3] - st[1])) {
                if (st[4] < st[2]) { up++; } else { down++; }
            } else {
                if (st[3] < st[1]) { left++; } else { right++; }
            }
        }
        StringBuilder sum = new StringBuilder();
        if (tap > 0) { sum.append(" 点×").append(tap); }
        if (up > 0) { sum.append(" 上滑×").append(up); }
        if (down > 0) { sum.append(" 下滑×").append(down); }
        if (left > 0) { sum.append(" 左滑×").append(left); }
        if (right > 0) { sum.append(" 右滑×").append(right); }
        recHud.setText("🔴 录制中：已记 " + recSteps.size() + " 步" + sum
                + (recName.length() > 0 ? "（" + recName + "）" : "") + "\n"
                + "做完说「录制」或「录制结束」→ 弹框起名保存");
    }

    /** 录完了（再听到「录制」）：存起来 / 弹框起名 */
    private void finishRecording() {
        endRecordingAndName();
    }

    /** 收尾：有名字直接存；没名字弹框让用户起一个（双击结束也走这里） */
    private void endRecordingAndName() {
        final String n = recName;
        java.util.List<int[]> steps = new java.util.ArrayList<>(recSteps);
        removeRecLayer();
        if (steps.isEmpty()) {
            if (n.length() > 0) {
                RecordedCmds.remove(this, n);
            }
            toast("一步都没记到，没保存");
            KeepService.note("录制没记到任何动作 → 没保存");
            return;
        }
        if (n.length() > 0) {
            saveRecorded(n, steps);
        } else {
            showNamingDialog(steps);
        }
    }

    /** 存好一个录制命令 + 名字进词表 */
    private void saveRecorded(final String n, final java.util.List<int[]> steps) {
        RecordedCmds.save(this, n, steps);
        RecordedCmds.reload(this);
        String desc = RecordedCmds.describe(this, n);
        postStatus("录制完成：「" + n + "」" + desc);
        KeepService.note("录制完成：语音命令「" + n + "」= " + desc
                + "（以后说「" + n + "」就自动重做；短剧模式里也能用）");
        toast("录好了：「" + n + "」= " + desc + "。以后说这个名字就自动做");
        refreshVocabNow();
    }

    /** 录完弹框起名（悬浮窗，能弹键盘输入） */
    /** 「阅读」：开关阅读模式。开 = 自动打开阅读软件 + 点一次 (50%,20%)；关 = 只关（用户要求） */
    private void toggleReading() {
        boolean on = !readingOn();
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("reading", on).apply();
        if (manualBoxOpen) {
            manualBoxOpen = false;
            // 手动框开着时本地引擎只听「手动」，强制关框要把完整词表换回来（v2.35）
            KeepService.applyVoiceSetting(this);
        }
        if (!on) {
            toast("阅读模式已关");
            KeepService.note("阅读模式已关（上页/下页/蓝牙音量键翻页随之失效）");
            showIdleBox(); // 模式切了，待命小框跟着换
            return;
        }
        showIdleBox(); // 阅读模式开：待命小框换成小字提示
        KeepService.note("阅读模式已开：只听 上页/下页/蓝牙音量键/「阅读」/小白再见；"
                + "自动打开阅读软件后点一次 (50%,20%)");
        toast("阅读模式已开");
        String app = findReadingApp();
        if (app == null) {
            toast("没找到阅读软件：先说「打开某某」开一次，以后「阅读」会自动开它");
            KeepService.note("没找到阅读软件：请用「打开某某」先打开一次（名字会记住）");
            return;
        }
        executeCommand(Commands.CMD_OPEN, "打开" + app);
        // 打开后自动点一次 (50%,20%)（等应用起来；用户要求）
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!readingOn() || !isIdle()) {
                    return;
                }
                tapAt(50, 20);
                KeepService.note("阅读模式：已自动点一次 (50%,20%)");
            }
        }, 6000);
    }

    /** 找阅读软件：优先用记住的（readApp），否则搜名字含 阅读/小说/读书 的应用；
     *  都没有就交给「阅读」这个别名（openAppByName 里按名字找） */
    private String findReadingApp() {
        String saved = getSharedPreferences("cfg", MODE_PRIVATE).getString("readApp", "");
        if (saved.length() > 0) {
            return saved;
        }
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            String best = null;
            for (android.content.pm.PackageInfo pi : pm.getInstalledPackages(0)) {
                String label;
                try {
                    label = String.valueOf(pm.getApplicationLabel(pi.applicationInfo));
                } catch (Throwable t2) {
                    continue;
                }
                String n = Commands.normalize(label);
                if (n.contains("阅读") || n.contains("小说") || n.contains("读书")) {
                    if (best == null || label.length() < best.length()) {
                        best = label; // 名字最短的通常就是专门的阅读软件
                    }
                }
            }
            if (best != null) {
                getSharedPreferences("cfg", MODE_PRIVATE).edit()
                        .putString("readApp", best).apply();
                KeepService.note("找到阅读软件「" + best + "」（已记住，⚙ 可换）");
                return best;
            }
        } catch (Throwable t) {
            // 权限/可见性限制：交给「阅读」别名去找
        }
        return "阅读";
    }

    // 旧手动框已删除（用户要求 v2.30）：「手动」改开文本命令框（showTextBox）
    private LinearLayout manualLayer;
    private boolean manualBoxOpen = false;
    /** 手动框这次是几时开的（开框 3 秒内的「手动」当回声忽略，防识别两遍关掉框） */
    private long manualOpenedAt = 0L;

    /** 手动命令框（用户要求 v2.33）：原「嗨小白」唤醒编辑框改造。
     *  说「手动」弹出：只在非短剧、非阅读模式；框有焦点、自动弹键盘、
     *  等键盘真的弹出来才点豆包 Mic（idleMicTask，借用嗨小白的等键盘逻辑），
     *  想说可以说、想打可以打；没有时间限制——直到再听到「手动」才关框并执行框里的命令。 */
    private void showTextBox(String why) {
        // v2.31：文本命令框就是待命小框（showIdleBox 的文本模式），不再另起一套
        KeepService.note(why);
        showIdleBox();
    }

    /** 手动命令框执行：不管对错都下达 —— 认得出就执行；认不出也记日志+提示 */
    private void executeTextCommand(String raw) {
        // v2.36：豆包语音会往框里塞标点和「手动」回声——
        // normalize 去标点，stripManualEcho 去「手动」，两道都过再匹配，命令才执行得了
        String t = stripManualEcho(Commands.normalize(raw));
        if (t.length() == 0) {
            toast("框里没有命令");
            return;
        }
        // 不过模式白名单：错误命令也下达（用户要求）
        int cmd = Commands.match(t);
        if (cmd == Commands.CMD_NONE) {
            KeepService.note("手动命令框下达：「" + t + "」（命令表里没有，不执行）");
            toast("「" + t + "」不在命令表里");
            return;
        }
        KeepService.note("手动命令框执行：「" + t + "」");
        if (executeCommand(cmd, t)) {
            toastResult(cmd, "执行：" + t);
            // 框留着（待命小框本来就该在），只清空文字好继续输
            if (boxEdit != null) {
                boxEdit.setText("");
            }
        } else {
            KeepService.note("手动命令框：「" + t + "」被拦，没有执行（原因看上一条日志）");
            toast("被拦：原因看日志");
        }
    }

    /** v2.36：去掉豆包写进框里的「手动」回声（说「手动」结束时输入法常把它一起写进框）。
     *  命令表里没有任何命令含这两个字，整串删掉再匹配；
     *  不然 match() 会先命中 CMD_MANUAL（它排在下一集等命令前面），命令就执行不了。 */
    private static String stripManualEcho(String t) {
        if (t == null) {
            return "";
        }
        return t.replace("手动", "");
    }

    /** v2.36：等豆包把字写完再关框执行。
     *  每 300ms 读一次框：文字和上一次一样（= 写完了）就收尾；最多等 5 拍约 1.5 秒。
     *  手动打字的第一个 300ms 就稳定，基本无感。 */
    private void settleBoxThenClose() {
        final String[] prev = {boxEdit == null ? "" : boxEdit.getText().toString()};
        final int[] tries = {0};
        final Runnable[] check = new Runnable[1];
        check[0] = new Runnable() {
            @Override
            public void run() {
                if (!manualBoxOpen || boxEdit == null) {
                    return; // 等待期间框被关了（切模式/退出）：不用再收尾
                }
                String cur = boxEdit.getText().toString();
                tries[0]++;
                boolean stable = cur.equals(prev[0]);
                prev[0] = cur;
                if (!stable && tries[0] < 5) {
                    handler.postDelayed(this, 300);  // 豆包还在往框里写字，再等一拍
                    return;
                }
                finishManualClose(cur);
            }
        };
        handler.postDelayed(check[0], 300);
    }

    /** 手动框收尾（v2.36）：关框 + 恢复完整词表 + 清洗（去标点/去「手动」回声）后执行 */
    private void finishManualClose(String raw) {
        manualBoxOpen = false; // 先关标志：万一框里的字清洗后还是「手动」相关，不再递归
        showIdleBox();
        KeepService.applyVoiceSetting(this); // 换回完整词表，恢复听全部命令
        String norm = stripManualEcho(Commands.normalize(raw));
        if (norm.length() == 0) {
            KeepService.note("手动命令框：清洗后框里没有命令（原文「" + raw + "」），只关框不执行");
            toast("框里没有命令：已关闭命令框");
        } else {
            executeTextCommand(raw);
        }
    }


    private void removeManualBox() {
        if (manualLayer != null && wm != null) {
            try {
                wm.removeView(manualLayer);
            } catch (Exception ignored) {
            }
        }
        manualLayer = null;
    }

    private void showNamingDialog(final java.util.List<int[]> steps) {
        if (wm == null) {
            wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        }
        if (wm == null) {
            toast("悬浮窗没准备好，这串动作没保存");
            return;
        }
        removeNamingLayer();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xF2000000);
        col.setPadding(30, 26, 30, 22);

        TextView tv = new TextView(this);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(13f);
        tv.setText("录好了 " + steps.size() + " 步。给这串动作起一个语音命令名：\n"
                + "（以后说这个名字，程序就自动重做这串动作）");

        final EditText et = new EditText(this);
        et.setHint("命令名，比如：点赞");
        // 用户反馈：默认深色文字在黑底弹框上根本看不到 → 白字灰提示
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFFAAAAAA);
        et.setTextSize(14f);
        et.setSingleLine(true);

        Button ok = new Button(this);
        ok.setText("保存");
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String n = Commands.normalize(et.getText().toString());
                if (n.length() == 0) {
                    toast("先写一个命令名，比如：点赞");
                    return;
                }
                removeNamingLayer();
                saveRecorded(n, steps);
            }
        });
        Button drop = new Button(this);
        drop.setText("丢弃");
        drop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                removeNamingLayer();
                toast("已丢弃，没保存");
                KeepService.note("录制的动作已丢弃（没起名字）");
            }
        });
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(ok, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(drop, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        col.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        col.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        col.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 故意不加 FLAG_NOT_FOCUSABLE：不加才能弹键盘输入
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                winType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE;
        try {
            wm.addView(col, lp);
            namingLayer = col;
        } catch (Exception e) {
            namingLayer = null;
            toast("起名弹框加不上（悬浮窗权限？），这串动作没保存");
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (namingLayer == null) {
                        return;
                    }
                    et.requestFocus();
                    InputMethodManager imm = (InputMethodManager) getSystemService(
                            Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
                    }
                } catch (Throwable ignored) {
                }
            }
        }, 250);
        KeepService.note("录制结束：已记 " + steps.size() + " 步，等起名保存");
        toast("双击结束录制：起个名字保存（共 " + steps.size() + " 步）");
    }

    private void removeNamingLayer() {
        if (namingLayer != null && wm != null) {
            try {
                wm.removeView(namingLayer);
            } catch (Exception ignored) {
            }
        }
        namingLayer = null;
    }

    /** 转发手势期间把录制浮层临时设成「不挡触摸」 */
    private void setRecPassThrough(final boolean on) {
        if (recPassThrough == on) {
            return;
        }
        recPassThrough = on;
        try {
            if (recLayer != null && recLp != null && wm != null) {
                if (on) {
                    recLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                } else {
                    recLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                }
                wm.updateViewLayout(recLayer, recLp);
            }
        } catch (Throwable t) {
            KeepService.note("录制浮层切换触摸模式失败：" + t.getClass().getSimpleName());
        }
    }

    /** 队列空了（最后一次转发完）再恢复浮层的触摸，中间留 100ms 缓冲 */
    private void maybeReleasePassThrough() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (recLayer == null) {
                    return;
                }
                if (recQueue.isEmpty() && !recPumping) {
                    setRecPassThrough(false);
                } else {
                    maybeReleasePassThrough();
                }
            }
        }, 100);
    }

    private void removeRecLayer() {
        if (recLayer != null && wm != null) {
            try {
                wm.removeView(recLayer);
            } catch (Exception ignored) {
            }
        }
        recPassThrough = false;
        recLp = null;
        recLayer = null;
        recHud = null;
        recQueue.clear();
    }

    /** 名字 → 动作（给主界面列表用） */
    public static String recDescribe(Context ctx, String name) {
        return RecordedCmds.describe(ctx, name);
    }

    /**
     * 听到自己录制的命令 → 自动把录下来的动作重放一遍（多次点击 / 多次划动）。
     */
    private void replayRecorded(final String heard) {
        String name = RecordedCmds.find(this, Commands.normalize(heard));
        if (name == null) {
            name = Commands.normalize(heard);
        }
        final java.util.List<int[]> steps = RecordedCmds.steps(this, name);
        if (steps.isEmpty()) {
            postStatus("自己录制的「" + name + "」没存动作可做");
            KeepService.note("听到「" + heard + "」，但录制的「" + name + "」里没有动作");
            return;
        }
        postStatus("执行录制的「" + name + "」：" + steps.size() + " 步");
        KeepService.note("听到录制的命令「" + name + "」→ 自动重放 " + steps.size() + " 步动作");
        replayStep(steps, 0, name);
    }

    private void replayStep(final java.util.List<int[]> steps, final int idx,
                            final String name) {
        if (idx >= steps.size()) {
            postStatus("录制动作「" + name + "」完成（共 " + steps.size() + " 步）");
            KeepService.note("录制的「" + name + "」做完了（共 " + steps.size() + " 步）");
            return;
        }
        final int[] st = steps.get(idx);
        long used;
        if (st[0] == RecordedCmds.TYPE_TAP) {
            tapAt(st[1], st[2]);
            used = 120; // 点击手势本身的时长（和 tapms 默认一致）
        } else {
            swipeByPct(st[1], st[2], st[3], st[4], st[5]);
            used = Math.max(60, Math.min(3000, st[5]));
        }
        // 用户要求：动作之间要有一定的延时。
        // 下一步的开销 = 这一步手势本身的时长 + 下一步录制的间隔 + 额外 200ms，
        // 且相邻两个手势至少隔 300ms（挨太近会被系统丢掉）
        long nextDelay = idx + 1 < steps.size() ? steps.get(idx + 1)[6] : 0;
        long gap = used + Math.max(REPLAY_GAP_MS, nextDelay + REPLAY_EXTRA_MS);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                replayStep(steps, idx + 1, name);
            }
        }, gap);
    }

    /**
     * 任意起止点划一下（录制回放专用）。
     * 不参与“一次命令只滑一次”的锁 —— 因为是用户自己录的动作，本来就可能连着好几个。
     */
    public void swipeByPct(int x1, int y1, int x2, int y2, int dur) {
        int[] s = screen();
        if (s == null) {
            return;
        }
        int d = Math.max(60, Math.min(3000, dur));
        Path p = new Path();
        p.moveTo(s[0] * x1 / 100f, s[1] * y1 / 100f);
        p.lineTo(s[0] * x2 / 100f, s[1] * y2 / 100f);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, d));
        try {
            dispatchGesture(b.build(), null, null);
        } catch (Throwable ignored) {
        }
    }

    /** 词表重装一次（录了新命令 / 删了命令之后要用：名字得进词表，Vosk 才听得懂） */
    public void refreshVocabNow() {
        if (phase != PHASE_IDLE) {
            return; // 正在听一轮命令，别打断
        }
        if (useDoubao()) {
            KeepService.listenVocab(WakeEngine.idleVocab(), "在听唤醒词：说「嗨小白」");
        } else {
            KeepService.listenVocab(WakeEngine.commandVocab(), "直接听命令（不用豆包）");
        }
    }

    // ==================== 命令执行 ====================

    /**
     * 执行命令。返回值 = 真的动手了？
     * 被防误听/单字闸拦下时返回 false —— 调用方就不要弹“执行：…”的提示了
     * （用户要求：没真执行就不在屏幕下方显示）。
     */
    public boolean executeCommand(int cmd, String text) {
        if (cmd == Commands.CMD_NONE) {
            return false;
        }
        // ================= 防误听“双保险”（所有路径都走这里） =================
        // 1) 同一条命令 1.5 秒内只执行一次（本地引擎会把同一句话反复报好几遍）
        // 2) 成对命令 2.5 秒内不允许反着再来一次
        //    （声音大→声音小、上一集→下一集、上页→下页）：
        //    本地识别听错方向时就会“刚调大又调小”，用户根本不想这样
        long nowMs = System.currentTimeMillis();
        if (cmd == lastExecCmd && nowMs - lastExecAt < REPEAT_GUARD_MS) {
            KeepService.note("刚执行过「" + lastExecText + "」，又听到同一条「" + text
                    + "」（" + (nowMs - lastExecAt) + "ms 内），忽略重复");
            return false;
        }
        if (isOppositePair(lastExecCmd, cmd) && nowMs - lastExecAt < OPPOSITE_GUARD_MS) {
            KeepService.note("刚执行过「" + lastExecText + "」，又听到反过来的「" + text
                    + "」，按防误听忽略（" + (OPPOSITE_GUARD_MS / 1000) + " 秒内不反向）");
            return false;
        }
        // 用户要求：上滑/下滑类命令只能滑一次，不能连着好几次。
        // （本地识别会把同一句话反复报出来，间隔经常超过上面那个 1.5 秒）
        if (isSwipeCmd(cmd) && isSwipeCmd(lastExecCmd)
                && nowMs - lastExecAt < SWIPE_GUARD_MS) {
            KeepService.note("刚滑过一次（「" + lastExecText + "」，" + (nowMs - lastExecAt)
                    + "ms 前），「" + text + "」不再滑第二次（"
                    + (SWIPE_GUARD_MS / 1000) + " 秒内只滑一次）");
            return false;
        }
        // 用户要求：自己录制的命令是一串手势，也要防“识别抖一下就连做两遍”
        if (cmd == Commands.CMD_RECORDED && lastExecCmd == Commands.CMD_RECORDED
                && nowMs - lastExecAt < SWIPE_GUARD_MS) {
            KeepService.note("刚做过一遍录制的「" + lastExecText + "」（"
                    + (nowMs - lastExecAt) + "ms 前），「" + text
                    + "」不再做第二遍（" + (SWIPE_GUARD_MS / 1000) + " 秒内只做一次）");
            return false;
        }
        // 用户要求：一个字就说完的命令（停 / 大 / 小 / 亮 / 暗）
        // 只有在【真的有视频/声音在播】时才执行；
        // 安静环境下本地引擎的幻觉、或视频根本没在放时的偶然一声，
        // 都不该真地动屏幕/调音量。
        String nt = Commands.normalize(text);
        if (nt.length() == 1 && !isAudioPlaying() && cmd != Commands.CMD_RECORDED) {
            KeepService.note("听到了「" + text + "」，但没执行：现在没有视频/声音在播"
                    + "（单字命令只在播放时才动）");
            return false;
        }
        lastExecCmd = cmd;
        lastExecAt = nowMs;
        lastExecText = text;
        // 屏幕下方也提示一句（用户要求：听到命令要能看到，
        // 像广告/直播这种以前什么提示都没有）。
        // 用户要求：屏幕上只显示【规定的命令】——这里写的名字就是命令表里的那条。
        String cname = Commands.nameOf(cmd);
        if (cmd == Commands.CMD_RECORDED) {
            // 显示成用户自己起的名字，不叫“自己录制的动作”
            String rn = RecordedCmds.find(this, Commands.normalize(text));
            if (rn != null) {
                cname = "自己录制的「" + rn + "」";
            }
        }
        showStatusStrip("听到「" + text + "」→ " + (cname.length() == 0 ? "命令" : cname));
        // 用户定稿：唤醒时什么都不做（没暂停过任何东西），
        // 命令直接执行；(50%,20%) 点击只在「停/播放」命令里。
        if (cmd == Commands.CMD_RESUME) {
            executeCommandInner(cmd, text);
            return true;
        }
        final int c2 = cmd;
        final String t2 = text;
        runAfterResume(new Runnable() {
            @Override
            public void run() {
                executeCommandInner(c2, t2);
            }
        });
        return true;
    }

    private void executeCommandInner(int cmd, String text) {
        switch (cmd) {
            case Commands.CMD_NEXT:
                // 唤醒时把视频暂停了：很多 App 暂停状态下上滑不换集，先恢复再滑；
                // 而且必须等键盘收完再滑（不然滑在键盘上，拉到一半弹回）
                KeepService.note("「" + text + "」→ 上滑（下一集）");
                runAfterResume(new Runnable() {
                    @Override
                    public void run() {
                        whenImeGone(new Runnable() {
                            @Override
                            public void run() {
                                swipeUpOnly();
                            }
                        });
                    }
                });
                break;
            case Commands.CMD_PREV:
                KeepService.note("「" + text + "」→ 下滑（上一集）");
                runAfterResume(new Runnable() {
                    @Override
                    public void run() {
                        whenImeGone(new Runnable() {
                            @Override
                            public void run() {
                                swipeDownOnly();
                            }
                        });
                    }
                });
                break;
            case Commands.CMD_EXIT_LIVE:
                // 退出直播 / 退出广告：先边缘左划，落地页若有读秒广告，等读秒结束再上拉
                startExitLiveWatch();
                break;
            case Commands.CMD_SKIP_AD:
                // 自动找「跳过」按钮点掉；找不到按钮才走兜底延时上滑
                startSkipWatch();
                break;
            case Commands.CMD_PAGE_DOWN:
                runAfterResume(new Runnable() {
                    @Override
                    public void run() {
                        whenImeGone(new Runnable() {
                            @Override
                            public void run() {
                                tapAt(85, 85);
                            }
                        });
                    }
                });
                break;
            case Commands.CMD_PAGE_UP:
                runAfterResume(new Runnable() {
                    @Override
                    public void run() {
                        whenImeGone(new Runnable() {
                            @Override
                            public void run() {
                                tapAt(15, 85);
                            }
                        });
                    }
                });
                break;
            case Commands.CMD_RESUME:
            case Commands.CMD_TOGGLE_PLAY:
                // 用户要求：停 / 放 / 继续 / 播放 / 继续播放 = 【同一条命令】
                // → 就在 (50%,20%) 点一下
                whenImeGone(new Runnable() {
                    @Override
                    public void run() {
                        tapAt(PAUSE_X, PAUSE_Y);
                    }
                });
                KeepService.note("「" + text + "」→ 已在 (50%,20%) 点一下");
                break;
            case Commands.CMD_BRIGHT_UP:
                adjustBrightness(true);
                break;
            case Commands.CMD_BRIGHT_DOWN:
                adjustBrightness(false);
                break;
            case Commands.CMD_DOUBAO_ON:
                switchDoubao(true);
                // 说「打开豆包」的另一个意思：马上把豆包语音叫出来，好让你直接说话
                startDoubaoVoiceNow();
                break;
            case Commands.CMD_DOUBAO_TOGGLE:
                // 用户要求：只说「豆包」= 切换（勾着就关，没勾就开）
                boolean wasOn = useDoubao();
                switchDoubao(!wasOn);
                KeepService.note("「豆包」= 切换：原来" + (wasOn ? "勾着" : "没勾")
                        + " → 现在" + (wasOn ? "关掉" : "打开"));
                if (!wasOn) {
                    startDoubaoVoiceNow();
                }
                break;
            case Commands.CMD_DOUBAO_OFF:
                switchDoubao(false);
                break;
            case Commands.CMD_VOL_UP:
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        adjustMediaVolume(true);
                    }
                }, 600);
                break;
            case Commands.CMD_VOL_DOWN:
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        adjustMediaVolume(false);
                    }
                }, 600);
                break;
            case Commands.CMD_OPEN: {
                String name = extractAppName(text);
                if (name.length() == 0) {
                    KeepService.note("听不出要打开哪个应用：" + text);
                    break;
                }
                String err = openAppByName(name);
                if (err == null) {
                    KeepService.note("已打开「" + name + "」✓");
                    String nn = Commands.normalize(name);
                    if (nn.contains("阅读") || nn.contains("小说") || nn.contains("读书")) {
                        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                                .putString("readApp", name).apply();
                        KeepService.note("已把「" + name + "」记为阅读软件（「阅读」模式会自动开它）");
                    }
                } else {
                    KeepService.note("打开「" + name + "」失败：" + err);
                    toast("打开「" + name + "」失败：" + err);
                }
                break;
            }
            case Commands.CMD_RECORDED:
                // 录制模式：听到自己录的那个名字 → 自动把当时录的动作重放一遍
                replayRecorded(text);
                break;
            case Commands.CMD_MANUAL:
                if (manualBoxOpen && boxEdit != null) {
                    // v2.35 回声保护：本地引擎会把同一声「手动」上报两遍（临时结果+最终结果，
                    // 间隔可超过防重的 1.5 秒），第二遍会把刚开的框又关掉——
                    // 开框后 3 秒内的「手动」一律当回声忽略，这就是"框弹一下就没"的根因之一
                    if (System.currentTimeMillis() - manualOpenedAt < 3000) {
                        KeepService.note("手动命令框：开框 3 秒内的「手动」当回声忽略（防同一声识别两遍关掉框）");
                        break;
                    }
                    // v2.36：本地引擎听到「手动」时，豆包可能还没把最后几个字写进框
                    //（含标点和「手动」回声）——等框里文字稳定再关框执行（最多约 1.5 秒）
                    settleBoxThenClose();
                } else {
                    // 说「手动」= 打开命令框：焦点 → 等 200ms → 探测到豆包键盘 → 点 Mic（idleMicTask）
                    manualBoxOpen = true;
                    manualOpenedAt = System.currentTimeMillis();
                    showIdleBox();
                    // v2.35（学嗨小白把麦克风让干净）：开框期间本地引擎【只听「手动」】，
                    // 别的话全部交给豆包 Mic 写进框——不然同一句命令会被本地引擎抢先执行一遍
                    KeepService.listenVocab(new String[]{"手动"},
                            "手动命令框开着：只听「手动」（说完命令再说一次「手动」执行）");
                }
                break;
            case Commands.CMD_READING_TOGGLE:
                // 「阅读」= 开关阅读模式（开：自动打开阅读软件 + 点一次 50%,20%）
                toggleReading();
                break;
            case Commands.CMD_DRAMA_TOGGLE: {
                // 用户要求：「短剧」= 像豆包一样说一声就切换（没开就进、开着就退）
                boolean dramaWas = shortDramaOn();
                setShortDrama(!dramaWas, "语音「短剧」");
                break;
            }
            case Commands.CMD_RECORD_TOGGLE: {
                // 「录制」= 开始录制（先不起名）；
                // 录制中再说「录制」（或「录制结束」）= 结束并弹框（用户要求：不用双击屏幕）
                if (recLayer != null) {
                    endRecordingAndName();
                } else {
                    startRecordingCmd("");
                }
                break;
            }
            case Commands.CMD_SOUND_OFF:
                // 用户要求：接电话时要把音乐关掉 → 立刻禁音（不走恢复播放那套，要快）
                setMediaMute(true);
                break;
            case Commands.CMD_SOUND_ON:
                setMediaMute(false);
                break;
            case Commands.CMD_EXIT:
                shutdownAll();
                break;
            default:
                break;
        }
    }

    /**
     * 禁音 / 恢复声音（用户要求：接电话时要把音乐关掉）。
     * 禁音时记住当时的媒体音量，「声音」一键恢复；
     * 故意不加“成对命令反向锁”——接完电话要马上能恢复。
     */
    private void setMediaMute(boolean mute) {
        markVolSelf(); // 程序自己改的音量：轮询不当按键处理
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                KeepService.note("禁音/恢复失败：拿不到 AudioManager");
                return;
            }
            int stream = AudioManager.STREAM_MUSIC;
            SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
            int before = am.getStreamVolume(stream);
            if (mute && btVolRestoreTarget >= 0) {
                // 只有蓝牙下键带进来的（用户要求 v2.33 定稿）：
                // 恢复声音时音量 +1 回到按下前；摇一摇/语音的广告没有这个 override，原样恢复
                before = btVolRestoreTarget;
                KeepService.note("蓝牙下键：恢复声音时音量目标 = " + before + "（+1 还原到按下前）");
            }
            if (mute) {
                sp.edit().putInt("volBeforeMute", before)
                        .putBoolean("soundMuted", true).apply();
                am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0);
                String msg = "禁音：媒体音量 " + before + " → " + am.getStreamVolume(stream);
                if (am.getStreamVolume(stream) > 0) {
                    // 有的机型 MUTE 不动音量条：直接置 0
                    try {
                        am.setStreamVolume(stream, 0, 0);
                        msg += "；已直接置 0 → " + am.getStreamVolume(stream);
                    } catch (Throwable t2) {
                        msg += "；置 0 失败：" + t2.getClass().getSimpleName();
                    }
                }
                msg += "（说「声音」恢复）";
                KeepService.note(msg);
                postStatus("已禁音（说「声音」恢复）");
            } else {
                int vol = sp.getInt("volBeforeMute", -1);
                boolean wasMuted = sp.getBoolean("soundMuted", false);
                sp.edit().putBoolean("soundMuted", false).apply();
                if (wasMuted && vol > 0) {
                    try {
                        markVolSelf();
                        am.setStreamVolume(stream, vol, 0);
                    } catch (Throwable t2) {
                        am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0);
                    }
                    KeepService.note("恢复声音：媒体音量 → " + am.getStreamVolume(stream)
                            + "（禁音前是 " + vol + "）");
                    if (btVolRestoreTarget >= 0) {
                        btVolRestoreTarget = -1; // 蓝牙音量键的还原目标用完清掉
                    }
                } else {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0);
                    KeepService.note("恢复声音：媒体音量 → " + am.getStreamVolume(stream)
                            + "（之前没禁过音，只是解除静音）");
                }
                postStatus("已恢复声音");
            }
        } catch (Throwable e) {
            KeepService.note("禁音/恢复出错：" + e.getClass().getSimpleName()
                    + " " + e.getMessage());
        }
    }

    /** 亮/暗：屏幕亮度加减一点（要「修改系统设置」权限，主界面有授权按钮） */
    private static final int BRIGHT_STEP = 25;

    private void adjustBrightness(boolean brighter) {
        try {
            if (!Settings.System.canWrite(this)) {
                KeepService.note("亮/暗需要「修改系统设置」权限：请到主界面点 ☀ 那个授权按钮");
                toast("亮/暗需要先允许“修改系统设置”（主界面有 ☀ 按钮）");
                return;
            }
            int cur = Settings.System.getInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 128);
            int max = 255;
            try {
                max = (int) Class.forName("android.os.PowerManager")
                        .getField("BRIGHTNESS_ON").get(null);
            } catch (Throwable ignored) {
            }
            int next = Math.max(5, Math.min(max, cur + (brighter ? BRIGHT_STEP : -BRIGHT_STEP)));
            // 先关自动亮度，否则写进去马上被系统改回去
            try {
                Settings.System.putInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            } catch (Throwable ignored) {
            }
            Settings.System.putInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, next);
            int check = Settings.System.getInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, next);
            KeepService.note("屏幕亮度：" + cur + " → " + check
                    + (brighter ? "（亮一点）" : "（暗一点）")
                    + (check == cur ? "；没变（可能系统不允许写）" : ""));
        } catch (Exception e) {
            KeepService.note("调亮度出错：" + e.getClass().getSimpleName()
                    + " " + e.getMessage());
        }
    }

    /** 「打开豆包」/「关闭豆包」：勾上或去掉设置里那个「使用豆包语音输入法」 */
    private void switchDoubao(boolean on) {
        try {
            boolean already = useDoubao() == on;
            // 用 commit：马上落盘，回到主界面看勾一定是对的
            getSharedPreferences("cfg", MODE_PRIVATE).edit()
                    .putBoolean("useDoubao", on).commit();
            if (on) {
                KeepService.note(already
                        ? "✅ 设置里的「使用豆包语音输入法」本来就是勾上的"
                        : "✅ 已勾上设置里的「使用豆包语音输入法」");
            } else {
                KeepService.note(already
                        ? "✅ 设置里的「使用豆包语音输入法」本来就是没勾的"
                        : "✅ 已去掉设置里的「使用豆包语音输入法」");
            }
            toast(on ? "已勾上「使用豆包语音输入法」" : "已去掉「使用豆包语音输入法」");
            KeepService.applyVoiceSetting(this);
            refreshOverlayForMode();
        } catch (Throwable ignored) {
        }
    }

    /** 媒体音频现在是不是在响（在响 = 视频在播；暂停时一般不响）。
     *  单字命令（停/放/大/小/亮/暗）和摇一摇都用它把关：
     *  没在播的时候听到/摇到什么都不做。 */
    public boolean isAudioPlaying() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            return am != null && am.isMusicActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 屏幕正中那一块（20%~80% 高）的灰度指纹（现无调用方，保留备用） */
    @SuppressWarnings("unused")
    private int[] centerSignature(Bitmap thumb) {
        if (thumb == null || thumb.getWidth() <= 0 || thumb.getHeight() <= 0) {
            return null;
        }
        try {
            int w = 64;
            int h = Math.max(8,
                    (int) ((long) thumb.getHeight() * w / Math.max(1, thumb.getWidth())));
            Bitmap small = Bitmap.createScaledBitmap(thumb, w, h, true);
            int top = (int) (h * 0.20f);
            int sh = Math.max(1, (int) (h * 0.80f) - top);
            int[] sig = new int[w * sh];
            int i = 0;
            for (int y = top; y < top + sh && y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = small.getPixel(x, y);
                    sig[i++] = ((p >> 16 & 0xFF) + (p >> 8 & 0xFF) + (p & 0xFF)) / 3;
                }
            }
            small.recycle();
            return sig;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 直接执行命令（用户定稿：唤醒时什么都不做、没暂停过任何东西，
     *  不需要「先点一下恢复播放」；(50%,20%) 只在「停/播放」命令里） */
    private void runAfterResume(final Runnable action) {
        action.run();
    }

    /** 声音大一点/小一点：直接调媒体音量（不需要无障碍），并把结果写进通知栏 */
    private void adjustMediaVolume(boolean up) {
        markVolSelf(); // 程序自己调的：轮询跳过这次变化（否则短剧里语音 大/小 会再触发一次下集/上拉）
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                KeepService.note("调音量失败：拿不到 AudioManager");
                return;
            }
            int stream = AudioManager.STREAM_MUSIC;
            int before = am.getStreamVolume(stream);
            int max = am.getStreamMaxVolume(stream);
            int min = 0;
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    min = am.getStreamMinVolume(stream);
                } catch (Throwable ignored) {
                }
            }
            am.adjustStreamVolume(stream,
                    up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI);
            int after = am.getStreamVolume(stream);
            String msg = "媒体音量：" + before + " → " + after + "（范围 " + min + "~" + max + "）";
            if (before == after) {
                int target = up ? Math.min(max, before + 1) : Math.max(min, before - 1);
                if (target == before) {
                    msg += up ? "，已经最大了，调不动" : "，已经最小了，调不动";
                } else {
                    try {
                        am.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI);
                        msg += "，直接置位到 " + target + " 后 = " + am.getStreamVolume(stream);
                    } catch (Throwable t2) {
                        msg += "，直接置位失败：" + t2.getClass().getSimpleName()
                                + " " + t2.getMessage();
                    }
                }
            }
            KeepService.note(msg);
        } catch (Throwable e) {
            KeepService.note("调音量出错：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private String extractAppName(String text) {
        String t = Commands.normalize(text);
        String[] prefixes = {"打开", "启动"};
        for (String p : prefixes) {
            int i = t.indexOf(p);
            if (i >= 0) {
                String name = t.substring(i + p.length());
                if (name.length() > 0) {
                    return name;
                }
            }
        }
        // 用户要求：直接喊 App 名字也当“打开它”（"红果" → 打开红果）
        if (Apps.isAppName(t)) {
            return Apps.canonical(t);
        }
        return "";
    }

    public String openAppByName(String name) {
        try {
            PackageManager pm = getPackageManager();
            // 别名/同音字归一：「打开ds」/「打开地艾斯」→ ds；「打开豆包应用」→ 豆包
            String want = Apps.canonical(name);
            if (want.length() == 0) {
                return "没听清要打开哪个应用";
            }
            // 0) 先试写死的包名（「ds」「豆包应用」这种按 App 名字找不到的）
            for (String pkg : Apps.packages(want)) {
                try {
                    Intent pi = pm.getLaunchIntentForPackage(pkg);
                    if (pi != null) {
                        pi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                        startActivity(pi);
                        return null;
                    }
                } catch (Exception ignored) {
                }
            }
            Intent main = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
            if (list == null || list.isEmpty()) {
                return "看不到任何已安装应用";
            }

            String low = want.replace(" ", "").toLowerCase();
            ResolveInfo exact = null;
            ResolveInfo contains = null;

            for (ResolveInfo ri : list) {
                String label;
                try {
                    label = ri.loadLabel(pm).toString().replace(" ", "");
                } catch (Exception e) {
                    continue;
                }
                if (label.length() == 0) {
                    continue;
                }
                String ll = label.toLowerCase();
                if (ll.equals(low)) {
                    exact = ri;
                    break;
                }
                if (contains == null && (ll.contains(low) || low.contains(ll))) {
                    contains = ri;
                }
            }

            ResolveInfo target = exact != null ? exact : contains;
            if (target == null) {
                return "没找到「" + want + "」（共可见 " + list.size() + " 个应用）";
            }

            Intent i = pm.getLaunchIntentForPackage(target.activityInfo.packageName);
            if (i == null) {
                return "「" + want + "」没有启动入口";
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            return null;
        } catch (Exception e) {
            return "启动失败：" + e.getClass().getSimpleName();
        }
    }

    // ==================== 退出 ====================

    /** 收到「关闭小助手」：把一切收干净 */
    public void shutdownAll() {
        try {
            stopVolPoller();
            getSharedPreferences("cfg", MODE_PRIVATE).edit()
                    .putBoolean("enabled", false)
                    .putBoolean("shortDrama", false)
                    .putBoolean("reading", false).apply(); // 退出自动关短剧/阅读模式
            // 用户要求 v2.33：退出后编辑框绝不能出现——
            // manualBoxOpen 不复位的话，看门狗 ensureOverlayAlive 会把框又补回屏幕
            manualBoxOpen = false;
            KeepService.note("已退出：短剧模式也自动关闭了");
            skipWatching = false;
            swipeChainActive = false;
            phase = PHASE_IDLE;
            removeBox();
            removePickerMic();
            removeRecLayer();
            removeNamingLayer();
            removeManualBox();
            removeStatusStripNow();
            hideProbePanel();
            handler.removeCallbacksAndMessages(null);
            if (KeepService.instance() != null) {
                // 用户反馈：服务整个停掉后，有的手机会把无障碍开关也一起关。
                // 改成只暂停采音、通知留一条「已暂停」，进程和无障碍都活着。
                KeepService.pauseForExit(this);
            }
            toast("小助手已关闭（打开 App 恢复；无障碍开关保持不动）");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 执行命令后的提示。跳过广告 / 退出直播 / 测试读秒 不弹 Toast：
     * Android 的 Toast 出现在屏幕最下方，正好盖住读秒那一行，
     * 会干扰「下方像素比对」的判断。
     */
    private void toastResult(int cmd, String s) {
        if (cmd == Commands.CMD_SKIP_AD || cmd == Commands.CMD_EXIT_LIVE) {
            // 不弹 Toast（它在屏幕最下方，盖住读秒那一行）——
            // 改用屏幕下方 79% 处的状态条，既不挡读秒又能看到
            showStatusStrip(s);
            return;
        }
        toast(s);
    }

    private void toast(String s) {
        try {
            android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }
}
