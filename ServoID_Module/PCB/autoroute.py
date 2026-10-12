# 小型自動佈線器:兩層、格點 0.1 mm、A* 搜尋、避開其他網路。
# 用法:"C:\Program Files\KiCad\10.0\bin\python.exe" autoroute.py   (先跑 build.py --dump 產生 pads.json)
import json, os, heapq, math, sys
import numpy as np
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
V = os.environ.get('SERVO_VARIANT', 'A')
import importlib
L = importlib.import_module('layout_' + V)

G = 0.1
CLR = float(os.environ.get('SERVO_CLR', '0.2'))   # 不同網路之間的最小間距
MINW = float(os.environ.get('SERVO_MINW', '0'))
VIA_D, VIA_DRILL = 0.6, 0.3
EDGE = 0.5
HOLE_R = 3.0
W, H = L.BOARD_W, L.BOARD_H
NX, NY = int(round(W / G)) + 1, int(round(H / G)) + 1
xs = np.arange(NX) * G
ys = np.arange(NY) * G
XX, YY = np.meshgrid(xs, ys, indexing="ij")     # [ix, iy]

pads = json.load(open(os.path.join(HERE, "pads_%s.json" % V)))
padmap = {p["name"]: p for p in pads}

# 障礙物:dict(kind, net, layers(set of 0/1), geometry)
items = []
for p in pads:
    if p["npth"]:
        continue
    layers = {0, 1} if p["tht"] else {0}
    items.append(dict(kind="rect", net=p["net"], layers=layers, g=(p["x1"], p["y1"], p["x2"], p["y2"])))
for (hx, hy) in L.HOLES:
    items.append(dict(kind="circle", net=None, layers={0, 1}, g=(hx, hy, HOLE_R)))


def mask_of(it, d):
    """傳回 bool 陣列:格點中心距離該物件 < d(物件膨脹 d)"""
    m = np.zeros((NX, NY), bool)
    k = it["kind"]
    if k == "rect":
        x1, y1, x2, y2 = it["g"]
        ix1, ix2 = max(0, int((x1 - d) / G) - 1), min(NX, int((x2 + d) / G) + 2)
        iy1, iy2 = max(0, int((y1 - d) / G) - 1), min(NY, int((y2 + d) / G) + 2)
        sx, sy = XX[ix1:ix2, iy1:iy2], YY[ix1:ix2, iy1:iy2]
        dx = np.maximum(np.maximum(x1 - sx, sx - x2), 0)
        dy = np.maximum(np.maximum(y1 - sy, sy - y2), 0)
        m[ix1:ix2, iy1:iy2] = np.hypot(dx, dy) < d
    elif k == "circle":
        cx, cy, r = it["g"]
        dd = d + r
        ix1, ix2 = max(0, int((cx - dd) / G) - 1), min(NX, int((cx + dd) / G) + 2)
        iy1, iy2 = max(0, int((cy - dd) / G) - 1), min(NY, int((cy + dd) / G) + 2)
        sx, sy = XX[ix1:ix2, iy1:iy2], YY[ix1:ix2, iy1:iy2]
        m[ix1:ix2, iy1:iy2] = np.hypot(sx - cx, sy - cy) < dd
    elif k == "seg":
        x1, y1, x2, y2, w = it["g"]
        dd = d + w / 2
        ix1, ix2 = max(0, int((min(x1, x2) - dd) / G) - 1), min(NX, int((max(x1, x2) + dd) / G) + 2)
        iy1, iy2 = max(0, int((min(y1, y2) - dd) / G) - 1), min(NY, int((max(y1, y2) + dd) / G) + 2)
        sx, sy = XX[ix1:ix2, iy1:iy2], YY[ix1:ix2, iy1:iy2]
        vx, vy = x2 - x1, y2 - y1
        ll = vx * vx + vy * vy
        t = np.zeros_like(sx) if ll == 0 else np.clip(((sx - x1) * vx + (sy - y1) * vy) / ll, 0, 1)
        m[ix1:ix2, iy1:iy2] = np.hypot(sx - (x1 + t * vx), sy - (y1 + t * vy)) < dd
    return m


