# 輸出下單用的檔案:Gerber、鑽孔、零件位置(CPL)、BOM、1:1 列印檔
# 用法:"C:\Program Files\KiCad\10.0\bin\python.exe" export_fab.py C
import os, sys, subprocess, csv, zipfile, shutil, collections
import pcbnew
HERE = os.path.dirname(os.path.abspath(__file__))
CLI = r"C:\Program Files\KiCad\10.0\bin\kicad-cli.exe"
v = sys.argv[1]
pcb = os.path.join(HERE, "ServoID_%s.kicad_pcb" % v)
out = os.path.join(HERE, "fab_%s" % v)
if os.path.exists(out):
    shutil.rmtree(out)
os.makedirs(out)

# Gerber + 鑽孔
gdir = os.path.join(out, "gerber")
os.makedirs(gdir)
subprocess.run([CLI, "pcb", "export", "gerbers", "--output", gdir + os.sep, "--layers",
                "F.Cu,B.Cu,F.Paste,F.Mask,B.Mask,F.Silkscreen,B.Silkscreen,Edge.Cuts", "--no-protel-ext", pcb], check=True, capture_output=True)
subprocess.run([CLI, "pcb", "export", "drill", "--output", gdir + os.sep, "--format", "excellon", "--drill-origin", "absolute",
                "--excellon-units", "mm", "--generate-map", "--map-format", "gerberx2", pcb], check=True, capture_output=True)
zp = os.path.join(out, "ServoID_%s_gerber.zip" % v)
with zipfile.ZipFile(zp, "w", zipfile.ZIP_DEFLATED) as z:
    for f in sorted(os.listdir(gdir)):
        z.write(os.path.join(gdir, f), f)

# 零件位置 CPL(只含 SMD)與 BOM(JLCPCB 格式,LCSC 料號欄留空,下單時在 JLCPCB 零件庫選)
b = pcbnew.LoadBoard(pcb)
rows = []
groups = collections.OrderedDict()
for fp in sorted(b.GetFootprints(), key=lambda f: f.GetReference()):
    ref = fp.GetReference()
    attr = fp.GetAttributes()
    is_smd = bool(attr & pcbnew.FP_SMD)
    if not is_smd:
        continue
    pos = fp.GetPosition()
    rows.append([ref, fp.GetValue(), str(fp.GetFPID().GetLibItemName()), "%.4f" % (pos.x / 1e6), "%.4f" % (-pos.y / 1e6), "%.0f" % fp.GetOrientationDegrees(), "top"])
    key = (fp.GetValue(), str(fp.GetFPID().GetLibItemName()))
    groups.setdefault(key, []).append(ref)
with open(os.path.join(out, "ServoID_%s_CPL.csv" % v), "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(["Designator", "Val", "Package", "Mid X", "Mid Y", "Rotation", "Layer"])
    w.writerows(rows)
with open(os.path.join(out, "ServoID_%s_BOM.csv" % v), "w", newline="", encoding="utf-8-sig") as f:
    w = csv.writer(f)
    w.writerow(["Comment", "Designator", "Footprint", "Quantity", "LCSC Part #"])
    for (val, fpn), refs in groups.items():
        w.writerow([val, ",".join(sorted(refs)), fpn, len(refs), ""])

# 1:1 列印檔(用來把 SuperMini 實體放上去對孔位)
subprocess.run([CLI, "pcb", "export", "pdf", "--output", os.path.join(out, "ServoID_%s_print_1to1.pdf" % v), "--layers", "F.Cu,F.Silkscreen,Edge.Cuts",
                "--black-and-white", "--mode-single", "--include-border-title", "--drill-shape-opt", "2", pcb], capture_output=True)
shutil.copy(os.path.join(HERE, "render_%s_top.png" % v), os.path.join(out, "preview_top.png"))
shutil.copy(os.path.join(HERE, "render_%s_bottom.png" % v), os.path.join(out, "preview_bottom.png"))
print("done", out)
for f in sorted(os.listdir(out)):
    print("  ", f)
