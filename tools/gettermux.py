#!/usr/bin/env python3
"""Download Termux aarch64 packages + deps, extract into a staging tree."""
import os, re, subprocess, sys, tarfile, io, lzma

ROOT = os.path.dirname(os.path.abspath(__file__))
PKGS = os.path.join(ROOT, 'pkgs.txt')
DEBDIR = os.path.join(ROOT, 'debs')
OUT = os.path.join(ROOT, 'termux-root')
BASE = "https://packages.termux.dev/apt/termux-main"
os.makedirs(DEBDIR, exist_ok=True)
os.makedirs(OUT, exist_ok=True)

def parse():
    db = {}
    cur = None
    for line in open(PKGS, encoding='utf-8', errors='replace'):
        line = line.rstrip('\n')
        if not line:
            cur = None
            continue
        if line.startswith(' '):
            continue
        k, _, v = line.partition(': ')
        if k == 'Package':
            cur = {'name': v, 'deps': []}
            db[v] = cur
        elif cur is not None:
            if k == 'Filename':
                cur['file'] = v
            elif k == 'Depends':
                cur['deps'] = [d.split('|')[0].strip() for d in re.split(r',\s*', v) if d.strip()]
    return db

db = parse()

def resolve(names):
    seen, order, stack = set(), [], list(names)
    while stack:
        n = stack.pop(0)
        n = re.split(r'[ (]', n)[0]
        if n in seen or n not in db:
            if n not in db:
                print("WARN missing pkg", n)
            continue
        seen.add(n)
        order.append(n)
        stack.extend(db[n].get('deps', []))
    return order

names = resolve(sys.argv[1:])
print("packages:", len(names))
for n in names:
    f = db[n]['file']
    path = os.path.join(DEBDIR, os.path.basename(f))
    size = int(db[n].get('Size', '0') or 0)
    if not os.path.exists(path) or (size and os.path.getsize(path) != size):
        print("get", n)
        subprocess.run(['curl', '-sSL', '-o', path, f"{BASE}/{f}"], check=True)
    # extract data.tar.*
    listing = subprocess.run(['ar', 't', path], capture_output=True, check=True).stdout.decode().split()
    member = [x for x in listing if x.startswith('data.tar')][0]
    data = subprocess.run(['ar', 'p', path, member], capture_output=True, check=True).stdout
    tf = tarfile.open(fileobj=io.BytesIO(data), mode='r:*')
    for m in tf.getmembers():
        # strip data/data/com.termux/files/usr prefix
        name = m.name
        while name.startswith('./'):
            name = name[2:]
        p = 'data/data/com.termux/files/usr/'
        if not name.startswith(p):
            continue
        rel = name[len(p):]
        dest = os.path.join(OUT, rel)
        if m.isdir():
            os.makedirs(dest, exist_ok=True)
        elif m.issym() or m.islnk():
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            if os.path.lexists(dest):
                os.unlink(dest)
            os.symlink(m.linkname, dest)
        elif m.isfile():
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, 'wb') as fh:
                fh.write(tf.extractfile(m).read())
            os.chmod(dest, m.mode)
print("done ->", OUT)
