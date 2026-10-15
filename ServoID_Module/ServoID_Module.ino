/*
  ServoID_Module — 用手機(WiFi 網頁)編伺服馬達 ID、測試轉動
  板子:M5Stack ATOM Lite(esp32:esp32:m5stack_atom)或 Seeed XIAO ESP32-C3(esp32:esp32:XIAO_ESP32C3:CDCOnBoot=cdc)

  用法:模組通電後會開自己的 WiFi 熱點(預設「HurtMakerID」/ 下面的 AP_PASS;賣出去的板子出貨時用 USB 改成買家的名稱和密碼),
        手機連上去,瀏覽器開 http://192.168.4.1
        網頁裡可以設定「模組要去找的網路」(只能一個,例如買家的手機熱點);連上後熱點照樣開著,
        在那個網路上開 http://servoid.local 也能用。
        有線:USB 線接電腦,用 ServoID_Tool.exe 選 COM 埠(不用連 WiFi)。指令和網頁的網址一樣,
        一行一個,例如 /api/move?id=1&delta=100;回應是一行「@」開頭的 JSON。
  電路和接線見 README.md。

  安全設計:
   ・永遠不送廣播(ID 0)的寫入指令,避免一次改到所有馬達
   ・改 ID 前先掃描 1~127 號:找到的馬達數量不是剛好 1 顆就拒絕
     (例外:電腦版「多顆馬達」頁的「全部改成同一個 ID」,使用者明確按下才會用,一樣不送廣播)
   ・測試轉動:電池電壓要和你選的馬達電壓(7.4 V / 12 V)相符才允許;位置夾在 800~2200,角度小、空載
   ・馬達電源是電池直通(模組不能切斷),所以要「先接電池看電壓,確認後才插馬達」
*/
#include <WiFi.h>
#include <WebServer.h>
#include <DNSServer.h>
#include <ESPmDNS.h>
#include <Preferences.h>
#include "servobus.h"
#if defined(CONFIG_IDF_TARGET_ESP32C3)
#include "hal/usb_serial_jtag_ll.h"   // 直接叫 USB 把緩衝區的資料送出去(見 sendJson)
#endif
#include "webpage.h"

// ===== 腳位 =====
// 兩塊板子都支援:編譯時依晶片自動選擇。
#if defined(CONFIG_IDF_TARGET_ESP32C3)
// --- Seeed XIAO ESP32-C3(esp32:esp32:XIAO_ESP32C3:CDCOnBoot=cdc)---
static const int PIN_BUS_TX = 7;   // D5  → 蕭特基二極體陰極(陽極接 Sig)。不要用 GPIO20/21:那是開機序列埠,開機訊息會跑到匯流排上
static const int PIN_BUS_RX = 6;   // D4  ← Sig 分壓(22k 在上、47k 在下)
static const int PIN_VBAT = 3;     // D1  ← 電池分壓(100k 在上、22k 在下)
#if defined(ARDUINO_XIAO_ESP32C3)
static const int PIN_LED = 10;     // D10 板上 LED
#else
static const int PIN_LED = 8;      // 其他 ESP32-C3 板(例如 ESP32-C3 SuperMini):板上 LED 在 GPIO8(低電位亮,相反的話只是亮暗顛倒)
#endif
static const bool LED_ACTIVE_LOW = true;
#define LED_IS_NEOPIXEL 0
#else
// --- M5Stack ATOM Lite(標準 ESP32;esp32:esp32:m5stack_atom)---
static const int PIN_BUS_TX = 22;  // G22 → 蕭特基二極體陰極(陽極接 Sig)
static const int PIN_BUS_RX = 19;  // G19 ← Sig 分壓(22k 在上、47k 在下)
static const int PIN_VBAT = 33;    // G33 ← 電池分壓(100k 在上、22k 在下);G33 是 ADC1,WiFi 開著也能量
static const int PIN_LED = 27;     // G27 板上全彩 LED(SK6812)
static const bool LED_ACTIVE_LOW = false;
#define LED_IS_NEOPIXEL 1
#endif

#if defined(CONFIG_IDF_TARGET_ESP32C3)
static const int PIN_BOOT = 9;     // 板上 BOOT 鍵(開機後當一般按鍵用):按住 3 秒 = 熱點密碼回到出廠密碼
#else
static const int PIN_BOOT = 39;    // ATOM Lite 的按鍵
#endif

static const uint32_t BUS_BAUD = 500000;
static const float VBAT_RATIO = (100000.0f + 22000.0f) / 22000.0f;
static const char* AP_PASS = "servoid1234";  // 至少 8 個字元,想改就改這裡

WebServer server(80);
DNSServer dns;            // 所有網址都指到模組(手機的「有沒有網路」檢查才會問到這裡)
Preferences prefs;
// 網路設定(存在板子裡,重新燒韌體也不會不見):
//   自己的熱點:名稱 / 密碼,出貨時用 USB 寫入(/api/apset,只有 USB 能改);沒寫就用預設 HurtMakerID。
//              密碼買家可以在網頁自己改(/api/appass),名稱不行。出廠密碼另外存在 apfac,按住 BOOT 3 秒會改回出廠密碼
//   要去找的網路:只能一個,買家在網頁設定(/api/netset)
static String apSsid, apPass, staSsid, staPass;
static uint32_t restartAt = 0;    // 不是 0:到這個時間就重新開機(改完網路設定後)
float vbatCal = 1.0f;     // 電池電壓校正係數(用 /api/cal?v=實測電壓 調整)
int selClass = 0;         // 0 = 還沒選,74 = 7.4 V 馬達,12 = 12 V 馬達
bool lastEchoOk = true;   // 最近一次有沒有聽到自己送出的資料(單線會有回音)

// ---------- LED ----------
static inline void ledSet(bool on) {
#if LED_IS_NEOPIXEL
  neopixelWrite(PIN_LED, 0, on ? 30 : 0, on ? 30 : 0);   // 匯流排有動作時亮青色
#else
  digitalWrite(PIN_LED, (on != LED_ACTIVE_LOW) ? HIGH : LOW);
#endif
}

