# AGENTS.md — Job Monitor

## Purpose

Job Monitor is a small, single-user, local application that collects job vacancies from supported sources, saves them to SQLite, and lets the user manage searches and review results.

The immediate goal is a reliable MVP, not a distributed system. Implement only the feature explicitly requested in the current task; do not build the whole roadmap at once.

## Technology and constraints

- Language: **Java 25 LTS** (Eclipse Temurin preferred). Avoid preview features.
- Build: **Gradle Kotlin DSL**, using the checked-in wrapper (`./gradlew`).
- Framework: **Spring Boot**. Choose dependencies compatible with Java 25 and the existing build.
- Persistence: **SQLite + Spring JDBC + Flyway**. Do not introduce JPA/Hibernate unless requested.
- Browser integration: **Playwright for Java** where automation is authorized.
- Web UI (when requested): **Thymeleaf + HTMX**, served by Spring Boot. No separate frontend application for the MVP.
- Tests: **JUnit 5 + Mockito**; add focused integration tests when appropriate.
- Quality tools (when configured): **Spotless, PMD, SpotBugs**.
- Supported OS: **macOS (Apple Silicon)** and **Fedora Linux**.
- No Redis, Kafka, microservices, Kubernetes, external database server, or required Docker setup for the MVP.

Never invent dependency versions. Follow versions already pinned in the project and verify compatibility before introducing or upgrading a dependency.

## Repository layout

Main package: `com.ororura.jobmonitor`.

```text
src/main/java/com/ororura/jobmonitor/
  search/       Search definitions, validation, CRUD, and scheduling settings
  collection/   Scheduler, collection orchestration, run history
  source/       VacancySource contract and source-specific adapters
  vacancy/      Vacancy model, persistence, deduplication, review APIs
  config/       Spring/application configuration
src/main/resources/
  application.yml
  db/migration/  Flyway migrations
src/test/java/com/ororura/jobmonitor/
```

Keep classes inside feature packages. Do not create generic `util`, `common`, `impl`, `interfaces`, `manager`, or `helper` packages without a demonstrated need.

The starter repository may contain `com.ororura.Main`. When implementing the Spring Boot entry point, move/replace it with `com.ororura.jobmonitor.JobMonitorApplication` and avoid leaving two competing application entry points.

Some Java files and directories were intentionally pre-created as empty placeholders. Treat them as a suggested structure, not as an obligation to implement every class in one task. Remove or rename placeholders when they no longer fit.

## Domain and data rules

- A **search query** contains at minimum a name, raw query text, enabled flag, configurable interval in minutes, and scheduling timestamps.
- Preserve the user's search expression as written, including parentheses, `AND`, `OR`, `NOT`, and quoted phrases. Do not invent a custom HH.ru query parser or silently rewrite the expression.
- A **vacancy** belongs to a source and has an external ID or another stable, documented source-specific identity. Normalize source data before persistence.
- Deduplicate globally by `(source, external_id)` where an external ID is available, not by title or URL alone.
- A vacancy may match multiple searches. Model this with a separate search-to-vacancy association; do not insert duplicate vacancy rows.
- Track `first_seen_at` and `last_seen_at` separately. A vacancy absent from one collection run is **not** automatically closed or deleted.
- Store the canonical vacancy URL, title, employer, location, salary/currency when provided, publication time when provided, and review status.
- Suggested review statuses: `NEW`, `SAVED`, `APPLIED`, `IGNORED`. Do not conflate review status with source availability.
- Record each collection run's start/end time, status, found/created/updated counts, and sanitized error message.
- Database schema changes go through Flyway migrations. Never edit an already-applied migration; create a new one.
- Use bound SQL parameters, database uniqueness constraints, and transactions where needed. Make repeated ingestion idempotent.
- Store timestamps consistently (UTC internally); convert to local time only for presentation.

## Collection and scheduling

- Support multiple independently enabled/disabled searches, each with a user-configured polling interval.
- Use one small scheduler to find due searches and dispatch collection jobs. Do not create an unbounded scheduled thread or browser instance per search.
- For the MVP, limit execution to **one active collection at a time**. Never run overlapping executions of the same search.
- Compute `next_run_at` from the completion time (fixed delay), unless a later task explicitly changes the semantics.
- A failed run must be recorded without preventing future runs. Use bounded timeouts, sensible backoff, and clear failure reporting.
- Allow manual execution without corrupting the regular schedule or creating concurrent duplicates.
- Do not treat "recursive collection" as unbounded crawling: source adapters must have explicit pagination and result limits.
- Make time-dependent code testable (prefer injected `Clock` over scattered `Instant.now()`).

## Source adapters and responsible access

