/*
pad.h -- 從 ESP32(PS4 橋接)接收按鍵
接線:ESP32 G26 -> Micro D8,GND 共地
搖桿等類比處理全部在 ESP32 完成,Micro 只收一個 16 位元按鍵值。
*/
#ifndef __PAD_H__
#define __PAD_H__

#include <stdint.h>

#define PAD_RX_PIN   8      // 接 ESP32 TX
#define PAD_TX_PIN   9      // 未使用(SoftwareSerial 需要指定)
#define PAD_BAUD     38400
#define PAD_LOST_MS  300    // 超過這個時間沒收到資料 -> 視為沒有按鍵

enum PAD_BTN : uint16_t {
  PAD_BTN_NONE     = 0x0000,
  PAD_BTN_UP       = 0x0001,
  PAD_BTN_DOWN     = 0x0002,
  PAD_BTN_RIGHT    = 0x0004,
  PAD_BTN_LEFT     = 0x0008,
  PAD_BTN_TRIANGLE = 0x0010,
  PAD_BTN_CROSS    = 0x0020,
  PAD_BTN_CIRCLE   = 0x0040,
  PAD_BTN_SQUARE   = 0x0100,
  PAD_BTN_L1       = 0x0200,
  PAD_BTN_L2       = 0x0400,
  PAD_BTN_R1       = 0x0800,
  PAD_BTN_R2       = 0x1000,
  PAD_BTN_START    = 0x0170,   // PS4 Options
  PAD_BTN_STOP     = 0x000F    // PS4 Share
};

void pad_init();
uint16_t pad_getKey();
uint16_t pad_getKeyEvent();           // 主程式分派用:忙碌時按過又放開的鍵也會補回傳一次

// 搖桿(蘑菇頭)類比值:-128 ~ 127,Y 向上為正。斷線時為 0。
// 搖桿的處理在 ESP32 完成;這裡只是讓 Micro 也能讀到數值。
enum PAD_STICK { PAD_LX = 0, PAD_LY, PAD_RX, PAD_RY };
int8_t pad_getStick(uint8_t axis);

// 按下搖桿(L3 / R3):和一般按鍵、推搖桿都是獨立的
enum PAD_STICKBTN { PAD_L3 = 0x01, PAD_R3 = 0x02 };
bool pad_getStickButton(uint8_t mask);
uint8_t pad_getStickButtons();          // 回傳 PAD_L3 / PAD_R3 的組合(給 switch 用),斷線為 0

// 1 = 在序列埠監控視窗(115200)每 200ms 印出按鍵與搖桿數值(只在遙控模式,不影響 MotorAdj)
#define PAD_DEBUG_PRINT  1

#endif
