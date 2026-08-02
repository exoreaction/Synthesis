# Synthesis Documentation: Choose Your Perspective

**Synthesis solves different problems for different roles.** Start with the guide written for yours.

**Version:** 1.15.0

---

## Who Are You?

| I am a... | Start here | Time | What you will learn |
|-----------|------------|------|---------------------|
| **Executive / CEO** | [Executive Guide](./EXECUTIVE.md) | 8 min | Business reports, pipeline, decisions, the `exo` command |
| **Developer** | [Developer Guide](./DEVELOPER.md) | 12 min | Search, relate, impact analysis, architecture, git integration, daily workflow |
| **Engineering Manager** | [Engineering Manager Guide](./ENGINEERING-MANAGER.md) | 10 min | Codebase health, onboarding, adoption playbook, research reports |
| **Architect** | [Architect Guide](./ARCHITECT.md) | 12 min | Dependency graphs, anti-patterns, cross-repo mapping, governance |
| **DevOps / Platform Eng** | [DevOps Guide](./DEVOPS.md) | 12 min | CI/CD, credentials, watch mode, Docker, staging, editions |
| **Product Manager** | [Product Manager Guide](./PRODUCT-MANAGER.md) | 10 min | Business reports, event tracking, content search, org intelligence |
| **Workshop Facilitator** | [Workshop Facilitator Guide](./WORKSHOP-FACILITATOR.md) | 15 min | 2-8 hour plans, 5-minute demo script, exercises, self-organizing workspace, proof of methodology |
| **AI Agent Developer** | [AI Agent Guide](./AI-AGENT.md) | 15 min | MCP/LSP setup, CLI patterns, tool schemas, `exo ask` RAG loop, directory identities, knowledge edges, agent best practices |

**Complete command reference:** [User Guide v2](../guides/USER-GUIDE.md) -- all commands, configuration, editions, credentials.

---

## How These Guides Relate

```
                     EXECUTIVE.md
                   "What happened this week?"
                          |
              +-----------+-----------+
              |                       |
     ENGINEERING-MANAGER.md    PRODUCT-MANAGER.md
     "Is the codebase healthy?"  "Where are our materials?"
              |                       |
     +--------+--------+             |
     |                 |             |
  ARCHITECT.md    DEVELOPER.md      |
  "What depends    "How do I       |
   on what?"        find things?"  |
     |                 |             |
     +--------+--------+-------------+
              |
         DEVOPS.md
         "How do we automate this?"
              |
        AI-AGENT.md
        "How do agents use this?"
```

**Top-down:** Executive reads their guide, shares the Engineering Manager guide with their leads, who share the Developer guide with their teams.

**Bottom-up:** Developer installs Synthesis, sees value, shares the Engineering Manager guide upward.

**Lateral:** Architect and DevOps guides complement each other -- one focuses on analysis, the other on automation.

---

## Quick Links by Question

| Question | Guide | Section |
|----------|-------|---------|
| "What happened this week?" | [Executive](./EXECUTIVE.md) | Daily Commands |
| "What decisions need my attention?" | [Executive](./EXECUTIVE.md) | Daily Commands |
| "How do I install and use Synthesis?" | [Developer](./DEVELOPER.md) | Daily Workflow |
| "What are all the commands?" | [User Guide v2](../guides/USER-GUIDE.md) | Command Reference |
| "How do I roll this out to my team?" | [Engineering Manager](./ENGINEERING-MANAGER.md) | Adoption Playbook |
| "What metrics should I track?" | [Engineering Manager](./ENGINEERING-MANAGER.md) | Success Dashboard |
| "Can it map our microservices?" | [Architect](./ARCHITECT.md) | Cross-Repository Dependency Mapping |
| "How do I detect anti-patterns?" | [Architect](./ARCHITECT.md) | Anti-Pattern Detection |
| "How do I integrate with CI/CD?" | [DevOps](./DEVOPS.md) | CI/CD Integration |
| "How do I store API keys safely?" | [DevOps](./DEVOPS.md) | Credentials Management |
| "How do I generate business reports?" | [Product Manager](./PRODUCT-MANAGER.md) | Business Reports |
| "How do I track events and deadlines?" | [Product Manager](./PRODUCT-MANAGER.md) | Tracking Events and Deadlines |
| "How do I run a workshop?" | [Workshop Facilitator](./WORKSHOP-FACILITATOR.md) | Workshop Structure |
| "How do I teach co-change analysis?" | [Workshop Facilitator](./WORKSHOP-FACILITATOR.md) | Module 8: Co-Change Analysis |
| "How do AI agents connect to Synthesis?" | [AI Agent](./AI-AGENT.md) | MCP Server Integration |
| "How do I integrate with my IDE?" | [AI Agent](./AI-AGENT.md) | LSP Server Integration |
| "How do agents use directory identities?" | [AI Agent](./AI-AGENT.md) | Directory Identity System |
| "How does the exo ask RAG loop work?" | [AI Agent](./AI-AGENT.md) | The `exo ask` Conversational RAG Loop |
| "Is it secure?" | [DevOps](./DEVOPS.md) | Security Model |
| "How do I scan for security vulnerabilities?" | [Architect](./ARCHITECT.md) | Security Analysis (CKG-5) |
| "How do I detect AI-specific security risks?" | [Architect](./ARCHITECT.md) | Agentic AI-Specific Signals |
| "How do I add security scanning to CI?" | [DevOps](./DEVOPS.md) | Security Scanning in CI/CD |

