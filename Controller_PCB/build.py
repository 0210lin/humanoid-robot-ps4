# 主控板:用 KiCad 內建 Python 產生 Controller_F.kicad_pcb
# 執行:"C:\Program Files\KiCad\10.0\bin\python.exe" build.py [--dump] [--noroute]
import os, sys, json
import pcbnew
from pcbnew import (BOARD, FOOTPRINT, PAD, PCB_TRACK, PCB_VIA, PCB_SHAPE, VECTOR2I, FromMM, ToMM,
                    F_Cu, B_Cu, Edge_Cuts, F_SilkS, NETINFO_ITEM)
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import importlib
V = os.environ.get('CTRL_VARIANT', 'F')
L = importlib.import_module('layout_' + V)
mm = FromMM
def P(x, y): return VECTOR2I(mm(x), mm(y))

board = BOARD()
nets = {}
def net(name):
    if name not in nets:
        n = NETINFO_ITEM(board, name); board.Add(n); nets[name] = n
    return nets[name]
net("GND")

for ref, lib, name, x, y, rot, val, pins in L.PARTS:
    fp = pcbnew.FootprintLoad(os.path.join(HERE if lib == 'custom' else L.LIB, lib + ".pretty"), name)
    fp.SetReference(ref); fp.SetValue(val)
    fp.SetPosition(P(x, y)); fp.SetOrientationDegrees(rot)
    for p in fp.Pads():
        n = p.GetNumber()
        if n in pins: p.SetNet(net(pins[n]))
    board.Add(fp)

def tht_pad(fp, number, x, y, drill, dia, netname=None):
    p = PAD(fp); p.SetNumber(number); p.SetShape(pcbnew.PAD_SHAPE_CIRCLE)
    p.SetAttribute(pcbnew.PAD_ATTRIB_PTH); p.SetLayerSet(p.PTHMask())
    p.SetDrillSize(VECTOR2I(mm(drill), mm(drill))); p.SetSize(VECTOR2I(mm(dia), mm(dia)))
    p.SetPosition(P(x, y))
    if netname: p.SetNet(net(netname))
    fp.Add(p); return p

# 20 個馬達插座:3 排(GND / PWR / SIG),每塊 10 個
sf = FOOTPRINT(board); sf.SetReference("J5"); sf.SetValue("SERVO x20"); sf.SetPosition(P(0, 0))
k = 0
for bx in L.SERVO_BLOCKS_X:
    for i in range(10):
        k += 1
        x = bx + 2.54 * i
        tht_pad(sf, "G%d" % k, x, L.SERVO_ROWS_Y[0], 1.0, 1.9, "GND")
        tht_pad(sf, "P%d" % k, x, L.SERVO_ROWS_Y[1], 1.0, 1.9, "VBAT")
        tht_pad(sf, "S%d" % k, x, L.SERVO_ROWS_Y[2], 1.0, 1.9, "SIG")
board.Add(sf)
# I2C 排針
jf = FOOTPRINT(board); jf.SetReference("J4"); jf.SetValue("I2C"); jf.SetPosition(P(0, 0))
for i in range(4):
    tht_pad(jf, str(i + 1), L.J4[0], L.J4[1] + 2.54 * i, 1.0, 1.7, L.J4_PINS[str(i + 1)])
board.Add(jf)
j6 = FOOTPRINT(board); j6.SetReference("J6"); j6.SetValue("KONDO"); j6.SetPosition(P(0, 0))
for i in range(4):
    tht_pad(j6, str(i + 1), L.J6[0], L.J6[1] + 2.54 * i, 1.0, 1.7, L.J6_PINS[str(i + 1)])
board.Add(j6)

for i, (x, y) in enumerate(L.HOLES):
    h = pcbnew.FootprintLoad(os.path.join(L.LIB, "MountingHole.pretty"), "MountingHole_3.2mm_M3")
    h.SetReference("H%d" % (i + 1)); h.SetValue(""); h.SetPosition(P(x, y)); board.Add(h)

def edge(a, b):
    s = PCB_SHAPE(board); s.SetShape(pcbnew.SHAPE_T_SEGMENT); s.SetLayer(Edge_Cuts)
    s.SetStart(P(*a)); s.SetEnd(P(*b)); s.SetWidth(mm(0.1)); board.Add(s)
