"""
不間斷訓練所有「動作幀」技能(直走、後退、左右側移、左右轉身):每個技能存最好的一組 4 幀,每 30 分鐘更新影片。
用法:python skills_forever.py [--workers 6] [--chunk 30] [--clip-minutes 30] [--only forward,backward]
停止:在 rl 資料夾建立 STOP_SKILLS.txt
輸出:rl\\skills\\<技能>\\   theta.npy(最好的參數)、best.json、history.csv、
                        gait_N4.csv / gait_N4.frames\\(.frame 檔)/ gait_N4.ino.txt / gait_N4.json、latest.gif、best_*.gif
      rl\\skills\\index.html   網頁:每個技能的影片、達標狀態、數字
"""
import argparse
import csv
import glob
import html
import json
import multiprocessing as mp
import os
import shutil
import time

import numpy as np

import gait_opt as go
import skills as sk
from robot_env import make_env

HERE = os.path.dirname(os.path.abspath(__file__))
SK_DIR = os.path.join(HERE, "skills")
STOP = os.path.join(HERE, "STOP_SKILLS.txt")
DONE_PCT = 90.0           # 達標比例 ≥ 90% 而且沒倒,就算這個技能「完成」(還是會繼續練,變更穩)


def log(s):
    print(time.strftime("[%m-%d %H:%M:%S] ") + s, flush=True)


def load_state(skill, dim, rng):
    d = os.path.join(SK_DIR, skill)
    os.makedirs(d, exist_ok=True)
    tp = os.path.join(d, "theta.npy")
    theta = np.load(tp) if os.path.exists(tp) and np.load(tp).size == dim else rng.standard_normal(dim) * 0.03
    bp = os.path.join(d, "best.json")
    best = json.load(open(bp, encoding="utf-8")) if os.path.exists(bp) else None
    if best is not None and best.get("criteria") != sk.CRITERIA:
        best = None            # 達標標準改過了:舊紀錄不算數,用新標準重新評(參數 theta 還是接著練)
    return theta, best


