/*
custom.ino  -- 機器人「按鍵 -> 動作」對照表(你最常改的檔案)

這台機器人原本用 Kondo 接收器,現在改成 PS4 手把。
動作的邏輯和原本【完全一樣】,只是按鍵改由 ESP32 的 PS4 橋接送來(定義見 pad.h),
KONDO_BUTTON_xxx 都換成 PAD_BTN_xxx(按鍵碼的數值一樣)。

SetFrameRun(幀編號, 時間ms) 會播放 motor.h 裡的一個動作幀。
幀編號對應 MotorAdj 調整工具裡的 frame 編號。

流程:
  開機 -> 等待按下 START(PS4 的 Options) -> 進入遙控模式
  PS4 的 Share 鍵 = STOP,所有馬達放鬆
  接上電腦的 MotorAdj 工具時,自動離開遙控模式

兩套動作(mode):
  mode 1 = 一般動作(開機預設)
  mode 2 = 第二組動作(幀 60~73)
  切換:右搖桿下推 + △ 按住約 300 毫秒 -> mode 2;右搖桿上推 + △ 按住約 300 毫秒 -> 回 mode 1
*/

#include "motor.h"
#include "custom.h"
#include "pad.h"

int mode = 1;
int timer = 0;
volatile uint8_t remoteRunning = 0;  // 1 = 已按過 Options,進入遙控模式(偵錯行會顯示 RUN / WAIT)
#define SWITCH_HOLD_MS 300  // 「右搖桿 + △」要按住多久才切換 mode(毫秒)
#define STICKBTN_MS 80      // L3 / R3 要連續按著多久才算(毫秒),濾掉雜訊
#define STICKBTN_NEAR 40    // 同一支搖桿推超過這個值(0~127),它的 L3 / R3 就當作雜訊

// 在序列埠監控視窗(115200)印出目前是 mode 幾。接上電腦用 MotorAdj 時不印,不影響它。
// 另外 pad.ino 的除錯行(每 200 ms 一行,PAD_DEBUG_PRINT)最後也會帶 mode=幾。
static void printMode() {
  if (uart_isUartMode == 0) {
    Serial.print(F("MODE = "));
    Serial.println(mode);
  }
}

void custom_setup() {
  pad_init();
}

void custom_loop() {
  remoteRunning = 0;
  uart_disableMotor();

  // 等待 START(PS4 Options)才開始動作(期間如果接上電腦,就交給電腦處理)
  while (pad_getKeyEvent() != PAD_BTN_START) {
    if (uart_isConnectToPC() != 0) {
      return;
    }
  }

  remoteRunning = 1;
  printMode();  // 按下 START 進入遙控模式:印出目前是 mode 幾

  while (uart_isUartMode == 0) {
    custom_stickFun();         // 先處理 L3 / R3 / 搖桿(含「搖桿 + △」的組合)
    custom_gamepadKeyFun();    // 再處理一般按鍵

    if (uart_isConnectToPC() != 0) {
      return;
    }
  }
}

// ====== 輔助函式 ======
// keyHas() 搖桿動作會用到(判斷 △ 有沒有按著);MotorAdj 的「方塊編輯」產生的組合鍵程式也會用到。

// 左搖桿左 / 右的動作用:沒按鍵,或只按 L2 / R2(組合鍵),都讓動作繼續;按了別的鍵就跳出去讓按鍵動作接手
static uint8_t stickKeyOkForCombo() {
  uint16_t k = pad_getKey();
  return k == 0 || k == PAD_BTN_L2 || k == PAD_BTN_R2;
}

// 「有按著某個鍵」:目前按著的鍵有包含 mask 裡的所有按鍵。
// Share(0x000F)和 Options(0x0170)是特殊組合碼,裡面剛好包含別的按鍵位元,所以不算。
static uint8_t keyHas(uint16_t mask) {
  uint16_t k = pad_getKey();
  return k != PAD_BTN_STOP && k != PAD_BTN_START && (k & mask) == mask;
}

