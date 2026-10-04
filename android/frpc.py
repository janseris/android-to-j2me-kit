"""Seznam FastRPC (binary, magic CA 11) decoder, versions 1-3. decode(bytes) -> dict.
Used by Seznam apps (mapy.cz backends, e.g. pubtran-backend.mapy.cz). Usage: see flowdump.py --frpc."""
import struct, datetime
class R:
    def __init__(s,d): s.d=d; s.i=0
    def take(s,n): b=s.d[s.i:s.i+n]; s.i+=n; return b
    def u(s,n): return int.from_bytes(s.take(n),'little')
def val(r, major=2):
    b=r.u(1); t=b>>3; inf=b&7
    if t==1:  # INT
        if major>=3:
            z=r.u(inf+1); return (z>>1)^-(z&1)
        return int.from_bytes(r.take(inf+1),'little',signed=True)
    if t==2: return bool(inf&1)
    if t==3: return struct.unpack('<d',r.take(8))[0]
    if t==4: n=r.u(inf+1); return r.take(n).decode('utf-8')
    if t==5:
        zone=r.u(1); ts=int.from_bytes(r.take(8 if major>=3 else 4),'little',signed=True); r.take(5)
        return {'$dt': ts, 'zone': zone, 'iso': datetime.datetime.utcfromtimestamp(ts).isoformat()+'Z'}
    if t==6: n=r.u(inf+1); return {'$bin': r.take(n).hex()}
    if t==7: return r.u(inf+1)
    if t==8: return -r.u(inf+1)
    if t==10:
        n=r.u(inf+1); o={}
        for _ in range(n):
            kl=r.u(1); k=r.take(kl).decode(); o[k]=val(r,major)
        return o
    if t==11:
        n=r.u(inf+1); return [val(r,major) for _ in range(n)]
    if t==12: return None
    if t==13:
        nl=r.u(1); name=r.take(nl).decode(); ps=[]
        while r.i<len(r.d): ps.append(val(r,major))
        return {'$call':name,'params':ps}
    if t==14:
        return {'$response': val(r,major)}
    if t==15:
        code=val(r,major); msg=val(r,major); return {'$fault':code,'msg':msg}
    raise ValueError(f'type {t} at {r.i}')
def decode(data):
    r=R(data); assert r.take(2)==b'\xca\x11', data[:4]
    major=r.u(1); minor=r.u(1)
    vals=[]
    while r.i<len(data): vals.append(val(r,major))
    return {'ver':f'{major}.{minor}','values':vals}