// ---------- 電池 ----------
float readVbat() {
  uint32_t sum = 0;
  for (int i = 0; i < 16; i++) { sum += analogReadMilliVolts(PIN_VBAT); delayMicroseconds(300); }
  return (sum / 16.0f) / 1000.0f * VBAT_RATIO * vbatCal;
}
const char* classOf(float v) {
  if (v < 4.5f) return "none";
  if (v >= 6.0f && v <= 8.7f) return "2S";
  if (v >= 9.0f && v <= 12.9f) return "3S";
  return "bad";
}
bool lowBattery(float v) {
  const char* c = classOf(v);
  if (!strcmp(c, "2S")) return v < 6.6f;
  if (!strcmp(c, "3S")) return v < 9.9f;
  return false;
}
bool classMatches(float v) {
  const char* c = classOf(v);
  if (selClass == 74) return !strcmp(c, "2S");
  if (selClass == 12) return !strcmp(c, "3S");
  return false;
}

// ---------- 匯流排收發 ----------
// 單線半雙工:送出去的資料自己也會聽到(回音),先讀掉再等馬達的回應。
// expect = 預期回應長度(0 = 不等回應)。回傳 true = 收到完整、檢查碼對、命令對的回應。
static size_t lastGot = 0;   // 最近一次 transact 實際收到幾個位元組(除錯用)
bool transact(const uint8_t* tx, size_t n, size_t expect, uint8_t* rx, uint32_t timeoutMs) {
  while (Serial1.available()) Serial1.read();
  ledSet(true);
  Serial1.write(tx, n);
  Serial1.flush();
  uint32_t t0 = millis();
  size_t e = 0;
  bool same = true;
  while (e < n && (millis() - t0) < 5) {
    if (Serial1.available()) { uint8_t c = Serial1.read(); if (c != tx[e]) same = false; e++; }
  }
  lastEchoOk = (e == n) && same;
  bool ok = false;
  if (expect > 0) {
    size_t got = 0;
    t0 = millis();
    while (got < expect && (millis() - t0) < timeoutMs) {
      if (Serial1.available()) rx[got++] = Serial1.read();
    }
    lastGot = got;
    ok = (got == expect) && servobus::checksum(rx, expect - 1) == rx[expect - 1] && rx[0] == tx[0] && rx[1] == tx[1];
  }
  ledSet(false);
  return ok;
}

bool pingId(uint8_t id) {
  uint8_t tx[12], rx[8];
  size_t n = servobus::ping(id, tx);
  return n && transact(tx, n, 3, rx, 4);
}

bool readReg(uint8_t id, uint8_t addr, uint16_t& val) {
  uint8_t tx[12], rx[16];
  size_t n = servobus::readReg(id, addr, 1, tx);
  size_t expect = servobus::readRegResponseLen(1);
  if (!n || !transact(tx, n, expect, rx, 8) || rx[2] != 3) return false;
  val = ((uint16_t)rx[3] << 8) | rx[4];
  return true;
}

// 掃描 1~127 號,回傳找到的數量(最多記錄前 maxFound 個)
int scanBus(uint8_t* found, int maxFound) {
  int cnt = 0;
  for (int id = 1; id <= 127; id++) {
    if (pingId((uint8_t)id)) { if (cnt < maxFound) found[cnt] = (uint8_t)id; cnt++; }
    delay(0);
  }
  return cnt;
}

bool movePos(uint8_t id, uint16_t pos, uint16_t timeMs) {
  uint8_t tx[12], rx[8];
  size_t n = servobus::setPosition(id, pos, timeMs, tx);
  return n && transact(tx, n, 3, rx, 6);
}

// ---------- JSON ----------
String esc(const String& s) { String o; for (char c : s) { if (c == '"' || c == '\\') o += '\\'; o += c; } return o; }

// 指令有兩個來源:WiFi 網頁,或 USB 線(電腦版工具)。同一組處理函式兩邊共用。
static bool viaUsb = false;   // true = 現在這個指令是從 USB 線來的,回應也要印回 USB
static String usbQuery;       // USB 指令「?」後面的參數,例如 id=1&delta=100
static String usbTag;         // 電腦版給每個指令的編號(參數 _t),回應時原樣帶回,電腦才分得出是哪一個指令的回應
bool hasArg(const char* name) {
  if (!viaUsb) return server.hasArg(name);
  return (String("&") + usbQuery).indexOf(String("&") + name + "=") >= 0;
}
String urlDecode(const String& v) {
  String o;
  for (unsigned i = 0; i < v.length(); i++) {
    char c = v[i];
    if (c == '+') o += ' ';
    else if (c == '%' && i + 2 < v.length()) { o += (char)strtol(v.substring(i + 1, i + 3).c_str(), nullptr, 16); i += 2; }
    else o += c;
  }
  return o;
}
String argStr(const char* name) {
  if (!viaUsb) return server.arg(name);
  String q = String("&") + usbQuery, key = String("&") + name + "=";
  int i = q.indexOf(key);
  if (i < 0) return "";
  int a = i + key.length(), b = q.indexOf('&', a);
  return urlDecode(b < 0 ? q.substring(a) : q.substring(a, b));
}

void sendJson(const String& body) {
  if (viaUsb) {                // 「@」開頭,電腦版才分得出這行是回應(不是開機訊息)
    Serial.print('@');
    if (usbTag.length()) { Serial.print('#'); Serial.print(usbTag); Serial.print(' '); }
    Serial.println(body);
    // ESP32-C3 內建 USB 有時把最後一小段留在緩衝區,等電腦下次送資料才送出(2026-10-14 實測),所以要推一下。
    // 不能用 Serial.flush():電腦沒在收的時候(例如電腦版剛好被關掉)它會永遠等下去,整個模組卡死(也實測過)。
    // 這裡只「叫它送」,不等待。
#if defined(CONFIG_IDF_TARGET_ESP32C3)
    usb_serial_jtag_ll_txfifo_flush();
    usb_serial_jtag_ll_ena_intr_mask(USB_SERIAL_JTAG_INTR_SERIAL_IN_EMPTY);
#endif
  }
  else server.send(200, "application/json; charset=utf-8", body);
}
void sendResult(bool ok, const String& msg) { sendJson(String("{\"ok\":") + (ok ? "true" : "false") + ",\"msg\":\"" + esc(msg) + "\"}"); }

int argInt(const char* name, int def) { return hasArg(name) ? argStr(name).toInt() : def; }

