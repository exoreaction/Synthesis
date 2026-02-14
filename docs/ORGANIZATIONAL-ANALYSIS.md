# Organizational Analysis for Synthesis

**Date:** 2026-02-14
**Analyzed by:** Synthesis Team
**Purpose:** Map the entity model, relationships, naming conventions, and integration points for multi-company/client organizational support.

---

## 1. Top-Level Directory Structure

```
~/Documents/
├── eXOReaction/          # PRIMARY company (consulting, SDD methodology)
├── Cantara/              # Open Source Foundation (frameworks: Xorcery, Whydah)
├── Quadim/               # Product company (competence platform SaaS)
├── Merkabit/             # Concept/IP project (theory, validation protocol)
├── Sunstone-Tech/        # Sister company (employment entity, operates Quadim)
├── T-Hex/                # Holding company (parent of eXOReaction, Sunstone Tech)
├── Synthesis/            # This tool's documentation space
├── personal/             # Personal docs (events, legal, travel)
├── archive/              # Historical materials (27 subdirs, design slides, old projects)
├── .synthesis/           # Synthesis workspace data
└── [various .md files]   # Cross-cutting documents (CLAUDE.md, ACTIVITY-LOG.md, etc.)
```

**Key Insight:** Top-level directories map 1:1 to companies/organizations. This is the primary organizational boundary.

---

## 2. Entity Model (Discovered)

### Companies (6 found)
| Name | Type | Path | Status | Notes |
|------|------|------|--------|-------|
| eXOReaction | Consulting company | ~/Documents/eXOReaction/ | Active (primary) | SDD methodology, workshop delivery |
| Cantara | Open Source Foundation | ~/Documents/Cantara/ | Active | Xorcery, Whydah frameworks |
| Quadim | Product company | ~/Documents/Quadim/ | Active | Competence management SaaS |
| Sunstone-Tech | Employment entity | ~/Documents/Sunstone-Tech/ | Active | Sister company, operates Quadim |
| T-Hex | Holding company | ~/Documents/T-Hex/ | Active | Parent company |
| Merkabit | Concept/IP project | ~/Documents/Merkabit/ | Active (conceptual) | Validation protocol, theory |

### Corporate Hierarchy
```
T-Hex Holding AS (parent)
├── eXOReaction AS (consulting, methodology)
├── Sunstone Tech AS (employment, operates Quadim)
│   └── Quadim (product platform)
└── Cantara (open source foundation - NOT T-Hex subsidiary, but closely related)
```

### Clients (by company)

#### eXOReaction Clients
| Client | Directory Pattern | Status | Notes |
|--------|------------------|--------|-------|
| Elprint | clients/Elprint/ | Active | Printing/logistics, 4 projects |
| Entra | clients/Entra-past/ | Past | Building/real estate |
| CatalystOne | clients/CatalystOne-past/ | Past | HR/HCM |
| Opplysningen 1881 | clients/Opplysningen-1881/ | Active | Directory services |
| Nooten | clients/Nooten-past/ | Past | |
| Skytale | clients/Skytale-past/ | Past | |
| SpareBank 1 | clients/opportunity-SpareBank1/ | Prospect | Financial sector |
| Item Consulting | clients/opportunity-ItemConsulting/ | Prospect | Consulting |
| Tvimenning | clients/opportunity-Tvimenning/ | Prospect (signed) | Renewable energy |
| Mynder | clients/opportunity-Mynder/ | Prospect | AI security |
| Daniel Bentes | clients/opportunity-DanielBentes/ | Prospect | Synapti |
| Anders Hoibakk | clients/opportunity-AndersHoibakk/ | Prospect | |

#### Quadim Clients
| Client | Directory Pattern | Status |
|--------|------------------|--------|
| CatalystOne | clients/CatalystOne/ | Active |

#### Cantara Clients
| Client | Directory Pattern | Status |
|--------|------------------|--------|
| Real Estate | clients/realestate/ | Active |

### Products (by company)

#### eXOReaction Products
| Product | Path | Notes |
|---------|------|-------|
| Xorcery AAA | products/xorcery-aaa/ | Alchemy + Aurora, DevSecOps Intelligence |
| Workshop | products/workshop/ | SDD training |
| Consulting | products/consulting/ | Consulting packages |
| Tools | products/tools/ | Internal tools |
| Elprint | products/Elprint/ | Client-specific product |

#### Quadim Products
| Product | Path | Notes |
|---------|------|-------|
| Platform | products/platform/ | SaaS competence platform |

#### Cantara Products
| Product | Path | Notes |
|---------|------|-------|
| Xorcery | products/xorcery/ | Reactive framework |

---

## 3. Naming Patterns

### Client Directory Patterns
Two distinct patterns observed:

