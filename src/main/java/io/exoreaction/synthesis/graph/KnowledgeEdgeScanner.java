package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class KnowledgeEdgeScanner {

    private static final Pattern CLASS_NAME_PATTERN =
        Pattern.compile("\\b([A-Z][a-zA-Z0-9]{2,})\\b");

    private static final Pattern METHOD_PATTERN =
        Pattern.compile("`([a-z][a-zA-Z0-9]+(?:\\(\\))?)`");

    public List<KnowledgeEdge> scan(List<Path> skillDirs, SearchIndex index,
                                    Path workspaceRoot) throws IOException {
        List<SearchResult> allIndexed = index.listAll(null, 5000);
        Map<String, String> fileNameToPath = new HashMap<>();
        for (SearchResult r : allIndexed) {
            fileNameToPath.put(r.fileName(), r.relativePath());
        }
        List<KnowledgeEdge> edges = new ArrayList<>();
        for (Path skillDir : skillDirs) {
            if (!Files.isDirectory(skillDir)) continue;
            try (Stream<Path> walk = Files.walk(skillDir)) {
                List<Path> skillFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".md") || n.endsWith(".yaml") || n.endsWith(".yml");
                    })
                    .collect(Collectors.toList());
                for (Path skillFile : skillFiles) {
                    edges.addAll(scanFile(skillFile, fileNameToPath, workspaceRoot));
                }
            }
        }
        return edges;
    }

    public List<KnowledgeEdge> scanFile(Path skillFile,
                                        Map<String, String> fileNameIndex,
                                        Path workspaceRoot) throws IOException {
        String content = Files.readString(skillFile);
        String skillRelPath = workspaceRoot.relativize(skillFile).toString();
        long skillModified = Files.getLastModifiedTime(skillFile).toMillis();
        Map<String, Set<String>> sourceToEntities = new LinkedHashMap<>();
        Matcher m = CLASS_NAME_PATTERN.matcher(content);
        while (m.find()) {
            String name = m.group(1);
            String javaFile = name + ".java";
            if (fileNameIndex.containsKey(javaFile)) {
                sourceToEntities
                    .computeIfAbsent(fileNameIndex.get(javaFile), k -> new LinkedHashSet<>())
                    .add(name);
            }
        }
        List<KnowledgeEdge> edges = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : sourceToEntities.entrySet()) {
            String sourcePath = e.getKey();
            Path sourceFile = workspaceRoot.resolve(sourcePath);
            long sourceModified = Files.exists(sourceFile)
                ? Files.getLastModifiedTime(sourceFile).toMillis()
                : skillModified;
            long diffMs = sourceModified - skillModified;
            int driftDays = (int) (diffMs / 86_400_000L);
            String confidence = KnowledgeEdge.computeConfidence(driftDays);
            for (String entity : e.getValue()) {
                edges.add(new KnowledgeEdge(
                    skillRelPath, sourcePath, entity,
                    "mentioned", skillModified, sourceModified,
                    driftDays, confidence
                ));
            }
        }
        return edges;
    }

    public void persist(List<KnowledgeEdge> edges, java.sql.Connection conn) throws java.sql.SQLException {
        String insertSql = "INSERT OR REPLACE INTO knowledge_edges"
            + " (skill_path, source_path, entity_name, coverage_type,"
            + " skill_modified_at, source_modified_at, drift_days, confidence, last_reconciled_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (var stmt = conn.prepareStatement(insertSql)) {
            long now = System.currentTimeMillis();
            for (KnowledgeEdge edge : edges) {
                stmt.setString(1, edge.skillPath());
                stmt.setString(2, edge.sourcePath());
                stmt.setString(3, edge.entityName());
                stmt.setString(4, edge.coverageType());
                stmt.setLong(5, edge.skillModifiedAt());
                stmt.setLong(6, edge.sourceModifiedAt());
                stmt.setInt(7, edge.driftDays());
                stmt.setString(8, edge.confidence());
                stmt.setLong(9, now);
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    public List<KnowledgeEdge> queryBySource(String sourcePath, java.sql.Connection conn)
            throws java.sql.SQLException {
        String querySql = "SELECT * FROM knowledge_edges WHERE source_path = ? ORDER BY confidence";
        List<KnowledgeEdge> result = new ArrayList<>();
        try (var stmt = conn.prepareStatement(querySql)) {
            stmt.setString(1, sourcePath);
            var rs = stmt.executeQuery();
            while (rs.next()) {
                result.add(new KnowledgeEdge(
                    rs.getString("skill_path"),
                    rs.getString("source_path"),
                    rs.getString("entity_name"),
                    rs.getString("coverage_type"),
                    rs.getLong("skill_modified_at"),
                    rs.getLong("source_modified_at"),
                    rs.getInt("drift_days"),
                    rs.getString("confidence")
                ));
            }
        }
        return result;
    }
}
