package main.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Centralized application.conf update utility.
 * Single point for all read-modify-write operations on application.conf.
 */
public final class ConfigFileUpdater {

    public static final Path CONFIG_PATH = Paths.get("src/main/resources/application.conf");

    private ConfigFileUpdater() {}

    /**
     * Replaces all occurrences of {@code regex} with {@code replacement} in application.conf.
     *
     * @return true if the file was modified, false if content was unchanged
     */
    public static boolean replace(String regex, String replacement) throws IOException {
        String content = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
        String updated = content.replaceAll(regex, replacement);
        if (updated.equals(content)) return false;
        Files.writeString(CONFIG_PATH, updated, StandardCharsets.UTF_8);
        return true;
    }

    /**
     * Returns true if application.conf contains the given text verbatim.
     */
    public static boolean contains(String text) throws IOException {
        return Files.readString(CONFIG_PATH, StandardCharsets.UTF_8).contains(text);
    }

    /**
     * Returns the canonical HOCON line for a symbol entry in the symbols block.
     */
    public static String formatSymbolLine(String symbol, int rsiOS, int rsiOB,
                                          double slMult, double tpMult, double bbWidthMult) {
        return String.format(
            "  %s { bbPeriod = 17, bbStdDev = 2.6, rsiOversold = %d, rsiOverbought = %d, slMult = %.1f, tpMult = %.1f, bbWidthMult = %.1f }",
            symbol, rsiOS, rsiOB, slMult, tpMult, bbWidthMult);
    }

    /**
     * Inserts {@code symbolLine} before the closing brace of the {@code symbols { }} block.
     *
     * @return true if the file was modified
     */
    public static boolean insertIntoSymbolsBlock(String symbolLine) throws IOException {
        String content = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("(?s)(symbols\\s*\\{.*?)(\\n\\})").matcher(content);
        if (!matcher.find()) return false;
        String updated = matcher.replaceFirst("$1\n" + symbolLine + "$2");
        if (updated.equals(content)) return false;
        Files.writeString(CONFIG_PATH, updated, StandardCharsets.UTF_8);
        return true;
    }

    /**
     * Appends {@code symbol} (quoted) to the {@code pairs = []} array.
     *
     * @return true if the file was modified
     */
    public static boolean addToPairsArray(String symbol) throws IOException {
        String content = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("(pairs\\s*=\\s*\\[[^\\]]*)(])").matcher(content);
        if (!matcher.find()) return false;
        String existing = matcher.group(1);
        String sep = existing.trim().endsWith("[") ? "" : ", ";
        String updated = matcher.replaceFirst("$1" + sep + "\"" + symbol + "\"$2");
        if (updated.equals(content)) return false;
        Files.writeString(CONFIG_PATH, updated, StandardCharsets.UTF_8);
        return true;
    }
}
