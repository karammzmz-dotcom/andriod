#!/usr/bin/env python3
import json, re, shutil, sys
from pathlib import Path
src=Path(sys.argv[1]).resolve(); assets=Path(sys.argv[2]).resolve(); games_dir=assets/'games'; games_dir.mkdir(parents=True,exist_ok=True)
for old in games_dir.iterdir():
    if old.is_file(): old.unlink()
allowed={'MIT','Apache-2.0','BSD-3-Clause','BSD-2-Clause','ISC','CC0-1.0'}
def valid_ihex(path):
    try:
        saw_data=False; saw_eof=False
        for line in path.read_text(encoding='ascii').splitlines():
            line=line.strip()
            if not line: continue
            if not line.startswith(':'): return False
            record=bytes.fromhex(line[1:])
            if len(record)<5 or len(record)!=record[0]+5 or (sum(record)&0xff)!=0: return False
            if record[3]==0: saw_data=True
            if record[3]==1: saw_eof=True
        return saw_data and saw_eof
    except Exception:
        return False
priority=['Arcade','Platformer','Puzzle','RPG','Racing','Shooter','Sports','Action']
entries=[]
for category in priority:
    ini_files=sorted((src/category).rglob('game.ini')) if (src/category).exists() else []
    for ini in ini_files:
        raw=ini.read_text(encoding='utf-8',errors='replace'); values={}
        for line in raw.splitlines():
            if '=' in line and not line.lstrip().startswith(('#',';')):
                k,v=line.split('=',1); values[k.strip().lower()]=v.strip().strip('"')
        license_id=values.get('license','')
        if license_id not in allowed: continue
        hex_files=sorted(p for p in ini.parent.rglob('*.hex') if p.is_file() and 'with_bootloader' not in p.name.lower() and valid_ihex(p))
        if not hex_files: continue
        title=values.get('title',ini.parent.name)
        if title.lower().startswith('catacombs of the damned'): continue
        safe=re.sub(r'[^A-Za-z0-9_-]+','_',title).strip('_')[:48] or 'ArduboyGame'
        name=safe+'.hex'; suffix=2
        while (games_dir/name).exists(): name=f'{safe}_{suffix}.hex';suffix+=1
        shutil.copy2(hex_files[0],games_dir/name)
        source_url=''
        for urlfile in (ini.parent/'source.url',ini.parent/'game.url',ini.parent/'website.url'):
            if urlfile.exists():
                m=re.search(r'URL=(.+)',urlfile.read_text(errors='ignore'))
                if m: source_url=m.group(1).strip(); break
        entries.append({'title':title,'description':values.get('description','Arduboy game'),'author':values.get('author','Unknown'),'date':values.get('date',''),'license':license_id,'file':name,'source':source_url,'category':category,'collectionPath':str(ini.parent.relative_to(src))})
        if len(entries)>=40: break
    if len(entries)>=40: break
(assets/'games.json').write_text(json.dumps(entries,ensure_ascii=False,indent=2),encoding='utf-8')
with (assets/'THIRD_PARTY_GAMES.txt').open('w',encoding='utf-8') as f:
    f.write('Offline Arduboy ROM credits and licence notices\n\n')
    for g in entries:
        year=g.get('date','')[:4] or 'year not stated'
        f.write(f"{g['title']}\nCopyright (c) {year} {g['author']}\nLicence: {g['license']}\nCollection path: {g['collectionPath']}\nSource: {g.get('source') or 'https://github.com/eried/ArduboyCollection/tree/master/'+g['collectionPath']}\n\n")
print(f'Prepared {len(entries)} offline Arduboy games')
if len(entries)<5: raise SystemExit('Not enough explicitly licensed games found; refusing to build an empty offline library.')