// ---------- 來回掃(讓潤滑油平均)----------
// 在 loop() 裡不斷地「到左端 → 到右端 → 到左端…」,直到按停止。不會卡住網頁。
struct Sweep {
  bool on = false;
  uint8_t id = 0;
  uint16_t lo = 900, hi = 2100, timeMs = 1500;
  bool toHi = false;           // 下一個要去的是右端(true)還是左端(false)
  uint32_t nextAt = 0, startedAt = 0, maxMs = 0, legs = 0;
  int fails = 0;
  String reason = "";
  bool all = false;            // true = 全部馬達一起轉(不管 ID)
  String acks = "";            // 全部模式:最近一趟有回應的號碼(最多列 20 個)
  int nAck = 0;                //           有回應的號碼個數
} sw;

// 來回掃進行中,匯流排只給它用(不然兩邊搶著送封包)。回傳 true = 已經回報「請先停止」
bool busBusy() {
  if (sw.on) { sendResult(false, "正在來回掃,請先按「停止」"); return true; }
  return false;
}

// ---------- API ----------
void handleState() {
  float v = readVbat();
  String s = String("{\"vbat\":") + String(v, 2) + ",\"cls\":\"" + classOf(v) + "\",\"sel\":" + selClass +
             ",\"match\":" + (classMatches(v) ? "true" : "false") + ",\"low\":" + (lowBattery(v) ? "true" : "false") +
             ",\"echo\":" + (lastEchoOk ? "true" : "false") + "}";
  sendJson(s);
}

void handleClass() {
  int c = argInt("c", 0);
  selClass = (c == 74 || c == 12) ? c : 0;
  sendResult(true, "ok");
}

void handleCal() {
  float real = argStr("v").toFloat();
  if (real < 3.0f || real > 14.0f) { sendResult(false, "請輸入用三用電表量到的電池電壓(3~14 V)"); return; }
  vbatCal = 1.0f;
  float raw = readVbat();
  vbatCal = real / raw;
  prefs.putFloat("vcal", vbatCal);
  sendResult(true, String("電池電壓已校正,係數 ") + String(vbatCal, 3));
}

void handleScan() {
  uint8_t ids[16];
  int n = scanBus(ids, 16);
  String s = String("{\"echo\":") + (lastEchoOk ? "true" : "false") + ",\"ids\":[";
  for (int i = 0; i < n && i < 16; i++) { if (i) s += ","; s += ids[i]; }
  s += "]}";
  sendJson(s);
}

void handleStatus() {
  int id = argInt("id", 0);
  if (!servobus::validId((uint8_t)id)) { sendResult(false, "ID 不合法(1~127)"); return; }
  uint16_t pos, volt, cur, temp, idr;
  if (!readReg(id, servobus::REG_CURR_POS, pos)) { sendResult(false, String("讀不到 ") + id + " 號馬達的位置(沒接好、沒通電,或 ID 不對)"); return; }
  if (!readReg(id, servobus::REG_VOLTAGE, volt)) volt = 0xFFFF;
  if (!readReg(id, servobus::REG_CURRENT, cur)) cur = 0xFFFF;
  if (!readReg(id, servobus::REG_TEMP, temp)) temp = 0xFFFF;
  if (!readReg(id, servobus::REG_ID, idr)) idr = 0xFFFF;
  uint16_t limL = 0, limR = 0;   // 馬達自己設定的左右端點(暫存器 33、34),拿來當來回掃範圍的參考
  if (!readReg(id, 33, limL)) limL = 0;
  if (!readReg(id, 34, limR)) limR = 0;
  sendJson(String("{\"ok\":true,\"pos\":") + pos + ",\"volt\":" + volt + ",\"cur\":" + cur + ",\"temp\":" + temp + ",\"idreg\":" + idr + ",\"limL\":" + limL + ",\"limR\":" + limR + "}");
}

void handleSetId() {
  int newId = argInt("new", 0);
  if (newId < 1 || newId > 32) { sendResult(false, "新 ID 要在 1~32 之間"); return; }
  uint8_t ids[8];
  int n = scanBus(ids, 8);
  if (!lastEchoOk) { sendResult(false, "沒聽到自己送出的資料(回音),請檢查 RX 接線和分壓電阻"); return; }
  if (n == 0) { sendResult(false, "找不到馬達:請確認馬達有接、有通電(電池已接),再按掃描"); return; }
  if (n > 1) { sendResult(false, String("匯流排上有 ") + n + " 顆馬達,為了安全只能一次改一顆,請只接一顆"); return; }
  uint8_t oldId = ids[0];
  if (oldId == newId) { sendResult(true, String("這顆本來就是 ") + newId + " 號,不用改"); return; }
  uint8_t tx[12], rx[16];
  // 先讀目前的 ID 暫存器:高位元組(廣播 ID)原樣保留,只改低位元組
  uint16_t before = 0;
  bool gotBefore = readReg(oldId, servobus::REG_ID, before);
  uint16_t value = (uint16_t)((gotBefore ? (before & 0xFF00) : 0) | newId);
  size_t len = servobus::writeReg(oldId, servobus::REG_ID, value, tx);
  if (!len) { sendResult(false, "內部錯誤:封包產生失敗"); return; }
  bool wrOk = transact(tx, len, 3, rx, 20);   // 回應可能用舊 ID 也可能沒有
  size_t wrGot = lastGot;
  char hx[40] = "";
  for (size_t i = 0; i < wrGot && i < 8; i++) { char b[4]; snprintf(b, sizeof(b), "%02X ", rx[i]); strcat(hx, b); }
  // 原廠工具固定等 1.5 秒;這裡改成每 40 ms 試一次,新 ID 一回應就結束(最多等 1.5 秒),再多等 200 ms 讓馬達存好
  uint16_t idr = 0;
  bool seesNew = false;
  for (uint32_t t0 = millis(); millis() - t0 < 1500 && !seesNew; delay(40))
    seesNew = pingId((uint8_t)newId) && readReg((uint8_t)newId, servobus::REG_ID, idr) && (idr & 0xFF) == newId;
  if (seesNew) delay(200);
  bool oldGone = !pingId(oldId);
  String found = "";
  int n2 = 0;
  if (!(seesNew && oldGone)) {          // 只有失敗才全掃一遍,把現況給你看
    uint8_t ids2[8];
    n2 = scanBus(ids2, 8);
    for (int i = 0; i < n2 && i < 8; i++) { if (i) found += ","; found += ids2[i]; }
  }
  String dbg = String("(除錯:舊 ID ") + oldId + ",寫入前暫存器 " + (gotBefore ? String(before, HEX) : String("讀不到")) + ",寫入值 " + String(value, HEX) +
               ",寫入回應 " + (wrOk ? "OK " : "無/錯 ") + wrGot + " 位元組 [" + hx + "],寫入後掃到 [" + (n2 ? found : String("沒有")) + "])";
  if (seesNew && oldGone) sendResult(true, String("成功:原本 ") + oldId + " 號 → 現在是 " + newId + " 號,可以裝上機器人了");
  else if (seesNew) sendResult(false, String("新 ID ") + newId + " 有回應,但舊 ID " + oldId + " 也還有回應。" + dbg);
  else sendResult(false, String("寫入後沒讀回新 ID ") + newId + "。" + dbg);
}