// ====== 搖桿(蘑菇頭)與 L3 / R3 ======
// PS4 的組合鍵比較難按,下面這幾個原本的組合鍵,換成搖桿 / L3 / R3(動作內容和原本一樣):
//   原本 ← + ↓                      →  L3
//   原本 ○ + ✕                      →  R3
//   原本 □ + ✕                      →  左搖桿前推 + △
//   原本 △ + □(再加 R1 → 幀 46)    →  左搖桿左推 + △(按著 △ 多做幀 46)
//   原本 → + ↓ 按住 1 秒(到 mode 2)  →  右搖桿下推 + △ 按住 300 毫秒
//   原本 ↑ + → 按住 1 秒(回 mode 1)  →  右搖桿上推 + △ 按住 300 毫秒
// 沒有搬過來(先不用):原本 ○ + △、↑ + ←(程式用註解留在 custom_gamepadKeyFun() 裡)。
//
// 三組資料互相獨立:
//   pad_getKey()              一般按鍵(下面的 custom_gamepadKeyFun)
//   pad_getStick(軸)          推搖桿,-128 ~ 127,Y 向上為正
//   pad_getStickButtons()     按下搖桿 L3 / R3
//
// 每支搖桿有兩組判斷,結果都存成變數(1 = 是,0 = 不是):
//   【半邊】LS_UP / LS_DOWN / LS_LEFT / LS_RIGHT(右搖桿 RS_ 開頭)
//      往那個方向推過死區就是 1,斜推時相鄰兩個方向會同時是 1。
//   【單一方向】LS_DIR / RS_DIR(給 switch 用)
//      同一時間只會是一個:DIR_CENTER / DIR_UP / DIR_DOWN / DIR_LEFT / DIR_RIGHT
//   LS_CENTER / RS_CENTER:X、Y 都在死區內(沒推)
#define STICK_DEAD 60  // 死區大小(0~127),愈大愈要推多一點才算

uint8_t LS_UP, LS_DOWN, LS_LEFT, LS_RIGHT, LS_CENTER;
uint8_t RS_UP, RS_DOWN, RS_LEFT, RS_RIGHT, RS_CENTER;

enum { DIR_CENTER = 0,
       DIR_UP,
       DIR_DOWN,
       DIR_LEFT,
       DIR_RIGHT };
uint8_t LS_DIR, RS_DIR;

// 讀搖桿,更新上面所有變數
void custom_stickUpdate() {
  int lx = pad_getStick(PAD_LX);  // 左(-) 右(+)
  int ly = pad_getStick(PAD_LY);  // 下(-) 上(+)
  int rx = pad_getStick(PAD_RX);
  int ry = pad_getStick(PAD_RY);

  LS_RIGHT = (lx >= STICK_DEAD);
  LS_LEFT = (lx <= -STICK_DEAD);
  LS_UP = (ly >= STICK_DEAD);
  LS_DOWN = (ly <= -STICK_DEAD);
  LS_CENTER = !(LS_UP || LS_DOWN || LS_LEFT || LS_RIGHT);

  RS_RIGHT = (rx >= STICK_DEAD);
  RS_LEFT = (rx <= -STICK_DEAD);
  RS_UP = (ry >= STICK_DEAD);
  RS_DOWN = (ry <= -STICK_DEAD);
  RS_CENTER = !(RS_UP || RS_DOWN || RS_LEFT || RS_RIGHT);

  if (LS_CENTER) LS_DIR = DIR_CENTER;
  else if (abs(lx) >= abs(ly)) LS_DIR = (lx >= 0) ? DIR_RIGHT : DIR_LEFT;
  else LS_DIR = (ly >= 0) ? DIR_UP : DIR_DOWN;

  if (RS_CENTER) RS_DIR = DIR_CENTER;
  else if (abs(rx) >= abs(ry)) RS_DIR = (rx >= 0) ? DIR_RIGHT : DIR_LEFT;
  else RS_DIR = (ry >= 0) ? DIR_UP : DIR_DOWN;
}

