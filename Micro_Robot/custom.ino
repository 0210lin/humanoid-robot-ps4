/*
custom.ino  -- 機器人「按鍵 -> 動作」對照表(你最常改的檔案)

按鍵值由 ESP32 的 PS4 橋接程式送來(定義見 pad.h)。
SetFrameRun(幀編號, 時間ms) 會播放 motor.h 裡的一個動作幀。
幀編號對應 MotorAdj 調整工具裡的 frame 編號。

流程:
  開機 -> 等待按下 START(PS4 的 Options) -> 進入遙控模式
  PS4 的 Share 鍵 = STOP,所有馬達放鬆
  接上電腦的 MotorAdj 工具時,自動離開遙控模式
*/

#include "motor.h"
#include "custom.h"
#include "pad.h"

static uint8_t imu_enabled = 0;         // 傾斜感測開關(Options 切換),見下方 custom_imuFun
static uint8_t imu_prevOptions = 1;     // 進入遙控模式時按著 Options,不算一次切換

void custom_setup()
{
  pad_init();
  imu_init();
}

void custom_loop()
{
  uart_disableMotor();
  imu_enabled = 0;                    // 每次進入遙控模式,傾斜感測先關閉
  imu_prevOptions = 1;

  // 等待 START 才開始動作(期間如果接上電腦,就交給電腦處理)
  while (pad_getKeyEvent() != PAD_BTN_START) {
    if (uart_isConnectToPC() != 0) {
      return;
    }
  }

  while (uart_isUartMode == 0) {
    custom_imuFun();                  // 傾斜超過角度 -> 跑你寫好的動作(優先於按鍵)
    custom_gamepadKeyFun_kondo();
    custom_stickFun();

    if (uart_isConnectToPC() != 0) {
      return;
    }
  }
}

// ====== 傾斜感測(BNO055):被推、被打時自己修正 ======
// 原則:你在操作時完全不干涉。只有「沒按任何鍵、沒推搖桿、沒按 L3/R3」,而且放開後已經穩定一陣子
// (imu.ino 的 IMU_COOLDOWN_MS,預設 1.5 秒),才會依傾斜角度修正。
//
// 做法:你在 MotorAdj 錄一幀「浮誇的修正姿勢」,【只勾選要動的馬達】(例如大腿)。
//   傾斜愈大,就愈接近那個浮誇姿勢(從站姿慢慢混過去),動的速度也愈快。
//   往另一邊倒,姿勢就反過來(等於前後 / 左右對稱的反向修正)。
//   傾斜太多(IMU_CROUCH_DEG 以上)就直接做蹲下的動作。
// 角度正負:往前倒 pitch 為正、往右倒 roll 為正;方向反了,改 imu.ino 的 IMU_PITCH_SIGN / IMU_ROLL_SIGN。
//
// 把下面的 -1 換成你的幀編號(-1 = 這一項不啟用)。【第一次測試一定要把機器人架空,先確認方向!】
#define IMU_STAND_FRAME    1     // 站姿幀
#define IMU_PITCH_FRAME   -1     // XX 前後傾的浮誇姿勢(往前倒時要做的修正)
#define IMU_ROLL_FRAME    -1     // XX 左右傾的浮誇姿勢(往右倒時要做的修正)
#define IMU_CROUCH_FRAME  -1     // XX 傾斜太多時的蹲下動作

#define IMU_DEAD_DEG      5      // 傾斜在這個角度內不動(死區)
#define IMU_FULL_DEG      20     // 傾斜到這個角度,修正達到 100%(完整的浮誇姿勢)
#define IMU_CROUCH_DEG    35     // 傾斜超過這個角度,直接蹲下
#define IMU_SLOW_MS       200    // 小傾斜時,馬達走到位置的時間(慢)
#define IMU_FAST_MS       60     // 傾斜到 100% 時的時間(快)
#define IMU_STICK_ACTIVE  40     // 搖桿推超過這個值就算「正在操作」(0~127)

static int16_t imu_lastKP = 0;
static int16_t imu_lastKR = 0;
static uint8_t imu_posed = 0;           // 目前是不是被傾斜修正拉離站姿

// 角度(0.1 度)換成修正比例:死區內 0,到 IMU_FULL_DEG 為 +-1000
static int16_t imu_gain(int16_t a10)
{
  int16_t a = abs(a10);
  if (a <= IMU_DEAD_DEG * 10) {
    return 0;
  }
  int32_t k = (int32_t)(a - IMU_DEAD_DEG * 10) * 1000 / ((IMU_FULL_DEG - IMU_DEAD_DEG) * 10);
  if (k > 1000) {
    k = 1000;
  }
  return a10 < 0 ? -k : k;
}