def save_best(skill, theta, res, e_nom, n, T_ms, stamp):
    d = os.path.join(SK_DIR, skill)
    np.save(os.path.join(d, "theta.npy"), theta)
    info = dict(res)
    info.update(skill=skill, label=sk.LABEL[skill], time=stamp, goal=sk.GOAL[skill],
                first_frame=sk.FIRST_FRAME[skill], frames="%d~%d" % (sk.FIRST_FRAME[skill], sk.FIRST_FRAME[skill] + n - 1))
    json.dump(info, open(os.path.join(d, "best.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    kf = e_nom.ctrl0 + theta.reshape(n, -1)
    go.write_outputs(kf, e_nom, n, T_ms, sk.FIRST_FRAME[skill], d, dict(skill=skill, label=sk.LABEL[skill], robust_test=res))
    return kf


def policy_seeds(env_name, n):
    """直走 / 後退的起點:用 RL 模型走路的步態(取樣成 n 幀)。後退 = 同一組幀倒過來播。回傳 {技能: theta}"""
    import export_gait as xg
    src = os.path.join(HERE, "runs", "skill_seed")
    os.makedirs(src, exist_ok=True)
    for cand in ("straighter_best", "straighter"):
        m = os.path.join(HERE, "runs", cand, "model.zip")
        if os.path.exists(m):
            shutil.copyfile(m, os.path.join(src, "model.zip"))
            break
    cyc, sd, e = xg.mean_cycle("skill_seed", env_name)
    kf = xg.keyframes(cyc, n)
    th = lambda k: np.clip((k - e.ctrl0).reshape(-1), -sk.LIMIT, sk.LIMIT)
    return {"forward": th(kf), "backward": th(kf[::-1].copy())}


def record_clip(skill, kf, n, T_ms, env_name, path):
    try:
        go.record(make_env(env_name, seed=5, randomize=False, push=False), kf, n, T_ms / 1000.0, path)
        return True
    except Exception as ex:
        log("錄影失敗 %s:%s" % (skill, ex))
        return False


def write_index(states):
    cards = []
    done = 0
    for skill in sk.ORDER:
        s = states[skill]
        b = s["best"]
        d = os.path.join(SK_DIR, skill)
        if b is None:
            cards.append("<div class='c'><h3>%s</h3><p>還沒有結果</p></div>" % sk.LABEL[skill])
            continue
        ok = b["success_pct"] >= DONE_PCT and b["falls"] == 0
        done += 1 if ok else 0
        badge = "<span class='ok'>✔ 完成</span>" if ok else ("<span class='mid'>進行中</span>" if b["success_pct"] > 0 else "<span class='no'>還不行</span>")
        gif = "latest.gif" if os.path.exists(os.path.join(d, "latest.gif")) else ""
        cards.append(
            "<div class='c'><h3>%s &nbsp;%s</h3><div class='goal'>目標:%s</div>%s"
            "<table><tr><td>達標比例(隨機化環境 %s 次 × 20 秒)</td><td><b>%s %%</b></td></tr>"
            "<tr><td>倒下</td><td>%s 次</td></tr><tr><td>前進(+)/後退(−)</td><td>%s cm</td></tr><tr><td>往右(+)/往左(−)</td><td>%s cm</td></tr>"
            "<tr><td>轉向(+右 / −左)</td><td>%s°</td></tr><tr><td>腳最高離地</td><td>%s mm</td></tr>"
            "<tr><td>動作幀(每幀 200 ms,重複播放)</td><td>%s 號</td></tr><tr><td>最後進步時間</td><td>%s</td></tr>"
            "<tr><td>已搜尋代數</td><td>%s</td></tr></table></div>" % (
                sk.LABEL[skill], badge, html.escape(sk.GOAL[skill]),
                ("<img src='%s/%s'>" % (skill, gif)) if gif else "", b.get("episodes"), b["success_pct"], b["falls"],
                b["forward_cm"], b["lateral_cm"], b["yaw_deg"], b["peak_lift_mm"], b.get("frames"), b.get("time"), s["gen"]))
    page = ("<!doctype html><html lang='zh-Hant'><head><meta charset='utf-8'><meta http-equiv='refresh' content='120'>"
            "<meta name='viewport' content='width=device-width,initial-scale=1'><title>動作幀技能訓練</title>"
            "<style>body{font-family:'Microsoft JhengHei',system-ui,sans-serif;background:#0d1117;color:#e6edf3;max-width:820px;margin:auto;padding:16px}"
            ".c{background:#161b22;border:1px solid #30363d;border-radius:12px;padding:12px;margin:14px 0}img{max-width:100%%;border-radius:8px}"
            ".ok{color:#3fb950;font-weight:700}.mid{color:#d29922;font-weight:700}.no{color:#f85149;font-weight:700}.goal{color:#9da7b3;font-size:14px;margin:4px 0 8px}"
            "table{border-collapse:collapse;width:100%%}td{border-bottom:1px solid #30363d;padding:4px 6px}td:last-child{text-align:right}</style></head><body>"
            "<h1>動作幀技能訓練(不間斷)</h1><p>%d / %d 個技能完成。每個技能都是 <b>4 個動作幀重複播放</b>(開環,沒有回饋,和真機照幀播放一樣),"
            "在隨機化的模擬裡練。「完成」= 達標比例 ≥ %d%% 且沒倒。<b>模擬到真機還有落差,放真機要實測、可能要微調。</b>最新影片約每 30 分鐘更新。</p>%s"
            "<p style='color:#9da7b3'>更新時間 %s</p></body></html>") % (done, len(sk.ORDER), DONE_PCT, "".join(cards), time.strftime("%Y-%m-%d %H:%M:%S"))
    with open(os.path.join(SK_DIR, "index.html"), "w", encoding="utf-8") as f:
        f.write(page)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--chunk", type=int, default=30, help="每個技能一次練幾代,再換下一個技能")
    ap.add_argument("--pop", type=int, default=48)
    ap.add_argument("--seconds", type=float, default=12.0)
    ap.add_argument("--clip-minutes", type=float, default=30.0)
    ap.add_argument("--only", default=None)
    ap.add_argument("--env", default="straighter")
    a = ap.parse_args()
    order = [s for s in sk.ORDER if (a.only is None or s in a.only.split(","))]
    n, T_ms = sk.N_FRAMES, sk.T_MS
    os.makedirs(SK_DIR, exist_ok=True)
    e_nom = make_env(a.env, seed=0, randomize=False, push=False)
    e_rand = make_env(a.env, seed=321, randomize=True, push=False)
    dim = n * e_nom.nu
    rng = np.random.default_rng(1)
    states = {}
    for s in sk.ORDER:
        theta, best = load_state(s, dim, rng)
        states[s] = dict(es=sk.ES(dim, theta, seed=hash(s) % 1000), best=best, gen=0, since_improve=0, last_clip=0.0)
        if best is not None:
            states[s]["gen"] = int(best.get("gen", 0))
    # 重新評分:評分現在每次都用同一組固定的 16 場隨機環境(見 skills.robust_eval)。
    # 以前每次抽新的環境,成績起伏很大(同一組參數轉身分數可以差到 100 分),運氣好的一次變成「最好成績」,
    # 之後怎麼練都贏不了它,搜尋就一直被退回去。這裡把每個技能存著的最好參數用新方式重評一次,當作公平的基準。
    for s_ in order:
        st_ = states[s_]
        tp_ = os.path.join(SK_DIR, s_, "theta.npy")
        if os.path.exists(tp_):
            th_ = np.load(tp_)
            if th_.size == dim:
                old_ = st_["best"]
                r_ = sk.robust_eval(e_rand, s_, th_, n, T_ms, k=16)
                r_["gen"] = st_["gen"]
                st_["best"] = r_
                save_best(s_, th_, r_, e_nom, n, T_ms, time.strftime("%Y-%m-%d %H:%M"))
                log("%s:用固定環境重新評分 → 達標 %.0f%% 倒 %d 轉向 %.0f 分數 %.0f(原紀錄:達標 %s%% 轉向 %s 分數 %s)" % (
                    sk.LABEL[s_], r_["success_pct"], r_["falls"], r_["yaw_deg"], r_["fitness"],
                    old_.get("success_pct") if old_ else "-", old_.get("yaw_deg") if old_ else "-", old_.get("fitness") if old_ else "-"))
    # 直走 / 後退如果還沒有任何進展(幾乎沒動),改用 RL 模型的步態當起點重新搜尋(原本的紀錄保留,有比較好才會取代)
    try:
        seeds = None
        for s_ in ("forward", "backward"):
            b_ = states[s_]["best"]
            stuck = b_ is None or (b_["success_pct"] == 0 and abs(b_["forward_cm"]) < 30)
            if stuck and s_ in order:
                if seeds is None:
                    seeds = policy_seeds(a.env, n)
                states[s_]["es"] = sk.ES(dim, seeds[s_], seed=hash(s_) % 1000)
                log("%s:原本幾乎沒進展,改用 RL 模型的步態當起點" % sk.LABEL[s_])
    except Exception as ex:
        log("取 RL 步態當起點失敗(照原本繼續):%s" % ex)
    pool = mp.Pool(a.workers, initializer=sk.init_worker, initargs=(a.env,))
    log("開始:技能 %s;每個技能一次練 %d 代;工作程序 %d" % (",".join(order), a.chunk, a.workers))
    write_index(states)
    while not os.path.exists(STOP):
        for skill in order:
            if os.path.exists(STOP):
                break
            st = states[skill]
            es = st["es"]
            t0 = time.time()
            for _ in range(a.chunk):
                if os.path.exists(STOP):
                    break
                es.step(pool, skill, n, T_ms, a.seconds, a.pop)
            st["gen"] += a.chunk
            cur = sk.robust_eval(e_rand, skill, es.theta, n, T_ms, k=16)
            stamp = time.strftime("%Y-%m-%d %H:%M")
            msg = "%s 第 %d 代:達標 %.0f%% 倒 %d 前進 %.0f 橫向 %.0f 轉向 %.0f 腳高 %.0f(%.0f 秒)" % (
                sk.LABEL[skill], st["gen"], cur["success_pct"], cur["falls"], cur["forward_cm"], cur["lateral_cm"], cur["yaw_deg"], cur["peak_lift_mm"], time.time() - t0)
            cur["gen"] = st["gen"]
            if sk.better(cur, st["best"]):
                st["best"] = cur
                st["since_improve"] = 0
                es.set_sigma(min(0.04, es.sigma * 1.1))
                kf = save_best(skill, es.theta.copy(), cur, e_nom, n, T_ms, stamp)
                d = os.path.join(SK_DIR, skill)
                bg = os.path.join(d, "best_%s.gif" % time.strftime("%Y%m%d_%H%M"))
                if record_clip(skill, kf, n, T_ms, a.env, bg):
                    shutil.copyfile(bg, os.path.join(d, "latest.gif"))
                    for old in sorted(glob.glob(os.path.join(d, "best_*.gif")))[:-3]:
                        os.remove(old)
                st["last_clip"] = time.time()
                msg += "  ★ 新紀錄"
            else:
                st["since_improve"] += 1
                if st["best"] is not None:
                    # 這一段沒有比最好的更好:立刻退回最好的參數(不讓搜尋漂走);已經不錯(達標 ≥ 50%)就把搜尋步伐縮小,更精細地微調
                    es.reset_to(np.load(os.path.join(SK_DIR, skill, "theta.npy")))
                    if st["best"]["success_pct"] >= 50:
                        es.set_sigma(max(0.012, es.sigma * 0.85))
                    msg += "  (退回最好的參數;步伐 %.3f)" % es.sigma
            # 每 30 分鐘更新一次「最好那組」的影片(就算沒進步)
            if st["best"] is not None and time.time() - st["last_clip"] >= a.clip_minutes * 60:
                d = os.path.join(SK_DIR, skill)
                kf = e_nom.ctrl0 + np.load(os.path.join(d, "theta.npy")).reshape(n, -1)
                if record_clip(skill, kf, n, T_ms, a.env, os.path.join(d, "latest.gif")):
                    st["last_clip"] = time.time()
            with open(os.path.join(SK_DIR, skill, "history.csv"), "a", encoding="utf-8", newline="") as f:
                w = csv.writer(f)
                w.writerow([stamp, st["gen"], cur["success_pct"], cur["falls"], cur["fitness"], cur["forward_cm"], cur["lateral_cm"], cur["yaw_deg"], cur["peak_lift_mm"]])
            log(msg)
            write_index(states)
    pool.close()
    log("收到停止檔案,結束")


if __name__ == "__main__":
    mp.freeze_support()
    main()
