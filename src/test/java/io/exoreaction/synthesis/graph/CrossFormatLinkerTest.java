package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.index.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CrossFormatLinkerTest {

    private final CrossFormatLinker linker = new CrossFormatLinker();

    private SearchResult makeResult(String relPath, String fileName) {
        return new SearchResult(null, relPath, 1.0f, fileName, "java", "Java", null, null, null, 100L);
    }

    // -----------------------------------------------------------------------
    // SQL → Java
    // -----------------------------------------------------------------------

    @Test
    void extractTableNames_findsCreateTable(@TempDir Path tmp) throws IOException {
        Path sql = tmp.resolve("V1__create.sql");
        Files.writeString(sql, "CREATE TABLE users (id INTEGER);\nCREATE TABLE IF NOT EXISTS posts (id INTEGER);");
        SearchResult r = new SearchResult(sql, "V1__create.sql", 1.0f, "V1__create.sql", "sql", "SQL", null, null, null, 100L);
        List<String> tables = linker.extractTableNames(r, tmp);
        assertEquals(2, tables.size());
        assertTrue(tables.contains("users"));
        assertTrue(tables.contains("posts"));
    }

    @Test
    void extractTableNames_emptyForNonSql(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("config.yaml");
        Files.writeString(f, "key: value");
        SearchResult r = new SearchResult(f, "config.yaml", 1.0f, "config.yaml", "yaml", "YAML", null, null, null, 100L);
        List<String> tables = linker.extractTableNames(r, tmp);
        assertTrue(tables.isEmpty());
    }

    @Test
    void findSqlToJavaLinks_matchesByTableName(@TempDir Path tmp) throws IOException {
        Path sqlPath = tmp.resolve("V1__create.sql");
        Files.writeString(sqlPath, "CREATE TABLE users (id INTEGER PRIMARY KEY);");
        SearchResult sql = new SearchResult(sqlPath, "V1__create.sql", 1.0f, "V1__create.sql", "sql", "SQL", null, null, null, 100L);

        Path javaDir = tmp.resolve("src/main");
        Files.createDirectories(javaDir);
        Path javaPath = javaDir.resolve("UserRepository.java");
        Files.writeString(javaPath, "class UserRepository { String table = \"users\"; }");
        SearchResult java = new SearchResult(javaPath, "src/main/UserRepository.java", 1.0f, "UserRepository.java", "java", "Java", null, null, null, 200L);

        List<SearchResult> all = Arrays.asList(sql, java);
        List<CrossFormatLinker.CrossFormatLink> links = linker.findSqlToJavaLinks(sql, all, tmp);
        assertEquals(1, links.size());
        assertEquals("UserRepository.java", links.get(0).targetFile());
        assertEquals("table", links.get(0).linkType());
        assertEquals("users", links.get(0).entityName());
    }

    @Test
    void findSqlToJavaLinks_noMatchWhenTableNotReferenced(@TempDir Path tmp) throws IOException {
        Path sqlPath = tmp.resolve("V2__create.sql");
        Files.writeString(sqlPath, "CREATE TABLE orders (id INTEGER);");
        SearchResult sql = new SearchResult(sqlPath, "V2__create.sql", 1.0f, "V2__create.sql", "sql", "SQL", null, null, null, 100L);

        Path javaDir = tmp.resolve("src/main");
        Files.createDirectories(javaDir);
        Path javaPath = javaDir.resolve("SomeService.java");
        Files.writeString(javaPath, "class SomeService { /* no table reference */ }");
        SearchResult java = new SearchResult(javaPath, "src/main/SomeService.java", 1.0f, "SomeService.java", "java", "Java", null, null, null, 100L);

        List<SearchResult> all = Arrays.asList(sql, java);
        List<CrossFormatLinker.CrossFormatLink> links = linker.findSqlToJavaLinks(sql, all, tmp);
        assertTrue(links.isEmpty());
    }

    @Test
    void findSqlToJavaLinks_excludesTestFiles(@TempDir Path tmp) throws IOException {
        Path sqlPath = tmp.resolve("V3__create.sql");
        Files.writeString(sqlPath, "CREATE TABLE products (id INTEGER);");
        SearchResult sql = new SearchResult(sqlPath, "V3__create.sql", 1.0f, "V3__create.sql", "sql", "SQL", null, null, null, 100L);

        Path testDir = tmp.resolve("src/test");
        Files.createDirectories(testDir);
        Path testPath = testDir.resolve("ProductTest.java");
        Files.writeString(testPath, "class ProductTest { String t = \"products\"; }");
        SearchResult test = new SearchResult(testPath, "src/test/ProductTest.java", 1.0f, "ProductTest.java", "java", "Java", null, null, null, 100L);

        List<SearchResult> all = Arrays.asList(sql, test);
        List<CrossFormatLinker.CrossFormatLink> links = linker.findSqlToJavaLinks(sql, all, tmp);
        assertTrue(links.isEmpty(), "Test files should be excluded from SQL→Java links");
    }

    // -----------------------------------------------------------------------
    // YAML → Java
    // -----------------------------------------------------------------------

    @Test
    void findYamlToJavaLinks_matchesByStringLiteral(@TempDir Path tmp) throws IOException {
        Path yamlPath = tmp.resolve("application.yaml");
        Files.writeString(yamlPath, "database:\n  host: localhost\ntimeout: 30\n");
        SearchResult yaml = new SearchResult(yamlPath, "application.yaml", 1.0f, "application.yaml", "yaml", "YAML", null, null, null, 100L);

        Path javaDir = tmp.resolve("src/main");
        Files.createDirectories(javaDir);
        Path javaPath = javaDir.resolve("AppConfig.java");
        Files.writeString(javaPath, "class AppConfig { String k = \"database\"; }");
        SearchResult java = new SearchResult(javaPath, "src/main/AppConfig.java", 1.0f, "AppConfig.java", "java", "Java", null, null, null, 100L);

        List<SearchResult> all = Arrays.asList(yaml, java);
        List<CrossFormatLinker.CrossFormatLink> links = linker.findYamlToJavaLinks(yaml, all, tmp);
        assertEquals(1, links.size());
        assertEquals("config-key", links.get(0).linkType());
    }

    @Test
    void findYamlToJavaLinks_matchesBySpringExpression(@TempDir Path tmp) throws IOException {
        Path yamlPath = tmp.resolve("config.yml");
        Files.writeString(yamlPath, "server:\n  port: 8080\n");
        SearchResult yaml = new SearchResult(yamlPath, "config.yml", 1.0f, "config.yml", "yaml", "YAML", null, null, null, 100L);

        Path javaDir = tmp.resolve("src/main");
        Files.createDirectories(javaDir);
        Path javaPath = javaDir.resolve("ServerConfig.java");
        Files.writeString(javaPath, "@Value(\"${server}\")\nprivate int port;");
        SearchResult java = new SearchResult(javaPath, "src/main/ServerConfig.java", 1.0f, "ServerConfig.java", "java", "Java", null, null, null, 100L);

        List<SearchResult> all = Arrays.asList(yaml, java);
        List<CrossFormatLinker.CrossFormatLink> links = linker.findYamlToJavaLinks(yaml, all, tmp);
        assertEquals(1, links.size());
    }

    @Test
    void findYamlToJavaLinks_noMatchWhenKeyNotUsed(@TempDir Path tmp) throws IOException {
        Path yamlPath = tmp.resolve("settings.yaml");
        Files.writeString(yamlPath, "featureFlag: true\n");
        SearchResult yaml = new SearchResult(yamlPath, "settings.yaml", 1.0f, "settings.yaml", "yaml", "YAML", null, null, null, 100L);

        Path javaDir = tmp.resolve("src/main");
        Files.createDirectories(javaDir);
        Path javaPath = javaDir.resolve("SomeBean.java");
        Files.writeString(javaPath, "class SomeBean { /* no config reference */ }");
        SearchResult java = new SearchResult(javaPath, "src/main/SomeBean.java", 1.0f, "SomeBean.java", "java", "Java", null, null, null, 100L);

        List<SearchResult> all = Arrays.asList(yaml, java);
        List<CrossFormatLinker.CrossFormatLink> links = linker.findYamlToJavaLinks(yaml, all, tmp);
        assertTrue(links.isEmpty());
    }

    @Test
    void findYamlToJavaLinks_excludesTestFiles(@TempDir Path tmp) throws IOException {
        // The SQL direction has always excluded src/test (findSqlToJavaLinks_excludesTestFiles),
        // and the incremental persistence path excludes it too. YAML matching test sources would
        // make the two persistence paths disagree about the same file -- the divergence #464
        // exists to remove.
        Path yamlPath = tmp.resolve("application.yaml");
        Files.writeString(yamlPath, "database:\n  host: localhost\n");
        SearchResult yaml = new SearchResult(yamlPath, "application.yaml", 1.0f, "application.yaml", "yaml", "YAML", null, null, null, 100L);

        Path testDir = tmp.resolve("src/test");
        Files.createDirectories(testDir);
        Path testPath = testDir.resolve("AppConfigTest.java");
        Files.writeString(testPath, "class AppConfigTest { String k = \"database\"; }");
        SearchResult test = new SearchResult(testPath, "src/test/AppConfigTest.java", 1.0f, "AppConfigTest.java", "java", "Java", null, null, null, 100L);

        List<SearchResult> all = Arrays.asList(yaml, test);
        List<CrossFormatLinker.CrossFormatLink> links = linker.findYamlToJavaLinks(yaml, all, tmp);
        assertTrue(links.isEmpty(), "Test files should be excluded from YAML→Java links");
    }

    // -----------------------------------------------------------------------
    // Config-key extraction and matching, reusable by the persistence path (#464)
    // -----------------------------------------------------------------------

    @Test
    void extractConfigKeys_findsTopLevelKeys(@TempDir Path tmp) throws IOException {
        Path yamlPath = tmp.resolve("application.yaml");
        Files.writeString(yamlPath, "database:\n  host: localhost\ntimeout: 30\n");
        SearchResult yaml = new SearchResult(yamlPath, "application.yaml", 1.0f, "application.yaml", "yaml", "YAML", null, null, null, 100L);

        List<String> keys = linker.extractConfigKeys(yaml, tmp);
        assertEquals(List.of("database", "timeout"), keys);
    }

    @Test
    void extractConfigKeys_skipsShortKeysAndLiteralWords(@TempDir Path tmp) throws IOException {
        // Two characters is the documented floor, and the YAML literals would match half the
        // Java in a repo.
        Path yamlPath = tmp.resolve("application.yaml");
        Files.writeString(yamlPath, "db: 1\ndbx: 2\ntrue: a\nfalse: b\nnull: c\nyes: d\nno: e\n");
        SearchResult yaml = new SearchResult(yamlPath, "application.yaml", 1.0f, "application.yaml", "yaml", "YAML", null, null, null, 100L);

        assertEquals(List.of("dbx"), linker.extractConfigKeys(yaml, tmp));
    }

    @Test
    void extractConfigKeys_emptyWhenNoTopLevelKeys(@TempDir Path tmp) throws IOException {
        Path yamlPath = tmp.resolve("comments.yaml");
        Files.writeString(yamlPath, "# nothing but a comment\n  indented: value\n");
        SearchResult yaml = new SearchResult(yamlPath, "comments.yaml", 1.0f, "comments.yaml", "yaml", "YAML", null, null, null, 100L);

        assertTrue(linker.extractConfigKeys(yaml, tmp).isEmpty());
    }

    @Test
    void referencesConfigKey_matchesLiteralPlaceholderAndLookup() {
        assertTrue(CrossFormatLinker.referencesConfigKey("String k = \"database\";", "database"));
        assertTrue(CrossFormatLinker.referencesConfigKey("@Value(\"${database}\")", "database"));
        assertTrue(CrossFormatLinker.referencesConfigKey("cfg.get(\"database.host\")", "database"));
    }

    @Test
    void referencesConfigKey_isCaseSensitiveAndNeedsADelimiter() {
        assertFalse(CrossFormatLinker.referencesConfigKey("String k = \"DATABASE\";", "database"),
                "config keys are matched verbatim, unlike table names");
        assertFalse(CrossFormatLinker.referencesConfigKey("String k = database;", "database"),
                "a bare identifier is not a config-key reference");
    }

    // -----------------------------------------------------------------------
    // Type detection
    // -----------------------------------------------------------------------

    @Test
    void isSqlFile_detectsCorrectly() {
        SearchResult sql = makeResult("db/V1__init.sql", "V1__init.sql");
        SearchResult yaml = makeResult("cfg.yaml", "cfg.yaml");
        assertTrue(linker.isSqlFile(sql));
        assertFalse(linker.isSqlFile(yaml));
    }

    @Test
    void isConfigYamlFile_detectsBothExtensions() {
        SearchResult yaml = makeResult("conf/a.yaml", "a.yaml");
        SearchResult yml = makeResult("conf/b.yml", "b.yml");
        SearchResult java = makeResult("C.java", "C.java");
        assertTrue(linker.isConfigYamlFile(yaml));
        assertTrue(linker.isConfigYamlFile(yml));
        assertFalse(linker.isConfigYamlFile(java));
    }

    @Test
    void isConfigYamlFile_rejectsAYamlThatIsNotConfiguration() {
        SearchResult manifest = makeResult("knowledge.yaml", "knowledge.yaml");
        assertFalse(linker.isConfigYamlFile(manifest),
                "relate must not offer cross-format links for a YAML that is not config (#506)");
    }

    // -----------------------------------------------------------------------
    // Binary / non-UTF-8 file handling (issue #252)
    // -----------------------------------------------------------------------

    @Test
    void extractTableNames_returnEmptyForBinaryFile(@TempDir Path tmp) throws IOException {
        // Write a file with raw binary content that cannot be decoded as UTF-8
        Path binaryFile = tmp.resolve("V99__binary.sql");
        Files.write(binaryFile, new byte[]{(byte)0xFF, (byte)0xFE, (byte)0x00, (byte)0x01,
                (byte)0xD8, (byte)0x00, (byte)0xDC, (byte)0x00}); // invalid UTF-8 sequence
        SearchResult r = new SearchResult(binaryFile, "V99__binary.sql", 1.0f,
                "V99__binary.sql", "sql", "SQL", null, null, null, 100L);

        // Must not throw; should return empty list gracefully
        List<String> tables = linker.extractTableNames(r, tmp);
        assertTrue(tables.isEmpty(), "Binary file should yield no table names without throwing");
    }

    @Test
    void findSqlToJavaLinks_skipsNonUtf8SqlFile(@TempDir Path tmp) throws IOException {
        // Binary content masquerading as a SQL file
        Path binaryFile = tmp.resolve("V98__bad.sql");
        Files.write(binaryFile, new byte[]{(byte)0x89, (byte)0x50, (byte)0x4E, (byte)0x47,
                (byte)0x0D, (byte)0x0A, (byte)0x1A, (byte)0x0A}); // PNG magic bytes
        SearchResult sql = new SearchResult(binaryFile, "V98__bad.sql", 1.0f,
                "V98__bad.sql", "sql", "SQL", null, null, null, 100L);

        Path javaDir = tmp.resolve("src/main");
        Files.createDirectories(javaDir);
        Path javaPath = javaDir.resolve("Foo.java");
        Files.writeString(javaPath, "class Foo {}");
        SearchResult java = new SearchResult(javaPath, "src/main/Foo.java", 1.0f,
                "Foo.java", "java", "Java", null, null, null, 100L);

        // Must not throw; returns empty because no tables could be extracted
        List<CrossFormatLinker.CrossFormatLink> links =
                linker.findSqlToJavaLinks(sql, List.of(sql, java), tmp);
        assertTrue(links.isEmpty(), "Binary SQL file should produce no links without throwing");
    }

    @Test
    void findYamlToJavaLinks_skipsNonUtf8YamlFile(@TempDir Path tmp) throws IOException {
        // Binary content masquerading as a YAML file
        Path binaryFile = tmp.resolve("config.yaml");
        Files.write(binaryFile, new byte[]{(byte)0xFF, (byte)0xD8, (byte)0xFF, (byte)0xE0,
                (byte)0x00, (byte)0x10, (byte)0x4A, (byte)0x46}); // JPEG magic bytes
        SearchResult yaml = new SearchResult(binaryFile, "config.yaml", 1.0f,
                "config.yaml", "yaml", "YAML", null, null, null, 100L);

        Path javaDir = tmp.resolve("src/main");
        Files.createDirectories(javaDir);
        Path javaPath = javaDir.resolve("Bar.java");
        Files.writeString(javaPath, "class Bar {}");
        SearchResult java = new SearchResult(javaPath, "src/main/Bar.java", 1.0f,
                "Bar.java", "java", "Java", null, null, null, 100L);

        // Must not throw; returns empty because YAML could not be read
        List<CrossFormatLinker.CrossFormatLink> links =
                linker.findYamlToJavaLinks(yaml, List.of(yaml, java), tmp);
        assertTrue(links.isEmpty(), "Binary YAML file should produce no links without throwing");
    }

    // -----------------------------------------------------------------------
    // Extension-based and size-based filtering (OOM prevention)
    // -----------------------------------------------------------------------

    @Test
    void isReadableTextFile_acceptsTextExtensions(@TempDir Path tmp) throws IOException {
        for (String ext : List.of(".java", ".sql", ".yaml", ".yml", ".json", ".xml",
                                   ".properties", ".gradle", ".kts", ".groovy", ".scala",
                                   ".ts", ".js", ".kt", ".md", ".txt")) {
            Path f = tmp.resolve("test" + ext);
            Files.writeString(f, "content");
            assertTrue(CrossFormatLinker.isReadableTextFile(f),
                    "Should accept text extension: " + ext);
        }
    }

    @Test
    void isReadableTextFile_rejectsBinaryExtensions(@TempDir Path tmp) throws IOException {
        for (String ext : List.of(".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico",
                                   ".pdf", ".zip", ".tar", ".gz", ".jar", ".class",
                                   ".exe", ".dll", ".so", ".dylib", ".woff", ".woff2")) {
            Path f = tmp.resolve("test" + ext);
            Files.write(f, new byte[]{0x00, 0x01, 0x02, 0x03});
            assertFalse(CrossFormatLinker.isReadableTextFile(f),
                    "Should reject binary extension: " + ext);
        }
    }

    @Test
    void isReadableTextFile_rejectsOversizedTextFile(@TempDir Path tmp) throws IOException {
        Path bigFile = tmp.resolve("huge.sql");
        // Write a file just over the 2MB limit
        byte[] data = new byte[(int)(CrossFormatLinker.MAX_TEXT_FILE_SIZE + 1)];
        java.util.Arrays.fill(data, (byte) 'x');
        Files.write(bigFile, data);

        assertFalse(CrossFormatLinker.isReadableTextFile(bigFile),
                "Should reject text file larger than MAX_TEXT_FILE_SIZE");
    }

    @Test
    void isReadableTextFile_acceptsTextFileUnderLimit(@TempDir Path tmp) throws IOException {
        Path smallSql = tmp.resolve("small.sql");
        Files.writeString(smallSql, "CREATE TABLE test (id INT);");
        assertTrue(CrossFormatLinker.isReadableTextFile(smallSql),
                "Should accept small text file");
    }

    @Test
    void isReadableTextFile_rejectsFileWithNoExtension(@TempDir Path tmp) throws IOException {
        Path noExt = tmp.resolve("Makefile");
        Files.writeString(noExt, "all: build");
        assertFalse(CrossFormatLinker.isReadableTextFile(noExt),
                "Should reject file with no extension");
    }

    @Test
    void extractTableNames_skipsLargePngNamedAsSql(@TempDir Path tmp) throws IOException {
        // Simulate the actual OOM scenario: a large PNG file that would cause
        // OutOfMemoryError if read as a UTF-16 string
        Path pngFile = tmp.resolve("screenshot.png");
        byte[] pngHeader = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        Files.write(pngFile, pngHeader);

        SearchResult r = new SearchResult(pngFile, "screenshot.png", 1.0f,
                "screenshot.png", "IMAGE", "PNG", null, null, null, pngHeader.length);

        List<String> tables = linker.extractTableNames(r, tmp);
        assertTrue(tables.isEmpty(), "PNG file should be skipped by extension check");
    }

    @Test
    void findSqlToJavaLinks_skipsLargeBinaryFilesInJavaList(@TempDir Path tmp) throws IOException {
        // Create a valid SQL file with a table reference
        Path sqlPath = tmp.resolve("V1__create.sql");
        Files.writeString(sqlPath, "CREATE TABLE users (id INTEGER PRIMARY KEY);");
        SearchResult sql = new SearchResult(sqlPath, "V1__create.sql", 1.0f,
                "V1__create.sql", "sql", "SQL", null, null, null, 100L);

        // Create a normal Java file that references the table
        Path javaDir = tmp.resolve("src/main");
        Files.createDirectories(javaDir);
        Path javaPath = javaDir.resolve("UserRepo.java");
        Files.writeString(javaPath, "class UserRepo { String t = \"users\"; }");
        SearchResult javaResult = new SearchResult(javaPath, "src/main/UserRepo.java", 1.0f,
                "UserRepo.java", "java", "Java", null, null, null, 100L);

        // Create an oversized .java file (> 2MB) that should be skipped
        Path bigJavaDir = tmp.resolve("src/main/gen");
        Files.createDirectories(bigJavaDir);
        Path bigJavaPath = bigJavaDir.resolve("Generated.java");
        byte[] bigContent = new byte[(int)(CrossFormatLinker.MAX_TEXT_FILE_SIZE + 1)];
        java.util.Arrays.fill(bigContent, (byte) 'x');
        Files.write(bigJavaPath, bigContent);
        SearchResult bigJava = new SearchResult(bigJavaPath, "src/main/gen/Generated.java", 1.0f,
                "Generated.java", "java", "Java", null, null, null, bigContent.length);

        List<SearchResult> all = Arrays.asList(sql, javaResult, bigJava);
        // Should not OOM; should find the normal match but skip the oversized file
        List<CrossFormatLinker.CrossFormatLink> links = linker.findSqlToJavaLinks(sql, all, tmp);
        assertEquals(1, links.size(), "Should find link from normal Java file");
        assertEquals("UserRepo.java", links.get(0).targetFile());
    }

    @Test
    void findYamlToJavaLinks_skipsPngFile(@TempDir Path tmp) throws IOException {
        // A PNG file with .yaml extension should be caught by the IOException handler
        // But a PNG file with .png extension should be caught by extension check first
        Path pngFile = tmp.resolve("diagram.png");
        Files.write(pngFile, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        SearchResult png = new SearchResult(pngFile, "diagram.png", 1.0f,
                "diagram.png", "IMAGE", "PNG", null, null, null, 8L);

        Path javaDir = tmp.resolve("src/main");
        Files.createDirectories(javaDir);
        Path javaPath = javaDir.resolve("Baz.java");
        Files.writeString(javaPath, "class Baz {}");
        SearchResult java = new SearchResult(javaPath, "src/main/Baz.java", 1.0f,
                "Baz.java", "java", "Java", null, null, null, 100L);

        // Should not throw OOM or any other error
        List<CrossFormatLinker.CrossFormatLink> links =
                linker.findYamlToJavaLinks(png, List.of(png, java), tmp);
        assertTrue(links.isEmpty(), "PNG file should produce no YAML links");
    }

    // -----------------------------------------------------------------------
    // Config-YAML recognition (#506)
    // -----------------------------------------------------------------------
    //
    // Case-class ledger for isConfigYaml, per the 8 classes in verification-patterns:
    //   1 Empty       — isConfigYaml_rejectsEmptyPath
    //   2 Absent      — N/A: every caller derives the argument from a file it has just
    //                   listed, so null never reaches here. Its sibling isTestPath makes
    //                   the same assumption.
    //   3 Single      — isConfigYaml_acceptsConfigFileAtWorkspaceRoot
    //   4 Boundary    — isConfigYaml_rejectsExtensionWithoutABaseName
    //   5 Invalid     — isConfigYaml_rejectsNonYamlExtensions
    //   6 Position    — isConfigYaml_acceptsAConfigDirectoryAtAnyDepth
    //   7 Unavailable — N/A: a pure predicate over its argument, with no dependency to fail.
    //   8 Normal      — isConfigYaml_rejectsManifestsThatAreNotConfiguration

    @Test
    void isConfigYaml_acceptsConfigFileAtWorkspaceRoot() {
        assertTrue(CrossFormatLinker.isConfigYaml("config.yaml"));
        assertTrue(CrossFormatLinker.isConfigYaml("synthesis-config.yaml"));
        assertTrue(CrossFormatLinker.isConfigYaml("application.yaml"));
        assertTrue(CrossFormatLinker.isConfigYaml("application-prod.yml"));
    }

    @Test
    void isConfigYaml_acceptsAConfigDirectoryAtAnyDepth() {
        assertTrue(CrossFormatLinker.isConfigYaml("configs/synapti-marketplace.yaml"),
                "a directory named configs marks its contents as configuration");
        assertTrue(CrossFormatLinker.isConfigYaml("conf/database.yml"));
        assertTrue(CrossFormatLinker.isConfigYaml("src/main/resources/config/routes.yaml"));
        assertTrue(CrossFormatLinker.isConfigYaml("deploy/configuration/limits.yaml"));
    }

    @Test
    void isConfigYaml_matchesTheDirectoryAsAWholeSegment() {
        assertFalse(CrossFormatLinker.isConfigYaml("confetti/party.yaml"),
                "confetti merely starts with conf and is not a config directory");
        assertFalse(CrossFormatLinker.isConfigYaml("preconfig/app.yaml"),
                "preconfig merely ends with config and is not a config directory");
    }

    @Test
    void isConfigYaml_rejectsManifestsThatAreNotConfiguration() {
        assertFalse(CrossFormatLinker.isConfigYaml("knowledge.yaml"),
                "a KCP manifest declares knowledge units, it is not read as configuration");
        assertFalse(CrossFormatLinker.isConfigYaml(
                        "src/main/resources/claude-skills/synthesis-summary.yaml"),
                "a skill manifest is not read as configuration");
        assertFalse(CrossFormatLinker.isConfigYaml("mkdocs.yml"));
        assertFalse(CrossFormatLinker.isConfigYaml("docs/slack-app-manifest.yaml"));
    }

    @Test
    void isConfigYaml_rejectsNonYamlExtensions() {
        assertFalse(CrossFormatLinker.isConfigYaml("config.json"));
        assertFalse(CrossFormatLinker.isConfigYaml("config.yaml.bak"));
        assertFalse(CrossFormatLinker.isConfigYaml("conf/V1__init.sql"));
    }

    @Test
    void isConfigYaml_rejectsEmptyPath() {
        assertFalse(CrossFormatLinker.isConfigYaml(""));
    }

    @Test
    void isConfigYaml_rejectsExtensionWithoutABaseName() {
        assertFalse(CrossFormatLinker.isConfigYaml(".yaml"),
                "a bare extension names no file and cannot be recognized as configuration");
        assertFalse(CrossFormatLinker.isConfigYaml("conf/.yml"));
    }

    @Test
    void isConfigYaml_readsADirectoryOnAWindowsPathToo() {
        assertTrue(CrossFormatLinker.isConfigYaml("conf\\database.yml"),
                "the directory rule must survive a backslash separator");
    }

    @Test
    void isConfigYaml_matchesCaseInsensitively() {
        assertTrue(CrossFormatLinker.isConfigYaml("conf/CONFIG.YAML"));
        assertTrue(CrossFormatLinker.isConfigYaml("Conf/App.Yml"));
        assertTrue(CrossFormatLinker.isConfigYaml("Application.YAML"));
    }
}