edge = np.zeros((NX, NY), bool)
e = int(EDGE / G)
edge[:e] = True
edge[-e:] = True
edge[:, :e] = True
edge[:, -e:] = True


def blocked_for(netname, half, layer):
    """某網路、某線寬(半寬 half)在某層不能走的格點"""
    m = edge.copy()
    for it in items:
        if netname and it["net"] == netname:
            continue
        if layer not in it["layers"]:
            continue
        m |= mask_of(it, CLR + half)
    return m


def via_blocked_for(netname):
    m = edge.copy()
    for it in items:
        if netname and it["net"] == netname:
            continue
        m |= mask_of(it, CLR + VIA_D / 2)
    return m


def snap(v):
    return int(round(v / G))


TURN = float(os.environ.get('SERVO_TURN', '0'))     # 轉彎懲罰:越大線越直


def astar(a, b, a_layers, b_layers, blk, vblk):
    sx, sy = snap(a[0]), snap(a[1])
    tx, ty = snap(b[0]), snap(b[1])
    free = {0: set(), 1: set()}
    for lay in a_layers:
        free[lay].add((sx, sy))
    for lay in b_layers:
        free[lay].add((tx, ty))
    dirs = [(1, 0, 1.0), (-1, 0, 1.0), (0, 1, 1.0), (0, -1, 1.0), (1, 1, 1.414), (1, -1, 1.414), (-1, 1, 1.414), (-1, -1, 1.414)]
    openq = []
    best = {}
    for lay in a_layers:
        heapq.heappush(openq, (0.0, 0.0, lay, sx, sy, -1))
        best[(lay, sx, sy, -1)] = 0.0
    prev = {}

    def okcell(l, x, y):
        if x < 0 or y < 0 or x >= NX or y >= NY:
            return False
        return (not blk[l][x, y]) or ((x, y) in free[l])

    n = 0
    while openq:
        f, gc, l, x, y, d = heapq.heappop(openq)
        if gc > best.get((l, x, y, d), 1e18):
            continue
        if x == tx and y == ty and l in b_layers:
            key = (l, x, y, d)
            path = [(l, x, y)]
            while key in prev:
                key = prev[key]
                path.append((key[0], key[1], key[2]))
            return path[::-1]
        n += 1
        if n > 2500000:
            return None
        for di, (dx, dy, c) in enumerate(dirs):
            nx_, ny_ = x + dx, y + dy
            if not okcell(l, nx_, ny_):
                continue
            if dx and dy and not (okcell(l, x + dx, y) and okcell(l, x, y + dy)):
                continue
            cc = gc + c * (1.0 if l == 0 else 1.25)
            if TURN and d != -1 and di != d:
                cc += TURN * (1.0 if (dirs[d][0] * dx + dirs[d][1] * dy) <= 0 else 0.5)
            k = (l, nx_, ny_, di)
            if cc < best.get(k, 1e18):
                best[k] = cc
                prev[k] = (l, x, y, d)
                h = max(abs(nx_ - tx), abs(ny_ - ty)) + 0.414 * min(abs(nx_ - tx), abs(ny_ - ty))
                heapq.heappush(openq, (cc + h, cc, l, nx_, ny_, di))
        ol = 1 - l
        if not vblk[x, y] and okcell(ol, x, y):
            cc = gc + 12.0
            k = (ol, x, y, -1)
            if cc < best.get(k, 1e18):
                best[k] = cc
                prev[k] = (l, x, y, d)
                h = max(abs(x - tx), abs(y - ty))
                heapq.heappush(openq, (cc + h, cc, ol, x, y, -1))
    return None


tracks, vias = [], []


def add_seg(net, layer, w, p, q):
    if abs(p[0] - q[0]) < 1e-9 and abs(p[1] - q[1]) < 1e-9:
        return
    tracks.append(dict(net=net, layer="F" if layer == 0 else "B", w=w, x1=round(p[0], 4), y1=round(p[1], 4), x2=round(q[0], 4), y2=round(q[1], 4)))
    items.append(dict(kind="seg", net=net, layers={layer}, g=(p[0], p[1], q[0], q[1], w)))


