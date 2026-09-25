# Argus — JavaFX Attack Surface Management Tool — functional spec

## Project overview

Build **Argus**, a JavaFX desktop application that automates the reconnaissance phase of an
**authorized** security assessment (Attack Surface Management). Given a target domain, it
discovers subdomains, live hosts, and open ports; enriches findings with threat-intel APIs;
matches against known exploited vulnerabilities; scores attack priority; persists results with
diffing across scans; and presents everything through an animated, dark "terminal" themed GUI.

This is **discovery-only** — no exploitation, brute-forcing, or attack capability. Intended for
authorized use only (own infrastructure, or explicit written permission).

## Tech stack

- Java 21
- JavaFX (FXML + CSS views, Scene Builder-compatible)
- SQLite via `sqlite-jdbc`
- Jackson for JSON parsing
- `java.net.http.HttpClient` for API calls
- Maven build
- JUnit 5 for tests

## Architecture — three packages, one-directional dependency

```
ui  →  core  →  db
```

- **`com.argus.core`** — all business logic. **No JavaFX dependency**, so it's independently
  unit-testable and could back a future CLI.
  - `PortScanner` — multithreaded port scanning using `ExecutorService` /
    `newFixedThreadPool()`, tasks as `Callable<PortResult>`, results collected via `Future`.
  - `SubdomainEnumerator` — queries crt.name (Certificate Transparency search), no API key needed.
  - `ThreatIntelClient` — a common `IntelSource` interface (Strategy pattern) with
    implementations for VirusTotal, Shodan, AbuseIPDB, Censys. Each returns a normalized
    `IntelResult`.
  - `KevScorer` — matches findings against the CISA KEV catalog (public, no key) and computes
    an attack-priority score.
  - `ScanDiffEngine` — compares two scans and produces added/removed/changed sets.
  - Producer–consumer pipeline: scan worker threads (producers) push results onto a shared
    queue; the UI thread (consumer) drains it via `Platform.runLater()` for live updates —
    never block the JavaFX Application Thread. Implement this with explicit `wait()`/`notify()`/
    `notifyAll()` on a `synchronized` shared queue (guarded `while` loop to handle spurious
    wakeups) rather than only `BlockingQueue`, so the lab's inter-thread communication topic is
    demonstrated directly in the codebase.
  - Synchronization: any shared mutable state written by multiple scan threads (e.g. the
    in-memory findings list before it's persisted) must be protected — use `synchronized`
    methods/blocks (same lock object for all accessors) or a concurrent collection, and avoid
    classic bugs like relying on `volatile` alone for compound operations (check-then-act,
    increment counters).

- **`com.argus.db`** — SQLite persistence.
  - `ScanRepository` — save/load scan sessions.
  - `FindingDao` — persist port/subdomain/vulnerability findings.
  - `AnnotationDao` / `TagDao` — notes and tags on findings.
  - `ScheduledScanDao` — stored cron-like scan schedules.
  - Schema should include tables for: scans, findings, annotations, tags, scheduled_scans.

- **`com.argus.ui`** — JavaFX views/controllers only, no business logic. Talks to `core` and
  `db`. Uses FXML + a shared CSS theme (see Theme section).

## Core features (must-have)

1. Multithreaded port scanning
2. Subdomain enumeration via crt.name
3. Threat-intel enrichment via VirusTotal, Shodan, AbuseIPDB, Censys
4. CISA KEV matching + attack-priority scoring
5. SQLite persistence + scan diffing between runs
6. Operator authentication (login screen) + AES-encrypted local key vault for API keys — no
   plaintext credentials on disk

## APIs to integrate

| API | Purpose | Auth |
|---|---|---|
| crt.name | Subdomain enumeration | none |
| VirusTotal | Domain/IP reputation | API key (free tier: 1,000 req/day) |
| Shodan | Exposed services / banners | API key (free tier: 100 results/month) |
| AbuseIPDB | IP abuse/reputation | API key (free tier: 1,000 req/day) |
| CISA KEV | Known Exploited Vulnerabilities catalog | none, public JSON feed |
| Censys | Internet-wide host/certificate search | Personal access token (IP lookup free tier) |

All keys are entered once in the UI and stored only in the encrypted vault, never hardcoded or
logged in plaintext.

## Interactive UI features

- Live progress bar + per-thread status during a scan
- Pause / resume / cancel a running scan
- Live scrolling log console
- Findings table: filterable, searchable, sortable columns
- Side-by-side scan diff view (added vs removed, since last scan), with a JavaFX `DatePicker`
  to choose which two past scan dates to compare
- Timeline slider to scrub through scan history
- Interactive network graph (subdomain/host/port relationships)
- Charts: port distribution, severity breakdown
- Desktop notification on new critical/KEV finding
- Email notification after each completed scan
- Export findings as PDF/HTML report, with a preview before export
- Drag-and-drop target queue for scanning multiple domains

## Collaboration / workflow features

- Annotations/notes on individual findings (e.g. "false positive", "already patched")
- Custom tags on findings
- Scheduled/cron-like recurring scans, configurable from the UI

## Visual theme — animated dark "terminal" style

All screens share one consistent look and one CSS + animation utility library — do not
hand-roll styling per screen.

**Color tokens**
```
--bg-base:        #0d0f0d
--bg-panel:       #111511
--bg-panel-alt:   #0a0d0a
--border:         #1f2b1f
--border-strong:  #2a5a35
--accent:         #3ddc5f   (primary green)
--accent-hover:   #4de870
--danger-bg:      #1a0e0e   --danger-border: #3a1414   --danger-text: #e8726e
--warning-bg:     #1a1608   --warning-border: #3a2f0e  --warning-text: #d4a53d
--success-bg:     #0e1a10   --success-border: #14361c
--text-primary:   #c9d6c9
--text-secondary: #8fa88f
--text-muted:     #5a6b5a
```
- Monospace font throughout (e.g. Consolas / Courier New)
- Sentence case, no title case
- Badges for priority: critical (red), medium (amber), low (green), each with a tinted
  background + matching border + darker-shade text
- Dashed border pattern for an "empty/not configured" state (e.g. an unconfigured API key row)

**Standard motion types** — implement as small reusable animation helpers (e.g. an
`AnimationUtils` class) and reuse across every screen rather than writing animation code per
screen:
- `fadeInUp` — staggered entrance for cards/fields/rows (opacity 0→1 + translateY 8px→0)
- `scaleIn` — for modals/popups
- `glowPulse` — soft recurring glow on a container, for critical/attention items (e.g. a KEV
  match card, an active scan-diff panel)
- `pulseDot` — opacity-pulsing small circle for "live"/active status indicators
- `shake` — quick horizontal shake for validation errors
- `rippleOnClick` — expanding circle from click point on primary buttons
- Click-to-expand field — a single-line input that grows into a multi-line text area on
  focus/click (used for the finding-annotation notes field)
- Respect reduced-motion: skip/soften animations if the platform signals a reduced-motion
  preference.

**Screens that must share this exact theme + animation library**
1. Login (operator ID + master password, unlocks the encrypted vault)
2. Dashboard (target input, summary metric cards, findings table with tabs for
   findings/chart/diff)
3. Key vault settings panel (list of configured/unconfigured API keys)
4. Scan diff view (added/removed since last scan)
5. Findings detail panel (opens on row click — raw data, related CVEs, annotation field)
6. Charts / severity breakdown view
7. Network graph view (subdomains/hosts/ports as nodes)
8. Report export preview (before generating PDF/HTML)

## Suggested build order (phased)

1. **Phase 1 — core scan experience**: package skeleton, `PortScanner` with Executor/Future,
   `SubdomainEnumerator` (crt.name), SQLite schema + `ScanRepository`/`FindingDao`, login +
   encrypted vault, basic dashboard (progress bar, log console, findings table) in the dark
   theme.
2. **Phase 2 — enrichment & diffing**: VirusTotal/Shodan/AbuseIPDB/Censys clients via
   `IntelSource`, CISA KEV matching + priority scoring, `ScanDiffEngine` + diff view, charts,
   network graph.
3. **Phase 3 — polish & extras**: timeline slider, notifications, PDF/HTML export,
   drag-and-drop target queue, annotations/tags, scheduled scans.

## Non-functional requirements

- Never block the JavaFX Application Thread — all network/DB/scan work runs on background
  threads (`Task`/`ExecutorService`), UI updates via `Platform.runLater()`.
- Handle thread pool shutdown gracefully on cancel/app close (`shutdown()` +
  `awaitTermination()`, fallback `shutdownNow()`).
- Validate all user input in the UI (empty fields, malformed domains) before dispatching to
  `core`.
- No plaintext API keys or credentials anywhere on disk or in logs.
- Write unit tests for `core` classes (they have no JavaFX dependency, so this should be
  straightforward) covering port scan logic, KEV matching, and diff computation.
