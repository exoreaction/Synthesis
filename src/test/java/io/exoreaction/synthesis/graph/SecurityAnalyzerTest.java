package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.db.SynthesisDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SecurityAnalyzer} -- security signal detection.
 *
 * <p>Each signal is tested with synthetic Java source that both triggers
 * and does NOT trigger the detection.
 *
 * @since v1.14.0 (Security)
 */
class SecurityAnalyzerTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private Connection conn;
    private SecurityAnalyzer analyzer;

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        conn = db.getConnection();
        analyzer = new SecurityAnalyzer(new SecurityRepository());
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    // -----------------------------------------------------------------------
    // S001: SQL Injection
    // -----------------------------------------------------------------------

    @Test
    void s001_detects_sql_concatenation() {
        String code = """
                package com.example.db;
                class Dao {
                    void query(String table) {
                        String sql = "SELECT * FROM " + table;
                        stmt.executeQuery(sql);
                    }
                }
                """;
        List<SecuritySignal> signals = analyzer.checkS001(code, "src/Dao.java", "Dao", "com.example.db");
        assertTrue(signals.stream().anyMatch(s -> s.signalId().equals("S001_SQL_INJECTION")),
                "Should detect SQL string concatenation");
        assertEquals("HIGH", signals.get(0).severity());
        assertEquals("CWE-89", signals.get(0).cweId());
    }

    @Test
    void s001_ignores_prepared_statement() {
        String code = """
                package com.example.db;
                class Dao {
                    void query(String id) {
                        PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE id = ?");
                        ps.setString(1, id);
                    }
                }
                """;
        List<SecuritySignal> signals = analyzer.checkS001(code, "src/Dao.java", "Dao", "com.example.db");
        assertTrue(signals.isEmpty(), "PreparedStatement should not trigger S001");
    }

    // Regression #238: S001 should not fire on non-SQL execute() calls
    @Test
    void s001_ignores_executor_service_execute_with_concatenation() {
        String code = """
                package com.example;
                import java.util.concurrent.ExecutorService;
                class TaskRunner {
                    void run(ExecutorService executor, String taskName) {
                        executor.execute(() -> System.out.println("Running: " + taskName));
                    }
                }
                """;
        List<SecuritySignal> signals = analyzer.checkS001(code, "src/TaskRunner.java", "TaskRunner", "com.example");
        assertTrue(signals.isEmpty(),
                "ExecutorService.execute() with concatenation must not trigger S001 — no SQL context (#238)");
    }

    @Test
    void s001_ignores_non_sql_file_with_execute_method() {
        String code = """
                package com.example.pipeline;
                class StepRunner {
                    void execute(String stepName) {
                        log("Executing step: " + stepName);
                    }
                }
                """;
        List<SecuritySignal> signals = analyzer.checkS001(code, "src/StepRunner.java", "StepRunner", "com.example.pipeline");
        assertTrue(signals.isEmpty(),
                "File with no SQL API imports should not trigger S001 on generic execute() (#238)");
    }

    // -----------------------------------------------------------------------
    // S002: Hardcoded Secrets
    // -----------------------------------------------------------------------

    @Test
    void s002_detects_hardcoded_password() {
        String code = """
                package com.example;
                class Config {
                    private static final String dbPassword = "s3cretP@ssw0rd";
                }
                """;
        List<SecuritySignal> signals = analyzer.checkS002(code, "src/Config.java", "Config", "com.example");
        assertTrue(signals.stream().anyMatch(s -> s.signalId().equals("S002_HARDCODED_SECRET")),
                "Should detect hardcoded password");
    }

    @Test
    void s002_ignores_test_values() {
        String code = """
                package com.example;
                class Config {
                    private static final String testPassword = "dummy_test_value";
                }
                """;
        List<SecuritySignal> signals = analyzer.checkS002(code, "src/Config.java", "Config", "com.example");
        assertTrue(signals.isEmpty(), "Test/dummy values should not trigger S002");
    }

    // Regression #239: Pattern.compile() line must not trigger S002 (self-triggering)
    @Test
    void s002_ignores_pattern_compile_definition_line() {
        String code = """
                package io.exoreaction.synthesis.graph;
                import java.util.regex.Pattern;
                class Detector {
                    private static final Pattern SECRET_PASSWORD = Pattern.compile(
                            "password\\s*=\\s*\\"[^\\"]{4,}\\"", Pattern.CASE_INSENSITIVE);
                }
                """;
        List<SecuritySignal> signals = analyzer.checkS002(code, "src/Detector.java", "Detector",
                "io.exoreaction.synthesis.graph");
        assertTrue(signals.isEmpty(),
                "Pattern.compile() definition containing 'password' must not trigger S002 (#239)");
    }

    // Regression #244: multi-line Pattern.compile() — string literal on continuation line
    @Test
    void s002_ignores_multiline_pattern_compile_continuation_with_metacharacters() {
        // "-----BEGIN.*PRIVATE KEY" is on its own line (continuation), contains .* metacharacter
        String code = "package io.exoreaction.synthesis.graph;\n"
                + "import java.util.regex.Pattern;\n"
                + "class Detector {\n"
                + "    private static final Pattern SECRET_PRIVATE_KEY = Pattern.compile(\n"
                + "            \"-----BEGIN.*PRIVATE KEY\");\n"
                + "}\n";
        List<SecuritySignal> signals = analyzer.checkS002(code, "src/Detector.java", "Detector",
                "io.exoreaction.synthesis.graph");
        assertTrue(signals.isEmpty(),
                "Regex pattern string on continuation line must not trigger S002 — metacharacters identify it as a pattern definition (#244)");
    }

    @Test
    void s002_ignores_line_with_regex_metacharacter_star() {
        // A line with .* in the string value is a regex, not a real credential
        String code = "package io.exoreaction.synthesis.graph;\n"
                + "class Foo {\n"
                + "    String pattern = \"api[_\\\\-]?key\\\\s*=\\\\s*.*\";\n"
                + "}\n";
        List<SecuritySignal> signals = analyzer.checkS002(code, "src/Foo.java", "Foo",
                "io.exoreaction.synthesis.graph");
        assertTrue(signals.isEmpty(),
                "String with .* metacharacter must not trigger S002 as a hardcoded secret (#244)");
    }

    // -----------------------------------------------------------------------
    // S003: Weak Cryptography
    // -----------------------------------------------------------------------

    @Test
    void s003_detects_md5() {
        String code = """
                package com.example;
                class Hasher {
                    void hash() {
                        MessageDigest md = MessageDigest.getInstance("MD5");
                    }
                }
                """;
        List<SecuritySignal> signals = analyzer.checkS003(code, "src/Hasher.java", "Hasher", "com.example");
        assertTrue(signals.stream().anyMatch(s -> s.signalId().equals("S003_WEAK_CRYPTO")),
                "Should detect MD5 usage");
        assertEquals("MEDIUM", signals.get(0).severity());
    }

    @Test
    void s003_ignores_sha256() {
        String code = """
                package com.example;
                class Hasher {
                    void hash() {
                        MessageDigest md = MessageDigest.getInstance("SHA-256");
                    }
                }
                """;
        List<SecuritySignal> signals = analyzer.checkS003(code, "src/Hasher.java", "Hasher", "com.example");
        assertTrue(signals.isEmpty(), "SHA-256 should not trigger S003");
    }

    // -----------------------------------------------------------------------
    // S007: Unsafe Deserialization
    // -----------------------------------------------------------------------

    @Test
    void s007_detects_object_input_stream() {
        String code = """
                package com.example;
                class Loader {
                    void load(InputStream is) {
                        ObjectInputStream ois = new ObjectInputStream(is);
                        Object obj = ois.readObject();
                    }
                }
                """;
        List<SecuritySignal> signals = analyzer.checkS007(code, "src/Loader.java", "Loader", "com.example");
        assertFalse(signals.isEmpty(), "Should detect ObjectInputStream usage");
        assertEquals("S007_UNSAFE_DESERIALIZATION", signals.get(0).signalId());
    }

    @Test
    void s007_ignores_with_filter() {
        String code = """
                package com.example;
                class Loader {
                    void load(InputStream is) {
                        ObjectInputFilter filter = ObjectInputFilter.Config.createFilter("*");
                        ObjectInputStream ois = new ObjectInputStream(is);
                    }
                }
                """;
        List<SecuritySignal> signals = analyzer.checkS007(code, "src/Loader.java", "Loader", "com.example");
        assertTrue(signals.isEmpty(), "ObjectInputFilter should suppress S007");
    }

    // -----------------------------------------------------------------------
    // S016: Direct Prompt Injection
    // -----------------------------------------------------------------------

    @Test
    void s016_detects_user_input_in_prompt() {
        String code = """
                package io.exoreaction.synthesis.ai;
                class Asker {
                    String ask(String question) {
                        return buildPrompt(question);
                    }
                    String buildPrompt(String q) { return q; }
                }
                """;
        List<SecuritySignal> signals = analyzer.checkS016(code, "src/Asker.java", "Asker", "io.exoreaction.synthesis.ai");
        assertTrue(signals.stream().anyMatch(s -> s.signalId().equals("S016_DIRECT_PROMPT_INJECTION")),
                "Should detect user input flowing into prompt");
        SecuritySignal s = signals.get(0);
        assertEquals("HIGH", s.severity());
        assertEquals("direct", s.flowType());
    }

    @Test
    void s016_ignores_non_ai_package() {
        String code = """
                package com.example.util;
                class Helper {
                    String help(String question) {
                        return buildPrompt(question);
                    }
                    String buildPrompt(String q) { return q; }
                }
                """;
        List<SecuritySignal> signals = analyzer.checkS016(code, "src/Helper.java", "Helper", "com.example.util");
        assertTrue(signals.isEmpty(), "Non-AI package should not trigger S016");
    }

    // -----------------------------------------------------------------------
    // S017: RAG Poisoning
    // -----------------------------------------------------------------------

    @Test
    void s017_detects_search_read_prompt_chain() {
        String code = """
                package io.exoreaction.synthesis.ai;
                class RagPipeline {
                    void run() {
                        var results = index.search("query");
                        String content = readPreview(results.get(0));
                        String prompt = buildPrompt(content);
                        client.generate(prompt);
                    }
                }
                """;
        List<SecuritySignal> signals = analyzer.checkS017(code, "src/RagPipeline.java", "RagPipeline", "io.exoreaction.synthesis.ai");
        assertTrue(signals.stream().anyMatch(s -> s.signalId().equals("S017_RAG_POISONING")),
                "Should detect search->read->prompt chain");
        assertEquals("indirect", signals.get(0).flowType());
    }

    @Test
    void s017_ignores_partial_chain() {
        String code = """
                package io.exoreaction.synthesis.ai;
                class SearchOnly {
                    void run() {
                        var results = index.search("query");
                        // No read or prompt
                    }
                }
                """;
        List<SecuritySignal> signals = analyzer.checkS017(code, "src/SearchOnly.java", "SearchOnly", "io.exoreaction.synthesis.ai");
        assertTrue(signals.isEmpty(), "Partial chain should not trigger S017");
    }

    // -----------------------------------------------------------------------
    // S018: Unconfirmed Agentic Action
    // -----------------------------------------------------------------------

    @Test
    void s018_detects_mcp_write_without_dryrun() {
        String code = """
                package io.exoreaction.synthesis.mcp;
                class ToolHandler {
                    void handleWrite(Map params) {
                        Path path = Path.of(params.get("path").toString());
                        Files.write(path, content.getBytes());
                    }
                }
                """;
        List<SecuritySignal> signals = analyzer.checkS018(code, "src/ToolHandler.java", "ToolHandler", "io.exoreaction.synthesis.mcp");
        assertTrue(signals.stream().anyMatch(s -> s.signalId().equals("S018_UNCONFIRMED_AGENTIC_ACTION")),
                "Should detect write without dryRun check");
        assertEquals("agentic", signals.get(0).flowType());
    }

    @Test
    void s018_ignores_with_dryrun() {
        String code = """
                package io.exoreaction.synthesis.mcp;
                class ToolHandler {
                    void handleWrite(Map params) {
                        boolean dryRun = params.containsKey("dryRun");
                        if (!dryRun) {
                            Files.write(path, content.getBytes());
                        }
                    }
                }
                """;
        List<SecuritySignal> signals = analyzer.checkS018(code, "src/ToolHandler.java", "ToolHandler", "io.exoreaction.synthesis.mcp");
        assertTrue(signals.isEmpty(), "dryRun check should suppress S018");
    }

    // -----------------------------------------------------------------------
    // S019: Unvalidated Agentic Path
    // -----------------------------------------------------------------------

    @Test
    void s019_detects_path_from_params_without_check() {
        String code = """
                package io.exoreaction.synthesis.mcp;
                class FileHandler {
                    void handleRead(Map params) {
                        Path path = Path.of(params.get("path").asText());
                        String content = Files.readString(path);
                    }
                }
                """;
        List<SecuritySignal> signals = analyzer.checkS019(code, "src/FileHandler.java", "FileHandler", "io.exoreaction.synthesis.mcp");
        assertTrue(signals.stream().anyMatch(s -> s.signalId().equals("S019_UNVALIDATED_AGENTIC_PATH")),
                "Should detect path from params without startsWith check");
    }

    @Test
    void s019_ignores_with_containment_check() {
        String code = """
                package io.exoreaction.synthesis.mcp;
                class FileHandler {
                    void handleRead(Map params) {
                        Path path = Path.of(params.get("path").asText());
                        if (!path.startsWith(allowedRoot)) throw new SecurityException();
                        String content = Files.readString(path);
                    }
                }
                """;
        List<SecuritySignal> signals = analyzer.checkS019(code, "src/FileHandler.java", "FileHandler", "io.exoreaction.synthesis.mcp");
        assertTrue(signals.isEmpty(), "startsWith containment check should suppress S019");
    }

    // -----------------------------------------------------------------------
    // S021: Missing Prompt Boundaries
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // S011: Overly Broad Catch — regression #241
    // -----------------------------------------------------------------------

    // Regression #241: S011 must only fire for security-boundary packages, not all files
    @Test
    void s011_ignores_broad_catch_in_non_security_package() {
        String code = """
                package io.exoreaction.synthesis.util;
                class StringUtils {
                    void process() {
                        try {
                            doWork();
                        } catch (Exception e) {
                            log.warn("error", e);
                        }
                    }
                }
                """;
        List<SecuritySignal> signals = analyzer.checkS011(code, "src/StringUtils.java", "StringUtils",
                "io.exoreaction.synthesis.util");
        assertTrue(signals.isEmpty(),
                "Broad catch in a non-security package must not trigger S011 (#241)");
    }

    @Test
    void s011_ignores_broad_catch_in_db_package() {
        String code = """
                package io.exoreaction.synthesis.db;
                class Repository {
                    void query() {
                        try {
                            ps.executeQuery();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
                """;
        List<SecuritySignal> signals = analyzer.checkS011(code, "src/Repository.java", "Repository",
                "io.exoreaction.synthesis.db");
        assertTrue(signals.isEmpty(),
                "Broad catch in db package must not trigger S011 even if it mentions security words (#241)");
    }

    @Test
    void s011_detects_broad_catch_in_mcp_package() {
        String code = """
                package io.exoreaction.synthesis.mcp;
                class ToolHandler {
                    void handleSecure(String token) {
                        try {
                            authenticate(token);
                        } catch (Exception e) {
                            log.warn("auth failed", e);
                        }
                    }
                }
                """;
        List<SecuritySignal> signals = analyzer.checkS011(code, "src/ToolHandler.java", "ToolHandler",
                "io.exoreaction.synthesis.mcp");
        assertTrue(signals.stream().anyMatch(s -> s.signalId().equals("S011_OVERLY_BROAD_CATCH")),
                "Broad catch in mcp package must trigger S011 (#241)");
    }

    @Test
    void s021_detects_prompt_without_boundaries() {
        String code = """
                package io.exoreaction.synthesis.ai;
                class PromptTemplates {
                    static String template = \"\"\\"
                        You are a helpful assistant.
                        Answer: %s
                        \\"\"\";
                }
                """;
        // We test the raw check method
        String rawCode = "package io.exoreaction.synthesis.ai;\n"
                + "class PromptTemplates {\n"
                + "    static String template = \"\"\"\n"
                + "        You are a helpful assistant.\n"
                + "        INSTRUCTIONS: Analyze the following content.\n"
                + "        Answer: %s\n"
                + "        \"\"\";\n"
                + "}\n";

        List<SecuritySignal> signals = analyzer.checkS021(rawCode, "src/PromptTemplates.java",
                "PromptTemplates", "io.exoreaction.synthesis.ai");
        assertTrue(signals.stream().anyMatch(s -> s.signalId().equals("S021_MISSING_PROMPT_BOUNDARIES")),
                "Should detect prompt template without boundary tags");
        assertEquals("structural", signals.get(0).flowType());
    }

    // Regression #240: JSON format strings must not trigger S021
    @Test
    void s021_ignores_json_format_string_without_ai_vocabulary() {
        String code = "package io.exoreaction.synthesis.report;\n"
                + "class ExportCommand {\n"
                + "    String buildJson(String status, String path) {\n"
                + "        return \"\"\"\n"
                + "            {\"status\": \"%s\", \"path\": \"%s\"}\n"
                + "            \"\"\".formatted(status, path);\n"
                + "    }\n"
                + "}\n";
        List<SecuritySignal> signals = analyzer.checkS021(code, "src/ExportCommand.java", "ExportCommand",
                "io.exoreaction.synthesis.report");
        assertTrue(signals.isEmpty(),
                "JSON text block with %s but no AI vocabulary must not trigger S021 (#240)");
    }

    @Test
    void s021_ignores_sql_text_block_without_ai_vocabulary() {
        String code = "package io.exoreaction.synthesis.report;\n"
                + "class SecurityRepository {\n"
                + "    private static final String FIND_BY_SIGNAL = \"\"\"\n"
                + "        SELECT * FROM security_findings\n"
                + "        WHERE signal_id = '%s'\n"
                + "        AND workspace_path = '%s'\n"
                + "        \"\"\";\n"
                + "}\n";
        List<SecuritySignal> signals = analyzer.checkS021(code, "src/SecurityRepository.java", "SecurityRepository",
                "io.exoreaction.synthesis.report");
        assertTrue(signals.isEmpty(),
                "SQL text block with %s but no AI vocabulary must not trigger S021 (#240)");
    }

    @Test
    void s021_ignores_prompt_with_boundaries() {
        String code = "package io.exoreaction.synthesis.ai;\n"
                + "class PromptTemplates {\n"
                + "    static String template = \"\"\"\n"
                + "        <system>You are a helpful assistant. INSTRUCTIONS: Analyze.</system>\n"
                + "        <user>%s</user>\n"
                + "        \"\"\";\n"
                + "}\n";

        List<SecuritySignal> signals = analyzer.checkS021(code, "src/PromptTemplates.java",
                "PromptTemplates", "io.exoreaction.synthesis.ai");
        assertTrue(signals.isEmpty(), "Boundary tags should suppress S021");
    }

    // -----------------------------------------------------------------------
    // Full analysis integration
    // -----------------------------------------------------------------------

    @Test
    void analyze_full_workspace_runs_without_errors() throws IOException, SQLException {
        // Create a minimal workspace with one Java file
        Path srcDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("App.java"), """
                package com.example;
                public class App {
                    public static void main(String[] args) {
                        System.out.println("Hello");
                    }
                }
                """);

        SecurityAnalysisOptions options = SecurityAnalysisOptions.defaults();
        List<SecuritySignal> signals = analyzer.analyze(tempDir, conn, options);

        // Should not throw; may or may not have findings
        assertNotNull(signals);
    }

    // -----------------------------------------------------------------------
    // Helper method tests
    // -----------------------------------------------------------------------

    @Test
    void extractPackageName_parses_correctly() {
        assertEquals("com.example",
                SecurityAnalyzer.extractPackageName("package com.example;\nimport java.util.List;"));
        assertNull(SecurityAnalyzer.extractPackageName("// no package\nclass Foo {}"));
    }

    @Test
    void severityOrder_correct() {
        assertTrue(SecurityAnalyzer.severityOrder("HIGH") < SecurityAnalyzer.severityOrder("MEDIUM"));
        assertTrue(SecurityAnalyzer.severityOrder("MEDIUM") < SecurityAnalyzer.severityOrder("LOW"));
        assertTrue(SecurityAnalyzer.severityOrder("LOW") < SecurityAnalyzer.severityOrder("INFO"));
    }
}
