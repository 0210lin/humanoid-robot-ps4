# 請 JLCPCB 代焊的插件(排針 / 母座),要選 Standard PCBA。沒列在這裡的版本 = 插件都自己焊。
# 每一項:(位號, 名稱, 封裝, LCSC 料號, 中心 x, 中心 y(KiCad 座標 mm,y 向下), JLC 角度, 規格說明, 組裝圖標示, 板子上原本的焊盤位號)
# 角度依據:JLCPCB 零件頁的 EasyEDA 封裝圖(2026-10-15 看過)
#   彎排針 C54907645 / C54907646:0° = 一排焊孔、1 腳在左、針往下伸 → 板子上針朝下緣外面,就是 0°
#   母座 C27438:0° = 橫的一排、1 腳在左 → SuperMini 的孔是直的,轉 270°(1 腳在上)
THT = {
    "E": [
        ("J1", "1x2P 2.54mm 彎公針", "PinHeader_1x02_P2.54mm_Horizontal", "C54907645", 10.27, 45.0, 0,
         "電池接頭:2.54 mm 單排 2P 公排針,90 度彎(SHOU HAN PZ2.54-1x2PWZ),針朝板子下緣外面;1 腳 = −、2 腳 = +",
         "J1 電池 2P 彎公針", "J1"),
        ("J2", "1x3P 2.54mm 彎公針", "PinHeader_1x03_P2.54mm_Horizontal", "C54907646", 20.04, 45.0, 0,
         "馬達接頭:2.54 mm 單排 3P 公排針,90 度彎(SHOU HAN PZ2.54-1x3PWZ),針朝板子下緣外面;1 腳起 GND / PWR / SIG",
         "J2 馬達 3P 彎公針", "J2"),
        ("J3", "1x8P 2.54mm 母座", "PinSocket_1x08_P2.54mm_Vertical", "C27438", 7.88, 10.19, 270,
         "SuperMini 插座:2.54 mm 單排 8P 母座,直插,高 8.5 mm(BOOMELE)。J3 左排、J4 右排,SuperMini 模組本身不用貼(客戶自己插)",
         "J3", "U2"),
        ("J4", "1x8P 2.54mm 母座", "PinSocket_1x08_P2.54mm_Vertical", "C27438", 23.12, 10.19, 270,
         "SuperMini 插座:2.54 mm 單排 8P 母座,直插,高 8.5 mm(BOOMELE)。J3 左排、J4 右排,SuperMini 模組本身不用貼(客戶自己插)",
         "J4", "U2"),
    ],
}
