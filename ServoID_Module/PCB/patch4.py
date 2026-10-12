s = open("build.py", encoding="utf-8").read()
# 絲印文字最小 0.8 mm;電池標示放在焊點左邊
s = s.replace('''for nm, x, y in L.MOTOR_PADS:
    silk_text(nm, x, y - 2.3, 0.7)
for nm, x, y in L.BAT_PADS:
    silk_text("BAT" + nm, x, y + 2.0, 0.7)
''', '''for nm, x, y in L.MOTOR_PADS:
    silk_text(nm, x, y - 2.4, 0.8)
for nm, x, y in L.BAT_PADS:
    silk_text("-" if nm == "-" else "+", x - 2.4, y, 1.0)
silk_text("BAT", 11.2, 49.3, 0.8)
''')
# SuperMini 外框只畫左右和下緣(USB 那端不畫,避免壓到焊盤)
s = s.replace('''silk_rect(smfp, SM["x"] - 9.0, SM["y"] + 0.2, SM["x"] + 9.0, SM["y"] + 22.0)''',
'''for a, b in (((SM["x"] - 9.0, SM["y"] + 0.4), (SM["x"] - 9.0, SM["y"] + 22.0)),
             ((SM["x"] - 9.0, SM["y"] + 22.0), (SM["x"] + 9.0, SM["y"] + 22.0)),
             ((SM["x"] + 9.0, SM["y"] + 22.0), (SM["x"] + 9.0, SM["y"] + 0.4))):
    sh = PCB_SHAPE(smfp); sh.SetShape(pcbnew.SHAPE_T_SEGMENT); sh.SetLayer(F_SilkS)
    sh.SetStart(P(*a)); sh.SetEnd(P(*b)); sh.SetWidth(mm(0.15)); smfp.Add(sh)''')
open("build.py", "w", encoding="utf-8").write(s)
print("ok")
