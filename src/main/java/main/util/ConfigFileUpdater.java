package main.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Centralized application.conf update utility.
 * Replaces duplicate read-replaceAll-write patterns in BitgetTradingBot and DailyOptimizer.
 */
public final class ConfigFileUpdater {

    public static final Path CONFIG_PATH = Paths.get("src/main/resources/application.conf");

    private ConfigFileUpdater() {}

    /**
     * Replaces all occurrences of {@code regex} with {@code replacement} in application.conf.
     *
     * @return true if the file was modified, false if content was unchanged (no match or already identical)
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
     * Used by DailyOptimizer to distinguish "already correct" from "pattern match failure".
     */
    public static boolean contains(String text) throws IOException {
        return Files.readString(CONFIG_PATH, StandardCharsets.UTF_8).contains(text);
    }
}
