Official checksum-pinned BlueMap 5.24 webapp in Nginx, without UI changes or local tiles.

`SETTINGS_URL` redirects `/settings.json` to the addon's public settings document. The browser downloads it directly; nothing is copied or cached in the container. No S3 credentials.

Optional alternatives, in precedence order:

1. Mount a directory containing `settings.json` at `/config:ro`; served unchanged and supports atomic replacements without restart.
2. Set `SETTINGS_URL` to an absolute HTTP(S) URL (no credentials, queries, or fragments).
3. Set `MAP_DATA_ROOT`, comma-separated `MAP_IDS`, and optional `LIVE_DATA_ROOT` (defaults to the map root). Startup generates settings; no variables gives an empty map list.

The mounted/generated alternatives need `clientDecompression: true` for this addon's gzip objects. The generated fallback sets it automatically.
