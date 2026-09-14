package gg.qdev.bluemap.s3;

import de.bluecolored.bluemap.core.storage.*;
import de.bluecolored.bluemap.core.storage.compression.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.DoublePredicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class S3Storage implements Storage {
  final S3Client client;
  final String prefix, publicUrl;
  private final Map<String, S3Map> maps = new ConcurrentHashMap<>();
  private volatile boolean closed;
  private ScheduledExecutorService publisher;

  S3Storage(S3Client client, String prefix, String publicUrl) {
    this.client = client;
    this.prefix = prefix;
    this.publicUrl = publicUrl;
  }

  @Override
  public void initialize() throws IOException {
    ensureOpen();
    client.page(prefix, null);
  }

  @Override
  public S3Map map(String id) {
    if (!id.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid map ID");
    return maps.computeIfAbsent(id, S3Map::new);
  }

  @Override
  public Stream<String> mapIds() throws IOException {
    ensureOpen();
    return client
        .list(prefix)
        .map(k -> k.substring(prefix.length()))
        .filter(k -> k.matches("[A-Za-z0-9_-]+/settings\\.json"))
        .map(k -> k.substring(0, k.indexOf('/')))
        .distinct();
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public synchronized void close() {
    closed = true;
    if (publisher != null) publisher.shutdownNow();
  }

  synchronized void startPublisher(Runnable task) {
    if (closed || publisher != null) return;
    publisher =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "BlueMap-S3-Settings");
              t.setDaemon(true);
              return t;
            });
    publisher.scheduleWithFixedDelay(task, 0, 10, TimeUnit.SECONDS);
  }

  private void ensureOpen() throws IOException {
    if (closed) throw new IOException("S3 storage is closed");
  }

  final class Item implements ItemStorage {
    final String key;
    final Compression compression;

    Item(String key, Compression compression) {
      this.key = key;
      this.compression = compression;
    }

    @Override
    public OutputStream write() throws IOException {
      ensureOpen();
      return compression.compress(
          new OutputStream() {
            private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            private boolean done, failed;

            @Override
            public void write(int b) throws IOException {
              write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
              if (done) throw new IOException("Stream closed");
              if (failed || len > S3Client.MAX_OBJECT_BYTES - bytes.size()) {
                failed = true;
                throw new IOException("S3 object exceeds 64 MiB");
              }
              bytes.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
              if (done) return;
              done = true;
              ensureOpen();
              if (failed) throw new IOException("Discarding incomplete S3 object");
              String type =
                  key.endsWith(".png")
                      ? "image/png"
                      : key.endsWith(".json")
                          ? "application/json"
                          : key.endsWith(".gz") ? "application/gzip" : "application/octet-stream";
              client.put(
                  key,
                  bytes.toByteArray(),
                  type,
                  key.contains("/tiles/") ? "public, max-age=240" : "no-store");
            }
          });
    }

    @Override
    public CompressedInputStream read() throws IOException {
      ensureOpen();
      byte[] data = client.get(key);
      return data == null
          ? null
          : new CompressedInputStream(new ByteArrayInputStream(data), compression);
    }

    @Override
    public void delete() throws IOException {
      ensureOpen();
      client.delete(key);
    }

    @Override
    public boolean exists() throws IOException {
      ensureOpen();
      return client.exists(key);
    }

    @Override
    public boolean isClosed() {
      return closed;
    }
  }

  static String gridPath(int x, int z) {
    return ("x" + x + "z" + z).replaceAll("(\\d)(?=.)", "$1/");
  }

  final class Grid implements GridStorage {
    final String root, suffix;
    final Compression compression;

    Grid(String root, String suffix, Compression compression) {
      this.root = root;
      this.suffix = suffix;
      this.compression = compression;
    }

    @Override
    public Item cell(int x, int z) {
      return new Item(root + "/" + gridPath(x, z) + suffix, compression);
    }

    @Override
    public OutputStream write(int x, int z) throws IOException {
      return cell(x, z).write();
    }

    @Override
    public CompressedInputStream read(int x, int z) throws IOException {
      return cell(x, z).read();
    }

    @Override
    public void delete(int x, int z) throws IOException {
      cell(x, z).delete();
    }

    @Override
    public boolean exists(int x, int z) throws IOException {
      return cell(x, z).exists();
    }

    @Override
    public boolean isClosed() {
      return closed;
    }

    @Override
    public Stream<Cell> stream() throws IOException {
      ensureOpen();
      Pattern pattern = Pattern.compile("x(-?\\d+)z(-?\\d+)" + Pattern.quote(suffix));
      return client
          .list(root + "/")
          .map(k -> k.substring(root.length() + 1).replace("/", ""))
          .map(pattern::matcher)
          .filter(java.util.regex.Matcher::matches)
          .map(
              m ->
                  new GridStorageCell(
                      this, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
    }
  }

  final class S3Map implements MapStorage {
    final String root;

    S3Map(String id) {
      root = prefix + id + "/";
    }

    S3Storage owner() {
      return S3Storage.this;
    }

    @Override
    public GridStorage hiresTiles() {
      return new Grid(root + "tiles/0", ".prbm.gz", Compression.GZIP);
    }

    @Override
    public GridStorage lowresTiles(int lod) {
      return new Grid(root + "tiles/" + lod, ".png", Compression.NONE);
    }

    @Override
    public GridStorage tileState() {
      return new Grid(root + "rstate", ".tiles.dat", Compression.GZIP);
    }

    @Override
    public GridStorage chunkState() {
      return new Grid(root + "rstate", ".chunks.dat", Compression.GZIP);
    }

    @Override
    public ItemStorage asset(String name) {
      return new Item(root + "assets/" + MapStorage.escapeAssetName(name), Compression.NONE);
    }

    @Override
    public ItemStorage settings() {
      return new Item(root + "settings.json", Compression.NONE);
    }

    @Override
    public ItemStorage textures() {
      return new Item(root + "textures.json.gz", Compression.GZIP);
    }

    @Override
    public ItemStorage markers() {
      return new Item(root + "live/markers.json", Compression.NONE);
    }

    @Override
    public ItemStorage players() {
      return new Item(root + "live/players.json", Compression.NONE);
    }

    @Override
    public boolean exists() throws IOException {
      ensureOpen();
      try (var keys = client.list(root)) {
        return keys.findAny().isPresent();
      }
    }

    @Override
    public boolean isClosed() {
      return closed;
    }

    @Override
    public void delete(DoublePredicate progress) throws IOException {
      ensureOpen();
      // Never delete sibling maps or the global settings; progress stays indeterminate until
      // finished.
      if (!progress.test(0)) return;
      try (var keys = client.list(root)) {
        var iterator = keys.iterator();
        while (iterator.hasNext()) {
          client.delete(iterator.next());
          if (!progress.test(0)) return;
        }
      } catch (UncheckedIOException e) {
        throw e.getCause();
      }
      progress.test(1);
    }
  }
}
