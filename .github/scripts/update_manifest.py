#!/usr/bin/env python3
"""Builds the schema-v1 update-feed manifest for a published release.

nightly.yml (nightly.json) and release.yml (stable.json) run this after `gh release create`,
passing the signed APK files that were just uploaded. The manifest is uploaded to the fixed
`update-feed` release, whose assets the unauthenticated in-app updater fetches instead of the
API: release-asset downloads are CDN-served and do not count against the 60-requests-per-hour
limit. The JSON schema is contracted by the app's UpdateFeedManifest.kt (schema == 1); nothing
in the manifest depends on or carries a repository secret.

Python 3 standard library only, so it runs on any Actions runner without setup.
"""

import argparse
import hashlib
import json
import sys
from pathlib import Path

OWNER = "NatuneGroup/ArchiveTune"
DEFAULT_DOWNLOAD_URL_BASE = f"https://github.com/{OWNER}/releases/download"
CHANNELS = ("nightly", "stable")
SCHEMA_VERSION = 1


def sha256_and_size(path: Path) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            size += len(chunk)
            digest.update(chunk)
    return digest.hexdigest(), size


def asset_entry(path: Path, tag: str, download_url_base: str) -> dict:
    sha256, size = sha256_and_size(path)
    return {
        "name": path.name,
        "url": f"{download_url_base}/{tag}/{path.name}",
        "size": size,
        "sha256": sha256,
    }


def normalize_published_at(value: str) -> str:
    # GitHub reports UTC instants as "...Z"; only the +00:00 spelling needs rewriting. Anything
    # else is passed through untouched — the app tolerates any string here.
    if value.endswith("+00:00"):
        return value[: -len("+00:00")] + "Z"
    return value


def build_manifest(
    *,
    channel: str,
    tag: str,
    version_name: str,
    version_code: int,
    commit: str,
    published_at: str,
    release_url: str,
    notes: str,
    apk_paths: list[Path],
    download_url_base: str,
) -> dict:
    return {
        "schema": SCHEMA_VERSION,
        "channel": channel,
        "tag": tag,
        "versionName": version_name,
        "versionCode": version_code,
        "commit": commit,
        "publishedAt": normalize_published_at(published_at),
        "releaseUrl": release_url,
        "notes": notes,
        "assets": [asset_entry(path, tag, download_url_base) for path in apk_paths],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Build a schema-v1 update-feed manifest.")
    parser.add_argument("--channel", required=True, choices=CHANNELS)
    parser.add_argument("--tag", required=True, help="tag of the release just created")
    parser.add_argument("--version-code", required=True, type=int, help="monotonic build number")
    parser.add_argument("--version-name", help="defaults to the tag")
    parser.add_argument("--commit", required=True, help="full commit SHA the release targets")
    parser.add_argument("--published-at", required=True, help="release publish instant (ISO 8601)")
    parser.add_argument("--release-url", required=True, help="HTML page of the release")
    parser.add_argument("--notes-file", help="release-notes markdown to embed verbatim")
    parser.add_argument(
        "--download-url-base",
        default=DEFAULT_DOWNLOAD_URL_BASE,
        help="asset URL prefix; the manifest URL is <base>/<tag>/<asset name>",
    )
    parser.add_argument("--output", help="write JSON here instead of stdout")
    parser.add_argument("apks", nargs="+", help="signed APK files uploaded to the release")
    args = parser.parse_args()

    if args.version_code <= 0:
        parser.error("--version-code must be a positive build number")
    if not args.tag.strip():
        parser.error("--tag must not be blank")

    apk_paths = [Path(apk) for apk in args.apks]
    for path in apk_paths:
        if not path.is_file():
            parser.error(f"APK not found: {path}")

    notes = ""
    if args.notes_file:
        notes_path = Path(args.notes_file)
        if not notes_path.is_file():
            parser.error(f"notes file not found: {notes_path}")
        notes = notes_path.read_text(encoding="utf-8")

    manifest = build_manifest(
        channel=args.channel,
        tag=args.tag,
        version_name=args.version_name or args.tag,
        version_code=args.version_code,
        commit=args.commit,
        published_at=args.published_at,
        release_url=args.release_url,
        notes=notes,
        apk_paths=apk_paths,
        download_url_base=args.download_url_base.rstrip("/"),
    )

    text = json.dumps(manifest, indent=2, ensure_ascii=False) + "\n"
    if args.output:
        Path(args.output).write_text(text, encoding="utf-8")
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
