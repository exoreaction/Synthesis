# Synthesis Quick Reference Card

**For:** Daniel Bentes, Synapti Plugin Marketplace
**Print this page. Keep it next to your terminal.**

---

## First Time Setup

```
synthesis init .                        # Initialize workspace
synthesis scan                          # Index all files
synthesis org scan                      # Discover structure
synthesis learn --install               # Claude Code skills
```

---

## Everyday Commands

| What you want | Command |
|---------------|---------|
| Update index | `synthesis maintain` |
| Search anything | `synthesis search "query"` |
| Search code only | `synthesis search "query" --type CODE` |
| Search configs only | `synthesis search "query" --type YAML` |
| Search docs only | `synthesis search "query" --type MARKDOWN` |
| Project analysis | `synthesis analyze` |
| Workspace health | `synthesis status` |
| Watch for changes | `synthesis watch --learn` |

---

## Plugin Marketplace Searches

```
synthesis search "PluginInterface"              # Find shared interfaces
synthesis search "import.*plugin" --type CODE   # Cross-plugin imports
synthesis search "spring-boot" --type YAML      # Shared dependencies
synthesis search "database.url" --type YAML     # Config comparison
synthesis search "test" --type CODE             # Find all tests
synthesis search "README" --type MARKDOWN       # Plugin documentation
```

---

## Organizational Commands

```
synthesis org scan                      # Discover organizations
synthesis org list                      # Show hierarchy
synthesis org list --show-clients       # Show with details
synthesis org classify ~/Downloads      # Classify files
```

---

## Export & Share

```
synthesis export --format markdown      # Markdown overview
synthesis export --format json          # JSON for tooling
synthesis export --type CODE            # Code files only
```

---

## Claude Code Integration

```
synthesis learn                         # Generate skills
synthesis learn --install               # Install globally
synthesis learn --uninstall             # Remove skills
```

After installing: Claude Code knows your project structure in every session.

---

## Watch Mode

```
synthesis watch                         # Basic watching
synthesis watch --verbose               # Show all events
synthesis watch --learn                 # Auto-update skills
```

Runs in foreground. Ctrl+C to stop.

---

## Filter by Type

| Type | What it matches |
|------|-----------------|
| `CODE` | .java .kt .py .js .ts .go .rs .sh |
| `YAML` | .yaml .yml .json .toml .xml |
| `MARKDOWN` | .md .txt .rst |
| `HTML` | .html .css .scss |

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| "Not a workspace" | `synthesis init .` |
| Stale results | `synthesis scan` (full rebuild) |
| Submodules missing | `git submodule update --init --recursive && synthesis scan` |
| Too many results | Add `--type CODE` or `--type YAML` filter |
| Skills not loading | `synthesis learn --install` |

---

## Key Directories

```
.synthesis/                  # All Synthesis data (gitignore this)
  config.yaml               # Configuration (share this)
  index/                    # Search index (local only)
  skills/                   # Generated Claude Code skills
  organizations.json        # Discovered structure
~/.claude/skills/            # Installed global skills
```

---

*Synthesis v1.0.0 | eXOReaction | February 2026*