// 回到站姿(從修正姿勢放回去)
static void imu_backToStand()
{
  if (imu_posed) {
    SetFrameTilt(IMU_STAND_FRAME, -1, 0, -1, 0, 120);
    imu_posed = 0;
    imu_lastKP = 0;
    imu_lastKR = 0;
  }
}

// 開關:按 PS4 的 Options 切換「傾斜感測」開 / 關。每次進入遙控模式一開始都是【關】。
uint8_t imu_isEnabled() { return imu_enabled; }   // 給 pad.ino 回報狀態用
//   (Options 本來就是「回站姿」,所以按一下會同時回站姿並切換開關;序列監控會印出 IMU ON / OFF)
void custom_imuFun()
{
  uint8_t options = (pad_getKey() == PAD_BTN_START);
  if (options && !imu_prevOptions) {
    imu_enabled = !imu_enabled;
    imu_posed = 0;
    imu_rearm();                        // 重新計冷卻(開啟後先給一段穩定時間)
    if (uart_isUartMode == 0) {
      Serial.println(imu_enabled ? F("IMU ON") : F("IMU OFF"));
    }
  }
  imu_prevOptions = options;

  if (!imu_enabled) {
    return;
  }

  // 正在操作(有按鍵、推搖桿、按 L3/R3):不干涉;如果剛好被修正拉在歪的姿勢,先放回站姿
  uint8_t busy = (pad_getKey() != 0) || (pad_getStickButtons() != 0)
              || (abs(pad_getStick(PAD_LX)) >= IMU_STICK_ACTIVE) || (abs(pad_getStick(PAD_LY)) >= IMU_STICK_ACTIVE)
              || (abs(pad_getStick(PAD_RX)) >= IMU_STICK_ACTIVE) || (abs(pad_getStick(PAD_RY)) >= IMU_STICK_ACTIVE);
  if (busy) {
    imu_backToStand();
    imu_rearm();
    return;
  }

  imu_update();
  if (!imu_present() || imu_cooling()) {
    return;
  }

  int16_t p = imu_getPitch10();
  int16_t r = imu_getRoll10();

  // 傾斜太多:直接蹲下
  if (IMU_CROUCH_FRAME >= 0 && (abs(p) >= IMU_CROUCH_DEG * 10 || abs(r) >= IMU_CROUCH_DEG * 10)) {
    SetFrameRun(IMU_CROUCH_FRAME, 400);
    imu_posed = 0;
    imu_lastKP = 0;
    imu_lastKR = 0;
    imu_rearm();
    return;
  }

  // 一般傾斜:依角度混合浮誇姿勢(變化夠大才重送,不要一直送指令)
  int16_t kP = (IMU_PITCH_FRAME >= 0) ? imu_gain(p) : 0;
  int16_t kR = (IMU_ROLL_FRAME >= 0) ? imu_gain(r) : 0;
  int16_t big = max(abs(kP), abs(kR));
  uint8_t changed = (abs(kP - imu_lastKP) >= 30) || (abs(kR - imu_lastKR) >= 30)
                 || ((kP == 0 && kR == 0) && imu_posed);
  if (changed) {
    uint16_t t = IMU_SLOW_MS - (uint32_t)(IMU_SLOW_MS - IMU_FAST_MS) * big / 1000;
    SetFrameTilt(IMU_STAND_FRAME, IMU_PITCH_FRAME, kP, IMU_ROLL_FRAME, kR, t);
    imu_lastKP = kP;
    imu_lastKR = kR;
    imu_posed = (kP != 0 || kR != 0);
  }
}

