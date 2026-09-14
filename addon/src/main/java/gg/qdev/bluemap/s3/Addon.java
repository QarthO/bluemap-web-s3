package gg.qdev.bluemap.s3;

import com.google.gson.*;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.common.api.BlueMapAPIImpl;
import de.bluecolored.bluemap.common.config.storage.StorageType;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.util.Key;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Addon implements Runnable {
  @Override
  public void run() {
    StorageType.REGISTRY.register(
        new StorageType() {
          @Override
          public Key getKey() {
            return new Key("qdev", "s3");
          }

          @Override
          public Class<StorageConfig> getConfigType() {
            return StorageConfig.class;
          }
        });
    BlueMapAPI.onEnable(
        api -> {
          var service = ((BlueMapAPIImpl) api).blueMapService();
          Set<S3Storage> stores = new HashSet<>();
          // Include configured but currently unused stores so removing the last map publishes [].
          for (var entry : service.getConfig().getStorageConfigs().entrySet()) {
            if (!(entry.getValue() instanceof StorageConfig)) continue;
            try {
              stores.add((S3Storage) service.getOrLoadStorage(entry.getKey()));
            } catch (Exception e) {
              Logger.global.logError("Qdev S3: could not initialize settings publisher", e);
            }
          }
          for (var storage : stores)
            storage.startPublisher(
                new Runnable() {
                  String previous;

                  @Override
                  public void run() {
                    if (storage.isClosed()) return;
                    try {
                      Path settings = service.getWebFilesManager().getSettingsFile();
                      JsonObject json;
                      if (service.getConfig().getWebappConfig().isEnabled()
                          && Files.exists(settings)) {
                        try (var reader = Files.newBufferedReader(settings)) {
                          json = new JsonParser().parse(reader).getAsJsonObject();
                        }
                      } else {
                        json = new JsonObject();
                        json.addProperty("useCookies", true);
                      }
                      List<String> ids =
                          service.getMaps().entrySet().stream()
                              .filter(
                                  e ->
                                      e.getValue().getStorage() instanceof S3Storage.S3Map m
                                          && m.owner() == storage)
                              .map(Map.Entry::getKey)
                              .sorted()
                              .toList();
                      json.add("maps", new Gson().toJsonTree(ids));
                      json.addProperty("mapDataRoot", storage.publicUrl);
                      json.addProperty("liveDataRoot", storage.publicUrl);
                      json.addProperty("clientDecompression", true);
                      String next = json.toString();
                      if (!next.equals(previous)) {
                        storage.client.put(
                            storage.prefix + "settings.json",
                            next.getBytes(StandardCharsets.UTF_8),
                            "application/json",
                            "no-store");
                        previous = next; // Failed writes are retried on the next interval.
                        Logger.global.logInfo(
                            "Qdev S3: published webapp settings for " + ids.size() + " maps");
                      }
                    } catch (Exception e) {
                      Logger.global.logError("Qdev S3: settings sync failed; will retry", e);
                    }
                  }
                });
        });
  }
}
