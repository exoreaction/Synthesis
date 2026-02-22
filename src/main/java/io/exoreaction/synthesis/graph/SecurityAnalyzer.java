package io.exoreaction.synthesis.graph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Analyzes source files for security vulnerabilities and reports findings
 * as {@link SecuritySignal} instances.
 *
 * <p>Implements 21 signals in two tiers:
 * <ul>
 *   <li><b>S001-S015</b>: Traditional security (SQL injection, hardcoded secrets,
 *       weak crypto, XXE, path traversal, unsafe deserialization, etc.)</li>
 *   <li><b>S016-S021</b>: Prompt injection and agentic surface (direct prompt
 *       injection, RAG poisoning, unconfirmed actions, unvalidated paths,
 *       sensitive data exposure, missing prompt boundaries)</li>
 * </ul>
 *
 * @since v1.14.0 (Security)
 */
public class SecurityAnalyzer {

    private static final Logger LOG = Logger.getLogger(SecurityAnalyzer.class.getName());

    // -- S001: SQL Injection patterns --
    private static final Pattern SQL_CONCAT = Pattern.compile(
            "\"\\s*(?:SELECT|INSERT|UPDATE|DELETE|DROP|CREATE|ALTER)\\s+.*\"\\s*\\+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_EXECUTE_CONCAT = Pattern.compile(
            "(?:executeQuery|executeUpdate)\\s*\\(.*\\+");
    private static final Pattern STATEMENT_EXECUTE = Pattern.compile(
            "Statement\\.execute\\w*\\s*\\(");

    // -- S002: Hardcoded secrets --
    private static final Pattern SECRET_PASSWORD = Pattern.compile(
            "password\\s*=\\s*\"[^\"]{4,}\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_API_KEY = Pattern.compile(
            "api[_\\-]?key\\s*=\\s*\"[^\"]{8,}\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_TOKEN = Pattern.compile(
            "(?<!\\w)token\\s*=\\s*\"[^\"]{8,}\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_GENERAL = Pattern.compile(
            "(?<!\\w)secret\\s*=\\s*\"[^\"]{4,}\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_PRIVATE_KEY = Pattern.compile(
            "-----BEGIN.*PRIVATE KEY");
    private static final Set<String> SECRET_EXCLUSIONS = Set.of(
            "test", "fake", "mock", "example", "sample", "dummy", "placeholder");

    // -- S003: Weak crypto --
    private static final Pattern WEAK_MD5 = Pattern.compile("getInstance\\s*\\(\\s*\"MD5\"\\s*\\)");
    private static final Pattern WEAK_SHA1 = Pattern.compile("getInstance\\s*\\(\\s*\"SHA-1\"\\s*\\)");
    private static final Pattern WEAK_DES = Pattern.compile("getInstance\\s*\\(\\s*\"DES");
    private static final Pattern WEAK_ECB = Pattern.compile("getInstance\\s*\\(\\s*\"AES/ECB");

    // -- S004: Insecure random --
    private static final Pattern INSECURE_RANDOM_IMPORT = Pattern.compile(
            "import\\s+java\\.util\\.Random\\s*;");
    private static final Pattern INSECURE_RANDOM_NEW = Pattern.compile(
            "new\\s+Random\\s*\\(");
    private static final Set<String> SECURITY_CONTEXT_KEYWORDS = Set.of(
            "auth", "token", "session", "password", "key", "secret", "credential");

    // -- S005: XXE --
    private static final Pattern XXE_DOC_BUILDER = Pattern.compile(
            "DocumentBuilderFactory\\.newInstance\\s*\\(");
    private static final Pattern XXE_SAX = Pattern.compile(
            "SAXParserFactory\\.newInstance\\s*\\(");
    private static final Pattern XXE_XML_INPUT = Pattern.compile(
            "XMLInputFactory\\.newInstance\\s*\\(");
    private static final Pattern XXE_PROTECTION = Pattern.compile(
            "setFeature|SUPPORT_DTD|ACCESS_EXTERNAL");

    // -- S006: Path traversal --
    private static final Pattern PATH_TRAVERSAL = Pattern.compile(
            "(?:new File|Path\\.of|Paths\\.get)\\s*\\(\\s*request\\.(?:getParameter|getHeader|getPathInfo)");

    // -- S007: Unsafe deserialization --
    private static final Pattern UNSAFE_DESER_OIS = Pattern.compile(
            "(?:new\\s+ObjectInputStream|readObject\\s*\\(|XMLDecoder)");
    private static final Pattern DESER_FILTER = Pattern.compile(
            "ObjectInputFilter");

