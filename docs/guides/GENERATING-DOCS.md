# Generating Documentation for Your Codebase

**Use Synthesis to automatically generate documentation for your project** - onboarding guides, architecture documents, API docs, and more.

---

## Quick Start: Generate in 3 Commands

```bash
# 1. Index your codebase
cd ~/your-project
synthesis init && synthesis scan

# 2. Generate onboarding guide (no AI needed)
synthesis export --format onboarding-guide > ONBOARDING.md

# 3. Generate architecture docs (with AI if available)
synthesis export --format architecture-doc > ARCHITECTURE.md
```

**Result:** Two ready-to-use documentation files in under 60 seconds.

---

## What Can Synthesis Generate?

| Documentation Type | Command | AI Required? | Output |
|-------------------|---------|--------------|--------|
| **Onboarding Guide** | `export --format onboarding` | No (enhanced with AI) | Welcome, getting started, project layout, common tasks |
| **Architecture Documentation** | `export --format architecture` | No (enhanced with AI) | Overview, components, tech stack, data flow, entry points |
| **File Index** | `export --format markdown` | No | Complete file inventory by type |
| **JSON Export** | `export --format json` | No | Machine-readable workspace index |

---

## Generate Onboarding Guide

**Purpose:** Help new developers get productive in your codebase in hours, not weeks.

### Basic Version (No AI)

```bash
synthesis export --format onboarding-guide > docs/ONBOARDING.md
```

**What it includes:**
- Welcome message
- Getting started checklist
- Project layout (file types, directory structure)
- README locations
- Configuration files

**Example output:**
```markdown
# Onboarding Guide: MyProject

Welcome to the MyProject workspace.

## Getting Started

1. Explore the project root: `/home/user/my-project`
2. Read key documentation files listed below
3. Run `synthesis search <topic>` to find relevant files

## Start Reading Here

- `README.md` - Main project README
- `docs/getting-started.md` - Development setup guide
- `api/README.md` - API documentation

## Project Layout

**Total:** 2,450 files

- CODE: 1,823 files
- MARKDOWN: 387 files
- YAML: 156 files
- JSON: 84 files

## Configuration Files

- `pom.xml`
- `application.yml`
- `docker-compose.yml`
...
```

### AI-Enhanced Version

**Requirements:**
1. Set `ANTHROPIC_API_KEY` environment variable
2. Enable AI in config: `ai.enabled: true`

```bash
synthesis export --format onboarding-guide > docs/ONBOARDING.md
```

**What AI adds:**
- Project purpose (inferred from code patterns)
- How to build and run (detected from build files)
- Key concepts and conventions used
- Where to add features (based on code structure)
- Welcoming, practical tone

**Example AI-enhanced output:**
```markdown
# Onboarding Guide: MyProject

Welcome to MyProject, a Spring Boot microservices platform for e-commerce.

## Getting Started

### Prerequisites
Based on `pom.xml` and `Dockerfile`, you'll need:
- Java 17+
- Maven 3.8+
- Docker (for local database)

### Build and Run
```bash
mvn clean install
docker-compose up -d  # Start postgres and redis
mvn spring-boot:run
```

The application will start on http://localhost:8080

## Project Layout

### `/src/main/java/com/example/api/`
REST API controllers. Each service (users, products, orders) has its own
controller following the same pattern: Controller → Service → Repository.

### `/src/main/resources/`
Application configuration. `application.yml` has profiles for dev, test, prod.
Database migrations live in `db/migration/` (Flyway).

### `/src/test/`
Tests follow the same package structure as main code. Integration tests
are in `/src/test/integration/` and require Docker.

## Key Concepts

- **Service Layer Pattern:** Business logic lives in `*Service.java` classes
- **DTO Pattern:** API models (request/response) are separate from domain models
- **Exception Handling:** Global exception handler in `GlobalExceptionHandler.java`

## Common Tasks

### Add a new API endpoint
1. Create controller in `api/controller/` (follow `UserController.java` pattern)
2. Implement service in `service/`
3. Add repository if needed in `repository/`
4. Write integration test in `test/integration/api/`

### Add a database migration
1. Create numbered SQL file in `src/main/resources/db/migration/`
2. Follow naming: `V{version}__{description}.sql`
3. Test locally with `mvn flyway:migrate`
...
```

**Time saved:** 4-8 hours of manual documentation writing → 30 seconds of generation.

---

## Generate Architecture Documentation

**Purpose:** Document your system architecture automatically, kept fresh with each scan.

### Basic Version (No AI)

```bash
synthesis export --format architecture-doc > docs/ARCHITECTURE.md
```

