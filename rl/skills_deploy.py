"""
把達標的技能轉成 MotorAdj 動作檔(副本)+ motor.h(副本)。不會動你原本的任何檔案。
輸出在 rl/skills_deploy/:
  light1.2_skills.config   你的動作檔 + 技能幀(接在原本 60 幀後面)
  motor_skills.h           對應的 motor.h(位置 = 動作檔的值 + Offset,格式和 MotorAdj 輸出一樣)
  summary.json             每個技能轉換後(取整數)的模擬驗證結果
用法:python skills_deploy.py [--check-only]
"""
import json
import os
import re
import sys

import numpy as np

import export_gait as xg
import skills as sk
from robot_env import make_env

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CONFIG = os.path.join(ROOT, "Tools_MotorAdj", "light1.2.config")
MOTOR_H = os.path.join(ROOT, "Micro_Robot", "motor.h")
OUT = os.path.join(HERE, "skills_deploy")
N, T_MS = 4, 200
BASE_FRAMES = 60
DEPLOY = ["forward", "backward", "side_r", "side_l"]        # 轉身還沒練好,先不放


def motor_h_text(cfg, nframes):
    """用和 MotorAdj 一樣的格式產生 motor.h(位置 = 設定檔的值 + Offset)"""
    off = cfg["Offset"]
    head = open(MOTOR_H, encoding="utf-8").read()
    head = head[:head.index("const PROGMEM")]
    head = re.sub(r"#define MOTOR_FRAME_MAX \d+", "#define MOTOR_FRAME_MAX %d" % nframes, head)
    s = head + "const PROGMEM uint16_t motor_para[MOTOR_FRAME_MAX][MOTOR_MOTOR_MAX][MOTOR_PARA_MAX]={\n"
    for fi in range(nframes):
        s += "\t{ // frame %d\t\n" % fi
        fr = cfg["MotorPos"][fi]
        for k in range(0, 26, 5):
            s += "\t\t" + " ".join("{%d, %d, %d}," % (m[0], m[1] + off[k + j], m[2]) for j, m in enumerate(fr[k:k + 5])) + " \n"
        s += "\t},\n"
    s += "};\n"
    tail = open(MOTOR_H, encoding="utf-8").read()
    tail = tail[tail.rindex("};") + 3:]
    return s + tail


def main():
    cfg = json.load(open(CONFIG, encoding="utf-8"))
    # 1) 產生器自我檢查:用原本的動作檔重現原本的 motor.h
    mine = motor_h_text(cfg, BASE_FRAMES)
    orig = open(MOTOR_H, encoding="utf-8").read()
    same = mine.replace("\r\n", "\n") == orig.replace("\r\n", "\n")
    print("motor.h 產生器重現原檔:", "完全一致" if same else "有差異")
    if not same:
        a, b = mine.replace("\r\n", "\n").split("\n"), orig.replace("\r\n", "\n").split("\n")
        for i, (x, y) in enumerate(zip(a, b)):
            if x != y:
                print("  第 %d 行不同:\n   mine:%r\n   orig:%r" % (i + 1, x, y))
                break
        print("  行數 mine %d / orig %d" % (len(a), len(b)))
    if "--check-only" in sys.argv:
        return

    # 2) 技能 -> 馬達位置
    e = make_env("straighter", seed=0, randomize=False, push=False)
    e_rand = make_env("straighter", seed=321, randomize=True, push=False)
    by = xg.meta_by_motor()
    motors = list(e.motors)
    summary = {}
    frames = {}
    nf = BASE_FRAMES
    for skill in DEPLOY:
        theta = np.load(os.path.join(HERE, "skills", skill, "theta.npy"))
        kf = e.ctrl0 + theta.reshape(N, -1)
        pos = np.rint(xg.to_motor_positions(kf, e)).astype(int)
        clipped = bool(pos.min() < 800 or pos.max() > 2200)
        pos = np.clip(pos, 800, 2200)
        # 取整數、夾範圍之後,再轉回弧度重新驗證
        ctrl_r = np.zeros_like(kf)
        for j, m in enumerate(motors):
            ctrl_r[:, j] = (pos[:, j] - by[m]["zero"]) * by[m]["factor"]
        theta_r = (ctrl_r - e.ctrl0).reshape(-1)
        before = sk.robust_eval(e_rand, skill, theta, k=16)
        after = sk.robust_eval(e_rand, skill, theta_r, k=16)
        summary[skill] = dict(first_frame=nf, frames="%d~%d" % (nf, nf + N - 1), clipped=clipped,
                              success_before=before["success_pct"], success_after_rounding=after["success_pct"],
                              forward_cm=after["forward_cm"], lateral_cm=after["lateral_cm"], yaw_deg=after["yaw_deg"],
                              peak_lift_mm=after["peak_lift_mm"], falls=after["falls"], label=sk.LABEL[skill],
                              positions={str(nf + k): {str(m): int(pos[k, j]) for j, m in enumerate(motors)} for k in range(N)})
        for k in range(N):
            fr = [[0, 1500, 100] for _ in range(26)]
            for j, m in enumerate(motors):
                fr[m - 1] = [1, int(pos[k, j]), T_MS]
            frames[nf + k] = fr
        print("%-9s 幀 %d~%d  成功率 %.0f%% -> 取整數後 %.0f%%  前進 %.0f 橫向 %.0f 轉向 %.0f 腳高 %.0f mm 倒 %d%s" % (
            skill, nf, nf + N - 1, before["success_pct"], after["success_pct"], after["forward_cm"], after["lateral_cm"],
            after["yaw_deg"], after["peak_lift_mm"], after["falls"], "  [位置被夾住]" if clipped else ""))
        nf += N

    # 3) 動作檔副本 + motor.h 副本
    os.makedirs(OUT, exist_ok=True)
    cfg2 = json.loads(json.dumps(cfg))
    cfg2["mmax"] = nf
    cfg2["MotorPos"] = cfg2["MotorPos"][:BASE_FRAMES] + [frames[i] for i in range(BASE_FRAMES, nf)]
    with open(os.path.join(OUT, "light1.2_skills.config"), "w", encoding="utf-8") as f:
        f.write(json.dumps(cfg2, separators=(",", ":")))
    with open(os.path.join(OUT, "motor_skills.h"), "w", encoding="utf-8", newline="") as f:
        f.write(motor_h_text(cfg2, nf))
    json.dump(summary, open(os.path.join(OUT, "summary.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("完成:", OUT, " 總幀數", nf)


if __name__ == "__main__":
    main()
