package com.example.volumeswipe;

import java.util.ArrayList;
import java.util.List;

/**
 * 「打开X」要启动的应用名单。
 *
 * 这些短语会进本地语音识别的词表（Commands.COMMAND_VOCAB），
 * 因为 Vosk 只认得词表里提前写好的词；没写的（红果、天鹅、ds…）它听不出来。
 */
public final class Apps {

    /** 说这些话 → 打开对应 App */
    public static final String[] OPEN_PHRASES = {
            "打开抖音", "打开抖音极速版", "打开红果", "打开证券",
            "打开ds", "打开deepseek", "打开深度求索", "打开天鹅", "打开阅读",
            "打开豆包应用", "打开快手", "打开微信", "打开b站", "打开哔哩哔哩"
    };

    /** 词表里再放几个裸名字：Vosk 断成一截也能命中「打开」之后的词 */
    public static final String[] BARE_NAMES = {
            "抖音", "红果", "证券", "天鹅", "阅读"
    };

    /** 别名 / 同音错字 → 真正用来找 App 的名字（每行第一个是标准名） */
    private static final String[][] ALIAS = {
            {"ds", "deepseek", "deapseek", "deepseak", "深度求索", "地艾斯", "弟艾斯", "迪斯", "的艾斯"},
            {"抖音", "抖阴", "斗音", "逗音", "抖音极速版"},
            {"快手", "快守", "快首"},
            {"微信", "威信"},
            {"红果", "宏果", "红国", "鸿果"},
            {"证券", "正券", "政券", "股票", "炒股"},
            {"天鹅", "天额"},
            {"阅读", "月读"},
            {"b站", "哔哩哔哩", "bilibili", "bili"},
            {"豆包", "豆包应用", "豆包app", "豆包软件"}
    };

    /** 直接按包名启动：按 App 名字找不到时（「ds」「豆包应用」这种）的兜底 */
    private static final String[][] PACKAGES = {
            {"ds", "com.deepseek.chat"},
            {"抖音", "com.ss.android.ugc.aweme"},
            {"快手", "com.smile.gifmaker"},
            {"微信", "com.tencent.mm"},
            {"b站", "tv.danmaku.bili"},
            {"豆包", "com.larus.nova"},
            {"红果", "com.phoenix.read"}
    };

    /**
     * 光喊一个 App 名字也算「打开它」：
     * 「红果」= 打开红果，「抖音」= 打开抖音，「ds」= 打开 deepseek。
     * 用户要求命令简单化：本地识别听不准长句，短词反而稳定。
     */
    public static boolean isAppName(String t) {
        String n = Commands.normalize(t == null ? "" : t).toLowerCase();
        if (n.length() == 0 || n.length() > 8) {
            return false;
        }
        String c = canonical(n);
        for (String[] row : ALIAS) {
            if (row[0].equals(c)) {
                return true;
            }
        }
        for (String b : BARE_NAMES) {
            if (n.equals(b)) {
                return true;
            }
        }
        return false;
    }

    private Apps() {
    }

    /** 把可能听错的名字归一到用来找 App 的关键词 */
    public static String canonical(String name) {
        String n = Commands.normalize(name == null ? "" : name).toLowerCase();
        if (n.length() == 0) {
            return "";
        }
        for (String[] row : ALIAS) {
            for (int i = 1; i < row.length; i++) {
                if (n.equals(row[i])) {
                    return row[0];
                }
            }
        }
        for (String[] row : ALIAS) {
            for (int i = 1; i < row.length; i++) {
                if (n.contains(row[i])) {
                    return row[0];
                }
            }
        }
        return n;
    }

    /** 该名字对应的候选包名（先试这些，再按 App 名字找） */
    public static List<String> packages(String canonicalName) {
        List<String> out = new ArrayList<>();
        for (String[] row : PACKAGES) {
            if (row[0].equals(canonicalName)) {
                for (int i = 1; i < row.length; i++) {
                    out.add(row[i]);
                }
            }
        }
        return out;
    }

    /**
     * 「打开豆包应用 / 打开豆包app / 打开豆包软件」= 打开豆包这个 App。
     * 注意：光说「打开豆包」还是切换「使用豆包语音输入法」那个勾，两者别混。
     */
    public static boolean doubaoApp(String t) {
        return t.contains("豆包应用") || t.contains("豆包app") || t.contains("豆包软件");
    }
}