W, H = L.BOARD_W, L.BOARD_H
edge((0, 0), (W, 0)); edge((W, 0), (W, H)); edge((W, H), (0, H)); edge((0, H), (0, 0))

def track(a, b, layer, width, netname):
    t = PCB_TRACK(board); t.SetStart(P(*a)); t.SetEnd(P(*b)); t.SetWidth(mm(width))
    t.SetLayer(layer); t.SetNet(net(netname)); board.Add(t)
def via(x, y, netname, dia=0.6, drill=0.3):
    v = PCB_VIA(board); v.SetPosition(P(x, y)); v.SetWidth(mm(dia)); v.SetDrill(mm(drill))
    v.SetNet(net(netname)); v.SetViaType(pcbnew.VIATYPE_THROUGH)
    v.SetLayerPair(F_Cu, B_Cu); board.Add(v)

if "--dump" in sys.argv:
    out = []
    for fp in board.GetFootprints():
        seen_ = {}
        for p in fp.Pads():
            bb = p.GetBoundingBox()
            nm_ = "%s.%s" % (fp.GetReference(), p.GetNumber())
            seen_[nm_] = seen_.get(nm_, 0) + 1
            if seen_[nm_] > 1:
                nm_ += "#%d" % seen_[nm_]
            out.append(dict(name=nm_, net=p.GetNetname(),
                            x1=ToMM(bb.GetLeft()), y1=ToMM(bb.GetTop()), x2=ToMM(bb.GetRight()), y2=ToMM(bb.GetBottom()),
                            cx=ToMM(p.GetPosition().x), cy=ToMM(p.GetPosition().y), drill=ToMM(p.GetDrillSize().x),
                            tht=bool(p.GetAttribute() in (pcbnew.PAD_ATTRIB_PTH, pcbnew.PAD_ATTRIB_NPTH)),
                            npth=bool(p.GetAttribute() == pcbnew.PAD_ATTRIB_NPTH or p.GetNumber() == "")))
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

def silk_text(text, x, y, size=1.0, rot=0):
    t = pcbnew.PCB_TEXT(board); t.SetText(text); t.SetPosition(P(x, y)); t.SetLayer(F_SilkS)
    t.SetTextSize(VECTOR2I(mm(size), mm(size))); t.SetTextThickness(mm(size * 0.15))
    t.SetTextAngleDegrees(rot); board.Add(t)

# 地線面(底層),天線下方留空
z = pcbnew.ZONE(board); z.SetLayer(B_Cu); z.SetNet(net("GND"))
ol = z.Outline(); ol.NewOutline()
ax1, ay1, ax2, ay2 = L.ANT_KEEPOUT
for x, y in ((0.3, ay2), (ax2, ay2), (ax2, 0.3), (W - 0.3, 0.3), (W - 0.3, H - 0.3), (0.3, H - 0.3)):
    ol.Append(mm(x), mm(y))
z.SetMinThickness(mm(0.2)); z.SetPadConnection(pcbnew.ZONE_CONNECTION_THERMAL)
z.SetThermalReliefGap(mm(0.3)); z.SetThermalReliefSpokeWidth(mm(0.4))
board.Add(z)
pcbnew.ZONE_FILLER(board).Fill(board.Zones())

for bx in L.SERVO_BLOCKS_X[:1]:
    silk_text("G", bx - 3.0, L.SERVO_ROWS_Y[0], 0.8)
    silk_text("P", bx - 3.0, L.SERVO_ROWS_Y[1], 0.8)
    silk_text("S", bx - 3.0, L.SERVO_ROWS_Y[2], 0.8)
silk_text("ESP32 Robot Controller 3S", 32.0, 58.2, 0.9)
silk_text("-", 44.3, 33.0, 1.0); silk_text("+", 44.3, 40.2, 1.0)
for t_, x_, y_ in getattr(L, "LABELS", []):
    silk_text(t_, x_, y_, 0.8)
for fp in board.GetFootprints():
    fp.Reference().SetVisible(False); fp.Value().SetVisible(False)
board.GetDesignSettings().m_CopperEdgeClearance = mm(0.3)
out = os.path.join(HERE, "Controller_%s.kicad_pcb" % V)
board.Save(out)
print("saved", out)