void custom_stickFun() {
  custom_stickUpdate();

  // ---------- 按下搖桿(L3 / R3)----------
  // 連續動作:按著不放會持續,放開收尾。條件放「搖桿還按著、而且沒按 Share」。
  //
  // 防雜訊(用力推搖桿時,手指會順便壓到搖桿鍵,或接點瞬間彈跳):
  //   1. L3 / R3 要【連續按著】STICKBTN_MS 毫秒以上才算,瞬間的碰觸忽略。
  //   2. 同一支搖桿正在被推動(推過 STICKBTN_NEAR)時,它的 L3 / R3 不算。
  static uint32_t l3Since = 0, r3Since = 0;
  uint8_t sbRaw = pad_getStickButtons();
  uint8_t sbOk = 0;
  if ((sbRaw & PAD_L3) && abs(pad_getStick(PAD_LX)) < STICKBTN_NEAR && abs(pad_getStick(PAD_LY)) < STICKBTN_NEAR) {
    if (l3Since == 0) l3Since = millis();
    if (millis() - l3Since >= STICKBTN_MS) sbOk |= PAD_L3;
  } else {
    l3Since = 0;
  }
  if ((sbRaw & PAD_R3) && abs(pad_getStick(PAD_RX)) < STICKBTN_NEAR && abs(pad_getStick(PAD_RY)) < STICKBTN_NEAR) {
    if (r3Since == 0) r3Since = millis();
    if (millis() - r3Since >= STICKBTN_MS) sbOk |= PAD_R3;
  } else {
    r3Since = 0;
  }
  switch (sbOk) {
    case PAD_L3:  // 只按 L3(原本 ← + ↓)
      if (mode == 1) {
        SetFrameRun(30, 150);
        SetFrameRun(22, 200);
        SetFrameRun(23, 200);
        SetFrameRun(24, 200);
        do {
          SetFrameRun(53, 20);
        } while (pad_getStickButtons() == PAD_L3 && pad_getKey() != PAD_BTN_STOP);
        SetFrameRun(25, 150);
        SetFrameRun(1, 150);
      } else if (mode == 2) {
        SetFrameRun(52, 300);
        SetFrameRun(55, 300);
        SetFrameRun(56, 200);
        do {
          SetFrameRun(57, 20);
        } while (pad_getStickButtons() == PAD_L3 && pad_getKey() != PAD_BTN_STOP);
        SetFrameRun(55, 100);
        SetFrameRun(60, 150);
        SetFrameRun(61, 150);
      }
      break;

    case PAD_R3:  // 只按 R3(原本 ○ + ✕)
      if (mode == 1) {
        SetFrameRun(30, 150);
        SetFrameRun(26, 200);
        SetFrameRun(27, 200);
        do {
          SetFrameRun(28, 20);
          // SetFrameRun(54, 20);
        } while (pad_getStickButtons() == PAD_R3 && pad_getKey() != PAD_BTN_STOP);
        SetFrameRun(29, 150);
        SetFrameRun(1, 150);
      } else if (mode == 2) {
        SetFrameRun(52, 300);
        SetFrameRun(58, 300);
        SetFrameRun(72, 200);
        do {
          SetFrameRun(73, 20);
        } while (pad_getStickButtons() == PAD_R3 && pad_getKey() != PAD_BTN_STOP);
        SetFrameRun(72, 100);
        SetFrameRun(60, 150);
        SetFrameRun(61, 150);
      }
      break;

    default:  // 都沒按,或 L3 + R3 一起按(沒有動作)
      break;
  }

  // ---------- 左搖桿 ----------
  // 「搖桿 + △」的組合:推搖桿 + 按 △ 啟動,之後只看 △ 還按不按著,搖桿可以放開。
  // 「左搖桿左推 + △」:推搖桿 + 按 △ 啟動,之後一直看搖桿有沒有還推著(△ 放開也繼續,只是少做幀 46)。
  switch (LS_DIR) {
    case DIR_UP:  // 左搖桿前推 + △(原本 □ + ✕)。只有 mode 1
      if (mode == 1 && keyHas(PAD_BTN_TRIANGLE)) {
        SetFrameRun(47, 400);
        SetFrameRun(48, 80);
        SetFrameRun(49, 280);
        do {
          SetFrameRun(50, 20);
          custom_stickUpdate();
        } while (keyHas(PAD_BTN_TRIANGLE) && pad_getKey() != PAD_BTN_STOP);  // 只看 △ 還按著,搖桿可以放開
        SetFrameRun(59, 200);
      }
      break;

    case DIR_LEFT:  // 左搖桿左推 + △(原本 △ + □,之後是下推)。幀 45 重複,按著 △ 會多做幀 46。只有 mode 1
      if (mode == 1 && keyHas(PAD_BTN_TRIANGLE)) {
        do {
          SetFrameRun(45, 20);
          if (keyHas(PAD_BTN_TRIANGLE)) {
            SetFrameRun(46, 500);
          } else {
            SetFrameRun(45, 20);
          }
          custom_stickUpdate();
        } while (LS_DIR == DIR_LEFT && pad_getKey() != PAD_BTN_STOP);  // 必殺技:一直看搖桿有沒有還推著
        SetFrameRun(1, 150);
      }
      break;

    case DIR_DOWN:  // 左搖桿下推:還沒有動作(之後放另外一招)。手把的大力震動已經有了:下推 + △ 就會震
          if (mode == 1 && keyHas(PAD_BTN_TRIANGLE)) {
        SetFrameRun(39, 400);
        SetFrameRun(40, 150);
        do {
          SetFrameRun(41, 20);
          custom_stickUpdate();
        } while (keyHas(PAD_BTN_TRIANGLE) && pad_getKey() != PAD_BTN_STOP);  // 只看 △ 還按著,搖桿可以放開
        SetFrameRun(43, 200);
      }
      break;

    default:
      break;
  }

  // ---------- 右搖桿(切換 mode)----------
  // 右搖桿「下推」+ △ 按住約 300 毫秒:切到 mode 2(原本 → + ↓)。
  // 右搖桿「上推」+ △ 按住約 300 毫秒:回到 mode 1(原本 ↑ + →)。
  //   - 開始之後只看 △ 還按不按著,搖桿可以放開;不到 300 毫秒就放開 △ 不算。
  //   - 一次按住只切換一次。已經在目標 mode 時不做任何事。
  //   - 切換的瞬間,手把會震(切到 mode 1 一下、切到 mode 2 兩下):Micro 把 mode 回報給 ATOM Lite,
  //     所以要接 D9 -> G32 那條線;沒接線時,ATOM Lite 是依手把的動作推測(見 ESP32_PS4_Bridge.ino)。
  if ((RS_DIR == DIR_DOWN && mode == 1 && pad_getKey() == PAD_BTN_TRIANGLE) ||
      (RS_DIR == DIR_UP && mode == 2 && pad_getKey() == PAD_BTN_TRIANGLE)) {
    uint8_t target = (RS_DIR == DIR_DOWN) ? 2 : 1;
    if (uart_isUartMode == 0) {
      Serial.print(F("SWITCH hold -> mode "));
      Serial.println(target);
    }
    uint32_t t0 = millis();
    uint8_t switched = 0;
    do {
      if (!switched && millis() - t0 >= SWITCH_HOLD_MS) {
        switched = 1;
        mode = target;
        printMode();
        if (target == 2) {
          SetFrameRun(60, 250);
          SetFrameRun(61, 100);
        } else {
          SetFrameRun(1, 250);
        }
      }
      custom_stickUpdate();
    } while (pad_getKey() == PAD_BTN_TRIANGLE);  // 只看「只按著 △」,搖桿可以放開(Share 也會結束)
  }
}

