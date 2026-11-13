"""Builds settings/src/main/assets/tree.json: the original Settings hierarchy, three levels deep."""
import sys, re, json
OUT = sys.argv[3]
# Keys the Settings app itself reports as absent on this device (content://com.android.settings/settings/non_indexables_key).
HIDDEN = set(open(sys.argv[4]).read().split()) if len(sys.argv) > 4 else set()
DROP_TOP = {'top_level_mobile', 'top_level_nfc', 'top_level_support', 'top_level_lenovo_account'}
exec(open('' + __file__.replace('tree.py', 'prefs.py') + '').read().split("if __name__")[0])
MANUAL = {
 'NetworkProviderSettings': 'network_provider_settings', 'BluetoothDashboardFragment': 'bluetooth_screen',
 'TetherSettings': 'tether_prefs', 'AppDashboardFragment': 'apps', 'SystemDashboardFragment': 'system_dashboard_fragment',
 'AboutDeviceFragment': 'about_device', 'PrivacyDashboardFragment': 'privacy_dashboard_settings', 'FapSettingsFragment': 'fap_settings',
 'AdvancedFeaturesSettings': 'advance_features_fragment', 'AccountDashboardFragment': 'accounts_dashboard_settings',
 'PowerUsageSummary': 'power_usage_summary', 'DisplaySettings': 'display_settings', 'SoundSettings': 'sound_settings',
}
def snake(n): return re.sub(r'(?<!^)(?=[A-Z])', '_', n).lower()
def resolve(frag):
    if not frag: return None
    simple = frag.split('.')[-1].split('$')[-1]
    cands = [MANUAL.get(simple), snake(simple)]
    base = snake(simple)
    for suf in ('_fragment', '_dashboard_fragment', '_settings', '_dashboard'):
        if base.endswith(suf): cands += [base[:-len(suf)], base[:-len(suf)] + '_settings', base[:-len(suf)] + '_fragment']
    cands += [base + '_settings', base + '_fragment', 'zui_' + base]
    for c in cands:
        if c and c in xmlfile: return c
    return None
stats = {'screens': 0, 'rows': 0, 'unresolved': set()}
def build(xml, depth, seen):
    items = dump(xml) or []
    out = []; cat = None
    base = min((i['depth'] for i in items), default=0)
    for i in items:
        tag = i['tag']
        if tag in ('PreferenceScreen', 'intent', 'extra') or not i.get('title') and tag != 'PreferenceCategory': continue
        t = i.get('title', '')
        if t.startswith('0x') or t.startswith('@'): t = ''
        if tag.endswith('Category'):
            cat = {'h': t, 'c': []}; out.append(cat); continue
        if not t: continue
        k = i.get('key', '')
        if ',' in k or ' ' in k: k = ''
        if (depth > 0 and k in HIDDEN) or k in DROP_TOP: continue
        n = {'t': t}
        if k: n['k'] = k
        sm = i.get('summary', '')
        if sm and not sm.startswith('0x') and sm.strip() and '%' not in sm: n['s'] = sm
        if 'Switch' in tag: n['w'] = 1
        f = i.get('fragment')
        if f:
            n['f'] = f
            x = resolve(f)
            if x and x not in seen and depth < 3:
                kids = build(x, depth + 1, seen | {x})
                if kids: n['c'] = kids
            elif not x: stats['unresolved'].add(f.split('.')[-1])
        stats['rows'] += 1
        (cat['c'] if cat is not None and i['depth'] > base + 1 else out).append(n)
    stats['screens'] += 1
    return [o for o in out if 'h' not in o or o['c']]
tree = build('top_level_settings', 0, {'top_level_settings'})
json.dump(tree, open(OUT, 'w'), ensure_ascii=False, separators=(',', ':'))
print(stats['screens'], 'screens', stats['rows'], 'rows', len(stats['unresolved']), 'unresolved:', sorted(stats['unresolved'])[:40])
