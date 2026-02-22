package io.exoreaction.synthesis.graph;

/**
 * An edge in the attack surface graph, connecting an entry point to a sink.
 *
 * <p>Entry points are CLI commands or MCP handlers. Sinks are files that
 * perform sensitive operations (SQL, file I/O, process execution).
 *
 * @param entryFile   file path of the entry point
 * @param entryClass  class name of the entry point
 * @param sinkFile    file path of the sink
 * @param sinkClass   class name of the sink
 * @param sinkType    type of sink: "sql", "file-io", "process", "ai-prompt"
 * @param hopCount    number of dependency hops from entry to sink
 * @param pathSummary human-readable path summary (e.g., "Cli -> Service -> Dao")
 * @since v1.14.0 (Security)
 */
public record AttackSurfaceEdge(
        String entryFile,
        String entryClass,
        String sinkFile,
        String sinkClass,
        String sinkType,
        int hopCount,
        String pathSummary
) {}
