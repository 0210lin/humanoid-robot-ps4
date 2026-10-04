/*
imu.ino -- BNO055 姿態感測器(I2C,板子上綠色的 I2C 接頭:SDA / SCL)
讀出前後傾(pitch)、左右傾(roll),傾斜超過設定角度就把旗標設成 1,
再由 custom.ino 的 custom_imuFun() 決定要跑哪一幀。
沒有接感測器時,imu_ok = 0,旗標永遠是 0,其他功能完全不受影響。

用「IMU 模式」(陀螺儀 + 加速度,不用磁力計),馬達的磁場不會干擾。
角度單位:度。放平時 pitch = roll = 0。
*/
#include "custom.h"

// ====== 你要調的設定 ======
#define IMU_TILT_DEG    25      // 傾斜超過幾度算「倒」
#define IMU_HOLD_MS     150     // 超過角度要持續多久才算(濾掉瞬間的抖動)
#define IMU_COOLDOWN_MS 1500    // 做完一次動作後、或你放開按鍵 / 搖桿後,要穩定多久才開始偵測
// 感測器裝的方向不同,正負號會相反:發現「往前倒卻變成往後」就把 PITCH_SIGN 改成 -1
#define IMU_PITCH_SIGN  1       // 往前倒 pitch 為正 = 1,為負 = -1
#define IMU_ROLL_SIGN   1       // 往右倒 roll 為正 = 1,為負 = -1
#define IMU_SWAP_AXES   0       // 感測器橫著裝,前後與左右對調 = 1

#define BNO_ADDR 0x28           // ADR 腳接 GND = 0x28(多數模組預設),接 3.3V = 0x29

uint8_t IMU_FWD, IMU_BACK, IMU_LEFT, IMU_RIGHT;   // 1 = 傾斜超過 IMU_TILT_DEG(已經持續 IMU_HOLD_MS)

static uint8_t  imu_ok = 0;
static int16_t  imu_pitch10 = 0;      // 角度 x10(0.1 度)
static int16_t  imu_roll10 = 0;
static uint32_t imu_lastRead = 0;
static uint32_t imu_since[4] = {0, 0, 0, 0};
static uint32_t imu_rearmMs = 0;

// ---- 精簡的 I2C(直接操作 ATmega32U4 的 TWI,不用 Wire 函式庫,省約 1 KB 程式空間)----
// 腳位固定:SDA = D2、SCL = D3(板子上綠色的 I2C 接頭)。全部有逾時,匯流排卡住也不會停住。
static uint8_t twi_wait()
{
  uint16_t t = 20000;
  while(!(TWCR & _BV(TWINT))){
    if(--t == 0){
      return 0;
    }
  }
  return 1;
}

static uint8_t twi_start(uint8_t sla)       // sla:位址 << 1 | 讀寫位元
{
  TWCR = _BV(TWINT) | _BV(TWSTA) | _BV(TWEN);
  if(!twi_wait()){
    return 0;
  }
  TWDR = sla;
  TWCR = _BV(TWINT) | _BV(TWEN);
  if(!twi_wait()){
    return 0;
  }
  uint8_t st = TWSR & 0xF8;
  return st == 0x18 || st == 0x40;          // 對方有回應(ACK)
}

static void twi_stop()
{
  TWCR = _BV(TWINT) | _BV(TWEN) | _BV(TWSTO);
}

static uint8_t twi_put(uint8_t d)
{
  TWDR = d;
  TWCR = _BV(TWINT) | _BV(TWEN);
  if(!twi_wait()){
    return 0;
  }
  return (TWSR & 0xF8) == 0x28;
}

static uint8_t twi_get(uint8_t ack)
{
  TWCR = _BV(TWINT) | _BV(TWEN) | (ack ? _BV(TWEA) : 0);
  if(!twi_wait()){
    return 0xFF;
  }
  return TWDR;
}

static uint8_t bno_write(uint8_t reg, uint8_t val)
{
  uint8_t ok = twi_start(BNO_ADDR << 1) && twi_put(reg) && twi_put(val);
  twi_stop();
  return ok;
}