**What it includes:**
- Technology stack (detected from file types)
- Directory structure (top-level overview)
- Key files (README, build files, Dockerfiles)

### AI-Enhanced Version

```bash
# With AI enabled
synthesis export --format architecture-doc > docs/ARCHITECTURE.md
```

**What AI adds:**
- Project purpose and scope
- Component relationships
- Data flow diagrams (described)
- Entry points and main flows
- Architecture patterns used

**Example AI-enhanced output:**
```markdown
# Architecture: MyProject

## Overview

MyProject is a microservices-based e-commerce platform built with Spring Boot.
The system consists of 5 services (user, product, order, payment, notification)
communicating via REST APIs and asynchronous messaging (Kafka).

## Directory Structure

### `/api/`
REST API layer with 5 controllers:
- `UserController.java` - User management (auth, profile, preferences)
- `ProductController.java` - Product catalog (search, details, inventory)
- `OrderController.java` - Order processing (cart, checkout, fulfillment)
- `PaymentController.java` - Payment integration (Stripe, PayPal)
- `NotificationController.java` - Email/SMS notifications

### `/service/`
Business logic layer. Each service encapsulates domain logic and coordinates
between repositories and external services. Services use `@Transactional` for
database operations and publish events to Kafka for async workflows.

### `/repository/`
Data access layer using Spring Data JPA. Repositories extend `JpaRepository`
and use custom queries for complex searches.

### `/model/`
Two types of models:
- `domain/` - Database entities (JPA annotated)
- `dto/` - API request/response objects (Jackson annotated)

### `/config/`
Application configuration:
- `SecurityConfig.java` - JWT authentication, CORS, method security
- `KafkaConfig.java` - Producer/consumer setup, topic definitions
- `CacheConfig.java` - Redis caching for product catalog

## Key Components

### Authentication & Authorization
- JWT-based stateless authentication
- `JwtTokenProvider.java` generates and validates tokens
- `SecurityConfig.java` secures endpoints with role-based access
- User roles: CUSTOMER, ADMIN, SUPPORT

### Order Processing Flow
1. `OrderController.createOrder()` receives cart
2. `OrderService.processOrder()` validates inventory, calculates total
3. `PaymentService.charge()` processes payment
4. `OrderService.confirmOrder()` publishes OrderCreated event to Kafka
5. `FulfillmentService` (listener) picks up event, initiates shipping

### Data Flow
```
Client → REST API → Service Layer → Repository → PostgreSQL
                      ↓
                   Kafka Event
                      ↓
              Async Listeners (Email, Analytics, Fulfillment)
```

## Technology Stack

- **Backend:** Java 17, Spring Boot 3.x, Spring Data JPA
- **Database:** PostgreSQL 14 (primary), Redis 7 (cache)
- **Messaging:** Apache Kafka 3.x
- **API:** REST (JSON), OpenAPI 3.0 documentation
- **Security:** Spring Security, JWT
- **Testing:** JUnit 5, Mockito, Testcontainers
- **Build:** Maven 3.8
- **Deployment:** Docker, Kubernetes manifests in `/k8s/`

## Entry Points

### Application Start
`Application.java` - Spring Boot main class

### API Documentation
`http://localhost:8080/swagger-ui.html` when running locally

### Configuration
`application.yml` - Main config (profiles: dev, test, prod)
...
```

**Kept fresh:** Re-run `synthesis scan && synthesis export --format architecture` after major changes. Documentation always reflects current code.

---

## Export File Index (All Formats)

### Markdown Format

```bash
synthesis export --format markdown > docs/FILE-INDEX.md
```

**Output:** File inventory grouped by type, with file sizes and summaries.

**Use for:**
- Code review reference
- Team knowledge base
- Audit/compliance (what files exist)

### JSON Format

```bash
synthesis export --format json > workspace-index.json
```

**Output:** Machine-readable workspace index.

**Use for:**
- Feeding into other tools
- Programmatic analysis
- CI/CD integration (track file growth over time)

---

## Filter by File Type

**Export only specific file types:**

```bash
# Only code files
synthesis export --type CODE > code-inventory.md

# Only markdown docs
synthesis export --type MARKDOWN > docs-inventory.md

# Only config files
synthesis export --type YAML > config-inventory.md
```

---

## Advanced: AI-Powered Analysis

**Requires AI enabled** (set `ANTHROPIC_API_KEY` and `ai.enabled: true`)

### Ask Questions About Your Codebase

```bash
synthesis ask "How does authentication work in this codebase?"
```

**Output:** AI-generated answer with file references.

**Example:**
```
Authentication in this codebase uses JWT (JSON Web Tokens) with Spring Security.

