package io.exoreaction.synthesis.core;

import java.util.List;

/**
 * Universal exclusion patterns that apply to all projects regardless of ecosystem.
 * These patterns cover version control, IDE files, OS artifacts, and logs.
 */
public class SmartExclusions {

    /**
     * Universal exclusion patterns applied to all workspaces.
     * These patterns are based on common development artifacts that should typically be excluded.
     */
    public static final List<String> UNIVERSAL = List.of(
        // Version Control
        ".git/**",
        "**/.git/**",
        ".svn/**",
        "**/.svn/**",
        ".hg/**",
        "**/.hg/**",
        ".bzr/**",
        "**/.bzr/**",

        // IDE Files
        ".idea/**",
        "**/.idea/**",
        ".vscode/**",
        "**/.vscode/**",
        ".vs/**",
        "**/.vs/**",
        "**/*.iml",
        "**/*.suo",
        "**/*.user",

        // OS Files
        ".DS_Store",
        "**/.DS_Store",
        "Thumbs.db",
        "**/Thumbs.db",
        "desktop.ini",
        "**/desktop.ini",

        // Editor Files
        "**/*.swp",
        "**/*.swo",
        "**/*~",
        ".#*",
        "**/.#*",

        // Logs and Temp
        "**/*.log",
        "logs/**",
        "**/logs/**",
        "tmp/**",
        "**/tmp/**",
        "temp/**",
        "**/temp/**",

        // Synthesis
        ".synthesis/**",
        "**/.synthesis/**"
    );

    private SmartExclusions() {
        // Utility class - prevent instantiation
    }
}
