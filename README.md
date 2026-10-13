
# Job Monitor

A single-user local application for collecting and reviewing job vacancies.
The application starts Spring Boot, creates a SQLite database, applies Flyway
migrations, and provides a REST API for managing search queries. Vacancy
collection, scheduling, and UI are not implemented yet.

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

The server listens on `127.0.0.1:8080`. Search management is available at
`/api/v1/search-queries`; an HTTP 404 at `/` is expected because there is no UI.
Stop the application with Ctrl+C.

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
changing V1. Repeated startup keeps existing data and does not reapply migrations.

## Search query API

| Method | Path | Success |
| --- | --- | --- |
| GET | `/api/v1/search-queries` | 200, JSON array (empty when no queries exist) |
| GET | `/api/v1/search-queries/{id}` | 200, query JSON |
| POST | `/api/v1/search-queries` | 201, query JSON and `Location` header |
| PUT | `/api/v1/search-queries/{id}` | 200, updated query JSON |
| DELETE | `/api/v1/search-queries/{id}` | 204, no body |

POST and PUT require `name`, `queryText`, `intervalMinutes`, and `enabled`.
Name and query text must not be blank; the interval must be at least 10 minutes.
Query text is stored exactly as supplied, including whitespace, parentheses,
operators, and quotes. PUT replaces all four editable fields; it preserves the
UUID and creation time and advances `updatedAt` using the application's clock.
All timestamps are ISO-8601 UTC strings ending in `Z`, stored as TEXT in SQLite.
`nextRunAt` is server-managed and remains null in this stage without scheduling.

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
and a V1-to-V2 upgrade preserving existing data. Migration reruns remain a no-op.
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
  collection/      Placeholders for future collection and scheduling
  source/          Placeholders for future source adapters
  vacancy/         Placeholders for future vacancy persistence and review
src/main/resources/
  application.yml
  db/migration/
src/test/java/com/ororura/jobmonitor/
.github/workflows/ci.yml
```

Keep databases, browser profiles, cookies, and credentials outside Git. Live
source integrations are not enabled by this foundation. Follow `AGENTS.md`
when implementing subsequent MVP features.
