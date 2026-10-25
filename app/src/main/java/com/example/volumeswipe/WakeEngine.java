package com.example.volumeswipe;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 唤醒词识别引擎（Vosk 离线）。
 *
 * 职责很单一：后台一直听，只判断有没有说唤醒词（比如“小助手”）。
 * 识别到就回调 onWake，由上层弹出输入框，真正的命令交给输入法识别。
 *
 * 用限定词表模式，只在这几个唤醒词里选，速度和抗噪都会好很多。
 */
public class WakeEngine {

    public interface Listener {
        void onReady();

        void onStatus(String msg);

        void onWake(String text);

        /** 每次识别出来的话（不只是在词表里的）：只用来记日志——“听到了什么声音” */
        void onHeard(String text);

        void onLevel(int level);

        void onError(String msg);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final String ASSET_DIR = "model-cn";

    // v2.37：唤醒词「嗨小白/小白」已整个去掉（用户要求：不再需要）。
    // 默认词表留空：真正的词表由 idleVocab()/commandVocab() 生成后 setVocab 进来。
    private static final String[] DEFAULT_VOCAB = new String[0];

    private final Context ctx;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile boolean running;
    private Thread worker;
    private AudioRecord record;
    private Model model;
    private Recognizer recognizer;

    private android.media.audiofx.NoiseSuppressor ns;
    private android.media.audiofx.AutomaticGainControl agc;

    private long lastWake = 0;
    /** 上一次「麦克风拿不到」上报时间：避免刷屏 */
    private long lastOpenFailNote = 0;

    /** 当前生效的词表 */
    private volatile String[] vocab;
    /** 待切换的词表，识别线程在下一块音频前应用 */
    private volatile String[] pendingVocab;
    /** 词表切换时刻，切换后 300ms 内的残句直接丢弃 */
    private volatile long swapAt = 0;
    /** true = 暂停采音（麦克风让给豆包输入法），模型留在内存 */
    private volatile boolean capturePaused = false;

    public WakeEngine(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "wake-engine");
        worker.start();
    }

    public void stop() {
        running = false;
        AudioRecord r = record;
        if (r != null) {
            try {
                r.stop();
            } catch (Exception ignored) {
            }
        }
        if (worker != null) {
            try {
                worker.join(600);
            } catch (Exception ignored) {
            }
            worker = null;
        }
    }

    private void loop() {
        try {
            postStatus("唤醒词引擎：准备模型…");
            File dir = ensureModelUnpacked();

            postStatus("唤醒词引擎：加载模型…");
            model = new Model(dir.getAbsolutePath());
            vocab = pendingVocab != null ? pendingVocab : DEFAULT_VOCAB;
            pendingVocab = null;
            try {
                recognizer = buildRecognizer(model, vocab);
            } catch (Throwable t) {
                postError("唤醒词引擎：识别器创建失败");
                return;
            }
            if (!openRecord()) {
                postError("唤醒词引擎：麦克风初始化失败");
                return;
            }
            postReady();

            byte[] buffer = new byte[3200];
            while (running) {
                applyPendingVocab();
                if (capturePaused) {
                    // 采音已暂停（麦克风让给豆包），线程空转等恢复
                    sleepQuietly(80);
                    continue;
                }
                if (record == null) {
                    // 麦克风句柄丢了（多半是让给豆包输入法后被系统收走）。
                    // 以前这里直接空转：一次拿不回来就永远变聋（唤醒词再也听不到）
                    // 现象就是「豆包关/开来回几次后，Mic 再也没人点」。现在自己重试重建。
                    if (!openRecord()) {
                        long now = System.currentTimeMillis();
                        if (now - lastOpenFailNote > 5000) {
                            lastOpenFailNote = now;
                            postStatus("麦克风暂时拿不到，正在重试…");
                        }
                        sleepQuietly(300);
                        continue;
                    }
                    postStatus("麦克风已恢复");
                }
                int n = record.read(buffer, 0, buffer.length);
                if (n <= 0) {
                    sleepQuietly(20);
                    continue;
                }

                postLevel(calcLevel(buffer, n));

                if (recognizer.acceptWaveForm(buffer, n)) {
                    check(extractText(recognizer.getResult()));
                } else {
                    check(extractText(recognizer.getPartialResult()));
                }
            }
        } catch (Throwable e) {
            postError("唤醒词引擎失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            release();
        }
    }

    /** 运行中切换词表：模型不重载，只换解码器，下一块音频生效 */
    public void setVocab(String[] words) {
        if (words == null || words.length == 0) {
            return;
        }
        if (!running || recognizer == null) {
            vocab = words;
            return;
        }
        pendingVocab = words;
    }

    /** 待命词表：【全部命令词】（v2.37 起没有唤醒词了）。
     *  本地直接认命令，能对上就直接执行，
     *  另外加上几个豆包口语说法和「打开某 App」的词。 */
    public static String[] idleVocab() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        // 整个命令表（含豆包开关、App 名字）
        for (String k : Commands.COMMAND_VOCAB()) {
            set.add(k);
        }
        // 豆包开关的常见口语说法
        set.add("豆包");
        set.add("关豆包");
        set.add("打开豆包");
        set.add("关闭豆包");
        set.add("不用豆包");
        // 直接喊 App 名字（"红果" → 打开红果）
        for (String b : Apps.BARE_NAMES) {
            set.add(b);
        }
        // 用户自己录制的语音命令（录制模式）：名字必须提前进词表，
        // 不然 Vosk 听不懂这个自定义名字
        for (String r : RecordedCmds.names()) {
            set.add(r);
        }
        return set.toArray(new String[0]);
    }