// 蘑菇頭(搖桿)範例:目前都沒有動作,要用時把 // 拿掉,換成你的幀編號即可。
// 三組資料互相獨立:
//   pad_getKey()              一般按鍵(下面的 custom_gamepadKeyFun_kondo)
//   pad_getStick(軸)          推搖桿,-128 ~ 127,Y 向上為正
//   pad_getStickButtons()     按下搖桿 L3 / R3
//
// 每支搖桿有兩組判斷,結果都存成變數(1 = 是,0 = 不是):
//
//   【半邊】LS_UP / LS_DOWN / LS_LEFT / LS_RIGHT(右搖桿 RS_ 開頭)
//      往那個方向推過死區就是 1,斜推時相鄰兩個方向會同時是 1(例如右上 = UP 和 RIGHT)。
//      LS_UP 就是「上半邊」(右上和左上都算)。適合用 if 判斷,也給 R2 / L2 使用。
//
//   【單一方向】LS_DIR / RS_DIR(給 switch 用)
//      同一時間只會是一個:DIR_CENTER / DIR_UP / DIR_DOWN / DIR_LEFT / DIR_RIGHT
//      斜推時,哪個軸推得多就算哪個方向。
//
//   LS_CENTER / RS_CENTER:X、Y 都在死區內(沒推)
#define STICK_DEAD  60          // 死區大小(0~127),愈大愈要推多一點才算

uint8_t LS_UP, LS_DOWN, LS_LEFT, LS_RIGHT, LS_CENTER;
uint8_t RS_UP, RS_DOWN, RS_LEFT, RS_RIGHT, RS_CENTER;

enum { DIR_CENTER = 0, DIR_UP, DIR_DOWN, DIR_LEFT, DIR_RIGHT };
uint8_t LS_DIR, RS_DIR;

// 讀搖桿,更新上面所有變數
void custom_stickUpdate()
{
  int lx = pad_getStick(PAD_LX);      // 左(-) 右(+)
  int ly = pad_getStick(PAD_LY);      // 下(-) 上(+)
  int rx = pad_getStick(PAD_RX);
  int ry = pad_getStick(PAD_RY);

  // 半邊
  LS_RIGHT = (lx >=  STICK_DEAD);
  LS_LEFT  = (lx <= -STICK_DEAD);
  LS_UP    = (ly >=  STICK_DEAD);
  LS_DOWN  = (ly <= -STICK_DEAD);
  LS_CENTER = !(LS_UP || LS_DOWN || LS_LEFT || LS_RIGHT);

  RS_RIGHT = (rx >=  STICK_DEAD);
  RS_LEFT  = (rx <= -STICK_DEAD);
  RS_UP    = (ry >=  STICK_DEAD);
  RS_DOWN  = (ry <= -STICK_DEAD);
  RS_CENTER = !(RS_UP || RS_DOWN || RS_LEFT || RS_RIGHT);

  // 單一方向(哪個軸推得多就算哪個)
  if (LS_CENTER)                    LS_DIR = DIR_CENTER;
  else if (abs(lx) >= abs(ly))      LS_DIR = (lx >= 0) ? DIR_RIGHT : DIR_LEFT;
  else                              LS_DIR = (ly >= 0) ? DIR_UP : DIR_DOWN;

  if (RS_CENTER)                    RS_DIR = DIR_CENTER;
  else if (abs(rx) >= abs(ry))      RS_DIR = (rx >= 0) ? DIR_RIGHT : DIR_LEFT;
  else                              RS_DIR = (ry >= 0) ? DIR_UP : DIR_DOWN;
}

