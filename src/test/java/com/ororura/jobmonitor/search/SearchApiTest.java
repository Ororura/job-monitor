package com.ororura.jobmonitor.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SearchApiTest {

  private static final String GET = "GET";
  private static final String POST = "POST";
  private static final String PUT = "PUT";
  private static final String DELETE = "DELETE";

  private static final String BASE_PATH = "/api/v1/search-queries";
  private static final String EXPRESSION =
      "  (Java AND \"Spring Boot\") OR (Kotlin AND NOT \"senior developer\")  ";
  private static final SearchQueryRequest VALID_REQUEST =
      new SearchQueryRequest("Java backend", EXPRESSION, 10, true);

  @TempDir static Path temporaryDirectory;

  @LocalServerPort private int port;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  private HttpClient client;

  @DynamicPropertySource
  static void databaseDirectory(DynamicPropertyRegistry registry) {
    registry.add("job-monitor.data-directory", () -> temporaryDirectory.toString());
  }

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("DELETE FROM search_queries");
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @AfterEach
  void closeClient() {
    client.close();
  }

  @Test
  void createsListsReadsUpdatesAndDeletesQuery() throws Exception {
    assertThat(send(GET, BASE_PATH, null).body()).isEqualTo("[]");
    Instant beforeCreate = Instant.now();
    HttpResponse<String> createdResponse =
        send(POST, BASE_PATH, objectMapper.writeValueAsString(VALID_REQUEST));
    assertThat(createdResponse.statusCode()).isEqualTo(201);
    SearchQueryResponse created =
        objectMapper.readValue(createdResponse.body(), SearchQueryResponse.class);
    String path = BASE_PATH + "/" + created.id();
    assertThat(createdResponse.headers().firstValue("Location")).contains(path);
    assertThat(created.name()).isEqualTo(VALID_REQUEST.name());
    assertThat(created.queryText()).isEqualTo(EXPRESSION);
    assertThat(created.intervalMinutes()).isEqualTo(10);
    assertThat(created.enabled()).isTrue();
    assertThat(created.createdAt()).isBetween(beforeCreate, Instant.now());
    assertThat(created.updatedAt()).isEqualTo(created.createdAt());
    assertThat(created.nextRunAt()).isNull();
    assertThat(objectMapper.readTree(createdResponse.body()).get("createdAt").asString())
        .endsWith("Z");

    HttpResponse<String> getResponse = send(GET, path, null);
    assertThat(getResponse.statusCode()).isEqualTo(200);
    assertThat(objectMapper.readValue(getResponse.body(), SearchQueryResponse.class))
        .isEqualTo(created);
    HttpResponse<String> listResponse = send(GET, BASE_PATH, null);
    assertThat(listResponse.statusCode()).isEqualTo(200);
    assertThat(objectMapper.readValue(listResponse.body(), SearchQueryResponse[].class))
        .containsExactly(created);

    String updatedExpression = "  (\"Java developer\" OR Kotlin) AND NOT 'intern'  ";
    SearchQueryRequest update = new SearchQueryRequest("Changed", updatedExpression, 60, false);
    HttpResponse<String> updateResponse = send(PUT, path, objectMapper.writeValueAsString(update));
    assertThat(updateResponse.statusCode()).isEqualTo(200);
    SearchQueryResponse updated =
        objectMapper.readValue(updateResponse.body(), SearchQueryResponse.class);
    assertThat(updated.id()).isEqualTo(created.id());
    assertThat(updated.name()).isEqualTo(update.name());
    assertThat(updated.queryText()).isEqualTo(updatedExpression);
    assertThat(updated.intervalMinutes()).isEqualTo(60);
    assertThat(updated.enabled()).isFalse();
    assertThat(updated.createdAt()).isEqualTo(created.createdAt());
    assertThat(updated.updatedAt()).isAfterOrEqualTo(created.updatedAt());
    assertThat(updated.nextRunAt()).isNull();
    assertThat(objectMapper.readValue(send(GET, path, null).body(), SearchQueryResponse.class))
        .isEqualTo(updated);

    HttpResponse<String> deleteResponse = send(DELETE, path, null);
    assertThat(deleteResponse.statusCode()).isEqualTo(204);
    assertThat(deleteResponse.body()).isEmpty();
    assertThat(send(GET, path, null).statusCode()).isEqualTo(404);
    assertThat(send(GET, BASE_PATH, null).body()).isEqualTo("[]");
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM search_queries", Integer.class))
        .isZero();
  }

  @Test
  void returns404ForMissingGetUpdateAndDelete() throws Exception {
    String path = BASE_PATH + "/" + UUID.randomUUID();
    for (String method : new String[] {GET, PUT, DELETE}) {
      HttpResponse<String> response =
          send(
              method,
              path,
              PUT.equals(method) ? objectMapper.writeValueAsString(VALID_REQUEST) : null);
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(objectMapper.readTree(response.body()).get("detail").asString())
          .contains("Search query not found");
    }
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM search_queries", Integer.class))
        .isZero();
  }

  @ParameterizedTest
  @MethodSource("invalidBodies")
  void returns400WithoutChangingSqliteForInvalidCreateAndUpdate(String method, String body)
      throws Exception {
    SearchQueryResponse existing =
        objectMapper.readValue(
            send(POST, BASE_PATH, objectMapper.writeValueAsString(VALID_REQUEST)).body(),
            SearchQueryResponse.class);
    String path = POST.equals(method) ? BASE_PATH : BASE_PATH + "/" + existing.id();

    assertThat(send(method, path, body).statusCode()).isEqualTo(400);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM search_queries", Integer.class))
        .isEqualTo(1);
    assertThat(
            objectMapper.readValue(
                send(GET, BASE_PATH + "/" + existing.id(), null).body(), SearchQueryResponse.class))
        .isEqualTo(existing);
  }

  @Test
  void returns400ForMalformedIds() throws Exception {
    for (String method : new String[] {GET, PUT, DELETE}) {
      assertThat(
              send(
                      method,
                      BASE_PATH + "/not-a-uuid",
                      PUT.equals(method) ? objectMapper.writeValueAsString(VALID_REQUEST) : null)
                  .statusCode())
          .isEqualTo(400);
    }
  }

  static Stream<Arguments> invalidBodies() {
    return Stream.of(POST, PUT)
        .flatMap(
            method ->
                Stream.of(
                        "{}",
                        "null",
                        "{",
                        "",
                        "{\"name\":null,\"queryText\":\"Java\",\"intervalMinutes\":10,\"enabled\":true}",
                        "{\"name\":\"\",\"queryText\":\"Java\",\"intervalMinutes\":10,\"enabled\":true}",
                        "{\"name\":\"  \",\"queryText\":\"Java\",\"intervalMinutes\":10,\"enabled\":true}",
                        "{\"name\":\"Java\",\"queryText\":null,\"intervalMinutes\":10,\"enabled\":true}",
                        "{\"name\":\"Java\",\"queryText\":\"\",\"intervalMinutes\":10,\"enabled\":true}",
                        "{\"name\":\"Java\",\"queryText\":\"  \",\"intervalMinutes\":10,\"enabled\":true}",
                        "{\"name\":\"Java\",\"queryText\":\"Java\",\"enabled\":true}",
                        "{\"name\":\"Java\",\"queryText\":\"Java\",\"intervalMinutes\":-1,\"enabled\":true}",
                        "{\"name\":\"Java\",\"queryText\":\"Java\",\"intervalMinutes\":9,\"enabled\":true}",
                        "{\"name\":\"Java\",\"queryText\":\"Java\",\"intervalMinutes\":10}",
                        "{\"name\":\"Java\",\"queryText\":\"Java\",\"intervalMinutes\":\"invalid\",\"enabled\":true}")
                    .map(body -> Arguments.of(method, body)));
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