    // -- S011: Overly broad catch --
    private static final Pattern BROAD_CATCH = Pattern.compile(
            "catch\\s*\\(\\s*(?:Exception|Throwable)\\s+\\w+\\s*\\)");

    // -- S012: Command injection --
    private static final Pattern CMD_RUNTIME_EXEC = Pattern.compile(
            "Runtime\\.getRuntime\\(\\)\\.exec\\s*\\(");
    private static final Pattern CMD_PROCESS_BUILDER = Pattern.compile(
            "new\\s+ProcessBuilder\\s*\\(");

    // -- S013: Temp file race --
    private static final Pattern TEMP_FILE_OLD = Pattern.compile(
            "File\\.createTempFile\\s*\\(");

    // -- S014: Log injection --
    private static final Pattern LOG_INJECTION = Pattern.compile(
            "(?:log|logger|LOG)\\w*\\.\\w+\\(.*(?:getParameter|getHeader)");

    // -- S016: Direct prompt injection --
    private static final Pattern PROMPT_BUILD = Pattern.compile(
            "build\\w*Prompt\\s*\\(|buildPrompt\\s*\\(|\\.formatted\\s*\\(");
    private static final Set<String> PROMPT_PARAM_NAMES = Set.of(
            "question", "query", "input", "target", "pattern");

    // -- S017: RAG poisoning --
    private static final Pattern RAG_SEARCH = Pattern.compile(
            "index\\.search\\s*\\(|index\\.listAll\\s*\\(");
    private static final Pattern RAG_READ = Pattern.compile(
            "readPreview\\s*\\(|readString\\s*\\(|readAllBytes\\s*\\(");
    private static final Pattern RAG_PROMPT = Pattern.compile(
            "build\\w*Prompt\\s*\\(|client\\.generate\\s*\\(");

    // -- S018: Unconfirmed agentic action --
    private static final Pattern AGENTIC_WRITE = Pattern.compile(
            "Files\\.(?:write|move|delete|createDirector)|generator\\.generate\\s*\\(");
    private static final Pattern AGENTIC_HANDLER = Pattern.compile(
            "handle\\w+\\s*\\(");
    private static final Pattern DRY_RUN_CHECK = Pattern.compile(
            "dryRun|dry_run|isDryRun");

    // -- S019: Unvalidated agentic path --
    private static final Pattern MCP_PATH_FROM_PARAMS = Pattern.compile(
            "Path\\.of\\s*\\(.*(?:params\\.get|asText\\s*\\()|Paths\\.get\\s*\\(.*(?:params\\.get|asText\\s*\\()");
    private static final Pattern PATH_CONTAINMENT = Pattern.compile(
            "startsWith\\s*\\(");

    // -- S020: Sensitive data exposure --
    private static final Pattern MCP_READ = Pattern.compile(
            "readPreview\\s*\\(|readString\\s*\\(");
    private static final Pattern MCP_RESPONSE = Pattern.compile(
            "response\\.put\\s*\\(|content\\.add\\s*\\(");
    private static final Set<String> SENSITIVE_FILES = Set.of(
            ".env", "credentials", "id_rsa", ".key", ".pem", "secret", "token");

