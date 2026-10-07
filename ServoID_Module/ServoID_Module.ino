/*
  ServoID_Module — 用手機(WiFi 網頁)編伺服馬達 ID、測試轉動
  板子:M5Stack ATOM Lite(esp32:esp32:m5stack_atom)或 Seeed XIAO ESP32-C3(esp32:esp32:XIAO_ESP32C3:CDCOnBoot=cdc)

  用法:模組通電後會開一個 WiFi 熱點「ServoID-XXXX」(密碼見下面 AP_PASS),
        手機連上去,瀏覽器開 http://192.168.4.1
  電路和接線見 README.md。

  安全設計:
   ・永遠不送廣播(ID 0)的寫入指令,避免一次改到所有馬達
   ・改 ID 前先掃描 1~127 號:找到的馬達數量不是剛好 1 顆就拒絕
   ・測試轉動:電池電壓要和你選的馬達電壓(7.4 V / 12 V)相符才允許;位置夾在 800~2200,角度小、空載
   ・馬達電源是電池直通(模組不能切斷),所以要「先接電池看電壓,確認後才插馬達」
*/
#include <WiFi.h>
#include <WebServer.h>
#include <Preferences.h>
#include "servobus.h"
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

static const uint32_t BUS_BAUD = 500000;
static const float VBAT_RATIO = (100000.0f + 22000.0f) / 22000.0f;
static const char* AP_PASS = "servoid1234";  // 至少 8 個字元,想改就改這裡

WebServer server(80);
Preferences prefs;
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
void sendJson(const String& body) { server.send(200, "application/json; charset=utf-8", body); }
void sendResult(bool ok, const String& msg) { sendJson(String("{\"ok\":") + (ok ? "true" : "false") + ",\"msg\":\"" + esc(msg) + "\"}"); }

int argInt(const char* name, int def) { return server.hasArg(name) ? server.arg(name).toInt() : def; }

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
  float real = server.arg("v").toFloat();
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
  uint8_t tx[12], rx[8];
  size_t len = servobus::writeReg(oldId, servobus::REG_ID, (uint16_t)newId, tx);
  if (!len) { sendResult(false, "內部錯誤:封包產生失敗"); return; }
  transact(tx, len, 3, rx, 8);   // 回應可能用舊 ID 也可能沒有,不管它,等一下用讀回確認
  delay(1500);                    // 原廠工具也是等 1.5 秒才確認
  uint16_t idr = 0;
  bool seesNew = pingId((uint8_t)newId) && readReg((uint8_t)newId, servobus::REG_ID, idr) && (idr & 0xFF) == newId;
  bool oldGone = !pingId(oldId);
  if (seesNew && oldGone) sendResult(true, String("成功:原本 ") + oldId + " 號 → 現在是 " + newId + " 號,可以裝上機器人了");
  else if (seesNew) sendResult(false, String("新 ID ") + newId + " 有回應,但舊 ID " + oldId + " 也還有回應,請重新掃描確認");
  else sendResult(false, String("寫入後沒讀回新 ID ") + newId + "。請按掃描看馬達現在是幾號,必要時用原廠工具確認");
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

void handleMove() {
  int id = argInt("id", 0);
  int delta = constrain(argInt("delta", 0), -100, 100);
  uint16_t cur;
  if (!moveGate(id, cur)) return;
  if (gHomeId != id) { gHomeId = id; gHomePos = cur; }   // 換了一顆馬達,重新記原位
  int target = servobus::clampPos((int)cur + delta);
  bool ok = movePos((uint8_t)id, (uint16_t)target, 300);
  delay(450);
  uint16_t p1 = 0;
  bool r = readReg((uint8_t)id, servobus::REG_CURR_POS, p1);
  int off = r ? (int)p1 - (int)gHomePos : 0;
  String m = String("目標 ") + target + (r ? String(",實際 ") + p1 + "(離原位 " + (off >= 0 ? "+" : "") + off + ")" : String(",讀不到實際位置")) + (ok ? "" : "(馬達沒回應位置指令)");
  sendJson(String("{\"ok\":") + ((ok && r) ? "true" : "false") + ",\"msg\":\"" + esc(m) + "\",\"pos\":" + (r ? p1 : 0) + ",\"home\":" + gHomePos + ",\"off\":" + off + "}");
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
  uint16_t cur;
  if (!moveGate(id, cur)) return;
  bool ok = movePos((uint8_t)id, 1500, 500);
  delay(650);
  uint16_t p1 = 0;
  bool r = readReg((uint8_t)id, servobus::REG_CURR_POS, p1);
  String m = String("回中點 1500") + (r ? String(",實際 ") + p1 : String(",讀不到實際位置")) + (ok ? "" : "(馬達沒回應位置指令)");
  sendResult(ok && r, m);
}

