# Organizational Model Design

**Date:** 2026-02-14
**Based on:** ORGANIZATIONAL-ANALYSIS.md findings
**Target:** Synthesis 1.1.0 - Organizational Intelligence

---

## 1. Entity Model

### Core Entities

```java
Organization {
    String name;           // "eXOReaction", "Cantara", "Quadim"
    String type;           // "company", "foundation", "holding", "concept"
    Path basePath;         // ~/Documents/eXOReaction/
    String description;    // From README.md
    List<Client> clients;
    List<Product> products;
    List<String> codebasePaths;  // ~/src/exoreaction/, etc.
    Map<String,String> metadata; // flexible key-value
}

Client {
    String name;           // "SpareBank 1", "Elprint"
    String organization;   // "eXOReaction"
    Path basePath;         // ~/Documents/eXOReaction/clients/opportunity-SpareBank1/
    ClientStatus status;   // ACTIVE, PAST, OPPORTUNITY, SIGNED
    String directoryPattern; // "opportunity-SpareBank1" or "Elprint"
    Map<String,String> metadata;
}

Product {
    String name;           // "Xorcery AAA", "Workshop"
    String organization;   // "eXOReaction"
    Path basePath;         // ~/Documents/eXOReaction/products/xorcery-aaa/
    Map<String,String> metadata;
}
```

### Enums

```java
ClientStatus { ACTIVE, PAST, OPPORTUNITY, SIGNED }
OrganizationType { COMPANY, FOUNDATION, HOLDING, CONCEPT, OTHER }
```

### Registry

```java
OrganizationRegistry {
    Map<String, Organization> organizations;

    void load(Path configPath);     // From .synthesis/organizations.json
    void save(Path configPath);     // To .synthesis/organizations.json

    Organization findOrg(String name);
    Client findClient(String name);
    List<Organization> listAll();
    List<Client> listClients(String orgName);

    boolean isPathInOrg(Path path, String orgName);
    String resolveOrg(Path filePath);    // Which org does this file belong to?
    String resolveClient(Path filePath); // Which client does this file belong to?
}
```

---

## 2. Auto-Discovery (OrganizationScanner)

### Discovery Algorithm

```
1. Scan base directory (~/Documents/) for top-level subdirectories
2. For each directory:
   a. Check if README.md exists -> extract description
   b. Check if CODEBASE-INDEX.md exists -> mark as organization
   c. Check for clients/ subdirectory -> scan for client patterns
   d. Check for products/ subdirectory -> scan for products
   e. Check for business/ subdirectory -> additional org signal
3. Client detection patterns:
   a. clients/<Name>/ -> ACTIVE client
   b. clients/<Name>-past/ -> PAST client
   c. clients/opportunity-<Name>/ -> OPPORTUNITY client
4. Product detection:
   a. products/<Name>/ -> Product
5. Codebase linking:
   a. Parse CODEBASE-INDEX.md for ~/src/ references
   b. Scan ~/src/<orgname>/ for git repositories
```

### Confidence Scoring
An organization candidate gets points:
- Has README.md: +1
- Has CODEBASE-INDEX.md: +3 (strong signal)
- Has clients/ directory: +2
- Has products/ directory: +2
- Has business/ directory: +2
- Has marketing/ directory: +1
- Has methodology/ directory: +1
- Name matches known pattern: +1

Score >= 3: Confident organization. Score 1-2: Possible (prompt user). Score 0: Skip.

---

## 3. Index Enhancement

### New Lucene Fields

```java
DocumentFields {
    // Existing
    REPOSITORY = "repository";

    // New
    ORGANIZATION = "organization";  // "eXOReaction", "Cantara", etc.
    CLIENT = "client";              // "SpareBank1", "Elprint", etc.
}
```

### Indexing Changes
- FileIndexer.createDocument() gains `organization` and `client` parameters
- ScanCommand resolves org/client for each file based on its path
- SearchIndex.search() gains organization and client filter parameters

---

## 4. New Commands

### `synthesis org scan`
Auto-discover organizational structure from ~/Documents/.

```
$ synthesis org scan

  Organizational Scan
  ============================================================

  Discovered 6 organizations:

    eXOReaction (company)
      Path: ~/Documents/eXOReaction/
      Clients: 12 (3 active, 3 past, 6 opportunities)
      Products: 5
      Codebases: ~/src/exoreaction/

    Cantara (foundation)
      Path: ~/Documents/Cantara/
      Clients: 1 (1 active)
      Products: 1
      Codebases: ~/src/cantara/

    Quadim (company)
      ...

  Saved to .synthesis/organizations.json
```

### `synthesis org list`
Display organizational hierarchy.

```
$ synthesis org list

  Organizations
  ============================================================

  eXOReaction (company) - ~/Documents/eXOReaction/
    Clients:
      ACTIVE   Elprint
      ACTIVE   Opplysningen-1881
      PAST     Entra
      PAST     CatalystOne
      PAST     Skytale
      PAST     Nooten
      SIGNED   Tvimenning
      PROSPECT SpareBank1
      PROSPECT ItemConsulting
      PROSPECT Mynder
      PROSPECT DanielBentes
      PROSPECT AndersHoibakk
    Products:
      Xorcery AAA, Workshop, Consulting, Tools, Elprint

  Cantara (foundation) - ~/Documents/Cantara/
    Clients:
      ACTIVE   realestate
    Products:
      Xorcery

  Quadim (company) - ~/Documents/Quadim/
    ...
```

### Enhanced Search with Org Filters

```
$ synthesis search "authentication" --company eXOReaction
$ synthesis search "authentication" --client "SpareBank1"
$ synthesis insights --company eXOReaction --include-clients
```

