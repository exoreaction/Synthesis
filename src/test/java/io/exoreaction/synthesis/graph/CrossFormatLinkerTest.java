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
    void isYamlFile_detectsBothExtensions() {
        SearchResult yaml = makeResult("a.yaml", "a.yaml");
        SearchResult yml = makeResult("b.yml", "b.yml");
        SearchResult java = makeResult("C.java", "C.java");
        assertTrue(linker.isYamlFile(yaml));
        assertTrue(linker.isYamlFile(yml));
        assertFalse(linker.isYamlFile(java));
    }
}
