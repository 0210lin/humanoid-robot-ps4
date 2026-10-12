# 用 KiCad 內建 Python 產生 ServoID_Module.kicad_pcb
# 執行:"C:\Program Files\KiCad\10.0\bin\python.exe" build.py
import os, sys, math
import pcbnew
from pcbnew import (BOARD, FOOTPRINT, PAD, PCB_TRACK, PCB_VIA, PCB_SHAPE, VECTOR2I, FromMM, ToMM,
                    F_Cu, B_Cu, Edge_Cuts, F_SilkS, NETINFO_ITEM)
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
V = os.environ.get('SERVO_VARIANT', 'A')
import importlib
L = importlib.import_module('layout_' + V)
import json

mm = FromMM
def P(x, y): return VECTOR2I(mm(x), mm(y))

board = BOARD()
nets = {}
def net(name):
    if name not in nets:
        n = NETINFO_ITEM(board, name); board.Add(n); nets[name] = n
    return nets[name]
net("GND")

padpos = {}   # "REF.pad" -> (x, y)

def register(fp, ref):
    for p in fp.Pads():
        c = p.GetPosition(); padpos["%s.%s" % (ref, p.GetNumber())] = (ToMM(c.x), ToMM(c.y))

# ---- 標準零件 ----
for ref, lib, name, x, y, rot, val, pins in L.PARTS:
    fp = pcbnew.FootprintLoad(os.path.join(L.LIB, lib + ".pretty"), name)
    fp.SetReference(ref); fp.SetValue(val)
    fp.SetPosition(P(x, y)); fp.SetOrientationDegrees(rot)
    for p in fp.Pads():
        n = p.GetNumber()
        if n in pins: p.SetNet(net(pins[n]))
    board.Add(fp); register(fp, ref)

# ---- 自製:通孔焊點 ----
def tht_pad(fp, number, x, y, drill, dia, netname=None, shape=pcbnew.PAD_SHAPE_CIRCLE):
    p = PAD(fp); p.SetNumber(number); p.SetShape(shape)
    p.SetAttribute(pcbnew.PAD_ATTRIB_PTH); p.SetLayerSet(p.PTHMask())
    p.SetDrillSize(VECTOR2I(mm(drill), mm(drill))); p.SetSize(VECTOR2I(mm(dia), mm(dia)))
    p.SetPosition(P(x, y))
    if netname: p.SetNet(net(netname))
    fp.Add(p); return p

def silk_rect(fp, x1, y1, x2, y2, w=0.15):
    for a, b in (((x1, y1), (x2, y1)), ((x2, y1), (x2, y2)), ((x2, y2), (x1, y2)), ((x1, y2), (x1, y1))):
        s = PCB_SHAPE(fp); s.SetShape(pcbnew.SHAPE_T_SEGMENT); s.SetLayer(F_SilkS)
        s.SetStart(P(*a)); s.SetEnd(P(*b)); s.SetWidth(mm(w)); fp.Add(s)

def silk_text(text, x, y, size=1.0, layer=F_SilkS, rot=0):
    t = pcbnew.PCB_TEXT(board); t.SetText(text); t.SetPosition(P(x, y)); t.SetLayer(layer)
    t.SetTextSize(VECTOR2I(mm(size), mm(size))); t.SetTextThickness(mm(size * 0.15))
    t.SetTextAngleDegrees(rot); board.Add(t)

# SuperMini:左排 L1..L8 = GPIO5,6,7,8,9,10,20,21;右排 R1..R8 = 5V,GND,3V3,4,3,2,1,0
SM = L.SUPERMINI
smfp = FOOTPRINT(board); smfp.SetReference(SM["ref"]); smfp.SetValue(SM["value"])
smfp.SetPosition(P(0, 0))
left_names = ["GPIO5", "GPIO6", "GPIO7", "GPIO8", "GPIO9", "GPIO10", "GPIO20", "GPIO21"]
right_names = ["5V", "GND", "3V3", "GPIO4", "GPIO3", "GPIO2", "GPIO1", "GPIO0"]
sm_nets = {"R1": "V5S", "R2": "GND", "L3": "TX", "L2": "RX", "R5": "VSENSE"}
PITCH = 2.54; XL = SM["x"] - 7.62; XR = SM["x"] + 7.62; Y0 = SM["y"] + 1.0
for i in range(8):
    tht_pad(smfp, "L%d" % (i + 1), XL, Y0 + i * PITCH, 1.0, 1.7, sm_nets.get("L%d" % (i + 1)))
    tht_pad(smfp, "R%d" % (i + 1), XR, Y0 + i * PITCH, 1.0, 1.7, sm_nets.get("R%d" % (i + 1)))
for a, b in (((SM["x"] - 9.0, SM["y"] + 0.4), (SM["x"] - 9.0, SM["y"] + 22.0)),
             ((SM["x"] - 9.0, SM["y"] + 22.0), (SM["x"] + 9.0, SM["y"] + 22.0)),
             ((SM["x"] + 9.0, SM["y"] + 22.0), (SM["x"] + 9.0, SM["y"] + 0.4))):
    sh = PCB_SHAPE(smfp); sh.SetShape(pcbnew.SHAPE_T_SEGMENT); sh.SetLayer(F_SilkS)
    sh.SetStart(P(*a)); sh.SetEnd(P(*b)); sh.SetWidth(mm(0.15)); smfp.Add(sh)
board.Add(smfp); register(smfp, SM["ref"])

