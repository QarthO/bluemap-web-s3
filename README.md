Unmodified BlueMap webapp + lightweight S3 storage addon, with automatic map discovery through your CDN.

## Setup

Build: `mvn -f addon/pom.xml package` (JDK 17+). Put `addon/target/bluemap-s3-1.0.0.jar` in `config/bluemap/packs/`; remove the old S3Storage pack and its AWS JVM flag. Tested with BlueMap 5.7 / Fabric 1.21.1 / Java 21.

Under `config/bluemap/`:

```hocon
# core.conf
accept-download: true

# storages/s3.conf
storage-type: "qdev:s3"
endpoint-url: "https://YOUR_ACCOUNT_ID.r2.cloudflarestorage.com"
bucket-name: "YOUR_BUCKET"
region: "auto"
access-key-id: "YOUR_KEY_ID"
secret-access-key: "YOUR_SECRET"
public-url: "https://cdn.example.com"

# maps/<map-id>.conf (each map)
storage: "s3"

# webapp.conf (sync UI settings too)
enabled: true

# webserver.conf
enabled: false

# plugin.conf
write-markers-interval: 10
write-players-interval: 10
```

Render state stays on the Minecraft server in `bluemap/rstate/`; keep this directory persistent and backed up. Existing S3 render state imports automatically on first use. Tiles stay in S3.

Restart Minecraft. Copy `.env.example` to `.env`; set `SETTINGS_URL=https://cdn.example.com/settings.json`, then `docker compose up -d --build`. Open `http://localhost:8080`.

R2 CORS:

```json
[{"AllowedOrigins":["*"],"AllowedMethods":["GET","HEAD"],"AllowedHeaders":["*"],"ExposeHeaders":["ETag"],"MaxAgeSeconds":3600}]
```

Cloudflare: keep the existing tile-only rule (`200–299 = 240s`, `404 = no-cache`, Browser TTL **Bypass cache**). Keep `/settings.json` and `/live/` uncached. No Worker. The addon publishes settings with `Cache-Control: no-store`.

Add/remove map configs, then `/bluemap reload`; reload the browser after rendering. No container changes. One server owns each bucket prefix. [Addon details](addon/README.md) · [Webapp options](webapp/README.md).
