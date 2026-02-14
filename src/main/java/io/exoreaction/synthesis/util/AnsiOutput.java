package io.exoreaction.synthesis.util;

/**
 * ANSI terminal color and formatting utilities.
 * Provides a clean API for colored terminal output without
 * scattering escape codes throughout the codebase.
 */
public final class AnsiOutput {

    private AnsiOutput() {}

    // ANSI escape codes
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";

    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String CYAN = "\u001B[36m";
    private static final String WHITE = "\u001B[37m";

    /** Whether ANSI output is enabled (can be disabled for piped output). */
    private static boolean enabled = System.console() != null;

    public static void setEnabled(boolean enabled) {
        AnsiOutput.enabled = enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    // --- Color methods ---

    public static String red(String text) {
        return enabled ? RED + text + RESET : text;
    }

    public static String green(String text) {
        return enabled ? GREEN + text + RESET : text;
    }

    public static String yellow(String text) {
        return enabled ? YELLOW + text + RESET : text;
    }

    public static String blue(String text) {
        return enabled ? BLUE + text + RESET : text;
    }

    public static String magenta(String text) {
        return enabled ? MAGENTA + text + RESET : text;
    }

    public static String cyan(String text) {
        return enabled ? CYAN + text + RESET : text;
    }

    public static String bold(String text) {
        return enabled ? BOLD + text + RESET : text;
    }

    public static String dim(String text) {
        return enabled ? DIM + text + RESET : text;
    }

    // --- Semantic methods ---

    public static String success(String text) {
        return green(text);
    }

    public static String error(String text) {
        return red(text);
    }

    public static String warning(String text) {
        return yellow(text);
    }

    public static String info(String text) {
        return blue(text);
    }

    public static String highlight(String text) {
        return cyan(text);
    }

    public static String header(String text) {
        return bold(blue(text));
    }

    // --- Output helpers ---

    public static void printSuccess(String message) {
        System.out.println(success("  [OK] ") + message);
    }

    public static void printError(String message) {
        System.err.println(error("  [ERROR] ") + message);
    }

    public static void printWarning(String message) {
        System.out.println(warning("  [WARN] ") + message);
    }

    public static void printInfo(String message) {
        System.out.println(info("  [INFO] ") + message);
    }

    public static void printHeader(String title) {
        String line = "=".repeat(Math.max(title.length() + 4, 40));
        System.out.println();
        System.out.println(header(line));
        System.out.println(header("  " + title));
        System.out.println(header(line));
        System.out.println();
    }
}