# 電池焊點(2)與馬達焊點(3)
batfp = FOOTPRINT(board); batfp.SetReference("J1"); batfp.SetValue("BATTERY")
batfp.SetPosition(P(0, 0))
tht_pad(batfp, "1", L.BAT_PADS[0][1], L.BAT_PADS[0][2], 1.6, 3.0, "GND")
tht_pad(batfp, "2", L.BAT_PADS[1][1], L.BAT_PADS[1][2], 1.6, 3.0, "BAT_RAW")
board.Add(batfp); register(batfp, "J1")
motfp = FOOTPRINT(board); motfp.SetReference("J2"); motfp.SetValue("MOTOR")
motfp.SetPosition(P(0, 0))
for i, (nm, x, y) in enumerate(L.MOTOR_PADS):
    tht_pad(motfp, str(i + 1), x, y, 1.0, 1.9, {"GND": "GND", "PWR": "VBAT", "SIG": "SIG"}[nm])
board.Add(motfp); register(motfp, "J2")

# 鎖孔
for i, (x, y) in enumerate(L.HOLES):
    h = pcbnew.FootprintLoad(os.path.join(L.LIB, "MountingHole.pretty"), "MountingHole_3.2mm_M3")
    h.SetReference("H%d" % (i + 1)); h.SetValue(""); h.SetPosition(P(x, y)); board.Add(h)

# 板框
def edge(a, b):
    s = PCB_SHAPE(board); s.SetShape(pcbnew.SHAPE_T_SEGMENT); s.SetLayer(Edge_Cuts)
    s.SetStart(P(*a)); s.SetEnd(P(*b)); s.SetWidth(mm(0.1)); board.Add(s)
W, H = L.BOARD_W, L.BOARD_H
edge((0, 0), (W, 0)); edge((W, 0), (W, H)); edge((W, H), (0, H)); edge((0, H), (0, 0))

# ---- 走線 ----
def track(a, b, layer, width, netname):
    t = PCB_TRACK(board); t.SetStart(P(*a)); t.SetEnd(P(*b)); t.SetWidth(mm(width))
    t.SetLayer(layer); t.SetNet(net(netname)); board.Add(t)
def via(x, y, netname, dia=0.6, drill=0.3):
    v = PCB_VIA(board); v.SetPosition(P(x, y)); v.SetWidth(mm(dia)); v.SetDrill(mm(drill))
    v.SetNet(net(netname)); v.SetViaType(pcbnew.VIATYPE_THROUGH)
    v.SetLayerPair(F_Cu, B_Cu); board.Add(v)

# 傾印所有焊盤(給佈線器用)
if "--dump" in sys.argv:
    out = []
    for fp in board.GetFootprints():
        for p in fp.Pads():
            bb = p.GetBoundingBox()
            out.append(dict(name="%s.%s" % (fp.GetReference(), p.GetNumber()), net=p.GetNetname(),
                            x1=ToMM(bb.GetLeft()), y1=ToMM(bb.GetTop()), x2=ToMM(bb.GetRight()), y2=ToMM(bb.GetBottom()),
                            cx=ToMM(p.GetPosition().x), cy=ToMM(p.GetPosition().y),
                            tht=bool(p.GetAttribute() in (pcbnew.PAD_ATTRIB_PTH, pcbnew.PAD_ATTRIB_NPTH)),
                            npth=bool(p.GetAttribute() == pcbnew.PAD_ATTRIB_NPTH)))
    json.dump(out, open(os.path.join(HERE, "pads_%s.json" % V), "w"), indent=1)
    print("dumped", len(out), "pads")
    sys.exit(0)

rj = os.path.join(HERE, "routes_%s.json" % V)
if os.path.exists(rj) and "--noroute" not in sys.argv:
    R = json.load(open(rj))
    for t in R["tracks"]:
        track((t["x1"], t["y1"]), (t["x2"], t["y2"]), F_Cu if t["layer"] == "F" else B_Cu, t["w"], t["net"])
    for v in R["vias"]:
        via(v["x"], v["y"], v["net"])

# ---- 地線面(底層)----
def gnd_zone(layer):
    z = pcbnew.ZONE(board); z.SetLayer(layer); z.SetNet(net("GND"))
    ol = z.Outline(); ol.NewOutline()
    for x, y in ((0.3, 0.3), (W - 0.3, 0.3), (W - 0.3, H - 0.3), (0.3, H - 0.3)):
        ol.Append(mm(x), mm(y))
    z.SetMinThickness(mm(0.2)); z.SetPadConnection(pcbnew.ZONE_CONNECTION_THERMAL)
    z.SetThermalReliefGap(mm(0.3)); z.SetThermalReliefSpokeWidth(mm(0.4))
    board.Add(z); return z
gnd_zone(B_Cu)
pcbnew.ZONE_FILLER(board).Fill(board.Zones())

# 標示:馬達三個焊點、電池兩個焊點、標題
for nm, x, y in L.MOTOR_PADS:
    silk_text(nm, x, y - 3.3, 0.8, rot=90)
for nm, x, y in L.BAT_PADS:
    silk_text("-" if nm == "-" else "+", x - 2.4, y, 1.0)
silk_text("ServoID 2S/3S", 11.0, 23.4, 0.9)
silk_text("USB", 15.5, 1.5, 0.8)
for fp in board.GetFootprints():          # 零件編號太擠,先全部隱藏(位置和料號看 BOM / CPL)
    fp.Reference().SetVisible(False)
    fp.Value().SetVisible(False)
ds = board.GetDesignSettings()
ds.m_CopperEdgeClearance = mm(0.3)
out = os.path.join(HERE, "ServoID_%s.kicad_pcb" % V)
board.Save(out)
print("saved", out)
if "--pads" in sys.argv:
    for k in sorted(padpos): print(k, "%.2f %.2f" % padpos[k])
