# Synthesis Documentation: Choose Your Perspective

**Synthesis solves different problems for different roles.** Start with the guide written for yours.

**Version:** 1.8.0

---

## Who Are You?

| I am a... | Start here | Time | What you will learn |
|-----------|------------|------|---------------------|
| **Executive / CEO** | [Executive Guide](./EXECUTIVE.md) | 8 min | Business reports, pipeline, decisions, the `exo` command |
| **Developer** | [Developer Guide](./DEVELOPER.md) | 12 min | Search, relate, architecture, git integration, daily workflow |
| **Engineering Manager** | [Engineering Manager Guide](./ENGINEERING-MANAGER.md) | 10 min | Codebase health, onboarding, adoption playbook, research reports |
| **Architect** | [Architect Guide](./ARCHITECT.md) | 12 min | Dependency graphs, anti-patterns, cross-repo mapping, governance |
| **DevOps / Platform Eng** | [DevOps Guide](./DEVOPS.md) | 12 min | CI/CD, credentials, watch mode, Docker, staging, editions |
| **Product Manager** | [Product Manager Guide](./PRODUCT-MANAGER.md) | 10 min | Business reports, event tracking, content search, org intelligence |
| **Workshop Facilitator** | [Workshop Facilitator Guide](./WORKSHOP-FACILITATOR.md) | 15 min | 2-8 hour plans, 5-minute demo script, exercises, proof of methodology |
| **AI Agent Developer** | [AI Agent Guide](./AI-AGENT.md) | 12 min | MCP/LSP setup, CLI patterns, tool schemas, agent best practices |

**Complete command reference:** [User Guide v2](../USER-GUIDE-V2.md) -- all 37 commands, configuration, editions, credentials.

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
| "What are all the commands?" | [User Guide v2](../USER-GUIDE-V2.md) | Command Reference |
| "How do I roll this out to my team?" | [Engineering Manager](./ENGINEERING-MANAGER.md) | Adoption Playbook |
| "What metrics should I track?" | [Engineering Manager](./ENGINEERING-MANAGER.md) | Success Dashboard |
| "Can it map our microservices?" | [Architect](./ARCHITECT.md) | Cross-Repository Dependency Mapping |
| "How do I detect anti-patterns?" | [Architect](./ARCHITECT.md) | Anti-Pattern Detection |
| "How do I integrate with CI/CD?" | [DevOps](./DEVOPS.md) | CI/CD Integration |
| "How do I store API keys safely?" | [DevOps](./DEVOPS.md) | Credentials Management |
| "How do I generate business reports?" | [Product Manager](./PRODUCT-MANAGER.md) | Business Reports |
| "How do I track events and deadlines?" | [Product Manager](./PRODUCT-MANAGER.md) | Tracking Events and Deadlines |
| "How do I run a workshop?" | [Workshop Facilitator](./WORKSHOP-FACILITATOR.md) | Workshop Structure |
| "How do AI agents connect to Synthesis?" | [AI Agent](./AI-AGENT.md) | MCP Server Integration |
| "How do I integrate with my IDE?" | [AI Agent](./AI-AGENT.md) | LSP Server Integration |
| "Is it secure?" | [DevOps](./DEVOPS.md) | Security Model |

---

## All Documentation

### Perspective Guides (v1.8.0)

| Guide | Audience | Lines | Key topics |
|-------|----------|-------|------------|
| [Executive](./EXECUTIVE.md) | CEO, VP, board | 295 | `exo` command, reports, decisions, pipeline, upcoming |
| [Developer](./DEVELOPER.md) | Software engineers | 392 | search, relate, architecture, git, multi-workspace |
| [Engineering Manager](./ENGINEERING-MANAGER.md) | Team leads, managers | 301 | health metrics, onboarding, adoption, research, ROI |
| [Architect](./ARCHITECT.md) | System/software architects | 297 | dependency graphs, anti-patterns, governance, cross-repo |
| [DevOps](./DEVOPS.md) | Platform eng, SRE, ops | 424 | CI/CD, credentials, Docker, staging, editions, security |
| [Product Manager](./PRODUCT-MANAGER.md) | Product managers | 298 | reports, upcoming, org intelligence, content management |
| [Workshop Facilitator](./WORKSHOP-FACILITATOR.md) | Trainers, facilitators | 455 | 2/4/8-hour plans, demo script, exercises, follow-up |
| [AI Agent](./AI-AGENT.md) | Agent developers, integrators | 392 | MCP, LSP, CLI patterns, tool schemas, best practices |

### Master Reference

- [User Guide v2](../USER-GUIDE-V2.md) -- Complete command reference for all 37 commands, configuration, editions, credentials, environment variables

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

- [Infographics and Presentations](../visuals/README.md) -- NotebookLM-generated visuals (sales decks, infographics, workshop slides)

### Previous Versions (Retained)

These files predate the v1.8.0 documentation rewrite. They are retained for reference but may contain outdated information.

- [User Guide v1](../guides/USER-GUIDE.md) -- Original command reference (superseded by [User Guide v2](../USER-GUIDE-V2.md))

---

**Synthesis v1.8.0** -- [GitHub](https://github.com/exoreaction/Synthesis)
