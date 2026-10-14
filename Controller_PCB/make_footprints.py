# 產生 custom.pretty:改過的 ESP32-WROOM-32E(去掉 0.2 mm 散熱孔、中央接地改成一整塊)與 TO-252(去掉 DPAK 沒有的中間腳)
import os, re
LIB = r"C:\Program Files\KiCad\10.0\share\kicad\footprints"
HERE = os.path.dirname(os.path.abspath(__file__))
out = os.path.join(HERE, "custom.pretty"); os.makedirs(out, exist_ok=True)

def blocks(text, head):
    """回傳 text 中所有以 '(head ' 開頭的平衡括號區塊 (start, end)"""
    res = []
    for m in re.finditer(r"\n\t\(" + head + r" ", text):
        s = m.start() + 2
        d = 0
        for k in range(s, len(text)):
            if text[k] == "(":
                d += 1
            elif text[k] == ")":
                d -= 1
                if d == 0:
                    res.append((s, k + 1)); break
    return res

# ESP32
t = open(os.path.join(LIB, "RF_Module.pretty", "ESP32-WROOM-32E.kicad_mod"), encoding="utf-8").read()
cut = []
for s, e in blocks(t, "pad"):
    if re.match(r'\(pad "39"', t[s:e]):
        cut.append((s, e))
for s, e in reversed(cut):
    t = t[:s] + t[e:]
ep = '''\t(pad "39" smd rect
\t\t(at -1.5 2.46)
\t\t(size 3.7 3.7)
\t\t(layers "F.Cu" "F.Paste" "F.Mask")
\t)
'''
k = t.rindex("\n\t(model") if "\n\t(model" in t else t.rindex(")")
t = t[:k + 1] + ep + t[k + 1:]
open(os.path.join(out, "ESP32-WROOM-32E.kicad_mod"), "w", encoding="utf-8").write(t)

# TO-252-3_TabPin2:去掉小的 2 號腳
t = open(os.path.join(LIB, "Package_TO_SOT_SMD.pretty", "TO-252-3_TabPin2.kicad_mod"), encoding="utf-8").read()
cut = []
for s, e in blocks(t, "pad"):
    seg = t[s:e]
    if re.match(r'\(pad "2"', seg) and re.search(r"\(at -5\.04 0\)", seg):
        cut.append((s, e))
assert len(cut) == 1, cut
for s, e in reversed(cut):
    t = t[:s] + t[e:]
open(os.path.join(out, "TO-252-2_DPAK.kicad_mod"), "w", encoding="utf-8").write(t)
print("ok")
