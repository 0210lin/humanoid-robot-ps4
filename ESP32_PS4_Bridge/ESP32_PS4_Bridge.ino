/*
ESP32_PS4_Bridge.ino
PS4 手把 (藍牙) -> M5Stack ATOM Lite -> UART -> Arduino Micro

函式庫:PS4Controller (github.com/aed3/PS4-esp32)
開發板:M5Stack-ATOM,ESP32 Arduino core 2.0.17

接線:ATOM Lite G26 (Grove) -> Micro D8 (DIO 排針)
      ATOM Lite GND         -> Micro GND

三組資料完全獨立,互不影響:
  1. 一般按鍵            (keyLow, keyHigh)
  2. 推搖桿的類比值       (LX, LY, RX, RY)  -128 ~ 127,Y 向上為正
  3. 按下搖桿 L3 / R3     (stickBtn)       bit0 = L3, bit1 = R3

封包(10 位元組,每 100ms 一次):
  0xAA, 0x55, keyLow, keyHigh, LX, LY, RX, RY, stickBtn, checksum
  搖桿值 = 實際值 + 128(0~255);checksum = 前 9 個位元組 XOR
按鍵位元定義見 Micro_Robot/pad.h。
*/

#include <PS4Controller.h>

// 手把配對的 MAC(用 SixaxisPairTool 寫入手把的 ESP32 藍牙 MAC)
#define PS4_MAC  "c8:85:41:4d:5c:3e"

#define TX2_PIN  26   // ATOM Lite Grove 的 G26
#define RX2_PIN  32   // Grove 的 G32:接 Micro 的 D9,收 Micro 回報的感測器狀態
#define LINK_BAUD 38400
#define SEND_INTERVAL_MS 100   // Micro 的接收緩衝區只有 64 位元組,間隔不要再縮短

// 一般按鍵位元(與 Micro 的 pad.h 一致)
#define K_UP        0x0001
#define K_DOWN      0x0002
#define K_RIGHT     0x0004
#define K_LEFT      0x0008
#define K_TRIANGLE  0x0010
#define K_CROSS     0x0020
#define K_CIRCLE    0x0040
#define K_SQUARE    0x0100
#define K_L1        0x0200
#define K_L2        0x0400
#define K_R1        0x0800
#define K_R2        0x1000
#define K_START     0x0170   // PS4 Options
#define K_STOP      0x000F   // PS4 Share

// 按下搖桿位元
#define SB_L3       0x01
#define SB_R3       0x02

// 只包含一般按鍵,不含搖桿
uint16_t readKeysLive()
{
  if (!PS4.isConnected()) return 0;

  if (PS4.Options()) return K_START;
  if (PS4.Share())   return K_STOP;

  uint16_t k = 0;
  if (PS4.Up())       k |= K_UP;
  if (PS4.Down())     k |= K_DOWN;
  if (PS4.Right())    k |= K_RIGHT;
  if (PS4.Left())     k |= K_LEFT;
  if (PS4.Triangle()) k |= K_TRIANGLE;
  if (PS4.Cross())    k |= K_CROSS;
  if (PS4.Circle())   k |= K_CIRCLE;
  if (PS4.Square())   k |= K_SQUARE;
  if (PS4.L1())       k |= K_L1;
  if (PS4.R1())       k |= K_R1;
  if (PS4.L2())       k |= K_L2;
  if (PS4.R2())       k |= K_R2;
  return k;
}

// 只包含 L3 / R3
uint8_t readStickButtonsLive()
{
  if (!PS4.isConnected()) return 0;

  uint8_t b = 0;
  if (PS4.L3()) b |= SB_L3;
  if (PS4.R3()) b |= SB_R3;
  return b;
}

// ====== 手把燈條 ======
// 手把還沒連上:ATOM 自己的燈(Atom Lite 上那顆 RGB)閃黃色
// 手把已連上,但 Micro 還沒回報狀態(沒接線 / 還在開機):手把燈條閃黃色
// 手把已連上,Micro 有回報:Micro 有偵測到 BNO055 感測器 = 綠色,沒有 = 藍色(可用 LED_GREEN_WHEN_ENABLED 改成「按 Options 開啟才綠」)
// 按手把的 PS 鍵:燈條顯示電量 3 秒(綠 = 夠,黃 = 一半,淺紅 = 偏低,深紅 = 快沒電),之後回到上面的顏色
// Micro 的 D9 要接到 ATOM 的 G32,Micro 才能把感測器狀態回報給 ATOM(沒接也能用,只是會一直閃黃)
#define ATOM_LED_PIN          27     // Atom Lite 內建 RGB 燈
#define LED_GREEN_WHEN_ENABLED 0     // 0 = 偵測到 BNO055 就綠色、沒偵測到是藍色;1 = 要按 Options 把傾斜感測【開啟】才綠色
#define MICRO_STATUS_TIMEOUT_MS 1500
#define BATTERY_SHOW_MS       3000

