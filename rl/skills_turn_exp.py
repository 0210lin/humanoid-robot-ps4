"""
轉身的對照實驗:換不同的搜尋空間(動作幀數、每顆馬達的範圍、每幀時間),看轉身能不能突破。
不動 rl/skills/ 裡的成績;結果寫在 rl/skills_exp/<名稱>/(best.npy、best.json、history.csv),進度寫在 skills_exp.log。
評分用固定的同一組 16 場隨機環境(新舊參數公平比較)。
用法:python skills_turn_exp.py [--workers 10] [--chunk 20] [--hours 8]
停止:在 rl/ 底下建一個 STOP_EXP.txt
"""
import argparse
import json
import multiprocessing as mp
import os
import shutil
import time

import numpy as np

import skills as sk
from robot_env import make_env

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "skills_exp")
STOP = os.path.join(HERE, "STOP_EXP.txt")
LOG = os.path.join(HERE, "skills_exp.log")

# 名稱: 技能、幀數 n、每顆馬達範圍 limit(rad)、每幀時間 T(ms)
CONFIGS = [
    ("l_n6_lim09_t150", "turn_l", 6, 0.9, 150),     # 最好(-169°)
    ("r_n6_lim09_t150", "turn_r", 6, 0.9, 150),     # 最好(+143°)
    ("l_n6_lim12_t150", "turn_l", 6, 1.2, 150),
    ("r_n6_lim12_t150", "turn_r", 6, 1.2, 150),
]
def log(s):
    line = time.strftime("[%m-%d %H:%M:%S] ") + s
    print(line, flush=True)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(line + "\n")


def seed_theta(skill, n, nu, limit, rng):
    """用現有最好的 4 幀參數當起點(幀數不同時用內插);沒有就用小隨機"""
    p = os.path.join(HERE, "skills", skill, "theta.npy")
    if os.path.exists(p):
        t4 = np.load(p).reshape(4, nu)
        if n == 4:
            return np.clip(t4.reshape(-1), -limit, limit)
        pos = np.linspace(0, 3, n)
        out = np.zeros((n, nu))
        for j in range(nu):
            out[:, j] = np.interp(pos, np.arange(4), t4[:, j])
        return np.clip(out.reshape(-1), -limit, limit)
    return rng.standard_normal(n * nu) * 0.03


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workers", type=int, default=10)
    ap.add_argument("--chunk", type=int, default=20)
    ap.add_argument("--hours", type=float, default=8.0)
    ap.add_argument("--pop", type=int, default=48)
    ap.add_argument("--seconds", type=float, default=12.0)
    ap.add_argument("--env", default="straighter")
    a = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    e_rand = make_env(a.env, seed=321, randomize=True, push=False)
    nu = make_env(a.env, seed=0, randomize=False, push=False).nu
    rng = np.random.default_rng(7)
    runs = []
    for name, skill, n, limit, T in CONFIGS:
        sk.LIMIT = limit
        resume = os.path.join(OUT, name, "best.npy")
        th0 = np.load(resume) if os.path.exists(resume) and np.load(resume).size == n * nu else seed_theta(skill, n, nu, limit, rng)
        es = sk.ES(n * nu, th0, sigma=0.05, seed=hash(name) % 1000)
        runs.append(dict(name=name, skill=skill, n=n, limit=limit, T=T, es=es, best=None, gen=0))
        for stale in ("best.json",):
            pp = os.path.join(OUT, name, stale)
            if os.path.exists(pp):
                os.replace(pp, pp + ".old")
        os.makedirs(os.path.join(OUT, name), exist_ok=True)
    for r_ in runs:
        bp_ = os.path.join(OUT, r_["name"], "best.npy")
        if os.path.exists(bp_):
            th_ = np.load(bp_)
            if th_.size == r_["n"] * nu:
                sk.LIMIT = r_["limit"]
                base_ = sk.robust_eval(e_rand, r_["skill"], th_, r_["n"], r_["T"], k=16)
                r_["best"] = dict(base_, gen=0, n=r_["n"], T=r_["T"], limit=r_["limit"], skill=r_["skill"])
                json.dump(r_["best"], open(os.path.join(OUT, r_["name"], "best.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
                es_ = sk.ES(r_["n"] * nu, th_, sigma=0.05, seed=hash(r_["name"]) % 1000)
                r_["es"] = es_
                log("%s:基準(存著的參數)→ 轉向 %+.0f 分數 %.0f 倒 %d 達標 %.0f%%" % (r_["name"], base_["yaw_deg"], base_["fitness"], base_["falls"], base_["success_pct"]))
    pool = mp.Pool(a.workers, initializer=sk.init_worker, initargs=(a.env,))
    log("開始轉身實驗:%d 組設定,每組一次 %d 代,工作程序 %d" % (len(runs), a.chunk, a.workers))
    t_end = time.time() + a.hours * 3600
    while time.time() < t_end and not os.path.exists(STOP):
        for r in runs:
            if os.path.exists(STOP) or time.time() >= t_end:
                break
            sk.LIMIT = r["limit"]
            t0 = time.time()
            for _ in range(a.chunk):
                r["es"].step(pool, r["skill"], r["n"], r["T"], a.seconds, a.pop)
            r["gen"] += a.chunk
            cur = sk.robust_eval(e_rand, r["skill"], r["es"].theta, r["n"], r["T"], k=16)
            msg = "%s 第 %d 代:達標 %.0f%% 倒 %d 轉向 %+.0f 位移 %.0f/%.0f 腳高 %.0f 分數 %.0f(%.0f 秒)" % (
                r["name"], r["gen"], cur["success_pct"], cur["falls"], cur["yaw_deg"], cur["forward_cm"], cur["lateral_cm"],
                cur["peak_lift_mm"], cur["fitness"], time.time() - t0)
            if sk.better(cur, r["best"]):
                r["best"] = dict(cur, gen=r["gen"], n=r["n"], T=r["T"], limit=r["limit"], skill=r["skill"])
                bp = os.path.join(OUT, r["name"], "best.npy")
                if os.path.exists(bp):
                    shutil.copyfile(bp, os.path.join(OUT, r["name"], "best_prev.npy"))
                np.save(bp, r["es"].theta)
                json.dump(r["best"], open(os.path.join(OUT, r["name"], "best.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
                msg += "  ★"
            else:
                r["es"].reset_to(np.load(os.path.join(OUT, r["name"], "best.npy")))
                msg += "  (退回最好的)"
            with open(os.path.join(OUT, r["name"], "history.csv"), "a", encoding="utf-8") as f:
                f.write("%s,%d,%.1f,%d,%.1f,%.1f\n" % (time.strftime("%Y-%m-%d %H:%M"), r["gen"], cur["success_pct"], cur["falls"], cur["yaw_deg"], cur["fitness"]))
            log(msg)
    pool.terminate()
    log("實驗結束")


if __name__ == "__main__":
    main()
