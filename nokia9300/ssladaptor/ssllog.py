#!/usr/bin/env python3
"""Summarise an SSL_LOG (SSLLog.txt) from the patched ssladaptor.dll: one row per TLS connection."""
import re, sys
def t(s):
    h, m, x = s.split(':'); return int(h) * 3600 + int(m) * 60 + float(x)
rows = []; cur = None
for line in open(sys.argv[1], encoding='latin1'):
    m = re.match(r'(\d+:\d+:\d+\.\d+) \[(\w+)\] (.*)', line.rstrip())
    if not m: continue
    ts, th, msg = t(m.group(1)), m.group(2), m.group(3)
    if 'CTlsConnection::NewL' in msg:
        cur = dict(th=th, start=ts, clock=m.group(1), host='?', peer='?', hs=None, kind='', offered=False,
                   sends=0, rx=0, end=None, end_reason='', first_send=None, sid='')
        rows.append(cur); continue
    if cur is None: continue
    if msg.startswith('Set hostname:'): cur['host'] = msg.split(': ', 1)[1]
    elif 'peer ' in msg:
        p = re.search(r'peer ([\d.]+)', msg); cur['peer'] = p.group(1) if p else '?'
    elif msg.startswith('offering saved TLS session'): cur['offered'] = True
    elif msg.startswith('handshake done:'):
        cur['hs'] = ts; cur['kind'] = 'RESUMED' if 'RESUMED' in msg else 'full'
        s = re.search(r'session id (\d+)', msg); cur['sid'] = s.group(1) if s else ''
    elif msg.startswith('CTlsConnection::Send('):
        cur['sends'] += 1
        if cur['first_send'] is None: cur['first_send'] = ts
    elif re.match(r'Recv \d+$', msg): cur['rx'] += int(msg.split()[1])
    elif 'CRecvData::OnCompletion() -' in msg and not cur['end_reason']:
        cur['end_reason'] = msg.split()[-1]
    elif '~CTlsConnection' in msg: cur['end'] = ts
print(f"{'clock':12} {'thr':4} {'host':32} {'handshake':>9} {'type':8} {'offer':5} {'sid':>3} {'reqs':>4} {'rx B':>8} {'total s':>7} {'KB/s':>6} end")
for r in rows:
    if r['host'] == '?' and r['hs'] is None: continue
    hs0 = r['first_send'] if (r['first_send'] and r['first_send'] < (r['hs'] or 1e9) and r['first_send'] - r['start'] > 0.05) else r['start']
    hsd = f"{r['hs'] - hs0:.2f}" if r['hs'] else '-'
    tot = (r['end'] or r['start']) - r['start']
    kbs = ''
    if r['hs'] and r['end'] and r['rx'] > 4096: kbs = f"{r['rx'] / 1024 / max(r['end'] - r['hs'], 0.001):.1f}"
    print(f"{r['clock']:12} {r['th']:4} {r['host'][:32]:32} {hsd:>9} {r['kind']:8} {'yes' if r['offered'] else '':5} {r['sid']:>3} {r['sends']:>4} {r['rx']:>8} {tot:7.1f} {kbs:>6} {r['end_reason']}")
