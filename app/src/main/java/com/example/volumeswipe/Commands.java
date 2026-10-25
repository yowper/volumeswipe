package com.example.volumeswipe;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 语音命令的定义与关键词匹配。
 */
public final class Commands {

    public static final int CMD_NONE = 0;
    public static final int CMD_SKIP_AD = 1;    // 跳过广告：等 N 秒 → 上滑
    public static final int CMD_NEXT = 2;       // 下一集 / 上翻：直接上滑
    public static final int CMD_PREV = 3;       // 上一集 / 下翻：直接下滑
    public static final int CMD_PAGE_DOWN = 4;  // 下页：点 (85%, 85%)
    public static final int CMD_PAGE_UP = 5;    // 上页：点 (15%, 85%)
    public static final int CMD_RESUME = 6;     // 旧号（已并入 CMD_TOGGLE_PLAY，保留兼容）
    public static final int CMD_OPEN = 7;       // 打开某 App
    public static final int CMD_EXIT = 8;       // 小白再见：彻底退出
    public static final int CMD_VOL_UP = 9;     // 声音大一点 / 大：媒体音量 +
    public static final int CMD_VOL_DOWN = 10;  // 声音小一点 / 小：媒体音量 -
    public static final int CMD_EXIT_LIVE = 11; // 退出直播：先左划，再上拉
    public static final int CMD_DOUBAO_ON = 12;  // 打开豆包：勾上「使用豆包语音输入法」
    public static final int CMD_DOUBAO_OFF = 13; // 关闭豆包：去掉那个勾
    public static final int CMD_TEST_SECONDS = 14; // 测试读秒：探测下方到底能不能读到读秒文字
    public static final int CMD_TOGGLE_PLAY = 15;  // 停 / 放 / 继续 / 播放 / 继续播放：点 (50%,20%)
    public static final int CMD_BRIGHT_UP = 16;    // 亮：屏幕亮一点
    public static final int CMD_BRIGHT_DOWN = 17;  // 暗：屏幕暗一点
    public static final int CMD_DOUBAO_TOGGLE = 18; // 「豆包」：勾着就关、没勾就开
    public static final int CMD_RECORDED = 19;      // 自己录制的命令（名字→一串点/划动作）
    public static final int CMD_DRAMA_TOGGLE = 20;  // 「短剧」：像「豆包」一样，说一声切换短剧模式
    public static final int CMD_RECORD_TOGGLE = 21; // 「录制」：开始录制动作（录制中再说=结束并弹框起名）
    public static final int CMD_SOUND_OFF = 22;     // 「禁音」：关掉声音（接电话前用）
    public static final int CMD_SOUND_ON = 23;      // 「声音」：恢复刚才的音量
    public static final int CMD_READING_TOGGLE = 24; // 「阅读」：开关阅读模式
    public static final int CMD_MANUAL = 25;         // 「手动」：弹出命令输入框

