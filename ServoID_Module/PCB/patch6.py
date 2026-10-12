a = open("autoroute.py", encoding="utf-8").read()
i = a.index("def astar(")
j = a.index("tracks, vias = [], []")
new = '''TURN = float(os.environ.get('SERVO_TURN', '0'))     # 轉彎懲罰:越大線越直


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


'''
a = a[:i] + new + a[j:]
open("autoroute.py", "w", encoding="utf-8").write(a)
r = open("run_variants.py", encoding="utf-8").read()
r = r.replace('PARAMS = {"D": {"SERVO_CLR": "0.3", "SERVO_MINW": "0.3"}}', 'PARAMS = {"D": {"SERVO_CLR": "0.3", "SERVO_MINW": "0.3"}, "C": {"SERVO_TURN": "1.5"}}')
open("run_variants.py", "w", encoding="utf-8").write(r)
open("layout_C.py", "w", encoding="utf-8").write("# 版本 C:零件位置和 B 相同,佈線器加轉彎懲罰,線比較直。\\nfrom layout_B import *  # noqa\\n".replace("\\\\n", "\\n"))
print("ok")
