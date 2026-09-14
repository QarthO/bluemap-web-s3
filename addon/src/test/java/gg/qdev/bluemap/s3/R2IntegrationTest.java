package gg.qdev.bluemap.s3;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Opt-in contract test against a real S3-compatible bucket; deletes only its unique test prefix.
 */
class R2IntegrationTest {
  @Test
  void realBucketRoundTrip() throws Exception {
    assumeTrue(System.getenv("S3_TEST_ENDPOINT") != null);
    var client =
        new S3Client(
            System.getenv("S3_TEST_ENDPOINT"),
            System.getenv("S3_TEST_BUCKET"),
            System.getenv().getOrDefault("S3_TEST_REGION", "auto"),
            System.getenv("S3_TEST_ACCESS_KEY"),
            System.getenv("S3_TEST_SECRET_KEY"),
            "",
            true);
    String prefix = "contract-test-" + UUID.randomUUID() + "/";
    var storage = new S3Storage(client, prefix, "https://cdn.example.com/" + prefix);
    try {
      storage.initialize();
      var map = storage.map("custom_world");
      byte[] bytes = "BlueMap S3 integration".getBytes(StandardCharsets.UTF_8);
      try (var out = map.hiresTiles().write(-123, 456)) {
        out.write(bytes);
      }
      try (var in = map.hiresTiles().read(-123, 456)) {
        assertArrayEquals(bytes, in.decompress().readAllBytes());
      }
      assertTrue(map.hiresTiles().exists(-123, 456));
      try (var cells = map.hiresTiles().stream()) {
        assertEquals(1, cells.count());
      }
      String unicode = prefix + "space + unicode é";
      client.put(unicode, bytes, "application/octet-stream", "no-store");
      assertArrayEquals(bytes, client.get(unicode));
      try (var keys = client.list(prefix)) {
        assertTrue(keys.toList().contains(unicode));
      }
      map.delete();
      assertFalse(map.exists());
      assertTrue(client.exists(unicode));
      client.delete(unicode);
      assertNull(client.get(unicode));
    } finally {
      try (var keys = client.list(prefix)) {
        for (var key : keys.toList()) client.delete(key);
      }
      storage.close();
    }
  }
}
