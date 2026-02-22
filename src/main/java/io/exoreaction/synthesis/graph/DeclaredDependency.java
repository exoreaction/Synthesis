package io.exoreaction.synthesis.graph;

/**
 * A declared dependency extracted from a build file (pom.xml).
 *
 * @param groupId    Maven group ID, e.g. "org.apache.logging.log4j"
 * @param artifactId Maven artifact ID, e.g. "log4j-core"
 * @param version    declared version string (may be null if managed)
 * @param scope      Maven scope: compile, test, runtime, provided (may be null)
 * @param buildFile  path to the build file that declares this dependency
 * @since v1.14.0 (Security)
 */
public record DeclaredDependency(
        String groupId,
        String artifactId,
        String version,
        String scope,
        String buildFile
) {}
