import re
# ---- build.py ----
s = open("build.py", encoding="utf-8").read()
s = s.replace('''silk_text("ServoID", 15.5, 24.0, 1.0)
''', '''# 標示:馬達三個焊點、電池兩個焊點、標題
for nm, x, y in L.MOTOR_PADS:
    silk_text(nm, x, y - 2.4, 0.8)
for nm, x, y in L.BAT_PADS:
    silk_text("BAT" + nm, x, y - 2.4, 0.8)
silk_text("MOTOR", (L.MOTOR_PADS[0][1] + L.MOTOR_PADS[-1][1]) / 2, L.MOTOR_PADS[0][2] + 2.4, 0.8)
silk_text("ServoID  2S/3S", 15.5, 23.6, 0.9)
silk_text("USB", 15.5, 1.5, 0.8)
for fp in board.GetFootprints():          # 零件編號太擠,先全部隱藏(位置和料號看 BOM / CPL)
    fp.Reference().SetVisible(False)
    fp.Value().SetVisible(False)
ds = board.GetDesignSettings()
ds.m_CopperEdgeClearance = mm(0.3)
''')
open("build.py", "w", encoding="utf-8").write(s)

# ---- autoroute.py:接地過孔不要和其他過孔重疊 ----
a = open("autoroute.py", encoding="utf-8").read()
a = a.replace('''            if not (0 <= ix < NX and 0 <= iy < NY) or vb[ix, iy]:
                continue
            ok = True''', '''            if not (0 <= ix < NX and 0 <= iy < NY) or vb[ix, iy]:
                continue
            if any(math.hypot(v["x"] - vx, v["y"] - vy) < 1.0 for v in vias):
                continue
            ok = True''')
open("autoroute.py", "w", encoding="utf-8").write(a)
print("patched")
