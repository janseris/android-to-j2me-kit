#!/usr/bin/env python3
"""
Dump a mitmproxy capture (.flow) without installing mitmproxy.

  python flowdump.py capture.flow                 # one line per request
  python flowdump.py capture.flow -v              # + headers and text bodies
  python flowdump.py capture.flow --out bodies/   # save every request/response body to files
  python flowdump.py capture.flow --frpc          # decode Seznam FastRPC bodies (frpc.py) as JSON

Passwords, tokens, codes and cookies are masked in the output (--show-secrets to see them).

A .flow file is a sequence of tnetstrings (mitmproxy's own format). This reader handles
the fields needed for HTTP flows; bodies are stored decompressed only if mitmproxy did it,
so gzip/br bodies are decoded here when Content-Encoding says so.
"""
import sys, os, re, json, gzip, zlib, argparse

# Secrets are masked in the printed output unless --show-secrets is given (saved --out bodies are raw).
SECRET_HEADERS = ('authorization', 'cookie', 'set-cookie', 'x-csrf-token', 'x-seznam-token')
SECRET_KEYS = re.compile(r'(pass(word|wd)?|heslo|secret|token|code_verifier|refresh|session|sid|otp|pin|(^|[^a-z])code$)', re.I)

def mask(v):
    v = str(v)
    return v if len(v) <= 8 else v[:4] + '...(%d chars)' % len(v)

def redact_text(t):
    # form bodies and query strings: key=value&...
    t = re.sub(r'([?&]|^)([^=&\s]+)=([^&\s]*)',
               lambda m: m.group(1) + m.group(2) + '=' + (mask(m.group(3)) if SECRET_KEYS.search(m.group(2)) else m.group(3)), t)
    # JSON: "key": "value"
    return re.sub(r'"([^"]+)"\s*:\s*"([^"]*)"',
                  lambda m: '"%s": "%s"' % (m.group(1), mask(m.group(2)) if SECRET_KEYS.search(m.group(1)) else m.group(2)), t)

def redact_obj(o):
    if isinstance(o, dict):
        return {k: (mask(v) if isinstance(v, str) and SECRET_KEYS.search(k) else redact_obj(v)) for k, v in o.items()}
    if isinstance(o, list):
        return [redact_obj(x) for x in o]
    return o

def tns_load(f):
    """Read one tnetstring from file f; returns None at EOF."""
    size = b''
    while True:
        c = f.read(1)
        if not c:
            if size: raise ValueError('truncated length')
            return None
        if c == b':': break
        size += c
    data = f.read(int(size))
    tag = f.read(1)
    return tns_parse(data, tag)

def tns_parse(data, tag):
    if tag == b',': return data                      # bytes
    if tag == b';': return data.decode('utf-8', 'replace')  # str
    if tag == b'#': return int(data)
    if tag == b'^': return float(data)
    if tag == b'!': return data == b'true'
    if tag == b'~': return None
    if tag in (b']', b'}'):
        items, i = [], 0
        while i < len(data):
            j = data.index(b':', i)
            n = int(data[i:j])
            items.append(tns_parse(data[j + 1:j + 1 + n], data[j + 1 + n:j + 2 + n]))
            i = j + 2 + n
        if tag == b']': return items
        return {(k.decode() if isinstance(k, bytes) else k): v for k, v in zip(items[::2], items[1::2])}
    raise ValueError('bad tnetstring tag %r' % tag)

def s(x):
    return x.decode('utf-8', 'replace') if isinstance(x, bytes) else str(x)

def header(headers, name):
    for k, v in headers or []:
        if s(k).lower() == name: return s(v)
    return ''

def body(msg):
    b = msg.get('content') or b''
    enc = header(msg.get('headers'), 'content-encoding').lower()
    try:
        if enc == 'gzip': b = gzip.decompress(b)
        elif enc == 'deflate': b = zlib.decompress(b)
        elif enc == 'br':
            import brotli; b = brotli.decompress(b)
    except Exception:
        pass
    return b

def is_text(b, ctype):
    if any(t in ctype for t in ('json', 'text', 'xml', 'javascript', 'x-www-form')): return True
    try: b.decode('utf-8'); return len(b) > 0
    except UnicodeDecodeError: return False

def flows(path):
    with open(path, 'rb') as f:
        while True:
            d = tns_load(f)
            if d is None: return
            if d.get('type') == 'http': yield d

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('flow')
    ap.add_argument('-v', action='store_true', help='headers and text bodies')
    ap.add_argument('--out', help='folder for raw bodies (NNN_req.bin / NNN_resp.bin)')
    ap.add_argument('--frpc', action='store_true', help='decode FastRPC bodies (CA 11 magic) with frpc.py')
    ap.add_argument('--show-secrets', action='store_true', help="don't mask passwords, tokens and cookies")
    a = ap.parse_args()
    if a.frpc:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import frpc
    if a.out: os.makedirs(a.out, exist_ok=True)
    for n, d in enumerate(flows(a.flow), 1):
        rq, rs = d.get('request') or {}, d.get('response') or {}
        url = '%s://%s%s' % (s(rq.get('scheme', 'https')), s(rq.get('host', '')), s(rq.get('path', '')))
        qb, sb = body(rq), body(rs) if rs else b''
        status = rs.get('status_code', '-') if rs else '-'
        shown_url = url if a.show_secrets else redact_text(url)
        print('%03d %s %s -> %s  (%d B / %d B)' % (n, s(rq.get('method', '')), shown_url, status, len(qb), len(sb)))
        if a.out:
            open(os.path.join(a.out, '%03d_req.bin' % n), 'wb').write(qb)
            open(os.path.join(a.out, '%03d_resp.bin' % n), 'wb').write(sb)
        for label, msg, b in (('request', rq, qb), ('response', rs, sb)):
            if not msg: continue
            if a.v:
                print('  --- %s headers' % label)
                for k, v in msg.get('headers') or []:
                    v = s(v)
                    if not a.show_secrets:
                        v = mask(v) if s(k).lower() in SECRET_HEADERS else redact_text(v) if s(k).lower() == 'location' else v
                    print('    %s: %s' % (s(k), v))
            if a.frpc and b[:2] == b'\xca\x11':
                print('  --- %s (FastRPC)' % label)
                o = frpc.decode(b)
                if not a.show_secrets: o = redact_obj(o)
                print('    ' + json.dumps(o, ensure_ascii=False, indent=1).replace('\n', '\n    '))
            elif a.v and b and is_text(b, header(msg.get('headers'), 'content-type')):
                print('  --- %s body' % label)
                t = s(b)[:4000]
                if not a.show_secrets: t = redact_text(t)
                print('    ' + t.replace('\n', '\n    '))

if __name__ == '__main__':
    main()
