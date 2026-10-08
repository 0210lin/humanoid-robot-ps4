"""
把訓練好的走路模型,整理成「N 個動作幀重複播放」,輸出馬達數值,並先在模擬裡測試(開環:沒有任何回饋,只是照幀播放)。

用法:python export_gait.py <模型名稱> [--env straighter] [--n 4] [--first-frame 40] [--out gait]
輸出(在 rl\gait\):
  gait_N4.csv          每一幀每顆馬達的位置(馬達編號為欄)
  gait_N4.frames\      frame_040.frame … 這種格式,可以用咖啡杯的「批量載入幀…」載入
  gait_N4.ino.txt      custom.ino 裡可以貼的程式(do while 重複播放這 N 幀)
  gait_N4.json         所有數值(含測試成績)

重點:
  ・位置數值是「設定檔裡的值」(沒有加 Offset),和咖啡杯裡看到的、動作幀存的是同一種,上傳時 Offset 會自動加上去
  ・只輸出 15 顆有驅動的馬達;馬達 2、18(並聯)不啟用,和你平常的動作一樣
  ・時間:整個步伐一圈 = 0.8 秒,N 幀就是每幀 800/N 毫秒(同時當作馬達走到位的時間)
  ・開環播放不保證能走:模型是有回饋的(看傾斜、角速度),只照幀重播可能會倒。測試結果會寫在輸出裡
"""
import argparse
import json
import os
import sys

import mujoco
import numpy as np
from stable_baselines3 import PPO

from robot_env import make_env, SUBSTEPS, MAX_STEPS

HERE = os.path.dirname(os.path.abspath(__file__))
PERIOD = 20           # 步態一圈 = 20 個控制步 = 0.8 秒(WalkEnv.PERIOD)
DT = 0.04             # 一個控制步 40 ms


def yaw_deg(e):
    f = -e.data.xmat[1].reshape(3, 3)[:, 2]
    return float(np.degrees(np.arctan2(f[0], -f[2])))


def meta_by_motor():
    meta = json.load(open(os.path.join(HERE, "robot_meta.json"), encoding="utf-8"))
    return {a["motor"]: a for a in meta["actuators"]}


def mean_cycle(model_name, env_name, seed=7, skip=100):
    """用模型走一段,把穩定後每個相位(0~19)的馬達目標值平均起來 → (20, 15) 和每個相位的標準差"""
    m = PPO.load(os.path.join(HERE, "runs", model_name, "model"), device="cpu")
    e = make_env(env_name, seed=seed, randomize=False)
    o, _ = e.reset()
    rows = [[] for _ in range(PERIOD)]
    d, k = False, 0
    while not d:
        ph = e.t % PERIOD
        o, r, te, tr, info = e.step(m.predict(o, deterministic=True)[0])
        if k >= skip:
            rows[ph].append(e.data.ctrl.copy())
        k += 1
        d = te or tr
    cyc = np.array([np.mean(r, axis=0) for r in rows])
    sd = np.array([np.std(r, axis=0) for r in rows])
    return cyc, sd, e


def keyframes(cyc, n, smooth=True):
    """N 個取樣點:第 k 點 = 步態在 k/N 圈時的位置。第 k 幀(播放時)的目標 = 第 k+1 點,花一幀的時間走過去"""
    out = []
    for k in range(n):
        ph = (k * PERIOD / n) % PERIOD
        i0 = int(round(ph)) % PERIOD
        if smooth:
            v = (cyc[(i0 - 1) % PERIOD] + 2 * cyc[i0] + cyc[(i0 + 1) % PERIOD]) / 4.0
        else:
            v = cyc[i0]
        out.append(v)
    return np.array(out)          # (N, 15) 弧度,MuJoCo 馬達順序


