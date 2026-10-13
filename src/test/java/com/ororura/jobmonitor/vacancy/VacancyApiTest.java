package com.ororura.jobmonitor.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.ororura.jobmonitor.search.SearchQuery;
import com.ororura.jobmonitor.search.SearchQueryRequest;
import com.ororura.jobmonitor.search.SearchService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VacancyApiTest {

  private static final String BASE_PATH = "/api/v1/vacancies";
  private static final String GET = "GET";
  private static final String PATCH = "PATCH";
  private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
  private static final String HH = "HH";

  @TempDir static Path temporaryDirectory;

  @LocalServerPort private int port;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private VacancyRepository repository;
  @Autowired private VacancyService service;
  @Autowired private SearchService searchService;
  private HttpClient client;

  @DynamicPropertySource
  static void databaseDirectory(DynamicPropertyRegistry registry) {
    registry.add("job-monitor.data-directory", () -> temporaryDirectory.toString());
  }

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("DELETE FROM vacancies");
    jdbcTemplate.update("DELETE FROM search_queries");
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @AfterEach
  void closeClient() {
    client.close();
  }

  @Test
  void listsEmptyPageWithDefaults() throws Exception {
    HttpResponse<String> response = send(GET, BASE_PATH, null);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(objectMapper.readValue(response.body(), VacancyPage.class))
        .isEqualTo(new VacancyPage(List.of(), 0, 20, 0));
  }

  @Test
  void getsAllFieldsAndUtcTimestampsByUuid() throws Exception {
    Vacancy vacancy = repository.upsert(VacancyRepositoryTest.data(HH, "1"), NOW, List.of());

    HttpResponse<String> response = send(GET, BASE_PATH + "/" + vacancy.id(), null);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(objectMapper.readValue(response.body(), Vacancy.class)).isEqualTo(vacancy);
    assertThat(objectMapper.readTree(response.body()).get("firstSeenAt").asString()).endsWith("Z");
    assertThat(objectMapper.readTree(response.body()).get("lastSeenAt").asString()).endsWith("Z");
    assertThat(objectMapper.readTree(response.body()).get("publishedAt").asString()).endsWith("Z");
  }

  @ParameterizedTest
  @EnumSource(VacancyStatus.class)
  void patchesStatusWithoutChangingDataOrObservationTimes(VacancyStatus status) throws Exception {
    Vacancy original = repository.upsert(VacancyRepositoryTest.data(HH, "1"), NOW, List.of());

    HttpResponse<String> response =
        send(
            PATCH,
            BASE_PATH + "/" + original.id() + "/status",
            objectMapper.writeValueAsString(new VacancyStatusRequest(status)));

    assertThat(response.statusCode()).isEqualTo(200);
    Vacancy updated = objectMapper.readValue(response.body(), Vacancy.class);
    assertThat(updated.status()).isEqualTo(status);
    assertThat(updated).usingRecursiveComparison().ignoringFields("status").isEqualTo(original);
    assertThat(service.findById(original.id())).isEqualTo(updated);
    assertThat(
            repository
                .upsert(VacancyRepositoryTest.data(HH, "1"), NOW.plusSeconds(60), List.of())
                .status())
        .isEqualTo(status);
  }

  @Test
  void listsPagesInBothDirectionsAndCombinesAllFilters() throws Exception {
    SearchQuery firstSearch =
        searchService.create(new SearchQueryRequest("First", "Java", 30, true));
    SearchQuery secondSearch =
        searchService.create(new SearchQueryRequest("Second", "Java", 30, true));
    Vacancy first =
        repository.upsert(
            VacancyRepositoryTest.data(HH, "1"), NOW, List.of(firstSearch.id(), secondSearch.id()));
    Vacancy second =
        repository.upsert(
            VacancyRepositoryTest.data("FAKE", "2"), NOW.plusSeconds(1), List.of(firstSearch.id()));
    Vacancy third =
        repository.upsert(VacancyRepositoryTest.data(HH, "3"), NOW.plusSeconds(2), List.of());
    service.changeStatus(first.id(), VacancyStatus.SAVED);

    VacancyPage defaults = getPage("");
    assertThat(defaults.content())
        .extracting(Vacancy::id)
        .containsExactly(third.id(), second.id(), first.id());
    assertThat(defaults.totalElements()).isEqualTo(3);
    VacancyPage page = getPage("?page=1&size=1&sort=firstSeenAt,asc");
    assertThat(page.content()).containsExactly(second);
    assertThat(page.page()).isEqualTo(1);
    assertThat(page.size()).isEqualTo(1);
    assertThat(page.totalElements()).isEqualTo(3);
    assertThat(getPage("?page=3&size=1").content()).isEmpty();
    assertThat(getPage("?status=SAVED").content())
        .extracting(Vacancy::id)
        .containsExactly(first.id());
    assertThat(getPage("?source=FAKE").content()).containsExactly(second);
    assertThat(getPage("?source=HH").totalElements()).isEqualTo(2);
    assertThat(getPage("?searchQueryId=" + firstSearch.id()).content())
        .extracting(Vacancy::id)
        .containsExactly(second.id(), first.id());
    assertThat(getPage("?status=SAVED&source=HH&searchQueryId=" + secondSearch.id()).content())
        .extracting(Vacancy::id)
        .containsExactly(first.id());
    assertThat(getPage("?status=IGNORED").totalElements()).isZero();
    assertThat(getPage("?searchQueryId=" + UUID.randomUUID()).content()).isEmpty();
  }

  @Test
  void returns404ForMissingVacancyAndStatusChange() throws Exception {
    String path = BASE_PATH + "/" + UUID.randomUUID();
    HttpResponse<String> get = send(GET, path, null);
    HttpResponse<String> patch = send(PATCH, path + "/status", "{\"status\":\"SAVED\"}");
    assertThat(get.statusCode()).isEqualTo(404);
    assertThat(patch.statusCode()).isEqualTo(404);
    assertThat(objectMapper.readTree(get.body()).get("detail").asString())
        .contains("Vacancy not found");
    assertThat(objectMapper.readTree(patch.body()).get("detail").asString())
        .contains("Vacancy not found");
  }

  @Test
  void returns400ForMalformedIds() throws Exception {
    assertThat(send(GET, BASE_PATH + "/not-a-uuid", null).statusCode()).isEqualTo(400);
    assertThat(send(PATCH, BASE_PATH + "/not-a-uuid/status", "{\"status\":\"NEW\"}").statusCode())
        .isEqualTo(400);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "null",
        "{",
        "",
        "{\"status\":null}",
        "{\"status\":\"UNKNOWN\"}",
        "{\"status\":\"saved\"}",
        "{\"status\":0}",
        "{\"status\":true}"
      })
  void returns400ForInvalidStatusWithoutChangingVacancy(String body) throws Exception {
    Vacancy vacancy = repository.upsert(VacancyRepositoryTest.data(HH, "1"), NOW, List.of());

    assertThat(send(PATCH, BASE_PATH + "/" + vacancy.id() + "/status", body).statusCode())
        .isEqualTo(400);
    assertThat(service.findById(vacancy.id())).isEqualTo(vacancy);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "?page=-1",
        "?page=invalid",
        "?page=2147483648",
        "?size=0",
        "?size=-1",
        "?size=101",
        "?size=invalid",
        "?status=UNKNOWN",
        "?source=%20",
        "?searchQueryId=not-a-uuid",
        "?sort=title,asc",
        "?sort=firstSeenAt,invalid"
      })
  void returns400ForInvalidFiltersPaginationAndSort(String query) throws Exception {
    assertThat(send(GET, BASE_PATH + query, null).statusCode()).isEqualTo(400);
  }

  @Test
  void hasNoPublicCreationEndpoint() throws Exception {
    assertThat(send("POST", BASE_PATH, "{}").statusCode()).isEqualTo(405);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vacancies", Integer.class))
        .isZero();
  }

  private VacancyPage getPage(String query) throws Exception {
    HttpResponse<String> response = send(GET, BASE_PATH + query, null);
    assertThat(response.statusCode()).isEqualTo(200);
    return objectMapper.readValue(response.body(), VacancyPage.class);
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