static uint8_t  microStatus = 0;     // bit0 = 偵測到感測器,bit1 = 傾斜感測已開啟
static uint32_t microMs = 0;
static bool     microEver = false;

// Micro 回報:0xBB, status, ~status
void readMicro()
{
  static uint8_t buf[3];
  static uint8_t idx = 0;
  while (Serial2.available()) {
    uint8_t b = Serial2.read();
    if (idx == 0 && b != 0xBB) continue;
    buf[idx++] = b;
    if (idx == 3) {
      idx = 0;
      if ((uint8_t)~buf[1] == buf[2]) {
        microStatus = buf[1];
        microMs = millis();
        microEver = true;
      }
    }
  }
}

enum LedMode { L_NONE, L_LINK, L_BLUE, L_GREEN, L_BAT };

static void padLed(uint8_t r, uint8_t g, uint8_t b, uint8_t onT, uint8_t offT)
{
  PS4.setLed(r, g, b);
  PS4.setFlashRate(onT, offT);
  PS4.sendToController();
}

void updateLeds()
{
  static LedMode mode = L_NONE;
  static uint32_t lastSend = 0;
  static uint32_t batUntil = 0;
  static bool prevPs = false;

  if (!PS4.isConnected()) {
    bool f = (millis() / 500) % 2;
    neopixelWrite(ATOM_LED_PIN, f ? 40 : 0, f ? 30 : 0, 0);   // 等待連線:閃黃
    mode = L_NONE;
    prevPs = false;
    return;
  }
  neopixelWrite(ATOM_LED_PIN, 0, 0, 0);

  bool ps = PS4.PSButton();
  if (ps && !prevPs) {
    batUntil = millis() + BATTERY_SHOW_MS;
    mode = L_NONE;                      // 重新送一次(顯示電量)
  }
  prevPs = ps;

  LedMode want;
  bool microOk = microEver && (millis() - microMs < MICRO_STATUS_TIMEOUT_MS);
  if (millis() < batUntil)            want = L_BAT;
  else if (!microOk)                  want = L_LINK;
#if LED_GREEN_WHEN_ENABLED
  else if (microStatus & 0x02)        want = L_GREEN;
#else
  else if (microStatus & 0x01)        want = L_GREEN;
#endif
  else                                want = L_BLUE;

  // 狀態改變,或每 2 秒補送一次(避免剛連上時第一次沒收到)
  if (want == mode && millis() - lastSend < 2000) return;
  lastSend = millis();

  if (want == L_LINK) {
    padLed(255, 180, 0, 30, 30);        // 閃黃
  } else if (want == L_BLUE) {
    padLed(0, 60, 255, 0, 0);
  } else if (want == L_GREEN) {
    padLed(0, 255, 40, 0, 0);
  } else {                              // L_BAT:原始值 0~15;充電中最滿 11,沒充電最滿約 8
    uint8_t raw = PS4.Battery();
    uint8_t full = PS4.Charging() ? 11 : 8;
    uint8_t pct = (uint8_t)min(100, (int)raw * 100 / full);
    if (mode != L_BAT) Serial.printf("Battery raw=%u charging=%d -> %u%%\n", raw, PS4.Charging(), pct);
    if (pct >= 60)      padLed(0, 255, 0, 0, 0);
    else if (pct >= 30) padLed(255, 200, 0, 0, 0);
    else if (pct >= 15) padLed(255, 70, 70, 0, 0);     // 淺紅(偏粉)
    else                padLed(120, 0, 0, 0, 0);       // 深紅(很暗,快沒電)
  }
  mode = want;
}

// ====== 手把震動 ======
// 連上手把:震一下(連上後約 1 秒,確定連線穩了才震)
// 傾斜感測切換(Micro 回報的「已開啟」狀態改變):開啟 = 震一長下,關閉 = 短短兩下
// 電量很低(深紅那一段)而且沒在充電:每 20 秒短短兩下提醒
#define RUMBLE_STRENGTH   200    // 震動強度 0~255
#define LOW_BATT_PCT      15     // 低於這個百分比算電量很低(跟燈條深紅那段一致)
#define LOW_BATT_REMIND_MS 20000   // 低電量提醒的間隔(毫秒)

static uint8_t  rumLeft = 0;
static uint16_t rumOn = 0, rumOff = 0;
static bool     rumIsOn = false;
static uint32_t rumT = 0;

