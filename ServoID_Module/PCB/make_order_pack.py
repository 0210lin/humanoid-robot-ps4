# 整理「下單資料」資料夾。用法:python make_order_pack.py C   (用 venv 的 python 也可以)
import os, sys, csv, shutil, subprocess, collections
HERE = os.path.dirname(os.path.abspath(__file__))
v = sys.argv[1]
fab = os.path.join(HERE, "fab_%s" % v)
dest = os.path.join(HERE, "下單資料_%s版" % v)
os.makedirs(dest, exist_ok=True)
for f in os.listdir(dest):      # 只清空內容;資料夾本身若正被檔案總管開著會刪不掉
    p = os.path.join(dest, f)
    shutil.rmtree(p) if os.path.isdir(p) else os.remove(p)

# 板子資訊:零件數量、孔、尺寸都從板子算,換版本不用改這支程式
import pcbnew
b = pcbnew.LoadBoard(os.path.join(HERE, "ServoID_%s.kicad_pcb" % v))
bb = b.GetBoardEdgesBoundingBox()
BW, BH = round(bb.GetWidth() / 1e6 - 0.1, 1), round(bb.GetHeight() / 1e6 - 0.1, 1)
smd = sorted(fp.GetReference() for fp in b.GetFootprints() if fp.GetAttributes() & pcbnew.FP_SMD)
HOLE_NAME = {"U2": "SuperMini", "J1": "電池焊點", "J2": "馬達焊點"}
holes = {}
for fp in sorted(b.GetFootprints(), key=lambda f: f.GetReference()):
    for p in fp.Pads():
        if p.GetDrillSizeX():
            npth = p.GetAttribute() == pcbnew.PAD_ATTRIB_NPTH
            who = "M3 鎖孔" if npth else HOLE_NAME.get(fp.GetReference(), fp.GetReference())
            d = holes.setdefault((round(p.GetDrillSizeX() / 1e6, 2), npth), collections.OrderedDict())
            d[who] = d.get(who, 0) + 1
hole_txt = ";".join("%.1f mm %s x %d(%s)" % (k[0], "非鍍銅孔" if k[1] else "鍍銅孔", sum(d.values()),
                    " + ".join("%s %d 個" % (n, c) for n, c in d.items())) for k, d in sorted(holes.items()))
CAT = [("C", "電容"), ("D", "二極體"), ("F", "保險絲"), ("L", "電感"), ("R", "電阻"), ("Q", "MOSFET(SOT-23)"), ("U", "IC(TSOT-26)")]
cat_txt = "、".join("%s %d" % (n, sum(1 for r in smd if r[0] == k)) for k, n in CAT if any(r[0] == k for r in smd))
has_q = "Q1" in smd