void custom_stickFun()
{
  custom_stickUpdate();

  // ---------- 按下搖桿(L3 / R3)----------
  switch (pad_getStickButtons()) {
    case PAD_L3:                    // 只按 L3(左搖桿按下)
      do {
        // SetFrameRun(1, 100);      // 動作 1
        // SetFrameRun(4, 90);       // 動作 2
      } while (pad_getStickButtons() == PAD_L3 && pad_getKey() == 0);
      // SetFrameRun(1, 150);      // 回站姿(幀 1)
      break;
    case PAD_R3:                    // 只按 R3(右搖桿按下)
      do {
        // SetFrameRun(1, 100);      // 動作 1
        // SetFrameRun(1, 90);       // 動作 2
      } while (pad_getStickButtons() == PAD_R3 && pad_getKey() == 0);
      // SetFrameRun(1, 150);      // 回站姿(幀 1)
      break;
    case PAD_L3 | PAD_R3:           // 兩個一起按
      do {
        // SetFrameRun(1, 100);      // 動作 1
        // SetFrameRun(1, 90);       // 動作 2
      } while (pad_getStickButtons() == (PAD_L3 | PAD_R3) && pad_getKey() == 0);
      // SetFrameRun(1, 150);      // 回站姿(幀 1)
      break;
    default:                        // 都沒按
      break;
  }

  // ---------- 左搖桿 4 個方向 ----------
  // 連續動作:用 do while,條件放「搖桿還在這個方向、而且沒按其他按鍵」,
  // 內層一定要呼叫 custom_stickUpdate(),不然出不來。
  switch (LS_DIR) {
    case DIR_UP:
      do {
        // SetFrameRun(1, 100);      // 動作 1
        // SetFrameRun(1, 90);       // 動作 2
        custom_stickUpdate();
      } while (LS_DIR == DIR_UP && pad_getKey() == 0);
      // SetFrameRun(1, 150);      // 回站姿(幀 1)
      break;
    case DIR_DOWN:
      do {
        // SetFrameRun(1, 100);
        // SetFrameRun(1, 90);
        custom_stickUpdate();
      } while (LS_DIR == DIR_DOWN && pad_getKey() == 0);
      // SetFrameRun(1, 150);      // 回站姿(幀 1)
      break;
    case DIR_LEFT:
      do {
        // SetFrameRun(1, 100);
        // SetFrameRun(1, 90);
        custom_stickUpdate();
      } while (LS_DIR == DIR_LEFT && pad_getKey() == 0);
      // SetFrameRun(1, 150);      // 回站姿(幀 1)
      break;
    case DIR_RIGHT:
      do {
        // SetFrameRun(1, 100);
        // SetFrameRun(1, 90);
        custom_stickUpdate();
      } while (LS_DIR == DIR_RIGHT && pad_getKey() == 0);
      // SetFrameRun(1, 150);      // 回站姿(幀 1)
      break;
    default:                        // DIR_CENTER:沒推
      break;
  }

  // ---------- 右搖桿 4 個方向 ----------
  switch (RS_DIR) {
    case DIR_UP:
      do {
        // SetFrameRun(1, 100);
        // SetFrameRun(1, 90);
        custom_stickUpdate();
      } while (RS_DIR == DIR_UP && pad_getKey() == 0);
      // SetFrameRun(1, 150);      // 回站姿(幀 1)
      break;
    case DIR_DOWN:
      do {
        // SetFrameRun(1, 100);
        // SetFrameRun(1, 90);
        custom_stickUpdate();
      } while (RS_DIR == DIR_DOWN && pad_getKey() == 0);
      // SetFrameRun(1, 150);      // 回站姿(幀 1)
      break;
    case DIR_LEFT:
      do {
        // SetFrameRun(1, 100);
        // SetFrameRun(1, 90);
        custom_stickUpdate();
      } while (RS_DIR == DIR_LEFT && pad_getKey() == 0);
      // SetFrameRun(1, 150);      // 回站姿(幀 1)
      break;
    case DIR_RIGHT:
      do {
        // SetFrameRun(1, 100);
        // SetFrameRun(1, 90);
        custom_stickUpdate();
      } while (RS_DIR == DIR_RIGHT && pad_getKey() == 0);
      // SetFrameRun(1, 150);      // 回站姿(幀 1)
      break;
    default:                        // DIR_CENTER:沒推
      break;
  }
}

