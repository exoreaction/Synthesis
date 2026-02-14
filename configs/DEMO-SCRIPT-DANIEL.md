# Synthesis Demo Script for Daniel Bentes

**Purpose:** Step-by-step walkthrough to demonstrate Synthesis value for Synapti
**Duration:** 15-20 minutes
**Setting:** In-person at Rebel Makerspace or screen-share

---

## Pre-Demo Checklist

Before the meeting:

- [ ] Synthesis JAR built and accessible
- [ ] Daniel's Synapti workspace cloned locally (or have him share screen)
- [ ] Terminal open with clean history
- [ ] If demoing on Daniel's machine: Java 21+ installed (`java --version`)

---

## Demo Flow

### Part 1: The Problem (2 minutes)

**Script:**

> "You have a plugin marketplace. Multiple plugins, each as a submodule. When you
> are working on plugin-auth and need to understand how plugin-payments handles
> something similar, what do you do? Grep across repos? Open 5 browser tabs?
> Ask a colleague?"
>
> "What if your development environment just *knew* your entire codebase?"

**Transition:** "Let me show you what that looks like."

---

### Part 2: Setup (3 minutes)

**Run these commands live:**

```bash
# Step 1: Initialize
cd ~/synapti-marketplace    # Daniel's workspace
synthesis init .
```

**What to point out:**
- Interactive setup asks for workspace name
- Creates `.synthesis/` directory (self-contained, gitignore-able)
- Config file is human-readable YAML

```bash
# Step 2: Scan
synthesis scan
```

**What to point out:**
- Progress bar shows scanning
- File count, categories, scan time
- "This is now indexed. Every file, every line. Searchable."

**Expected output example:**
```
Scanning [==============================] 100%
Scanning complete: 847 items in 2.3s
  CODE: 412 | MARKDOWN: 89 | YAML: 156 | OTHER: 190
```

---

### Part 3: Search Power (5 minutes) -- "Wow Moments"

#### Wow Moment 1: Instant Cross-Plugin Search

```bash
synthesis search "authentication"
```

**What to point out:**
- Results from ALL plugins, not just one
- File paths show which plugin each result is from
- Content previews give immediate context
- "This just searched your entire plugin ecosystem in milliseconds."

#### Wow Moment 2: Type-Filtered Search

```bash
# Only configuration files
synthesis search "database" --type YAML

# Only code
synthesis search "handler" --type CODE

# Only documentation
synthesis search "setup" --type MARKDOWN
```

**What to point out:**
- Type filters eliminate noise
- "When you are looking for a config, you don't want code results."

#### Wow Moment 3: Cross-Plugin Dependencies

```bash
# Find all imports referencing other plugins
synthesis search "import.*plugin" --type CODE

# Find shared dependency usage
synthesis search "spring-boot" --type YAML
```

**What to point out:**
- "This shows you which plugins depend on each other."
- "Invisible dependencies become visible."

#### Wow Moment 4: Architecture Discovery

```bash
synthesis analyze
```

**What to point out:**
- Automatic project structure analysis
- File type distribution
- Key files identified
- "Synthesis understands your codebase structure, not just file contents."

---

### Part 4: Organizational Intelligence (3 minutes)

```bash
# Discover organizational structure
synthesis org scan

# View what was found
synthesis org list --show-clients
```

**What to point out:**
- Auto-discovers plugins as organizational units
- Understands directory naming conventions
- "This is organizational intelligence. Synthesis knows your business structure."

---

### Part 5: Claude Code Integration (3 minutes) -- The Killer Feature

```bash
# Generate Claude Code skills from workspace knowledge
synthesis learn
```

**Show the output:**
- Skills generated: workspace-context, navigate-clients, etc.
- Line counts show depth of knowledge

```bash
# Install to Claude Code
synthesis learn --install
```

**What to point out:**
- "Now Claude Code knows your entire plugin ecosystem."
- "When you ask Claude about your project, it already knows where everything is."
- "No more explaining your project structure every session."

