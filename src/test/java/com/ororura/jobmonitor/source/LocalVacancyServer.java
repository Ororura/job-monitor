package com.ororura.jobmonitor.source;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class LocalVacancyServer implements AutoCloseable {
  private final HttpServer server;
  private final ExecutorService executor = Executors.newFixedThreadPool(2);
  private final List<URI> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger cookiesReceived = new AtomicInteger();
  private final CountDownLatch requestStarted = new CountDownLatch(1);
  private final CountDownLatch releaseRequest = new CountDownLatch(1);
  private volatile String fixtureName = "results";
  private volatile int responseStatus = 200;
  private volatile String redirectTarget = "";
  private volatile boolean delayed;
  private volatile boolean asynchronous;
  private volatile String suffix = "";

  public LocalVacancyServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext("/", this::respond);
    server.start();
  }

  public URI searchUrl() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search");
  }

  public List<URI> requests() {
    return List.copyOf(received);
  }

  public int cookieCount() {
    return cookiesReceived.get();
  }

  public void fixture(String name) {
    fixtureName = name;
    responseStatus = 200;
  }

  public void status(int status) {
    responseStatus = status;
  }

  public void redirect(URI target) {
    redirectTarget = target.toString();
    responseStatus = 302;
  }

  public void delayResponse() {
    delayed = true;
  }

  public void asynchronousResults() {
    asynchronous = true;
  }

  public void appendHtml(String html) {
    suffix = html;
  }

  public boolean awaitRequest() throws InterruptedException {
    return requestStarted.await(10, TimeUnit.SECONDS);
  }

  public void releaseResponse() {
    releaseRequest.countDown();
  }

  private void respond(HttpExchange exchange) throws IOException {
    try (exchange) {
      received.add(exchange.getRequestURI());
      if (exchange.getRequestHeaders().containsKey("Cookie")) {
        cookiesReceived.incrementAndGet();
      }
      requestStarted.countDown();
      if (delayed) {
        try {
          if (!releaseRequest.await(10, TimeUnit.SECONDS)) {
            return;
          }
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          return;
        }
      }
      boolean secondPage =
          exchange.getRequestURI().getRawQuery() != null
              && exchange.getRequestURI().getRawQuery().contains("page=2");
      String html =
          HtmlVacancyParserTest.fixture(
              secondPage && fixtureName.equals("results") ? "page2" : fixtureName);
      if (asynchronous) {
        html =
            "<html><body><script>setTimeout(() => document.body.innerHTML = `"
                + html.replace("`", "\\`")
                + "`, 50)</script></body></html>";
      }
      byte[] content = (html + suffix).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
      exchange.getResponseHeaders().add("Set-Cookie", "fixture_session=local; Path=/");
      if (!redirectTarget.isEmpty()) {
        exchange.getResponseHeaders().set("Location", redirectTarget);
      }
      exchange.sendResponseHeaders(responseStatus, content.length);
      exchange.getResponseBody().write(content);
    }
  }

  @Override
  public void close() {
    releaseResponse();
    server.stop(0);
    executor.shutdownNow();
  }
}