// 震 pulses 下,每下震 onMs 毫秒、間隔 offMs 毫秒
void rumble(uint8_t pulses, uint16_t onMs, uint16_t offMs)
{
  rumLeft = pulses;
  rumOn = onMs;
  rumOff = offMs;
  rumIsOn = false;
  rumT = 0;
}

void updateRumble()
{
  if (!PS4.isConnected()) {
    rumLeft = 0;
    rumIsOn = false;
    return;
  }
  uint32_t now = millis();
  if (rumIsOn) {
    if (now - rumT >= rumOn) {
      PS4.setRumble(0, 0);
      PS4.sendToController();
      rumIsOn = false;
      rumT = now;
    }
  } else if (rumLeft > 0 && (rumT == 0 || now - rumT >= rumOff)) {
    PS4.setRumble(RUMBLE_STRENGTH, RUMBLE_STRENGTH);
    PS4.sendToController();
    rumIsOn = true;
    rumT = now;
    rumLeft--;
  }
}

// 偵測要震動的事件
void updateEvents()
{
  static bool     wasConn = false;
  static uint32_t connMs = 0;
  static bool     connBuzzed = false;
  static int8_t   prevEnabled = -1;
  static uint32_t lastLowBatt = 0;

  bool on = PS4.isConnected();
  if (on && !wasConn) {
    connMs = millis();
    connBuzzed = false;
    prevEnabled = -1;
    lastLowBatt = millis();
  }
  wasConn = on;
  if (!on) return;

  if (!connBuzzed && millis() - connMs > 1000) {
    connBuzzed = true;
    rumble(1, 200, 0);                  // 連上了
  }

  bool microOk = microEver && (millis() - microMs < MICRO_STATUS_TIMEOUT_MS);
  if (microOk) {
    int8_t en = (microStatus & 0x02) ? 1 : 0;
    if (prevEnabled >= 0 && en != prevEnabled) {
      if (en) rumble(1, 500, 0);        // 傾斜感測開啟:一長下
      else    rumble(2, 120, 120);      // 關閉:短短兩下
    }
    prevEnabled = en;
  }

  // 連上 10 秒後才檢查電量(剛連上時電量資料可能還沒更新)
  if (millis() - connMs > 10000 && millis() - lastLowBatt > LOW_BATT_REMIND_MS) {
    lastLowBatt = millis();
    uint8_t full = PS4.Charging() ? 11 : 8;
    uint8_t pct = (uint8_t)min(100, (int)PS4.Battery() * 100 / full);
    if (pct < LOW_BATT_PCT && !PS4.Charging()) {
      rumble(2, 150, 150);
    }
  }
}


// ====== 手把資料快照(只收「正常的報告」)======
// 手把正常的報告,原始封包的第 9、10 個位元組是 A1 11。不是這個開頭的封包(別種報告、雜訊)
// 函式庫還是會照正常格式去解讀,解出亂按的鍵和推到底的搖桿。
// 所以每筆報告進來,先檢查開頭;正常的才把按鍵和搖桿存進快照,其他的丟掉(保留上一筆正常的)。
// 後面送給 Micro 的資料,一律從快照讀,不直接讀函式庫。
static volatile uint16_t snapKeys = 0;
static volatile uint8_t  snapSb = 0;
static volatile int8_t   snapLX = 0, snapLY = 0, snapRX = 0, snapRY = 0;
static volatile uint32_t badCount = 0;
static volatile uint8_t  badHdr[24];

uint16_t readKeys()          { return PS4.isConnected() ? snapKeys : 0; }
uint8_t  readStickButtons()  { return PS4.isConnected() ? snapSb : 0; }
// ====== 診斷(找「閒置一段時間後出現怪資料」的原因)======
// DIAG_PRINT = 1:每秒在序列監控印一行 "DIAG reports/s=… hdr=…",
// 內容是這一秒收到幾筆手把報告,以及最近一筆原始封包的前幾個位元組。
// 怪資料出現時,把這一行貼給我。查完原因可以設成 0。
#define DIAG_PRINT 1
#define PAD_SETTLE_MS 500      // 連上後至少等這麼久才放行(避開剛連上時的亂資料)

static volatile uint32_t diagCount = 0;
static volatile bool     gotReport = false;   // 連上之後,是否已經收到手把的報告
static volatile uint8_t  diagHdr[24];

