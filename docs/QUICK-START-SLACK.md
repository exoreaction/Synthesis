# Quick Start: Create Slack Bot in 2 Minutes

## Step 1: Create the Bot

1. Go to **https://api.slack.com/apps**
2. Click **Create New App** → **From an app manifest**
3. Select your workspace
4. **Copy and paste the entire contents** of [`slack-app-manifest.yaml`](./slack-app-manifest.yaml)
5. Click **Next** → **Create**

✅ Done! Your bot is created with all necessary permissions.

## Step 2: Get Your Tokens

### Bot Token (for reading approvals)
1. Go to **OAuth & Permissions**
2. Click **Install to Workspace** → **Allow**
3. Copy the **Bot User OAuth Token** (starts with `xoxb-...`)

### Webhook URL (for telemetry)
1. Go to **Incoming Webhooks**
2. Click **Add New Webhook to Workspace**
3. Select channel `#synthesis-telemetry` (or create it)
4. Copy the **Webhook URL** (starts with `https://hooks.slack.com/...`)

### Channel ID (for approvals)
1. Create a public channel: `#synthesis-pilots`
2. Invite the bot: `/invite @Synthesis Pilot Manager`
3. Right-click channel name → **Copy link**
4. Extract the channel ID from URL (e.g., `C01234567`)

## Step 3: Configure Synthesis

**Option A: Embed in code (for distribution)**

Edit these files before building:

`TelemetryConfig.java`:
```java
public static final String DEFAULT_WEBHOOK_URL = "https://hooks.slack.com/services/YOUR/WEBHOOK/HERE";
```

`ApprovalConfig.java`:
```java
private static final String DEFAULT_BOT_TOKEN = "xoxb-YOUR-BOT-TOKEN-HERE";
private static final String DEFAULT_APPROVAL_CHANNEL_ID = "C01234567";
```

**Option B: User configuration (for development)**

Create `~/.synthesis/telemetry.properties`:
```properties
webhook_url=https://hooks.slack.com/services/YOUR/WEBHOOK/HERE
```

Create `~/.synthesis/approval.properties`:
```properties
slack_bot_token=xoxb-YOUR-BOT-TOKEN-HERE
approval_channel_id=C01234567
```

## Step 4: Approve Users

Post approved UUIDs in `#synthesis-pilots`:

```
Approved: 550e8400-e29b-41d4-a716-446655440000
```

Or in bulk:
```
Approved UUIDs (Feb 14):
- 550e8400-e29b-41d4-a716-446655440000
- 12345678-1234-1234-1234-123456789abc
```

The bot automatically extracts UUIDs from any message in the channel.

---

**Need more details?** See [SLACK-SETUP.md](./SLACK-SETUP.md) for complete documentation.