    private static final String[] KW_EXIT = {
            "小白再见", "再见小白", "小白拜拜"
    };
    /** 「大」在短句里也算（用户要求：声音大一点 → 大） */
    private static final String[] KW_VOL_UP = {
            "声音大", "音量大", "大点声", "大声点", "调大", "大一点", "大点"
    };
    /** 「小」在短句里也算（用户要求：声音小一点 → 小） */
    private static final String[] KW_VOL_DOWN = {
            "声音小", "音量小", "小点声", "小声点", "调小", "小一点", "小点"
    };
    /** 亮：屏幕亮度 +（需要「修改系统设置」权限）
     *  注意：单字「亮」不在这个表里 —— 单字只认【整句就这一个字】（见 matchOne） */
    private static final String[] KW_BRIGHT_UP = {
            "亮一点", "亮点", "亮些", "调亮", "屏幕亮", "变亮", "亮起来"
    };
    /** 暗：屏幕亮度 - */
    private static final String[] KW_BRIGHT_DOWN = {
            "暗一点", "暗点", "暗些", "调暗", "屏幕暗", "变暗", "暗下来"
    };
    private static final String[] KW_EXIT_LIVE = {
            // 用户要求简单化：只说「直播」也算退出直播
            "退出直播", "推出直播", "退出直拨", "直播"
    };
    private static final String[] KW_PREV = {
            "上一集", "上一部", "上一步", "下翻", "向下翻",
            // 同音字容错：级/季/期/节 都读作“ji”
            "上一级", "上一季", "上一期", "上一节", "上集"
    };
    private static final String[] KW_NEXT = {
            "下一集", "下一部", "换一集", "上翻", "向上翻",
            "下一级", "下一季", "下一期", "下一节", "下集"
    };
    private static final String[] KW_PAGE_UP = {
            "上页", "翻上页", "上一页"
    };
    private static final String[] KW_PAGE_DOWN = {
            "下页", "翻下页", "下一页"
    };
    private static final String[] KW_SKIP_AD = {
            // 用户要求简单化：只说「广告」也算退出广告。
            // （「广告」在视频里偶尔会误触发，但用户希望命令越短越好）
            "退出广告", "跳过广告", "推出广告", "退出广告片", "退出光告",
            "关掉广告", "跳过这段广告", "广告"
    };
    /** 停 / 继续 / 播放 / 暂停：用户要求合成同一条命令 = 点 (50%,20%)
     *  （用户要求把单字「放」去掉：太容易在视频台词里被触发（“放假/放下”等），
     *    “放着/放一下/继续放/接着放”这些多字的保留） */
    private static final String[] KW_TOGGLE_PLAY = {
            "暂停", "停下", "停一下", "放着", "放一下",
            "恢复播放", "继续播放", "播放", "继续看", "接着看", "接着播", "继续播",
            "开始播放", "接着放", "继续", "继续放"
    };
    private static final String[] KW_RESUME = {
            "恢复播放", "继续播放", "播放", "继续看", "接着看", "接着播", "继续播",
            "开始播放", "接着放", "继续"
    };
    private static final String[] KW_DOUBAO_ON = {
            "打开豆包", "开启豆包", "启用豆包", "用豆包", "豆包打开", "豆包开启"
    };
    private static final String[] KW_DOUBAO_OFF = {
            "关闭豆包", "关掉豆包", "关豆包", "不用豆包", "豆包关闭", "退出豆包", "去掉豆包"
    };
    private static final String[] KW_TEST_SECONDS = {
            "测试读秒", "读秒测试", "读秒探测", "探测读秒", "测试下方"
    };
    private static final String[] KW_DRAMA = {
            "短剧", "短剧模式"
    };
    private static final String[] KW_RECORD = {
            "录制", "开始录制", "录制动作", "录制命令"
    };
    /** 阅读模式开关：单独说「阅读」= 开/关；含「打开」的句子不当开关（「打开阅读」仍是开 App） */
    private static final String[] KW_READING = {
            "阅读", "阅读模式", "月读"
    };
    /** 手动模式：弹出可输入的命令框（语音认不出/来不及说的就在这里输） */
    private static final String[] KW_MANUAL = {
            "手动", "手动模式"
    };
    /** 禁音：关掉声音（接电话前用）。放在 音量大/小 之后判，避免抢走「声音大」 */
    private static final String[] KW_SOUND_OFF = {
            "禁音", "静音", "关声音", "关闭声音", "没声音", "声音关掉"
    };
    /** 恢复声音：整句只说「声音」也算（说「声音大/小」不会走到这里，前面先判了） */
    private static final String[] KW_SOUND_ON = {
            "声音", "恢复声音", "打开声音", "开声音", "要声音", "有声音"
    };

    private Commands() {
    }

