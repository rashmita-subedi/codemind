package com.codemind.cli;

public final class Ansi {

    private static final String ESC = "";
    private static final boolean ENABLED = enabled();

    public static final String RESET = code(ESC + "[0m");
    public static final String BOLD = code(ESC + "[1m");
    public static final String DIM = code(ESC + "[2m");
    public static final String ITALIC = code(ESC + "[3m");
    public static final String UNDERLINE = code(ESC + "[4m");

    public static final String BLACK = code(ESC + "[30m");
    public static final String RED = code(ESC + "[31m");
    public static final String GREEN = code(ESC + "[32m");
    public static final String YELLOW = code(ESC + "[33m");
    public static final String BLUE = code(ESC + "[34m");
    public static final String MAGENTA = code(ESC + "[35m");
    public static final String CYAN = code(ESC + "[36m");
    public static final String WHITE = code(ESC + "[37m");
    public static final String GRAY = code(ESC + "[90m");
    public static final String BRIGHT_RED = code(ESC + "[91m");
    public static final String BRIGHT_GREEN = code(ESC + "[92m");
    public static final String BRIGHT_YELLOW = code(ESC + "[93m");
    public static final String BRIGHT_BLUE = code(ESC + "[94m");
    public static final String BRIGHT_MAGENTA = code(ESC + "[95m");
    public static final String BRIGHT_CYAN = code(ESC + "[96m");
    public static final String BRIGHT_WHITE = code(ESC + "[97m");

    public static final String BG_GRAY = code(ESC + "[100m");

    private Ansi() {
    }

    public static boolean enabledOutput() {
        return ENABLED;
    }

    public static String wrap(String text, String... styles) {
        if (!ENABLED || text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        StringBuilder b = new StringBuilder(text.length() + 16);
        for (String s : styles) {
            b.append(s);
        }
        b.append(text).append(RESET);
        return b.toString();
    }

    public static String strip(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\[[;\\d]*m", "");
    }

    private static boolean enabled() {
        if (System.getenv("NO_COLOR") != null) {
            return false;
        }
        String term = System.getenv("TERM");
        if ("dumb".equalsIgnoreCase(term)) {
            return false;
        }
        return true;
    }

    private static String code(String c) {
        return ENABLED ? c : "";
    }
}
