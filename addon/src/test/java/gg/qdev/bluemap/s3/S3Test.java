package gg.qdev.bluemap.s3;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import de.bluecolored.bluemap.core.storage.compression.Compression;
import de.bluecolored.bluemap.core.storage.file.FileMapStorage;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class S3Test {
  HttpServer server;
  Map<String, byte[]> objects;
  List<String> requests;
  S3Client client;

  @BeforeEach
  void start() throws Exception {
    objects = new ConcurrentHashMap<>();
    requests = new CopyOnWriteArrayList<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String key = exchange.getRequestURI().getPath().substring("/bucket/".length());
          String method = exchange.getRequestMethod();
          requests.add(method + " " + key);
          byte[] response = new byte[0];
          int status = 200;
          if (exchange.getRequestURI().getRawQuery() != null) {
            String query = exchange.getRequestURI().getRawQuery();
            String prefix =
                Arrays.stream(query.split("&"))
                    .filter(s -> s.startsWith("prefix="))
                    .map(s -> URLDecoder.decode(s.substring(7), StandardCharsets.UTF_8))
                    .findFirst()
                    .orElse("");
            int page =
                query.contains("continuation-token=")
                    ? Integer.parseInt(
                        Arrays.stream(query.split("&"))
                            .filter(s -> s.startsWith("continuation-token="))
                            .findFirst()
                            .orElseThrow()
                            .split("=")[1])
                    : 0;
            // Fixed pages for listing test, normal complete listing for storage tests.
            List<String> keys =
                prefix.equals("pages/")
                    ? List.of("pages/a +é", "pages/b", "pages/c")
                    : objects.keySet().stream().filter(k -> k.startsWith(prefix)).sorted().toList();
            int end = prefix.equals("pages/") ? Math.min(page + 1, keys.size()) : keys.size();
            StringBuilder xml =
                new StringBuilder(
                    "<ListBucketResult><IsTruncated>" + (end < keys.size()) + "</IsTruncated>");
            for (String k : keys.subList(page, end))
              xml.append("<Contents><Key>")
                  .append(S3Client.encode(k, false))
                  .append("</Key></Contents>");
            if (end < keys.size())
              xml.append("<NextContinuationToken>").append(end).append("</NextContinuationToken>");
            response =
                xml.append("</ListBucketResult>").toString().getBytes(StandardCharsets.UTF_8);
          } else if (method.equals("PUT")) {
            objects.put(key, exchange.getRequestBody().readAllBytes());
          } else if (method.equals("DELETE")) {
            objects.remove(key);
            status = 204;
          } else {
            response = objects.get(key);
            if (response == null) {
              response = new byte[0];
              status = 404;
            }
          }
          exchange.sendResponseHeaders(
              status,
              method.equals("HEAD") || status == 204
                  ? -1
                  : (response.length == 0 ? -1 : response.length));
          if (!method.equals("HEAD") && status != 204) exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    client =
        new S3Client(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "bucket",
            "auto",
            "key",
            "secret",
            "",
            true);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @org.junit.jupiter.api.io.TempDir Path temporary;

  @Test
  void pathsMatchBlueMapIncludingNegativeAndLargeCoordinates() throws Exception {
    var reference = new FileMapStorage(temporary, Compression.GZIP, false).hiresTiles();
    for (int x : new int[] {0, 1, -1, 12, -123, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
      for (int z : new int[] {0, 42, -456}) {
        try (var out = reference.write(x, z)) {
          out.write(1);
        }
        assertTrue(
            java.nio.file.Files.exists(
                temporary.resolve("tiles/0/" + S3Storage.gridPath(x, z) + ".prbm.gz")));
      }
    }
  }

  @Test
  void paginatesAndDecodesKeys() throws Exception {
    try (var keys = client.list("pages/")) {
      assertEquals(List.of("pages/a +é", "pages/b", "pages/c"), keys.toList());
    }
    assertEquals(3, requests.size());
  }

  @Test
  void roundTripCompressionAndMapScopedDeletion() throws Exception {
    var storage = new S3Storage(client, "test/", "https://cdn.example.com/test");
    storage.initialize();
    var a = storage.map("custom_world");
    var sibling = storage.map("custom_world_2");
    byte[] original = "real geometry bytes".getBytes(StandardCharsets.UTF_8);
    try (var out = a.hiresTiles().write(-123, 456)) {
      out.write(original);
    }
    byte[] raw = objects.get("test/custom_world/tiles/0/x-1/2/3/z4/5/6.prbm.gz");
    assertEquals(0x1f, raw[0] & 255);
    assertEquals(0x8b, raw[1] & 255);
    try (var in = a.hiresTiles().read(-123, 456)) {
      assertArrayEquals(original, in.decompress().readAllBytes());
    }
    try (var cells = a.hiresTiles().stream()) {
      var cell = cells.findFirst().orElseThrow();
      assertEquals(-123, cell.getX());
      assertEquals(456, cell.getZ());
    }
    assertNull(a.hiresTiles().read(99, 99));
    try (var out = a.settings().write()) {
      out.write("{}".getBytes());
    }
    try (var out = sibling.settings().write()) {
      out.write("{}".getBytes());
    }
    client.put("test/settings.json", new byte[] {1}, "application/json", "no-store");
    a.delete();
    assertFalse(a.exists());
    assertTrue(sibling.exists());
    assertTrue(client.exists("test/settings.json"));
    storage.close();
    assertThrows(java.io.IOException.class, () -> sibling.settings().read());
  }

  @Test
  void signerMatchesAwsPublishedGetObjectVector() {
    var headers =
        S3Client.sign(
            "GET",
            URI.create("https://examplebucket.s3.amazonaws.com/test.txt"),
            new byte[0],
            Map.of("range", "bytes=0-9"),
            "us-east-1",
            "AKIAIOSFODNN7EXAMPLE",
            "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            "",
            Instant.parse("2013-05-24T00:00:00Z"));
    assertTrue(
        headers
            .get("authorization")
            .endsWith("f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"));
  }

  @Test
  void encodingAndSessionToken() {
    assertEquals("a%20b%2B%C3%A9/%25", S3Client.encode("a b+é/%", true));
    assertEquals("a=%2B&z=%20", S3Client.canonicalQuery(Map.of("z", " ", "a", "+")));
    var h =
        S3Client.sign(
            "GET",
            URI.create("http://localhost:9000/bucket/key"),
            new byte[0],
            Map.of(),
            "auto",
            "key",
            "secret",
            "token",
            Instant.EPOCH);
    assertEquals("localhost:9000", h.get("host"));
    assertEquals("token", h.get("x-amz-security-token"));
    assertTrue(h.get("authorization").contains("x-amz-security-token"));
  }

  @Test
  void retriesTemporaryFailuresButNotAuthenticationErrors() throws Exception {
    var attempts = new java.util.concurrent.atomic.AtomicInteger();
    server.createContext(
        "/bucket/retry",
        exchange -> {
          int status = attempts.incrementAndGet() < 3 ? 503 : 200;
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    assertNotNull(client.get("retry"));
    assertEquals(3, attempts.get());
    attempts.set(0);
    server.createContext(
        "/bucket/denied",
        exchange -> {
          attempts.incrementAndGet();
          exchange.sendResponseHeaders(403, -1);
          exchange.close();
        });
    assertThrows(java.io.IOException.class, () -> client.get("denied"));
    assertEquals(1, attempts.get());
  }

  @Test
  void rejectsRedirectsAndExternalEntities() throws Exception {
    server.createContext(
        "/bucket/redirect",
        exchange -> {
          exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/secret");
          exchange.sendResponseHeaders(307, -1);
          exchange.close();
        });
    assertThrows(java.io.IOException.class, () -> client.get("redirect"));
    server.removeContext("/");
    server.createContext(
        "/",
        exchange -> {
          byte[] xml =
              "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><ListBucketResult>&e;</ListBucketResult>"
                  .getBytes();
          exchange.sendResponseHeaders(200, xml.length);
          exchange.getResponseBody().write(xml);
          exchange.close();
        });
    assertThrows(java.io.IOException.class, () -> client.page("", null));
  }
}
