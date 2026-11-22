#!/usr/bin/env python3
"""Clone extra Arduboy game repos listed in scripts/extra_repos.txt, check each
repo's LICENSE against the permissive allowlist, use a valid prebuilt .hex or
compile with PlatformIO, then append the result to games.json and credits.

Usage: prepare_extra_repos.py <assets_dir> <manifest_file>
Repos that fail any check are skipped and reported; they never break the build.
"""
import json, re, shutil, subprocess, sys, tempfile
from pathlib import Path

ALLOWED = {'MIT', 'Apache-2.0', 'BSD-3-Clause', 'BSD-2-Clause', 'ISC', 'CC0-1.0'}
assets = Path(sys.argv[1]).resolve()
manifest = Path(sys.argv[2]).resolve()
games_dir = assets / 'games'
games_dir.mkdir(parents=True, exist_ok=True)
games_json = assets / 'games.json'
credits = assets / 'THIRD_PARTY_GAMES.txt'

entries = json.loads(games_json.read_text(encoding='utf-8') or '[]')
existing_files = {e['file'] for e in entries}


def valid_ihex(path):
    try:
        saw_data = saw_eof = False
        for line in path.read_text(encoding='ascii').splitlines():
            line = line.strip()
            if not line:
                continue
            if not line.startswith(':'):
                return False
            rec = bytes.fromhex(line[1:])
            if len(rec) < 5 or len(rec) != rec[0] + 5 or (sum(rec) & 0xff) != 0:
                return False
            if rec[3] == 0:
                saw_data = True
            if rec[3] == 1:
                saw_eof = True
        return saw_data and saw_eof
    except Exception:
        return False


def detect_license(repo):
    lic = repo / 'LICENSE'
    if not lic.exists():
        lic = next((p for p in repo.iterdir() if p.name.upper().startswith(('LICENSE', 'LICENCE', 'COPYING'))), None)
    if not lic or not lic.is_file():
        return None
    text = lic.read_text(encoding='utf-8', errors='replace')
    if 'Redistribution and use in source and binary forms' in text:
        return 'BSD-3-Clause' if 'Neither the name' in text else 'BSD-2-Clause'
    if 'Permission is hereby granted, free of charge' in text or re.search(r'^MIT License', text, re.M):
        return 'MIT'
    if re.search(r'Apache License\s+Version 2\.0', text):
        return 'Apache-2.0'
    if re.search(r'ISC License|Permission to use, copy, modify, and/or distribute this software for any', text):
        return 'ISC'
    if 'CC0 1.0 Universal' in text:
        return 'CC0-1.0'
    return None


def find_hex(repo):
    # 1) a valid prebuilt hex shipped in the repo (not a PlatformIO build artifact)
    prebuilt = sorted(p for p in repo.rglob('*.hex')
                      if p.is_file() and '.pio' not in p.parts
                      and 'with_bootloader' not in p.name.lower() and valid_ihex(p))
    if prebuilt:
        return prebuilt[0]
    # 2) compile with PlatformIO
    if not (repo / 'platformio.ini').exists():
        return None
    try:
        subprocess.run(['pio', 'run', '-d', str(repo)], check=True, timeout=900,
                       capture_output=True)
    except Exception as e:
        print(f'  compile failed: {e}')
        return None
    built = sorted(p for p in repo.glob('.pio/build/*/firmware.hex') if valid_ihex(p))
    return built[0] if built else None


def clean_title(repo_name):
    return re.sub(r'[-_]+', ' ', repo_name).strip() or repo_name


urls = [l.strip() for l in manifest.read_text(encoding='utf-8').splitlines()
        if l.strip() and not l.strip().startswith('#')]
added = 0
for url in urls:
    parts = url.rstrip('/').split('/')
    owner, repo_name = parts[-2], parts[-1]
    print(f'== {url}')
    with tempfile.TemporaryDirectory() as td:
        dest = Path(td) / repo_name
        try:
            subprocess.run(['git', 'clone', '--depth', '1', url, str(dest)],
                           check=True, timeout=300, capture_output=True)
        except Exception:
            print('  SKIP: clone failed')
            continue
        lic = detect_license(dest)
        if lic not in ALLOWED:
            print(f'  SKIP: licence {lic or "not detected"} is not allowlisted')
            continue
        hexf = find_hex(dest)
        if not hexf:
            print('  SKIP: no valid .hex (prebuilt or compiled)')
            continue
        safe = re.sub(r'[^A-Za-z0-9_-]+', '_', clean_title(repo_name)).strip('_')[:48] or 'ArduboyGame'
        name = safe + '.hex'
        n = 2
        while name in existing_files or (games_dir / name).exists():
            name = f'{safe}_{n}.hex'
            n += 1
        shutil.copy2(hexf, games_dir / name)

    existing_files.add(name)
    entry = {
        'title': clean_title(repo_name),
        'description': 'Arduboy game from GitHub',
        'author': owner,
        'date': '',
        'license': lic,
        'file': name,
        'source': url,
        'category': 'GitHub',
        'collectionPath': f'{owner}/{repo_name}',
    }
    entries.append(entry)
    with credits.open('a', encoding='utf-8') as f:
        f.write(f"{entry['title']}\nCopyright (c) {owner}\nLicence: {lic}\nSource: {url}\n\n")
    added += 1
    print(f'  ADDED as {name} ({lic})')

games_json.write_text(json.dumps(entries, ensure_ascii=False, indent=2), encoding='utf-8')
print(f'Added {added} extra GitHub games; library now has {len(entries)} games')