    /** 本地引擎命令模式的词表：所有命令关键词 + 打开/启动（不含唤醒词，那部分由 WakeEngine 拼） */
    public static String[] COMMAND_VOCAB() {
        Set<String> set = new LinkedHashSet<>();
        String[][] all = {
                KW_EXIT, KW_PAGE_UP, KW_PAGE_DOWN, KW_SKIP_AD, KW_EXIT_LIVE,
                KW_PREV, KW_NEXT, KW_VOL_UP, KW_VOL_DOWN,
                KW_BRIGHT_UP, KW_BRIGHT_DOWN,
                KW_TOGGLE_PLAY, KW_DRAMA,
                KW_RECORD, KW_SOUND_OFF, KW_SOUND_ON, KW_READING, KW_MANUAL
        };
        for (String[] arr : all) {
            for (String k : arr) {
                set.add(k);
            }
        }
        set.add("打开");
        set.add("启动");
        // 「打开抖音」「打开红果」… 这些 App 名字必须提前写进词表，
        // 否则 Vosk 压根听不懂这几个名字
        for (String s : Apps.OPEN_PHRASES) {
            set.add(s);
        }
        for (String s : Apps.BARE_NAMES) {
            set.add(s);
        }
        return set.toArray(new String[0]);
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        // 只保留汉字、字母、数字：标点、空格、引号、emoji 全部丢弃
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 0x4E00 && ch <= 0x9FFF) {
                sb.append(ch);
            } else if (ch >= 'a' && ch <= 'z') {
                sb.append(ch);
            } else if (ch >= 'A' && ch <= 'Z') {
                sb.append(ch);
            } else if (ch >= '0' && ch <= '9') {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** 返回命中的命令，CMD_NONE 表示没命中 */
    public static int match(String text) {
        String t = normalize(text);
        if (t.length() == 0) {
            return CMD_NONE;
        }

        // 退出指令放最前面，优先级最高
        if (hit(t, KW_EXIT)) {
            return CMD_EXIT;
        }

        // 用户要求（v2.30）：去掉「嗨小白」唤醒、去掉豆包输入法勾选的语音开关 ——
        // 唤醒词不再拦截命令；豆包相关的语音命令整个删掉（豆包 IME 只留给文本命令框点 Mic 用）。
        // 5) 「短剧」= 切换短剧模式：没开就进入，开着就退出
        //   （用户要求：像「豆包」一样用语音切换，不用勾选框，也不用「嗨小白」退出）
        if (t.contains("短剧")) {
            return CMD_DRAMA_TOGGLE;
        }
        // 6) 「阅读」= 开关阅读模式（v2.25）：开 = 自动开阅读软件+点一次(50%,20%)，
        //    模式里只听 上页/下页/蓝牙音量键；再说一次「阅读」= 关
        //    含「打开/启动」的句子不当开关（「打开阅读」仍是打开 App）
        if ((t.contains("阅读") || t.contains("月读"))
                && !t.contains("打开") && !t.contains("启动")) {
            return CMD_READING_TOGGLE;
        }
        // 7) 「手动」= 弹出命令输入框（黑底白字，输入后点「执行」才执行，✕ 关闭）
        if (t.contains("手动")) {
            return CMD_MANUAL;
        }
        // 6) 「录制」= 开始录制动作（录制中再说一次 = 结束并弹框起名）
        if (t.contains("录制")) {
            return CMD_RECORD_TOGGLE;
        }
        // 先判“页”，避免被“上一/下一”抢走
        if (hit(t, KW_PAGE_UP)) {
            return CMD_PAGE_UP;
        }
        if (hit(t, KW_PAGE_DOWN)) {
            return CMD_PAGE_DOWN;
        }
        if (hit(t, KW_EXIT_LIVE)) {
            return CMD_EXIT_LIVE;
        }
        if (hit(t, KW_SKIP_AD)) {
            return CMD_SKIP_AD;
        }
        if (hit(t, KW_PREV)) {
            return CMD_PREV;
        }
        if (hit(t, KW_NEXT)) {
            return CMD_NEXT;
        }
        // 兜底（限定到 ≤ 3 字，只认「下/上 + 集/级/季/期」这种识别抖动）：
        // 例如「下几集」「下一几」→ 下一集。其它一律不算命令。
        if (t.length() <= 3 && t.contains("下")
                && (t.contains("集") || t.contains("级") || t.contains("季") || t.contains("期"))) {
            return CMD_NEXT;
        }
        if (t.length() <= 3 && t.contains("上")
                && (t.contains("集") || t.contains("级") || t.contains("季") || t.contains("期"))) {
            return CMD_PREV;
        }
        // 亮度：用户规定就是这两个单字（「亮」「暗」）加上表里的说法；
        // 单字只认【整句就是这一个字】，表里也不用「≤ 4 字里含亮/暗」这种宽松兑底。
        if (hit(t, KW_BRIGHT_UP) || matchOne(t) == CMD_BRIGHT_UP) {
            return CMD_BRIGHT_UP;
        }
        if (hit(t, KW_BRIGHT_DOWN) || matchOne(t) == CMD_BRIGHT_DOWN) {
            return CMD_BRIGHT_DOWN;
        }
        // 音量：用户规定就是「大」「小」这两个单字，加上表里的说法
        if (hit(t, KW_VOL_UP) || matchOne(t) == CMD_VOL_UP) {
            return CMD_VOL_UP;
        }
        if (hit(t, KW_VOL_DOWN) || matchOne(t) == CMD_VOL_DOWN) {
            return CMD_VOL_DOWN;
        }
        // 禁音 / 恢复声音（用户要求：接电话时要把音乐关掉）。
        // 必须在 声量大/小 之后判，否则「声音」会把「声音大」抢走；
        // 也要在 打开/启动 兑底之前，否则「打开声音」会被当成开 App
        if (hit(t, KW_SOUND_OFF)) {
            return CMD_SOUND_OFF;
        }
        if (hit(t, KW_SOUND_ON)) {
            return CMD_SOUND_ON;
        }
        // 停 / 继续 / 播放 / 继续播放：用户要求合成【同一条命令】= (50%,20%) 点一下
        if (hit(t, KW_TOGGLE_PLAY) || matchOne(t) == CMD_TOGGLE_PLAY) {
            return CMD_TOGGLE_PLAY;
        }
        // 用户要求：直接喊 App 名字就打开（「红果」=打开红果，「抖音」=打开抖音）
        if (Apps.isAppName(t)) {
            return CMD_OPEN;
        }
        if (t.contains("打开") || t.contains("启动")) {
            return CMD_OPEN;
        }


        return CMD_NONE;
    }

    private static boolean hit(String text, String[] keys) {
        for (String k : keys) {
            // 单字不许用 contains 匹配：视频台词里带这个字就误触发
            // （例如“停下来看看”里的“停”）。单字只走 matchOne。
            if (k == null || k.length() <= 1) {
                continue;
            }
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单字命令：只有【整句就是这个字】才算命令。
     * 用户规定：大 = 声音大一点、小 = 声音小一点、亮/暗 = 亮度、停 = 暂停/继续。
     */
    private static int matchOne(String t) {
        if (t == null || t.length() != 1) {
            return CMD_NONE;
        }
        switch (t.charAt(0)) {
            case '\u5927':   // 大
                return CMD_VOL_UP;
            case '\u5c0f':   // 小
                return CMD_VOL_DOWN;
            case '\u4eae':   // 亮
                return CMD_BRIGHT_UP;
            case '\u6697':   // 暗
                return CMD_BRIGHT_DOWN;
            case '\u505c':   // 停
                return CMD_TOGGLE_PLAY;
            default:
                return CMD_NONE;
        }
    }

    /**
     * 命令的正式名称（就是用户规定的那份命令表里的名字）。
     * 屏幕下方的提示 / 日志都用它：一眼能看出这句话对上了哪条规定的命令。
     */
    public static String nameOf(int cmd) {
        switch (cmd) {
            case CMD_SKIP_AD:
                return "退出广告";
            case CMD_NEXT:
                return "下一集（上滑）";
            case CMD_PREV:
                return "上一集（下滑）";
            case CMD_PAGE_DOWN:
                return "下一页";
            case CMD_PAGE_UP:
                return "上一页";
            case CMD_RESUME:
            case CMD_TOGGLE_PLAY:
                return "停 / 继续播放（点 50%,20%）";
            case CMD_OPEN:
                return "打开 App";
            case CMD_EXIT:
                return "关闭小助手";
            case CMD_VOL_UP:
                return "声音大一点";
            case CMD_VOL_DOWN:
                return "声音小一点";
            case CMD_EXIT_LIVE:
                return "退出直播";
            case CMD_DOUBAO_ON:
                return "打开豆包（勾上）";
            case CMD_DOUBAO_OFF:
                return "关闭豆包（去勾）";
            case CMD_DOUBAO_TOGGLE:
                return "切换豆包";
            case CMD_READING_TOGGLE:
                return "阅读模式开关";
            case CMD_MANUAL:
                return "手动模式（弹命令输入框）";
            case CMD_TEST_SECONDS:
                return "测试读秒";
            case CMD_BRIGHT_UP:
                return "屏幕亮一点";
            case CMD_BRIGHT_DOWN:
                return "屏幕暗一点";
            case CMD_RECORDED:
                return "自己录制的动作";
            case CMD_DRAMA_TOGGLE:
                return "短剧模式（开/关）";
            case CMD_RECORD_TOGGLE:
                return "录制动作（开始/结束）";
            case CMD_SOUND_OFF:
                return "禁音（关声音）";
            case CMD_SOUND_ON:
                return "恢复声音";
            default:
                return "";
        }
    }

    /**
     * 是不是【规定的命令表】里的命令。
     * 用户要求：不在表里的一律不执行，也不在屏幕下方显示。
     */
    public static boolean isCommand(String text) {
        return match(text) != CMD_NONE;
    }
}
