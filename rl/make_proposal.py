"""產生 custom.ino 的提案版(另存新檔,不改原檔),並產生差異檔 custom_skills.diff。"""
import difflib
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "Micro_Robot", "custom.ino")
OUT = os.path.join(HERE, "skills_deploy")
s = open(SRC, encoding="utf-8").read()

helper = '''// ====== 模擬訓練出來的 4 個技能(幀 60~75,每個技能 4 個幀、每幀 200 ms)======
// 這些幀是在電腦模擬裡練的,還沒在真機驗證:第一次請把機器人架空,確認方向再放地上。
#define SKILL_FWD 60     // 直走(往前)
#define SKILL_BACK 64    // 後退
#define SKILL_SIDE_R 68  // 往右橫移(機器人自己的右邊)
#define SKILL_SIDE_L 72  // 往左橫移
#define SKILL_MS 200     // 每個幀的時間(要改請連模擬一起改,不然動作會不一樣)

// 播放一個技能:先回站姿,4 個幀重複播放,搖桿離開這個方向(或按 Share)就回站姿。
// 一圈 4 幀一定播完才檢查,放開搖桿時不會停在半途。
static void skillPlay(uint16_t first, uint8_t dir) {
  SetFrameRun(1, SKILL_MS);
  do {
    for (uint8_t k = 0; k < 4; k++) {
      SetFrameRun(first + k, SKILL_MS);
    }
    custom_stickUpdate();
  } while (LS_DIR == dir && pad_getKey() != PAD_BTN_STOP);
  SetFrameRun(1, SKILL_MS);
}

'''
marker = "void custom_stickFun() {"
assert marker in s
s = s.replace(marker, helper + marker, 1)

a = s.index("  // ---------- 左搖桿 4 個方向 ----------")
b = s.index("  // ---------- 右搖桿 4 個方向 ----------")
new_block = '''  // ---------- 左搖桿 4 個方向 ----------
  // 上 = 直走,下 = 後退,左 / 右 = 往左 / 往右橫移(模擬訓練的技能,見上面 skillPlay)
  switch (LS_DIR) {
    case DIR_UP:
      skillPlay(SKILL_FWD, DIR_UP);
      break;
    case DIR_DOWN:
      skillPlay(SKILL_BACK, DIR_DOWN);
      break;
    case DIR_LEFT:
      skillPlay(SKILL_SIDE_L, DIR_LEFT);
      break;
    case DIR_RIGHT:
      skillPlay(SKILL_SIDE_R, DIR_RIGHT);
      break;
    default:  // DIR_CENTER:沒推
      break;
  }

'''
s2 = s[:a] + new_block + s[b:]
open(os.path.join(OUT, "custom_skills_proposal.ino"), "w", encoding="utf-8", newline="").write(s2)
orig = open(SRC, encoding="utf-8").read().splitlines(keepends=True)
diff = difflib.unified_diff(orig, s2.splitlines(keepends=True), "Micro_Robot/custom.ino(現在)", "custom_skills_proposal.ino(提案)")
open(os.path.join(OUT, "custom_skills.diff"), "w", encoding="utf-8").write("".join(diff))
print("ok")
