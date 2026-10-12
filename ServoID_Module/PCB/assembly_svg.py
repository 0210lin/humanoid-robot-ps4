# 產生「零件編號與方向」組裝圖(SVG)。用 KiCad 的 Python 執行:  python assembly_svg.py C
import os, sys
import pcbnew
HERE = os.path.dirname(os.path.abspath(__file__))
v = sys.argv[1]
b = pcbnew.LoadBoard(os.path.join(HERE, "ServoID_%s.kicad_pcb" % v))
S = 30.0                     # 1 mm = 30 px
M = 40                       # 邊界 px
bb = b.GetBoardEdgesBoundingBox()
W, H = bb.GetWidth() / 1e6, bb.GetHeight() / 1e6
ox, oy = bb.GetX() / 1e6, bb.GetY() / 1e6
def X(mm): return M + (mm - ox) * S
def Y(mm): return M + 70 + (mm - oy) * S
out = []
out.append('<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" font-family="Microsoft JhengHei, Arial">' % (W * S + 2 * M, H * S + 2 * M + 70 + 150))
out.append('<rect width="100%" height="100%" fill="white"/>')
out.append('<text x="%d" y="34" font-size="22" font-weight="bold">ServoID 電路板 %s 版  組裝圖(正面,零件編號與方向)</text>' % (M, v))
out.append('<text x="%d" y="58" font-size="14" fill="#555">板子 %.1f x %.1f mm,單面貼片。紅色粗線 = 二極體陰極(有橫線端);紅點 = U1 的 1 腳</text>' % (M, W - 0.1, H - 0.1))
out.append('<rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" fill="#e9f3e9" stroke="black" stroke-width="2"/>' % (X(ox), Y(oy), W * S, H * S))
for fp in b.GetFootprints():
    ref = fp.GetReference()
    pads = list(fp.Pads())
    if ref.startswith("H"):
        p = pads[0].GetPosition() if pads else fp.GetPosition()
        c = fp.GetPosition()
        out.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="white" stroke="#444" stroke-width="1.5"/>' % (X(c.x / 1e6), Y(c.y / 1e6), 1.6 * S))
        out.append('<text x="%.1f" y="%.1f" font-size="11" fill="#444" text-anchor="middle">%s</text>' % (X(c.x / 1e6), Y(c.y / 1e6) + 4, ref))
        continue
    smd = bool(fp.GetAttributes() & pcbnew.FP_SMD)
    cx = sum(p.GetPosition().x for p in pads) / len(pads) / 1e6
    cy = sum(p.GetPosition().y for p in pads) / len(pads) / 1e6
    minx = min(p.GetBoundingBox().GetLeft() for p in pads) / 1e6; maxx = max(p.GetBoundingBox().GetRight() for p in pads) / 1e6
    miny = min(p.GetBoundingBox().GetTop() for p in pads) / 1e6; maxy = max(p.GetBoundingBox().GetBottom() for p in pads) / 1e6
    if smd:
        # 零件外框(用焊盤範圍)
        for p in pads:
            bp = p.GetBoundingBox()
            x1, y1, x2, y2 = bp.GetLeft() / 1e6, bp.GetTop() / 1e6, bp.GetRight() / 1e6, bp.GetBottom() / 1e6
            out.append('<rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" fill="#c9a227" stroke="#7a5f00" stroke-width="0.8"/>' % (X(x1), Y(y1), (x2 - x1) * S, (y2 - y1) * S))
        out.append('<rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" fill="none" stroke="#222" stroke-width="1.2"/>' % (X(minx), Y(miny), (maxx - minx) * S, (maxy - miny) * S))
        # 極性標記
        if ref in ("D1", "D2", "D3"):
            k = [p for p in pads if p.GetNumber() == "1"][0]
            bp = k.GetBoundingBox()
            x1, y1, x2, y2 = bp.GetLeft() / 1e6, bp.GetTop() / 1e6, bp.GetRight() / 1e6, bp.GetBottom() / 1e6
            kx, ky = k.GetPosition().x / 1e6, k.GetPosition().y / 1e6
            horiz = (maxx - minx) > (maxy - miny)
            if horiz:
                xs = X(x2) if kx < cx else X(x1)
                out.append('<line x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f" stroke="red" stroke-width="5"/>' % (xs, Y(y1), xs, Y(y2)))
            else:
                ys = Y(y2) if ky < cy else Y(y1)
                out.append('<line x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f" stroke="red" stroke-width="5"/>' % (X(x1), ys, X(x2), ys))
        if ref == "U1":
            p1 = [p for p in pads if p.GetNumber() == "1"][0]
            out.append('<circle cx="%.1f" cy="%.1f" r="5" fill="red"/>' % (X(p1.GetPosition().x / 1e6 - 0.9), Y(p1.GetPosition().y / 1e6)))
        label = ref
        ty = miny - 0.25
        out.append('<text x="%.1f" y="%.1f" font-size="13" font-weight="bold" text-anchor="middle" fill="#00258a">%s %s</text>' % (X(cx), Y(ty), label, fp.GetValue()))
    else:
        for p in pads:
            c = p.GetPosition()
            r = p.GetSizeX() / 2e6
            out.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="#ddd" stroke="#777" stroke-width="1"/>' % (X(c.x / 1e6), Y(c.y / 1e6), r * S))
        if ref == "U2":
            out.append('<text x="%.1f" y="%.1f" font-size="15" text-anchor="middle" fill="#a30">U2 SuperMini(不用貼,自己焊)</text>' % (X(cx), Y(cy + 3)))
        else:
            out.append('<text x="%.1f" y="%.1f" font-size="12" text-anchor="middle" fill="#a30">%s(自己焊)</text>' % (X(cx), Y(miny - 0.5), ref))
out.append('<text x="%d" y="%.1f" font-size="14" fill="#333">要貼的 SMD 零件:C1 C2 C3 C4 D1 D2 D3 F1 L1 R1 R2 R3 R4 R5 U1(共 15 顆)。</text>' % (M, Y(oy) + H * S + 30))
out.append('<text x="%d" y="%.1f" font-size="14" fill="#333">不用貼:U2(SuperMini)、J1(電池焊點)、J2(馬達焊點)、H1~H4(M3 鎖孔)。</text>' % (M, Y(oy) + H * S + 54))
out.append('<text x="%d" y="%.1f" font-size="14" fill="#333">二極體 D1/D2/D3:紅色粗線那一端是陰極(有橫線端)。U1:紅點旁是 1 腳。</text>' % (M, Y(oy) + H * S + 78))
out.append('</svg>')
open(os.path.join(HERE, "assembly_%s.svg" % v), "w", encoding="utf-8").write("\n".join(out))
print("ok")
