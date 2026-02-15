# Synthesis API Reference

Protocol-level documentation for the Synthesis MCP and LSP servers. These documents are intended for platform engineers, IDE extension developers, and anyone building integrations with Synthesis.

---

## API Documents

| Document | Protocol | Audience | Description |
|----------|----------|----------|-------------|
| **[MCP Protocol Reference](./MCP-PROTOCOL-REFERENCE.md)** | MCP v2024-11-05 | Platform engineers, MCP client developers | JSON-RPC lifecycle, tool schemas, error codes, wire format |
| **[LSP Protocol Reference](./LSP-PROTOCOL-REFERENCE.md)** | LSP 3.17 | IDE extension developers, LSP client maintainers | Server capabilities, method implementations, message examples |

---

## Quick Reference

### MCP Server

- **Transport:** JSON-RPC 2.0 over stdio
- **Tools:** `search`, `relate`, `graph`, `stats`
- **Protocol version:** 2024-11-05
- **Entry point:** `synthesis-mcp-server`
- **User guides:** [Quick Start](../guides/MCP-QUICKSTART.md) | [Comprehensive Guide](../guides/MCP-COMPREHENSIVE-GUIDE.md) | [Benchmarks](../guides/MCP-PERFORMANCE-BENCHMARKS.md)

### LSP Server

- **Transport:** JSON-RPC 2.0 over stdio (LSP4J)
- **Features:** Workspace symbols, document links, hover, definition, references, code lens, diagnostics
- **Protocol version:** LSP 3.17
- **Entry point:** `synthesis-lsp-server`
- **User guides:** [Quick Start](../guides/LSP-QUICKSTART.md) | [Comprehensive Guide](../guides/LSP-COMPREHENSIVE-GUIDE.md) | [IDE Guides](../guides/LSP-IDE-INTEGRATION-GUIDES.md)

---

## Implementation Details

| Property | MCP Server | LSP Server |
|----------|-----------|------------|
| Main class | `SynthesisMCPServer` | `SynthesisLanguageServer` |
| JSON library | Jackson 2.x | Eclipse LSP4J 0.23.1 |
| Search engine | Apache Lucene 10.1.0 | Apache Lucene 10.1.0 |
| Concurrency | Single-threaded read loop | CompletableFuture (async) |
| Log file | `~/.synthesis/logs/mcp-server.log` | `~/.synthesis/logs/lsp-server.log` |
| Java requirement | 17+ | 17+ |
