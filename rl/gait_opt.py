"""
直接找「N 個動作幀重複播放」的最佳數值(開環:不靠任何回饋,和真機照幀播放一樣),
讓它重複播放也能走直、抬腳(腳整塊離地 ≥ 1 公分)。方法:演化策略(OpenAI-ES,對稱取樣 + Adam)。

用法:python gait_opt.py [--n 4] [--ms 200] [--gens 300] [--workers 6] [--first-frame 40] [--init 模型名稱|zero] [--tag 名稱]
輸出(在 rl\gait\<tag>\):gait_N4.csv、gait_N4.frames\(.frame 檔)、gait_N4.ino.txt、gait_N4.json、clip.gif、log.txt
  ・位置是「設定檔裡的值」(沒加 Offset),只輸出 15 顆有驅動的馬達;2、18(並聯)不啟用
  ・每一幀的時間 = --ms(也是馬達走到位的時間);整個循環 = N × ms
"""
import argparse
import json
import multiprocessing as mp
import os
import sys
import time

import mujoco
import numpy as np
from PIL import Image, ImageDraw, ImageFont

import export_gait as xg
from robot_env import make_env, SUBSTEPS

HERE = os.path.dirname(os.path.abspath(__file__))
DT = xg.DT
LIMIT = 0.6          # 每顆馬達離站姿最多 ±0.6 弧度(約 34°)
_env = None


def init_worker(env_name):
    global _env
    _env = make_env(env_name, seed=0, randomize=True, push=False)


def fitness(res):
    """越大越好:往前走多遠 − 橫向偏離 − 轉向 + 抬腳(整塊腳離地 ≥ 1 cm)的獎勵;倒下重扣;抬太高、貼地腳滑行小扣"""
    if res["fell"]:
        return -100.0 + res["seconds_survived"] * 5.0
    sc = res["forward_cm"] - 2.5 * abs(res["lateral_cm"]) - 1.2 * abs(res["yaw_deg"])
    sc += 30.0 * min(1.0, res["peak_lift_mm"] / 10.0) + 1.0 * min(res["lift_events_per_20s"], 20.0)
    sc -= 0.5 * max(0.0, res["peak_lift_mm"] - 40.0)             # 腳抬超過 4 公分就扣(不要亂甩腿)
    sc -= 4.0 * max(0.0, res.get("slip_cm_s", 0.0) - 1.5)        # 貼地的腳在地上滑(滑冰)小扣:超過 1.5 cm/s 的部分
    return sc


def eval_x(args):
    x, n, T_ms, seconds, seed = args
    _env.rng = np.random.default_rng(seed)        # 同一代的候選用同一組隨機(摩擦、重量、伺服力量、延遲),比較才公平
    kf = _env.ctrl0 + x.reshape(n, -1)
    res = xg.play_open_loop(_env, kf, n, seconds=seconds, T=T_ms / 1000.0, ramp=T_ms / 1000.0)
    return fitness(res)


def rank_shape(f):
    r = np.empty(len(f))
    r[np.argsort(f)] = np.arange(len(f))
    return r / (len(f) - 1) - 0.5


def record(e, kf, n, T, path, seconds=10.0):
    """把這組幀開環播放 10 秒,存成帶數字的 GIF"""
    font = None
    for f in ["C:/Windows/Fonts/msjh.ttc", "C:/Windows/Fonts/mingliu.ttc"]:
        if os.path.exists(f):
            font = ImageFont.truetype(f, 13)
            break
    r = mujoco.Renderer(e.model, 360, 480)
    cam = e.model.camera("view").id
    o, _ = e.reset()
    z0, x0, y0 = float(e.data.qpos[2]), float(e.data.qpos[0]), xg.yaw_deg(e)
    frames, peak = [], 0.0
    for i in range(int(seconds / DT)):
        t = i * DT
        tgt = xg.setpoint(kf, t, n, T)
        if t < T:
            tgt = e.ctrl0 + (tgt - e.ctrl0) * (t / T)
        e.data.ctrl[:] = np.clip(tgt, e.lo, e.hi)
        for _ in range(SUBSTEPS):
            mujoco.mj_step(e.model, e.data)
        cr, cl = e.foot_clear(e.foot_r), e.foot_clear(e.foot_l)
        peak = max(peak, cr, cl)
        if i % 2 == 0:
            r.update_scene(e.data, cam)
            im = Image.fromarray(r.render())
            if font is not None:
                dr = ImageDraw.Draw(im)
                lines = ["%.1f 秒  前進 %+.0f cm  側向 %+.0f cm  轉向 %+.0f°" % (
                    (i + 1) * DT, -(float(e.data.qpos[2]) - z0) * 100, (float(e.data.qpos[0]) - x0) * 100, xg.yaw_deg(e) - y0),
                    "腳離地 右 %.0f / 左 %.0f mm  最高 %.0f mm(規則:至少 10)" % (max(0, cr) * 1000, max(0, cl) * 1000, peak * 1000)]
                y = 4
                for ln in lines:
                    dr.rectangle([2, y - 1, 2 + dr.textlength(ln, font=font) + 6, y + 16], fill=(0, 0, 0))
                    dr.text((5, y), ln, font=font, fill=(255, 255, 255))
                    y += 19
            frames.append(im)
    frames[0].save(path, save_all=True, append_images=frames[1:], duration=80, loop=0)