The flow is:
1. User logs in via POST /api/auth/login (AuthController.java:L42)
2. Credentials validated against UserRepository (UserService.java:L78)
3. JWT token generated by JwtTokenProvider.java:L23
4. Token returned in response, stored in client (typically localStorage)
5. Subsequent requests include token in Authorization header
6. JwtAuthenticationFilter.java:L35 intercepts requests, validates token
7. If valid, user context loaded into SecurityContext (L58)

Key files:
- SecurityConfig.java - Configures security filter chain
- JwtTokenProvider.java - Token generation and validation logic
- JwtAuthenticationFilter.java - Intercepts and validates requests
- UserDetailsServiceImpl.java - Loads user data for authentication
```

### Analyze Your Project

```bash
synthesis analyze
```

**Output:** Project health analysis with actionable recommendations.

**Example:**
```
## Project Structure
This is a well-organized Spring Boot microservices project with clear
separation of concerns (controller → service → repository pattern).

## Strengths
- Consistent package structure across all services
- Comprehensive integration test coverage (87% of controllers tested)
- All services have README files
- Docker and Kubernetes configurations present

## Issues Found

### Missing Documentation
- `api/controller/PaymentController.java` - No JavaDoc on public methods
- `service/NotificationService.java` - Complex logic, no inline comments
- `config/` directory has no README explaining configuration files

### Test Coverage Gaps
- `service/PaymentService.java` - No unit tests (only integration tests)
- `repository/OrderRepository.java` - Custom queries untested

### Code Smells
- `OrderService.java` - 487 lines (consider splitting into smaller services)
- `model/dto/OrderResponse.java` - 23 fields (consider nested DTOs)

### Configuration Issues
- `application-prod.yml` - Database password in plain text (use secrets)
- Multiple `.env` files (consolidate or document which is canonical)

## Recommendations

### Quick Wins (1-2 hours)
1. Add README to `/config/` directory explaining all configuration files
2. Move `application-prod.yml` password to environment variable
3. Add JavaDoc to public methods in PaymentController

### Medium-term (1-2 days)
4. Split OrderService.java into OrderService + OrderValidationService
5. Add unit tests for PaymentService (mock external payment API)
6. Refactor OrderResponse DTO into nested objects

### Long-term (1-2 weeks)
7. Extract notification logic into separate microservice
8. Implement API versioning (current API has no version)
9. Add OpenAPI documentation generation to build process
```

---

## Workflow: Keeping Docs Fresh

**Problem:** Documentation becomes stale within weeks.

**Solution:** Regenerate from code automatically.

### Daily/Weekly (Development)

```bash
# Morning: Scan for changes
synthesis scan

# Anytime: Check what changed
synthesis search "new feature"
synthesis relate "NewFile.java"  # See what it connects to
```

### Monthly (Documentation Refresh)

```bash
# Regenerate onboarding guide
synthesis export --format onboarding > docs/ONBOARDING.md

# Regenerate architecture docs
synthesis export --format architecture > docs/ARCHITECTURE.md

# Review diff
git diff docs/
```

**If diff is significant:** Architecture changed, review with team.
**If diff is minor:** Routine updates, commit and continue.

### Quarterly (Full Audit)

```bash
# Generate analysis
synthesis analyze > analysis-$(date +%Y-%m-%d).md

# Review recommendations
cat analysis-$(date +%Y-%m-%d).md