**Demo in Claude Code (if available):**
- Open a new Claude Code session
- Ask: "What plugins does this marketplace have?"
- Ask: "Where is the authentication logic?"
- Claude should answer using the installed skills

---

### Part 6: Watch Mode (2 minutes)

```bash
# Start watching in verbose mode
synthesis watch --learn --verbose
```

Then (in another terminal):

```bash
# Create a new file
echo "# New Plugin" > ~/synapti-marketplace/plugin-new/README.md
```

**What to point out:**
- Watch detects the change instantly
- Index updates automatically
- With `--learn`, Claude Code skills regenerate too
- "Your knowledge infrastructure stays current automatically."

Stop the watcher with Ctrl+C.

---

### Part 7: Team Value (2 minutes)

```bash
# Export workspace overview
synthesis export --format markdown --output workspace-overview.md
```

**What to point out:**
- "Share this with your team. Everyone gets the same view."
- "New team members run `synthesis init && scan` and they are immediately productive."
- "This is team knowledge, not tribal knowledge."

---

## Key Messages to Reinforce

Throughout the demo, come back to these themes:

1. **Cross-plugin context** -- "This solves the 'where is X across all my plugins?' problem"
2. **Zero learning curve** -- "It's search. Everyone knows search."
3. **Claude Code amplifier** -- "Claude Code + Synthesis = Claude that knows your codebase"
4. **Self-maintaining** -- "Watch mode keeps everything current. Set and forget."
5. **Team-ready** -- "Export, share, onboard. Works for solo devs and teams."

---

## Handling Questions

### "How is this different from grep?"

> "Grep searches text. Synthesis understands structure. It knows that a `.java` file
> is code and a `.yaml` file is configuration. It indexes content for instant search
> instead of scanning every file each time. And it teaches Claude Code about your project."

### "Does it work with private repos?"

> "Everything runs locally. Your code never leaves your machine. The index is stored
> in `.synthesis/` in your workspace. No cloud, no API calls (unless you enable AI features)."

### "What about large monorepos?"

> "Synthesis handles thousands of files. The exclude patterns skip node_modules, build
> artifacts, etc. Incremental updates via `synthesis maintain` only process changed files."

### "Can it handle submodules?"

> "Yes. As long as submodules are checked out (`git submodule update --init --recursive`),
> Synthesis indexes them like any other directory. Cross-submodule search works naturally."

---

## Example Queries for Daniel

These are ready-to-run queries using common plugin marketplace patterns:

```bash
# Plugin discovery
synthesis search "plugin" --type YAML           # Find all plugin configs
synthesis search "manifest" --type YAML         # Find plugin manifests
synthesis search "package.json" --type JSON     # Find package definitions

# Architecture
synthesis search "interface" --type CODE        # Find interfaces/contracts
synthesis search "abstract" --type CODE         # Find abstract classes
synthesis search "extends" --type CODE          # Find inheritance

# Dependencies
synthesis search "dependency" --type YAML       # Find dependency declarations
synthesis search "import" --type CODE           # Find cross-module imports
synthesis search "require" --type CODE          # Find CommonJS requires

# Testing
synthesis search "test" --type CODE             # Find test files
synthesis search "mock" --type CODE             # Find mock setups
synthesis search "assert" --type CODE           # Find assertions

# Configuration
synthesis search "port" --type YAML             # Find port configurations
synthesis search "database" --type YAML         # Find DB configs
synthesis search "secret" --type YAML           # Find secret references (audit!)
```

---

## Post-Demo Next Steps

After the demo, propose:

1. **Immediate:** Daniel installs Synthesis on his actual workspace
2. **This week:** Run through the setup guide, report any issues
3. **Next week:** Review what worked, what needs tuning
4. **Ongoing:** Pilot feedback loop (4-8 weeks)

---

## Notes

- Keep the demo conversational, not lecture-style
- Let Daniel drive if he wants to try commands himself
- Adapt queries to his actual codebase (ask what he is working on)
- The goal is for Daniel to think "I want this now" -- not "interesting technology"