    // -- S021: Missing prompt boundaries --
    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\"");
    private static final Pattern FORMAT_INTERPOLATION = Pattern.compile(
            "String\\.format\\s*\\(.*%s|\"[^\"]*%s");
    private static final Pattern BOUNDARY_TAG = Pattern.compile(
            "<system|<context|<user|<instructions");
    private static final Set<String> PROMPT_VOCAB = Set.of(
            "You are", "Your Role", "Your role", "INSTRUCTIONS:",
            "Be THOROUGH", "Analyze", "ANALYZE", "Provide", "PROVIDE",
            "FORMAT FOR", "CODEBASE METRICS", "COVERAGE PERIOD",
            "PREVIOUS ANALYSIS", "TARGET AUDIENCE");

    private final SecurityRepository repository;

    public SecurityAnalyzer() {
        this.repository = new SecurityRepository();
    }

    public SecurityAnalyzer(SecurityRepository repository) {
        this.repository = repository;
    }

    /**
     * Analyzes all Java files under the workspace root for security issues.
     *
     * @param workspaceRoot workspace root path
     * @param conn          database connection for persisting findings
     * @param options       analysis options
     * @return list of detected security signals
     */
    public List<SecuritySignal> analyze(Path workspaceRoot, Connection conn,
                                         SecurityAnalysisOptions options) throws IOException, SQLException {
        List<SecuritySignal> allSignals = new ArrayList<>();
        String wsPath = workspaceRoot.toString();
        long now = Instant.now().getEpochSecond();

        // Delete previous findings
        repository.deleteAllFindings(conn, wsPath);

        // Find all Java files
        List<Path> javaFiles;
        try (Stream<Path> walk = Files.walk(workspaceRoot)) {
            javaFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/."))
                    .filter(p -> !CodeGraphExtractor.isBuildArtifact(workspaceRoot, p))
                    .filter(p -> options.includeTests() || !isTestFile(workspaceRoot, p))
                    .toList();
        }

        // Scan each file
        for (Path javaFile : javaFiles) {
            try {
                String content = Files.readString(javaFile);
                String relativePath = workspaceRoot.relativize(javaFile).toString();
                String className = extractClassName(javaFile);
                String packageName = extractPackageName(content);

                allSignals.addAll(checkS001(content, relativePath, className, packageName));
                allSignals.addAll(checkS002(content, relativePath, className, packageName));
                allSignals.addAll(checkS003(content, relativePath, className, packageName));
                allSignals.addAll(checkS004(content, relativePath, className, packageName));
                allSignals.addAll(checkS005(content, relativePath, className, packageName));
                allSignals.addAll(checkS006(content, relativePath, className, packageName));
                allSignals.addAll(checkS007(content, relativePath, className, packageName));
                allSignals.addAll(checkS011(content, relativePath, className, packageName));
                allSignals.addAll(checkS012(content, relativePath, className, packageName));
                allSignals.addAll(checkS013(content, relativePath, className, packageName));
                allSignals.addAll(checkS014(content, relativePath, className, packageName));
                allSignals.addAll(checkS015(content, relativePath, className, packageName));
                allSignals.addAll(checkS016(content, relativePath, className, packageName));
                allSignals.addAll(checkS017(content, relativePath, className, packageName));
                allSignals.addAll(checkS018(content, relativePath, className, packageName));
                allSignals.addAll(checkS019(content, relativePath, className, packageName));
                allSignals.addAll(checkS020(content, relativePath, className, packageName));
                allSignals.addAll(checkS021(content, relativePath, className, packageName));
            } catch (IOException e) {
                LOG.fine("Could not read file: " + javaFile + " - " + e.getMessage());
            }
        }

        // S008 and S009 use database queries
        allSignals.addAll(checkS008(workspaceRoot, conn));
        allSignals.addAll(checkS009(wsPath, conn));

        // S010: dependency vulnerabilities
        DependencyInventoryExtractor depExtractor = new DependencyInventoryExtractor();
        allSignals.addAll(depExtractor.analyzeAndReport(workspaceRoot, conn, wsPath));

        // Persist all findings
        for (SecuritySignal signal : allSignals) {
            try {
                repository.upsertFinding(conn, wsPath, signal, now);
            } catch (SQLException e) {
                LOG.fine("Could not persist finding: " + e.getMessage());
            }
        }

        // Sort by severity
        allSignals.sort(Comparator.comparingInt(s -> severityOrder(s.severity())));

        return allSignals;
    }

    // -----------------------------------------------------------------------
    // S001: SQL Injection
    // -----------------------------------------------------------------------

    List<SecuritySignal> checkS001(String content, String filePath,
                                     String className, String packageName) {
        List<SecuritySignal> signals = new ArrayList<>();

        // SQL context gate: skip files that don't use any SQL APIs
        boolean hasSqlContext = content.contains("java.sql") || content.contains("PreparedStatement")
                || content.contains("executeQuery") || content.contains("executeUpdate")
                || content.contains("Statement");
        if (!hasSqlContext) return signals;

        // Skip if file uses only PreparedStatement
        if (content.contains("PreparedStatement") && !content.contains("Statement.execute")) {
            // Check for string concat even with PreparedStatement
            String[] lines = content.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (SQL_CONCAT.matcher(line).find() && !line.contains("PreparedStatement")) {
                    signals.add(new SecuritySignal(
                            "S001_SQL_INJECTION", "HIGH", "CWE-89",
                            filePath, i + 1, className, packageName,
                            "String concatenation in SQL query",
                            trimEvidence(line),
                            "Use PreparedStatement with parameterized queries",
                            null));
                }
            }
            return signals;
        }

        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (SQL_CONCAT.matcher(line).find()
                    || SQL_EXECUTE_CONCAT.matcher(line).find()
                    || (STATEMENT_EXECUTE.matcher(line).find() && line.contains("+"))) {
                signals.add(new SecuritySignal(
                        "S001_SQL_INJECTION", "HIGH", "CWE-89",
                        filePath, i + 1, className, packageName,
                        "String concatenation in SQL query",
                        trimEvidence(line),
                        "Use PreparedStatement with parameterized queries",
                        null));
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // S002: Hardcoded Secrets
    // -----------------------------------------------------------------------

    List<SecuritySignal> checkS002(String content, String filePath,
                                     String className, String packageName) {
        List<SecuritySignal> signals = new ArrayList<>();
        String[] lines = content.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String lineLower = line.toLowerCase(Locale.ROOT);

            // Skip pattern definition lines (detector's own regex strings)
            if (line.contains("Pattern.compile(")) continue;

            // Skip lines containing test/mock exclusion words
            boolean excluded = SECRET_EXCLUSIONS.stream().anyMatch(lineLower::contains);
            if (excluded) continue;

            Pattern[] patterns = {SECRET_PASSWORD, SECRET_API_KEY, SECRET_TOKEN,
                    SECRET_GENERAL, SECRET_PRIVATE_KEY};
            for (Pattern p : patterns) {
                if (p.matcher(line).find()) {
                    signals.add(new SecuritySignal(
                            "S002_HARDCODED_SECRET", "HIGH", "CWE-798",
                            filePath, i + 1, className, packageName,
                            "Potential hardcoded secret detected",
                            trimEvidence(line),
                            "Use environment variables or a secrets manager",
                            null));
                    break; // One finding per line
                }
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // S003: Weak Cryptography
    // -----------------------------------------------------------------------

    List<SecuritySignal> checkS003(String content, String filePath,
                                     String className, String packageName) {
        List<SecuritySignal> signals = new ArrayList<>();
        String[] lines = content.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Pattern[] weakPatterns = {WEAK_MD5, WEAK_SHA1, WEAK_DES, WEAK_ECB};
            String[] descriptions = {
                    "MD5 is cryptographically broken",
                    "SHA-1 is deprecated for security use",
                    "DES uses a 56-bit key, easily brute-forced",
                    "AES/ECB mode leaks data patterns"
            };
            for (int j = 0; j < weakPatterns.length; j++) {
                if (weakPatterns[j].matcher(line).find()) {
                    signals.add(new SecuritySignal(
                            "S003_WEAK_CRYPTO", "MEDIUM", "CWE-327",
                            filePath, i + 1, className, packageName,
                            descriptions[j],
                            trimEvidence(line),
                            "Use SHA-256 or stronger; use AES/GCM instead of AES/ECB",
                            null));
                }
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // S004: Insecure Random
    // -----------------------------------------------------------------------

    List<SecuritySignal> checkS004(String content, String filePath,
                                     String className, String packageName) {
        List<SecuritySignal> signals = new ArrayList<>();
        // Only flag if file is in a security-relevant context
        String contentLower = content.toLowerCase(Locale.ROOT);
        boolean securityContext = SECURITY_CONTEXT_KEYWORDS.stream().anyMatch(contentLower::contains);
        if (!securityContext) return signals;

        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (INSECURE_RANDOM_IMPORT.matcher(line).find()
                    || INSECURE_RANDOM_NEW.matcher(line).find()) {
                signals.add(new SecuritySignal(
                        "S004_INSECURE_RANDOM", "MEDIUM", "CWE-330",
                        filePath, i + 1, className, packageName,
                        "java.util.Random used in security context",
                        trimEvidence(line),
                        "Use java.security.SecureRandom for security-sensitive operations",
                        null));
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // S005: XXE Vulnerability
    // -----------------------------------------------------------------------

    List<SecuritySignal> checkS005(String content, String filePath,
                                     String className, String packageName) {
        List<SecuritySignal> signals = new ArrayList<>();
        // Check if file has XXE protection anywhere
        boolean hasProtection = XXE_PROTECTION.matcher(content).find();
        if (hasProtection) return signals;

        String[] lines = content.split("\n");
        Pattern[] xxePatterns = {XXE_DOC_BUILDER, XXE_SAX, XXE_XML_INPUT};
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            for (Pattern p : xxePatterns) {
                if (p.matcher(line).find()) {
                    signals.add(new SecuritySignal(
                            "S005_XXE_VULNERABILITY", "HIGH", "CWE-611",
                            filePath, i + 1, className, packageName,
                            "XML parser without XXE protection",
                            trimEvidence(line),
                            "Disable external entities: setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)",
                            null));
                }
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // S006: Path Traversal
    // -----------------------------------------------------------------------

    List<SecuritySignal> checkS006(String content, String filePath,
                                     String className, String packageName) {
        List<SecuritySignal> signals = new ArrayList<>();
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (PATH_TRAVERSAL.matcher(line).find()) {
                signals.add(new SecuritySignal(
                        "S006_PATH_TRAVERSAL", "HIGH", "CWE-22",
                        filePath, i + 1, className, packageName,
                        "User input from HTTP request used in file path construction",
                        trimEvidence(line),
                        "Validate and sanitize path; use Path.normalize().startsWith(allowedBase)",
                        null));
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // S007: Unsafe Deserialization
    // -----------------------------------------------------------------------

    List<SecuritySignal> checkS007(String content, String filePath,
                                     String className, String packageName) {
        List<SecuritySignal> signals = new ArrayList<>();
        // Skip if file uses ObjectInputFilter
        if (DESER_FILTER.matcher(content).find()) return signals;

        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (UNSAFE_DESER_OIS.matcher(line).find()) {
                signals.add(new SecuritySignal(
                        "S007_UNSAFE_DESERIALIZATION", "HIGH", "CWE-502",
                        filePath, i + 1, className, packageName,
                        "Unsafe deserialization without input filtering",
                        trimEvidence(line),
                        "Use ObjectInputFilter or avoid Java deserialization entirely",
                        null));
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // S008: Missing Input Validation (database-backed)
    // -----------------------------------------------------------------------

    List<SecuritySignal> checkS008(Path workspaceRoot, Connection conn) throws SQLException {
        List<SecuritySignal> signals = new ArrayList<>();

        // Query high-fan-in packages (must include repo_name per V14)
        String sql = """
            SELECT repo_name, module_path, package_name, fan_in
            FROM module_profiles
            WHERE workspace_path = ? AND fan_in > 3
            GROUP BY repo_name, module_path
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceRoot.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String modulePath = rs.getString("module_path");
                    String packageName = rs.getString("package_name");
                    int fanIn = rs.getInt("fan_in");

                    // Find Java files in this package and check for missing validation
                    Path packageDir = workspaceRoot.resolve(modulePath);
                    if (!Files.isDirectory(packageDir)) continue;

                    try (Stream<Path> files = Files.list(packageDir)) {
                        files.filter(p -> p.toString().endsWith(".java"))
                                .forEach(f -> {
                                    try {
                                        String content = Files.readString(f);
                                        if (hasPublicStringParamsWithoutValidation(content)) {
                                            String relPath = workspaceRoot.relativize(f).toString();
                                            signals.add(new SecuritySignal(
                                                    "S008_MISSING_INPUT_VALIDATION", "MEDIUM", "CWE-20",
                                                    relPath, 0, extractClassName(f), packageName,
                                                    "High fan-in package (" + fanIn + " dependents) with public String params lacking validation",
                                                    null,
                                                    "Add null checks and input validation for public method parameters",
                                                    null));
                                        }
                                    } catch (IOException e) {
                                        // skip
                                    }
                                });
                    } catch (IOException e) {
                        // skip
                    }
                }
            }
        }
        return signals;
    }

    private boolean hasPublicStringParamsWithoutValidation(String content) {
        Pattern publicStringMethod = Pattern.compile(
                "public\\s+\\w+\\s+\\w+\\s*\\([^)]*String\\s+\\w+[^)]*\\)");
        Matcher m = publicStringMethod.matcher(content);
        if (!m.find()) return false;

        // Check if any null check exists near public methods
        return !content.contains("== null") && !content.contains("!= null")
                && !content.contains("Objects.requireNonNull")
                && !content.contains("isBlank()") && !content.contains("isEmpty()");
    }

    // -----------------------------------------------------------------------
    // S009: Exposed Internals (database-backed)
    // -----------------------------------------------------------------------

    List<SecuritySignal> checkS009(String workspacePath, Connection conn) throws SQLException {
        List<SecuritySignal> signals = new ArrayList<>();

        // Find imports from .impl or .internal packages used outside their parent
        // Must scope by repo_name to avoid cross-repo false positives
        String sql = """
            SELECT d.repo_name, d.source_file, d.source_package, d.target_package, d.target_class
            FROM code_dependencies d
            WHERE d.workspace_path = ?
              AND d.is_external = 0
              AND (d.target_package LIKE '%.impl%' OR d.target_package LIKE '%.internal%')
              AND d.source_package NOT LIKE d.target_package || '%'
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sourceFile = rs.getString("source_file");
                    String sourcePackage = rs.getString("source_package");
                    String targetPackage = rs.getString("target_package");
                    String targetClass = rs.getString("target_class");

                    signals.add(new SecuritySignal(
                            "S009_EXPOSED_INTERNAL", "MEDIUM", "CWE-749",
                            sourceFile, 0, null, sourcePackage,
                            "Imports internal type " + targetClass + " from " + targetPackage,
                            null,
                            "Use public API instead of reaching into internal packages",
                            null));
                }
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // S011: Overly Broad Catch
    // -----------------------------------------------------------------------

    List<SecuritySignal> checkS011(String content, String filePath,
                                     String className, String packageName) {
        List<SecuritySignal> signals = new ArrayList<>();
        // Only flag in security-boundary packages, not any file that mentions security in comments
        if (packageName == null) return signals;
        boolean securityPackage = packageName.contains(".mcp") || packageName.contains(".cli")
                || packageName.contains(".security") || packageName.contains(".auth")
                || packageName.contains(".credential") || packageName.contains(".crypto");
        if (!securityPackage) return signals;

        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (BROAD_CATCH.matcher(lines[i]).find()) {
                signals.add(new SecuritySignal(
                        "S011_OVERLY_BROAD_CATCH", "LOW", "CWE-396",
                        filePath, i + 1, className, packageName,
                        "Broad catch (Exception/Throwable) in security-sensitive code",
                        trimEvidence(lines[i]),
                        "Catch specific exception types to avoid masking security failures",
                        null));
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // S012: Command Injection
    // -----------------------------------------------------------------------

    List<SecuritySignal> checkS012(String content, String filePath,
                                     String className, String packageName) {
        List<SecuritySignal> signals = new ArrayList<>();
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if ((CMD_RUNTIME_EXEC.matcher(line).find() && line.contains("+"))
                    || (CMD_PROCESS_BUILDER.matcher(line).find() && line.contains("+"))) {
                signals.add(new SecuritySignal(
                        "S012_COMMAND_INJECTION", "HIGH", "CWE-78",
                        filePath, i + 1, className, packageName,
                        "Command execution with string concatenation",
                        trimEvidence(line),
                        "Use parameterized ProcessBuilder arguments; avoid Runtime.exec with concatenated strings",
                        null));
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // S013: Temp File Race
    // -----------------------------------------------------------------------

    List<SecuritySignal> checkS013(String content, String filePath,
                                     String className, String packageName) {
        List<SecuritySignal> signals = new ArrayList<>();
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (TEMP_FILE_OLD.matcher(lines[i]).find()) {
                signals.add(new SecuritySignal(
                        "S013_TEMP_FILE_RACE", "LOW", "CWE-377",
                        filePath, i + 1, className, packageName,
                        "File.createTempFile is susceptible to race conditions",
                        trimEvidence(lines[i]),
                        "Use Files.createTempFile() which creates with restrictive permissions",
                        null));
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // S014: Log Injection
    // -----------------------------------------------------------------------

    List<SecuritySignal> checkS014(String content, String filePath,
                                     String className, String packageName) {
        List<SecuritySignal> signals = new ArrayList<>();
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (LOG_INJECTION.matcher(lines[i]).find()) {
                signals.add(new SecuritySignal(
                        "S014_LOG_INJECTION", "MEDIUM", "CWE-117",
                        filePath, i + 1, className, packageName,
                        "HTTP request parameters logged directly without sanitization",
                        trimEvidence(lines[i]),
                        "Sanitize user input before logging to prevent log forging",
                        null));
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // S015: Attack Surface Entry Points
    // -----------------------------------------------------------------------

    List<SecuritySignal> checkS015(String content, String filePath,
                                     String className, String packageName) {
        List<SecuritySignal> signals = new ArrayList<>();
        if (packageName == null) return signals;

        // CLI entry points
        if (packageName.endsWith(".cli") && content.contains("implements Callable<Integer>")) {
            signals.add(new SecuritySignal(
                    "S015_ATTACK_SURFACE_ENTRY", "INFO", null,
                    filePath, 0, className, packageName,
                    "CLI command entry point: " + className,
                    null,
                    "Ensure all user inputs are validated at this entry point",
                    null));
        }

        // MCP handlers
        if (packageName.endsWith(".mcp") || packageName.contains(".mcp.")) {
            Pattern handler = Pattern.compile("handle\\w+\\s*\\(");
            Matcher m = handler.matcher(content);
            while (m.find()) {
                signals.add(new SecuritySignal(
                        "S015_ATTACK_SURFACE_ENTRY", "INFO", null,
                        filePath, findLineNumber(content, m.start()), className, packageName,
                        "MCP handler entry point in " + className,
                        null,
                        "Validate all tool parameters at this handler boundary",
                        null));
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // S016: Direct Prompt Injection
    // -----------------------------------------------------------------------

    List<SecuritySignal> checkS016(String content, String filePath,
                                     String className, String packageName) {
        List<SecuritySignal> signals = new ArrayList<>();
        if (packageName == null) return signals;

        boolean relevantPackage = packageName.contains(".ai") || packageName.contains(".summary")
                || packageName.contains(".research") || packageName.contains(".report")
                || packageName.endsWith(".cli");
        if (!relevantPackage) return signals;

        // Check if file has prompt building and user-controlled params
        if (!PROMPT_BUILD.matcher(content).find()) return signals;

        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            for (String paramName : PROMPT_PARAM_NAMES) {
                if (line.contains(paramName) && PROMPT_BUILD.matcher(line).find()) {
                    signals.add(new SecuritySignal(
                            "S016_DIRECT_PROMPT_INJECTION", "HIGH", "CWE-1426",
                            filePath, i + 1, className, packageName,
                            "User-controlled parameter '" + paramName + "' flows into prompt construction",
                            trimEvidence(line),
                            "Sanitize user input before prompt construction; use structured prompt templates",
                            "direct"));
                    break;
                }
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // S017: RAG Poisoning
    // -----------------------------------------------------------------------

    List<SecuritySignal> checkS017(String content, String filePath,
                                     String className, String packageName) {
        List<SecuritySignal> signals = new ArrayList<>();

        // Three-step chain: search -> read -> prompt
        boolean hasSearch = RAG_SEARCH.matcher(content).find();
        boolean hasRead = RAG_READ.matcher(content).find();
        boolean hasPrompt = RAG_PROMPT.matcher(content).find();

        if (hasSearch && hasRead && hasPrompt) {
            // Flag at the readPreview line
            String[] lines = content.split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (RAG_READ.matcher(lines[i]).find()) {
                    signals.add(new SecuritySignal(
                            "S017_RAG_POISONING", "HIGH", "CWE-1426",
                            filePath, i + 1, className, packageName,
                            "RAG pipeline: search results read into prompt without sanitization",
                            trimEvidence(lines[i]),
                            "Validate/sanitize document content before injecting into prompts",
                            "indirect"));
                    break;
                }
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // S018: Unconfirmed Agentic Action
    // -----------------------------------------------------------------------

    List<SecuritySignal> checkS018(String content, String filePath,
                                     String className, String packageName) {
        List<SecuritySignal> signals = new ArrayList<>();
        if (packageName == null || (!packageName.endsWith(".mcp") && !packageName.contains(".mcp.")))
            return signals;

        // Check handler methods for write operations without dryRun
        boolean hasDryRun = DRY_RUN_CHECK.matcher(content).find();

        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (AGENTIC_HANDLER.matcher(line).find()) {
                // Scan the method body for write operations
                StringBuilder methodBody = new StringBuilder();
                int braceCount = 0;
                for (int j = i; j < Math.min(i + 100, lines.length); j++) {
                    methodBody.append(lines[j]).append("\n");
                    braceCount += countChar(lines[j], '{') - countChar(lines[j], '}');
                    if (braceCount <= 0 && j > i) break;
                }
                String body = methodBody.toString();
                if (AGENTIC_WRITE.matcher(body).find() && !hasDryRun) {
                    signals.add(new SecuritySignal(
                            "S018_UNCONFIRMED_AGENTIC_ACTION", "HIGH", "CWE-862",
                            filePath, i + 1, className, packageName,
                            "MCP handler performs file/generation operations without dryRun confirmation",
                            trimEvidence(line),
                            "Add dryRun parameter check before performing destructive operations",
                            "agentic"));
                }
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // S019: Unvalidated Agentic Path
    // -----------------------------------------------------------------------

    List<SecuritySignal> checkS019(String content, String filePath,
                                     String className, String packageName) {
        List<SecuritySignal> signals = new ArrayList<>();
        if (packageName == null || (!packageName.endsWith(".mcp") && !packageName.contains(".mcp.")))
            return signals;

        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (MCP_PATH_FROM_PARAMS.matcher(line).find()) {
                // Check surrounding lines for containment check
                boolean hasContainment = false;
                int start = Math.max(0, i - 5);
                int end = Math.min(lines.length, i + 10);
                for (int j = start; j < end; j++) {
                    if (PATH_CONTAINMENT.matcher(lines[j]).find()) {
                        hasContainment = true;
                        break;
                    }
                }
                if (!hasContainment) {
                    signals.add(new SecuritySignal(
                            "S019_UNVALIDATED_AGENTIC_PATH", "HIGH", "CWE-22",
                            filePath, i + 1, className, packageName,
                            "MCP handler constructs path from parameters without containment check",
                            trimEvidence(line),
                            "Add path.startsWith(allowedRoot) containment check",
                            "agentic"));
                }
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // S020: Sensitive Data Exposure via MCP
    // -----------------------------------------------------------------------

    List<SecuritySignal> checkS020(String content, String filePath,
                                     String className, String packageName) {
        List<SecuritySignal> signals = new ArrayList<>();
        if (packageName == null || (!packageName.endsWith(".mcp") && !packageName.contains(".mcp.")))
            return signals;

        boolean hasRead = MCP_READ.matcher(content).find();
        boolean hasResponse = MCP_RESPONSE.matcher(content).find();
        if (!hasRead || !hasResponse) return signals;

        // Check if any sensitive file filtering is done
        boolean checksSensitive = SENSITIVE_FILES.stream()
                .anyMatch(s -> content.toLowerCase(Locale.ROOT).contains(s));
        if (checksSensitive) return signals;

        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (MCP_READ.matcher(lines[i]).find()) {
                signals.add(new SecuritySignal(
                        "S020_SENSITIVE_DATA_EXPOSURE_MCP", "HIGH", "CWE-200",
                        filePath, i + 1, className, packageName,
                        "File content read and returned via MCP without sensitive file checks",
                        trimEvidence(lines[i]),
                        "Check filename against sensitive patterns (.env, credentials, id_rsa, .key, .pem) before returning",
                        "agentic"));
                break;
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // S021: Missing Prompt Boundaries
    // -----------------------------------------------------------------------

    List<SecuritySignal> checkS021(String content, String filePath,
                                     String className, String packageName) {
        List<SecuritySignal> signals = new ArrayList<>();
        if (packageName == null) return signals;

        boolean relevantPackage = packageName.contains(".ai") || packageName.contains(".summary")
                || packageName.contains(".research") || packageName.contains(".report");
        if (!relevantPackage) return signals;

        // AI prompt vocabulary gate: require at least 2 prompt-specific markers
        long vocabCount = PROMPT_VOCAB.stream().filter(content::contains).count();
        if (vocabCount < 2) return signals;

        // Only flag files that have prompt templates (text blocks or String.format with %s)
        boolean hasTextBlock = TEXT_BLOCK.matcher(content).find();
        boolean hasFormatInterpolation = FORMAT_INTERPOLATION.matcher(content).find();
        if (!hasTextBlock && !hasFormatInterpolation) return signals;

        // Check if boundary tags exist
        boolean hasBoundaries = BOUNDARY_TAG.matcher(content).find();
        if (hasBoundaries) return signals;

        // Flag the first text block or format call
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (TEXT_BLOCK.matcher(line).find() || FORMAT_INTERPOLATION.matcher(line).find()) {
                signals.add(new SecuritySignal(
                        "S021_MISSING_PROMPT_BOUNDARIES", "HIGH", "CWE-1426",
                        filePath, i + 1, className, packageName,
                        "Prompt template without XML boundary tags (<system>, <context>, <user>)",
                        trimEvidence(line),
                        "Add structural boundary tags to separate system instructions from user content",
                        "structural"));
                break; // One finding per file
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static boolean isTestFile(Path workspaceRoot, Path file) {
        String rel = workspaceRoot.relativize(file).toString();
        return rel.contains("/test/") || rel.contains("Test.java") || rel.contains("Tests.java");
    }

    private static String extractClassName(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
    }

    static String extractPackageName(String content) {
        Pattern pkg = Pattern.compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
        Matcher m = pkg.matcher(content);
        return m.find() ? m.group(1) : null;
    }

    private static int findLineNumber(String content, int charOffset) {
        int line = 1;
        for (int i = 0; i < Math.min(charOffset, content.length()); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static String trimEvidence(String line) {
        String trimmed = line.strip();
        return trimmed.length() > 120 ? trimmed.substring(0, 117) + "..." : trimmed;
    }

    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }

    static int severityOrder(String severity) {
        return switch (severity) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            case "LOW" -> 2;
            case "INFO" -> 3;
            default -> 4;
        };
    }
}
