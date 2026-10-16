# JLCPCB 上傳頁只收「英文檔名」,內容也最好是英文。從 下單資料_<版本>\ 產生一份上傳用的三個檔。
# 用法: python make_upload.py E   → Upload_E\ServoID_E_Gerber.zip / ServoID_E_BOM.csv / ServoID_E_CPL.csv
import csv, os, shutil, sys
HERE = os.path.dirname(os.path.abspath(__file__))
v = sys.argv[1]
src = os.path.join(HERE, "下單資料_%s版" % v)
dst = os.path.join(HERE, "Upload_%s" % v)
os.makedirs(dst, exist_ok=True)
for f in os.listdir(dst):
    os.remove(os.path.join(dst, f))
# 中文品名 → 英文(只有排針 / 母座用到)
EN = {"1x2P 2.54mm 彎公針": "Pin header 1x2P 2.54mm right angle", "1x3P 2.54mm 彎公針": "Pin header 1x3P 2.54mm right angle",
      "1x8P 2.54mm 母座": "Female header 1x8P 2.54mm vertical"}
shutil.copy(os.path.join(src, "01_Gerber和鑽孔.zip"), os.path.join(dst, "ServoID_%s_Gerber.zip" % v))
rows = list(csv.reader(open(os.path.join(src, "02_BOM_零件清單.csv"), encoding="utf-8-sig")))
with open(os.path.join(dst, "ServoID_%s_BOM.csv" % v), "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    for i, r in enumerate(rows):
        r = r[:5]                                   # Comment, Designator, Footprint, Quantity, LCSC Part #(不要中文說明欄)
        if i: r[0] = EN.get(r[0], r[0])
        w.writerow(r)
rows = list(csv.reader(open(os.path.join(src, "03_CPL_零件位置.csv"), encoding="utf-8")))
with open(os.path.join(dst, "ServoID_%s_CPL.csv" % v), "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    for i, r in enumerate(rows):
        if i: r[1] = EN.get(r[1], r[1])
        w.writerow(r)
bad = []
for f in os.listdir(dst):
    if f.endswith(".csv") and any(ord(c) > 127 for c in open(os.path.join(dst, f), encoding="utf-8").read()):
        bad.append(f)
print("OK ->", dst)
for f in sorted(os.listdir(dst)):
    print("  ", f, "(still has non-ASCII)" if f in bad else "")