1. **Active/Past clients:** `clients/<ClientName>[-past]/`
   - Examples: `clients/Elprint/`, `clients/Entra-past/`, `clients/CatalystOne-past/`
   - Suffix `-past` indicates historical/completed engagement

2. **Opportunity/Prospect clients:** `clients/opportunity-<Name>/`
   - Examples: `clients/opportunity-SpareBank1/`, `clients/opportunity-Mynder/`
   - Prefix `opportunity-` indicates pipeline/prospect status

### Standard Company Subdirectories
Consistent pattern across eXOReaction, Quadim, Cantara:
```
<company>/
├── README.md           # Company overview
├── CODEBASE-INDEX.md   # Repository inventory
├── business/           # Strategy, pipeline, opportunities
├── clients/            # Client work & relationships
├── products/           # Company products
├── marketing/          # Content, LinkedIn, materials
├── media/              # Videos, images, presentations
├── archive/            # Historical materials
├── codebase/           # Metadata about source repos (not code itself)
├── skills/             # Claude Code skills
└── [company-specific]/ # e.g., methodology/, proof-projects/, etc.
```

### Codebase Locations
Source code lives in ~/src/, NOT in ~/Documents/:
```
~/src/
├── exoreaction/
│   └── lib-pcb/        # PCB design library (proof project)
├── synthesis/          # This tool
├── cantara/            # (referenced in docs: xorcery, Whydah)
├── quadim/             # (referenced in docs: 45 microservice repos)
└── elprint/            # (referenced in docs: 25 repos)
```

---

## 4. Relationship Types

### Cross-Company Relationships
1. **Framework dependency:** Quadim uses Cantara's Whydah (SSO) and Xorcery (microservices)
2. **Service relationship:** eXOReaction provides operations to Quadim
3. **Employment:** Sunstone Tech employs team, work assigned to eXOReaction
4. **Ownership:** T-Hex owns both eXOReaction and Sunstone Tech
5. **Methodology:** SDD methodology flows from eXOReaction to all companies

### Client-Company Relationships
1. **Active client:** Ongoing work (Elprint, Opplysningen)
2. **Past client:** Completed engagement (Entra, CatalystOne, Skytale, Nooten)
3. **Opportunity/Prospect:** Pipeline leads (SpareBank 1, Mynder, Item Consulting)
4. **Signed:** Contract signed but work not yet active (Tvimenning)

---

## 5. Downloads Analysis

Current ~/Downloads/ contains 50+ files:
- **PDFs (20+):** Mix of Synthesis reports, Quadim analysis, strategy docs, external articles
- **Images (20+):** Slide series (compression, scale), screenshots
- **Videos (2):** Synthesis demo, Architecture of Intelligence
- **Archives (2):** Quadim.zip, Xorcery.zip
- **Software (1):** ferrite-editor_amd64.deb

### Classification Signals
Files can be classified by:
1. **Filename keywords:** "Quadim", "Xorcery", "Synthesis", "Knowledge_Infrastructure"
2. **Content analysis:** PDF text mentioning company/product names
3. **File naming patterns:** slide-compression-XX, slide-scale-XX (presentation series)
4. **File type:** .deb = software, .zip = archive, .pdf = document

### Routing Logic (observed from file names)
- `Quadim-Analysis-V2.pdf` -> ~/Documents/Quadim/business/
- `Knowledge_Infrastructure_*.pdf` -> ~/Documents/Synthesis/ or ~/Documents/eXOReaction/
- `Xorcery.zip` -> ~/Documents/Cantara/products/xorcery/
- `slide-compression-*.png` -> ~/Documents/eXOReaction/marketing/ or presentations
- `ferrite-editor_amd64.deb` -> software/apps (not document-related)

---

## 6. Integration Points for Synthesis

### Existing Infrastructure
- **SearchIndex** already supports `REPOSITORY` field for filtering
- **RepositoryManager** already handles multi-repo with name/path/lastScanTime
- **WatchCommand** already monitors directories for changes
- **InsightsCommand** already supports `--repo` filtering
- **SearchCommand** already supports `--repo` filtering

### Extension Strategy
1. **Organization is a higher-level concept than Repository.** An organization contains multiple repositories, clients, and products.
2. **The existing `--repo` filter pattern** can be extended to `--company` and `--client` filters.
3. **DocumentFields.REPOSITORY** can be used to store organization info (or we add a new ORGANIZATION field).
4. **WatchCommand** can be extended to watch ~/Downloads with classification logic.
5. **Configuration** can add an `organizations` section to synthesis-config.yaml.

### Key Design Decision
Add `ORGANIZATION` and `CLIENT` fields to the Lucene index (alongside existing `REPOSITORY`). This enables filtering at three levels: organization > client > repository.
