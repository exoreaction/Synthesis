# Slack Setup for Synthesis Pilot Program

This guide explains how to set up the Slack integration for the Synthesis pilot licensing and telemetry system.

## Architecture

The system uses two Slack integration points:

1. **Incoming Webhook** -- For telemetry (installation events, command tracking)
2. **Bot API** -- For approval checking (reading the approval channel)

## Quick Setup (Recommended): Use App Manifest

**Fastest way:** Use the pre-configured app manifest to create the bot with all permissions automatically.

1. Go to [api.slack.com/apps](https://api.slack.com/apps)
2. Click **Create New App** > **From an app manifest**
3. Select your workspace
4. Paste the contents of [`slack-app-manifest.yaml`](./slack-app-manifest.yaml) (in this directory)
5. Click **Next** > **Create**
6. **Done!** The bot is created with all necessary permissions

Now skip to **Getting Your Tokens** below.

---

## Alternative: Manual Setup (Step-by-Step)

If you prefer manual setup or need to modify an existing app:

### Step 1: Create a Slack App

1. Go to [api.slack.com/apps](https://api.slack.com/apps)
2. Click **Create New App** > **From scratch**
3. Name: `Synthesis Pilot Manager`
4. Workspace: Select your Slack workspace
5. Click **Create App**

### Step 2: Enable Incoming Webhooks

1. In the Slack App settings, go to **Incoming Webhooks**
2. Toggle **Activate Incoming Webhooks** to On
3. *(You'll add the actual webhook in the "Getting Your Tokens" section below)*

### Step 3: Set Up Bot Permissions

1. In the Slack App settings, go to **OAuth & Permissions**
2. Under **Bot Token Scopes**, add:
   - `channels:read` -- List public channels
   - `channels:history` -- Read messages in public channels
   - `chat:write` -- Post messages
3. Click **Install to Workspace** (or **Reinstall** if already installed)

---

## Getting Your Tokens

**After setup (either manifest or manual), collect these values:**

### 1. Bot User OAuth Token (for approval checking)

1. Go to **OAuth & Permissions** in your Slack app settings
2. Copy the **Bot User OAuth Token** (starts with `xoxb-...`)
3. Save this - you'll need it for `approval.properties`

### 2. Incoming Webhook URL (for telemetry)

1. Go to **Incoming Webhooks** in your Slack app settings
2. Click **Add New Webhook to Workspace**
3. Select a channel for telemetry notifications (e.g., `#synthesis-telemetry`)
4. Copy the Webhook URL (starts with `https://hooks.slack.com/services/...`)
5. Save this - you'll need it for `telemetry.properties`

### 3. Approval Channel ID

1. Create a public channel: `#synthesis-pilots`
2. Invite the bot: `/invite @Synthesis Pilot Manager`
3. Right-click the channel name > **Copy link**
4. Extract the channel ID from the URL (the last part, starts with `C`, e.g., `C01234567`)

---

## Configure Synthesis

### Telemetry Configuration

Create or edit `~/.synthesis/telemetry.properties`:

```properties
# Slack Webhook URL for telemetry reporting
webhook_url=https://hooks.slack.com/services/YOUR/WEBHOOK/URL

# Installation timestamp (auto-generated on first init)
installed_at=2026-02-14T12:00:00Z
```

### Approval Configuration

Create `~/.synthesis/approval.properties`:

```properties
# Slack Bot Token (starts with xoxb-)
slack_bot_token=xoxb-1234567890-1234567890123-aBcDeFgHiJkLmNoPqRsTuVwX

# Slack Channel ID for the approval list (starts with C)
approval_channel_id=C01234567
```

**For pilot distribution:** Embed these values as defaults in `TelemetryConfig.java` and `ApprovalConfig.java` so pilots don't need to configure anything.

---

## Approving Pilot Users

Post messages in the `#synthesis-pilots` channel containing approved UUIDs.

### Format

The bot extracts UUIDs from any message using regex. Any of these formats work:

```
Approved: 550e8400-e29b-41d4-a716-446655440000
```

```
Approved UUIDs (Feb 14, 2026):
- 550e8400-e29b-41d4-a716-446655440000
- 12345678-1234-1234-1234-123456789abc
- abcdef12-3456-7890-abcd-ef1234567890
```

```
User 550e8400-e29b-41d4-a716-446655440000 is approved for the pilot program.
```

The bot scans the last 200 messages in the channel, extracts all UUID patterns, and checks if the user's UUID is present.

### Revoking Access

To revoke a UUID, simply delete the message containing it from the channel. On the user's next daily refresh, they will see the nag message again.

## Channels Summary

| Channel | Purpose | Integration |
|---------|---------|-------------|
| `#synthesis-telemetry` | Telemetry events (installs, commands) | Incoming Webhook |
| `#synthesis-pilots` | Approved UUID list | Bot API (read) |

## Required Bot Scopes

| Scope | Purpose |
|-------|---------|
| `channels:read` | List channels to find the approval channel |
| `channels:history` | Read messages to extract approved UUIDs |

## Troubleshooting

### "Approval system not configured"
- Ensure `~/.synthesis/approval.properties` exists
- Verify `slack_bot_token` starts with `xoxb-`
- Verify `approval_channel_id` starts with `C`

### "Could not check approval"
- Check network connectivity
- Verify the bot is invited to the approval channel
- Verify the bot token has `channels:history` scope
- Try: `synthesis telemetry --check-approval` for detailed errors

### Bot can't read channel
- Ensure the channel is public (or add `groups:history` scope for private channels)
- Ensure the bot is a member of the channel
- Re-install the Slack app if scopes were changed after installation

## Security Notes

- The **webhook URL** is write-only (can only post messages, cannot read)
- The **bot token** can only read channel history (cannot post, modify, or delete)
- Neither token provides access to DMs, files, or user data
- Store tokens securely; do not commit them to public repositories
