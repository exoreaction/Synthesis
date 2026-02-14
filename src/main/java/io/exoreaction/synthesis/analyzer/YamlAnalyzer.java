package io.exoreaction.synthesis.analyzer;

import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.FileUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * Analyzes YAML files to extract structure and key metadata.
 *
 * <p>Detects special YAML formats:
 * <ul>
 *   <li>Claude Code skill files (name, description, steps)</li>
 *   <li>Docker Compose files (services)</li>
 *   <li>GitHub Actions workflows (jobs)</li>
 *   <li>Kubernetes manifests (kind, apiVersion)</li>
 *   <li>Generic YAML configuration</li>
 * </ul>
 */
public class YamlAnalyzer implements FileAnalyzer {

    private static final int CONTENT_PREVIEW_LIMIT = 10240;

    @Override
    public boolean canAnalyze(FileMetadata metadata) {
        return metadata.fileType() == FileUtils.FileType.YAML;
    }

    @Override
    public AnalysisResult analyze(FileMetadata metadata) throws IOException {
        String content = Files.readString(metadata.path());
        if (content.isBlank()) {
            return AnalysisResult.empty();
        }

        // Parse YAML safely
        Map<String, Object> data;
        try {
            Yaml yaml = new Yaml();
            Object parsed = yaml.load(content);
            if (parsed instanceof Map<?, ?> map) {
                // SnakeYAML may parse some keys as non-String types
                // (e.g., bare "on" becomes Boolean.TRUE, "3.8" becomes Double).
                // Normalize all keys to strings for consistent handling.
                data = new LinkedHashMap<>();
                for (var entry : map.entrySet()) {
                    data.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            } else {
                // YAML is a list or scalar at top level
                return AnalysisResult.minimal("YAML data file", truncate(content));
            }
        } catch (Exception e) {
            // Invalid YAML -- still index the raw content
            return AnalysisResult.minimal("YAML file (parse error: " + e.getMessage() + ")", truncate(content));
        }

        // Detect YAML type and extract accordingly
        String yamlType = detectYamlType(data, metadata.fileName());
        List<String> topLevelKeys = new ArrayList<>(data.keySet());
        List<String> keywords = new ArrayList<>();
        String summary;

        switch (yamlType) {
            case "claude-skill" -> {
                summary = extractClaudeSkillInfo(data);
                keywords.add("claude-code");
                keywords.add("skill");
                addIfPresent(keywords, data, "name");
            }
            case "docker-compose" -> {
                summary = extractDockerComposeInfo(data);
                keywords.add("docker");
                keywords.add("compose");
            }
            case "github-actions" -> {
                summary = extractGitHubActionsInfo(data);
                keywords.add("github-actions");
                keywords.add("ci-cd");
            }
            case "kubernetes" -> {
                summary = extractKubernetesInfo(data);
                keywords.add("kubernetes");
                keywords.add("k8s");
            }
            default -> {
                summary = "YAML configuration with keys: " +
                        String.join(", ", topLevelKeys.subList(0, Math.min(5, topLevelKeys.size())));
            }
        }

        keywords.add("yaml");

        // Structure description
        String structure = String.format("YAML (%s), %d top-level keys", yamlType, topLevelKeys.size());

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("topLevelKeys", topLevelKeys.size());
        metrics.put("yamlType", yamlType);

        return AnalysisResult.builder()
                .summary(summary)
                .headings(topLevelKeys)
                .keywords(keywords)
                .structure(structure)
                .metrics(metrics)
                .contentPreview(truncate(content))
                .build();
    }

    private String detectYamlType(Map<String, Object> data, String fileName) {
        // Claude Code skill detection
        if (data.containsKey("name") && (data.containsKey("steps") || data.containsKey("instructions"))) {
            return "claude-skill";
        }

        // Docker Compose
        if (data.containsKey("services") && (data.containsKey("version") || data.containsKey("networks"))) {
            return "docker-compose";
        }

        // GitHub Actions
        // Note: YAML parses bare "on" as Boolean.TRUE, so after key normalization
        // it becomes the string "true". Check both forms.
        boolean hasOnKey = data.containsKey("on") || data.containsKey("true");
        if (hasOnKey && data.containsKey("jobs")) {
            return "github-actions";
        }
        if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
            if (data.containsKey("name") && hasOnKey) {
                return "github-actions";
            }
        }

        // Kubernetes
        if (data.containsKey("apiVersion") && data.containsKey("kind")) {
            return "kubernetes";
        }

        return "generic";
    }

    private String extractClaudeSkillInfo(Map<String, Object> data) {
        String name = getString(data, "name");
        String description = getString(data, "description");
        if (!description.isEmpty()) {
            return "Claude Code skill: " + name + " - " + truncateString(description, 100);
        }
        return "Claude Code skill: " + name;
    }

    private String extractDockerComposeInfo(Map<String, Object> data) {
        Object services = data.get("services");
        if (services instanceof Map<?, ?> map) {
            return "Docker Compose: " + map.size() + " services (" +
                    String.join(", ", map.keySet().stream().map(Object::toString).limit(5).toList()) + ")";
        }
        return "Docker Compose configuration";
    }

    private String extractGitHubActionsInfo(Map<String, Object> data) {
        String name = getString(data, "name");
        Object jobs = data.get("jobs");
        int jobCount = jobs instanceof Map<?, ?> map ? map.size() : 0;
        return "GitHub Actions: " + name + " (" + jobCount + " jobs)";
    }

    private String extractKubernetesInfo(Map<String, Object> data) {
        String kind = getString(data, "kind");
        String apiVersion = getString(data, "apiVersion");
        String name = "";
        Object metadata = data.get("metadata");
        if (metadata instanceof Map<?, ?> meta) {
            Object n = meta.get("name");
            if (n != null) name = n.toString();
        }
        return "Kubernetes " + kind + " (" + apiVersion + ")" + (name.isEmpty() ? "" : ": " + name);
    }

    private String getString(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value != null ? value.toString() : "";
    }

    private void addIfPresent(List<String> keywords, Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value != null) {
            String str = value.toString().toLowerCase().trim();
            if (!str.isEmpty() && str.length() < 50) {
                keywords.add(str);
            }
        }
    }

    private String truncate(String content) {
        return content.length() > CONTENT_PREVIEW_LIMIT
                ? content.substring(0, CONTENT_PREVIEW_LIMIT) : content;
    }

    private String truncateString(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