# 1 Gerber、3 CPL、7 預覽圖
shutil.copy(os.path.join(fab, "ServoID_%s_gerber.zip" % v), os.path.join(dest, "01_Gerber和鑽孔.zip"))
# CPL 旋轉角度改成 JLCPCB 零件庫的方向(KiCad 角度 + 修正值)。
# 依據:JLCPCB 零件頁的 EasyEDA 封裝圖。TSOT-23-6(C2071056)0° 是 1 腳在左下、腳在上下兩排;
# SOT-23(C15127)0° 是 3 腳在左、1 腳在右下。C 版下單時 U1 也是在預覽頁手動順時針轉 90° 才對。
JLC_ROT = {"TSOT-23-6": 270, "SOT-23": 180}
cpl = list(csv.reader(open(os.path.join(fab, "ServoID_%s_CPL.csv" % v), encoding="utf-8")))
rot_notes = []
with open(os.path.join(dest, "03_CPL_零件位置.csv"), "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(cpl[0])
    for r in cpl[1:]:
        if r[2] in JLC_ROT:
            new = (int(r[5]) + JLC_ROT[r[2]]) % 360
            rot_notes.append("%s(%s):KiCad %s° → 檔案內 %d°" % (r[0], r[2], r[5], new))
            r = r[:5] + [str(new)] + r[6:]
        w.writerow(r)
shutil.copy(os.path.join(fab, "preview_top.png"), os.path.join(dest, "07_預覽_上層.png"))
shutil.copy(os.path.join(fab, "preview_bottom.png"), os.path.join(dest, "07_預覽_下層.png"))

# 2 BOM:加規格說明欄
SPEC = {
    "10uF 25V": "陶瓷電容 10uF,耐壓 25V 以上(3S 滿電 12.6V),X5R/X7R,1206",
    "22uF": "陶瓷電容 22uF,耐壓 6.3V 以上(輸出 5V),X5R/X7R,0805",
    "100nF": "陶瓷電容 100nF,耐壓 16V 以上,X5R/X7R,0603",
    "1N4148W": "小訊號二極體 1N4148W,SOD-123",
    "SS14": "蕭特基二極體 SS14,1A、40V,SMA",
    "5A": "SMD 保險絲,5A,快斷,耐壓 32V 以上,1206",
    "4.7uH": "功率電感 4.7uH,屏蔽型約 4x4mm,飽和電流 2.7A 以上,DCR 100mΩ 以下(封裝 Bourns SRN4018 腳位)",
    "3.3k": "電阻 3.3kΩ,1%,0603",
    "22k": "電阻 22kΩ,1%,0603",
    "47k": "電阻 47kΩ,1%,0603",
    "100k": "電阻 100kΩ,1%,0603",
    "AP63205WU-7": "Diodes Inc. AP63205WU-7,5V 固定輸出降壓 IC,TSOT-26(TSOT-23-6)",
    "AO3401A": "P 通道 MOSFET AO3401A(AOS),-30V、-4A、Vgs ±12V,SOT-23(1=G、2=S、3=D),電池防反接用",
}
# JLCPCB / LCSC 料號:C 版 2026-10-10 實際下單用的那一組(板子已測試可用),Q1 是 E 版新增
LCSC = {"10uF 25V": "C13585", "22uF": "C45783", "100nF": "C14663", "1N4148W": "C81598", "SS14": "C2480",
        "5A": "C48332", "4.7uH": "C48427", "3.3k": "C22978", "22k": "C31850", "47k": "C25819", "100k": "C25803",
        "AP63205WU-7": "C2071056", "AO3401A": "C15127"}
rows = list(csv.reader(open(os.path.join(fab, "ServoID_%s_BOM.csv" % v), encoding="utf-8-sig")))
with open(os.path.join(dest, "02_BOM_零件清單.csv"), "w", newline="", encoding="utf-8-sig") as f:
    w = csv.writer(f)
    w.writerow(rows[0] + ["Spec / 規格說明"])
    for r in rows[1:]:
        w.writerow(r[:4] + [LCSC.get(r[0], "")] + [SPEC.get(r[0], "")])

# 4 組裝圖
K = r"C:\Program Files\KiCad\10.0\bin\python.exe"
subprocess.run([K, os.path.join(HERE, "assembly_svg.py"), v], check=True, capture_output=True)
svg = os.path.join(HERE, "assembly_%s.svg" % v)
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
head = open(svg, encoding="utf-8").read(300)
import re
wpx = int(float(re.search(r'width="([\d.]+)"', head).group(1))); hpx = int(float(re.search(r'height="([\d.]+)"', head).group(1)))
png = os.path.join(dest, "04_組裝圖_零件編號與方向.png")
uri = "file:///" + svg.replace("\\", "/").replace(" ", "%20")
tmp = os.path.join(os.environ.get("TEMP", HERE), "asm_%s.png" % v)
if os.path.exists(tmp):
    os.remove(tmp)
subprocess.run([EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--window-size=%d,%d" % (wpx + 7, hpx + 7), "--screenshot=" + tmp, uri], capture_output=True, timeout=120)
import time
for _ in range(40):
    if os.path.exists(tmp) and os.path.getsize(tmp) > 1000:
        break
    time.sleep(0.5)
shutil.copy(tmp, png)

# 5 規格說明
open(os.path.join(dest, "05_板子規格與組裝說明.md"), "w", encoding="utf-8").write("""# ServoID 電路板 %s 版 — 板子規格與組裝說明 / PCB spec and assembly notes

## PCB 規格 / PCB specification
| 項目 Item | 內容 Value |
|---|---|
| 板材 Material | FR-4 TG135 |
| 層數 Layers | 2 |
| 尺寸 Size | %.1f x %.1f mm,單片出貨(single piece,不拼板) |
| 板厚 Thickness | 1.6 mm |
| 銅厚 Copper | 1 oz(外層) |
| 阻焊 Solder mask | 綠色(green)(下單時可自選顏色) |
| 絲印 Silkscreen | 白色(white) |
| 表面處理 Surface finish | HASL(有鉛或無鉛皆可) |
| 過孔 Vias | 0.6 mm 焊盤 / 0.3 mm 鑽孔,蓋油(tented) |
| 最小線寬 / 間距 Min track / space | 0.25 mm / 0.2 mm |
| 孔 Holes | %s |
| 半孔 / 金手指 / 邊緣電鍍 | 無 None |
| 測試 Test | 飛針(flying probe) |

## 組裝(SMT)/ Assembly
- 只貼**頂層(Top side)**,%d 顆 SMD 零件:%s。
- **不要貼:** U2(ESP32-C3 SuperMini 模組)、J1(電池焊點)、J2(馬達焊點)、H1~H4(M3 鎖孔)。這些是通孔焊盤 / 孔,由客戶自己焊。
- 二極體 D1 D2 D3 有極性:組裝圖上紅色粗線那端是陰極(有橫線端)。U1%s 的 1 腳位置見組裝圖(紅點)。
- 零件規格見 `02_BOM_零件清單.csv` 的 Spec 欄。LCSC 料號欄已填 JLCPCB / LCSC 料號(C 版實際下單、測試可用的那一組%s);給其他廠商時請依 Spec 欄對應。
- 零件位置檔 `03_CPL_零件位置.csv`:座標單位 mm,Y 軸朝上(KiCad 輸出格式),Mid Y 為負值是正常的。
- **旋轉角度已改成 JLCPCB 零件庫的方向:** %s。其他零件是 KiCad 原始角度。上傳後仍請在零件擺放預覽圖上對照 `04_組裝圖` 確認 1 腳(紅點)位置;若給其他廠商,請以 `04_組裝圖` 的方向為準。
%s
## 檔案清單
1. `01_Gerber和鑽孔.zip`:Gerber(RS-274X)與 Excellon 鑽孔檔
2. `02_BOM_零件清單.csv`
3. `03_CPL_零件位置.csv`
4. `04_組裝圖_零件編號與方向.png`
5. `05_板子規格與組裝說明.md`(本檔)
6. `06_詢價信範本.txt`
7. `07_預覽_上層.png` / `07_預覽_下層.png`
8. `08_接線表_網路.csv`:每個網路接了哪些腳(給確認用)
""" % (v, BW, BH, hole_txt, len(smd), " ".join(smd), "、Q1" if has_q else "",
       ";Q1 = C15127 是這版新增的" if has_q else "", ";".join(rot_notes),
       """
## 這一版和 C 版的差別 / Changes from version C
- 新增電池防反接:Q1(P 通道 MOSFET AO3401A,SOT-23)串在保險絲之後的正極路徑,R6 / R7(100 kΩ)分壓做閘極偏壓。電池接反時 Q1 不導通,板子和馬達都不會通電。
- 電池焊點改成 2.54 mm 間距、1.0 mm 孔、2.0 mm 焊盤(可以直接焊排針或電線),標示 − / + 印在焊點下方。
- Q1 持續電流約 3~4 A(比保險絲 5 A 小):單顆伺服編 ID 沒問題,不要接多顆伺服長時間滿載。
""" if has_q else ""))

# 6 詢價信
open(os.path.join(dest, "06_詢價信範本.txt"), "w", encoding="utf-8").write("""主旨:PCB 打樣加 SMT 代工報價詢問(2 層板 %gx%g mm,5 片)

您好,

想詢問以下電路板的打樣與 SMT 代工報價,附件是 Gerber、BOM、CPL 和組裝圖。

【PCB】
- 2 層,FR-4,%g x %g mm,1.6 mm,1 oz,HASL,綠色阻焊,白色絲印
- 數量:5 片(也想知道 10 片的單價)

【SMT 組裝】
- 只貼頂層,共 %d 顆 SMD(%d 種料號):%s
- SuperMini 模組與接線焊點由我自己焊,不用貼
- 組裝數量:2 片(或 5 片)

【想請教】
1. 零件是由貴公司代購,還是需要我自備?代購的話缺料怎麼處理?
2. 有沒有最低下單量或開機費、鋼網費?
3. 請提供分項報價:板費、鋼網、貼片費、零件費、運費、稅
4. 交期(從確認檔案到出貨)
5. 零件擺放確認圖(DFM / 擺放預覽)是否可以先看,再付款?

檔案說明請看 `05_板子規格與組裝說明.md`。謝謝!

(附件:01_Gerber和鑽孔.zip、02_BOM_零件清單.csv、03_CPL_零件位置.csv、04_組裝圖_零件編號與方向.png、05_板子規格與組裝說明.md)
""" % (BW, BH, BW, BH, len(smd), len(rows) - 1, cat_txt))

# 8 接線表
nets = collections.defaultdict(list)
for fp in b.GetFootprints():
    for p in fp.Pads():
        if p.GetNetname():
            nets[p.GetNetname()].append("%s.%s" % (fp.GetReference(), p.GetNumber()))
with open(os.path.join(dest, "08_接線表_網路.csv"), "w", newline="", encoding="utf-8-sig") as f:
    w = csv.writer(f)
    w.writerow(["Net 網路", "Pads 接了哪些腳"])
    for n in sorted(nets):
        w.writerow([n, " ".join(sorted(nets[n]))])
print("done", dest)
for f in sorted(os.listdir(dest)):
    print("  ", f)
