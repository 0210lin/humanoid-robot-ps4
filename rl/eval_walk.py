"""
量走路成績:走直不直、有沒有抬腳。
用法:python eval_walk.py <模型名稱> [--env straight] [--n 6] [--csv 檔案] [--tag 標籤]
輸出:一行 JSON;有給 --csv 就把這一行附加到檔案裡(clips 網頁會讀它)。

判定:
  走直 = 20 秒內橫向偏離平均 ≤ 25 cm 而且身體轉向平均 ≤ 10°,而且沒有倒
  抬腳 = 腳最高離地平均 ≥ 10 mm(規則:抬起的腳要完全離地至少 1 公分)而且 20 秒內至少抬了 8 次腳
"""
import argparse
import csv
import json
import os
import sys

import numpy as np
from stable_baselines3 import PPO

from robot_env import make_env

HERE = os.path.dirname(os.path.abspath(__file__))


def yaw_deg(e):
    f = -e.data.xmat[1].reshape(3, 3)[:, 2]
    return float(np.degrees(np.arctan2(f[0], -f[2])))


def evaluate(name, env_name="straight", n=6, seed=1234):
    m = PPO.load(os.path.join(HERE, "runs", name, "model"), device="cpu")
    e = make_env(env_name, seed=seed)
    fwd, lat, yaw, peaks, lift_pct, steps, falls = [], [], [], [], [], [], 0
    for _ in range(n):
        o, _ = e.reset()
        z0, x0, y0 = float(e.data.qpos[2]), float(e.data.qpos[0]), yaw_deg(e)
        peak, lift_t, events, k, d = 0.0, 0, 0, 0, False
        prev = [False, False]
        while not d:
            o, r, te, tr, info = e.step(m.predict(o, deterministic=True)[0])
            k += 1
            d = te or tr
            cr, cl = info["clr_r"], info["clr_l"]
            peak = max(peak, cr, cl)
            up = [cr >= 0.010, cl >= 0.010]
            lift_t += 1 if (up[0] or up[1]) else 0
            for i in (0, 1):
                if up[i] and not prev[i]:
                    events += 1
            prev = up
            if te:
                falls += 1
        fwd.append(-(float(e.data.qpos[2]) - z0) * 100)
        lat.append((float(e.data.qpos[0]) - x0) * 100)
        yaw.append(yaw_deg(e) - y0)
        peaks.append(peak * 1000)
        lift_pct.append(100.0 * lift_t / k)
        steps.append(events * 500.0 / k)
    res = dict(
        forward_cm=round(float(np.mean(fwd)), 1),
        lateral_cm=round(float(np.mean(np.abs(lat))), 1),
        yaw_deg=round(float(np.mean(np.abs(yaw))), 1),
        peak_lift_mm=round(float(np.mean(peaks)), 1),
        lift_time_pct=round(float(np.mean(lift_pct)), 1),
        steps_per_20s=round(float(np.mean(steps)), 1),
        falls=falls, episodes=n)
    res["straight_ok"] = bool(res["lateral_cm"] <= 25 and res["yaw_deg"] <= 10 and falls == 0)
    res["lift_ok"] = bool(res["peak_lift_mm"] >= 10 and res["steps_per_20s"] >= 8)
    return res


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("name")
    ap.add_argument("--env", default="straight")
    ap.add_argument("--n", type=int, default=6)
    ap.add_argument("--csv", default=None)
    ap.add_argument("--tag", default="")
    a = ap.parse_args()
    res = evaluate(a.name, a.env, a.n)
    res["tag"] = a.tag
    print(json.dumps(res, ensure_ascii=False))
    if a.csv:
        new = not os.path.exists(a.csv)
        with open(a.csv, "a", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=list(res.keys()))
            if new:
                w.writeheader()
            w.writerow(res)
