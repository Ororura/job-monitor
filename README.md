
# Job Monitor

A single-user local application for collecting and reviewing job vacancies.
The application starts Spring Boot, creates a SQLite database, applies Flyway
migrations, and provides REST APIs for managing search queries and reviewing
vacancies. An offline fake source, an opt-in Playwright source, a bounded
sequential collection worker, search scheduling, and persistent run history are
implemented. A local Thymeleaf + HTMX web interface manages searches, vacancies,
collection history, and read-only configuration. Browser collection supports local fixtures and an opt-in HH.ru search profile
for the user-confirmed permitted access. Automatic collection
and browser collection are independently disabled by default.

## Requirements

- Java 25 LTS JDK, without preview features; Eclipse Temurin is preferred.
- macOS on Apple Silicon or Fedora Linux (x86_64 / ARM64).
- Use the checked-in Gradle 9.6.0 wrapper; no system Gradle is required.
- The first build needs network access to download dependencies.

Install a JDK 25 matching your OS and architecture using the
[Temurin installation instructions](https://adoptium.net/installation).
On macOS, select the installed JDK with `export JAVA_HOME=$(/usr/libexec/java_home -v 25)`.
On Fedora, set `JAVA_HOME` to the installed JDK directory. In either case, set
`export PATH="$JAVA_HOME/bin:$PATH"` and verify `java -version` and `./gradlew --version`.
SQLite's native libraries for macOS ARM64 and Linux are bundled in the JDBC jar;
a separate SQLite installation is optional.

## Run

```sh
./gradlew bootRun
```

Open [Job Monitor](http://127.0.0.1:8080) in your browser. The server listens on
`127.0.0.1:8080`; the REST API remains available under `/api/v1/`.
Stop the application with Ctrl+C.

### Docker and automatic delivery to GHCR

The `CI/CD` workflow runs on pushes, pull requests and manual dispatch. Formatting,
tests, PMD, SpotBugs and the executable build run on Ubuntu and macOS with Java 25.
A separate Linux job runs all browser tests against local fixtures. Native amd64
and arm64 runners build and smoke-test the runtime image: non-root execution,
offline Chromium launch, FAKE collection, SQLite persistence after restart and the
container health check. Reports are kept as workflow artifacts for seven days.

Only after all checks pass, pushes and manual runs publish a multi-platform image
to `ghcr.io/ororura/job-monitor` using the workflow's `GITHUB_TOKEN`. No registry
password or personal token needs to be added to repository secrets. Pull requests,
including forks, build and test without registry login or publication permissions.

| Trigger | Published tags |
| --- | --- |
| Push/manual run on `main` | `latest`, `main`, `sha-<full commit SHA>` |
| Push/manual run on another branch | Sanitized branch name, `sha-<full commit SHA>`; no `latest` |
| Version tag, e.g. `v1.2.3` | `v1.2.3`, `1.2.3`, `1.2`, `sha-<full commit SHA>`; no `latest` |
| Pull request | Build and tests only |

Publication includes OCI source/revision labels, a software bill of materials
(SBOM) and build provenance. The workflow summary records the image digest and
tags. For a reproducible deployment, use the recorded `@sha256:...` reference.
Docker Actions and Java 25 base images are pinned to verified commit/digest
references; update these pins deliberately when updating the toolchain.

GitHub creates new container packages as private by default, even for a public
repository. For anonymous pulls, set the package visibility to public in the
GitHub package settings. Otherwise authenticate with a token having `read:packages`
using `docker login ghcr.io`; do not commit that token. Repository Actions must
remain enabled and allowed to write packages. Package access is linked to this
repository through the image's source label and workflow token.

After the first successful publication from `main`, start the app locally:

```sh
docker compose up -d
docker compose logs -f job-monitor
```

Open `http://127.0.0.1:8080`. The provided `compose.yml` publishes the port only on
host loopback and persists `/data/job-monitor.db` in the `job-monitor-data` named
volume. The image listens on all interfaces **inside the container** so Docker
port forwarding works; the direct Java application's loopback default is unchanged.
Chromium and its OS dependencies are included using the Playwright version pinned
in Gradle. Collection remains disabled by default; enable the `hh` profile explicitly
with `SPRING_PROFILES_ACTIVE=hh` in Compose configuration when permitted access is
confirmed. The runtime runs as UID/GID `10001`; bind-mounted data directories must
be writable by that identity. `TZ` can configure presentation time zones.

To update or stop without deleting data:

```sh
docker compose pull
docker compose up -d
docker compose down
```

Keep the data volume when recreating containers. Back up SQLite before updates;
schema migrations run automatically on startup, so older application images are
not guaranteed to work against a migrated database. Deleting the volume deletes
the database.

Build and validate the image locally without publishing:

```sh
docker build -t job-monitor:local .
python3 scripts/docker-smoke.py job-monitor:local
```

Docker is optional for running and testing the Java application. The image uses a
multi-stage Gradle build and a JRE runtime; its build context excludes Git metadata,
local credentials, database files and generated output. Smoke tests create and
remove their own temporary containers and volume and never contact external sources.

The data directory is created automatically before opening `job-monitor.db`:

| OS | Default directory |
| --- | --- |
| macOS | `~/Library/Application Support/job-monitor/` |
| Fedora / Linux | `$XDG_DATA_HOME/job-monitor/` when XDG_DATA_HOME is an absolute path; otherwise `~/.local/share/job-monitor/` |

To override it, use an absolute directory outside the Git working tree:

```sh
JOB_MONITOR_DATA_DIR="/absolute/path/to/job-monitor" ./gradlew bootRun
```

`job-monitor.data-directory` can also be set through Spring configuration, e.g.
`./gradlew bootRun --args='--job-monitor.data-directory=/absolute/path/to/job-monitor'`.
Spring Boot does not automatically load `.env` files; export environment variables
in the shell or configure them in the IDE.

Application settings are in `src/main/resources/application.yml`. The SQLite URL
is derived from the data directory by `DataSourceConfiguration`; the Hikari pool
uses one connection, enables foreign keys, and sets a five-second SQLite lock
timeout. `SERVER_PORT` overrides the default port. Run only one application
instance against a database file.

Flyway validates and applies `src/main/resources/db/migration/V*__*.sql` on startup.
`V1__initialize_database.sql` creates a small infrastructure metadata table, with
no vacancy domain schema. Add a new migration for each schema change; never edit
an applied migration. `V2__create_search_queries.sql` adds search queries without
changing V1. `V3__create_vacancies.sql` adds vacancies and their search matches.
`V4__create_collection_runs.sql` adds collection history with a nullable search
reference and `ON DELETE SET NULL`. `V5__add_search_query_source.sql` defaults
existing searches to `FAKE`. `V6__allow_unknown_vacancy_fields.sql` allows null
employer/location and preserves vacancy rows, review statuses, associations,
indexes, and foreign keys. Repeated startup does not reapply migrations.

## Local web interface

| Page | Purpose |
| --- | --- |
| `/` | SQLite-backed counts, recent vacancies/runs, upcoming checks and safe diagnostics |
| `/searches` | Create/edit queries, choose FAKE/PLAYWRIGHT, toggle scheduling, confirm deletion and queue manual runs |
| `/vacancies` | Filter by status/source/search, sort by discovery time, paginate and change review status |
| `/collections` | Filter/paginate run history; `/collections/{id}` shows run details |
| `/settings` | Read-only collection/browser configuration and local database location |

The UI calls existing services directly. Thymeleaf escapes user content; mutations
use POST forms with session CSRF tokens. REST endpoints and their request/response
contracts are unchanged. Session IDs use cookies only; cookies must be enabled for
web forms. Basic pages and form submissions also work without JavaScript.
[HTMX](https://htmx.org/docs/) 2.0.11 and its license are vendored in
`src/main/resources/static/js`; CSS, scripts, and fonts require no CDN or frontend
build. System light/dark mode is the default; the theme selector saves a local
browser preference. Times display in the application's OS timezone, identified
in the footer. SQLite timestamps remain UTC.

Start with an offline demonstration:

1. Open `/searches/new`, enter a name and an expression such as
   `(Java OR "Java-разработчик") AND (Junior OR стажёр)`.
2. Select **FAKE** and save. The expression, including surrounding whitespace,
   is preserved; FAKE intentionally ignores it and returns deterministic test data.
3. Click **Запустить сбор**. Acceptance means queued, not completed. A duplicate
   queued/running request or full queue is reported as a conflict. Pending searches
   and visible active runs refresh every eight seconds; manual refresh is available.
4. Open `/vacancies`: the fake source creates two unique vacancies from three
   fetched records. Repeat collection: the same vacancies are updated, not duplicated.
5. Set a vacancy to SAVED, then inspect `/collections` and a run's details.
6. Restart the app with the same data directory: searches, vacancies, statuses,
   associations, and run history persist.

Automatic collection stays disabled unless explicitly enabled in configuration.
Checking a query's enabled flag alone does not enable the global scheduler.
PLAYWRIGHT searches can be saved while the source is disabled; the UI explains
that state and a manual run records the existing sanitized source failure.
The optional `hh` profile supports HH.ru searches under confirmed permitted access. Settings cannot write configuration; global changes
require a restart. The database path is visible only as local settings information;
source URLs and raw diagnostic messages are not displayed.

### Web tests and visual verification

```sh
./gradlew test --tests '*WebMvcIntegrationTest' --tests '*WebFormattingTest'
./gradlew playwrightInstall   # one-time Chromium installation; requires network
./gradlew playwrightInstall -PwithBrowserDeps  # also install Linux browser OS dependencies
./gradlew browserTest --tests '*WebBrowserTest'
./gradlew spotlessCheck check build browserTest
```

Web integration tests use real HTTP/HTML rendering and temporary SQLite. Browser
tests run a local Spring Boot application with automatic/browser collection disabled,
use FAKE, block external requests, and skip automatic browser downloads. Once Gradle
dependencies and Chromium are cached, run with `--offline` for network-free tests.
Synchronisation uses Locator assertions and bounded Awaitility, without `Thread.sleep`.
The complete browser workflow closes/restarts Spring Boot against the same test DB
and verifies deduplication and persisted statuses. Additional tests cover forms,
filtering/pagination, eight-second polling, JavaScript-free forms, and desktop/mobile
layout at 1440/390 px in light/dark themes. Screenshots are generated under
`build/reports/web-visual/`; HTML test reports are under `build/reports/tests/`.
These browser checks were performed on macOS Apple Silicon; Fedora was not tested.

## Search query API

| Method | Path | Success |
| --- | --- | --- |
| GET | `/api/v1/search-queries` | 200, JSON array (empty when no queries exist) |
| GET | `/api/v1/search-queries/{id}` | 200, query JSON |
| POST | `/api/v1/search-queries` | 201, query JSON and `Location` header |
| PUT | `/api/v1/search-queries/{id}` | 200, updated query JSON |
| DELETE | `/api/v1/search-queries/{id}` | 204, no body |

POST and PUT require `name`, `queryText`, `intervalMinutes`, and `enabled`.
Optional `source` accepts `FAKE` or `PLAYWRIGHT` (case-sensitive). Omission/null
defaults to `FAKE` on creation and preserves the current source on update.
Responses include `source`; older request JSON remains supported.
Name and query text must not be blank; the interval must be at least 10 minutes.
Query text is stored exactly as supplied, including whitespace, parentheses,
operators, and quotes. PUT replaces all four editable fields; it preserves the
UUID and creation time and advances `updatedAt` using the application's clock.
All timestamps are ISO-8601 UTC strings ending in `Z`, stored as TEXT in SQLite.
`nextRunAt` is server-managed. Enabled searches with a null value are due for
their first automatic run. Changing an interval adjusts an existing `nextRunAt`
from the previous completion time; changing other fields retains it. Disabled
searches are never automatically dispatched. Completing a run of a disabled
search clears `nextRunAt`.

Invalid input returns 400. A valid UUID with no matching query returns 404 for
GET, PUT, and DELETE; invalid UUID syntax returns 400. Domain errors include a
Problem Detail JSON body with a readable `detail` field.

Create a query:

```sh
curl -i http://127.0.0.1:8080/api/v1/search-queries \
  -H 'Content-Type: application/json' \
  -d '{"name":"Java backend","queryText":"(Java AND \"Spring Boot\") OR (Kotlin AND NOT senior)","intervalMinutes":30,"enabled":true}'
```

Copy the returned UUID into `SEARCH_ID` for the remaining examples:

```sh
SEARCH_ID='replace-with-returned-uuid'
curl -i http://127.0.0.1:8080/api/v1/search-queries
curl -i "http://127.0.0.1:8080/api/v1/search-queries/$SEARCH_ID"

curl -i -X PUT "http://127.0.0.1:8080/api/v1/search-queries/$SEARCH_ID" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Java backend paused","queryText":"Java AND NOT senior","intervalMinutes":60,"enabled":false}'

curl -i -X DELETE "http://127.0.0.1:8080/api/v1/search-queries/$SEARCH_ID"
# Returns 404 after deletion:
curl -i "http://127.0.0.1:8080/api/v1/search-queries/$SEARCH_ID"

# Returns 400 because the interval is below 10 minutes:
curl -i http://127.0.0.1:8080/api/v1/search-queries \
  -H 'Content-Type: application/json' \
  -d '{"name":"Invalid interval","queryText":"Java","intervalMinutes":9,"enabled":true}'
```

## Vacancy persistence and API

`VacancyService.save(VacancyData)` and `saveAll(List<VacancyData>)` ingest source
data without HTTP or JDBC types. Both methods also accept a search UUID as a
second argument to save the vacancy and its match in one transaction.
`linkToSearch(vacancyId, searchQueryId)` associates an existing vacancy with
another search. Batch ingestion is atomic: a failure rolls back the whole batch.
An empty batch returns an empty list. Missing search UUIDs are rejected before
ingestion. Batch results follow input order; repeated identities return the same
vacancy UUID.

Vacancies are unique by `(source, external_id)`, independently of title and URL.
Upsert refreshes source fields and `lastSeenAt`, preserving the internal UUID,
`firstSeenAt`, and user status. New vacancies have status `NEW`. Each vacancy can
match multiple searches; duplicate matches are ignored. Deleting a search
cascades its matches while preserving all vacancies and other matches.

Required source fields are `source`, `externalId`, `title`, and an absolute
HTTP/HTTPS `url` without embedded credentials. Unknown `employerName` and `area`
may be null; supplied values must not be blank. Surrounding
whitespace is stripped; source identifiers remain case-sensitive. Salary bounds,
currency, and publication time can be null. Non-null salaries must be non-negative,
and the lower bound must not exceed the upper bound. Salaries use `BigDecimal`
in Java and decimal TEXT in SQLite to preserve precision without conversion to
floating point. Vacancy timestamps use `Instant` and UTC TEXT with nine fractional
digits, so sorting by `firstSeenAt` also works within the same second.

| Method | Path | Success |
| --- | --- | --- |
| GET | `/api/v1/vacancies` | 200, page JSON |
| GET | `/api/v1/vacancies/{id}` | 200, vacancy JSON |
| PATCH | `/api/v1/vacancies/{id}/status` | 200, updated vacancy JSON |

There is no public creation endpoint. PATCH requires `{"status":"SAVED"}`;
allowed statuses are `NEW`, `SAVED`, `APPLIED`, and `IGNORED`. Status changes do
not modify source fields or observation timestamps. Missing vacancies return 404;
invalid UUIDs, bodies, statuses, pagination, and sort values return 400.

GET accepts optional `status`, `source`, and `searchQueryId` filters, combined
with AND. An unknown source or search UUID returns an empty page. Pagination is
zero-based: `page=0` and `size=20` by default; size must be between 1 and 100.
Sort accepts `firstSeenAt,desc` (default) or `firstSeenAt,asc`; UUID is a stable
tie-breaker in the same direction. The response contains `content`, `page`,
`size`, and `totalElements` (the count after filtering, before pagination).

```sh
curl 'http://127.0.0.1:8080/api/v1/vacancies?page=0&size=20&sort=firstSeenAt,desc'
curl 'http://127.0.0.1:8080/api/v1/vacancies?status=NEW&source=FAKE'

# Use existing UUIDs from the local database/API:
SEARCH_ID='replace-with-search-uuid'
VACANCY_ID='replace-with-vacancy-uuid'
curl "http://127.0.0.1:8080/api/v1/vacancies?searchQueryId=$SEARCH_ID&sort=firstSeenAt,asc"
curl "http://127.0.0.1:8080/api/v1/vacancies/$VACANCY_ID"
curl -X PATCH "http://127.0.0.1:8080/api/v1/vacancies/$VACANCY_ID/status" \
  -H 'Content-Type: application/json' -d '{"status":"SAVED"}'
```

## Offline collection and run history

`VacancySource` fetches existing `VacancyData` records for a saved search. The default
adapter is `FakeVacancySource`: it returns three deterministic records containing
two distinct `(FAKE, external_id)` identities. It ignores search expressions and
uses example.invalid URLs; it never accesses a network or a browser. Records are
shared across searches to exercise global deduplication and multiple matches.

| Property | Default | Meaning |
| --- | --- | --- |
| `app.collection.enabled` | `false` | Enables automatic polling; manual runs remain available |
| `app.collection.poll-interval` | `30s` | Positive duration between due-search checks |
| `app.collection.queue-capacity` | `32` | Positive number of waiting jobs, plus one running job |

The `dev` profile enables automatic fake collection. Use a separate data directory:

```sh
./gradlew bootRun --args='--spring.profiles.active=dev --job-monitor.data-directory=/tmp/job-monitor-dev'
```

One worker executes all collections sequentially. A search can have only one
queued/running job, even across repeated polls and manual requests. The queue is
bounded; automatic submissions that cannot fit are considered on later polls.
Queued automatic jobs recheck the saved search before fetching, so disabling or
deleting a search prevents a queued automatic collection from starting. A run
already fetching can finish. The next run is calculated from completion plus the
currently saved interval, on both success and failure. A manual run of an enabled
search also advances its regular schedule; a disabled search stays disabled.

Fetching holds no database transaction. Ingestion, matches, success statistics,
and schedule updates commit together. Failure rolls back ingestion and records
`FAILED` with a sanitized error category in a separate transaction. A search
deleted while fetching retains its detached history and collected vacancies.
On startup, unfinished `RUNNING` records become `INTERRUPTED`; surviving enabled
searches retry after their saved interval from recovery time. Waiting jobs are
in memory and are not restored; due enabled searches are picked up by later polls.
Run one application instance per database.

| Method | Path | Success |
| --- | --- | --- |
| POST | `/api/v1/search-queries/{id}/run` | 202, accepted asynchronously with no body |
| GET | `/api/v1/collection-runs` | 200, page JSON |
| GET | `/api/v1/collection-runs/{id}` | 200, run JSON |

Manual execution is allowed even when automatic collection or the search is
disabled. A queued/running search or a full queue returns 409 with Problem Detail;
a missing search/run returns 404; malformed UUIDs, statuses, or pagination return
400. History accepts optional `searchQueryId` and `status` filters combined with
AND, plus zero-based `page=0` and `size=20` defaults (size 1–100). Unknown search
UUIDs return an empty page. Results sort by `startedAt` descending, then UUID
for stable ordering. Page JSON contains `content`, `page`, `size`, `totalElements`.

Each run includes `id`, nullable `searchQueryId`, the original `searchQueryName`,
`source`, `status` (`RUNNING`, `SUCCESS`, `FAILED`, `INTERRUPTED`), UTC `startedAt`
and nullable `finishedAt`, counts, and nullable `errorMessage`. `fetchedCount`
counts returned records including duplicate identities. `createdCount` and
`updatedCount` count distinct normalized identities, using the last occurrence
for fields; updated means an existing vacancy was refreshed, even if its source
fields did not change. Failed ingestion reports zero committed creates/updates.
A failed source fetch reports zero fetched records. Review statuses are preserved.

```sh
SEARCH_ID='replace-with-search-uuid'
curl -i -X POST "http://127.0.0.1:8080/api/v1/search-queries/$SEARCH_ID/run"
curl "http://127.0.0.1:8080/api/v1/collection-runs?searchQueryId=$SEARCH_ID&status=SUCCESS&page=0&size=20"
curl "http://127.0.0.1:8080/api/v1/vacancies?searchQueryId=$SEARCH_ID"
RUN_ID='replace-with-run-uuid'
curl "http://127.0.0.1:8080/api/v1/collection-runs/$RUN_ID"
```

Wait for the accepted run to finish in history before repeating POST. With the
fake source, the first run creates two vacancies and repeated runs refresh those
two records without new rows or resetting user statuses. Deleting the search
preserves history and its original name, setting `searchQueryId` to null.

## Verification and build

```sh
./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew check
./gradlew build
```

`check` runs tests, Spotless, PMD, and SpotBugs; failures fail the build.
Reports are written under `build/reports/`. Tests use temporary SQLite files
outside the repository and do not access the user's database or external websites.
Integration tests start a real local HTTP server and verify search CRUD, complex
expressions, input validation, and missing records. Repository tests verify
SQLite field round-trips through an independent connection, database constraints,
and a V1-to-V2 upgrade preserving existing data. Vacancy tests cover exact salary
round-trips, global deduplication, field updates, preserved timestamps/status,
multiple matches, deletion cascades, transaction rollback (including batch
failure), filters, stable pagination, HTTP errors, and a V2-to-V3 upgrade retaining
searches and metadata. Migration reruns remain a no-op.
Collection tests cover first runs, due boundaries, individual intervals, interval
changes, disabled/queued jobs, manual execution, bounded queues, duplicate
submissions, one-worker execution, distinct statistics, preserved statuses,
source failures, database rollback, detached history, and a real close/reopen
cycle against the same SQLite database. HTTP tests cover 202/400/404/409 and
filtering/pagination. Scheduling uses a managed clock without long waits or
Thread.sleep; latches and bounded completion waits coordinate worker tests.

Unit tests cover search validation and timestamp behavior with a fixed clock,
macOS/Linux default paths, XDG_DATA_HOME, and an explicit override.

Build output includes `build/libs/job-monitor-1.0-SNAPSHOT.jar`:

```sh
java --enable-native-access=ALL-UNNAMED -jar build/libs/job-monitor-1.0-SNAPSHOT.jar
```

Native access is enabled for the SQLite JDBC native library; Gradle's `bootRun`
and test tasks already set that option.

GitHub Actions runs the same three verification commands with Temurin 25 on
Ubuntu Linux and macOS ARM64. Fedora uses the same Linux JDBC natives and path
resolution; a native Fedora run is still needed to validate that specific host.

## Versions and compatibility

| Component | Version |
| --- | --- |
| Java toolchain | 25 |
| Gradle wrapper | 9.6.0 |
| Spring Boot | 4.1.1 |
| SQLite JDBC | 3.53.4.0 |
| Playwright Java | 1.63.0 |
| jsoup | 1.23.2 |
| Flyway | 12.4.0 (Spring Boot BOM) |
| JUnit Jupiter | 6.0.3 (Spring Boot BOM) |
| Spotless / google-java-format | 8.10.4 / 1.36.1 |
| PMD | 7.28.0 |
| SpotBugs plugin / engine | 6.5.13 / 4.10.5 |

JUnit 6 was chosen instead of the initial JUnit 5 requirement because
[Spring Framework 7's SpringExtension requires JUnit Jupiter 6](https://docs.spring.io/spring-framework/docs/7.0.9/javadoc-api/org/springframework/test/context/junit/jupiter/SpringExtension.html).
Spring Boot's BOM manages the Spring and testing dependencies. SQLite support is
included in this Flyway core version; no additional database module is required.

## Layout

```text
src/main/java/com/ororura/jobmonitor/
  JobMonitorApplication.java
  config/          SQLite and platform data-directory configuration
  search/          Search model, JDBC repository, service, REST DTOs and endpoints
  collection/      Bounded worker, scheduler, execution, run history and REST API
  source/          Source selection, fake adapter, Chromium lifecycle and HTML parsing
  vacancy/         Vacancy data/model, JDBC persistence, service and review REST API
src/main/resources/
  application.yml
  db/migration/
src/test/java/com/ororura/jobmonitor/
.github/workflows/ci.yml
```

Keep databases, browser profiles, cookies, and credentials outside Git. Live
source integrations are not enabled by default. Follow `AGENTS.md`
when implementing subsequent MVP features.

## Opt-in Playwright collection

`PlaywrightVacancySource` implements the same `VacancySource` contract as the fake.
A single configured, explicitly permitted endpoint supplies searches whose `source`
is `PLAYWRIGHT`. Generic fixture-format vacancies use `PLAYWRIGHT`; HH-format
vacancies use `HH` with the site's numeric ID, keeping source identity separate
from the browser transport. Collection business logic and persistence use the same
existing services. The HH profile is opt-in and requires the user's confirmed
permitted access; other sites need their own permitted parser contract. Do not switch
generic fixture providers that share the same identity namespace in one database.

### Browser installation

Java 25 must be selected as described above. Install Chromium once and repeat
installation after upgrading the pinned Playwright dependency:

```sh
# macOS ARM64 (native ARM64 JDK, not an Intel JDK under Rosetta)
./gradlew playwrightInstall
./gradlew browserTest
```

The Gradle task runs `com.microsoft.playwright.CLI install chromium` using the
project's Java toolchain. Playwright stores binaries outside Git, normally under
`~/Library/Caches/ms-playwright` on macOS and `~/.cache/ms-playwright` on Linux.
`PLAYWRIGHT_BROWSERS_PATH` can override that path; use the same value for install,
tests and application startup. Application startup and collection never download
browsers automatically. See the [official browser installation guide](https://playwright.dev/java/docs/browsers).

Fedora is outside Playwright's [official Linux support matrix](https://playwright.dev/java/docs/intro#system-requirements).
For a native Fedora installation, the distro's [Chromium package](https://packages.fedoraproject.org/pkgs/chromium/chromium/)
provides a convenient starting point for native runtime dependencies:

```sh
sudo dnf install chromium
./gradlew playwrightInstall
./gradlew browserTest
```

Job Monitor still launches the Playwright-pinned Chromium binary, not the distro
browser. Do not run Playwright's apt-based `install --with-deps` on Fedora. If a
bundled binary fails to launch, run `ldd /absolute/path/to/the/bundled/browser`
and use `dnf provides '*/missing-library.so'` to identify missing libraries,
then install the matching Fedora packages. Headed mode also needs a graphical
session. Native Fedora browser compatibility has not been tested in this change;
validate `browserTest` on the target Fedora host before enabling collection.

### Configuration and enabling a permitted endpoint

| Property | Default | Meaning |
| --- | --- | --- |
| `app.playwright.enabled` | `false` | Allows browser collection; never enables automatic polling |
| `app.playwright.headless` | `true` | Set `false` to display Chromium |
| `app.playwright.navigation-timeout` | `30s` | Positive timeout for launch/navigation/network fetching |
| `app.playwright.operation-timeout` | `10s` | Positive timeout for Locator waits and browser operations |
| `app.playwright.max-pages` | `5` | Positive maximum number of search pages per run |
| `app.playwright.max-vacancies` | `100` | Positive maximum number of distinct vacancies returned per run |
| `app.playwright.search-url` | unset | Absolute HTTP(S) search endpoint; query parameters supported for HH format, no fragment/credentials |
| `app.playwright.format` | inferred | `HH` for hh.ru hosts, otherwise `FIXTURE`; explicit HH format also supports owned offline fixtures |
| `app.playwright.access-confirmed` | `false` | Explicit confirmation that the configured source permits this access |

Set `search-url` using `JOB_MONITOR_BROWSER_SEARCH_URL` or Spring configuration.
Only enable collection after establishing permitted access to that specific
endpoint and implementing/verifying its supported HTML contract. For an owned
or otherwise authorized endpoint, start the application with:

```sh
export JOB_MONITOR_BROWSER_SEARCH_URL='http://127.0.0.1:8090/search'
./gradlew bootRun --args='--app.playwright.enabled=true --app.playwright.access-confirmed=true'
# Headed mode (requires a graphical desktop):
./gradlew bootRun --args='--app.playwright.enabled=true --app.playwright.access-confirmed=true --app.playwright.headless=false'
```

These examples assume an authorized fixture-compatible server already runs at
that address. They do not enable automatic scheduling. Create a browser search:

```sh
curl http://127.0.0.1:8080/api/v1/search-queries \
  -H 'Content-Type: application/json' \
  -d '{"name":"Authorized browser search","queryText":"(Java AND \"Spring Boot\") OR (Kotlin AND NOT senior)","intervalMinutes":30,"enabled":false,"source":"PLAYWRIGHT"}'
# Use the returned search ID for the existing manual-run endpoint:
curl -X POST "http://127.0.0.1:8080/api/v1/search-queries/$SEARCH_ID/run"
```

### HH.ru search profile

For the user's confirmed permitted HH.ru access, the `hh` profile configures the
provided Saint Petersburg search (`area=2`, `order_by=publication_time`). It enables
manual browser collection with a hard maximum of **100 pages per run** and a safety
limit of **5,000 unique vacancies**. Automatic collection remains disabled.

```sh
./gradlew bootRun --args='--spring.profiles.active=hh'
```

Open `http://127.0.0.1:8080/searches`, create or edit a query, choose **PLAYWRIGHT**,
enter the exact expression to send to HH.ru, and use **Запустить сбор**. The raw
expression replaces `text` in the configured URL on every page; location, ordering
and other search parameters are preserved. `q` is retained only for the generic
fixture format. Do not combine the `hh` and `dev` profiles for unattended collection.

The HH parser uses public `data-qa` card and next-page markers, recognizes explicit
empty results, and reads numeric salary values from HTML5 `data` elements without
floating-point conversion. Missing publication times remain unknown; salary amounts
are stored as supplied, without converting hourly/daily amounts into monthly pay.
Sponsored-card IDs are extracted from their public response links without following
advertising redirects or sending applications. Vacancy identities use `(HH, numeric
vacancy ID)` and canonical `https://hh.ru/vacancy/{id}` URLs; the UI offers an HH.ru
vacancy-source filter. Search/run source PLAYWRIGHT identifies the collection adapter.

Only search documents are fetched in HH mode. Scripts, XHR, images, advertisements
and external resources are blocked. Sequential next-page navigations have a two-second
pause; readiness uses Locator waits. The adapter stops at the next-page end, a cycle,
or either limit. It does not log in, retain a profile, visit vacancy detail pages or
circumvent access restrictions. CAPTCHA, 403/429 and redirects terminate the run with
safe diagnostics and the existing retry schedule. Start with a smaller manual run:

```sh
./gradlew bootRun --args='--spring.profiles.active=hh --app.playwright.max-pages=1 --app.playwright.max-vacancies=5'
```

The default application configuration still leaves Playwright and automatic collection
off. The HH profile is an explicit opt-in for the confirmed access; adjust it if the
source's permission or limits change. The supported production endpoints are HTTPS
`spb.hh.ru/search/vacancy` and `hh.ru/search/vacancy`. Unapproved origins, credentials,
fragments, unsupported HH endpoints, and HH limits above 100 pages are rejected.

HH unit tests and Chromium tests use synthetic local HTML and isolated local servers:

```sh
./gradlew test --tests '*Hh*Test'
./gradlew browserTest --tests '*HhBrowserIntegrationTest'
```

They do not contact HH.ru or follow the fixture's external vacancy/advertising links.
A separate authorized live smoke check on 2026-10-13 (macOS Apple Silicon) successfully
collected five vacancies for `Java` from one HH page into temporary SQLite. This
checks the real markup and ingestion path, not a complete 100-page crawl.

### Supported HTML contract

Search requests use the URL-encoded `q` parameter containing the exact saved
expression, including leading/trailing spaces, operators and quotes. A source's
own search engine interprets it; the adapter does not rewrite its logic. Every
next-page request retains the original expression and other pagination parameters.

```html
<section data-job-results data-ready="true">
  <article data-job-card data-external-id="123">
    <a data-job-title href="/vacancies/123">Java developer</a>
    <span data-job-employer>Example employer</span>
    <span data-job-area>Remote</span>
    <span data-job-salary data-salary-from="100000.01"
          data-salary-to="150000.02" data-currency="RUB"></span>
    <time datetime="2026-10-13T09:00:00Z"></time>
  </article>
</section>
<a data-job-next href="/search?page=2">Next</a>
```

Use `data-ready="true"` only after results are complete. Empty results require an
explicit `data-job-empty` element. Missing/contradictory markers or a page with
only invalid cards fail with `STRUCTURE`; individual invalid cards are skipped
and only their count is logged. `data-job-blocked`, `data-captcha` or a CAPTCHA
iframe stop the run with `BLOCKED`. No CAPTCHA or access restriction is bypassed.

A title link and a stable ID are required. Prefer `data-external-id`; otherwise
IDs are extracted from `/vacancies/{id}`. Relative links resolve against the
page URL. Canonical vacancy URLs exclude fragments and `tracking`/`utm_*` query
parameters while retaining parameters that may identify the vacancy.
Salary bounds use decimal attributes (dot decimal separator, optional grouping
spaces), parsed directly as `BigDecimal`, never floating point. Supplied currency
codes are preserved without guessing. Employer, area, salary, currency and
publication time may be absent/null. Unknown publication dates stay null.

Pagination follows `a[data-job-next]` unless `aria-disabled="true"`, stopping when
there is no next page, a URL cycle, or a configured limit. Next pages must use
the same origin **and search path**. HTTP requests outside the configured origin
are aborted; redirects, 403 and 429 stop the run. No vacancy detail pages are
visited. Pages requiring external CDN assets, redirects, login sessions, a
different pagination model or arbitrary CSS selectors need a permitted adapter
extension and dedicated tests.

Each fetch creates and closes Playwright, Chromium, an isolated BrowserContext
and Page on the collection worker thread. No cookies/profile are retained between
runs. Application shutdown signals browser jobs to stop before another page;
queued browser jobs fail with `SHUTDOWN` without opening a browser. The worker
waits for the active bounded browser operation and teardown before database
shutdown. Errors retain the existing fixed-delay retry schedule and never
persist raw browser error messages, URLs, HTML, cookies or credentials.

### Offline tests and diagnostics

Parser and mock-browser tests require no browser binaries or internet:

```sh
./gradlew test --tests '*HtmlVacancyParserTest' --tests '*PlaywrightVacancySourceTest'
./gradlew spotlessCheck
./gradlew check
./gradlew build
```

As with all Gradle tasks, dependencies must already be cached for a completely
offline run (`--offline`). Real browser tests are separately tagged and do not
run in ordinary `check`/`build`; install Chromium before invoking them:

```sh
./gradlew browserTest
./gradlew browserTest -Pheaded
```

They use checked-in fixtures in `src/test/resources/fixtures`, loopback HTTP
servers and temporary SQLite databases. Coverage includes real navigation,
dynamic Locator waits, pagination/limits/deduplication, query encoding, isolation,
access denial, redirects, CAPTCHA, timeouts, resource teardown, manual/scheduled
collection, review-status preservation, multiple search matches and application
shutdown. No external vacancy site is contacted. Reports are in
`build/reports/tests/test` and `build/reports/tests/browserTest`.

Inspect `errorMessage` in the existing collection-run API:

| Diagnostic | Action |
| --- | --- |
| `DISABLED` | Enable browser collection only for a permitted configured endpoint, or select `FAKE` |
| `CONFIGURATION` | Check source registration, search URL and access confirmation; invalid enabled configuration also fails startup |
| `LAUNCH` | Run `playwrightInstall`; check the browser cache path, native libraries and desktop availability in headed mode |
| `NAVIGATION` | Check endpoint availability, TLS and HTTP errors against the authorized server |
| `TIMEOUT` | Verify response/ready markers; adjust bounded timeouts when justified |
| `STRUCTURE` | Inspect the authorized HTML fixture and update the source parser contract/tests |
| `BLOCKED` | Stop and investigate source restrictions, CAPTCHA, 403/429, redirects or an unapproved next-page origin/path |
| `SHUTDOWN` | The application is closing; restart and retry when appropriate |

Logging reports only fixed failure codes and malformed-card counts. Review
source-specific access permission before changing restrictions; browser debug
traces and screenshots are not collected by this adapter.
