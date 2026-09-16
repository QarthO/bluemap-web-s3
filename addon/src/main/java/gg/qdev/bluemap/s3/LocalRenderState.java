package gg.qdev.bluemap.s3;

import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.storage.*;
import de.bluecolored.bluemap.core.storage.compression.*;
import de.bluecolored.bluemap.core.storage.file.FileMapStorage;
import java.io.*;
import java.nio.file.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Persistent, authoritative render state. S3 is read only during the initial import. */
final class LocalRenderState {
  private final S3Storage owner;
  private final String remoteRoot;
  private final Path root, marker;
  private final FileMapStorage files;
  private boolean imported;
  final GridStorage tiles, chunks;

  LocalRenderState(S3Storage owner, String remoteRoot, Path root) {
    this.owner = owner;
    this.remoteRoot = remoteRoot;
    this.root = root;
    marker = root.resolve(".s3-imported");
    files = new FileMapStorage(root, Compression.GZIP, true);
    tiles = new LocalGrid(files.tileState());
    chunks = new LocalGrid(files.chunkState());
  }

  private synchronized void ready() throws IOException {
    owner.ensureOpen();
    if (imported) return;
    if (!Files.exists(marker)) {
      Logger.global.logInfo("Qdev S3: importing render state from " + remoteRoot + " to " + root);
      // No renderer writes are allowed until BOTH grids have imported successfully.
      // Retrying an interrupted import is safe: only completed local files are skipped.
      Pattern valid =
          Pattern.compile("x-?\\d+(?:/\\d+)*?/z-?\\d+(?:/\\d+)*\\.(tiles|chunks)\\.dat");
      try (var keys = owner.client.list(remoteRoot)) {
        var iterator = keys.iterator();
        while (iterator.hasNext()) {
          String key = iterator.next();
          String relative = key.substring(remoteRoot.length());
          if (!valid.matcher(relative).matches()) continue;
          Path target = root.resolve("rstate").resolve(relative);
          if (Files.exists(target)) continue;
          byte[] data = owner.client.get(key);
          if (data == null) throw new IOException("Render state disappeared during import: " + key);
          Files.createDirectories(target.getParent());
          Path part = Files.createTempFile(target.getParent(), ".import-", ".part");
          try {
            Files.write(part, data);
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
          } finally {
            Files.deleteIfExists(part);
          }
        }
      } catch (UncheckedIOException e) {
        throw e.getCause();
      }
      Files.createDirectories(root);
      Files.createFile(marker);
      Logger.global.logInfo("Qdev S3: render state is now local at " + root);
    }
    imported = true;
  }

  synchronized void delete() throws IOException {
    owner.ensureOpen();
    files.delete();
    Files.createDirectories(root);
    Files.createFile(marker);
    imported = true;
  }

  private final class LocalGrid implements GridStorage {
    private final GridStorage delegate;

    LocalGrid(GridStorage delegate) {
      this.delegate = delegate;
    }

    @Override
    public ItemStorage cell(int x, int z) {
      return new GridStorageCell(this, x, z);
    }

    @Override
    public OutputStream write(int x, int z) throws IOException {
      ready();
      return delegate.write(x, z);
    }

    @Override
    public CompressedInputStream read(int x, int z) throws IOException {
      ready();
      return delegate.read(x, z);
    }

    @Override
    public void delete(int x, int z) throws IOException {
      ready();
      delegate.delete(x, z);
    }

    @Override
    public boolean exists(int x, int z) throws IOException {
      ready();
      return delegate.exists(x, z);
    }

    @Override
    public boolean isClosed() {
      return owner.isClosed();
    }

    @Override
    public Stream<Cell> stream() throws IOException {
      ready();
      return delegate.stream().map(c -> new GridStorageCell(this, c.getX(), c.getZ()));
    }
  }
}