    /** 命令模式词表：所有命令关键词（v2.37 起没有唤醒词了） */
    public static String[] commandVocab() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String k : Commands.COMMAND_VOCAB()) {
            set.add(k);
        }
        // 自己录制的语音命令（录制模式）
        for (String r : RecordedCmds.names()) {
            set.add(r);
        }
        return set.toArray(new String[0]);
    }

    /** 暂停采音并释放麦克风（模型保留在内存，恢复时不用重新加载） */
    public void pauseCapture() {
        capturePaused = true;
        AudioRecord r = record;
        record = null;
        if (r != null) {
            try {
                r.stop();
            } catch (Exception ignored) {
            }
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** 恢复采音（重新建 AudioRecord，很便宜） */
    public void resumeCapture() {
        if (!running || !capturePaused) {
            return;
        }
        capturePaused = false;
        // 这里建不上也没关系：识别线程会自己重试，不会变聋
        openRecord();
    }

    /** 建 AudioRecord（幂等）。拿不到麦克风返回 false，交给调用方重试 */
    private boolean openRecord() {
        if (record != null) {
            return true;
        }
        if (model == null) {
            return false;
        }
        try {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf * 2, 8192);
            AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                try {
                    r.release();
                } catch (Exception ignored) {
                }
                return false;
            }
            enableEffects(r.getAudioSessionId());
            r.startRecording();
            record = r;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isCapturePaused() {
        return capturePaused;
    }

    /** 麦克风句柄是否在手（false = 现在听不到任何声音） */
    public boolean isMicOpen() {
        return record != null && !capturePaused;
    }

    /** 引擎是否活着（线程在跑且模型已加载） */
    public boolean isAlive() {
        return running && model != null;
    }

    private void applyPendingVocab() {
        String[] p = pendingVocab;
        if (p == null || model == null) {
            return;
        }
        pendingVocab = null;
        try {
            Recognizer old = recognizer;
            recognizer = null;
            if (old != null) {
                old.close();
            }
            try {
                recognizer = buildRecognizer(model, p);
            } catch (Throwable ignored) {
            }
            vocab = p;
            swapAt = System.currentTimeMillis();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 建识别器：先试整张词表（识别最准），失败再去掉带字母/数字的词重试，
     * 最后还是不行就退回到不带词表的自由识别。
     * 这样「打开ds」这种带字母的词不会把整张词表弄挂。
     */
    private static Recognizer buildRecognizer(Model model, String[] words)
            throws java.io.IOException {
        try {
            return new Recognizer(model, SAMPLE_RATE, grammarOf(words));
        } catch (Throwable ignored) {
        }
        try {
            return new Recognizer(model, SAMPLE_RATE, grammarOf(cjkOnly(words)));
        } catch (Throwable ignored) {
        }
        return new Recognizer(model, SAMPLE_RATE);
    }

    /** 只留纯汉字词（Vosk 模型词表里可能没有拉丁字母） */
    private static String[] cjkOnly(String[] words) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String w : words) {
            if (w == null || w.length() == 0) {
                continue;
            }
            boolean ok = true;
            for (int i = 0; i < w.length(); i++) {
                char c = w.charAt(i);
                if (c < 0x4E00 || c > 0x9FFF) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                out.add(w);
            }
        }
        return out.toArray(new String[0]);
    }

    private static String grammarOf(String[] words) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(words[i]).append("\"");
        }
        sb.append(",\"[unk]\"]");
        return sb.toString();
    }

    private void check(String text) {
        if (text == null || text.length() == 0) {
            return;
        }
        String t = Commands.normalize(text);
        if (t.length() == 0) {
            return;
        }
        if (System.currentTimeMillis() - swapAt < 300) {
            return; // 刚换完词表，残句不可信
        }
        // 用户要求：日志里要记录【听到了什么声音】，不只是记录执行了什么。
        // 这里把每次识别出来的原话上报（不分是不是命令），由上层记一笔。
        postHeard(t);
        String[] v = vocab == null ? DEFAULT_VOCAB : vocab;
        for (String w : v) {
            if (t.contains(w)) {
                long now = System.currentTimeMillis();
                if (now - lastWake < 1200) {
                    return;
                }
                lastWake = now;
                final String hit = t;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onWake(hit);
                    }
                });
                return;
            }
        }
    }

    /** 最近一次上报过的“听到的话”，用来去重（同一句话会被反复识别出来） */
    private volatile String lastHeardText = "";
    private volatile long lastHeardAt = 0;

    /** 上报“听到的原话”（节流 + 去重，不然一个词会被报好几遍） */
    private void postHeard(final String t) {
        long now = System.currentTimeMillis();
        if (t.equals(lastHeardText) && now - lastHeardAt < 2000) {
            return;
        }
        lastHeardText = t;
        lastHeardAt = now;
        handler.post(new Runnable() {
            @Override
            public void run() {
                listener.onHeard(t);
            }
        });
    }

    private String extractText(String json) {
        if (json == null) {
            return "";
        }
        try {
            JSONObject o = new JSONObject(json);
            String t = o.optString("text", "");
            return t == null ? "" : t.replace(" ", "");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 音量指示 0~100。
     * 注意这不是 dBFS：说话时的峰值通常只有满量程的 5%~20%，
     * 如果按 32768 满量程算，指针几乎不动。这里按 8000 作为 100 的量程，
     * 让指针对说话更敏感（只用于人眼判断"有没有采到音"）。
     */
    private int calcLevel(byte[] buf, int len) {
        long sum = 0;
        int peak = 0;
        int count = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            int v = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
            if (v < 0) {
                v = -v;
            }
            if (v > peak) {
                peak = v;
            }
            sum += (long) v * v;
            count++;
        }
        if (count == 0) {
            return 0;
        }
        int rms = (int) Math.sqrt((double) sum / count);
        // 同时参考峰值和均方根，取较大者，避免说话瞬间跳不出来
        int v = Math.max(peak, rms * 2);
        int level = v * 100 / 8000;
        return level > 100 ? 100 : level;
    }

    private void enableEffects(int sessionId) {
        try {
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                ns = android.media.audiofx.NoiseSuppressor.create(sessionId);
                if (ns != null) {
                    ns.setEnabled(true);
                }
            }
        } catch (Throwable ignored) {
            ns = null;
        }
        try {
            if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                agc = android.media.audiofx.AutomaticGainControl.create(sessionId);
                if (agc != null) {
                    agc.setEnabled(true);
                }
            }
        } catch (Throwable ignored) {
            agc = null;
        }
    }

    private void release() {
        try {
            if (ns != null) {
                ns.release();
            }
        } catch (Exception ignored) {
        }
        ns = null;
        try {
            if (agc != null) {
                agc.release();
            }
        } catch (Exception ignored) {
        }
        agc = null;
        try {
            if (record != null) {
                record.stop();
                record.release();
            }
        } catch (Exception ignored) {
        }
        record = null;
        try {
            if (recognizer != null) {
                recognizer.close();
            }
        } catch (Exception ignored) {
        }
        recognizer = null;
        try {
            if (model != null) {
                model.close();
            }
        } catch (Exception ignored) {
        }
        model = null;
    }

    private File ensureModelUnpacked() throws IOException {
        File dir = new File(ctx.getFilesDir(), "vosk-cn");
        File marker = new File(dir, "am/final.mdl");
        if (marker.exists() && marker.length() > 1000) {
            return dir;
        }
        dir.mkdirs();
        copyAsset(ASSET_DIR, dir);
        return dir;
    }

    private void copyAsset(String assetPath, File target) throws IOException {
        String[] list = ctx.getAssets().list(assetPath);
        if (list != null && list.length > 0) {
            target.mkdirs();
            for (String name : list) {
                copyAsset(assetPath + "/" + name, new File(target, name));
            }
            return;
        }
        File parent = target.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        InputStream in = ctx.getAssets().open(assetPath);
        FileOutputStream out = new FileOutputStream(target);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            try {
                out.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Exception ignored) {
        }
    }

    private void postReady() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                listener.onReady();
            }
        });
    }

    private void postStatus(final String msg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                listener.onStatus(msg);
            }
        });
    }

    private void postError(final String msg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                listener.onError(msg);
            }
        });
    }

    private void postLevel(final int level) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                listener.onLevel(level);
            }
        });
    }
}
