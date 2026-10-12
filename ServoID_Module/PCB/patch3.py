s = open("build.py", encoding="utf-8").read()
s = s.replace('''for nm, x, y in L.MOTOR_PADS:
    silk_text(nm, x, y - 2.4, 0.8)
for nm, x, y in L.BAT_PADS:
    silk_text("BAT" + nm, x, y - 2.4, 0.8)
silk_text("MOTOR", (L.MOTOR_PADS[0][1] + L.MOTOR_PADS[-1][1]) / 2, L.MOTOR_PADS[0][2] + 2.4, 0.8)
''', '''for nm, x, y in L.MOTOR_PADS:
    silk_text(nm, x, y - 2.3, 0.7)
for nm, x, y in L.BAT_PADS:
    silk_text("BAT" + nm, x, y + 2.0, 0.7)
''')
s = s.replace('silk_rect(smfp, SM["x"] - 9.0, SM["y"] - 0.5, SM["x"] + 9.0, SM["y"] + 22.0)',
              'silk_rect(smfp, SM["x"] - 9.0, SM["y"] + 0.2, SM["x"] + 9.0, SM["y"] + 22.0)')
open("build.py", "w", encoding="utf-8").write(s)
l = open("layout.py", encoding="utf-8").read()
l = l.replace('("D3", "Diode_SMD", "D_SMA", 22.6, 27.0,', '("D3", "Diode_SMD", "D_SMA", 22.6, 26.4,')
l = l.replace('("D1", "Diode_SMD", "D_SOD-123", 20.6, 31.0,', '("D1", "Diode_SMD", "D_SOD-123", 20.6, 31.0,')
l = l.replace('("R1", "Resistor_SMD", "R_0603_1608Metric", 20.6, 34.6,', '("R1", "Resistor_SMD", "R_0603_1608Metric", 20.6, 35.0,')
open("layout.py", "w", encoding="utf-8").write(l)
print("ok")
