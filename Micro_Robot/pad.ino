/*
pad.ino -- 接收 ESP32 送來的資料
封包(10 位元組):0xAA, 0x55, keyLow, keyHigh, LX, LY, RX, RY, stickBtn, checksum
  搖桿值 = 實際值 + 128;checksum = 前 9 個位元組 XOR
  三組資料互相獨立:一般按鍵 / 推搖桿 / 按下搖桿(L3、R3)
*/
#include <SoftwareSerial.h>
#include "pad.h"
#include "custom.h"

#define PKT_LEN 10

extern volatile uint8_t uart_isUartMode;

static SoftwareSerial espSerial(PAD_RX_PIN, PAD_TX_PIN);

static uint16_t pad_lastKey = 0;
static int8_t   pad_stick[4] = {0, 0, 0, 0};
static uint8_t  pad_stickBtn = 0;
static uint16_t pad_pending = 0;        // 動作播放中(程式忙)按過又放開的鍵,留給主程式處理一次
static uint16_t pad_served = 0;         // 程式已經看過、正在處理的按著的鍵(放開才清掉)
static uint32_t pad_lastMs = 0;
static uint8_t  pad_buf[PKT_LEN];
static uint8_t  pad_idx = 0;

void pad_init()
{
  espSerial.begin(PAD_BAUD);
}

static bool pad_alive();
#if PAD_DEBUG_PRINT
static void pad_debugPrint()
{
  static uint32_t lastPrint = 0;
  if(uart_isUartMode != 0 || millis() - lastPrint < 200){
    return;
  }
  lastPrint = millis();
  if(!pad_alive()){
    // 超過一段時間沒收到 ESP32 的封包:不要顯示舊資料(可能是雜訊),直接告訴你斷線
    Serial.println(F("LINK LOST: no data from ESP32 (check ATOM power / G26->D8 / GND)"));
    return;
  }
  // 按鍵:Share / Options 是特殊組合碼,其他按鍵可以同時按,逐個列出名字
  Serial.print(F("Buttons: "));
  if(pad_lastKey == PAD_BTN_STOP){
    Serial.print(F("SHARE(STOP) "));
  }else if(pad_lastKey == PAD_BTN_START){
    Serial.print(F("OPTIONS(START) "));
  }else{
    if(pad_lastKey & PAD_BTN_UP)       Serial.print(F("UP "));
    if(pad_lastKey & PAD_BTN_DOWN)     Serial.print(F("DOWN "));
    if(pad_lastKey & PAD_BTN_LEFT)     Serial.print(F("LEFT "));
    if(pad_lastKey & PAD_BTN_RIGHT)    Serial.print(F("RIGHT "));
    if(pad_lastKey & PAD_BTN_TRIANGLE) Serial.print(F("TRIANGLE "));
    if(pad_lastKey & PAD_BTN_CROSS)    Serial.print(F("CROSS "));
    if(pad_lastKey & PAD_BTN_CIRCLE)   Serial.print(F("CIRCLE "));
    if(pad_lastKey & PAD_BTN_SQUARE)   Serial.print(F("SQUARE "));
    if(pad_lastKey & PAD_BTN_L1)       Serial.print(F("L1 "));
    if(pad_lastKey & PAD_BTN_L2)       Serial.print(F("L2 "));
    if(pad_lastKey & PAD_BTN_R1)       Serial.print(F("R1 "));
    if(pad_lastKey & PAD_BTN_R2)       Serial.print(F("R2 "));
  }
  if(pad_stickBtn & PAD_L3) Serial.print(F("L3 "));
  if(pad_stickBtn & PAD_R3) Serial.print(F("R3 "));
  if(pad_lastKey == PAD_BTN_NONE && pad_stickBtn == 0) Serial.print(F("(none)"));
  Serial.print(F("  | LeftStick X="));
  Serial.print(pad_stick[PAD_LX]);
  Serial.print(F(" Y="));
  Serial.print(pad_stick[PAD_LY]);
  Serial.print(F("  RightStick X="));
  Serial.print(pad_stick[PAD_RX]);
  Serial.print(F(" Y="));
  Serial.print(pad_stick[PAD_RY]);
  if(imu_present()){
    imu_update();                       // 傾斜感測沒開啟時也讀,方便檢查接線和方向
    int16_t p = imu_getPitch10();
    int16_t r = imu_getRoll10();
    Serial.print(F("  | IMU pitch="));
    Serial.print(p / 10);
    Serial.print('.');
    Serial.print(abs(p % 10));
    Serial.print(F(" roll="));
    Serial.print(r / 10);
    Serial.print('.');
    Serial.print(abs(r % 10));
  }else{
    Serial.print(F("  | IMU: not found"));
  }
  Serial.println();
}
#endif