def add_via(net, x, y):
    vias.append(dict(net=net, x=round(x, 4), y=round(y, 4)))
    items.append(dict(kind="circle", net=net, layers={0, 1}, g=(x, y, VIA_D / 2)))


failed = []
for net, w, na, nb in L.CONNECTIONS:
    pa, pb = padmap[na], padmap[nb]
    a = (pa["cx"], pa["cy"])
    b = (pb["cx"], pb["cy"])
    al = {0, 1} if pa["tht"] else {0}
    bl = {0, 1} if pb["tht"] else {0}
    half = w / 2
    blk = {0: blocked_for(net, half, 0), 1: blocked_for(net, half, 1)}
    vblk = via_blocked_for(net)
    path = astar(a, b, al, bl, blk, vblk)
    if not path:
        failed.append((net, na, nb))
        print("FAILED", net, na, nb)
        continue
    pts = [(l, x * G, y * G) for l, x, y in path]
    segs = []
    cur_l = pts[0][0]
    cur = [pts[0]]
    for p in pts[1:]:
        if p[0] != cur_l:
            segs.append((cur_l, cur))
            add_via(net, p[1], p[2])
            cur_l = p[0]
            cur = [p]
        else:
            cur.append(p)
    segs.append((cur_l, cur))
    for si, (l, cs) in enumerate(segs):
        poly = [(c[1], c[2]) for c in cs]
        if si == 0:
            poly = [a] + poly
        if si == len(segs) - 1:
            poly = poly + [b]
        simp = [poly[0]]
        for i in range(1, len(poly) - 1):
            v1 = (poly[i][0] - simp[-1][0], poly[i][1] - simp[-1][1])
            v2 = (poly[i + 1][0] - poly[i][0], poly[i + 1][1] - poly[i][1])
            if abs(v1[0] * v2[1] - v1[1] * v2[0]) > 1e-6:
                simp.append(poly[i])
        simp.append(poly[-1])
        for p, q in zip(simp, simp[1:]):
            add_seg(net, l, w, p, q)
    print("ok", net, na, nb, "cells %d" % len(path), "vias %d" % (len(segs) - 1))

# 地線過孔:每個接地腳旁邊找一個空位打孔,連到底層地線面
dirs8 = [(1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (1, -1), (-1, 1), (-1, -1)]
for pn in L.GND_PADS:
    p = padmap[pn]
    cx, cy = p["cx"], p["cy"]
    blk0 = blocked_for("GND", 0.15, 0)
    vb = via_blocked_for("GND")
    done = False
    for dist in (1.0, 1.2, 1.4, 1.7, 2.0, 2.4):
        for dx, dy in dirs8:
            n_ = math.hypot(dx, dy)
            vx, vy = cx + dx / n_ * dist, cy + dy / n_ * dist
            ix, iy = snap(vx), snap(vy)
            if not (0 <= ix < NX and 0 <= iy < NY) or vb[ix, iy]:
                continue
            if any(math.hypot(v["x"] - vx, v["y"] - vy) < 1.0 for v in vias):
                continue
            ok = True
            for t in np.linspace(0, 1, 15):
                jx, jy = snap(cx + (vx - cx) * t), snap(cy + (vy - cy) * t)
                inside = p["x1"] <= jx * G <= p["x2"] and p["y1"] <= jy * G <= p["y2"]
                if blk0[jx, jy] and not inside:
                    ok = False
                    break
            if ok:
                add_seg("GND", 0, 0.3, (cx, cy), (vx, vy))
                add_via("GND", vx, vy)
                done = True
                break
        if done:
            break
    print("gnd via", pn, "ok" if done else "FAILED")
    if not done:
        failed.append(("GND", pn, ""))

json.dump(dict(tracks=tracks, vias=vias), open(os.path.join(HERE, "routes_%s.json" % V), "w"), indent=0)
print("tracks", len(tracks), "vias", len(vias), "failed", failed)