- Keep all source-specific fetching logic behind `VacancySource`; orchestration and persistence must not depend on Playwright or any particular website.
- Start development and automated tests with a deterministic `FakeVacancySource` and local HTML fixtures.
- Add live integrations only when the source permits the intended automated access, and respect applicable terms and access restrictions.
- For HH.ru, do **not** assume browser automation is allowed simply because the pages are public. Prefer an officially permitted API, authorized integration, or user-configured vacancy email alerts. Keep any HH.ru Playwright integration disabled until its permitted use is established.
- Never implement CAPTCHA bypass, anti-bot evasion, fingerprint spoofing, proxy rotation to evade limits, credential theft, or access-control bypass.
- On access denial, CAPTCHA, or rate limiting (e.g. HTTP 403/429), stop the affected run, log a sanitized reason, and apply backoff instead of attempting to circumvent the restriction.
- When Playwright is used, confine browser objects to their owning thread; Playwright Java objects are not generally thread-safe. Always close pages/contexts/browsers reliably.
- Do not run real external scraping as part of unit tests or CI.

## Local application behavior

- Bind the web server to `127.0.0.1` by default. Do not expose unauthenticated administration to the network.
- Keep database files and browser profiles **outside the Git working tree**.
- Prefer OS-appropriate user data directories: `~/Library/Application Support/job-monitor/` on macOS and `${XDG_DATA_HOME:-~/.local/share}/job-monitor/` on Linux. Allow an explicit data-directory override.
- Do not commit secrets, cookies, authenticated browser profiles, email credentials, or generated database files.
- Do not log credentials or full email contents. Keep user-facing and log error messages useful but sanitized.
- Prefer a simple, accessible UI and predictable server-rendered behavior; avoid unnecessary JS frameworks.

## Coding conventions

- Write readable, idiomatic Java. Prefer constructor injection, small focused classes, and clear names.
- Distinguish source DTOs, domain models, persistence records, and HTTP request/response types only where useful; avoid needless mappings and layers.
- Prefer Java records for immutable data carriers and interfaces at actual external boundaries.
- Validate inputs at API/service boundaries. Keep business rules in services, not controllers.
- Handle expected failures explicitly; do not swallow exceptions or use `catch (Exception)` as normal control flow.
- Do not add Lombok unless expressly requested.
- Avoid premature abstraction, speculative configuration, and unrelated refactoring.
- Comments should explain *why*, not restate *what*. Use English consistently for identifiers and code comments.

## Testing and quality gates

For each changed behavior, add or update focused tests. Especially cover:

- SQL uniqueness and idempotent vacancy upserts;
- one vacancy matching several saved searches;
- scheduler due/not-due checks, fixed-delay semantics, and non-overlap;
- collection failures, retries/backoff, and run history;
- search creation, update, validation, enabling and disabling;
- source adapter parsing using fixtures (without contacting real websites).

Before marking a task complete, run the checks that exist in this repository:

```bash
./gradlew spotlessApply    # Only after Spotless is configured
./gradlew check            # Tests and configured verification tasks
./gradlew build
```

Do not claim a check passed if it was not run. If a plugin or task is not yet configured, report that fact instead of silently skipping its setup or claiming success. Do not disable checks, suppress warnings, or weaken tests just to obtain a green build.

## Codex workflow

1. Read `AGENTS.md`, `build.gradle.kts`, `settings.gradle.kts`, relevant source files, and existing tests before editing.
2. Briefly identify the files to change and the smallest implementation that satisfies the request.
3. Implement only the requested scope; keep the application runnable after each task.
4. Add meaningful tests and update migrations/configuration/documentation as needed.
5. Run relevant formatting, tests, and build checks. Fix failures introduced by your changes.
6. Summarize the changes, tests actually run, and any limitations or follow-ups.
7. Make Git commits only when explicitly requested. Never push, open a PR, or alter unrelated files without authorization.

If an important design decision is unspecified, choose the simplest option consistent with this document and clearly mention the assumption. Do not add features to "help" beyond the current request.

## MVP implementation order

1. Spring Boot entry point, configuration, SQLite connection, Flyway migrations.
2. Search CRUD, vacancy persistence, associations, deduplication.
3. Deterministic fake source, collection orchestration, scheduling, run history.
4. Local UI for searches, runs, and vacancies.
5. Authorized external source integrations and OS-level startup configuration.

This sequence is a roadmap, not permission to implement every stage in one task.

## Documentation

- Consult Context7 when working with library APIs,
  dependency configuration, or version-specific behavior.
- Prefer official documentation and compatible stable versions.
- Do not introduce deprecated APIs.
- Do not use Context7 for ordinary refactoring
  or business-logic changes.