void handleTest() {
  int id = argInt("id", 0);
  int amp = constrain(argInt("amp", 150), 50, 250);
  if (!servobus::validId((uint8_t)id)) { sendResult(false, "ID 不合法(1~127)"); return; }
  float v = readVbat();
  if (!classMatches(v)) { sendResult(false, "電池電壓和你選的馬達電壓不符,或還沒選,不能測試轉動"); return; }
  if (lowBattery(v)) { sendResult(false, "電池電量偏低,不能測試轉動"); return; }
  uint16_t p0;
  if (!readReg((uint8_t)id, servobus::REG_CURR_POS, p0)) { sendResult(false, String("讀不到 ") + id + " 號馬達的位置,不能測試"); return; }
  if (p0 < servobus::POS_MIN || p0 > servobus::POS_MAX) { sendResult(false, String("馬達回報位置 ") + p0 + " 超出 800~2200,不測試"); return; }
  int dir = (p0 > 1500) ? -1 : 1;
  uint16_t target = servobus::clampPos((int)p0 + dir * amp);
  uint16_t p1 = 0, p2 = 0;
  bool a = movePos((uint8_t)id, target, 500);
  delay(800);
  bool r1 = readReg((uint8_t)id, servobus::REG_CURR_POS, p1);
  bool b = movePos((uint8_t)id, p0, 500);
  delay(800);
  bool r2 = readReg((uint8_t)id, servobus::REG_CURR_POS, p2);
  int want = abs((int)target - (int)p0);
  bool moved = r1 && want > 0 && abs((int)p1 - (int)p0) >= want * 6 / 10;
  bool back = r2 && abs((int)p2 - (int)p0) <= 20;
  String m = String("起點 ") + p0 + " → 目標 " + target + " → 實際 " + (r1 ? String(p1) : String("讀不到")) + " → 回到 " + (r2 ? String(p2) : String("讀不到"));
  if (!a || !b) m += "(馬達沒回應位置指令)";
  if (moved && back) sendResult(true, "轉動正常。" + m);
  else sendResult(false, "轉動不如預期。" + m);
}

void handleRelease() {
  int id = argInt("id", 0);
  if (!servobus::validId((uint8_t)id)) { sendResult(false, "ID 不合法(1~127)"); return; }
  bool ok = movePos((uint8_t)id, 0, 0);   // 原廠工具的「Motor Release」= 目標位置 0
  sendResult(ok, ok ? String("已放鬆 ") + id + " 號馬達" : "沒有回應");
}

// ---------- 微調 ±100 ----------
// 每按一次動 100 單位(約 16°),不限次數,位置夾在 800~2200。
// 另外記下「第一次按的時候馬達所在的位置」當原位,「回原位」可以回去。
static int gHomeId = -1;
static uint16_t gHomePos = 0;

// 回傳 true = 可以動;false = 已經用 sendResult 回報原因
bool moveGate(int id, uint16_t& cur) {
  if (!servobus::validId((uint8_t)id)) { sendResult(false, "ID 不合法(1~127)"); return false; }
  float v = readVbat();
  if (!classMatches(v)) { sendResult(false, "電池電壓和你選的馬達電壓不符,或還沒選,不能動"); return false; }
  if (lowBattery(v)) { sendResult(false, "電池電量偏低,不能動"); return false; }
  if (!readReg((uint8_t)id, servobus::REG_CURR_POS, cur)) { sendResult(false, String("讀不到 ") + id + " 號馬達的位置(沒接好、沒通電,或 ID 不對)"); return false; }
  if (cur < servobus::POS_MIN || cur > servobus::POS_MAX) { sendResult(false, String("馬達回報位置 ") + cur + " 超出 800~2200,不動"); return false; }
  return true;
}

// 快速版:按一次就送一個指令,不等馬達做完、不讀回位置,所以可以一直連按,目標一路累加。
// 目標位置記在 gTgt(第一次或換馬達時才讀一次馬達的實際位置),夾在 800~2200。
static int gTgtId = -1;
static uint16_t gTgt = 1500;

bool batteryGate() {
  float v = readVbat();
  if (!classMatches(v)) { sendResult(false, "電池電壓和你選的馬達電壓不符,或還沒選,不能動"); return false; }
  if (lowBattery(v)) { sendResult(false, "電池電量偏低,不能動"); return false; }
  return true;
}

void sendTarget(bool ok, const String& msg) {
  sendJson(String("{\"ok\":") + (ok ? "true" : "false") + ",\"msg\":\"" + esc(msg) + "\",\"target\":" + gTgt + "}");
}

void handleMove() {
  int id = argInt("id", 0);
  int delta = constrain(argInt("delta", 0), -100, 100);
  if (busBusy()) return;
  if (!servobus::validId((uint8_t)id)) { sendResult(false, "ID 不合法(1~127)"); return; }
  if (!batteryGate()) return;
  if (gTgtId != id) {
    uint16_t cur;
    if (!readReg((uint8_t)id, servobus::REG_CURR_POS, cur)) { sendResult(false, String("讀不到 ") + id + " 號馬達的位置(沒接好、沒通電,或 ID 不對)"); return; }
    if (cur < servobus::POS_MIN || cur > servobus::POS_MAX) { sendResult(false, String("馬達回報位置 ") + cur + " 超出 800~2200,不動"); return; }
    gTgt = cur; gTgtId = id;
  }
  uint16_t before = gTgt;
  gTgt = servobus::clampPos((int)gTgt + delta);
  bool ok = movePos((uint8_t)id, gTgt, 120);
  if (!ok) gTgtId = -1;                        // 沒回應:下次重新讀實際位置
  String m = String("目標 ") + gTgt;
  if (gTgt == before && delta != 0) m += "(已到極限)";
  else if (!ok) m += "(馬達沒回應)";
  sendTarget(ok, m);
}