def setpoint(kf, t, n, T):
    """時間 t(秒)的目標位置:通過第 k 點(時間 k*T),兩點之間線性走(伺服板的做法:位置 + 時間)"""
    cycle_t = n * T
    tt = t % cycle_t
    k = int(tt // T)
    frac = (tt - k * T) / T
    return kf[k] + (kf[(k + 1) % n] - kf[k]) * frac


def play_open_loop(e, kf, n, seconds=20.0, T=None, ramp=1.0):
    T = T or (PERIOD * DT / n)
    o, _ = e.reset()
    z0, x0, y0 = float(e.data.qpos[2]), float(e.data.qpos[0]), yaw_deg(e)
    peak, lift_t, events, prev_up = 0.0, 0, 0, [False, False]
    slip_sum, slip_n, vtmp = 0.0, 0, np.zeros(6)
    steps = int(seconds / DT)
    fell = False
    # 起點:先把馬達慢慢帶到第一幀(從站姿走過去)
    for i in range(steps):
        t = i * DT
        tgt = setpoint(kf, t, n, T)
        if ramp > 0 and t < ramp:                      # 從站姿慢慢進入循環,避免一開始就大跳
            tgt = e.ctrl0 + (tgt - e.ctrl0) * (t / ramp)
        e.data.ctrl[:] = np.clip(tgt, e.lo, e.hi)
        for _ in range(SUBSTEPS):
            mujoco.mj_step(e.model, e.data)
        up = e.data.xmat[1].reshape(3, 3)[:, 1][1]
        h = e.data.xpos[1][1] - e.pelvis_h0
        cr, cl = e.foot_clear(e.foot_r), e.foot_clear(e.foot_l)
        peak = max(peak, cr, cl)
        for g_, c_ in ((e.foot_r, cr), (e.foot_l, cl)):          # 貼地的腳在地上滑多快(cm/s):真的在走,貼地的腳應該不動
            if c_ < 0.001:
                mujoco.mj_objectVelocity(e.model, e.data, mujoco.mjtObj.mjOBJ_GEOM, g_, vtmp, 0)
                slip_sum += float(np.hypot(vtmp[3], vtmp[5])) * 100
                slip_n += 1
        upf = [cr >= 0.010, cl >= 0.010]
        lift_t += 1 if (upf[0] or upf[1]) else 0
        for j in (0, 1):
            if upf[j] and not prev_up[j]:
                events += 1
        prev_up = upf
        if up < 0.7 or h < -0.04:
            fell = True
            break
    k = i + 1
    return dict(
        fell=bool(fell), seconds_survived=round(k * DT, 1),
        forward_cm=round(-(float(e.data.qpos[2]) - z0) * 100, 1),
        lateral_cm=round((float(e.data.qpos[0]) - x0) * 100, 1),
        yaw_deg=round(yaw_deg(e) - y0, 1),
        peak_lift_mm=round(peak * 1000, 1),
        lift_events_per_20s=round(events * (20.0 / (k * DT)), 1),
        slip_cm_s=round(slip_sum / max(1, slip_n), 2))


def to_motor_positions(kf, env):
    """弧度 → 馬達位置值(設定檔裡的值,沒加 Offset):pos = zero + ctrl / factor"""
    by = meta_by_motor()
    pos = np.zeros_like(kf)
    for j, mnum in enumerate(env.motors):
        a = by[mnum]
        pos[:, j] = a["zero"] + kf[:, j] / a["factor"]
    return pos


def export(model_name, env_name, n, first_frame, out_dir, smooth=True):
    cyc, sd, e = mean_cycle(model_name, env_name)
    kf = keyframes(cyc, n, smooth)
    res = play_open_loop(e, kf, n)
    pos = np.rint(to_motor_positions(kf, e)).astype(int)
    warn = []
    if pos.min() < 800 or pos.max() > 2200:
        warn.append("有位置超出 800~2200(伺服的有效範圍),已夾住:最小 %d 最大 %d" % (pos.min(), pos.max()))
    pos_c = np.clip(pos, 800, 2200)
    T_ms = int(round(PERIOD * DT * 1000 / n))
    motors = list(e.motors)
    order = sorted(range(len(motors)), key=lambda j: motors[j])
    os.makedirs(out_dir, exist_ok=True)
    base = os.path.join(out_dir, "gait_N%d" % n)
    # CSV
    with open(base + ".csv", "w", encoding="utf-8-sig") as f:
        f.write("幀,時間ms," + ",".join("馬達%d" % motors[j] for j in order) + "\n")
        for k in range(n):
            f.write("%d,%d," % (first_frame + k, T_ms) + ",".join(str(pos_c[k, j]) for j in order) + "\n")
    # .frame
    fd = base + ".frames"
    os.makedirs(fd, exist_ok=True)
    for k in range(n):
        mp = [[0, 1500, 100] for _ in range(26)]
        for j, mnum in enumerate(motors):
            mp[mnum - 1] = [1, int(pos_c[k, j]), T_ms]
        with open(os.path.join(fd, "frame_%03d.frame" % (first_frame + k)), "w", encoding="utf-8") as f:
            f.write('{"MotorFrameIdx":%d,"MotorPos":[\n' % (first_frame + k))
            f.write(",\n".join("  [%d,%d,%d]" % tuple(x) for x in mp))
            f.write("\n]}\n")
    # custom.ino 片段
    with open(base + ".ino.txt", "w", encoding="utf-8") as f:
        f.write("// 走路:%d 個動作幀重複播放(幀 %d~%d,每幀 %d ms)。條件請換成你的按鍵。\n" % (n, first_frame, first_frame + n - 1, T_ms))
        f.write("do {\n")
        for k in range(n):
            f.write("  SetFrameRun(%d, %d);\n" % (first_frame + k, T_ms))
        f.write("} while (/* 你的條件,例如 LS_DIR == DIR_UP && pad_getKey() != PAD_BTN_STOP */);\n")
    info = dict(model=model_name, env=env_name, n=n, first_frame=first_frame, frame_ms=T_ms, motors=motors,
                positions={str(first_frame + k): {str(motors[j]): int(pos_c[k, j]) for j in order} for k in range(n)},
                open_loop_test=res, warnings=warn,
                cycle_spread_rad=round(float(np.mean(sd)), 4))
    json.dump(info, open(base + ".json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    return info


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("model")
    ap.add_argument("--env", default="straighter")
    ap.add_argument("--n", type=int, default=4)
    ap.add_argument("--first-frame", type=int, default=40)
    ap.add_argument("--out", default="gait")
    ap.add_argument("--scan", default=None, help="逗號分隔的 N,例如 4,5,8,10,20:只測試不輸出檔案,比較不同幀數的開環表現")
    a = ap.parse_args()
    if a.scan:
        cyc, sd, e = mean_cycle(a.model, a.env)
        print("模型步態的穩定度(每個相位的標準差平均,弧度):%.4f" % float(np.mean(sd)))
        for n in [int(x) for x in a.scan.split(",")]:
            kf = keyframes(cyc, n)
            print("N=%2d 幀(每幀 %3d ms):%s" % (n, int(PERIOD * DT * 1000 / n), json.dumps(play_open_loop(e, kf, n), ensure_ascii=False)))
    else:
        info = export(a.model, a.env, a.n, a.first_frame, os.path.join(HERE, a.out))
        print(json.dumps({k: info[k] for k in ("n", "frame_ms", "open_loop_test", "warnings", "cycle_spread_rad")}, ensure_ascii=False, indent=1))
        print("已輸出到 %s\\gait_N%d.*" % (os.path.join(HERE, a.out), a.n))
