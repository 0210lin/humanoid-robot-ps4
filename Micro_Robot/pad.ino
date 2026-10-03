/*
pad.ino -- 接收 ESP32 送來的資料
封包(9 位元組):0xAA, keyLow, keyHigh, LX, LY, RX, RY, stickBtn, checksum
  搖桿值 = 實際值 + 128;checksum = 前 8 個位元組 XOR
  三組資料互相獨立:一般按鍵 / 推搖桿 / 按下搖桿(L3、R3)
*/
#include <SoftwareSerial.h>
#include "pad.h"

#define PKT_LEN 9

extern volatile uint8_t uart_isUartMode;

static SoftwareSerial espSerial(PAD_RX_PIN, PAD_TX_PIN);

static uint16_t pad_lastKey = 0;
static int8_t   pad_stick[4] = {0, 0, 0, 0};
static uint8_t  pad_stickBtn = 0;
static uint32_t pad_lastMs = 0;
static uint8_t  pad_buf[PKT_LEN];
static uint8_t  pad_idx = 0;

void pad_init()
{
  espSerial.begin(PAD_BAUD);
}

#if PAD_DEBUG_PRINT
static void pad_debugPrint()
{
  static uint32_t lastPrint = 0;
  if(uart_isUartMode != 0 || millis() - lastPrint < 200){
    return;
  }
  lastPrint = millis();
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
  Serial.println(pad_stick[PAD_RY]);
}
#endif

// 讀完目前收到的資料,保留最新的一筆有效封包
static void pad_poll()
{
  while(espSerial.available()){
    uint8_t b = espSerial.read();

    if(pad_idx == 0 && b != 0xAA){
      continue;                         // 等封包開頭
    }
    pad_buf[pad_idx++] = b;

    if(pad_idx == PKT_LEN){
      pad_idx = 0;
      uint8_t chk = 0;
      for(uint8_t i = 0; i < PKT_LEN - 1; i++){
        chk ^= pad_buf[i];
      }
      if(chk == pad_buf[PKT_LEN - 1]){
        pad_lastKey = pad_buf[1] | ((uint16_t)pad_buf[2] << 8);
        for(uint8_t i = 0; i < 4; i++){
          pad_stick[i] = (int16_t)pad_buf[3 + i] - 128;
        }
        pad_stickBtn = pad_buf[7];
        pad_lastMs = millis();
      }
    }
  }
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
  return pad_alive() ? pad_lastKey : 0;
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
