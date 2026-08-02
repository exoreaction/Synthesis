# Synthesis Documentation Guides

**Welcome to Synthesis** - Your AI-powered knowledge infrastructure for code and documentation.

---

## 📚 Available Guides

### 🚀 [Quick Start Guide](./QUICK-START.md) **(Start Here!)**
**Get up and running in 5 minutes**

Perfect for:
- First-time users
- Quick setup and installation
- Essential commands (init, scan, search)
- Daily workflow basics

**What you'll learn:**
- Install Synthesis
- Index your first project
- Search across code and docs
- Basic relationship mapping

**Time required:** 5-10 minutes

---

### 🎯 [Documentation by Role](../perspectives/README.md) **(Not a developer?)**
**Find the guide written for your perspective**

If you are an Engineering Manager, Architect, or Executive, start here instead:
- **[Executive Brief](../perspectives/EXECUTIVE.md)** -- 5-min strategic overview, ROI, decision framework
- **[Engineering Manager Guide](../perspectives/ENGINEERING-MANAGER.md)** -- 10-min team adoption playbook with metrics
- **[Architecture Intelligence Guide](../perspectives/ARCHITECT.md)** -- 12-min dependency mapping and governance integration

These documents are role-specific: no CLI commands for executives, no code for managers, architecture diagrams for architects.

---

### 📖 [Complete User Guide](./USER-GUIDE.md)
**Comprehensive documentation for power users**

Perfect for:
- Learning all features
- Advanced workflows
- Troubleshooting
- Real-world use cases

**Contents:**
- **Core Concepts:** Workspaces, indexes, relationships
- **Essential Commands:** Full command reference with examples
- **Advanced Features:** Media support, AI features, graphs
- **Real-World Use Cases:** 6 practical scenarios
- **Pro Tips:** Aliases, workflows, IDE integration
- **Troubleshooting:** Common issues and solutions
- **FAQ:** 20+ frequently asked questions

**Length:** 39 KB, ~1,600 lines, comprehensive

---

### Protocol Integration Guides

#### MCP Server (AI Agent Integration)

Connect Synthesis to Claude Code, Cursor, Aider, and other MCP-compatible AI agents.

| Document | Audience | Time |
|----------|----------|------|
| **[MCP Quick Start](./MCP-QUICKSTART.md)** | Developers | 5 min |
| **[MCP Comprehensive Guide](./MCP-COMPREHENSIVE-GUIDE.md)** | Power users, enterprise architects | 20 min |
| **[MCP Performance Benchmarks](./MCP-PERFORMANCE-BENCHMARKS.md)** | Platform partners, performance engineers | 10 min |
| **[MCP Protocol Reference](../api/MCP-PROTOCOL-REFERENCE.md)** | Platform engineers, MCP client developers | 15 min |

#### LSP Server (IDE Integration)

Bring Synthesis intelligence directly into your IDE: VSCode, IntelliJ, Neovim, Vim, Emacs.

| Document | Audience | Time |
|----------|----------|------|
| **[LSP Quick Start](./LSP-QUICKSTART.md)** | Developers | 5 min |
| **[LSP Comprehensive Guide](./LSP-COMPREHENSIVE-GUIDE.md)** | Power users, enterprise architects | 20 min |
| **[LSP IDE Integration Guides](./LSP-IDE-INTEGRATION-GUIDES.md)** | Developers (per-IDE setup) | 5 min/IDE |
| **[LSP Protocol Reference](../api/LSP-PROTOCOL-REFERENCE.md)** | IDE extension developers | 15 min |

#### API Reference

| Document | Description |
|----------|-------------|
| **[API Reference Hub](../api/README.md)** | Index of all protocol-level documentation |

---

## Which Guide Should I Read?

### "I'm brand new to Synthesis"
→ **Start with [Quick Start Guide](./QUICK-START.md)**

Get up and running in 5 minutes. Learn the three essential commands (init, scan, search).

---