---

## 5. Downloads Integration

### Architecture

```
DownloadsClassifier
    ├── FilenameAnalyzer    - Extract org/client signals from filename
    ├── ContentAnalyzer     - Extract signals from file content (PDF text, etc.)
    └── PatternMatcher      - Match against configured routing rules

RoutingEngine
    ├── RuleEvaluator       - Apply routing rules in priority order
    ├── ConfidenceScorer    - Calculate classification confidence
    └── DestinationResolver - Determine target path

DownloadsWatcher (extends WatchCommand concepts)
    ├── Monitors ~/Downloads for new files
    ├── Classifies using DownloadsClassifier
    ├── Routes using RoutingEngine
    └── Prompts user when confidence < threshold
```

### Classification Algorithm

```
1. Analyze filename for organization keywords
   - "Quadim" in name -> org=Quadim, confidence=0.8
   - "Xorcery" in name -> org=Cantara, confidence=0.7

2. Analyze content (for text-readable files)
   - PDF: Extract first 5000 chars, scan for org names
   - Markdown: Full text scan
   - Other text: First 2000 chars

3. Analyze file type for routing hints
   - .pdf -> business/ or marketing/
   - .png/.jpg -> media/ or marketing/
   - .mp4 -> media/
   - .zip -> archive/
   - .deb -> skip (software)

4. Compute confidence score (0.0 - 1.0)
   - Multiple signals agree: high confidence
   - Single weak signal: low confidence
   - No signals: unknown (prompt user)

5. If confidence >= threshold: auto-route
   If confidence < threshold: prompt user
```

### Configuration Schema

```yaml
# .synthesis/organization-config.yaml
organizations:
  eXOReaction:
    path: ~/Documents/eXOReaction
    type: company
    auto_detect_clients: true
    client_patterns:
      - "opportunity-*"
      - "*-past"
      - "*"  # any direct child of clients/
    codebase_paths:
      - ~/src/exoreaction
      - ~/src/elprint
      - ~/src/entra
    keywords:
      - "eXOReaction"
      - "SDD"
      - "Skill-Driven"
      - "workshop"
      - "lib-pcb"

  Cantara:
    path: ~/Documents/Cantara
    type: foundation
    keywords:
      - "Cantara"
      - "Xorcery"
      - "Whydah"

  Quadim:
    path: ~/Documents/Quadim
    type: company
    keywords:
      - "Quadim"
      - "competence"
      - "skill library"
      - "CatalystOne"

downloads:
  watch_path: ~/Downloads
  classify_on_arrival: true
  confidence_threshold: 0.6
  routing_rules:
    - pattern: "*.pdf"
      destination_subdir: business/
    - pattern: "*.png"
      destination_subdir: media/
    - pattern: "*.mp4"
      destination_subdir: media/
    - pattern: "*.zip"
      destination_subdir: archive/
  skip_patterns:
    - "*.deb"
    - "*.exe"
    - "*.dmg"
    - "*.AppImage"
  prompt_on_uncertain: true
```

---

## 6. Persistence

### .synthesis/organizations.json

```json
{
  "version": 1,
  "lastScanTime": "2026-02-14T12:00:00Z",
  "organizations": [
    {
      "name": "eXOReaction",
      "type": "COMPANY",
      "basePath": "/home/totto/Documents/eXOReaction",
      "description": "Skill-Driven Development consulting company",
      "clients": [
        {
          "name": "Elprint",
          "status": "ACTIVE",
          "basePath": "/home/totto/Documents/eXOReaction/clients/Elprint"
        },
        {
          "name": "SpareBank1",
          "status": "OPPORTUNITY",
          "basePath": "/home/totto/Documents/eXOReaction/clients/opportunity-SpareBank1"
        }
      ],
      "products": [
        {
          "name": "xorcery-aaa",
          "basePath": "/home/totto/Documents/eXOReaction/products/xorcery-aaa"
        }
      ],
      "codebasePaths": ["/home/totto/src/exoreaction"],
      "keywords": ["eXOReaction", "SDD", "workshop"]
    }
  ]
}
```

---

## 7. Implementation Plan

### Phase 1: Entity Model + Registry
- Organization, Client, Product records
- OrganizationRegistry with load/save
- JSON serialization (manual, matching RepositoryManager style)

### Phase 2: Auto-Discovery Scanner
- OrganizationScanner class
- Directory structure analysis
- README.md parsing for descriptions
- Client pattern detection
- Product detection

### Phase 3: New Commands
- OrgCommand (picocli @Command with subcommands)
  - OrgScanSubcommand
  - OrgListSubcommand
- Register in SynthesisApp

### Phase 4: Index Enhancement
- Add ORGANIZATION and CLIENT fields to DocumentFields
- Update FileIndexer to accept org/client
- Update SearchIndex.search() with org/client filters
- Update SearchCommand with --company and --client options
- Update InsightsCommand with --company option

### Phase 5: Downloads Integration
- DownloadsClassifier class
- ContentClassifier (filename + content analysis)
- RoutingEngine with rule evaluation
- DownloadsWatcher (extends watch concepts)
- Add to WatchCommand or create new command

### Phase 6: Testing
- Unit tests for all new classes
- Integration tests for org discovery
- Integration tests for search filtering
- Downloads classification tests
- Target: 220+ total tests (currently 185)

---

## 8. Backward Compatibility

- All new features are additive (no existing behavior changes)
- Existing commands work identically without organizations configured
- `--company` and `--client` filters are optional
- Organizations.json is created only on `synthesis org scan`
- Downloads watching is opt-in (not default)
