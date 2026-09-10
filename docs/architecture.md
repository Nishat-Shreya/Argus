# Argus architecture

Argus is a JavaFX desktop **Attack Surface Management** tool. Given an authorized target
domain it discovers subdomains, live hosts, and open ports; enriches findings with
threat-intel APIs; matches CISA KEV; scores attack priority; persists to SQLite with
diffing across scans; and presents everything through an animated dark "terminal" GUI.

**Discovery-only.** No exploitation, brute-forcing, or attack capability. Authorized use
only — your own infrastructure or with explicit written permission.

Full functional spec: [spec.md](spec.md).

## Tech stack

- Java 21, Maven
- JavaFX (FXML + CSS views, Scene Builder-compatible)
- SQLite via `sqlite-jdbc`
- Jackson for JSON
- `java.net.http.HttpClient` for API calls
- JUnit 5 for tests
- GitHub Actions for CI

## Build & test

```bash
mvn -q compile               # compile
mvn -q test                  # run all tests
mvn -q test -Dtest=KevScorerTest   # single test class
mvn -q compile javafx:run    # launch the app (needs classes + resources in target/)
```

## Architecture invariants

1. **One-directional dependency: `ui → core → db`.** `core` must not import `ui`;
   `db` must not import `core` or `ui`.
2. **`com.argus.core` has zero JavaFX imports.** It must stay independently unit-testable
   and CLI-reusable — no `javafx.*`, no `Platform`, no `Task` inside `core`.
3. **Never block the JavaFX Application Thread.** All network / DB / scan work runs on
   background threads (`Task` / `ExecutorService`); UI updates go through
   `Platform.runLater()`.
4. **The producer–consumer pipeline uses explicit `wait()` / `notify()` / `notifyAll()`**
   on a `synchronized` shared queue, with a guarded `while` loop for spurious wakeups — not
   only `BlockingQueue`.
5. **Shared mutable state written by multiple scan threads** is protected by `synchronized`
   (one consistent lock object for all accessors) or a concurrent collection. No
   `volatile`-only compound operations (check-then-act, counter increment).
6. **Graceful thread-pool shutdown** on cancel / app close: `shutdown()` +
   `awaitTermination()`, fallback `shutdownNow()`.
7. **No plaintext API keys or credentials** anywhere on disk or in logs. Keys live only in
   the AES-encrypted local vault, unlocked by the operator master password.
8. **Validate all user input in the UI** (empty fields, malformed domains) before
   dispatching to `core`.

## Dependency rule

```
com.argus.ui  ──►  com.argus.core  ──►  com.argus.db
```

One direction only. `core` has **no JavaFX**. `db` depends on nothing above it. A future
CLI could sit on top of `core` + `db` with no `ui`.

Invariants 1 and 2 are enforced automatically by `PackageBoundaryTest` in `com.argus.core`,
which scans sources for forbidden imports (including static imports).

## com.argus.core — business logic, no JavaFX

| Class | Responsibility | Key mechanics |
|---|---|---|
| `PortScanner` | Multithreaded TCP port scan of a host | `ExecutorService` via `newFixedThreadPool()`; each port is a `Callable<PortResult>`; results gathered via `Future`; graceful `shutdown()` / `awaitTermination()` / `shutdownNow()` |
| `SubdomainEnumerator` | Discover subdomains for a domain | Queries crt.sh (Certificate Transparency), no API key; parses JSON with Jackson |
| `IntelSource` (interface) | Strategy for one threat-intel provider | `IntelResult query(target)` — normalized output |
| `VirusTotalSource`, `ShodanSource`, `AbuseIpdbSource`, `CensysSource` | `IntelSource` impls | `java.net.http.HttpClient`; API key pulled from the encrypted vault, never logged |
| `ThreatIntelClient` | Fans a target out across configured `IntelSource`s | Aggregates `IntelResult`s |
| `KevScorer` | Match findings against the CISA KEV catalog; compute attack-priority score | Public KEV JSON feed, no key |
| `ScanDiffEngine` | Compare two scans → added / removed / changed sets | Pure function over two result sets |
| `ScanPipeline` (producer–consumer) | Stream live results from scan workers to the UI | Scan threads (producers) push onto a **shared `synchronized` queue**; consumer drains via a guarded `while` loop using `wait()` / `notify()` / `notifyAll()`. UI adapter calls `Platform.runLater()` — but that call lives in `ui`, not here. |
| `AiInsightService` (stretch) | LLM wrapper: summary, risk explanation, NL→SQL | Optional; behind an interface |

### Concurrency contract

- Shared mutable state touched by multiple scan threads (in-memory findings list, counters,
  the pipeline queue) is guarded by `synchronized` on **one consistent lock object** per
  structure, or uses a concurrent collection.
- No `volatile`-only compound operations (no check-then-act, no `count++` on a bare
  `volatile`).
- The producer–consumer queue is implemented explicitly with `wait()`/`notify()`/
  `notifyAll()` and a `while` guard for spurious wakeups — not to be replaced wholesale by
  `BlockingQueue`.
- Tests must not assert on `availableProcessors()` or wall-clock timing; the pipeline
  contract is *no lost and no duplicated items*, which is order-independent.

## com.argus.db — SQLite persistence

`sqlite-jdbc`. Close every `Connection` / `Statement` / `ResultSet`.

| DAO / repo | Table(s) |
|---|---|
| `ScanRepository` | `scans` — save/load scan sessions |
| `FindingDao` | `findings` — port / subdomain / vulnerability findings |
| `AnnotationDao` | `annotations` — notes on findings |
| `TagDao` | `tags` — custom tags on findings |
| `ScheduledScanDao` | `scheduled_scans` — cron-like schedules |

## com.argus.ui — JavaFX only

FXML + controllers, no business logic. One shared CSS theme (`theme.css`, 18 `-argus-*`
looked-up colour tokens on `.root` — JavaFX CSS has no `var()`) applied through
`Theme.applyTo(scene)`, plus one `AnimationUtils` helper (motion types: `fadeInUp`,
`scaleIn`, `glowPulse`, `pulseDot`, `shake`, `rippleOnClick`, click-to-expand field;
respect reduced-motion via `AnimationUtils.isReducedMotion()`).

All network / DB / scan work runs on background `Task` / `ExecutorService`; UI updates via
`Platform.runLater()`. Validate input (empty fields, malformed domains) before calling
`core`. `App.stop()` is the shutdown hook for the scan `ExecutorService`.

Screens sharing the theme: Login, Dashboard, Key vault settings, Scan diff, Findings
detail, Charts, Network graph, Report export preview.

## Testing

- **TDD for `core`** — write the failing test first. Priority coverage: port-scan logic,
  KEV matching, diff computation.
- `core` tests must not require JavaFX on the classpath.

## Security

- Operator login (operator ID + master password) unlocks an **AES-encrypted local vault**.
- API keys entered once in the UI, stored only in the vault. Never hardcoded, never on disk
  in plaintext, never logged. This extends to CI — no secrets in the workflow; threat-intel
  clients are tested with recorded JSON fixtures and a stubbed HTTP layer.