// ====== 按鍵 -> 動作(和原本 Kondo 版一樣;組合鍵換成搖桿 / L3 / R3 的部分見上面)======
// 迴圈的條件是「pad_getKey() == 這個 case 的鍵」:只有這個鍵(單獨)還按著,動作才繼續;
// 放開、或多按了別的鍵,就跳出去。(兩招必殺技除外,見 custom_stickFun())
int16_t custom_gamepadKeyFun() {
  uint16_t buttonData = pad_getKeyEvent();  // 動作播放期間按過又放開的鍵,會補回傳一次

  if (buttonData != PAD_BTN_NONE) {  // 沒按鍵就什麼都不做
    switch (buttonData) {

      // ---------- 方向鍵(動作名稱以機器人實際動作為準,這裡只標幀編號)----------
      case PAD_BTN_RIGHT:  // →(幀 14 搭配 12;mode 2:幀 64 搭配 62)
        if (mode == 1) {
          do {
            SetFrameRun(14, 100);
            SetFrameRun(12, 100);
          } while (pad_getKey() == PAD_BTN_RIGHT);
          SetFrameRun(12, 50);
          SetFrameRun(59, 100);
        } else if (mode == 2) {
          do {
            SetFrameRun(64, 60);
            SetFrameRun(62, 80);
          } while (pad_getKey() == PAD_BTN_RIGHT);
          SetFrameRun(60, 50);
          SetFrameRun(61, 100);
        }
        break;

      case PAD_BTN_DOWN:  // ↓(幀 2、3 起步,然後 4~7 重複;mode 2 沒有動作)
        if (mode == 1) {
          uint8_t jumpFlag = 0;

          SetFrameRun(2, 100);
          SetFrameRun(3, 80);
          do {
            SetFrameRun(4, 75);
            SetFrameRun(5, 105);

            if (pad_getKey() != PAD_BTN_DOWN) {
              jumpFlag = 1;
              break;
            }
            SetFrameRun(6, 75);
            SetFrameRun(7, 105);
          } while (pad_getKey() == PAD_BTN_DOWN);

          if (jumpFlag == 1) {
            SetFrameRun(5, 50);
            SetFrameRun(6, 65);
          } else {
            SetFrameRun(7, 50);
            SetFrameRun(4, 65);
          }

          SetFrameRun(59, 150);
        }
        break;

      case PAD_BTN_LEFT:  // ←(幀 13 搭配 12;mode 2:幀 63 搭配 62)
        if (mode == 1) {
          do {
            SetFrameRun(13, 100);
            SetFrameRun(12, 100);
          } while (pad_getKey() == PAD_BTN_LEFT);
          SetFrameRun(12, 50);
          SetFrameRun(59, 100);
        } else if (mode == 2) {
          do {
            SetFrameRun(63, 60);
            SetFrameRun(62, 80);
          } while (pad_getKey() == PAD_BTN_LEFT);
          SetFrameRun(60, 50);
          SetFrameRun(61, 100);
        }
        break;

      case PAD_BTN_UP:  // ↑(幀 2、3 起步,然後 8~11 重複;走路途中加按 L1 / R1 會換成另一個動作,幀 48 / 49)
        if (mode == 1) {
          uint8_t jumpFlag = 0;

          SetFrameRun(2, 100);
          SetFrameRun(3, 80);
          do {
            SetFrameRun(8, 65);

            if (pad_getKey() == (PAD_BTN_L1 | PAD_BTN_UP)) {
              SetFrameRun(48, 90);
            } else {
              SetFrameRun(9, 95);
            }

            buttonData = pad_getKey();
            if ((buttonData != PAD_BTN_UP) &&
                (buttonData != (PAD_BTN_R1 | PAD_BTN_UP)) &&
                (buttonData != (PAD_BTN_L1 | PAD_BTN_UP))) {
              jumpFlag = 1;
              break;
            }
            SetFrameRun(10, 65);

            if (pad_getKey() == (PAD_BTN_R1 | PAD_BTN_UP)) {
              SetFrameRun(49, 90);
            } else {
              SetFrameRun(11, 95);
            }

            buttonData = pad_getKey();
          } while ((buttonData == PAD_BTN_UP) ||
                   (buttonData == (PAD_BTN_R1 | PAD_BTN_UP)) ||
                   (buttonData == (PAD_BTN_L1 | PAD_BTN_UP)));

          if (jumpFlag == 1) {
            SetFrameRun(10, 65);
          } else {
            SetFrameRun(8, 65);
          }
          SetFrameRun(59, 150);
        }
        break;

      // ---------- 單鍵動作 ----------
      case PAD_BTN_TRIANGLE:
        // 搖桿正推在「搖桿 + △」的方向上(左前、左左、左下、右上、右下),這一下是組合的一部分,不當成單獨的 △
        if (mode == 1 && !(LS_DIR == DIR_UP || LS_DIR == DIR_LEFT || LS_DIR == DIR_DOWN || RS_DIR == DIR_UP || RS_DIR == DIR_DOWN)) {
          SetFrameRun(20, 800);
          SetFrameRun(21, 50);
        }
        break;

      case PAD_BTN_CROSS:
        if (mode == 1) {
          SetFrameRun(18, 100);
          do {
            SetFrameRun(19, 20);
          } while (pad_getKey() == PAD_BTN_CROSS);
          SetFrameRun(1, 150);
        }
        break;

      case PAD_BTN_CIRCLE:
        if (mode == 1) {
          SetFrameRun(31, 50);
          do {
            SetFrameRun(32, 20);
          } while (pad_getKey() == PAD_BTN_CIRCLE);
          SetFrameRun(59, 150);
        } else if (mode == 2) {
          SetFrameRun(68, 50);
          do {
            SetFrameRun(69, 20);
          } while (pad_getKey() == PAD_BTN_CIRCLE);
          SetFrameRun(60, 150);
          SetFrameRun(61, 150);
        }
        break;

      case PAD_BTN_SQUARE:
        if (mode == 1) {
          SetFrameRun(33, 50);
          do {
            SetFrameRun(34, 20);
          } while (pad_getKey() == PAD_BTN_SQUARE);
          SetFrameRun(59, 150);
        } else if (mode == 2) {
          SetFrameRun(70, 50);
          do {
            SetFrameRun(71, 20);
          } while (pad_getKey() == PAD_BTN_SQUARE);
          SetFrameRun(60, 150);
          SetFrameRun(61, 150);
        }
        break;

      case PAD_BTN_R1:
        if (mode == 1) {
          SetFrameRun(37, 100);
          do {
            SetFrameRun(38, 20);
          } while (pad_getKey() == PAD_BTN_R1);
          SetFrameRun(59, 150);
        }
        break;

      case PAD_BTN_L1:
        if (mode == 1) {
          SetFrameRun(35, 100);
          do {
            SetFrameRun(36, 20);
          } while (pad_getKey() == PAD_BTN_L1);
          SetFrameRun(59, 150);
        }
        break;

      // L2 / R2:按住就一直做(幀 16 / 17 搭配 15),放開回站姿。
      case PAD_BTN_L2:
        if (mode == 1) {
          do {
            SetFrameRun(16, 110);
            SetFrameRun(15, 70);
          } while (pad_getKey() == PAD_BTN_L2);
          SetFrameRun(1, 150);
        } else if (mode == 2) {
          do {
            SetFrameRun(67, 110);
            SetFrameRun(65, 70);
          } while (pad_getKey() == PAD_BTN_L2);
          SetFrameRun(60, 150);
          SetFrameRun(61, 150);
        }
        break;

      case PAD_BTN_R2:
        if (mode == 1) {
          do {
            SetFrameRun(17, 110);
            SetFrameRun(15, 70);
          } while (pad_getKey() == PAD_BTN_R2);
          SetFrameRun(1, 150);
        } else if (mode == 2) {
          do {
            SetFrameRun(66, 110);
            SetFrameRun(65, 70);
          } while (pad_getKey() == PAD_BTN_R2);
          SetFrameRun(60, 150);
          SetFrameRun(61, 150);
        }
        break;

      // ---------- 系統 ----------
      case PAD_BTN_STOP:  // PS4 Share:馬達放鬆
        uart_disableMotor();
        break;

      case PAD_BTN_START:  // PS4 Options:回站姿
        if (mode == 1) {
          SetFrameRun(1, 500);
        } else if (mode == 2) {
          SetFrameRun(60, 250);
          SetFrameRun(61, 100);
        }
        break;

      // ---------- 組合鍵 ----------
      // 下面這些組合鍵已經換成搖桿 / L3 / R3(見 custom_stickFun()):
      //   ← + ↓ → L3        ○ + ✕ → R3        □ + ✕ → 左搖桿前推 + △
      //   △ + □(+ R1)→ 左搖桿左推 + △
      //   → + ↓ 按住 1 秒 → 右搖桿下推 + △ 按住 300 毫秒(mode 2);↑ + → 按住 1 秒 → 右搖桿上推 + △ 按住 300 毫秒(mode 1)
      //
      // 先不用(原本的動作留在這裡,要用的時候把每一行最前面的 // 拿掉):
      //
      // case PAD_BTN_CIRCLE | PAD_BTN_TRIANGLE:  // ○ + △
      //   if (mode == 1) {
      //     SetFrameRun(39, 50);
      //     SetFrameRun(40, 100);
      //     do {
      //       SetFrameRun(41, 20);
      //     } while (pad_getKey() == (PAD_BTN_CIRCLE | PAD_BTN_TRIANGLE));
      //     SetFrameRun(39, 200);
      //     SetFrameRun(1, 150);
      //   }
      //   break;
      //
      // case PAD_BTN_UP | PAD_BTN_LEFT:  // ↑ + ←
      //   if (mode == 1) {
      //     SetFrameRun(42, 50);
      //     SetFrameRun(43, 100);
      //     do {
      //       SetFrameRun(44, 20);
      //     } while (pad_getKey() == (PAD_BTN_UP | PAD_BTN_LEFT));
      //     SetFrameRun(42, 200);
      //     SetFrameRun(1, 150);
      //   }
      //   break;

      default:
        break;
    }
  }

  delay(10);
  return 0;
}
