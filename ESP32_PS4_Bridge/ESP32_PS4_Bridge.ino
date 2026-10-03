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

封包(9 位元組,每 100ms 一次):
  0xAA, keyLow, keyHigh, LX, LY, RX, RY, stickBtn, checksum
  搖桿值 = 實際值 + 128(0~255);checksum = 前 8 個位元組 XOR
按鍵位元定義見 Micro_Robot/pad.h。
*/

#include <PS4Controller.h>

// 手把配對的 MAC(用 SixaxisPairTool 寫入手把的 ESP32 藍牙 MAC)
#define PS4_MAC  "c8:85:41:4d:5c:3e"

#define TX2_PIN  26   // ATOM Lite Grove 的 G26
#define RX2_PIN  32   // Grove 的 G32(沒有使用)
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
uint16_t readKeys()
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
uint8_t readStickButtons()
{
  if (!PS4.isConnected()) return 0;

  uint8_t b = 0;
  if (PS4.L3()) b |= SB_L3;
  if (PS4.R3()) b |= SB_R3;
  return b;
}

void setup()
{
  Serial.begin(115200);
  Serial2.begin(LINK_BAUD, SERIAL_8N1, RX2_PIN, TX2_PIN);
  PS4.begin(PS4_MAC);
  Serial.println("Waiting for PS4 controller...");
}

void loop()
{
  static uint32_t last = 0;
  if (millis() - last < SEND_INTERVAL_MS) return;
  last = millis();

  bool on = PS4.isConnected();
  uint16_t k = readKeys();
  uint8_t  sb = readStickButtons();

  uint8_t pkt[9];
  pkt[0] = 0xAA;
  pkt[1] = k & 0xFF;
  pkt[2] = k >> 8;
  pkt[3] = on ? (uint8_t)(PS4.LStickX() + 128) : 128;
  pkt[4] = on ? (uint8_t)(PS4.LStickY() + 128) : 128;
  pkt[5] = on ? (uint8_t)(PS4.RStickX() + 128) : 128;
  pkt[6] = on ? (uint8_t)(PS4.RStickY() + 128) : 128;
  pkt[7] = sb;
  pkt[8] = 0;
  for (int i = 0; i < 8; i++) pkt[8] ^= pkt[i];
  Serial2.write(pkt, 9);

  static uint32_t prev = 0xFFFFFFFF;
  uint32_t now = ((uint32_t)sb << 16) | k;
  if (now != prev) { Serial.printf("key=0x%04X stickBtn=0x%02X\n", k, sb); prev = now; }
}