void handleHome() {
  int id = argInt("id", 0);
  uint16_t cur;
  if (!moveGate(id, cur)) return;
  if (gHomeId != id) { sendResult(false, "還沒有記錄這顆馬達的原位(先按一次 −100 或 +100)"); return; }
  bool ok = movePos((uint8_t)id, gHomePos, 300);
  delay(450);
  uint16_t p1 = 0;
  bool r = readReg((uint8_t)id, servobus::REG_CURR_POS, p1);
  String m = String("回原位 ") + gHomePos + (r ? String(",實際 ") + p1 : String(",讀不到實際位置")) + (ok ? "" : "(馬達沒回應位置指令)");
  sendJson(String("{\"ok\":") + ((ok && r) ? "true" : "false") + ",\"msg\":\"" + esc(m) + "\",\"pos\":" + (r ? p1 : 0) + ",\"home\":" + gHomePos + ",\"off\":" + (r ? (int)p1 - (int)gHomePos : 0) + "}");
}

void handleCenter() {
  int id = argInt("id", 0);
  if (busBusy()) return;
  if (!servobus::validId((uint8_t)id)) { sendResult(false, "ID 不合法(1~127)"); return; }
  if (!batteryGate()) return;
  bool ok = movePos((uint8_t)id, 1500, 400);
  if (ok) { gTgt = 1500; gTgtId = id; } else gTgtId = -1;
  sendTarget(ok, ok ? String("回中點 1500") : String("馬達沒回應"));
}

// ---------- 來回掃的啟動 / 停止 / 狀態 ----------
// 不管 ID:對 1~127 號每一個號碼各送一次位置指令(不送廣播,也不寫任何設定)。
// 同號的多顆馬達會一起動。每個號碼等回應最多 3 ms,避免馬達回話和下一個指令撞在一起;一輪約 0.4 秒。
// 回傳這一輪有回應的號碼(多顆同號時回應會互相干擾,清單可能不完整)。
String moveAllIds(uint16_t pos, uint16_t timeMs, int& acked) {
  String list = "";
  acked = 0;
  uint8_t tx[12], rx[8];
  for (int id = 1; id <= 127; id++) {
    size_t n = servobus::setPosition((uint8_t)id, pos, timeMs, tx);
    if (n && transact(tx, n, 3, rx, 3)) {
      if (++acked <= 20) { if (list.length()) list += ","; list += id; }
    }
    delay(0);
  }
  return list;
}

void sweepStop(const String& why, bool goCenter) {
  if (!sw.on) return;
  sw.on = false;
  sw.reason = why;
  uint16_t mid = (uint16_t)((sw.lo + sw.hi) / 2);
  if (goCenter) {                                   // 停下來時慢慢回到兩端的中間
    if (sw.all) { int a; moveAllIds(mid, 600, a); }
    else movePos(sw.id, mid, 600);
  }
}

void sweepTick() {
  if (!sw.on) return;
  uint32_t now = millis();
  if ((int32_t)(now - sw.nextAt) < 0) return;
  if (sw.maxMs && now - sw.startedAt > sw.maxMs) { sweepStop("時間到,已自動停止", true); return; }
  float v = readVbat();
  if (!classMatches(v)) { sweepStop("電池電壓和選的馬達不符,已自動停止", false); return; }
  if (lowBattery(v)) { sweepStop("電池電量偏低,已自動停止", true); return; }
  uint16_t tgt = sw.toHi ? sw.hi : sw.lo;
  if (sw.all) {
    // 全部模式不因為沒回應而停:多顆同號時回應本來就會互相干擾。以眼睛看有沒有轉為準
    sw.acks = moveAllIds(tgt, sw.timeMs, sw.nAck);
    now = millis();                    // 送完一輪要 0.4 秒,從送完才開始算
  } else {
    bool ok = movePos(sw.id, tgt, sw.timeMs);
    if (ok) sw.fails = 0;
    else if (++sw.fails >= 3) { sweepStop("馬達連續 3 次沒有回應,已自動停止", false); return; }
  }
  sw.legs++;
  sw.toHi = !sw.toHi;
  sw.nextAt = now + sw.timeMs + 250;   // 多等 0.25 秒讓馬達到位
}

void handleSweepStart() {
  if (sw.on) { sendResult(false, "已經在來回掃了"); return; }
  int id = argInt("id", 0);
  bool all = argInt("all", 0) == 1;   // 1 = 全部馬達一起轉,不管 ID
  int lo = servobus::clampPos(argInt("lo", 900));
  int hi = servobus::clampPos(argInt("hi", 2100));
  float t = hasArg("t") ? argStr("t").toFloat() : 1.5f;
  int timeMs = constrain((int)(t * 1000), 800, 5000);
  int mins = constrain(argInt("min", 0), 0, 600);
  if (hi - lo < 200) { sendResult(false, "左端和右端至少要差 200 單位"); return; }
  if (all) { if (!batteryGate()) return; }   // 全部模式沒有特定一顆可以先讀位置,只檢查電池
  else { uint16_t cur; if (!moveGate(id, cur)) return; }
  sw.all = all; sw.acks = ""; sw.nAck = 0;
  sw.id = (uint8_t)id; sw.lo = (uint16_t)lo; sw.hi = (uint16_t)hi; sw.timeMs = (uint16_t)timeMs;
  sw.toHi = false; sw.legs = 0; sw.fails = 0; sw.reason = "";
  sw.startedAt = millis(); sw.nextAt = sw.startedAt;
  sw.maxMs = (uint32_t)mins * 60000UL;
  sw.on = true;
  sendResult(true, String(all ? "全部馬達一起來回轉:" : "開始來回掃:") + lo + " ↔ " + hi + ",單程 " + String(timeMs / 1000.0f, 1) + " 秒" + (mins ? String(",最長 ") + mins + " 分鐘" : String(",不限時,按停止才停")));
}

void handleSweepStop() {
  if (!sw.on) { sendResult(true, "目前沒有在來回掃"); return; }
  sweepStop("手動停止", true);
  sendResult(true, "已停止,馬達回到中間位置");
}