# Track progress
diff analysis-2025-11-01.md analysis-2026-02-01.md
```

---

## Comparison: Manual vs Synthesis-Generated Docs

| Documentation Task | Manual Time | Synthesis Time | Quality |
|--------------------|-------------|----------------|---------|
| **Onboarding guide** | 4-8 hours | 30 seconds | 80-90% (AI), 60% (basic) |
| **Architecture doc** | 3-6 hours | 30 seconds | 85-95% (AI), 50% (basic) |
| **File inventory** | 2-4 hours | 10 seconds | 100% (always accurate) |
| **Project analysis** | 8-16 hours | 2 minutes | 70-80% (finds issues humans miss) |
| **Keeping docs current** | 1-2 hours/month | 1 minute/month | Always current (regenerate) |

**Time saved per quarter:** 20-40 hours → 30 minutes = **98% time reduction**

---

## Tips for Better Generated Docs

### 1. Maintain READMEs in Key Directories

**Why:** Synthesis uses README files as context for AI generation.

**Best practice:**
```bash
# Add README to each major directory
echo "# API Controllers" > api/README.md
echo "REST API endpoints for all services" >> api/README.md
```

**Result:** AI-generated docs will be more accurate (understands directory purpose).

### 2. Use Descriptive File Names

**Why:** Synthesis infers purpose from file names.

**Good:** `UserAuthenticationService.java`, `EmailNotificationService.java`
**Bad:** `Service1.java`, `Util.java`, `Helper.java`

### 3. Keep Build Files Current

**Why:** Onboarding guide includes build instructions inferred from build files.

**Ensure these exist and are accurate:**
- `pom.xml` (Maven)
- `build.gradle` (Gradle)
- `package.json` (Node)
- `requirements.txt` (Python)
- `Makefile`
- `Dockerfile`
- `docker-compose.yml`

### 4. Tag Important Files

**Why:** Helps Synthesis identify entry points.

**Best practice:**
- Name main class `Application.java`, `Main.java`, `App.java`
- Use conventional names: `README.md`, `CONTRIBUTING.md`, `ARCHITECTURE.md`

### 5. Scan Regularly

**Why:** Fresh index = accurate docs.

**Best practice:**
```bash
# Add to .git/hooks/post-commit
#!/bin/bash
synthesis scan --quiet &
```

**Result:** Index always current, docs always accurate when regenerated.

---

## FAQ: Documentation Generation

### Q: Does AI see my entire codebase?

**A:** No. AI receives a file index (paths, sizes, summaries), not full code.

**What AI sees:**
```
src/main/java/UserService.java [CODE] (Java) 12.3 KB - User management service
src/main/java/OrderService.java [CODE] (Java) 34.5 KB - Order processing logic
...
```

**What AI does NOT see:** The actual code content (unless you use `synthesis ask` with specific files).

### Q: Can I generate docs without AI?

**A:** Yes. Basic versions work offline with no API key.

**AI-optional:** All export formats have non-AI fallback versions (less detailed, but functional).

### Q: How do I customize the generated docs?

**A:** Generate as base, then edit.

**Workflow:**
```bash
# Generate base
synthesis export --format onboarding > ONBOARDING.md

# Customize
vim ONBOARDING.md  # Add company-specific info

# Track changes
git add ONBOARDING.md
git commit -m "docs: Add onboarding guide (generated + customized)"
```

**Best practice:** Keep generated sections clearly marked:
```markdown
<!-- AUTO-GENERATED by Synthesis - REGENERATE MONTHLY -->
## Project Layout
...
<!-- END AUTO-GENERATED -->

<!-- CUSTOM - Do not regenerate -->
## Company-Specific Setup
- VPN required: Connect to vpn.company.com
- Jira access: Request from IT
...
```

### Q: Can I run this in CI/CD?

**A:** Yes. Perfect for keeping docs fresh automatically.

**Example GitHub Actions:**
```yaml
name: Update Documentation

on:
  schedule:
    - cron: '0 0 1 * *'  # Monthly, 1st of month
  workflow_dispatch:      # Manual trigger

jobs:
  docs:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Install Synthesis
        run: curl -L https://github.com/exoreaction/Synthesis/releases/latest/download/synthesis.jar -o synthesis.jar

      - name: Scan codebase
        run: java -jar synthesis.jar init && java -jar synthesis.jar scan

      - name: Generate docs
        run: |
          java -jar synthesis.jar export --format onboarding > docs/ONBOARDING.md
          java -jar synthesis.jar export --format architecture > docs/ARCHITECTURE.md

      - name: Create PR if docs changed
        run: |
          if git diff --quiet docs/; then
            echo "No documentation changes"
          else
            git config user.name "Synthesis Bot"
            git config user.email "bot@synthesis.io"
            git checkout -b update-docs-$(date +%Y%m%d)
            git add docs/
            git commit -m "docs: Update generated documentation"
            git push origin update-docs-$(date +%Y%m%d)
            # Create PR via gh CLI or API
          fi
```

**Result:** Docs automatically updated monthly, reviewed via PR.

---

## Your Next Step

**Generate your first documentation in 2 minutes:**

```bash
# 1. Index your codebase
cd ~/your-project
synthesis init && synthesis scan

# 2. Generate docs
synthesis export --format onboarding > ONBOARDING.md
synthesis export --format architecture > ARCHITECTURE.md

# 3. Review
cat ONBOARDING.md
```

**If you like the output:** Add to git, share with team.

**If you want better output:** Enable AI (set `ANTHROPIC_API_KEY`), regenerate.

---

**Related documentation:**
- **User Guide:** [All Commands](./USER-GUIDE.md) - Complete command reference
- **Quick Start:** [5-Minute Intro](./QUICK-START.md) - Get started with Synthesis
- **AI Features:** [AI-Powered Features](./USER-GUIDE.md#ai-powered-features) - Enabling and using AI
