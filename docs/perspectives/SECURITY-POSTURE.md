# Synthesis Security Posture

**For Security Officers, Compliance Teams, and IT Risk Management**

---

## Executive Summary

**Synthesis is a local-first knowledge infrastructure tool with zero cloud dependency for core features.**

**Key security properties:**
- ✅ **100% local processing** for core features (scan, search, relate, graph)
- ✅ **No network calls** during normal operation
- ✅ **No telemetry by default** (opt-in only, anonymous)
- ✅ **Open source** (auditable, no black box)
- ✅ **Standard dependencies** (Apache Lucene, FFmpeg, Apache PDFBox)
- ⚠️ **AI features require opt-in** (Anthropic API key, explicit file selection)

**Risk assessment:** **Low risk** for core features, **medium risk** for AI features (if enabled)

---

## Security Architecture

### Data Flow Diagram

```
Developer's Machine (Local)
┌─────────────────────────────────────────────────┐
│                                                 │
│  Codebase Files                                 │
│  (/home/user/projects/)                         │
│         │                                        │
│         ▼                                        │
│  Synthesis Scanner                              │
│  (reads files, extracts metadata)               │
│         │                                        │
│         ▼                                        │
│  Local Index                                    │
│  (.synthesis/index/)                            │
│  - Lucene index (Apache Lucene 9.x)             │
│  - File paths, content snippets, metadata       │
│  - Stored on local filesystem                   │
│         │                                        │
│         ▼                                        │
│  Search/Relate/Graph Commands                   │
│  (query local index, no network)                │
│                                                 │
└─────────────────────────────────────────────────┘

NO NETWORK CALLS FOR CORE FEATURES ✓

Optional AI Features (Opt-In Only):
┌─────────────────────────────────────────────────┐
│  User runs: synthesis scan --with-readme        │
│  (explicit opt-in)                              │
│         │                                        │
│         ▼                                        │
│  User selects specific files to send            │
│  (not entire codebase)                          │
│         │                                        │
│         ▼                                        │
│  HTTPS → Anthropic API                          │
│  (api.anthropic.com)                            │
│  - User's own API key required                  │
│  - Only selected files sent                     │
│  - Encrypted in transit (TLS 1.3)               │
│         │                                        │
│         ▼                                        │
│  AI-generated README returned                   │
│  (saved locally)                                │
└─────────────────────────────────────────────────┘

NETWORK CALLS ONLY FOR OPT-IN AI FEATURES ⚠
```

---

## Threat Model

### Assets

| Asset | Sensitivity | Location | Protection |
|-------|-------------|----------|------------|
| **Source code** | High | User's filesystem | Not copied; indexed metadata only |
| **Lucene index** | Medium | `.synthesis/index/` | Contains file paths, content snippets |
| **Configuration** | Low-Medium | `.synthesis/config.yaml` | May contain API key if AI features enabled |
| **Bundled binaries** | Low | `~/.synthesis/bin/ffprobe` | Trusted (FFmpeg official build, checksum verified) |

### Threats & Mitigations

