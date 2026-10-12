"""
用「你自己做的轉身幀」當種子,在模擬裡測會不會轉。
你第一台的 custom.ino:L2 = 幀 14 搭配 12,R2 = 幀 13 搭配 12(按住重複)。
把這些幀(動作檔的值,沒加 Offset)轉成模擬的馬達控制量,排成 4 幀(A B A B)重複播放。
用法:python seed_turn_user.py
"""
import json
import os
import sys

import numpy as np

import export_gait as xg
import skills as sk
from robot_env import make_env

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CFG = os.path.join(ROOT, "Tools_MotorAdj", "light1.2.config")


def frame_ctrl(cfg, e, fr):
    by = xg.meta_by_motor()
    ctrl = np.zeros(len(e.motors))
    for j, m in enumerate(e.motors):
        pos = cfg["MotorPos"][fr][m - 1][1]
        ctrl[j] = (pos - by[m]["zero"]) * by[m]["factor"]
    return ctrl


def main():
    cfg = json.load(open(CFG, encoding="utf-8"))
    e = make_env("straighter", seed=0, randomize=False, push=False)
    e_rand = make_env("straighter", seed=321, randomize=True, push=False)
    seeds = {"L2(14,12)": (14, 12), "R2(13,12)": (13, 12)}
    out = {}
    for name, (a, b) in seeds.items():
        ca, cb = frame_ctrl(cfg, e, a), frame_ctrl(cfg, e, b)
        kf = np.stack([ca, cb, ca, cb])
        theta = (kf - e.ctrl0).reshape(-1)
        print("%s: 離站姿最大 %.2f rad(搜尋範圍 ±%.2f)" % (name, np.abs(theta).max(), sk.LIMIT))
        for T in (200, 120):
            r = sk.robust_eval(e_rand, "turn_r", np.clip(theta, -1.2, 1.2), n=4, T_ms=T, k=8)
            print("  每幀 %3d ms:轉向 %+.0f° 前進 %+.0f cm 橫向 %+.0f cm 倒 %d/%d 腳高 %.0f mm" % (
                T, r["yaw_deg"], r["forward_cm"], r["lateral_cm"], r["falls"], r["episodes"], r["peak_lift_mm"]))
            out["%s_%d" % (name, T)] = dict(r, theta=theta.tolist())
    json.dump(out, open(os.path.join(HERE, "seed_turn_user.json"), "w"), indent=1)


if __name__ == "__main__":
    main()
