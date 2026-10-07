// ServoBus 封包產生器(純 C++,不依賴 Arduino)。
// 格式來源:ServoBus protocol V0.5 PDF + 原廠 CAS_Servo_Management 工具的程式(靜態分析)。
//   封包 = [ID|0x80] [命令|0x80 已含在命令碼裡] [資料...] [檢查碼]
//   檢查碼 = 前面所有位元組相加,取低 8 位元
//   ID 0 是廣播,這裡的函式一律拒絕 ID 0(回傳長度 0),避免不小心改到所有馬達。
#pragma once
#include <stddef.h>
#include <stdint.h>

namespace servobus {

enum : uint8_t {
  CMD_PING = 0x81,
  CMD_SYNC_MOVE = 0x82,
  CMD_SET_TARGET = 0x83,
  CMD_READ = 0x91,
  CMD_WRITE = 0x92,
};

// 暫存器位址(原廠工具實際用的)
enum : uint8_t {
  REG_TARGET_POS = 0,
  REG_TARGET_TIME = 1,
  REG_CURR_POS = 5,
  REG_VOLTAGE = 7,   // 原始值 0~1023,換算係數不明
  REG_CURRENT = 8,   // 原始值 0~1023
  REG_TEMP = 9,      // 原始值 0~1023(NTC)
  REG_ID = 32,       // 高位元組 = Broadcast ID,低位元組 = 馬達 ID(1~127)
};

static const uint16_t POS_MIN = 800;   // PDF:目標位置有效範圍 800~2200(0 = 不動 / 放鬆)
static const uint16_t POS_MAX = 2200;

inline uint8_t checksum(const uint8_t* b, size_t n) {
  uint8_t s = 0;
  for (size_t i = 0; i < n; i++) s = (uint8_t)(s + b[i]);
  return s;
}

inline bool validId(uint8_t id) { return id >= 1 && id <= 127; }

// 每個函式回傳封包長度,失敗(ID 不合法)回傳 0。out 至少 12 位元組。
inline size_t ping(uint8_t id, uint8_t* out) {
  if (!validId(id)) return 0;
  out[0] = id | 0x80; out[1] = CMD_PING;
  out[2] = checksum(out, 2);
  return 3;
}

// 讀 nRegs 個連續暫存器(每個 2 位元組),從 addr 開始
inline size_t readReg(uint8_t id, uint8_t addr, uint8_t nRegs, uint8_t* out) {
  if (!validId(id) || nRegs < 1) return 0;
  out[0] = id | 0x80; out[1] = CMD_READ; out[2] = nRegs; out[3] = addr;
  out[4] = checksum(out, 4);
  return 5;
}

// 回應長度:addr, cmd, length, 資料(2*nRegs), 檢查碼 = 2*nRegs + 4
inline size_t readRegResponseLen(uint8_t nRegs) { return (size_t)nRegs * 2 + 4; }

// 寫 1 個暫存器(2 位元組值)。ID 暫存器:writeReg(目前ID, REG_ID, 新ID)
inline size_t writeReg(uint8_t id, uint8_t addr, uint16_t value, uint8_t* out) {
  if (!validId(id)) return 0;
  out[0] = id | 0x80; out[1] = CMD_WRITE; out[2] = addr;
  out[3] = (uint8_t)(value >> 8); out[4] = (uint8_t)(value & 0xFF);
  out[5] = checksum(out, 5);
  return 6;
}

// 設定目標位置(位置單位 = 脈波寬度 µs,1500 = 中點)和到位時間(ms)。位置 0 = 放鬆。
inline size_t setPosition(uint8_t id, uint16_t pos, uint16_t timeMs, uint8_t* out) {
  if (!validId(id)) return 0;
  out[0] = id | 0x80; out[1] = CMD_SET_TARGET; out[2] = 0x05;
  out[3] = (uint8_t)((pos >> 8) & 0x7F); out[4] = (uint8_t)(pos & 0xFF);
  out[5] = (uint8_t)((timeMs >> 8) & 0x7F); out[6] = (uint8_t)(timeMs & 0xFF);
  out[7] = checksum(out, 7);
  return 8;
}

inline uint16_t clampPos(int p) {
  if (p < (int)POS_MIN) return POS_MIN;
  if (p > (int)POS_MAX) return POS_MAX;
  return (uint16_t)p;
}

}  // namespace servobus