void handleSweepState() {
  uint32_t el = sw.on ? (millis() - sw.startedAt) / 1000 : 0;
  sendJson(String("{\"on\":") + (sw.on ? "true" : "false") + ",\"cycles\":" + (sw.legs / 2) + ",\"toHi\":" + (sw.toHi ? "true" : "false") +
           ",\"sec\":" + el + ",\"lo\":" + sw.lo + ",\"hi\":" + sw.hi + ",\"reason\":\"" + esc(sw.reason) + "\"" +
           ",\"all\":" + (sw.all ? "true" : "false") + ",\"acks\":\"" + sw.acks + "\",\"nack\":" + sw.nAck + "}");
}

// ---------- 多顆馬達(電腦版「多顆馬達」頁用)----------
// 全部改成同一個 ID。不送廣播:對 1~127 每個號碼各寫一次 ID 暫存器。
// 暫存器高位元組(廣播 ID)要保留:掃描得到的馬達用它自己的值,其他號碼(沒有馬達,或多顆同號回應互相干擾)用第一顆讀到的值。
void handleSetAll() {
  int newId = argInt("new", 0);
  if (newId < 1 || newId > 32) { sendResult(false, "新 ID 要在 1~32 之間"); return; }
  uint8_t ids[16];
  int n = scanBus(ids, 16);
  if (!lastEchoOk) { sendResult(false, "沒聽到自己送出的資料(回音),請檢查 RX 接線和分壓電阻"); return; }
  uint16_t regs[16];
  bool got[16];
  int firstGot = -1;
  String before = "";
  for (int i = 0; i < n && i < 16; i++) {
    got[i] = readReg(ids[i], servobus::REG_ID, regs[i]);
    if (got[i] && firstGot < 0) firstGot = i;
    if (i) before += ","; before += ids[i];
  }
  if (n == 0) { sendResult(false, "找不到馬達:請確認馬達有接、有通電(電池已接)"); return; }
  if (firstGot < 0) { sendResult(false, "讀不到任何一顆馬達的 ID 設定,不能全部改"); return; }
  uint16_t hiDefault = regs[firstGot] & 0xFF00;
  uint8_t tx[12], rx[8];
  for (int id = 1; id <= 127; id++) {
    if (id == newId) continue;                     // 本來就是這個號碼,不用寫
    uint16_t hi = hiDefault;
    for (int i = 0; i < n && i < 16; i++) if (ids[i] == id && got[i]) hi = regs[i] & 0xFF00;
    size_t len = servobus::writeReg((uint8_t)id, servobus::REG_ID, (uint16_t)(hi | newId), tx);
    if (len) transact(tx, len, 3, rx, 3);
    delay(0);
  }
  delay(500);                                      // 讓馬達存好
  uint8_t ids2[16];
  int n2 = scanBus(ids2, 16);
  String after = "";
  bool onlyNew = n2 >= 1;
  for (int i = 0; i < n2 && i < 16; i++) { if (i) after += ","; after += ids2[i]; if (ids2[i] != newId) onlyNew = false; }
  gTgtId = -1; gHomeId = -1;
  if (onlyNew) sendResult(true, String("完成:原本掃到 ") + before + " 號 → 現在都是 " + newId + " 號(多顆同號時掃描只看得到一個號碼)");
  else sendResult(false, String("改完掃到 ") + (n2 ? after : String("沒有馬達")) + ",不是全部都變成 " + newId + " 號。請再按一次,或檢查接線");
}

// 認馬達:讓指定號碼的馬達左右動一下(80 單位)再回原位,方便認出是哪一顆
void handleWiggle() {
  int id = argInt("id", 0);
  uint16_t p0;
  if (!moveGate(id, p0)) return;
  gTgtId = -1;
  int dir = (p0 > 1500) ? -1 : 1;
  bool a = movePos((uint8_t)id, servobus::clampPos((int)p0 + dir * 80), 200);
  delay(300);
  bool b = movePos((uint8_t)id, p0, 200);
  delay(250);
  sendResult(a && b, (a && b) ? String(id) + " 號動了一下" : String(id) + " 號沒有回應位置指令");
}

// 全部一起轉到同一個位置(不管 ID)
void handleAllPos() {
  if (!batteryGate()) return;
  uint16_t pos = servobus::clampPos(argInt("pos", 1500));
  int t = constrain(argInt("t", 600), 100, 3000);
  gTgtId = -1;
  int acked;
  String list = moveAllIds(pos, (uint16_t)t, acked);
  sendResult(true, String("全部轉到 ") + pos + (acked ? String(",有回應:") + list : String(",沒有收到回應(多顆同號時常見)")));
}

// 全部放鬆:位置 0 = 不出力(原廠工具的 Motor Release)
void handleAllRelease() {
  gTgtId = -1;
  int acked;
  moveAllIds(0, 0, acked);
  sendResult(true, String("已讓全部馬達放鬆") + (acked ? "" : "(沒有收到回應,多顆同號時常見)"));
}

