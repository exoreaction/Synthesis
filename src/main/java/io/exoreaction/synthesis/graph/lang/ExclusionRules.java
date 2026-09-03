package io.exoreaction.synthesis.graph.lang;

/**
 * Shared file-discovery exclusion context passed to
 * {@link LanguageExtractor#findFiles}. Carries the one caller-configurable
 * exclusion; build-artifact and archive/vendor/node_modules filtering are fixed
 * rules applied inside each {@code findFiles}.
 *
 * @param includeArchives when {@code true}, archive/vendor/node_modules
 *        directories are included (mirrors {@code CodeGraphExtractor.setIncludeArchives}).
 */
public record ExclusionRules(boolean includeArchives) {}
