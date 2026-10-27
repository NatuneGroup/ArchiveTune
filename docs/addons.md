# Add-on Protocols

ArchiveTune accepts declarative HTTPS add-on servers. It fetches JSON only and never downloads or
executes add-on code. HTTP is not supported because the app's network security policy denies
cleartext traffic. In Settings, enter either an add-on base URL or its `manifest.json` URL.

## BitChord-Compatible Servers

The default protocol matches BitChord's addon server contract. The manifest at
`{base}/manifest.json` describes the addon and may provide request settings:

```json
{
  "id": "example.catalog",
  "name": "Example Catalog",
  "version": "1.0.0",
  "resources": ["search", "stream"],
  "settings": [
    {"key": "region", "default": "US"},
    {"key": "quality", "default": "normal", "options": [{"value": "lossless"}, {"value": "high"}]}
  ]
}
```

ArchiveTune sends declared setting defaults on requests and selects a declared lossless quality
option when available. Search uses `GET {base}/search?q=<query>` and reads `tracks[]`:

```json
{
  "tracks": [
    {
      "id": "tidal:track-123",
      "title": "Example Track",
      "artist": "Example Artist",
      "album": "Example Album",
      "duration": 205,
      "artworkURL": "https://example.com/cover.jpg",
      "streamURL": "https://cdn.example.com/track.flac",
      "format": "flac",
      "audioQuality": "LOSSLESS"
    }
  ]
}
```

Track `duration` is in seconds. `streamURL` is an optional direct fallback. Otherwise the app requests
`GET {base}/stream/{id}`. A stream response may describe codec, quality, bitrate (bits per second),
sample rate (hertz), bit depth, and transport (`manifest`: `hls` or `dash`). Encrypted streams are
rejected. A missing manifest is also supported when `{base}/search` responds to the install probe.

## ArchiveTune Endpoint Manifests

The earlier ArchiveTune format remains supported. Its manifest can declare `searchEndpoint` and
`streamEndpoint` (containing `{id}`); search may return `results[]` instead of `tracks[]`:

```json
{
  "id": "example.catalog",
  "name": "Example Catalog",
  "searchEndpoint": "/search",
  "streamEndpoint": "/stream/{id}"
}
```

Unknown fields are ignored. Responses are limited to 1 MB, no more than 20 add-ons can be installed,
and each search contributes at most 50 tracks per add-on.