static void onPadReport()
{
  diagCount++;
  const uint8_t* p = PS4.data.latestPacket;
  bool valid = p && p[9] == 0xA1 && p[10] == 0x11;
  if (p) {
    for (int i = 0; i < 24; i++) diagHdr[i] = p[i];
  }
  if (!valid) {
    badCount++;                         // 不是正常報告:丟掉,保留上一筆正常的快照
    if (p) {
      for (int i = 0; i < 24; i++) badHdr[i] = p[i];
    }
    return;
  }
  snapKeys = readKeysLive();
  snapSb = readStickButtonsLive();
  snapLX = PS4.LStickX();
  snapLY = PS4.LStickY();
  snapRX = PS4.RStickX();
  snapRY = PS4.RStickY();
  gotReport = true;
}

static void diagLoop()
{
#if DIAG_PRINT
  static uint32_t lastMs = 0;
  static uint32_t lastCount = 0;
  static uint32_t lastBad = 0;
  if (millis() - lastMs < 1000) return;
  lastMs = millis();
  uint32_t c = diagCount;
  uint32_t b = badCount;
  if (PS4.isConnected()) {
    Serial.printf("DIAG reports/s=%u bad=%u hdr=", (unsigned)(c - lastCount), (unsigned)(b - lastBad));
    for (int i = 0; i < 24; i++) Serial.printf("%02X ", diagHdr[i]);
    Serial.printf(" LX=%d LY=%d RX=%d RY=%d\n", snapLX, snapLY, snapRX, snapRY);
    if (b != lastBad) {                 // 這一秒有丟掉的封包:把它的內容也印出來
      Serial.print("DIAG BAD-PACKET hdr=");
      for (int i = 0; i < 24; i++) Serial.printf("%02X ", badHdr[i]);
      Serial.println();
    }
  }
  lastCount = c;
  lastBad = b;
#endif
}

void setup()
{
  Serial.begin(115200);
  Serial2.begin(LINK_BAUD, SERIAL_8N1, RX2_PIN, TX2_PIN);
  PS4.attach(onPadReport);
  PS4.begin(PS4_MAC);
  Serial.println("Waiting for PS4 controller...");
}

void loop()
{
  readMicro();
  updateLeds();
  updateEvents();
  updateRumble();
  diagLoop();

  // 連續取樣、把這一個傳送週期內「出現過」的按鍵鎖住(很快按一下也不會漏掉)。
  // Share / Options 是特殊組合碼,不能跟別的按鍵 OR 在一起,所以分開記:Share 優先,其次 Options。
  static uint16_t latchKeys = 0;
  static bool     latchStart = false, latchStop = false;
  static uint8_t  latchSb = 0;
  uint16_t kNow = readKeys();
  if (kNow == K_STOP)       latchStop = true;
  else if (kNow == K_START) latchStart = true;
  else                      latchKeys |= kNow;
  latchSb |= readStickButtons();

  static uint32_t last = 0;
  if (millis() - last < SEND_INTERVAL_MS) return;
  last = millis();

  bool on = PS4.isConnected();

  // 手把剛連上時,函式庫裡還是沒初始化的內容(會解讀出亂按的鍵和推到底的搖桿)。
  // 連上後要先收到手把的報告、而且過了 PAD_SETTLE_MS,才開始送真正的資料;之前一律送「什麼都沒按」。
  static bool     wasOn = false;
  static uint32_t connectMs = 0;
  if (on && !wasOn) {
    connectMs = millis();
    gotReport = false;
  }
  wasOn = on;
  bool ready = on && gotReport && (millis() - connectMs >= PAD_SETTLE_MS);
  if (!ready) {
    latchKeys = 0; latchStart = false; latchStop = false; latchSb = 0;
  }
  uint16_t k = latchStop ? K_STOP : (latchStart ? K_START : latchKeys);
  uint8_t  sb = latchSb;
  latchKeys = 0; latchStart = false; latchStop = false; latchSb = 0;

  uint8_t pkt[10];
  pkt[0] = 0xAA;
  pkt[1] = 0x55;                        // 兩個位元組的開頭,避免檢查碼剛好等於 0xAA 時 Micro 對齊錯位
  pkt[2] = k & 0xFF;
  pkt[3] = k >> 8;
  pkt[4] = ready ? (uint8_t)(snapLX + 128) : 128;
  pkt[5] = ready ? (uint8_t)(snapLY + 128) : 128;
  pkt[6] = ready ? (uint8_t)(snapRX + 128) : 128;
  pkt[7] = ready ? (uint8_t)(snapRY + 128) : 128;
  pkt[8] = sb;
  pkt[9] = 0;
  for (int i = 0; i < 9; i++) pkt[9] ^= pkt[i];
  Serial2.write(pkt, 10);

  static uint32_t prev = 0xFFFFFFFF;
  uint32_t now = ((uint32_t)sb << 16) | k;
  if (now != prev) { Serial.printf("key=0x%04X stickBtn=0x%02X\n", k, sb); prev = now; }
}