### "I want to learn everything"
→ **Read [Complete User Guide](./USER-GUIDE.md)**

Deep dive into all features, use cases, and advanced workflows.

---

### "I'm an engineering manager / architect / executive"
→ **Start with [Documentation by Role](../perspectives/README.md)**

Role-specific guides that address your concerns without requiring CLI knowledge.

---

### "I want to use Synthesis with Claude Code or Cursor"
> **Start with [MCP Quick Start](./MCP-QUICKSTART.md)**

Set up the MCP server in 5 minutes. Gives AI agents access to search, relate, graph, and stats tools.

---

### "I want to use Synthesis in my IDE"
> **Start with [LSP Quick Start](./LSP-QUICKSTART.md)**

Set up the LSP server in 5 minutes. Provides workspace symbols, document links, hover, diagnostics, and more.

---

### "I'm a platform engineer evaluating Synthesis for integration"
> **Read the [MCP Protocol Reference](../api/MCP-PROTOCOL-REFERENCE.md) and [LSP Protocol Reference](../api/LSP-PROTOCOL-REFERENCE.md)**

Full JSON-RPC protocol details, tool schemas, and capability advertisements.

---

### "I have a specific question"
→ **Jump to the relevant section:**

**Installation & Setup:**
- Quick Start: [Installation](./QUICK-START.md#installation-30-seconds)
- User Guide: [Getting Started](./USER-GUIDE.md#getting-started-in-5-minutes)

**Commands:**
- Quick Start: [Your First 3 Commands](./QUICK-START.md#your-first-3-commands-3-minutes)
- User Guide: [Essential Commands](./USER-GUIDE.md#essential-commands)

**Relationships & Graphs:**
- Quick Start: [See File Relationships](./QUICK-START.md#see-file-relationships)
- User Guide: [synthesis relate](./USER-GUIDE.md#synthesis-relate---find-relationships)
- User Guide: [synthesis graph](./USER-GUIDE.md#synthesis-graph---visualize-knowledge-graphs)

**Advanced Features:**
- User Guide: [Media Support](./USER-GUIDE.md#media-support-batteries-included)
- User Guide: [AI-Powered Features](./USER-GUIDE.md#ai-powered-features)
- User Guide: [Configuration Deep Dive](./USER-GUIDE.md#configuration-deep-dive)

**Use Cases:**
- User Guide: [Real-World Use Cases](./USER-GUIDE.md#real-world-use-cases)
- Quick Start: [Common Use Cases](./QUICK-START.md#common-use-cases)

**Troubleshooting:**
- Quick Start: [Troubleshooting](./QUICK-START.md#troubleshooting)
- User Guide: [Troubleshooting](./USER-GUIDE.md#troubleshooting)
- User Guide: [FAQ](./USER-GUIDE.md#faq)

---

## 🏃 Quick Navigation

### Most Common Tasks

| Task | Quick Answer |
|------|--------------|
| **Install Synthesis** | `curl -L <url> -o synthesis.jar` → [Quick Start](./QUICK-START.md#installation-30-seconds) |
| **First-time setup** | `synthesis init && synthesis scan` → [Quick Start](./QUICK-START.md#your-first-3-commands-3-minutes) |
| **Search for something** | `synthesis search "query"` → [Quick Start](./QUICK-START.md#3-search-for-anything) |
| **See what depends on a file** | `synthesis relate <file>` → [User Guide](./USER-GUIDE.md#synthesis-relate---find-relationships) |
| **Generate architecture diagram** | `synthesis graph --modules --format mermaid` → [User Guide](./USER-GUIDE.md#synthesis-graph---visualize-knowledge-graphs) |
| **Update index after changes** | `synthesis scan` → [Quick Start](./QUICK-START.md#when-to-re-scan) |
| **Fix "No search results"** | Check config includePatterns → [Troubleshooting](./USER-GUIDE.md#issue-no-search-results) |
| **Support videos/media** | Works automatically (bundled ffprobe) → [User Guide](./USER-GUIDE.md#media-support-batteries-included) |

---

## 💡 Key Features

### 1. **Universal Search**
Search across code, docs, videos, PDFs, configs - all in one place.

```bash
synthesis search "authentication"
# Finds: code files, markdown docs, config files, PDF documentation
```

### 2. **Bi-Directional Relationships**
See what a file imports AND what imports it.

```bash
synthesis relate "AuthService.java"
# Shows: 5 imports → AuthService ← 8 references
```

### 3. **Knowledge Graphs**
Auto-generate architecture diagrams from your code.

```bash
synthesis graph --modules --format mermaid > architecture.md
# Creates: Mermaid diagram showing module dependencies
```

### 4. **Multi-Format Support**
- **Code:** .java, .py, .js, .ts, .go, .rs, .kt, .scala, .c, .cpp, .cs, .php, .rb, .sh
- **Docs:** .md, .pdf, .txt
- **Config:** .yaml, .json, .toml, .xml
- **Media:** .png, .jpg, .svg, .mp4, .mov, .mp3

### 5. **Batteries Included**
- ✅ Video metadata extraction (bundled ffprobe)
- ✅ PDF full-text search
- ✅ Image dimension detection
- ✅ Works completely offline

### 6. **Fast & Efficient**
- 200-500 files/second indexing
- Sub-second search across 10,000+ files
- 2-5% storage overhead

---

## 🎓 Learning Path

### Level 1: Beginner (5 minutes)
**Goal:** Get Synthesis working

1. Read [Quick Start Guide](./QUICK-START.md)
2. Run: `synthesis init && synthesis scan`
3. Try: `synthesis search "something"`

**You can now:** Search your entire project instantly.

---

### Level 2: Intermediate (30 minutes)
**Goal:** Use relationships and graphs

1. Read Quick Start sections:
   - [See File Relationships](./QUICK-START.md#see-file-relationships)
   - [Visualize Architecture](./QUICK-START.md#visualize-architecture)
2. Try: `synthesis relate <your-file>`
3. Try: `synthesis graph --modules --format mermaid`

**You can now:** Analyze impact, generate architecture diagrams.

---

### Level 3: Advanced (1-2 hours)
**Goal:** Master all features

1. Read [Complete User Guide](./USER-GUIDE.md):
   - [Advanced Features](./USER-GUIDE.md#advanced-features)
   - [Real-World Use Cases](./USER-GUIDE.md#real-world-use-cases)
   - [Pro Tips & Workflows](./USER-GUIDE.md#pro-tips--workflows)
2. Customize `.synthesis/config.yaml` for your project
3. Set up shell aliases
4. Integrate with your IDE

**You can now:** Use Synthesis like a pro, customize everything, handle edge cases.

---

## 📋 Quick Command Reference

```bash
# Setup
synthesis init                     # Initialize workspace
synthesis status                   # Check workspace status

# Indexing
synthesis scan                     # Scan files (incremental)
synthesis scan --full              # Full rebuild
synthesis scan --verbose           # Show detailed progress

# Searching
synthesis search "query"           # Search everything
synthesis search "multi word"      # Multi-word search

# Relationships
synthesis relate <file>            # Show connections
synthesis relate <file> --depth 2  # Follow 2 levels
synthesis relate <file> --mermaid  # Mermaid diagram

# Graphs
synthesis graph --modules --format mermaid       # Module graph
synthesis graph <file> --depth 2 --format svg   # File graph
synthesis graph --cross-repo --format png       # Repo graph

# Help
synthesis --help                   # Main help
synthesis <command> --help         # Command-specific help
```

---

## 🆘 Getting Help

### Documentation
- **Quick Start:** [QUICK-START.md](./QUICK-START.md) - 5-minute introduction
- **User Guide:** [USER-GUIDE.md](./USER-GUIDE.md) - Complete documentation
- **This README:** You are here!

### Command-Line Help
```bash
synthesis --help                # Overview
synthesis scan --help           # Scan command help
synthesis search --help         # Search command help
synthesis relate --help         # Relate command help
synthesis graph --help          # Graph command help
```

### Community Support
- **GitHub Issues:** https://github.com/exoreaction/Synthesis/issues
- **Discussions:** https://github.com/exoreaction/Synthesis/discussions
- **Email:** support@exoreaction.io

### Troubleshooting
Most common issues are covered in:
- [Quick Start Troubleshooting](./QUICK-START.md#troubleshooting)
- [User Guide Troubleshooting](./USER-GUIDE.md#troubleshooting)
- [User Guide FAQ](./USER-GUIDE.md#faq)

---

## 🎯 Use Case Examples

### Software Development
- **Codebase onboarding:** Generate architecture diagrams, search for patterns
- **Impact analysis:** See what breaks before refactoring
- **Technical debt:** Find all TODOs, deprecated code
- **Code navigation:** Trace dependencies and references

### Documentation
- **Content discovery:** Search across markdown, PDFs, videos
- **Link validation:** See which docs reference each other
- **Content maintenance:** Find outdated references

### Multi-Repository Projects
- **Dependency analysis:** Map cross-repo dependencies
- **Service architecture:** Visualize microservice relationships
- **Shared library usage:** Find all consumers of a library

### Research & Analysis
- **Knowledge extraction:** Index research papers, notes, media
- **Topic exploration:** Search across all formats
- **Reference tracking:** See citation networks

---

## 📊 Performance Expectations

| Project Size | Files | Scan Time | Index Size | Search Time |
|--------------|-------|-----------|------------|-------------|
| **Small** | <1,000 | <1s | <5 MB | <100ms |
| **Medium** | 1,000-10,000 | 5-30s | 5-50 MB | <500ms |
| **Large** | 10,000+ | 30-120s | 50-200 MB | <1s |

**Hardware tested:** Standard laptop (16 GB RAM, SSD)

---

## 🔒 Privacy & Security

### Data Storage
- **Local only:** All data stored in `.synthesis/` directory
- **No cloud:** Core features work completely offline
- **No tracking:** Anonymous telemetry only (can be disabled)

### AI Features (Optional)
- **Opt-in:** Disabled by default
- **API required:** Need Anthropic API key
- **Explicit:** Only when enabled + `--with-readme` or `--synthesize` flags
- **Selective:** Only selected files sent to API

### Open Source
- **Code:** Available on GitHub
- **Auditable:** Review what it does
- **Extensible:** Fork and customize

---

## 🚀 What's Next?

### Start Using Synthesis
1. **New users:** Read [Quick Start Guide](./QUICK-START.md)
2. **Advanced users:** Read [Complete User Guide](./USER-GUIDE.md)
3. **Install:** Follow installation instructions
4. **Explore:** Try it on your project!

### Learn More
- **Architecture:** `../ARCHITECTURE.md` - How Synthesis works internally
- **Contributing:** `../../CONTRIBUTING.md` - Help improve Synthesis
- **Changelog:** `../../CHANGELOG.md` - Version history

### Stay Updated
- **Releases:** https://github.com/exoreaction/Synthesis/releases
- **Blog:** https://exoreaction.io/blog
- **Newsletter:** Subscribe at https://exoreaction.io

---

## 💬 Feedback

We'd love to hear from you!

**Found a bug?** → [Open an issue](https://github.com/exoreaction/Synthesis/issues)

**Have a question?** → [Start a discussion](https://github.com/exoreaction/Synthesis/discussions)

**Feature request?** → [Open an issue](https://github.com/exoreaction/Synthesis/issues) with "Feature Request" label

**Success story?** → Share in [Discussions](https://github.com/exoreaction/Synthesis/discussions)

---

**🎉 Welcome to Synthesis!**

Transform your codebase into an intelligent knowledge graph.

**Start here:** [Quick Start Guide](./QUICK-START.md) → 5 minutes to your first indexed project.
