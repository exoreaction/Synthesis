package io.exoreaction.synthesis.integration;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Path;
import java.util.List;

/**
 * JUnit 5 extension that fails-fast if the {@code SYNTHESIS_WORKSPACE} environment
 * variable points to a real (protected) workspace path.
 *
 * <p>Apply via the {@link IsolatedWorkspaceTest} meta-annotation:
 * <pre>{@code
 * @IsolatedWorkspaceTest
 * class MyIntegrationTest { ... }
 * }</pre>
 *
 * <p>Or directly via {@code @ExtendWith(WorkspaceIsolationExtension.class)}.
 *
 * @since v1.9.9 (issue #184)
 */
public class WorkspaceIsolationExtension implements BeforeEachCallback {

    /**
     * Paths that must not be touched by test workspaces.
     * Any path that starts with one of these prefixes is considered "real" and unsafe.
     */
    private static final List<String> PROTECTED_PATHS = List.of(
            System.getProperty("user.home"),
            "/src"
    );

    @Override
    public void beforeEach(ExtensionContext ctx) throws Exception {
        String env = System.getenv("SYNTHESIS_WORKSPACE");
        if (env == null || env.isBlank()) {
            return; // not set — safe
        }

        Path envPath = Path.of(env).toAbsolutePath().normalize();
        for (String protectedPathStr : PROTECTED_PATHS) {
            Path protectedPath = Path.of(protectedPathStr).toAbsolutePath().normalize();
            if (envPath.startsWith(protectedPath)) {
                throw new IllegalStateException(
                        "SYNTHESIS_WORKSPACE points to a real (protected) path: " + env
                        + ". Tests must use @TempDir to avoid touching real workspaces. "
                        + "Unset SYNTHESIS_WORKSPACE or override it to a temporary directory.");
            }
        }
    }

    // =========================================================================
    // Meta-annotation
    // =========================================================================

    /**
     * Convenience meta-annotation that activates {@link WorkspaceIsolationExtension}
     * on the annotated test class.
     *
     * <pre>{@code
     * @IsolatedWorkspaceTest
     * class SweepRoutingIntegrationTest {
     *     @TempDir Path tempDir;
     *     ...
     * }
     * }</pre>
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @ExtendWith(WorkspaceIsolationExtension.class)
    public @interface IsolatedWorkspaceTest {}
}
