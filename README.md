# Argus

A JavaFX desktop **Attack Surface Management** tool for the reconnaissance phase of an
**authorized** security assessment. Discovers subdomains, live hosts, and open ports;
enriches with threat-intel APIs; matches CISA KEV; scores attack priority; persists to
SQLite with scan diffing; animated dark terminal-themed GUI.

**Discovery-only.** No exploitation or attack capability. Authorized use only — your own
infrastructure or with explicit written permission.

## Modules

```
com.argus.ui  ──►  com.argus.core  ──►  com.argus.db
```

- `com.argus.core` — business logic (port scanning, subdomain enumeration, threat-intel
  enrichment, KEV scoring, scan diffing). No JavaFX; independently unit-testable.
- `com.argus.db` — SQLite persistence via `sqlite-jdbc`.
- `com.argus.ui` — JavaFX views/controllers, shared dark theme, animation helpers.

See [docs/architecture.md](docs/architecture.md) for the invariants and class breakdown,
and [docs/spec.md](docs/spec.md) for the full functional spec.

## Build & test

```bash
mvn -q compile
mvn -q test
mvn -q compile javafx:run
```

Requires JDK 21. CI runs `mvn -B verify` on Temurin 21 for every push and pull request.
