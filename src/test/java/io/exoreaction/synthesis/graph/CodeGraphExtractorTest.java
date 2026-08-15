package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.graph.CodeGraphRepository.CodeDependency;
import io.exoreaction.synthesis.graph.CodeGraphRepository.CrossFormatLinkRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CodeGraphExtractor} -- code dependency extraction and persistence.
 */
class CodeGraphExtractorTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private Connection conn;
    private CodeGraphExtractor extractor;

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        conn = db.getConnection();
        extractor = new CodeGraphExtractor();
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    // -----------------------------------------------------------------------
    // Import extraction (unit-level)
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Kotlin support (spike)
    // -----------------------------------------------------------------------

    @Test
    void extractAndPersist_resolves_kotlin_supertype_edge_as_internal() throws SQLException, IOException {
        Path pkgDir = tempDir.resolve("src/main/kotlin/com/example");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("Base.kt"), "package com.example\n\nopen class Base\n");
        Files.writeString(pkgDir.resolve("Foo.kt"), "package com.example\n\nclass Foo : Base()\n");

        extractor.extractAndPersist(tempDir, conn);

        List<CodeDependency> deps = new CodeGraphRepository()
                .getDependenciesFrom(conn, tempDir.toString(), "src/main/kotlin/com/example/Foo.kt");
        CodeDependency supertypeDep = deps.stream()
                .filter(d -> "supertype".equals(d.dependencyType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a supertype edge from Foo.kt"));
        assertEquals("Base", supertypeDep.targetClass());
        assertFalse(supertypeDep.isExternal(), "Base is in-workspace, should resolve internal");
        assertEquals("src/main/kotlin/com/example/Base.kt", supertypeDep.targetFile());
    }

    @Test
    void extractAndPersist_attributes_kotlin_edges_to_filename_matching_class_not_first_declared()
            throws SQLException, IOException {
        // Regression test for the HelloController.kt shape found in tvimenning-template:
        // HelloResponse (a data class) is declared before the file's real primary class,
        // HelloController. Before the choosePrimaryClass fix, every import edge in the file
        // was misattributed to HelloResponse -- querying by the file's actual public,
        // externally-referenceable class name (HelloController) returned nothing.
        Path pkgDir = tempDir.resolve("src/main/kotlin/com/example");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("Greeter.kt"), "package com.example.service\n\nclass Greeter\n");
        Files.writeString(pkgDir.resolve("HelloController.kt"), """
                package com.example

                import com.example.service.Greeter

                data class HelloResponse(val message: String)

                class HelloController(private val greeter: Greeter)
                """);

        extractor.extractAndPersist(tempDir, conn);

        List<CodeDependency> fromHelloController = new CodeGraphRepository()
                .getDependenciesFrom(conn, tempDir.toString(), "src/main/kotlin/com/example/HelloController.kt");
        assertFalse(fromHelloController.isEmpty(), "expected edges attributed to the file");
        assertTrue(fromHelloController.stream().allMatch(d -> "HelloController".equals(d.sourceClass())),
                "all edges from this file should be attributed to HelloController, not HelloResponse");
    }

    // -----------------------------------------------------------------------
    // Kotlin top-level function resolution (#438)
    // -----------------------------------------------------------------------

    @Test
    void extractAndPersist_resolves_kotlin_top_level_function_import_via_single_candidate()
            throws SQLException, IOException {
        // Regression test for #438: Utils.kt has no top-level class, only a top-level
        // function -- the compiler-synthesized UtilsKt facade is never named by source-level
        // imports (they name doThing directly), so buildKotlinClassToFileMap alone can't
        // resolve this. Exactly one function-only file in the imported package -> resolve it.
        Path utilsDir = tempDir.resolve("src/main/kotlin/com/example/utils");
        Files.createDirectories(utilsDir);
        Files.writeString(utilsDir.resolve("Utils.kt"), """
                package com.example.utils

                fun doThing() {}
                """);
        Path callerDir = tempDir.resolve("src/main/kotlin/com/example");
        Files.createDirectories(callerDir);
        Files.writeString(callerDir.resolve("Caller.kt"), """
                package com.example

                import com.example.utils.doThing

                class Caller
                """);

        extractor.extractAndPersist(tempDir, conn);

        List<CodeDependency> fromCaller = new CodeGraphRepository()
                .getDependenciesFrom(conn, tempDir.toString(), "src/main/kotlin/com/example/Caller.kt");
        CodeDependency importDep = fromCaller.stream()
                .filter(d -> "import".equals(d.dependencyType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected an import edge from Caller.kt"));
        assertFalse(importDep.isExternal(),
                "single function-only candidate in the imported package should resolve internal");
        assertEquals("src/main/kotlin/com/example/utils/Utils.kt", importDep.targetFile());
    }

    @Test
    void extractAndPersist_kotlin_import_stays_external_when_multiple_function_only_candidates()
            throws SQLException, IOException {
        Path utilsDir = tempDir.resolve("src/main/kotlin/com/example/utils");
        Files.createDirectories(utilsDir);
        Files.writeString(utilsDir.resolve("Utils.kt"), """
                package com.example.utils

                fun doThing() {}
                """);
        Files.writeString(utilsDir.resolve("Helpers.kt"), """
                package com.example.utils

                fun doOtherThing() {}
                """);
        Path callerDir = tempDir.resolve("src/main/kotlin/com/example");
        Files.createDirectories(callerDir);
        Files.writeString(callerDir.resolve("Caller.kt"), """
                package com.example

                import com.example.utils.doThing

                class Caller
                """);

        extractor.extractAndPersist(tempDir, conn);

        List<CodeDependency> fromCaller = new CodeGraphRepository()
                .getDependenciesFrom(conn, tempDir.toString(), "src/main/kotlin/com/example/Caller.kt");
        CodeDependency importDep = fromCaller.stream()
                .filter(d -> "import".equals(d.dependencyType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected an import edge from Caller.kt"));
        assertTrue(importDep.isExternal(),
                "ambiguous package (2 function-only candidates) should stay external, not guess");
        assertNull(importDep.targetFile());
    }

    // -----------------------------------------------------------------------
    // Full extraction with temp workspace
    // -----------------------------------------------------------------------

    @Test
    void extractAndPersist_processes_java_files() throws SQLException, IOException {
        // Create a mini Java project
        Path srcDir = Files.createDirectories(tempDir.resolve("project/src"));
        Files.writeString(srcDir.resolve("Config.java"), """
                package com.example;
                public class Config {
                    private String name;
                }
                """);
        Files.writeString(srcDir.resolve("Service.java"), """
                package com.example;
                import com.example.Config;
                import java.util.List;

                public class Service {
                    private Config config;
                }
                """);
        Files.writeString(srcDir.resolve("App.java"), """
                package com.example;
                import com.example.Service;

                public class App {
                    private Service service;
                }
                """);

        Path projectRoot = tempDir.resolve("project");
        CodeGraphStats stats = extractor.extractAndPersist(projectRoot, conn);

        assertEquals(3, stats.filesProcessed());
        assertTrue(stats.dependenciesFound() >= 2, "Should find at least 2 import deps");
        assertTrue(stats.elapsedMs() >= 0);

        // Verify persistence
        CodeGraphRepository repo = extractor.getRepository();
        assertTrue(repo.isPopulated(conn, projectRoot.toString()));

        // Service.java imports Config
        List<CodeDependency> serviceDeps = repo.getDependenciesFrom(conn,
                projectRoot.toString(), "src/Service.java");
        assertTrue(serviceDeps.stream().anyMatch(d -> d.targetClass().equals("Config")),
                "Service should depend on Config");
    }

    @Test
    void extractAndPersist_detects_extends_and_implements() throws SQLException, IOException {
        Path srcDir = Files.createDirectories(tempDir.resolve("project/src"));
        Files.writeString(srcDir.resolve("Animal.java"), """
                package com.example;
                public class Animal {}
                """);
        Files.writeString(srcDir.resolve("Runnable.java"), """
                package com.example;
                public interface Runnable { void run(); }
                """);
        Files.writeString(srcDir.resolve("Dog.java"), """
                package com.example;
                public class Dog extends Animal implements Runnable {
                    public void run() {}
                }
                """);

        Path projectRoot = tempDir.resolve("project");
        CodeGraphStats stats = extractor.extractAndPersist(projectRoot, conn);

        CodeGraphRepository repo = extractor.getRepository();
        List<CodeDependency> dogDeps = repo.getDependenciesFrom(conn,
                projectRoot.toString(), "src/Dog.java");

        boolean hasExtends = dogDeps.stream()
                .anyMatch(d -> d.targetClass().equals("Animal") && d.dependencyType().equals("extends"));
        boolean hasImplements = dogDeps.stream()
                .anyMatch(d -> d.targetClass().equals("Runnable") && d.dependencyType().equals("implements"));

        assertTrue(hasExtends, "Dog should extend Animal: " + dogDeps);
        assertTrue(hasImplements, "Dog should implement Runnable: " + dogDeps);
    }

    @Test
    void extractAndPersist_clears_old_data_first() throws SQLException, IOException {
        Path srcDir = Files.createDirectories(tempDir.resolve("project/src"));
        Files.writeString(srcDir.resolve("Foo.java"), """
                package com.example;
                import java.util.List;
                public class Foo {}
                """);

        Path projectRoot = tempDir.resolve("project");
        extractor.extractAndPersist(projectRoot, conn);
        int firstCount = extractor.getRepository().countDependencies(conn, projectRoot.toString());

        // Run again -- should not accumulate duplicates
        extractor.extractAndPersist(projectRoot, conn);
        int secondCount = extractor.getRepository().countDependencies(conn, projectRoot.toString());

        assertEquals(firstCount, secondCount, "Second extraction should replace, not accumulate");
    }

    // -----------------------------------------------------------------------
    // Incremental update
    // -----------------------------------------------------------------------

    @Test
    void incrementalUpdate_replaces_changed_file_deps() throws SQLException, IOException {
        Path srcDir = Files.createDirectories(tempDir.resolve("project/src"));
        Files.writeString(srcDir.resolve("Config.java"), """
                package com.example;
                public class Config {}
                """);
        Files.writeString(srcDir.resolve("Service.java"), """
                package com.example;
                import com.example.Config;
                public class Service {}
                """);

        Path projectRoot = tempDir.resolve("project");
        extractor.extractAndPersist(projectRoot, conn);

        // Modify Service.java to no longer import Config
        Files.writeString(srcDir.resolve("Service.java"), """
                package com.example;
                public class Service {}
                """);

        // Run incremental for just Service.java
        Set<Path> changed = Set.of(Path.of("src/Service.java"));
        CodeGraphStats stats = extractor.incrementalUpdate(projectRoot, conn, changed);

        assertEquals(1, stats.filesProcessed());

        // Service.java should no longer depend on Config
        List<CodeDependency> serviceDeps = extractor.getRepository().getDependenciesFrom(
                conn, projectRoot.toString(), "src/Service.java");
        assertFalse(serviceDeps.stream().anyMatch(d -> d.targetClass().equals("Config")),
                "After incremental update, Service should no longer depend on Config");
    }

    @Test
    void incrementalUpdate_skips_non_java_files() throws SQLException, IOException {
        Path srcDir = Files.createDirectories(tempDir.resolve("project/src"));
        Files.writeString(srcDir.resolve("readme.md"), "# Hello");

        Path projectRoot = tempDir.resolve("project");
        Set<Path> changed = Set.of(Path.of("src/readme.md"));
        CodeGraphStats stats = extractor.incrementalUpdate(projectRoot, conn, changed);

        assertEquals(0, stats.filesProcessed());
    }

    // -----------------------------------------------------------------------
    // Incremental staleness -- the graph must not outlive the source (#459, #460)
    // -----------------------------------------------------------------------

    /** Writes a two-file project where Service imports Config, and returns its root. */
    private Path writeServiceImportingConfig(boolean withConfig) throws IOException {
        Path srcDir = Files.createDirectories(tempDir.resolve("project/src"));
        if (withConfig) {
            Files.writeString(srcDir.resolve("Config.java"), """
                    package com.example;
                    public class Config {}
                    """);
        }
        Files.writeString(srcDir.resolve("Service.java"), """
                package com.example;
                import com.example.Config;
                public class Service {}
                """);
        return tempDir.resolve("project");
    }

    /** The single Service -> Config edge, or an assertion failure if it is gone. */
    private CodeDependency serviceToConfig(Path projectRoot) throws SQLException {
        return extractor.getRepository()
                .getDependenciesFrom(conn, projectRoot.toString(), "src/Service.java").stream()
                .filter(d -> "Config".equals(d.targetClass()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a Service -> Config edge"));
    }

    @Test
    @DisplayName("REGRESSION #460: deleting a source file removes its rows on the next incremental")
    void incrementalUpdate_removes_rows_of_a_deleted_source_file() throws SQLException, IOException {
        Path projectRoot = writeServiceImportingConfig(true);
        extractor.extractAndPersist(projectRoot, conn);
        assertFalse(extractor.getRepository()
                        .getDependenciesFrom(conn, projectRoot.toString(), "src/Service.java").isEmpty(),
                "precondition: Service.java has edges after the full extract");

        Files.delete(projectRoot.resolve("src/Service.java"));
        extractor.incrementalUpdate(projectRoot, conn, Set.of(Path.of("src/Service.java")));

        assertTrue(extractor.getRepository()
                        .getDependenciesFrom(conn, projectRoot.toString(), "src/Service.java").isEmpty(),
                "a deleted file's outgoing edges must not survive the incremental update (#460)");
    }

    @Test
    @DisplayName("REGRESSION #459: an unchanged file's edge is refreshed when its target appears later")
    void incrementalUpdate_refreshes_edges_whose_target_became_resolvable()
            throws SQLException, IOException {
        Path projectRoot = writeServiceImportingConfig(false); // Config.java does not exist yet
        extractor.extractAndPersist(projectRoot, conn);
        assertTrue(serviceToConfig(projectRoot).isExternal(),
                "precondition: Config is unresolvable at first extract, so the edge is external");

        // Config.java appears later. Only Config.java is in the changed set -- Service.java is
        // untouched on disk and will never enter it.
        Files.writeString(projectRoot.resolve("src/Config.java"), """
                package com.example;
                public class Config {}
                """);
        extractor.incrementalUpdate(projectRoot, conn, Set.of(Path.of("src/Config.java")));

        CodeDependency edge = serviceToConfig(projectRoot);
        assertFalse(edge.isExternal(),
                "Service -> Config must be internal once Config.java exists (#459)");
        assertEquals("src/Config.java", edge.targetFile(),
                "the refreshed edge must point at the file that now provides the target (#459)");
    }

    @Test
    @DisplayName("REGRESSION #459: an unchanged file's edge is refreshed when its target disappears")
    void incrementalUpdate_refreshes_edges_whose_target_file_disappeared()
            throws SQLException, IOException {
        Path projectRoot = writeServiceImportingConfig(true);
        extractor.extractAndPersist(projectRoot, conn);
        assertEquals("src/Config.java", serviceToConfig(projectRoot).targetFile(),
                "precondition: the edge resolves to Config.java after the full extract");

        // Config.java is deleted. Service.java is unchanged, so only Config.java is in the set.
        Files.delete(projectRoot.resolve("src/Config.java"));
        extractor.incrementalUpdate(projectRoot, conn, Set.of(Path.of("src/Config.java")));

        CodeDependency edge = serviceToConfig(projectRoot);
        assertTrue(edge.isExternal(),
                "Service -> Config must fall back to external once Config.java is gone (#459)");
        assertTrue(edge.targetFile() == null || edge.targetFile().isEmpty(),
                "the edge must not keep pointing at a file that no longer exists (#459)");
    }

    @Test
    @DisplayName("REGRESSION #459: a TypeScript edge is refreshed when its module appears later")
    void incrementalUpdate_refreshes_typescript_edges_whose_module_became_resolvable()
            throws SQLException, IOException {
        // TypeScript declares no FQN identities -- it reaches the resolver through the module
        // path index -- so it needs its own path into the re-resolution above.
        Path tsDir = Files.createDirectories(tempDir.resolve("project/src"));
        Files.writeString(tsDir.resolve("foo.ts"), "import { bar } from './bar';\n");
        Path projectRoot = tempDir.resolve("project");
        extractor.extractAndPersist(projectRoot, conn);

        CodeDependency before = extractor.getRepository()
                .getDependenciesFrom(conn, projectRoot.toString(), "src/foo.ts").stream()
                .filter(d -> "bar".equals(d.targetClass()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a foo.ts -> bar edge"));
        assertTrue(before.isExternal(), "precondition: bar.ts does not exist yet");

        Files.writeString(tsDir.resolve("bar.ts"), "export const bar = 1;\n");
        extractor.incrementalUpdate(projectRoot, conn, Set.of(Path.of("src/bar.ts")));

        CodeDependency after = extractor.getRepository()
                .getDependenciesFrom(conn, projectRoot.toString(), "src/foo.ts").stream()
                .filter(d -> "bar".equals(d.targetClass()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a foo.ts -> bar edge"));
        assertFalse(after.isExternal(),
                "foo.ts -> ./bar must be internal once bar.ts exists (#459)");
        assertEquals("src/bar.ts", after.targetFile());
    }

    // -----------------------------------------------------------------------
    // Cross-format links must not freeze on the incremental path (#465)
    // -----------------------------------------------------------------------

    /**
     * Writes a project with one migration creating {@code customers} and one Java class
     * referencing that table, and returns its root.
     */
    private Path writeMigrationAndRepo() throws IOException {
        Path srcDir = Files.createDirectories(tempDir.resolve("project/src"));
        Files.writeString(srcDir.resolve("V1__init.sql"), "CREATE TABLE customers (id INT);\n");
        Files.writeString(srcDir.resolve("CustomerRepo.java"), """
                package com.example;
                public class CustomerRepo {
                    String t = "customers";
                }
                """);
        return tempDir.resolve("project");
    }

    /** The source files of every persisted cross-format link, for readable assertions. */
    private List<String> crossFormatSources(Path projectRoot) throws SQLException {
        return extractor.getRepository()
                .getCrossFormatLinks(conn, projectRoot.toString()).stream()
                .map(CrossFormatLinkRecord::sourceFile)
                .toList();
    }

    @Test
    @DisplayName("REGRESSION #465: a new SQL file's links are persisted on the incremental path")
    void incrementalUpdate_persists_links_for_a_new_sql_file() throws SQLException, IOException {
        Path projectRoot = writeMigrationAndRepo();
        extractor.extractAndPersist(projectRoot, conn);
        assertEquals(List.of("src/V1__init.sql"), crossFormatSources(projectRoot),
                "precondition: the full extract links V1__init.sql to CustomerRepo.java");

        Files.writeString(projectRoot.resolve("src/V2__orders.sql"),
                "CREATE TABLE orders (id INT);\n");
        Files.writeString(projectRoot.resolve("src/OrderRepo.java"), """
                package com.example;
                public class OrderRepo {
                    String t = "orders";
                }
                """);
        extractor.incrementalUpdate(projectRoot, conn,
                Set.of(Path.of("src/V2__orders.sql"), Path.of("src/OrderRepo.java")));

        assertTrue(crossFormatSources(projectRoot).contains("src/V2__orders.sql"),
                "a migration added since the last full extract must be linked (#465)");
    }

    @Test
    @DisplayName("REGRESSION #465: a deleted SQL file's links are removed on the incremental path")
    void incrementalUpdate_removes_links_of_a_deleted_sql_file() throws SQLException, IOException {
        Path projectRoot = writeMigrationAndRepo();
        extractor.extractAndPersist(projectRoot, conn);
        assertEquals(List.of("src/V1__init.sql"), crossFormatSources(projectRoot),
                "precondition: the link exists after the full extract");

        Files.delete(projectRoot.resolve("src/V1__init.sql"));
        extractor.incrementalUpdate(projectRoot, conn, Set.of(Path.of("src/V1__init.sql")));

        assertFalse(crossFormatSources(projectRoot).contains("src/V1__init.sql"),
                "a link must not outlive the SQL file that produced it (#465)");
    }

    @Test
    @DisplayName("REGRESSION #465: a new Java file is linked to an unchanged SQL file")
    void incrementalUpdate_links_a_new_java_file_to_an_unchanged_sql_file()
            throws SQLException, IOException {
        // The counterpart case: only the Java side changes, so re-linking just the changed
        // .sql files would miss it -- the #459 staleness class in the cross-format table.
        Path projectRoot = writeMigrationAndRepo();
        extractor.extractAndPersist(projectRoot, conn);

        Files.writeString(projectRoot.resolve("src/CustomerDao.java"), """
                package com.example;
                public class CustomerDao {
                    String t = "customers";
                }
                """);
        extractor.incrementalUpdate(projectRoot, conn, Set.of(Path.of("src/CustomerDao.java")));

        List<String> targets = extractor.getRepository()
                .getCrossFormatLinks(conn, projectRoot.toString()).stream()
                .map(CrossFormatLinkRecord::targetFile)
                .toList();
        assertTrue(targets.contains("src/CustomerDao.java"),
                "a Java file added after the full extract must be linked to the table it uses (#465)");
    }

    @Test
    @DisplayName("REGRESSION #465: a modified SQL file's links follow the table it now declares")
    void incrementalUpdate_relinks_a_modified_sql_file() throws SQLException, IOException {
        Path projectRoot = writeMigrationAndRepo();
        extractor.extractAndPersist(projectRoot, conn);
        assertEquals(List.of("src/V1__init.sql"), crossFormatSources(projectRoot),
                "precondition: the link exists after the full extract");

        // The migration now declares a table nothing references.
        Files.writeString(projectRoot.resolve("src/V1__init.sql"),
                "CREATE TABLE archived_invoices (id INT);\n");
        extractor.incrementalUpdate(projectRoot, conn, Set.of(Path.of("src/V1__init.sql")));

        assertTrue(crossFormatSources(projectRoot).isEmpty(),
                "a link must not survive the table declaration that justified it (#465)");
    }

    @Test
    @DisplayName("REGRESSION #465: a Java file that drops its table reference drops its link")
    void incrementalUpdate_removes_the_link_of_a_java_file_that_stopped_referencing()
            throws SQLException, IOException {
        Path projectRoot = writeMigrationAndRepo();
        extractor.extractAndPersist(projectRoot, conn);
        assertEquals(List.of("src/V1__init.sql"), crossFormatSources(projectRoot),
                "precondition: the link exists after the full extract");

        // Only the Java side changes -- V1__init.sql is untouched and never enters the set.
        Files.writeString(projectRoot.resolve("src/CustomerRepo.java"), """
                package com.example;
                public class CustomerRepo {}
                """);
        extractor.incrementalUpdate(projectRoot, conn, Set.of(Path.of("src/CustomerRepo.java")));

        assertTrue(crossFormatSources(projectRoot).isEmpty(),
                "a link must not survive the reference that justified it (#465)");
    }

    @Test
    @DisplayName("#465: a change touching no cross-format file leaves the links alone")
    void incrementalUpdate_leaves_links_untouched_when_no_sql_or_java_changed()
            throws SQLException, IOException {
        Path projectRoot = writeMigrationAndRepo();
        Files.writeString(projectRoot.resolve("src/Util.kt"), "package com.example\n\nclass Util\n");
        extractor.extractAndPersist(projectRoot, conn);
        assertEquals(List.of("src/V1__init.sql"), crossFormatSources(projectRoot),
                "precondition: the link exists after the full extract");

        CodeGraphStats stats = extractor.incrementalUpdate(projectRoot, conn,
                Set.of(Path.of("src/Util.kt")));

        assertEquals(List.of("src/V1__init.sql"), crossFormatSources(projectRoot),
                "a Kotlin-only change must not disturb the cross-format table (#465)");
        assertEquals(0, stats.crossFormatLinks(), "nothing was re-persisted");
    }

    @Test
    @DisplayName("REGRESSION #465: the incremental path reports its cross-format link count")
    void incrementalUpdate_reports_the_cross_format_link_count() throws SQLException, IOException {
        Path projectRoot = writeMigrationAndRepo();
        extractor.extractAndPersist(projectRoot, conn);

        // CustomerRepo.java references `customers`, so re-linking it persists exactly one row.
        CodeGraphStats stats = extractor.incrementalUpdate(projectRoot, conn,
                Set.of(Path.of("src/CustomerRepo.java")));

        assertEquals(1, stats.crossFormatLinks(),
                "the incremental path must report the links it persisted, like the full path "
                        + "does, instead of a hardcoded 0 (#465)");
    }

    // -----------------------------------------------------------------------
    // YAML config links must be persisted, not only shown by `relate` (#464)
    // -----------------------------------------------------------------------

    /**
     * Writes a project with one YAML config declaring {@code featureToggle} and one Java class
     * referencing that key, and returns its root.
     */
    private Path writeConfigAndConsumer() throws IOException {
        Path projectRoot = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(projectRoot.resolve("conf"));
        Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve("conf/application.yaml"),
                "featureToggle:\n  enabled: true\n");
        Files.writeString(projectRoot.resolve("src/Config.java"), """
                package com.example;
                public class Config {
                    String k = "featureToggle";
                }
                """);
        return projectRoot;
    }

    /** Every persisted cross-format link as {@code source|target|type|entity}, for readability. */
    private List<String> crossFormatLinks(Path projectRoot) throws SQLException {
        return extractor.getRepository()
                .getCrossFormatLinks(conn, projectRoot.toString()).stream()
                .map(l -> l.sourceFile() + "|" + l.targetFile() + "|" + l.linkType()
                        + "|" + l.entityName())
                .toList();
    }

    @Test
    @DisplayName("REGRESSION #464: the full extract persists YAML→Java links, not only SQL ones")
    void extractAndPersist_persists_yaml_to_java_links() throws SQLException, IOException {
        Path projectRoot = writeConfigAndConsumer();

        extractor.extractAndPersist(projectRoot, conn);

        assertEquals(List.of("conf/application.yaml|src/Config.java|config-key|featureToggle"),
                crossFormatLinks(projectRoot),
                "`relate application.yaml` reports this link, so the graph must stand behind "
                        + "it too (#464)");
    }

    @Test
    @DisplayName("#464: SQL and YAML links coexist, each under its own link type")
    void extractAndPersist_persists_sql_and_yaml_links_side_by_side()
            throws SQLException, IOException {
        Path projectRoot = writeConfigAndConsumer();
        Files.writeString(projectRoot.resolve("src/V1__init.sql"),
                "CREATE TABLE customers (id INT);\n");
        Files.writeString(projectRoot.resolve("src/CustomerRepo.java"), """
                package com.example;
                public class CustomerRepo {
                    String t = "customers";
                }
                """);

        CodeGraphStats stats = extractor.extractAndPersist(projectRoot, conn);

        assertEquals(2, stats.crossFormatLinks(), "one SQL link and one YAML link");
        List<String> links = crossFormatLinks(projectRoot);
        assertTrue(links.contains("src/V1__init.sql|src/CustomerRepo.java|table-reference|customers"),
                "the SQL link keeps its existing link type: " + links);
        assertTrue(links.contains("conf/application.yaml|src/Config.java|config-key|featureToggle"),
                "the YAML link carries the type `relate` already prints: " + links);
    }

    @Test
    @DisplayName("#464: YAML links exclude test sources, as SQL links do")
    void extractAndPersist_does_not_link_yaml_to_test_sources() throws SQLException, IOException {
        Path projectRoot = writeConfigAndConsumer();
        Files.delete(projectRoot.resolve("src/Config.java"));
        Files.createDirectories(projectRoot.resolve("src/test/java"));
        Files.writeString(projectRoot.resolve("src/test/java/ConfigTest.java"), """
                package com.example;
                public class ConfigTest {
                    String k = "featureToggle";
                }
                """);

        extractor.extractAndPersist(projectRoot, conn);

        assertTrue(crossFormatLinks(projectRoot).isEmpty(),
                "a test source is not a consumer the graph reports (#464)");
    }

    @Test
    @DisplayName("#464: YAML under a build output directory is not linked")
    void extractAndPersist_ignores_yaml_in_build_output() throws SQLException, IOException {
        Path projectRoot = writeConfigAndConsumer();
        Files.delete(projectRoot.resolve("conf/application.yaml"));
        Files.createDirectories(projectRoot.resolve("target/classes"));
        Files.writeString(projectRoot.resolve("target/classes/application.yaml"),
                "featureToggle:\n  enabled: true\n");

        extractor.extractAndPersist(projectRoot, conn);

        assertTrue(crossFormatLinks(projectRoot).isEmpty(),
                "a copy of the config under target/ is not a source of truth");
    }

    @Test
    @DisplayName("#464: an unreadable YAML file is skipped, not fatal")
    void extractAndPersist_skips_unreadable_yaml() throws SQLException, IOException {
        Path projectRoot = writeConfigAndConsumer();
        Files.write(projectRoot.resolve("conf/broken.yaml"),
                new byte[] {(byte) 0xC3, (byte) 0x28, (byte) 0xA0, (byte) 0xA1});

        CodeGraphStats stats = extractor.extractAndPersist(projectRoot, conn);

        assertEquals(1, stats.crossFormatLinks(),
                "the readable config is still linked after the broken one is skipped");
    }

    @Test
    @DisplayName("#464: a broken YAML between two good ones stops neither of them")
    void extractAndPersist_continues_past_a_broken_yaml_between_two_good_ones()
            throws SQLException, IOException {
        // The walk hands the sources over in an order the caller does not choose, so a file that
        // cannot be read has to be skipped in place rather than end the pass.
        Path projectRoot = writeConfigAndConsumer();
        Files.writeString(projectRoot.resolve("conf/a-first.yaml"), "firstToggle:\n  on: true\n");
        Files.write(projectRoot.resolve("conf/b-broken.yaml"),
                new byte[] {(byte) 0xC3, (byte) 0x28, (byte) 0xA0, (byte) 0xA1});
        Files.writeString(projectRoot.resolve("conf/c-last.yaml"), "lastToggle:\n  on: true\n");
        Files.writeString(projectRoot.resolve("src/Toggles.java"), """
                package com.example;
                public class Toggles {
                    String first = "firstToggle";
                    String last = "lastToggle";
                }
                """);

        extractor.extractAndPersist(projectRoot, conn);

        List<String> sources = crossFormatSources(projectRoot);
        assertTrue(sources.contains("conf/a-first.yaml"),
                "the config before the broken one is linked: " + sources);
        assertTrue(sources.contains("conf/c-last.yaml"),
                "the config after the broken one is linked: " + sources);
    }

    @Test
    @DisplayName("#464: a Java file that cannot be read is skipped, and the pass continues")
    void extractAndPersist_skips_an_unreadable_java_consumer() throws SQLException, IOException {
        Path projectRoot = writeConfigAndConsumer();
        Files.write(projectRoot.resolve("src/Broken.java"),
                new byte[] {(byte) 0xC3, (byte) 0x28, (byte) 0xA0, (byte) 0xA1});

        CodeGraphStats stats = extractor.extractAndPersist(projectRoot, conn);

        assertEquals(1, stats.crossFormatLinks(),
                "the readable consumer is still linked after the unreadable one is skipped");
    }

    @Test
    @DisplayName("REGRESSION #464: a new YAML file's links are persisted on the incremental path")
    void incrementalUpdate_persists_links_for_a_new_yaml_file() throws SQLException, IOException {
        Path projectRoot = writeConfigAndConsumer();
        extractor.extractAndPersist(projectRoot, conn);

        Files.writeString(projectRoot.resolve("conf/routing.yaml"), "routingRules:\n  a: b\n");
        Files.writeString(projectRoot.resolve("src/Router.java"), """
                package com.example;
                public class Router {
                    String k = "routingRules";
                }
                """);
        extractor.incrementalUpdate(projectRoot, conn,
                Set.of(Path.of("conf/routing.yaml"), Path.of("src/Router.java")));

        assertTrue(crossFormatSources(projectRoot).contains("conf/routing.yaml"),
                "a config added since the last full extract must be linked (#464)");
    }

    @Test
    @DisplayName("REGRESSION #464: a deleted YAML file's links are removed on the incremental path")
    void incrementalUpdate_removes_links_of_a_deleted_yaml_file() throws SQLException, IOException {
        Path projectRoot = writeConfigAndConsumer();
        extractor.extractAndPersist(projectRoot, conn);
        assertEquals(List.of("conf/application.yaml"), crossFormatSources(projectRoot),
                "precondition: the link exists after the full extract");

        Files.delete(projectRoot.resolve("conf/application.yaml"));
        extractor.incrementalUpdate(projectRoot, conn, Set.of(Path.of("conf/application.yaml")));

        assertTrue(crossFormatSources(projectRoot).isEmpty(),
                "a link must not outlive the config file that produced it (#464)");
    }

    @Test
    @DisplayName("REGRESSION #464: a new Java file is linked to an unchanged YAML file")
    void incrementalUpdate_links_a_new_java_file_to_an_unchanged_yaml_file()
            throws SQLException, IOException {
        Path projectRoot = writeConfigAndConsumer();
        extractor.extractAndPersist(projectRoot, conn);

        Files.writeString(projectRoot.resolve("src/SecondConsumer.java"), """
                package com.example;
                public class SecondConsumer {
                    String k = "featureToggle";
                }
                """);
        extractor.incrementalUpdate(projectRoot, conn, Set.of(Path.of("src/SecondConsumer.java")));

        List<String> targets = extractor.getRepository()
                .getCrossFormatLinks(conn, projectRoot.toString()).stream()
                .map(CrossFormatLinkRecord::targetFile)
                .toList();
        assertTrue(targets.contains("src/SecondConsumer.java"),
                "a Java file added after the full extract must be linked to the config key it "
                        + "uses (#464)");
    }

    @Test
    @DisplayName("REGRESSION #464: a modified YAML file's links follow the keys it now declares")
    void incrementalUpdate_relinks_a_modified_yaml_file() throws SQLException, IOException {
        Path projectRoot = writeConfigAndConsumer();
        extractor.extractAndPersist(projectRoot, conn);
        assertEquals(List.of("conf/application.yaml"), crossFormatSources(projectRoot),
                "precondition: the link exists after the full extract");

        // The config now declares a key nothing references.
        Files.writeString(projectRoot.resolve("conf/application.yaml"),
                "retiredToggle:\n  enabled: true\n");
        extractor.incrementalUpdate(projectRoot, conn, Set.of(Path.of("conf/application.yaml")));

        assertTrue(crossFormatSources(projectRoot).isEmpty(),
                "a link must not survive the key declaration that justified it (#464)");
    }

    @Test
    @DisplayName("REGRESSION #464: phase 10 must admit YAML as a cross-format input")
    void isCrossFormatSourceFile_admits_yaml_and_sql() {
        // MaintainOrchestrator gates the changed set on this: a .yaml it rejects never reaches
        // the incremental update, so its links are never added, and never cleaned up.
        assertTrue(CodeGraphExtractor.isCrossFormatSourceFile("conf/application.yaml"));
        assertTrue(CodeGraphExtractor.isCrossFormatSourceFile("conf/application.yml"));
        assertTrue(CodeGraphExtractor.isCrossFormatSourceFile("src/V1__init.sql"));
        assertFalse(CodeGraphExtractor.isCrossFormatSourceFile("README.md"));
    }

    // -----------------------------------------------------------------------
    // Helper: findJavaFiles
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Registry-driven file discovery (#466)
    // -----------------------------------------------------------------------

    @Test
    void sourceFilesByLanguage_covers_every_registered_language() throws IOException {
        Path java = Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(java.resolve("Foo.java"), "package com.example;\npublic class Foo {}\n");
        Path kotlin = Files.createDirectories(tempDir.resolve("src/main/kotlin/com/example"));
        Files.writeString(kotlin.resolve("Bar.kt"), "package com.example\n\nclass Bar\n");
        Path ts = Files.createDirectories(tempDir.resolve("src/main/ts"));
        Files.writeString(ts.resolve("baz.ts"), "export const baz = 1;\n");
        Files.writeString(ts.resolve("widget.tsx"), "export const w = 1;\n");

        Map<String, List<Path>> byLanguage = extractor.sourceFilesByLanguage(tempDir);

        assertEquals(List.of("Java", "Kotlin", "TypeScript"), List.copyOf(byLanguage.keySet()),
                "every registered language is reported, in registry order");
        assertEquals(1, byLanguage.get("Java").size());
        assertEquals(1, byLanguage.get("Kotlin").size());
        assertEquals(2, byLanguage.get("TypeScript").size(), ".ts and .tsx both claimed");
    }

    @Test
    void sourceFilesByLanguage_applies_each_language_own_exclusions() throws IOException {
        Path ts = Files.createDirectories(tempDir.resolve("src/main/ts"));
        Files.writeString(ts.resolve("foo.ts"), "export const foo = 1;\n");
        Files.writeString(ts.resolve("types.d.ts"), "export declare const x: number;\n");

        Map<String, List<Path>> byLanguage = extractor.sourceFilesByLanguage(tempDir);

        assertEquals(1, byLanguage.get("TypeScript").size(),
                "the TypeScript extractor's own .d.ts exclusion must apply: "
                        + byLanguage.get("TypeScript"));
        assertTrue(byLanguage.get("TypeScript").get(0).toString().endsWith("foo.ts"));
    }

    @Test
    void sourceFilesByLanguage_honours_includeArchives_flag() throws IOException {
        Path vendored = Files.createDirectories(tempDir.resolve("node_modules/dep/src"));
        Files.writeString(vendored.resolve("dep.ts"), "export const d = 1;\n");
        Path ts = Files.createDirectories(tempDir.resolve("src/main/ts"));
        Files.writeString(ts.resolve("foo.ts"), "export const foo = 1;\n");

        assertEquals(1, extractor.sourceFilesByLanguage(tempDir).get("TypeScript").size(),
                "node_modules excluded by default");

        extractor.setIncludeArchives(true);
        assertEquals(2, extractor.sourceFilesByLanguage(tempDir).get("TypeScript").size(),
                "--include-archives must reach discovery too");
    }

    @Test
    void isSourceFile_matches_every_registered_language_extension() {
        // The single source of truth for "is this a code-graph file?" -- callers that gate on
        // their own extension list (maintain phase 10 did) go stale when a language is added.
        assertTrue(CodeGraphExtractor.isSourceFile("src/main/java/com/example/Foo.java"));
        assertTrue(CodeGraphExtractor.isSourceFile("src/main/kotlin/com/example/Bar.kt"));
        assertTrue(CodeGraphExtractor.isSourceFile("src/main/ts/baz.ts"));
        assertTrue(CodeGraphExtractor.isSourceFile("src/main/ts/widget.tsx"));
        assertFalse(CodeGraphExtractor.isSourceFile("README.md"));
        assertFalse(CodeGraphExtractor.isSourceFile("src/main/resources/db/V1__init.sql"));
    }

    @Test
    void isBuildArtifact_detects_common_build_dirs() {
        Path root = Path.of("/workspace");
        assertTrue(CodeGraphExtractor.isBuildArtifact(root, Path.of("/workspace/target/classes/Foo.java")));
        assertTrue(CodeGraphExtractor.isBuildArtifact(root, Path.of("/workspace/build/classes/Foo.java")));
        assertTrue(CodeGraphExtractor.isBuildArtifact(root, Path.of("/workspace/out/classes/Foo.java")));
        assertTrue(CodeGraphExtractor.isBuildArtifact(root, Path.of("/workspace/sub/target/Foo.java")));
        assertFalse(CodeGraphExtractor.isBuildArtifact(root, Path.of("/workspace/src/main/java/Foo.java")));
    }

    @Test
    void fqn_lookup_correctly_marks_stdlib_as_external() throws SQLException, IOException {
        // This test verifies that stdlib/framework imports are correctly marked external
        // even when a project class has the same simple name (issue #223)
        Path srcDir = Files.createDirectories(tempDir.resolve("project/src"));
        Files.writeString(srcDir.resolve("Service.java"), """
                package com.example;
                public class Service {}
                """);
        Files.writeString(srcDir.resolve("App.java"), """
                package com.example;
                import org.springframework.stereotype.Service;
                import com.example.Service;

                public class App {}
                """);

        Path projectRoot = tempDir.resolve("project");
        CodeGraphStats stats = extractor.extractAndPersist(projectRoot, conn);

        CodeGraphRepository repo = extractor.getRepository();
        List<CodeDependency> appDeps = repo.getDependenciesFrom(conn,
                projectRoot.toString(), "src/App.java");

        // The Spring import should be external (not matched to project's Service)
        boolean springExternal = appDeps.stream()
                .anyMatch(d -> d.targetClass().equals("Service")
                        && d.targetPackage().equals("org.springframework.stereotype")
                        && d.isExternal());
        assertTrue(springExternal,
                "Spring Service import should be external: " + appDeps);

        // The project import should be internal
        boolean projectInternal = appDeps.stream()
                .anyMatch(d -> d.targetClass().equals("Service")
                        && d.targetPackage().equals("com.example")
                        && !d.isExternal());
        assertTrue(projectInternal,
                "Project Service import should be internal: " + appDeps);
    }

    // -----------------------------------------------------------------------
    // Non-Java repo skipping (#226)
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // #279: archive/ directory exclusion
    // -----------------------------------------------------------------------

    @Test
    void isArchiveDirectory_detects_archive_dir() {
        Path root = Path.of("/workspace");
        assertTrue(CodeGraphExtractor.isArchiveDirectory(root,
                Path.of("/workspace/archive/old/Foo.java")));
        assertTrue(CodeGraphExtractor.isArchiveDirectory(root,
                Path.of("/workspace/project/archive/Foo.java")));
    }

    @Test
    void isArchiveDirectory_detects_vendor_dir() {
        Path root = Path.of("/workspace");
        assertTrue(CodeGraphExtractor.isArchiveDirectory(root,
                Path.of("/workspace/vendor/lib/Foo.java")));
    }

    @Test
    void isArchiveDirectory_detects_node_modules_dir() {
        Path root = Path.of("/workspace");
        assertTrue(CodeGraphExtractor.isArchiveDirectory(root,
                Path.of("/workspace/node_modules/pkg/Foo.java")));
    }

    @Test
    void isArchiveDirectory_does_not_match_normal_dirs() {
        Path root = Path.of("/workspace");
        assertFalse(CodeGraphExtractor.isArchiveDirectory(root,
                Path.of("/workspace/src/main/java/Foo.java")));
        assertFalse(CodeGraphExtractor.isArchiveDirectory(root,
                Path.of("/workspace/archiver/Foo.java")));
    }


    // -----------------------------------------------------------------------
    // TypeScript extraction (#323) -- characterization (black-box) so the
    // per-language seam refactor has a gate to measure against.
    // -----------------------------------------------------------------------

    @Test
    void extractAndPersist_ts_relative_import_resolves_internal() throws SQLException, IOException {
        Path src = Files.createDirectories(tempDir.resolve("project/src"));
        Files.writeString(src.resolve("Bar.ts"), "export const bar = 1;\n");
        Files.writeString(src.resolve("Foo.ts"), "import { bar } from './Bar';\nexport const foo = bar;\n");

        Path root = tempDir.resolve("project");
        extractor.extractAndPersist(root, conn);

        List<CodeDependency> fooDeps = new CodeGraphRepository()
                .getDependenciesFrom(conn, root.toString(), "src/Foo.ts");
        CodeDependency dep = fooDeps.stream()
                .filter(d -> "Bar".equals(d.targetClass()))
                .findFirst().orElseThrow(() -> new AssertionError("expected edge to Bar: " + fooDeps));
        assertFalse(dep.isExternal(), "relative import to an in-workspace file is internal");
        assertEquals("src/Bar.ts", dep.targetFile());
        assertEquals("import", dep.dependencyType());
        assertEquals("Foo", dep.sourceClass());
    }

    @Test
    void extractAndPersist_ts_bare_module_import_stays_external() throws SQLException, IOException {
        Path src = Files.createDirectories(tempDir.resolve("project/src"));
        Files.writeString(src.resolve("Foo.ts"), "import React from 'react';\n");

        Path root = tempDir.resolve("project");
        extractor.extractAndPersist(root, conn);

        List<CodeDependency> fooDeps = new CodeGraphRepository()
                .getDependenciesFrom(conn, root.toString(), "src/Foo.ts");
        CodeDependency dep = fooDeps.stream()
                .filter(d -> "react".equals(d.targetClass()))
                .findFirst().orElseThrow(() -> new AssertionError("expected react edge: " + fooDeps));
        assertTrue(dep.isExternal(), "bare module specifier is external");
        assertNull(dep.targetFile());
    }

    @Test
    void extractAndPersist_ts_js_extension_rewrite_resolves_to_ts() throws SQLException, IOException {
        Path src = Files.createDirectories(tempDir.resolve("project/src"));
        Files.writeString(src.resolve("Bar.ts"), "export const bar = 1;\n");
        // Bun/NodeNext: source imports its own file by the compiled .js extension.
        Files.writeString(src.resolve("Foo.ts"), "import { bar } from './Bar.js';\n");

        Path root = tempDir.resolve("project");
        extractor.extractAndPersist(root, conn);

        List<CodeDependency> fooDeps = new CodeGraphRepository()
                .getDependenciesFrom(conn, root.toString(), "src/Foo.ts");
        CodeDependency dep = fooDeps.stream()
                .filter(d -> "Bar".equals(d.targetClass()))
                .findFirst().orElseThrow(() -> new AssertionError("expected edge to Bar: " + fooDeps));
        assertFalse(dep.isExternal(), ".js specifier must rewrite to the .ts file");
        assertEquals("src/Bar.ts", dep.targetFile());
    }

    @Test
    void extractAndPersist_ts_duplicate_specifier_deduped_to_one_edge() throws SQLException, IOException {
        Path src = Files.createDirectories(tempDir.resolve("project/src"));
        Files.writeString(src.resolve("Bar.ts"), "export const bar = 1;\nexport const baz = 2;\n");
        Files.writeString(src.resolve("Foo.ts"),
                "import { bar } from './Bar';\nimport { baz } from './Bar';\n");

        Path root = tempDir.resolve("project");
        extractor.extractAndPersist(root, conn);

        List<CodeDependency> fooDeps = new CodeGraphRepository()
                .getDependenciesFrom(conn, root.toString(), "src/Foo.ts");
        long barEdges = fooDeps.stream().filter(d -> "Bar".equals(d.targetClass())).count();
        assertEquals(1, barEdges, "the same specifier imported twice yields one edge: " + fooDeps);
    }

    @Test
    void extractAndPersist_ts_directory_index_import_resolves() throws SQLException, IOException {
        Path src = Files.createDirectories(tempDir.resolve("project/src"));
        Path widget = Files.createDirectories(src.resolve("widget"));
        Files.writeString(widget.resolve("index.ts"), "export const w = 1;\n");
        Files.writeString(src.resolve("Foo.ts"), "import { w } from './widget';\n");

        Path root = tempDir.resolve("project");
        extractor.extractAndPersist(root, conn);

        List<CodeDependency> fooDeps = new CodeGraphRepository()
                .getDependenciesFrom(conn, root.toString(), "src/Foo.ts");
        CodeDependency dep = fooDeps.stream()
                .filter(d -> "widget".equals(d.targetClass()))
                .findFirst().orElseThrow(() -> new AssertionError("expected edge to widget: " + fooDeps));
        assertFalse(dep.isExternal(), "directory import resolves to <dir>/index.ts");
        assertEquals("src/widget/index.ts", dep.targetFile());
    }

    @Test
    void extractAndPersist_ts_declaration_file_excluded() throws SQLException, IOException {
        Path src = Files.createDirectories(tempDir.resolve("project/src"));
        Files.writeString(src.resolve("Other.ts"), "export const x = 1;\n");
        Files.writeString(src.resolve("types.d.ts"), "import { X } from './Other';\n");

        Path root = tempDir.resolve("project");
        extractor.extractAndPersist(root, conn);

        List<CodeDependency> dtsDeps = new CodeGraphRepository()
                .getDependenciesFrom(conn, root.toString(), "src/types.d.ts");
        assertTrue(dtsDeps.isEmpty(), ".d.ts declaration files are excluded from extraction: " + dtsDeps);
    }

    // -----------------------------------------------------------------------
    // Stats reflect what was persisted, not upsert attempts (#469)
    //
    // code_dependencies is UNIQUE(workspace_path, source_file, target_class,
    // target_package) (V13__code_knowledge_graph.sql), so two edges that agree on
    // those columns collapse into one row on INSERT OR REPLACE. The counters must
    // report the surviving rows -- otherwise `code-graph extract` prints a larger
    // number than `code-graph extract --stats`, which reads countDependencies().
    // -----------------------------------------------------------------------

    @Test
    void extractAndPersist_dependenciesFound_equals_persisted_row_count() throws SQLException, IOException {
        Path src = Files.createDirectories(tempDir.resolve("project/src"));
        Files.writeString(src.resolve("Bar.ts"), "export const bar = 1;\n");
        // './Bar' and './Bar.js' both resolve to src/Bar.ts with targetClass "Bar":
        // two edges, one persisted row.
        Files.writeString(src.resolve("Foo.ts"),
                "import { bar } from './Bar';\nimport { bar as b2 } from './Bar.js';\n");

        Path root = tempDir.resolve("project");
        CodeGraphStats stats = extractor.extractAndPersist(root, conn);

        int persisted = new CodeGraphRepository().countDependencies(conn, root.toString());
        assertEquals(1, persisted, "the two specifiers collapse onto one row");
        assertEquals(persisted, stats.dependenciesFound(),
                "dependenciesFound must count persisted rows, not upsert attempts");
    }

    @Test
    void extractAndPersist_externalDeps_equals_persisted_external_row_count() throws SQLException, IOException {
        Path src = Files.createDirectories(tempDir.resolve("project/src"));
        // Two bare modules sharing a trailing segment -> same targetClass "util",
        // same (empty) targetPackage: two external edges, one persisted row.
        Files.writeString(src.resolve("Foo.ts"),
                "import a from 'pkg-one/util';\nimport b from 'pkg-two/util';\n");

        Path root = tempDir.resolve("project");
        CodeGraphStats stats = extractor.extractAndPersist(root, conn);

        int persistedExternal = countExternalRows(root);
        assertEquals(1, persistedExternal, "the two bare modules collapse onto one row");
        assertEquals(persistedExternal, stats.externalDeps(),
                "externalDeps must count persisted external rows, not upsert attempts");
        assertTrue(stats.externalDeps() <= stats.dependenciesFound(),
                "external rows are a subset of all rows");
    }

    @Test
    void incrementalUpdate_dependenciesFound_equals_persisted_row_count() throws SQLException, IOException {
        Path src = Files.createDirectories(tempDir.resolve("project/src"));
        Files.writeString(src.resolve("Bar.ts"), "export const bar = 1;\n");
        Path foo = src.resolve("Foo.ts");
        Files.writeString(foo, "import { bar } from './Bar';\nimport { bar as b2 } from './Bar.js';\n");

        Path root = tempDir.resolve("project");
        CodeGraphStats stats = extractor.incrementalUpdate(root, conn, Set.of(foo));

        int persisted = new CodeGraphRepository().countDependencies(conn, root.toString());
        assertEquals(1, persisted, "the two specifiers collapse onto one row");
        assertEquals(persisted, stats.dependenciesFound(),
                "incremental dependenciesFound must count persisted rows too");
    }

    /** Counts persisted {@code code_dependencies} rows flagged external for a workspace. */
    private int countExternalRows(Path workspaceRoot) throws SQLException {
        String sql = "SELECT COUNT(*) FROM code_dependencies "
                + "WHERE workspace_path = ? AND is_external = 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceRoot.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

}
