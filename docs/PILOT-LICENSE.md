# Synthesis Pilot Program

## Overview

Synthesis uses a **pilot licensing system** to track installations and usage during the pilot program. The system has two components:

1. **Mandatory Telemetry** -- Operational metadata is reported for all pilot installations
2. **Pilot Approval** -- Installations are identified by UUID and approved via a Slack channel

## How It Works

### Installation

When you run `synthesis init`, the system:

1. Generates a unique **Client UUID** (random, not linked to your identity)
2. Reports the installation to the pilot telemetry channel
3. Displays your UUID so you can request approval

```
$ synthesis init
Initializing Synthesis workspace...
...

Pilot Program Registration

  Your Synthesis UUID: 550e8400-e29b-41d4-a716-446655440000
  Provide this UUID to the maintainer for pilot approval.

  Telemetry: Active (mandatory for pilot program)
  Run 'synthesis telemetry --show' to see what data is sent.
```

### Approval Process

1. **You** share your UUID with the Synthesis maintainer
2. **The maintainer** adds your UUID to the `#synthesis-pilots` Slack channel
3. **Synthesis** checks the channel daily and caches your approval status
4. Once approved, the nag message disappears

### Before Approval (Soft Enforcement)

Unapproved installations see a one-line nag message before each command. Commands still execute normally.

```
$ synthesis scan
  Warning: Synthesis pilot approval pending. UUID: 550e8400-.... Contact maintainer for access.
Scanning workspace...
[command executes normally]
```

### After Approval

```
$ synthesis scan
  Checkmark: Pilot approved -- Thank you for participating!
Scanning workspace...
[no further nag messages]
```

## Telemetry Details

### What IS Sent

| Data | Example | Purpose |
|------|---------|---------|
| Client UUID | `550e8400-e29b-41d4-...` | Random identifier (not linked to identity) |
| Command name | `scan`, `search`, `init` | Usage patterns |
| Command success/failure | `true` / `false` | Reliability tracking |
| Command duration | `1234` (milliseconds) | Performance monitoring |
| OS name & version | `Linux 6.17.0` | Environment compatibility |
| Java version | `17.0.2` | Environment compatibility |
| Synthesis version | `1.0.0-SNAPSHOT` | Version tracking |

### What is NEVER Sent

- Workspace content, file names, or file paths
- Search queries or command arguments
- API keys, credentials, or tokens
- User identity, username, or email
- Hostname, IP address, or network information
- Any workspace data whatsoever

### Why Telemetry is Mandatory

During the pilot program, telemetry helps the team:
- Track how many installations are active
- Understand which features are used most
- Identify reliability issues early
- Make informed decisions about the product

This is a **pilot program requirement**, not an optional feature.

## Managing Your Installation

### View Status

```bash
synthesis telemetry                     # Show pilot status and approval
synthesis telemetry --show              # See exactly what data is sent
synthesis telemetry --check-approval    # Force-refresh approval status
```

### Reset UUID

If you need a new UUID (e.g., after reinstalling):

```bash
synthesis telemetry --reset-uuid
```

Note: You will need to request re-approval for the new UUID.

## Files on Disk

```
~/.synthesis/
  client-uuid              # Your random UUID (persists across workspaces)
  telemetry.properties     # Telemetry config (webhook URL, install timestamp)
  approval.properties      # Approval config (bot token, channel ID)
  approval-status          # Cached approval status (refreshed daily)
```

## Technical Details

### Approval Check Timing

- **First check:** During `synthesis init` or first command
- **Daily refresh:** First command after 24 hours triggers a re-check
- **Force refresh:** `synthesis telemetry --check-approval`
- **Cache:** Stored in `~/.synthesis/approval-status`

### Telemetry Delivery

- Events are sent via Slack incoming webhook (write-only)
- Delivery is asynchronous on a background thread
- Commands are never delayed by telemetry
- Network failures are silently ignored
- On shutdown, pending events have 2 seconds to deliver

### UUID Properties

- Random UUID v4 (no PII)
- Generated once, persisted at `~/.synthesis/client-uuid`
- Not derived from hostname, MAC address, or user identity
- Can be regenerated with `synthesis telemetry --reset-uuid`

## FAQ

**Q: Can I disable telemetry?**
A: No. Telemetry is mandatory for pilot program participation.

**Q: What happens if I'm not approved?**
A: Commands still work normally. You see a one-line nag message.

**Q: Who has access to the telemetry data?**
A: Only the Synthesis development team at eXOReaction.

**Q: Will telemetry slow down my commands?**
A: No. Events are sent asynchronously and never block command execution.

**Q: What happens if the network is down?**
A: Events are silently discarded. Telemetry never affects your workflow.

**Q: How do I get approved?**
A: Share your UUID (shown during `synthesis init` or via `synthesis telemetry`) with the Synthesis maintainer.
