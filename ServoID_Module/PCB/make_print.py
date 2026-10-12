# 1:1 對孔位列印檔:板子置中、不要圖框、附 50 mm 尺規。用法:python make_print.py C
import os, re, subprocess, sys
HERE = os.path.dirname(os.path.abspath(__file__))
CLI = r"C:\Program Files\KiCad\10.0\bin\kicad-cli.exe"
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
v = sys.argv[1]
svg = os.path.join(HERE, "print_%s.svg" % v)
subprocess.run([CLI, "pcb", "export", "svg", "--layers", "F.Cu,F.Silkscreen,Edge.Cuts", "--mode-single", "--black-and-white",
                "--page-size-mode", "2", "--exclude-drawing-sheet", "--drill-shape-opt", "2", "-o", svg,
                os.path.join(HERE, "ServoID_%s.kicad_pcb" % v)], check=True, capture_output=True)
head = open(svg, encoding="utf-8").read(900)
w = float(re.search(r'width="([\d.]+)mm"', head).group(1))
h = float(re.search(r'height="([\d.]+)mm"', head).group(1))
html = """<!doctype html><html><head><meta charset="utf-8"><style>
@page { size: A4 portrait; margin: 0 }
body { margin: 0; width: 210mm; height: 297mm; font-family: "Microsoft JhengHei", sans-serif; }
.board { position: absolute; left: %.2fmm; top: 45mm; width: %.3fmm; height: %.3fmm; }
.t { position: absolute; left: 0; width: 210mm; text-align: center; }
.ruler { position: absolute; left: 80mm; top: 225mm; width: 50mm; height: 0; border-top: 0.4mm solid #000; }
.tick { position: absolute; top: -2mm; width: 0; height: 4mm; border-left: 0.3mm solid #000; }
</style></head><body>
<div class="t" style="top:12mm;font-size:5mm"><b>ServoID 電路板 %s 版 — 1:1 對孔位</b></div>
<div class="t" style="top:20mm;font-size:3.6mm">列印時請選「實際大小 / 100%%」,不要選「符合頁面」。先用尺量下方尺規是否剛好 50 mm。</div>
<div class="t" style="top:27mm;font-size:3.6mm">把 SuperMini 的排針放在板子上的 16 個孔裡(USB 朝上),看孔是不是都對得到。</div>
<img class="board" src="print_%s.svg">
<div class="ruler"><div class="tick" style="left:0"></div><div class="tick" style="left:10mm"></div><div class="tick" style="left:20mm"></div>
<div class="tick" style="left:30mm"></div><div class="tick" style="left:40mm"></div><div class="tick" style="left:50mm"></div></div>
<div class="t" style="top:231mm;font-size:3.6mm">這條線 = 50 mm</div>
</body></html>""" % ((210 - w) / 2, w, h, v, v)
hp = os.path.join(HERE, "print_%s.html" % v)
open(hp, "w", encoding="utf-8").write(html)
out = os.path.join(HERE, "fab_%s" % v, "ServoID_%s_對孔位1比1.pdf" % v)
if os.path.exists(out):
    os.remove(out)
uri = "file:///" + hp.replace("\\", "/").replace(" ", "%20")
subprocess.run([EDGE, "--headless=new", "--disable-gpu", "--no-pdf-header-footer", "--print-to-pdf=" + out, uri], capture_output=True, timeout=120)
print(out, os.path.exists(out))