---

## All Documentation

### Perspective Guides (v1.15.0)

| Guide | Audience | Key topics |
|-------|----------|------------|
| [Executive](./EXECUTIVE.md) | CEO, VP, board | `exo` command, reports, decisions, pipeline, security posture |
| [Developer](./DEVELOPER.md) | Software engineers | search, relate, impact, architecture, security analysis, git, multi-workspace |
| [Engineering Manager](./ENGINEERING-MANAGER.md) | Team leads, managers | health metrics, security scanning, onboarding, adoption, research, ROI |
| [Architect](./ARCHITECT.md) | System/software architects | dependency graphs, anti-patterns, CKG-5 security, agentic AI signals, governance |
| [DevOps](./DEVOPS.md) | Platform eng, SRE, ops | CI/CD, security CI gates, credentials, Docker, staging, editions |
| [Product Manager](./PRODUCT-MANAGER.md) | Product managers | reports, upcoming, org intelligence, content management |
| [Workshop Facilitator](./WORKSHOP-FACILITATOR.md) | Trainers, facilitators | 2/4/8-hour plans, demo script, co-change analysis, self-organizing workspace, temporal summaries |
| [AI Agent](./AI-AGENT.md) | Agent developers, integrators | MCP, LSP, CLI patterns, tool schemas, exo ask, directory identities, knowledge edges, staging pipeline |

### Master Reference

- [User Guide v2](../guides/USER-GUIDE.md) -- Complete command reference for all commands, configuration, editions, credentials, environment variables

### Quick Start

- [Quick Start](../guides/QUICK-START.md) -- 5-minute hands-on introduction (install, init, scan, search)

### Protocol Integration

- [MCP Quick Start](../guides/MCP-QUICKSTART.md) -- 5-minute AI agent integration (Claude Desktop, Claude Code, Cursor)
- [MCP Comprehensive Guide](../guides/MCP-COMPREHENSIVE-GUIDE.md) -- Full MCP tool reference, configuration, troubleshooting
- [MCP Performance Benchmarks](../guides/MCP-PERFORMANCE-BENCHMARKS.md) -- Response times, scaling, agent productivity metrics
- [LSP Quick Start](../guides/LSP-QUICKSTART.md) -- 5-minute IDE integration (VS Code, IntelliJ, Neovim, Vim, Emacs)
- [LSP Comprehensive Guide](../guides/LSP-COMPREHENSIVE-GUIDE.md) -- Full LSP feature reference, configuration, troubleshooting
- [IDE Integration Guides](../guides/LSP-IDE-INTEGRATION-GUIDES.md) -- Per-IDE setup instructions
- [API Reference Hub](../api/README.md) -- Protocol-level documentation for MCP and LSP

### Visual Assets

- [Infographics and Presentations](https://github.com/exoreaction/Synthesis/blob/main/docs/visuals/README.md) -- NotebookLM-generated visuals (sales decks, infographics, workshop slides)

### Previous Versions (Retained)

These files predate the v1.8.0 documentation rewrite. They are retained for reference but may contain outdated information.

- [User Guide v1](../guides/USER-GUIDE.md) -- Original command reference (superseded by [User Guide v2](../guides/USER-GUIDE.md))

---

**Synthesis v1.15.0** -- [GitHub](https://github.com/exoreaction/Synthesis)
