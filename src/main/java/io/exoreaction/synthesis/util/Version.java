package io.exoreaction.synthesis.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Utility class to read the version from the generated version.properties file.
 * The version is injected at build time by Maven resource filtering.
 */
public class Version {
    private static final String VERSION;

    static {
        String loadedVersion = "unknown";
        try (InputStream input = Version.class.getClassLoader().getResourceAsStream("version.properties")) {
            if (input != null) {
                Properties prop = new Properties();
                prop.load(input);
                loadedVersion = prop.getProperty("version", "unknown");
            }
        } catch (IOException ex) {
            System.err.println("Warning: Could not load version from version.properties: " + ex.getMessage());
        }
        VERSION = loadedVersion;
    }

    /**
     * Get the Synthesis version (e.g., "1.0.4-SNAPSHOT").
     * @return the version string
     */
    public static String getVersion() {
        return VERSION;
    }

    /**
     * Get the full version string with product name (e.g., "Synthesis 1.0.4-SNAPSHOT").
     * @return the full version string
     */
    public static String getFullVersion() {
        return "Synthesis " + VERSION;
    }
}
