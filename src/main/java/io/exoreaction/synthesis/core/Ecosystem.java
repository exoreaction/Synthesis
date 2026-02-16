package io.exoreaction.synthesis.core;

import java.util.List;

/**
 * Represents a software development ecosystem with its specific exclusion patterns.
 * Each ecosystem is detected based on marker files and applies targeted exclusions
 * for build artifacts, dependencies, and generated files.
 */
public enum Ecosystem {

    /**
     * Python ecosystem (pip, poetry, conda)
     */
    PYTHON(List.of(
        "venv/**",
        "**/venv/**",
        ".venv/**",
        "**/.venv/**",
        "env/**",
        "**/env/**",
        ".env/**",
        "**/.env/**",
        "__pycache__/**",
        "**/__pycache__/**",
        "**/*.pyc",
        "**/*.pyo",
        "**/*.pyd",
        ".Python",
        "**/.Python",
        "pip-log.txt",
        "**/pip-log.txt",
        "pip-delete-this-directory.txt",
        "**/pip-delete-this-directory.txt",
        ".tox/**",
        "**/.tox/**",
        ".coverage",
        "**/.coverage",
        ".pytest_cache/**",
        "**/.pytest_cache/**",
        ".mypy_cache/**",
        "**/.mypy_cache/**",
        ".hypothesis/**",
        "**/.hypothesis/**",
        "*.egg-info/**",
        "**/*.egg-info/**",
        "dist/**",
        "**/dist/**",
        "build/**",
        "**/build/**",
        ".eggs/**",
        "**/.eggs/**"
    )),

    /**
     * JavaScript/Node.js ecosystem (npm, yarn, pnpm)
     */
    JAVASCRIPT(List.of(
        "node_modules/**",
        "**/node_modules/**",
        ".npm/**",
        "**/.npm/**",
        ".yarn/**",
        "**/.yarn/**",
        ".pnp.*",
        "**/.pnp.*",
        ".pnp/**",
        "**/.pnp/**",
        ".next/**",
        "**/.next/**",
        ".nuxt/**",
        "**/.nuxt/**",
        "dist/**",
        "**/dist/**",
        "build/**",
        "**/build/**",
        ".cache/**",
        "**/.cache/**",
        "coverage/**",
        "**/coverage/**"
    )),

    /**
     * Java ecosystem with Maven build tool
     */
    JAVA_MAVEN(List.of(
        "target/**",
        "**/target/**",
        ".mvn/**",
        "**/.mvn/**",
        "mvnw",
        "**/mvnw",
        "mvnw.cmd",
        "**/mvnw.cmd"
    )),

    /**
     * Java ecosystem with Gradle build tool
     */
    JAVA_GRADLE(List.of(
        "build/**",
        "**/build/**",
        ".gradle/**",
        "**/.gradle/**",
        "gradlew",
        "**/gradlew",
        "gradlew.bat",
        "**/gradlew.bat",
        "gradle-app.setting",
        "**/gradle-app.setting"
    )),

    /**
     * Rust ecosystem (Cargo)
     */
    RUST(List.of(
        "target/**",
        "**/target/**",
        "Cargo.lock",
        "**/Cargo.lock"
    )),

    /**
     * Go ecosystem
     */
    GO(List.of(
        "vendor/**",
        "**/vendor/**",
        "go.sum",
        "**/go.sum"
    )),

    /**
     * .NET ecosystem (C#, F#, VB.NET)
     */
    DOTNET(List.of(
        "bin/**",
        "**/bin/**",
        "obj/**",
        "**/obj/**",
        "**/*.dll",
        "**/*.exe",
        "**/*.pdb",
        ".vs/**",
        "**/.vs/**",
        "packages/**",
        "**/packages/**"
    )),

    /**
     * Ruby ecosystem (Bundler, Gem)
     */
    RUBY(List.of(
        "vendor/bundle/**",
        "**/vendor/bundle/**",
        ".bundle/**",
        "**/.bundle/**",
        "**/*.gem",
        ".rbenv/**",
        "**/.rbenv/**",
        ".rvm/**",
        "**/.rvm/**"
    )),

    /**
     * PHP ecosystem (Composer)
     */
    PHP(List.of(
        "vendor/**",
        "**/vendor/**",
        "composer.lock",
        "**/composer.lock"
    ));

    private final List<String> exclusionPatterns;

    Ecosystem(List<String> exclusionPatterns) {
        this.exclusionPatterns = exclusionPatterns;
    }

    /**
     * Returns the exclusion patterns specific to this ecosystem.
     *
     * @return list of glob patterns to exclude
     */
    public List<String> getExclusionPatterns() {
        return exclusionPatterns;
    }
}