int16_t custom_gamepadKeyFun_kondo()
{
  uint16_t key = pad_getKeyEvent();

  if (key != PAD_BTN_NONE) {   // 沒按鍵就什麼都不做
    switch (key) {

      // ---------- 移動 ----------
      case PAD_BTN_UP:          // 前進
        SetFrameRun(2, 100);
        SetFrameRun(3, 100);
        do {
          SetFrameRun(4, 90);
          SetFrameRun(5, 110);
          SetFrameRun(6, 90);
          SetFrameRun(7, 110);
        } while (pad_getKey() > 0);
        SetFrameRun(7, 50);
        SetFrameRun(4, 65);
        SetFrameRun(1, 10);
        break;

      case PAD_BTN_DOWN: {      // 後退
        uint8_t jumpFlag = 0;
        SetFrameRun(2, 80);
        SetFrameRun(3, 80);
        do {
          SetFrameRun(8, 70);
          SetFrameRun(9, 110);
          if (pad_getKey() != PAD_BTN_DOWN) {
            jumpFlag = 1;
            break;
          }
          SetFrameRun(10, 70);
          SetFrameRun(11, 110);
        } while (pad_getKey() == PAD_BTN_DOWN);

        if (jumpFlag == 1) {
          SetFrameRun(11, 20);
          SetFrameRun(10, 85);
        } else {
          SetFrameRun(9, 20);
          SetFrameRun(8, 85);
        }
        SetFrameRun(2, 150);
        SetFrameRun(1, 10);
        break;
      }

      case PAD_BTN_RIGHT:       // 右轉
        do {
          SetFrameRun(14, 90);
          SetFrameRun(12, 80);
        } while (pad_getKey() > 0);
        SetFrameRun(12, 50);
        SetFrameRun(1, 100);
        break;

      case PAD_BTN_LEFT:        // 左轉
        do {
          SetFrameRun(13, 90);
          SetFrameRun(12, 80);
        } while (pad_getKey() > 0);
        SetFrameRun(12, 50);
        SetFrameRun(1, 100);
        break;

      // ---------- 單鍵動作 ----------
      case PAD_BTN_CROSS:
        SetFrameRun(35, 100);
        do {
          SetFrameRun(36, 20);
        } while (pad_getKey() > 0);
        SetFrameRun(1, 150);
        break;

      case PAD_BTN_CIRCLE:
        SetFrameRun(23, 200);
        do {
          SetFrameRun(24, 20);
        } while (pad_getKey() > 0);
        SetFrameRun(1, 150);
        break;

      case PAD_BTN_SQUARE:
        SetFrameRun(25, 200);
        do {
          SetFrameRun(26, 20);
        } while (pad_getKey() > 0);
        SetFrameRun(1, 150);
        break;

      case PAD_BTN_R1:
        SetFrameRun(27, 130);
        do {
          SetFrameRun(28, 20);
        } while (pad_getKey() > 0);
        SetFrameRun(1, 150);
        break;

      case PAD_BTN_L1:
        SetFrameRun(29, 130);
        do {
          SetFrameRun(30, 20);
        } while (pad_getKey() > 0);
        SetFrameRun(1, 150);
        break;

      case PAD_BTN_L2:               // L2:按住期間同時看左、右搖桿有沒有往上
        do {
          custom_stickUpdate();      // 重新讀搖桿,更新 LS_UP / RS_UP ...

          if (LS_UP) {               // L2 + 左搖桿往上
            do {
              // SetFrameRun(1, 100);      // 動作 1
              // SetFrameRun(1, 90);       // 動作 2
              custom_stickUpdate();        // 內層要自己更新搖桿,不然出不來
            } while (pad_getKey() > 0 && LS_UP);

          } else if (RS_UP) {        // L2 + 右搖桿往上(左搖桿沒往上時才會判斷到這裡)
            do {
              // SetFrameRun(1, 100);      // 動作 1
              // SetFrameRun(1, 90);       // 動作 2
              custom_stickUpdate();
            } while (pad_getKey() > 0 && RS_UP);

          } else {                   // 兩支搖桿都沒往上:原本 L2 的動作
            do {
              SetFrameRun(15, 100);
              SetFrameRun(16, 90);
              custom_stickUpdate();
            } while (pad_getKey() > 0 && !LS_UP && !RS_UP);
          }
        } while (pad_getKey() > 0);
        SetFrameRun(15, 100);
        SetFrameRun(1, 150);
        break;

      case PAD_BTN_R2:               // R2:按住期間同時看左、右搖桿有沒有往上
        do {
          custom_stickUpdate();      // 重新讀搖桿,更新 LS_UP / RS_UP ...

          if (LS_UP) {               // R2 + 左搖桿往上
            do {
              // SetFrameRun(1, 100);      // 動作 1
              // SetFrameRun(1, 90);       // 動作 2
              custom_stickUpdate();        // 內層要自己更新搖桿,不然出不來
            } while (pad_getKey() > 0 && LS_UP);

          } else if (RS_UP) {        // R2 + 右搖桿往上(左搖桿沒往上時才會判斷到這裡)
            do {
              // SetFrameRun(1, 100);      // 動作 1
              // SetFrameRun(1, 90);       // 動作 2
              custom_stickUpdate();
            } while (pad_getKey() > 0 && RS_UP);

          } else {                   // 兩支搖桿都沒往上:原本 R2 的動作
            do {
              SetFrameRun(15, 100);
              SetFrameRun(17, 90);
              custom_stickUpdate();
            } while (pad_getKey() > 0 && !LS_UP && !RS_UP);
          }
        } while (pad_getKey() > 0);
        SetFrameRun(15, 100);
        SetFrameRun(1, 150);
        break;

      // ---------- 系統 ----------
      case PAD_BTN_STOP:             // PS4 Share:馬達放鬆
        uart_disableMotor();
        break;

      case PAD_BTN_START:            // PS4 Options:回站姿
        SetFrameRun(1, 500);
        break;

      default:
        break;
    }
  }

  delay(10);
  return 0;
}
 