// 回報狀態給 ESP32,讓它決定手把燈條的顏色:0xBB, status, ~status(每 300 ms 一次)
//   status:bit0 = 偵測到 BNO055 感測器,bit1 = 傾斜感測已開啟(Options 切換)
// 走 D9(PAD_TX_PIN),要接到 ESP32 的 G32。沒接線也不影響其他功能。
static void pad_sendStatus()
{
  static uint32_t lastSend = 0;
  if(millis() - lastSend < 300){
    return;
  }
  lastSend = millis();
  uint8_t s = (imu_present() ? 1 : 0) | (imu_isEnabled() ? 2 : 0);
  espSerial.write((uint8_t)0xBB);
  espSerial.write(s);
  espSerial.write((uint8_t)~s);
}

// 收到一個完整、檢查碼正確的封包
static void pad_accept()
{
  pad_lastKey = pad_buf[2] | ((uint16_t)pad_buf[3] << 8);
  if(pad_lastKey == 0){
    pad_served = 0;                     // 放開了
  }else if(pad_lastKey != pad_served){
    // 這個鍵程式還沒親眼看到過(忙碌時按的),記起來,等程式有空時補處理一次
    if(pad_lastKey == PAD_BTN_STOP){
      pad_pending = PAD_BTN_STOP;       // Share 絕對不能漏掉
    }else if(pad_pending != PAD_BTN_STOP){
      pad_pending = pad_lastKey;
    }
  }
  for(uint8_t i = 0; i < 4; i++){
    pad_stick[i] = (int16_t)pad_buf[4 + i] - 128;
  }
  pad_stickBtn = pad_buf[8];
  pad_lastMs = millis();
}

// 一次餵一個位元組。封包開頭是兩個位元組 0xAA 0x55:
// 只有 0xAA 一個位元組當開頭的話,檢查碼剛好是 0xAA 時(手把完全沒動),錯位一個位元組也會「剛好合法」,
// 然後永遠卡在錯位的狀態。開頭加上 0x55,而且檢查碼不對時從下一個位元組重新找開頭,就不會了。
static void pad_feed(uint8_t b)
{
  if(pad_idx == 0){
    if(b == 0xAA){
      pad_buf[pad_idx++] = b;
    }
    return;
  }
  if(pad_idx == 1 && b != 0x55){
    pad_idx = 0;
    if(b == 0xAA){
      pad_buf[pad_idx++] = b;
    }
    return;
  }
  pad_buf[pad_idx++] = b;
  if(pad_idx < PKT_LEN){
    return;
  }
  pad_idx = 0;
  uint8_t chk = 0;
  for(uint8_t i = 0; i < PKT_LEN - 1; i++){
    chk ^= pad_buf[i];
  }
  if(chk == pad_buf[PKT_LEN - 1]){
    pad_accept();
    return;
  }
  // 檢查碼不對:可能開頭抓錯位置,從第 2 個位元組起重新找開頭
  uint8_t tmp[PKT_LEN - 1];
  for(uint8_t i = 0; i < PKT_LEN - 1; i++){
    tmp[i] = pad_buf[i + 1];
  }
  for(uint8_t i = 0; i < PKT_LEN - 1; i++){
    pad_feed(tmp[i]);
  }
}

// 讀完目前收到的資料,保留最新的一筆有效封包
static void pad_poll()
{
  while(espSerial.available()){
    pad_feed(espSerial.read());
  }
  pad_sendStatus();
#if PAD_DEBUG_PRINT
  pad_debugPrint();
#endif
}

static bool pad_alive()
{
  return (millis() - pad_lastMs) <= PAD_LOST_MS;
}

// 一般按鍵
uint16_t pad_getKey()
{
  pad_poll();
  // 斷線 -> 回報「沒有按鍵」,讓進行中的動作停止
  if(!pad_alive()){
    return 0;
  }
  if(pad_lastKey != 0){
    pad_served = pad_lastKey;               // 程式已經看到這個按著的鍵了,不必再補處理
    pad_pending = 0;
  }
  return pad_lastKey;
}

// 按鍵「事件」:給主程式的按鍵分派用。
// 動作播放時(SetFrameRun 會等整段播完)程式讀不到按鍵,那段時間很快按一下的鍵會被後面的封包蓋掉。
// 這裡把那一下記起來:目前沒按鍵、但剛才有按過,就補回傳一次。目前有按著的鍵就照常回傳。
uint16_t pad_getKeyEvent()
{
  uint16_t k = pad_getKey();
  if(k != 0){
    pad_pending = 0;
    return k;
  }
  if(pad_pending != 0){
    k = pad_pending;
    pad_pending = 0;
    return pad_alive() ? k : 0;
  }
  return 0;
}

// 推搖桿
int8_t pad_getStick(uint8_t axis)
{
  if(axis > PAD_RY){
    return 0;
  }
  pad_poll();
  return pad_alive() ? pad_stick[axis] : 0;
}

// 按下搖桿
bool pad_getStickButton(uint8_t mask)
{
  pad_poll();
  return pad_alive() && (pad_stickBtn & mask);
}

// 按下搖桿(整組):0 = 都沒按,PAD_L3、PAD_R3、或兩個相加
uint8_t pad_getStickButtons()
{
  pad_poll();
  return pad_alive() ? (pad_stickBtn & (PAD_L3 | PAD_R3)) : 0;
}
