# 版本 E:C 版 + 電池防反接(P 通道 MOSFET Q1)+ 電池焊點改成 2.54 mm 排針孔距。
# Q1 = AO3401A(SOT-23),串在保險絲之後的正極路徑:汲極(D)接保險絲側、源極(S)接板子側。
# R6/R7 = 100k 分壓:閘極 = VBAT 的一半,滿電 12.6 V 時 Vgs = -6.3 V,不超過 ±12 V 上限。
import layout_A as _A
from layout_B import BOARD_W, BOARD_H, LIB, SUPERMINI, HOLES, MOTOR_PADS, GND_PADS as _G0, PARTS as _P
PARTS = list(_P)
PARTS += [
    ("Q1", "Package_TO_SOT_SMD", "SOT-23", 18.3, 42.1, 180, "AO3401A",
     {"1": "GATE", "2": "VBAT", "3": "BAT_FUSED"}),
    ("R6", "Resistor_SMD", "R_0603_1608Metric", 21.6, 40.6, 90, "100k",
     {"1": "GATE", "2": "VBAT"}),
    ("R7", "Resistor_SMD", "R_0603_1608Metric", 23.4, 40.6, 90, "100k",
     {"1": "GATE", "2": "GND"}),
]
# 保險絲後面改成 BAT_FUSED 網路(F1.2 -> Q1.D)
PARTS = [(r, l, n, x, y, rot, v, (dict(p, **{"2": "BAT_FUSED"}) if r == "F1" else p)) for (r, l, n, x, y, rot, v, p) in PARTS]
BAT_PADS = [("-", 9.0, 45.0), ("+", 11.54, 45.0)]   # 2.54 mm 間距,1.0 mm 孔(排針)
BAT_DRILL, BAT_DIA = 1.0, 2.0
GND_PADS = list(_G0) + ["R7.2"]
CONNECTIONS = [
    ("BAT_RAW", 1.5, "J1.2", "F1.1"),
    ("BAT_FUSED", 1.5, "F1.2", "Q1.3"),
    ("GATE", 0.25, "Q1.1", "R6.1"),
    ("GATE", 0.25, "R6.1", "R7.1"),
    ("VBAT", 1.5, "Q1.2", "J2.2"),
    ("VBAT", 0.5, "Q1.2", "C1.2"),
    ("VBAT", 0.25, "Q1.2", "R6.2"),
] + [c for c in _A.CONNECTIONS if not (c[0] in ("BAT_RAW",) or (c[0] == "VBAT" and c[2] in ("F1.2",)))]
LABEL_BELOW = True