// ---------- 網路設定 ----------
bool netNameOk(const String& ss, const String& pw, bool needPass) {
  if (ss.length() == 0 || ss.length() > 32) { sendResult(false, "網路名稱要 1~32 個字元(中文一個字算 3 個)"); return false; }
  if ((needPass || pw.length() > 0) && (pw.length() < 8 || pw.length() > 63)) { sendResult(false, needPass ? "密碼要 8~63 個字元" : "密碼要 8~63 個字元(沒有密碼就留空)"); return false; }
  return true;
}
void handleNet() {
  bool up = staSsid.length() && WiFi.status() == WL_CONNECTED;
  sendJson(String("{\"ap\":\"") + esc(apSsid) + "\",\"ssid\":\"" + esc(staSsid) + "\",\"connected\":" + (up ? "true" : "false") +
           ",\"ip\":\"" + (up ? WiFi.localIP().toString() : String("")) + "\",\"host\":\"servoid.local\",\"skip\":" + (prefs.getBool("netskip", false) ? "true" : "false") + "}");
}
// 設定要去找的網路(只能一個,新的蓋掉舊的),2 秒後重新開機生效。自己的熱點照樣開著
void handleNetSet() {
  String ss = argStr("ssid"), pw = argStr("pass");
  if (!netNameOk(ss, pw, false)) return;
  prefs.putString("ssid", ss);
  prefs.putString("pass", pw);
  restartAt = millis() + 2000;
  sendResult(true, String("已設定要找的網路「") + ss + "」,模組 2 秒後重新開機。連上後,在「" + ss + "」上開 http://servoid.local 也能用;" + apSsid + " 熱點照樣開著");
}
void handleNetClear() {
  prefs.remove("ssid"); prefs.remove("pass");
  restartAt = millis() + 2000;
  sendResult(true, "已清除要找的網路,模組 2 秒後重新開機");
}
// 第一次開網頁會問要不要設定;選「不要」就記住,不再問
void handleNetSkip() { prefs.putBool("netskip", true); sendResult(true, "好,先不設定(之後可以在「網路設定」裡設定)"); }
// 掃描附近的網路,讓使用者用點的選名稱,不用自己打
void handleNetScan() {
  int n = WiFi.scanNetworks();
  if (n < 0 && staSsid.length()) {
    // 正在連「要找的網路」(例如熱點沒開、一直重試)時掃描會失敗:先暫停連線,掃完再接著連(2026-10-14 實測)
    WiFi.setAutoReconnect(false);
    WiFi.disconnect(false, false);
    delay(200);
    n = WiFi.scanNetworks();
    WiFi.setAutoReconnect(true);
    WiFi.begin(staSsid.c_str(), staPass.c_str());
  }
  String o = "{\"ok\":true,\"list\":[";
  int shown = 0;
  for (int i = 0; i < n && shown < 20; i++) {
    String ss = WiFi.SSID(i);
    if (!ss.length() || ss == apSsid) continue;
    bool dup = false;
    for (int k = 0; k < i; k++) if (WiFi.SSID(k) == ss) { dup = true; break; }
    if (dup) continue;
    if (shown++) o += ",";
    o += String("{\"s\":\"") + esc(ss) + "\",\"r\":" + WiFi.RSSI(i) + "}";
  }
  o += "]}";
  WiFi.scanDelete();
  sendJson(o);
}
// 出貨用:改這塊板子自己熱點的名稱 / 密碼。只有 USB 能改(買家從網頁改不到)
void handleApSet() {
  if (!viaUsb) { sendResult(false, "這個只能用 USB 設定"); return; }
  String ss = argStr("ssid"), pw = argStr("pass");
  if (!netNameOk(ss, pw, true)) return;
  prefs.putString("apssid", ss);
  prefs.putString("appass", pw);
  prefs.putString("apfac", pw);               // 出廠密碼:按住 BOOT 3 秒會改回這個
  prefs.remove("netskip");                    // 新買家第一次開網頁時要再問一次
  prefs.remove("ssid"); prefs.remove("pass"); // 前一個人設定的「要找的網路」一起清掉
  restartAt = millis() + 1500;
  sendResult(true, String("熱點改成「") + ss + "」,模組 1.5 秒後重新開機");
}
// 買家改自己熱點的密碼(名稱不能改,出貨時定好)。網頁和 USB 都可以
void handleApPass() {
  String pw = argStr("pass");
  if (pw.length() < 8 || pw.length() > 63) { sendResult(false, "密碼要 8~63 個字元"); return; }
  prefs.putString("appass", pw);
  restartAt = millis() + 2000;
  sendResult(true, String("熱點「") + apSsid + "」的密碼已改,模組 2 秒後重新開機,請用新密碼重新連線");
}

// 連上 / 斷線時印出位址;按住 BOOT 3 秒 = 熱點密碼回到出廠密碼;改完設定要重新開機
void netTick() {
  static bool wasUp = false;
  static uint32_t pressAt = 0;
  if (digitalRead(PIN_BOOT) == LOW) {
    if (!pressAt) pressAt = millis() | 1;
    else if (millis() - pressAt > 3000 && !restartAt) {
      String fac = prefs.getString("apfac", AP_PASS);
      prefs.putString("appass", fac);
      Serial.printf("按住 BOOT 3 秒:熱點「%s」的密碼回到出廠密碼,重新開機\n", apSsid.c_str());
      for (int i = 0; i < 6; i++) { ledSet(i % 2 == 0); delay(120); }   // LED 快閃 3 下表示收到
      restartAt = millis() + 300;
    }
  } else pressAt = 0;
  if (staSsid.length()) {
    bool up = WiFi.status() == WL_CONNECTED;
    if (up != wasUp) {
      wasUp = up;
      if (up) Serial.printf("已連上「%s」,開 http://servoid.local 或 http://%s\n", staSsid.c_str(), WiFi.localIP().toString().c_str());
      else Serial.printf("和「%s」斷線,持續重試\n", staSsid.c_str());
    }
  }
  if (restartAt && (int32_t)(millis() - restartAt) >= 0) ESP.restart();
}

// ---------- 指令表:WiFi 網頁和 USB 線共用 ----------
// bus = true:會用到匯流排,來回掃進行中時一律先擋下來(要先按停止)
struct Route { const char* path; void (*fn)(); bool bus; };
static const Route ROUTES[] = {
  {"/api/state", handleState, false},
  {"/api/class", handleClass, false},
  {"/api/cal", handleCal, false},
  {"/api/scan", handleScan, true},
  {"/api/status", handleStatus, true},
  {"/api/setid", handleSetId, true},
  {"/api/test", handleTest, true},
  {"/api/release", handleRelease, true},
  {"/api/move", handleMove, true},
  {"/api/home", handleHome, true},
  {"/api/center", handleCenter, true},
  {"/api/setall", handleSetAll, true},
  {"/api/wiggle", handleWiggle, true},
  {"/api/allpos", handleAllPos, true},
  {"/api/allrelease", handleAllRelease, true},
  {"/api/sweep", handleSweepStart, false},
  {"/api/sweepstop", handleSweepStop, false},
  {"/api/sweepstate", handleSweepState, false},
  {"/api/net", handleNet, false},
  {"/api/netset", handleNetSet, false},
  {"/api/netclear", handleNetClear, false},
  {"/api/netskip", handleNetSkip, false},
  {"/api/netscan", handleNetScan, false},
  {"/api/apset", handleApSet, false},
  {"/api/appass", handleApPass, false},
};
static const int N_ROUTES = sizeof(ROUTES) / sizeof(ROUTES[0]);
void runRoute(int i) { if (!ROUTES[i].bus || !busBusy()) ROUTES[i].fn(); }

