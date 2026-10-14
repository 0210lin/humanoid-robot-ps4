# ESP32 機器人主控板(草稿 F 版)

ESP32-WROOM-32E(傳統藍牙,直接讀 PS4 手把)+ 20 個 3 pin 馬達插座(GND / PWR / SIG)+ XT60 電池 + 防反接 + 5 V 降壓 + 3.3 V + USB-C 燒錄。64 x 60 mm,2 層。

## 重現
```
"C:\Program Files\KiCad\10.0\bin\python.exe" make_footprints.py
"C:\Program Files\KiCad\10.0\bin\python.exe" build.py --dump --noroute
set VIA_COST=6 & set SORT=1 & set SERVO_TURN=1.0
"C:\Program Files\KiCad\10.0\bin\python.exe" autoroute.py
"C:\Program Files\KiCad\10.0\bin\python.exe" build.py
```
DRC:`kicad-cli pcb drc ...` → 0 違規、0 未連接(2026-10-13)。

## 電路
- 電池 XT60 + → 保險絲(汽車 mini 片式保險絲,手焊)→ Q1 CJU40P03(P 通道 30 V 40 A,TO-252,防反接,閘極 100k 到地;取代不建議新設計的 AOD403)→ VBAT(馬達 PWR 與降壓輸入)。
- AP63205 降壓 5 V(同 ServoID 板)→ D3 / D4(SS14)與 USB 5 V 二選一 → V5S → AMS1117-3.3 → 3V3。
- 匯流排:TX = GPIO17、RX = GPIO16,D2 / R1 / D1 / R2 / R3 同 ServoID 板。
- 電池電壓偵測 GPIO35(100k / 22k)。LED GPIO4。
- I2C 排針(腳序同原板:VDD 5V / GND / SCL / SDA):SDA = GPIO21、SCL = GPIO22(BNO055)。
- Kondo 接收器預留排針:5V / GND / RX / TX,RX 經 22k / 47k 分壓到 GPIO19,TX = GPIO18。
- 馬達插座外側到內側:SIG / PWR / GND(同原板)。電池接頭 XT60。
- CH340C + 兩顆 NPN 做自動進入燒錄模式;另有 BOOT / RESET 按鍵。

## 尚未驗證
- CJU40P03(LCSC C19268999,JLCPCB 擴充料件)的腳位(G / D / S)、Vgs 上限與供貨。
- 電流:1 oz 銅、PWR 排 1.5 mm 線寬,滿載堵轉電流要另外核算,建議 2 oz。
- USB D+/D- 手動橋接、天線周圍淨空、自動燒錄電路都還沒實體測過。
- 尚未產生 BOM / CPL / Gerber 下單資料。