def write_outputs(kf, e, n, T_ms, first_frame, out_dir, extra):
    pos = np.rint(xg.to_motor_positions(kf, e)).astype(int)
    warn = []
    if pos.min() < 800 or pos.max() > 2200:
        warn.append("有位置超出 800~2200,已夾住:最小 %d 最大 %d" % (pos.min(), pos.max()))
    pos_c = np.clip(pos, 800, 2200)
    motors = list(e.motors)
    order = sorted(range(len(motors)), key=lambda j: motors[j])
    os.makedirs(out_dir, exist_ok=True)
    base = os.path.join(out_dir, "gait_N%d" % n)
    with open(base + ".csv", "w", encoding="utf-8-sig") as f:
        f.write("幀,時間ms," + ",".join("馬達%d" % motors[j] for j in order) + "\n")
        for k in range(n):
            f.write("%d,%d," % (first_frame + k, T_ms) + ",".join(str(pos_c[k, j]) for j in order) + "\n")
    fd = base + ".frames"
    os.makedirs(fd, exist_ok=True)
    for k in range(n):
        mp_ = [[0, 1500, 100] for _ in range(26)]
        for j, mnum in enumerate(motors):
            mp_[mnum - 1] = [1, int(pos_c[k, j]), T_ms]
        with open(os.path.join(fd, "frame_%03d.frame" % (first_frame + k)), "w", encoding="utf-8") as f:
            f.write('{"MotorFrameIdx":%d,"MotorPos":[\n' % (first_frame + k))
            f.write(",\n".join("  [%d,%d,%d]" % tuple(x) for x in mp_))
            f.write("\n]}\n")
    with open(base + ".ino.txt", "w", encoding="utf-8") as f:
        f.write("// 走路:%d 個動作幀重複播放(幀 %d~%d,每幀 %d ms)。條件請換成你的按鍵。\n" % (n, first_frame, first_frame + n - 1, T_ms))
        f.write("// 播放前請先回站姿(例如 SetFrameRun(1, 200);),結束後再回站姿。\n")
        f.write("do {\n")
        for k in range(n):
            f.write("  SetFrameRun(%d, %d);\n" % (first_frame + k, T_ms))
        f.write("} while (/* 你的條件,例如 LS_DIR == DIR_UP && pad_getKey() != PAD_BTN_STOP */);\n")
    info = dict(n=n, first_frame=first_frame, frame_ms=T_ms, motors=motors, warnings=warn,
                positions={str(first_frame + k): {str(motors[j]): int(pos_c[k, j]) for j in order} for k in range(n)})
    info.update(extra)
    json.dump(info, open(base + ".json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    return base


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=4)
    ap.add_argument("--ms", type=int, default=200)
    ap.add_argument("--gens", type=int, default=300)
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--pop", type=int, default=48)
    ap.add_argument("--seconds", type=float, default=12.0)
    ap.add_argument("--sigma", type=float, default=0.04)
    ap.add_argument("--lr", type=float, default=0.02)
    ap.add_argument("--first-frame", type=int, default=40)
    ap.add_argument("--init", default="gait_src", help="用哪個模型的步態當起點;zero = 從站姿加小亂數開始")
    ap.add_argument("--env", default="straighter")
    ap.add_argument("--tag", default=None)
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args()
    n, T_ms = a.n, a.ms
    tag = a.tag or ("N%d_%dms" % (n, T_ms))
    out_dir = os.path.join(HERE, "gait", tag)
    os.makedirs(out_dir, exist_ok=True)
    logf = open(os.path.join(out_dir, "log.txt"), "w", encoding="utf-8")

    def log(s):
        print(s, flush=True)
        logf.write(s + "\n")
        logf.flush()

    e = make_env(a.env, seed=0, randomize=False, push=False)
    rng = np.random.default_rng(a.seed)
    D = n * e.nu
    if a.init == "zero":
        theta = rng.standard_normal(D) * 0.02
    else:
        cyc, sd, _ = xg.mean_cycle(a.init, a.env)
        theta = (xg.keyframes(cyc, n) - e.ctrl0).reshape(-1)
        # 如果要的幀時間和模型的步態週期不同,取樣點還是照「一圈」均分,播放速度會不同,屬於正常
    theta = np.clip(theta, -LIMIT, LIMIT)
    log("參數 %d 個(%d 幀 × %d 顆馬達);每幀 %d ms;起點:%s;工作程序 %d" % (D, n, e.nu, T_ms, a.init, a.workers))
    m_ = np.zeros(D); v_ = np.zeros(D)
    best_f, best_theta = -1e9, theta.copy()
    pool = mp.Pool(a.workers, initializer=init_worker, initargs=(a.env,))
    t0 = time.time()
    half = a.pop // 2
    for g in range(a.gens):
        eps = rng.standard_normal((half, D))
        cands = np.concatenate([theta + a.sigma * eps, theta - a.sigma * eps] + [theta[None, :]])
        cands = np.clip(cands, -LIMIT, LIMIT)
        fits = np.array(pool.map(eval_x, [(c, n, T_ms, a.seconds, 1000 + g) for c in cands], chunksize=2))
        f_pos, f_neg, f_theta = fits[:half], fits[half:2 * half], fits[-1]
        if f_theta > best_f:
            best_f, best_theta = f_theta, theta.copy()
        shaped = rank_shape(fits[:2 * half])
        sp, sn = shaped[:half], shaped[half:]
        grad = ((sp - sn)[:, None] * eps).sum(0) / (half * a.sigma)
        m_ = 0.9 * m_ + 0.1 * grad
        v_ = 0.999 * v_ + 0.001 * grad ** 2
        mh = m_ / (1 - 0.9 ** (g + 1)); vh = v_ / (1 - 0.999 ** (g + 1))
        theta = np.clip(theta + a.lr * mh / (np.sqrt(vh) + 1e-8), -LIMIT, LIMIT)
        if g % 5 == 0 or g == a.gens - 1:
            log("第 %3d 代  目前 %7.1f  這代最好 %7.1f  平均 %7.1f  歷史最好 %7.1f  (%.1f 分鐘)" % (
                g, f_theta, fits[:2 * half].max(), fits[:2 * half].mean(), best_f, (time.time() - t0) / 60))
    pool.close()
    # ---- 最後:用最好的那組,在「有隨機化」的環境各跑 6 次 20 秒,看穩不穩
    T = T_ms / 1000.0
    er = make_env(a.env, seed=123, randomize=True, push=False)

    def robust_score(th):
        k_ = e.ctrl0 + th.reshape(n, -1)
        rs = [xg.play_open_loop(er, k_, n, seconds=20.0, T=T, ramp=T) for _ in range(12)]
        return float(np.mean([fitness(r) for r in rs])), int(sum(r["fell"] for r in rs))
    sc_last, fl_last = robust_score(theta)
    sc_best, fl_best = robust_score(best_theta)
    log("最後的參數:12 次隨機環境平均分數 %.1f(倒 %d 次);歷史最好的參數:%.1f(倒 %d 次)" % (sc_last, fl_last, sc_best, fl_best))
    if (fl_last, -sc_last) <= (fl_best, -sc_best):
        best_theta, best_f = theta.copy(), sc_last
    else:
        best_f = sc_best
    kf = e.ctrl0 + best_theta.reshape(n, -1)
    res_nom = xg.play_open_loop(e, kf, n, seconds=20.0, T=T, ramp=T)
    rr = [xg.play_open_loop(er, kf, n, seconds=20.0, T=T, ramp=T) for _ in range(6)]
    rob = dict(
        falls=int(sum(r["fell"] for r in rr)),
        forward_cm=round(float(np.mean([r["forward_cm"] for r in rr])), 1),
        lateral_cm=round(float(np.mean([abs(r["lateral_cm"]) for r in rr])), 1),
        yaw_deg=round(float(np.mean([abs(r["yaw_deg"]) for r in rr])), 1),
        peak_lift_mm=round(float(np.mean([r["peak_lift_mm"] for r in rr])), 1),
        slip_cm_s=round(float(np.mean([r["slip_cm_s"] for r in rr])), 2))
    log("標準環境 20 秒:" + json.dumps(res_nom, ensure_ascii=False))
    log("隨機化環境(摩擦、重量、伺服力量都亂動)6 次平均:" + json.dumps(rob, ensure_ascii=False))
    base = write_outputs(kf, e, n, T_ms, a.first_frame, out_dir,
                         dict(tag=tag, fitness=round(float(best_f), 1), test_nominal_20s=res_nom, test_randomized_6x20s=rob, generations=a.gens))
    record(make_env(a.env, seed=5, randomize=False, push=False), kf, n, T, os.path.join(out_dir, "clip.gif"))
    log("已輸出:" + base + ".* 和 clip.gif")


if __name__ == "__main__":
    mp.freeze_support()
    main()
