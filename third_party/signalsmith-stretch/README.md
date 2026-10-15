# signalsmith-stretch (vendored)

Header-only polyphonic pitch/time library, used by the transport's
independent pitch shift. Vendored rather than added as a submodule because
it is headers only, and because `assembleDebug` already needs two
submodules checked out before it will configure — a third would be a third
way for a fresh clone to fail.

| part | upstream | commit | licence |
| --- | --- | --- | --- |
| signalsmith-stretch 1.4.0 | https://github.com/Signalsmith-Audio/signalsmith-stretch | `a670068d9aeb64913331d5cc29337b19a457a7df` | MIT (LICENSE-stretch.txt) |
| signalsmith-linear 0.6.5 | https://github.com/Signalsmith-Audio/linear | `146b26f91b48835d665fa0498d64d9617818d27a` | MIT (LICENSE-linear.txt) |

Verified against official immutable upstream sources on 2026-10-14. The existing
Stretch runtime header already matches stable 1.4.0 byte-for-byte (Git blob
`cd13f2e98b8967577c6da9bc848d4e42c5eeab2a`); its upstream internal `{1,3,2}`
version constant is intentionally unchanged. Only Linear's `linear.h` and `stft.h`
needed refresh; its other headers and MIT notice already match 0.6.5.

SHA-256 of the exact Linear 0.6.5 headers:
- `linear.h`: `7cfd0f9e91ee1bb1f1a582824b404fffdc2c041fc1e3db67341d2ebb1035740f`
- `stft.h`: `7422ff5ddc2a017dc0587dcce99ce5ba30a2414bc2996081f6b00d56905d9b8c`

Only the headers are taken. Upstream's `cmd/` (which carries its own
submodule) and `web/` are not vendored.

`signalsmith-stretch.h` includes `signalsmith-linear/stft.h` relative to
itself, which is why `linear`'s headers sit in a subdirectory of that exact
name rather than beside it.
