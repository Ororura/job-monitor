# Stage 6 — Local Web Interface validation

Verified on 2026-10-13 on macOS Apple Silicon using Java 25.0.3, Spring Boot 4.1.1,
Playwright Java 1.63.0 and locally vendored HTMX 2.0.11.

Branch: `feat/web-interface`. Base: `feat/playwright-source` at `7b378f5`, containing
Stage 5. No push or pull request was performed. The user's staged and unstaged
`LICENSE` changes were preserved and excluded from every commit.

## Pages and behavior

- `/`: real SQLite statistics, latest vacancies/runs, next enabled searches, safe failures.
  The 24-hour run counters use run start times; interrupted runs count as unsuccessful.
- `/searches`, `/searches/new`, `/searches/{id}/edit`: forms preserve expressions,
  service validation, source/interval selection, enabling/disabling, confirmed deletion,
  asynchronous manual acceptance/conflicts, pending-state refresh every eight seconds.
- `/vacancies`: server pagination, status/source/search filters, first-discovery sorting,
  decimal salary formatting, safe external links, inline review-status updates.
- `/collections`, `/collections/{id}`: status/search filtering, pagination, counters,
  duration, sanitized diagnostics, eight-second polling only for visible active runs.
- `/settings`: read-only scheduler/queue/Playwright/SQLite information.

All pages share a responsive layout with system/light/dark themes. Tables scroll
inside keyboard-focusable containers on narrow screens. Form errors are associated
with inputs; idle loading text is hidden from the accessibility tree. MVC controllers
call existing services directly and remain separate from REST controllers.

## Validation results

Final command (successful):

```sh
./gradlew spotlessApply spotlessCheck check build browserTest --offline
```

| Check | Result |
| --- | --- |
| Spotless | PASS |
| `check` including tests, PMD and SpotBugs for production and tests | PASS |
| `build` including executable Boot jar | PASS |
| `browserTest` including existing browser-source tests | PASS |
| Ordinary tests | 254 passed, 0 failed, 0 skipped |
| Chromium tests | 28 passed, 0 failed, 0 skipped |
| Total | 282 passed: 250 existing + 32 new |

The new coverage consists of 3 formatting/form unit tests, 22 HTTP MVC integration
cases against isolated SQLite, and 7 Chromium cases. Tests cover empty/data dashboards,
form validation/CRUD/toggling, async acceptance and duplicate conflict, vacancy/status
filters and pagination, run history/details/polling, escaping, CSRF, REST compatibility,
JavaScript-free forms, layout/themes and persistence after restart. Existing tests were
not modified or disabled. No `Thread.sleep` is used in the new tests.

Security review checked all MVC mutation routes for POST plus session tokens, parameter
binding for SQL, Thymeleaf escaping for user content, safe vacancy link attributes,
fixed-category diagnostics, cookie-only session tracking and unchanged loopback binding.
No Spring Security dependency, external CDN, new migration, or global-settings store
was introduced. Source adapters, collection/scheduler rules, service methods and REST
controllers retain their existing behavior.

## User workflow and visual verification

The executable jar was also launched on `http://127.0.0.1:8080` with a separate
SQLite directory under `/private/tmp`, with automatic and Playwright collection off.
The workflow was performed through the browser UI:

1. Created `Java Junior` with `(Java OR "Java-разработчик") AND (Junior OR стажёр)` and FAKE.
2. Queued a manual run; two unique vacancies appeared from three fetched records.
3. Changed the Java vacancy to SAVED through the table.
4. Repeated the run: fetched 3, created 0, updated 2; no duplicate rows or status reset.
5. Inspected two SUCCESS runs in history.
6. Stopped/restarted the executable jar against the same SQLite: the query, two
   vacancies, SAVED status and two runs survived. Dashboard showed 2 total, 2 new,
   0 enabled queries and 2 successful / 0 unsuccessful runs.

The Chromium suite separately executes the complete restart scenario. It captures
empty and populated pages at 1440 and 390 px in light/dark themes, checks document
width, and blocks external requests. Screenshots were visually inspected; the mobile
table overflow and stretched status badges found during QA were fixed.
Generated screenshots: `build/reports/web-visual/`. Manual post-restart proof:
`build/reports/web-visual/manual-dashboard.jpg`. Test reports:
`build/reports/tests/test/` and `build/reports/tests/browserTest/`.
The manual verification server was stopped after testing; start normal usage with
`./gradlew bootRun` and the desired persistent data directory.

## Limits

- Fedora was not tested; checks above describe macOS Apple Silicon only.
- Global settings remain read-only and require a restart after external configuration changes.
- FAKE is deterministic test data. PLAYWRIGHT requires its existing opt-in permitted-endpoint
  configuration. No HH.ru integration or real external vacancy collection was performed.
- Wide vacancy/history tables use horizontal scrolling on mobile.
- Cookies are required for web mutation forms; basic GET pages and POST forms work without JS.

## Changed files

- `README.md`
- `build.gradle.kts`
- `docs/web-interface-validation.md`
- `src/main/java/com/ororura/jobmonitor/collection/CollectionDispatcher.java`
- `src/main/java/com/ororura/jobmonitor/collection/CollectionRunRepository.java`
- `src/main/java/com/ororura/jobmonitor/search/SearchRepository.java`
- `src/main/java/com/ororura/jobmonitor/vacancy/VacancyRepository.java`
- `src/main/java/com/ororura/jobmonitor/web/CollectionPageController.java`
- `src/main/java/com/ororura/jobmonitor/web/DashboardController.java`
- `src/main/java/com/ororura/jobmonitor/web/SearchForm.java`
- `src/main/java/com/ororura/jobmonitor/web/SearchPageController.java`
- `src/main/java/com/ororura/jobmonitor/web/SettingsPageController.java`
- `src/main/java/com/ororura/jobmonitor/web/VacancyPageController.java`
- `src/main/java/com/ororura/jobmonitor/web/WebAdvice.java`
- `src/main/java/com/ororura/jobmonitor/web/WebConfiguration.java`
- `src/main/java/com/ororura/jobmonitor/web/WebForbiddenException.java`
- `src/main/java/com/ororura/jobmonitor/web/WebFormatting.java`
- `src/main/resources/application.yml`
- `src/main/resources/static/css/app.css`
- `src/main/resources/static/js/app.js`
- `src/main/resources/static/js/htmx-LICENSE.txt`
- `src/main/resources/static/js/htmx.min.js`
- `src/main/resources/templates/collections/detail.html`
- `src/main/resources/templates/collections/list.html`
- `src/main/resources/templates/dashboard.html`
- `src/main/resources/templates/error.html`
- `src/main/resources/templates/fragments/notice.html`
- `src/main/resources/templates/layout.html`
- `src/main/resources/templates/searches/delete.html`
- `src/main/resources/templates/searches/form.html`
- `src/main/resources/templates/searches/list.html`
- `src/main/resources/templates/settings.html`
- `src/main/resources/templates/vacancies/list.html`
- `src/test/java/com/ororura/jobmonitor/web/WebBrowserTest.java`
- `src/test/java/com/ororura/jobmonitor/web/WebFormattingTest.java`
- `src/test/java/com/ororura/jobmonitor/web/WebMvcIntegrationTest.java`
