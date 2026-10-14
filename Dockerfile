# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk-noble@sha256:589ff4cc3f71aab462e7048a47a0d10edf57fbccde3fceea2281e610bf5880b4 AS build
WORKDIR /workspace
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY src/ src/
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar \
    && mkdir -p build/extracted \
    && cd build/extracted \
    && jar -xf ../libs/job-monitor-*.jar BOOT-INF/lib BOOT-INF/classes

FROM eclipse-temurin:25-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab
WORKDIR /app
COPY --from=build /workspace/build/extracted/BOOT-INF/lib/ /app/lib/
COPY --from=build /workspace/build/extracted/BOOT-INF/classes/ /app/classes/
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    JOB_MONITOR_DATA_DIR=/data \
    SERVER_ADDRESS=0.0.0.0
RUN java --enable-native-access=ALL-UNNAMED -cp '/app/lib/*' \
        com.microsoft.playwright.CLI install --with-deps chromium \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 jobmonitor \
    && useradd --uid 10001 --gid jobmonitor --create-home jobmonitor \
    && mkdir -p /data \
    && chown jobmonitor:jobmonitor /data \
    && chmod -R a+rX /ms-playwright
USER 10001:10001
VOLUME ["/data"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent --output /dev/null http://127.0.0.1:8080/api/v1/search-queries || exit 1
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-cp", "/app/classes:/app/lib/*", "com.ororura.jobmonitor.JobMonitorApplication"]
