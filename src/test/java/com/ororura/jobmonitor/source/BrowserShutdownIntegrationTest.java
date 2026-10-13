package com.ororura.jobmonitor.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.ororura.jobmonitor.JobMonitorApplication;
import com.ororura.jobmonitor.collection.CollectionDispatcher;
import com.ororura.jobmonitor.search.SearchQueryRequest;
import com.ororura.jobmonitor.search.SearchService;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.ContextClosedEvent;

@Tag("browser")
class BrowserShutdownIntegrationTest {
  @Test
  void applicationShutdownWaitsForWorkerToCloseBrowserOnItsOwningThread(@TempDir Path directory)
      throws Exception {
    try (var server = new LocalVacancyServer();
        var executor = Executors.newSingleThreadExecutor();
        var context =
            new SpringApplicationBuilder(JobMonitorApplication.class, RecordingConfiguration.class)
                .web(WebApplicationType.NONE)
                .run(
                    "--job-monitor.data-directory=" + directory,
                    "--app.playwright.enabled=true",
                    "--app.playwright.access-confirmed=true",
                    "--app.playwright.search-url=" + server.searchUrl(),
                    "--app.playwright.headless="
                        + System.getProperty("jobmonitor.browser.headless", "true"),
                    "--app.playwright.navigation-timeout=5s",
                    "--app.playwright.operation-timeout=1s")) {
      assertThat(context.getBean(com.zaxxer.hikari.HikariDataSource.class).getJdbcUrl())
          .isEqualTo("jdbc:sqlite:" + directory.resolve("job-monitor.db"));
      assertThat(context.getBean(PlaywrightProperties.class).enabled()).isTrue();
      server.delayResponse();
      var search =
          context
              .getBean(SearchService.class)
              .create(new SearchQueryRequest("Browser", "Java", 10, false, "PLAYWRIGHT"));
      var recording = context.getBean(RecordingBrowser.class);
      context.getBean(CollectionDispatcher.class).runManually(search.id());
      assertThat(server.awaitRequest()).isTrue();
      assertThat(recording.owner.get().getName()).startsWith("collection-worker-");
      assertThat(recording.lastBrowser.get().isConnected()).isTrue();
      var closing = new CountDownLatch(1);
      context.addApplicationListener(
          event -> {
            if (event instanceof ContextClosedEvent) {
              closing.countDown();
            }
          });
      var shutdown = executor.submit(context::close);
      assertThat(closing.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(shutdown.isDone()).isFalse();
      server.releaseResponse();
      shutdown.get(15, TimeUnit.SECONDS);
      assertThat(recording.lastPage.get().isClosed()).isTrue();
      assertThat(recording.lastBrowser.get().isConnected()).isFalse();
      assertThat(recording.closedOn.get()).isSameAs(recording.owner.get());
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("job-monitor.db"));
          var statement = connection.createStatement();
          var result =
              statement.executeQuery("SELECT status, error_message FROM collection_runs")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString("status")).isEqualTo("FAILED");
        assertThat(result.getString("error_message"))
            .isEqualTo("Browser collection failed: SHUTDOWN");
      }
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class RecordingConfiguration {
    @Bean
    @Primary
    RecordingBrowser recordingBrowser(PlaywrightProperties properties) {
      return new RecordingBrowser(properties);
    }
  }

  static final class RecordingBrowser extends ChromiumBrowser {
    private final AtomicReference<Browser> lastBrowser = new AtomicReference<>();
    private final AtomicReference<Page> lastPage = new AtomicReference<>();
    private final AtomicReference<Thread> owner = new AtomicReference<>();
    private final AtomicReference<Thread> closedOn = new AtomicReference<>();

    RecordingBrowser(PlaywrightProperties properties) {
      super(properties);
    }

    @Override
    public <T> T withPage(Function<Page, T> action) {
      try {
        return super.withPage(
            page -> {
              owner.set(Thread.currentThread());
              lastPage.set(page);
              lastBrowser.set(page.context().browser());
              return action.apply(page);
            });
      } finally {
        closedOn.set(Thread.currentThread());
      }
    }
  }
}