// ---------- USB 線指令 ----------
// 從 USB 序列埠讀一行(例如 /api/scan),執行同一個處理函式,回應印回 USB。
void usbTick() {
  static String line;
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c != '\n') { if (c != '\r' && line.length() < 120) line += c; continue; }
    line.trim();
    if (line.length()) {
      int q = line.indexOf('?');
      String path = q < 0 ? line : line.substring(0, q);
      usbQuery = q < 0 ? String("") : line.substring(q + 1);
      viaUsb = true;
      usbTag = argStr("_t");
      bool known = false;
      for (int i = 0; i < N_ROUTES; i++) if (path == ROUTES[i].path) { known = true; runRoute(i); break; }
      if (!known) sendResult(false, "不認得的指令");
      viaUsb = false;
    }
    line = "";
  }
}

// ---------- 開機自我檢查:封包要和 PDF / 原廠工具的範例一模一樣 ----------
bool eq(const uint8_t* a, const uint8_t* b, size_t n) { return memcmp(a, b, n) == 0; }
void selfTest() {
  uint8_t t[16];
  bool ok = true;
  { const uint8_t e[] = {0x81, 0x81, 0x02}; ok &= servobus::ping(1, t) == 3 && eq(t, e, 3); }
  { const uint8_t e[] = {0x81, 0x83, 0x05, 0x05, 0xDC, 0x00, 0x64, 0x4E}; ok &= servobus::setPosition(1, 1500, 100, t) == 8 && eq(t, e, 8); }
  { const uint8_t e[] = {0x81, 0x91, 0x01, 0x05, 0x18}; ok &= servobus::readReg(1, 5, 1, t) == 5 && eq(t, e, 5); }
  { const uint8_t e[] = {0x81, 0x91, 0x04, 0x00, 0x16}; ok &= servobus::readReg(1, 0, 4, t) == 5 && eq(t, e, 5); }
  { const uint8_t e[] = {0x81, 0x92, 0x00, 0x00, 0x00, 0x13}; ok &= servobus::writeReg(1, 0, 0, t) == 6 && eq(t, e, 6); }
  { const uint8_t e[] = {0x81, 0x92, 0x20, 0x00, 0x05, 0x38}; ok &= servobus::writeReg(1, 32, 5, t) == 6 && eq(t, e, 6); }
  ok &= servobus::writeReg(0, 32, 5, t) == 0;        // 廣播一定被拒絕
  ok &= servobus::setPosition(0, 1500, 100, t) == 0;
  Serial.println(ok ? "[自我檢查] 封包格式 全部 PASS" : "[自我檢查] 封包格式 FAIL!請不要接馬達");
}

void setup() {
  Serial.begin(115200);
#if !LED_IS_NEOPIXEL
  pinMode(PIN_LED, OUTPUT);
#endif
  ledSet(false);
  analogReadResolution(12);
  analogSetPinAttenuation(PIN_VBAT, ADC_11db);
  prefs.begin("servoid", false);
  vbatCal = prefs.getFloat("vcal", 1.0f);
  Serial1.begin(BUS_BAUD, SERIAL_8N1, PIN_BUS_RX, PIN_BUS_TX);
  selfTest();

  apSsid = prefs.getString("apssid", "HurtMakerID");   // 預設是自己的板子;賣出去的板子出貨時用 USB 改成買家的
  apPass = prefs.getString("appass", prefs.getString("apfac", AP_PASS));
  pinMode(PIN_BOOT, INPUT_PULLUP);
  staSsid = prefs.getString("ssid", "");
  staPass = prefs.getString("pass", "");
  WiFi.mode(WIFI_AP_STA);                 // 熱點和「要找的網路」同時開(只有一個天線:連上後熱點會換到同一個頻道)
  WiFi.setHostname("servoid");
  WiFi.softAP(apSsid.c_str(), apPass.c_str());
  if (staSsid.length()) { WiFi.setAutoReconnect(true); WiFi.begin(staSsid.c_str(), staPass.c_str()); }
  WiFi.setTxPower(WIFI_POWER_8_5dBm);   // ESP32-C3 SuperMini 常見問題:全功率時熱點不穩 / 消失,降低功率比較穩
  dns.start(53, "*", WiFi.softAPIP());
  MDNS.begin("servoid");                  // 在「要找的網路」上用 http://servoid.local
  MDNS.addService("http", "tcp", 80);
  Serial.printf("WiFi 熱點:%s  密碼:%s  網址:http://%s\n", apSsid.c_str(), apPass.c_str(), WiFi.softAPIP().toString().c_str());
  if (staSsid.length()) Serial.printf("要找的網路:「%s」\n", staSsid.c_str());

  server.on("/", []() {
    server.sendHeader("Cache-Control", "no-store");   // 每次都重新載入,手機才不會一直顯示舊版網頁
    server.send_P(200, "text/html; charset=utf-8", PAGE);
  });
  // 手機連上 WiFi 會先檢查「有沒有網路」;檢查不過,iPhone / Android 可能自己斷開、跳回別的網路。
  // 這裡照它們期待的答案回應,手機就把模組的 WiFi 當成正常網路,不會自己斷開。
  server.on("/hotspot-detect.html", []() { server.send(200, "text/html", "<HTML><HEAD><TITLE>Success</TITLE></HEAD><BODY>Success</BODY></HTML>"); });       // iPhone / iPad / Mac
  server.on("/library/test/success.html", []() { server.send(200, "text/html", "<HTML><HEAD><TITLE>Success</TITLE></HEAD><BODY>Success</BODY></HTML>"); });
  server.on("/generate_204", []() { server.send(204, "text/plain", ""); });                         // Android
  server.on("/gen_204", []() { server.send(204, "text/plain", ""); });
  server.on("/connecttest.txt", []() { server.send(200, "text/plain", "Microsoft Connect Test"); });  // Windows
  server.on("/ncsi.txt", []() { server.send(200, "text/plain", "Microsoft NCSI"); });
  for (int i = 0; i < N_ROUTES; i++) server.on(ROUTES[i].path, [i]() { runRoute(i); });
  server.begin();
}

void loop() {
  server.handleClient();
  dns.processNextRequest();
  usbTick();
  sweepTick();
  netTick();
}
