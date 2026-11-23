#!/usr/bin/env python3
"""
Fetches reproducible upstream English corpora into tools/data_sources/english_corpora/
for periodic vocabulary ingestion:
  1. Debian Testing/Stable Translation-en.xz (OSS/tech English package prose)
  2. Homebrew Core formula.json & cask.json (modern CLI/SaaS/desktop tool metadata)
  3. Mozc-UT jawiki & sudachidict .txt.bz2 (monthly Japanese-English Wikipedia & modern entities)
"""

import argparse
import datetime
import gzip
import json
import os
import sys
import time
import urllib.error
import urllib.request

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(REPO_ROOT, "tools", "data_sources", "english_corpora")
MANIFEST_PATH = os.path.join(OUT_DIR, "manifest.json")

SOURCES = [
    {
        "name": "debian_translation_en",
        "filename": "debian_translation_en.xz",
        "urls": [
            "https://deb.debian.org/debian/dists/trixie/main/i18n/Translation-en.xz",
            "https://deb.debian.org/debian/dists/bookworm/main/i18n/Translation-en.xz",
            "https://deb.debian.org/debian/dists/sid/main/i18n/Translation-en.xz",
        ],
        "gunzip": False,
    },
    {
        "name": "homebrew_formula",
        "filename": "homebrew_formula.json",
        "urls": ["https://formulae.brew.sh/api/formula.json"],
        "gunzip": True,
    },
    {
        "name": "homebrew_cask",
        "filename": "homebrew_cask.json",
        "urls": ["https://formulae.brew.sh/api/cask.json"],
        "gunzip": True,
    },
    {
        "name": "mozcdic_ut_jawiki",
        "filename": "mozcdic-ut-jawiki.txt.bz2",
        "urls": [
            "https://raw.githubusercontent.com/utuhiro78/mozcdic-ut-jawiki/master/mozcdic-ut-jawiki.txt.bz2",
        ],
        "gunzip": False,
    },
    {
        "name": "mozcdic_ut_sudachidict",
        "filename": "mozcdic-ut-sudachidict.txt.bz2",
        "urls": [
            "https://raw.githubusercontent.com/utuhiro78/mozcdic-ut-sudachidict/master/mozcdic-ut-sudachidict.txt.bz2",
        ],
        "gunzip": False,
    },
]


def is_fresh(path, max_age_hours):
    if not os.path.exists(path) or os.path.getsize(path) == 0:
        return False
    age_seconds = time.time() - os.path.getmtime(path)
    return age_seconds <= max_age_hours * 3600.0


def fetch_url(url, dest_path, allow_gunzip=False, timeout=60):
    tmp_path = dest_path + ".tmp"
    headers = {"User-Agent": "JotInput-LexiconPipeline/1.0"}
    if allow_gunzip:
        headers["Accept-Encoding"] = "gzip"
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            status = getattr(resp, "status", 200)
            if status != 200:
                raise RuntimeError(f"HTTP {status} for {url}")
            last_modified = resp.headers.get("Last-Modified")
            etag = resp.headers.get("ETag")
            content_encoding = (resp.headers.get("Content-Encoding") or "").lower()

            if allow_gunzip and "gzip" in content_encoding:
                with gzip.GzipFile(fileobj=resp) as gz_in, open(tmp_path, "wb") as out_f:
                    while True:
                        chunk = gz_in.read(65536)
                        if not chunk:
                            break
                        out_f.write(chunk)
            else:
                with open(tmp_path, "wb") as out_f:
                    head = resp.read(2)
                    if not head:
                        raise RuntimeError(f"Empty response for {url}")
                    if allow_gunzip and head == b"\x1f\x8b":
                        rest = resp.read()
                        out_f.write(gzip.decompress(head + rest))
                    else:
                        out_f.write(head)
                        while True:
                            chunk = resp.read(65536)
                            if not chunk:
                                break
                            out_f.write(chunk)

        if os.path.getsize(tmp_path) == 0:
            raise RuntimeError(f"Downloaded 0 bytes for {url}")
        os.replace(tmp_path, dest_path)
        return {
            "url": url,
            "bytes": os.path.getsize(dest_path),
            "last_modified": last_modified,
            "etag": etag,
        }
    except Exception:
        if os.path.exists(tmp_path):
            try:
                os.remove(tmp_path)
            except OSError:
                pass
        raise


def main():
    parser = argparse.ArgumentParser(description="Fetch upstream English corpora for periodic ingestion")
    parser.add_argument("--force", action="store_true", help="Re-download even if cached files are fresh")
    parser.add_argument("--max-age-hours", type=float, default=24.0, help="Skip download if newer than this many hours")
    parser.add_argument("--strict", action="store_true", help="Exit non-zero if any source fails to download")
    args = parser.parse_args()

    os.makedirs(OUT_DIR, exist_ok=True)
    prev_manifest = {}
    if os.path.exists(MANIFEST_PATH):
        try:
            with open(MANIFEST_PATH, "r", encoding="utf-8") as f:
                prev_manifest = json.load(f).get("sources", {})
        except Exception:
            prev_manifest = {}

    manifest_sources = {}
    failures = []
    now_iso = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    for src in SOURCES:
        name = src["name"]
        dest_path = os.path.join(OUT_DIR, src["filename"])
        if not args.force and is_fresh(dest_path, args.max_age_hours):
            cached = dict(prev_manifest.get(name, {}))
            cached.update({
                "file": src["filename"],
                "bytes": os.path.getsize(dest_path),
                "status": "cached_fresh",
            })
            manifest_sources[name] = cached
            print(f"[SKIP fresh] {src['filename']} ({cached['bytes']} bytes)")
            continue

        last_err = None
        downloaded_meta = None
        for url in src["urls"]:
            try:
                print(f"[FETCH] {url} -> {src['filename']} ...")
                downloaded_meta = fetch_url(url, dest_path, allow_gunzip=src["gunzip"])
                break
            except Exception as exc:
                last_err = exc
                print(f"  [WARN] Failed {url}: {exc}", file=sys.stderr)

        if downloaded_meta is not None:
            downloaded_meta.update({
                "file": src["filename"],
                "fetched_at": now_iso,
                "status": "downloaded",
            })
            manifest_sources[name] = downloaded_meta
            print(f"  [OK] {src['filename']} ({downloaded_meta['bytes']} bytes)")
        else:
            failures.append((name, str(last_err)))
            manifest_sources[name] = {
                "file": src["filename"],
                "bytes": os.path.getsize(dest_path) if os.path.exists(dest_path) else 0,
                "status": "failed",
                "error": str(last_err),
            }

    manifest = {
        "generated_at": now_iso,
        "sources": manifest_sources,
    }
    manifest_tmp = MANIFEST_PATH + ".tmp"
    with open(manifest_tmp, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, sort_keys=True)
        f.write("\n")
    os.replace(manifest_tmp, MANIFEST_PATH)

    if failures and args.strict:
        print(f"Strict fetch failed for {len(failures)} source(s): {failures}", file=sys.stderr)
        sys.exit(1)
    if failures:
        print(f"Completed with {len(failures)} warning(s).", file=sys.stderr)


if __name__ == "__main__":
    main()
