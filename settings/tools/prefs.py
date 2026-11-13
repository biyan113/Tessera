"""Dumps preference screens of the pulled Settings APK as an indented outline with zh-CN titles."""
import os, re, shutil, subprocess, sys
from pathlib import Path

def find_aapt2():
    found = shutil.which('aapt2')
    if found:
        return found
    home = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if home:
        tools = sorted(Path(home, 'build-tools').glob('*/aapt2'), reverse=True)
        if tools:
            return str(tools[0])
    sys.exit('aapt2 not found; put it on PATH or set ANDROID_HOME')

AAPT = find_aapt2()
APK = sys.argv[1]; RES = sys.argv[2]
strings = {}; xmlfile = {}
cur = None; kind = None
for line in open(RES, encoding='utf-8', errors='replace'):
    m = re.match(r'\s+resource (0x[0-9a-f]+) (\w+)/(\S+)', line)
    if m:
        cur = m.group(1); kind = m.group(2); name = m.group(3)
        if kind == 'string': strings[cur] = {}
        continue
    if cur is None: continue
    m = re.match(r'\s+\((.*?)\) "(.*)"\s*$', line)
    if m and kind == 'string':
        strings[cur][m.group(1)] = m.group(2)
    m = re.match(r'\s+\(.*?\) \(file\) (\S+) type=XML', line)
    if m and kind == 'xml':
        xmlfile[name] = m.group(1)
def s(ref):
    d = strings.get(ref)
    if not d: return ref
    return d.get('zh-rCN') or d.get('zh') or d.get('') or next(iter(d.values()))
def dump(name):
    f = xmlfile.get(name)
    if not f: return None
    out = subprocess.run([AAPT, 'dump', 'xmltree', '--file', f, APK], capture_output=True, text=True).stdout
    items = []; cur = None
    for line in out.split('\n'):
        m = re.match(r'(\s*)E: (\S+)', line)
        if m:
            cur = {'depth': len(m.group(1)) // 2, 'tag': m.group(2).split('.')[-1]}
            items.append(cur); continue
        m = re.match(r'\s*A: .*?:(\w+)\(0x[0-9a-f]+\)=(.*)', line)
        if m and cur is not None:
            k, v = m.group(1), m.group(2).strip()
            if k in ('title', 'summary', 'key', 'fragment', 'controller', 'action', 'targetPackage', 'targetClass'):
                r = re.match(r'@(0x[0-9a-f]+)', v)
                v = s(r.group(1)) if r else v.split('"')[1] if '"' in v else v
                cur[k] = v
    return items
if __name__ == '__main__':
    for n in sys.argv[3:]:
        it = dump(n)
        print('#', n)
        for i in it or []:
            if i['tag'] in ('intent',) and 'action' not in i and 'targetClass' not in i: continue
            print('  ' * i['depth'] + i['tag'] + ' | ' + ' | '.join(f"{k}={v}" for k, v in i.items() if k not in ('depth', 'tag')))