| Threat | Likelihood | Impact | Mitigation |
|--------|-----------|--------|------------|
| **Source code exfiltration** | Low | High | Core features never send data over network |
| **Index exfiltration** | Low | Medium | Index stored locally; protected by filesystem permissions |
| **API key leakage** | Medium | Medium | Config file should be chmod 600; never committed to git |
| **Malicious code execution** | Low | High | Open source (auditable); standard JVM sandbox |
| **Dependency vulnerabilities** | Medium | Medium | Dependencies updated regularly; see [Security Updates](#security-updates) |
| **Man-in-the-middle (AI features)** | Low | Medium | TLS 1.3 for API calls; certificate pinning possible |

---

## Data Storage & Handling

### What Synthesis Stores Locally

**`.synthesis/index/` directory:**
- **Content:** Lucene index with full-text search data
- **Contains:**
  - File paths (absolute and relative)
  - File content snippets (for search result preview)
  - Metadata (file size, type, language, creation date)
  - Relationship edges (file A imports file B)
- **Size:** 2-5% of indexed content (e.g., 9.7 MB index for 202 MB content)
- **Format:** Apache Lucene binary format (industry-standard)

**`.synthesis/config.yaml`:**
- **Content:** User preferences, include/exclude patterns, API key (if AI features enabled)
- **Sensitive data:** API key (if present)
- **Recommendation:** `chmod 600` and add to `.gitignore`

**`~/.synthesis/bin/`:**
- **Content:** Bundled ffprobe binary (video metadata extraction)
- **Source:** FFmpeg official release (johnvansickle.com)
- **Verification:** SHA256 checksum validated on extraction

### What Synthesis Does NOT Store

- ❌ Full source code (only metadata and snippets)
- ❌ Credentials or secrets from code (not indexed)
- ❌ Telemetry by default (opt-in only)
- ❌ User identity (no login, no tracking)

---

## Network Activity

### Core Features (scan, search, relate, graph)

**Network calls:** **ZERO**

All processing happens locally:
- Directory scanning: local filesystem
- Content analysis: local JVM
- Indexing: local Lucene
- Search: local index query
- Relationship mapping: local graph traversal

**Verification:**
```bash
# Run Synthesis with network disabled
sudo iptables -A OUTPUT -p tcp --dport 443 -j DROP
synthesis scan
synthesis search "test"
# Both work without network ✓
```

### AI Features (--with-readme, --synthesize)

**Network calls:** **ONLY when explicitly enabled**

**Requirements:**
1. User provides Anthropic API key (stored in `config.yaml`)
2. User runs command with `--with-readme` or `--synthesize` flag
3. User selects specific files to send (not entire codebase)

**Network destinations:**
- `api.anthropic.com` (HTTPS, TLS 1.3)
- No other external services

**Data sent:**
- Selected files only (user chooses which)
- Prompt/instructions
- User's API key (for authentication)

**Data NOT sent:**
- Entire codebase
- Index contents
- File paths outside selected files
- Telemetry (unless separately opted-in)

### Telemetry (opt-in only, disabled by default)

**Default:** Disabled

**When enabled:**
```yaml
# .synthesis/config.yaml
telemetry:
  enabled: true
```

**Data sent (anonymous):**
- Command invoked (scan, search, relate, graph)
- File count indexed
- Scan duration
- Search query count (not actual queries)
- No file paths, no code, no user identity

**Destination:** Synthesis telemetry service (analytics.exoreaction.io)

**Purpose:** Improve product based on usage patterns

**Recommendation:** Review with legal/compliance before enabling in regulated environments

---

## Security Updates

### Dependency Management

**Core dependencies:**
- Apache Lucene 9.x (full-text search)
- Apache PDFBox 3.x (PDF text extraction)
- FFmpeg 7.0.2-static (video metadata)
- JDK 17+ (runtime)

**Update policy:**
- Security patches applied within 7 days of disclosure
- Dependency updates quarterly (non-security)
- CVE monitoring via GitHub Dependabot

**Current status (as of 2026-02-14):**
- ✅ No known CVEs in dependencies
- ✅ All dependencies at latest stable versions

### Reporting Security Issues

**Contact:** security@exoreaction.io

**Response SLA:**
- Critical vulnerabilities: 24 hours acknowledgment, 7 days patch
- High vulnerabilities: 72 hours acknowledgment, 14 days patch
- Medium/Low: 7 days acknowledgment, 30 days patch

**Disclosure policy:** Coordinated disclosure (90-day embargo)

---

## Compliance Considerations

### GDPR (EU Data Protection)

**Personal data handling:**
- Synthesis does not collect personal data by default
- If source code contains PII, it is indexed locally (same risk as code in IDE)
- No transfer of data outside user's control (unless AI features used)

**Right to erasure:**
- User can delete index: `rm -rf .synthesis/`
- User can delete config: `rm .synthesis/config.yaml`
- No data stored externally (unless AI features used)

**Recommendation:** If source code contains PII, treat `.synthesis/` index with same controls as source code itself

### SOC 2 / ISO 27001

**Relevant controls:**
- **Access control:** Filesystem permissions on `.synthesis/` directory
- **Encryption at rest:** Use encrypted filesystem (dm-crypt, BitLocker)
- **Encryption in transit:** AI features use TLS 1.3
- **Audit logging:** Optional telemetry provides usage logs

**Recommendation:** Apply same access controls to `.synthesis/` as to source code directories

### HIPAA / PCI-DSS (Regulated Industries)

**Risk assessment:**
- **PHI/PCI data in code:** If present, indexed locally (same risk as IDE)
- **Data transmission:** None for core features; AI features require explicit opt-in
- **Third-party access:** None (open source, self-hosted)

**Recommendation:**
- Disable AI features in regulated environments
- Disable telemetry
- Apply filesystem encryption
- Audit code for PHI/PCI data before indexing

### NIS2 / EU Cyber Resilience Act

**Security by design:**
- Local-first architecture (minimal attack surface)
- Open source (auditable, no hidden behavior)
- Standard dependencies (well-vetted)

**Vulnerability management:**
- Dependency updates quarterly
- CVE monitoring automated
- Security patches within 7 days

**Recommendation:** Include Synthesis in software asset inventory; monitor for CVEs like any other tool

---

## Deployment Security

### Recommended Configurations

**For sensitive environments (finance, healthcare, defense):**

```yaml
# .synthesis/config.yaml
telemetry:
  enabled: false  # No telemetry

ai:
  enabled: false  # Disable AI features entirely

scan:
  excludePatterns:
    - "**/*.key"      # Exclude private keys
    - "**/*.pem"      # Exclude certificates
    - "**/.env"       # Exclude environment files
    - "**/secrets/**" # Exclude secrets directory
```

**Filesystem permissions:**
```bash
chmod 700 .synthesis/         # Only owner can read/write index
chmod 600 .synthesis/config.yaml  # Only owner can read config
```

**Network isolation (air-gapped environments):**
- Synthesis core features work offline
- Disable AI features (no API calls needed)
- Transfer via USB (JAR file is self-contained)

### Container Deployment (Zero Trust)

**Docker image (minimal attack surface):**

```dockerfile
FROM eclipse-temurin:17-jre-alpine

# Non-root user
RUN addgroup -S synthesis && adduser -S synthesis -G synthesis

# Install Synthesis
COPY synthesis.jar /usr/local/bin/synthesis.jar
RUN chmod 555 /usr/local/bin/synthesis.jar

# Drop privileges
USER synthesis
WORKDIR /workspace

# Read-only filesystem (except workspace)
ENTRYPOINT ["java", "-jar", "/usr/local/bin/synthesis.jar"]
```

**Kubernetes deployment:**
- Use read-only root filesystem
- Drop all capabilities
- Apply network policies (block egress for core features)
- Use pod security policies/standards

---

## Incident Response

### Security Incident Classification

| Severity | Definition | Example |
|----------|------------|---------|
| **Critical** | Code execution, data exfiltration | RCE vulnerability in Lucene |
| **High** | Privilege escalation, DoS | API key leaked to logs |
| **Medium** | Information disclosure | Index readable by other users |
| **Low** | Minor configuration issue | Telemetry enabled by default |

### Response Procedure

**If you discover a security issue:**

1. **Do NOT open a public GitHub issue** (responsible disclosure)
2. Email security@exoreaction.io with:
   - Description of vulnerability
   - Steps to reproduce
   - Affected versions
   - Suggested mitigation (if any)
3. Expect acknowledgment within 24-72 hours
4. Coordinate disclosure timeline (90-day embargo standard)

**If Synthesis team discloses a vulnerability:**

1. Review security advisory (published on GitHub Releases)
2. Assess impact on your environment
3. Update to patched version within SLA:
   - Critical: 7 days
   - High: 14 days
   - Medium: 30 days
4. Verify patch applied: `synthesis --version`

---

## Security Checklist for Approval

**Use this checklist to approve Synthesis for your organization:**

- [ ] **Architecture review:** Understand local-first model
- [ ] **Data flow review:** Verify no cloud dependency for core features
- [ ] **Dependency audit:** Review Apache Lucene, PDFBox, FFmpeg (all standard, well-vetted)
- [ ] **AI features decision:** Enable or disable based on risk tolerance
- [ ] **Telemetry decision:** Enable or disable based on policy
- [ ] **Access controls:** Apply filesystem permissions (`chmod 700 .synthesis/`)
- [ ] **Secrets exclusion:** Configure excludePatterns for .env, .key, secrets/
- [ ] **Encryption at rest:** Use encrypted filesystem if required
- [ ] **Network policies:** Block egress if air-gapped (optional, core features work offline)
- [ ] **Incident response:** Add security@exoreaction.io to contact list
- [ ] **Software inventory:** Add Synthesis to asset management system
- [ ] **CVE monitoring:** Subscribe to GitHub Releases for security updates

---

## Frequently Asked Questions (Security)

### Q: Does Synthesis upload our code to the cloud?

**A:** No. Core features (scan, search, relate, graph) are 100% local. No network calls.

AI features (optional, opt-in) send only files you explicitly select to Anthropic API using your own API key.

### Q: Where is the index stored?

**A:** Locally in `.synthesis/` directory in your workspace. Same machine as your source code.

### Q: Can other users on the same machine access the index?

**A:** By default, yes (standard filesystem permissions). Recommendation: `chmod 700 .synthesis/` to restrict to owner only.

### Q: What if we have secrets in our code?

**A:** Configure excludePatterns in `.synthesis/config.yaml` to skip files containing secrets (.env, .key, .pem, etc.).

Synthesis does not automatically detect or redact secrets—it indexes what you tell it to index.

### Q: Is Synthesis PCI-DSS / HIPAA / SOC 2 compliant?

**A:** Synthesis is a tool, not a service, so compliance applies to how YOU use it, not to Synthesis itself.

If your source code contains regulated data, apply the same controls to `.synthesis/` (encryption, access controls) as to source code.

### Q: Can we use Synthesis in an air-gapped environment?

**A:** Yes. Core features work completely offline. Transfer the JAR file via USB. Disable AI features (no API needed).

### Q: How do we know the FFmpeg binary bundled with Synthesis is safe?

**A:** The bundled ffprobe is the official FFmpeg static build from johnvansickle.com (trusted source used by thousands of projects).

SHA256 checksum is verified on extraction. You can also audit the source: it's in the JAR file under `resources/binaries/`.

### Q: What happens if Anthropic API is compromised (for AI features)?

**A:** AI features send only files you explicitly select (not entire codebase). Even if Anthropic were compromised, exposure is limited to what you chose to send.

Recommendation for sensitive environments: Disable AI features entirely.

---

## Your Next Step

**For security approval:**

1. **Review this document** with your security/compliance team
2. **Complete the security checklist** above
3. **Run a pilot** in a non-production environment (test on sample code, not production)
4. **Audit the configuration** (excludePatterns for secrets, telemetry off, AI features off if not needed)
5. **Approve for production** once pilot passes review

**For questions:** security@exoreaction.io

---

**Related documentation:**
- **For deployment patterns:** [DevOps Guide](./DEVOPS.md) - includes Docker and Kubernetes deployment
- **For data handling:** [User Guide](../guides/USER-GUIDE.md) - configuration details
- **For AI features:** [User Guide](../guides/USER-GUIDE.md#ai-powered-features) - opt-in process
- **For updates:** [GitHub Releases](https://github.com/exoreaction/Synthesis/releases) - security advisories published here