static uint8_t bno_read(uint8_t reg, uint8_t *buf, uint8_t len)
{
  if(!(twi_start(BNO_ADDR << 1) && twi_put(reg) && twi_start((BNO_ADDR << 1) | 1))){
    twi_stop();
    return 0;
  }
  for(uint8_t i = 0; i < len; i++){
    buf[i] = twi_get(i + 1 < len);
  }
  twi_stop();
  return 1;
}

void imu_init()
{
  // 不開內建上拉(內建上拉會拉到 5V,可能灌進只能 3.3V 的感測器);上拉電阻由模組或板子的 I2C 接頭提供
  TWSR = 0;
  TWBR = 72;                            // 100 kHz
  TWCR = _BV(TWEN);
  delay(700);                           // BNO055 開機要約 650 ms
  uint8_t id = 0;
  if(!bno_read(0x00, &id, 1) || id != 0xA0){
    imu_ok = 0;                         // 沒接、接錯、位址不對
    return;
  }
  bno_write(0x3D, 0x00);                // 先進設定模式
  delay(25);
  bno_write(0x07, 0x00);                // 暫存器第 0 頁
  bno_write(0x3E, 0x00);                // 一般電源
  bno_write(0x3D, 0x08);                // IMU 模式(不用磁力計)
  delay(25);
  imu_ok = 1;
}

uint8_t imu_present() { return imu_ok; }
uint8_t imu_cooling() { return (uint8_t)(millis() - imu_rearmMs < IMU_COOLDOWN_MS); }   // 剛做完動作 / 剛放開按鍵,還在等穩定
int16_t imu_getPitch10() { return imu_pitch10; }
int16_t imu_getRoll10() { return imu_roll10; }

// 做完一次傾斜動作後呼叫,冷卻時間內不會再觸發(避免剛站起來又被判成倒)
void imu_rearm()
{
  imu_rearmMs = millis();
  for(uint8_t i = 0; i < 4; i++){
    imu_since[i] = 0;
  }
  IMU_FWD = IMU_BACK = IMU_LEFT = IMU_RIGHT = 0;
}

// 每次迴圈呼叫一次;內部每 20 ms 才真的讀一次
void imu_update()
{
  if(!imu_ok){
    return;
  }
  uint32_t now = millis();
  if(now - imu_lastRead < 20){
    return;
  }
  imu_lastRead = now;

  uint8_t b[4];
  if(!bno_read(0x1C, b, 4)){            // 0x1C: roll(2 bytes), 0x1E: pitch(2 bytes),單位 1/16 度
    return;
  }
  int16_t roll16 = (int16_t)((uint16_t)b[0] | ((uint16_t)b[1] << 8));
  int16_t pitch16 = (int16_t)((uint16_t)b[2] | ((uint16_t)b[3] << 8));
  int16_t pitch10 = (int16_t)((int32_t)pitch16 * 10 / 16);
  int16_t roll10 = (int16_t)((int32_t)roll16 * 10 / 16);
#if IMU_SWAP_AXES
  int16_t t = pitch10; pitch10 = roll10; roll10 = t;
#endif
  imu_pitch10 = pitch10 * IMU_PITCH_SIGN;
  imu_roll10 = roll10 * IMU_ROLL_SIGN;

  if(now - imu_rearmMs < IMU_COOLDOWN_MS){
    IMU_FWD = IMU_BACK = IMU_LEFT = IMU_RIGHT = 0;
    return;
  }

  const int16_t lim = IMU_TILT_DEG * 10;
  uint8_t over[4] = {
    (uint8_t)(imu_pitch10 >=  lim),     // 前
    (uint8_t)(imu_pitch10 <= -lim),     // 後
    (uint8_t)(imu_roll10  <= -lim),     // 左
    (uint8_t)(imu_roll10  >=  lim)      // 右
  };
  uint8_t flag[4];
  for(uint8_t i = 0; i < 4; i++){
    if(!over[i]){
      imu_since[i] = 0;
      flag[i] = 0;
    }else{
      if(imu_since[i] == 0){
        imu_since[i] = now;
      }
      flag[i] = (now - imu_since[i] >= IMU_HOLD_MS);
    }
  }
  IMU_FWD = flag[0];
  IMU_BACK = flag[1];
  IMU_LEFT = flag[2];
  IMU_RIGHT = flag[3];
}
