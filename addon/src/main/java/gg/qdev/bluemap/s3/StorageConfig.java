package gg.qdev.bluemap.s3;

import de.bluecolored.bluemap.common.config.ConfigurationException;
import de.bluecolored.bluemap.common.debug.DebugDump;
import de.bluecolored.bluemap.core.storage.Storage;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

@ConfigSerializable
public final class StorageConfig
    extends de.bluecolored.bluemap.common.config.storage.StorageConfig {
  private String endpointUrl = "https://s3.us-east-1.amazonaws.com";
  private String bucketName = "";
  private String region = "us-east-1";

  @DebugDump(exclude = true)
  private String accessKeyId = "";

  @DebugDump(exclude = true)
  private String secretAccessKey = "";

  @DebugDump(exclude = true)
  private String sessionToken = "";

  private boolean forcePathStyle = true;
  private String rootPath = "";
  private String publicUrl = "";

  @Override
  public Storage createStorage() throws ConfigurationException {
    try {
      if (region.isBlank() || accessKeyId.isBlank() || secretAccessKey.isBlank())
        throw new IllegalArgumentException(
            "region, access-key-id and secret-access-key are required");
      var publicRoot = java.net.URI.create(publicUrl);
      if (!("https".equals(publicRoot.getScheme()) || "http".equals(publicRoot.getScheme()))
          || publicRoot.getHost() == null
          || publicRoot.getUserInfo() != null
          || publicRoot.getQuery() != null
          || publicRoot.getFragment() != null)
        throw new IllegalArgumentException(
            "public-url must be the public HTTP(S) URL of root-path");
      String prefix = rootPath.replaceAll("^/+|/+$", "");
      if (!prefix.isEmpty() && !prefix.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*"))
        throw new IllegalArgumentException(
            "root-path must contain only path segments of letters, numbers, _ or -");
      return new S3Storage(
          new S3Client(
              endpointUrl,
              bucketName,
              region,
              accessKeyId,
              secretAccessKey,
              sessionToken,
              forcePathStyle),
          prefix.isEmpty() ? "" : prefix + "/",
          publicUrl.replaceAll("/+$", ""));
    } catch (IllegalArgumentException e) {
      throw new ConfigurationException(e.getMessage());
    }
  }
}
