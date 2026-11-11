#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Compiles Mozc OSS raw dictionary files (or enriched base TSV) into a
high-performance, zero-heap, memory-mapped binary dictionary: system_dictionary.bin

Format Specification: MYASRDIC v1.0
- Header: 64 bytes (Magic: 0x4D594153 0x52444943 "MYASRDIC")
- Reading Table: N entries * 12 bytes, strictly sorted by UTF-8 bytes for O(log N) binary search
- Token Table: M entries * 12 bytes (surface_offset: u32, surface_len: u16, cost: i16, lid: u16, rid: u16)
- String Pool: Packed UTF-8 strings
"""

import os
import sys
import glob
import struct
import time

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
OUTPUT_BIN = os.path.join(REPO_ROOT, "tools/mozc_dict/system_dictionary.bin")
TEST_RES_BIN = os.path.join(REPO_ROOT, "app/src/test/resources/mozc/system_dictionary.bin")

MAGIC_1 = 0x4D594153  # "MYAS"
MAGIC_2 = 0x52444943  # "RDIC"
VERSION = 1

def load_all_tokens():
    # 1. Load protected symbols and base entries from tools/mozc_dict/base_dictionary.tsv
    base_tsv = os.path.join(REPO_ROOT, "tools/mozc_dict/base_dictionary.tsv")
    reading_to_tokens = {}

    print(f"Reading base dictionary entries from {base_tsv}...")
    if os.path.exists(base_tsv):
        with open(base_tsv, "r", encoding="utf-8") as f:
            for line in f:
                line = line.rstrip("\r\n")
                if not line or line.startswith("#"):
                    continue
                parts = line.split("\t")
                if len(parts) >= 5:
                    r, lid, rid, cost, s = parts[0], int(parts[1]), int(parts[2]), int(parts[3]), parts[4]
                    reading_to_tokens.setdefault(r, []).append((s, cost, lid, rid))

    print(f"Loaded {len(reading_to_tokens)} readings from base_dictionary.tsv")

    # 2. Enrich systematically with Mozc OSS general vocabulary (100% UNPRUNED, ALL TOKENS)
    mozc_dict_dir = os.path.join(REPO_ROOT, "tools/mozc/src/data/dictionary_oss")
    os.makedirs(mozc_dict_dir, exist_ok=True)
    dict_files = sorted(glob.glob(os.path.join(mozc_dict_dir, "dictionary*.txt")))
    if len(dict_files) < 10:
        print(f"Downloading missing Mozc OSS dictionary files into {mozc_dict_dir}...")
        import urllib.request
        for i in range(10):
            fname = f"dictionary{i:02d}.txt"
            fpath = os.path.join(mozc_dict_dir, fname)
            if not os.path.exists(fpath):
                url = f"https://raw.githubusercontent.com/google/mozc/master/src/data/dictionary_oss/{fname}"
                print(f"  Downloading {fname} from {url}...")
                urllib.request.urlretrieve(url, fpath)
        dict_files = sorted(glob.glob(os.path.join(mozc_dict_dir, "dictionary*.txt")))

    # Also download reading_correction.tsv if missing
    corr_file = os.path.join(mozc_dict_dir, "reading_correction.tsv")
    if not os.path.exists(corr_file):
        try:
            url = "https://raw.githubusercontent.com/google/mozc/master/src/data/dictionary_oss/reading_correction.tsv"
            print(f"  Downloading reading_correction.tsv from {url}...")
            import urllib.request
            urllib.request.urlretrieve(url, corr_file)
        except Exception as e:
            print(f"Warning: Failed to download reading_correction.tsv: {e}")

    if dict_files:
        print(f"Enriching with Mozc OSS vocabulary (ALL TOKENS, 100% UNPRUNED) from {len(dict_files)} files...")
        added_count = 0
        existing_keys = set()
        for r, toks in reading_to_tokens.items():
            for s, _, l, ri in toks:
                existing_keys.add((r, s, l, ri))

        for fpath in dict_files:
            with open(fpath, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.rstrip("\r\n")
                    if not line or line.startswith("#"):
                        continue
                    parts = line.split("\t")
                    if len(parts) >= 5:
                        r, lid, rid, cost, s = parts[0], int(parts[1]), int(parts[2]), int(parts[3]), parts[4]
                        key = (r, s, lid, rid)
                        if key not in existing_keys:
                            reading_to_tokens.setdefault(r, []).append((s, cost, lid, rid))
                            existing_keys.add(key)
                            added_count += 1
        print(f"Enriched with {added_count} additional Mozc OSS tokens (total: {sum(len(v) for v in reading_to_tokens.values())}).")

    # Integrate reading_correction.tsv
    if os.path.exists(corr_file):
        print(f"Integrating reading correction entries from {corr_file}...")
        # Map surface to best POS info
        surface_to_pos = {}
        for r, toks in reading_to_tokens.items():
            for s, cost, lid, rid in toks:
                if s not in surface_to_pos or cost < surface_to_pos[s][2]:
                    surface_to_pos[s] = (lid, rid, cost)

        corr_added = 0
        with open(corr_file, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                parts = line.split("\t")
                if len(parts) >= 3:
                    surface, error_reading, _ = parts[0], parts[1], parts[2]
                    pos_info = surface_to_pos.get(surface)
                    if pos_info:
                        lid, rid, base_cost = pos_info
                        corr_cost = min(32767, base_cost + 3000)
                    else:
                        lid, rid, corr_cost = 1851, 1851, 7500
                    key = (error_reading, surface, lid, rid)
                    if key not in existing_keys:
                        reading_to_tokens.setdefault(error_reading, []).append((surface, corr_cost, lid, rid))
                        existing_keys.add(key)
                        corr_added += 1
        print(f"Integrated {corr_added} reading correction entries.")

    return reading_to_tokens

def build_binary(reading_to_tokens, output_path):
    print("Sorting readings lexicographically by UTF-8 bytes...")
    # Sort readings strictly by UTF-8 bytes to guarantee valid binary search with memcmp
    sorted_readings = sorted(reading_to_tokens.keys(), key=lambda x: x.encode("utf-8"))

    # Prepare string pool and token array
    string_pool_bytes = bytearray()
    string_offset_map = {}

    def get_string_offset(s):
        s_bytes = s.encode("utf-8")
        if s_bytes in string_offset_map:
            return string_offset_map[s_bytes], len(s_bytes)
        offset = len(string_pool_bytes)
        string_pool_bytes.extend(s_bytes)
        string_offset_map[s_bytes] = offset
        return offset, len(s_bytes)

    num_readings = len(sorted_readings)
    total_tokens = sum(len(reading_to_tokens[r]) for r in sorted_readings)
    print(f"Compiling {num_readings} readings, {total_tokens} tokens into binary format...")

    reading_records = bytearray()
    token_records = bytearray()

    token_counter = 0

    for r in sorted_readings:
        r_offset, r_len = get_string_offset(r)
        tokens = reading_to_tokens[r]
        # Sort tokens by cost
        tokens.sort(key=lambda x: x[1])
        t_count = len(tokens)
        t_start = token_counter

        # Reading Record: reading_offset(u32), reading_len(u16), token_count(u16), token_start_idx(u32) = 12 bytes
        reading_records.extend(struct.pack("<IHHI", r_offset, r_len, t_count, t_start))

        for s, cost, lid, rid in tokens:
            s_offset, s_len = get_string_offset(s)
            # Token Record: surface_offset(u32), surface_len(u16), cost(i16), lid(u16), rid(u16) = 12 bytes
            token_records.extend(struct.pack("<IhhHH", s_offset, s_len, cost, lid, rid))
            token_counter += 1

    header_size = 64
    reading_table_offset = header_size
    token_table_offset = reading_table_offset + len(reading_records)
    string_pool_offset = token_table_offset + len(token_records)

    header = bytearray(64)
    struct.pack_into(
        "<IIHHIIIIII",
        header, 0,
        MAGIC_1, MAGIC_2, VERSION, 0,
        num_readings, total_tokens,
        reading_table_offset, token_table_offset,
        string_pool_offset, len(string_pool_bytes)
    )

    print(f"Writing {output_path}...")
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, "wb") as f:
        f.write(header)
        f.write(reading_records)
        f.write(token_records)
        f.write(string_pool_bytes)

    size_mb = os.path.getsize(output_path) / (1024 * 1024)
    print(f"Successfully compiled system_dictionary.bin: {size_mb:.2f} MB")

def main():
    start = time.time()
    tokens = load_all_tokens()
    build_binary(tokens, OUTPUT_BIN)
    # Also copy to test resources
    if os.path.exists(os.path.dirname(TEST_RES_BIN)):
        import shutil
        shutil.copyfile(OUTPUT_BIN, TEST_RES_BIN)
        print(f"Copied to test resources: {TEST_RES_BIN}")
    print(f"Build completed in {time.time() - start:.2f} seconds.")

if __name__ == "__main__":
    main()
