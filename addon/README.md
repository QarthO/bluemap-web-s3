S3 storage for BlueMap 5.7 using JDK HTTP, SHA-256/HMAC signing, and BlueMap's own compression/JSON libraries. No bundled runtime dependencies or native binaries.

`mvn package` builds the jar; `mvn test` runs contract tests against an in-process HTTP server. Optional `S3_TEST_ENDPOINT`, `S3_TEST_BUCKET`, `S3_TEST_REGION`, `S3_TEST_ACCESS_KEY`, and `S3_TEST_SECRET_KEY` enable the real-bucket test, isolated under a random prefix and cleaned afterward. Compilation uses the last published BlueMapCommon API (5.3); runtime compatibility is tested on 5.7, not promised for future internal APIs.

Additional storage config:

```hocon
render-state-path: "bluemap/rstate" # Local directory, relative to the Minecraft working directory
root-path: ""             # Optional bucket prefix, e.g. "server-one"
force-path-style: true    # false for virtual-hosted S3 endpoints
session-token: ""         # Optional temporary credential token; no automatic refresh
```

`public-url` must point to that prefix, e.g. `https://cdn.example.com/server-one`. Use the corresponding `/server-one/settings.json` as the webapp's `SETTINGS_URL`. Credentials need ListBucket and GetObject/PutObject/DeleteObject within the prefix. Existing bucket required; AWS S3 uses its regional endpoint and region instead of R2's `auto`.

The addon preserves BlueMap's existing gzip tile/texture paths. PNGs and live JSON remain uncompressed. No tile migration is required from S3Storage 1.5.1 with the same root path. Single-object PUTs are limited to 64 MiB of compressed data, buffered per active write; larger objects fail instead of uploading a partial object. Requests use bounded responses, timeouts and three attempts for transport failures, throttling and transient server errors. Listings paginate; redirects are rejected. No multipart upload, credential discovery, bucket creation, or ACL management.

Every 10 seconds, the addon checks local webapp settings and publishes changes to `<prefix>/settings.json`. It preserves UI preferences, replaces the map list with currently loaded maps using this storage, and supplies CDN roots plus `clientDecompression: true`. With local webapp generation disabled, it publishes default UI settings. Reloading BlueMap updates additions/removals, including an empty map list. Removing a map config doesn't delete its stored tiles; use BlueMap's purge command when needed. Settings publishing retries failures and stops when storage closes.

Use one server/storage definition per prefix: the settings document has one owner, not a distributed map registry. Already-open browser tabs require a reload to discover maps or refresh terrain. TTL is independent of Minecraft saves.

Render state (`rstate`) is authoritative on local disk, isolated by endpoint/bucket/prefix and map ID. On first access to each map, the addon imports both existing S3 state grids before allowing reads or writes. Failed imports block state access and retry; a persistent `.s3-imported` marker prevents repeated imports. Existing remote state is left untouched and becomes stale. Subsequent state operations use BlueMap's atomic file storage with no S3 requests; no tiles are stored locally. Purging a map clears its local state too.

Keep `render-state-path` on persistent storage and back it up with your world. Move it with the server; don't discard it or switch back to an older addon expecting remote state to be current. Losing the directory can re-import stale remote state: force-update the maps after recovery. No webapp mount or Cloudflare changes are needed.