// ---------- 來回掃的啟動 / 停止 / 狀態 ----------
void sweepStop(const String& why, bool goCenter) {
  if (!sw.on) return;
  sw.on = false;
  sw.reason = why;
  if (goCenter) movePos(sw.id, (uint16_t)((sw.lo + sw.hi) / 2), 600);   // 停下來時慢慢回到兩端的中間
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
  bool ok = movePos(sw.id, tgt, sw.timeMs);
  if (ok) sw.fails = 0;
  else if (++sw.fails >= 3) { sweepStop("馬達連續 3 次沒有回應,已自動停止", false); return; }
  sw.legs++;
  sw.toHi = !sw.toHi;
  sw.nextAt = now + sw.timeMs + 250;   // 多等 0.25 秒讓馬達到位
}

void handleSweepStart() {
  if (sw.on) { sendResult(false, "已經在來回掃了"); return; }
  int id = argInt("id", 0);
  int lo = servobus::clampPos(argInt("lo", 900));
  int hi = servobus::clampPos(argInt("hi", 2100));
  float t = server.hasArg("t") ? server.arg("t").toFloat() : 1.5f;
  int timeMs = constrain((int)(t * 1000), 800, 5000);
  int mins = constrain(argInt("min", 0), 0, 600);
  if (hi - lo < 200) { sendResult(false, "左端和右端至少要差 200 單位"); return; }
  uint16_t cur;
  if (!moveGate(id, cur)) return;
  sw.id = (uint8_t)id; sw.lo = (uint16_t)lo; sw.hi = (uint16_t)hi; sw.timeMs = (uint16_t)timeMs;
  sw.toHi = false; sw.legs = 0; sw.fails = 0; sw.reason = "";
  sw.startedAt = millis(); sw.nextAt = sw.startedAt;
  sw.maxMs = (uint32_t)mins * 60000UL;
  sw.on = true;
  sendResult(true, String("開始來回掃:") + lo + " ↔ " + hi + ",單程 " + String(timeMs / 1000.0f, 1) + " 秒" + (mins ? String(",最長 ") + mins + " 分鐘" : String(",不限時,按停止才停")));
}

void handleSweepStop() {
  if (!sw.on) { sendResult(true, "目前沒有在來回掃"); return; }
  sweepStop("手動停止", true);
  sendResult(true, "已停止,馬達回到中間位置");
}

void handleSweepState() {
  uint32_t el = sw.on ? (millis() - sw.startedAt) / 1000 : 0;
  sendJson(String("{\"on\":") + (sw.on ? "true" : "false") + ",\"cycles\":" + (sw.legs / 2) + ",\"toHi\":" + (sw.toHi ? "true" : "false") +
           ",\"sec\":" + el + ",\"lo\":" + sw.lo + ",\"hi\":" + sw.hi + ",\"reason\":\"" + esc(sw.reason) + "\"}");
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

  uint8_t mac[6];
  WiFi.macAddress(mac);
  char ssid[24];
  snprintf(ssid, sizeof(ssid), "ServoID-%02X%02X", mac[4], mac[5]);
  WiFi.mode(WIFI_AP);
  WiFi.softAP(ssid, AP_PASS);
  Serial.printf("WiFi 熱點:%s  密碼:%s  網址:http://%s\n", ssid, AP_PASS, WiFi.softAPIP().toString().c_str());

  server.on("/", []() { server.send_P(200, "text/html; charset=utf-8", PAGE); });
  server.on("/api/state", handleState);
  server.on("/api/class", handleClass);
  server.on("/api/cal", handleCal);
  // 來回掃進行中時,其他會用到匯流排的功能一律先擋下來(要先按停止)
  server.on("/api/scan", []() { if (!busBusy()) handleScan(); });
  server.on("/api/status", []() { if (!busBusy()) handleStatus(); });
  server.on("/api/setid", []() { if (!busBusy()) handleSetId(); });
  server.on("/api/test", []() { if (!busBusy()) handleTest(); });
  server.on("/api/release", []() { if (!busBusy()) handleRelease(); });
  server.on("/api/move", []() { if (!busBusy()) handleMove(); });
  server.on("/api/home", []() { if (!busBusy()) handleHome(); });
  server.on("/api/center", []() { if (!busBusy()) handleCenter(); });
  server.on("/api/sweep", handleSweepStart);
  server.on("/api/sweepstop", handleSweepStop);
  server.on("/api/sweepstate", handleSweepState);
  server.begin();
}

void loop() {
  server.handleClient();
  sweepTick();
}
