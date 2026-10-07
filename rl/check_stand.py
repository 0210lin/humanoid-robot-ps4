"""
站立測試:把機器人放在地上(腳底板貼地),所有馬達都下零位指令(1500),加上重力,看它站不站得住。
"""
import json
import math
import os

import mujoco
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
SUF = os.environ.get("ROBOT_SUFFIX", "")
meta = json.load(open(os.path.join(HERE, "robot_meta%s.json" % SUF), encoding="utf-8"))
model = mujoco.MjModel.from_xml_path(os.path.join(HERE, "robot%s.xml" % SUF))
data = mujoco.MjData(model)
mujoco.mj_forward(model, data)

total = float(np.sum(model.body_mass))
com = np.array(data.subtree_com[1])                   # 整台質心(世界座標,公尺)
feet = []
for g in range(model.ngeom):
    nm = mujoco.mj_id2name(model, mujoco.mjtObj.mjOBJ_GEOM, g)
    if nm and nm.startswith("foot_"):
        c = data.geom_xpos[g]; s = model.geom_size[g]
        feet.append((nm, c.copy(), s.copy()))
xs = [f[1][0] + sx for f in feet for sx in (-f[2][0], f[2][0])]
zs = [f[1][2] + sz for f in feet for sz in (-f[2][2], f[2][2])]
print("總質量 %.3f kg" % total)
print("整台質心(公尺): x=%.3f y=%.3f z=%.3f(y 是高度,離地約 %.0f mm)" % (com[0], com[1], com[2], (com[1] - model.geom_pos[0][1]) * 1000 if False else (com[1] - (-0.115)) * 1000))
print("腳底板範圍: x %.3f ~ %.3f, z %.3f ~ %.3f(兩隻腳的外框)" % (min(xs), max(xs), min(zs), max(zs)))
inside = min(xs) <= com[0] <= max(xs) and min(zs) <= com[2] <= max(zs)
print("質心投影在兩腳外框內:", "是" if inside else "否(靜止時站不住,會倒)")

mujoco.mj_resetData(model, data)
fall_t = None
hist = []
steps = int(4.0 / model.opt.timestep)
for i in range(steps):
    mujoco.mj_step(model, data)
    if i % 250 == 0:
        up = np.array(data.xmat[1]).reshape(3, 3)[:, 1]            # 骨盆的「上」(它的 Y 軸)在世界裡指向哪
        tilt = math.degrees(math.acos(max(-1, min(1, up[1]))))
        pelvis_h = data.xpos[1][1]
        hist.append((i * model.opt.timestep, tilt, data.subtree_com[1][1]))
    if fall_t is None:
        up = np.array(data.xmat[1]).reshape(3, 3)[:, 1]
        if math.degrees(math.acos(max(-1, min(1, up[1])))) > 35:
            fall_t = i * model.opt.timestep
print("時間(s)  骨盆傾斜(度)  質心高度(mm,離地面)")
for t, tilt, h in hist[::2]:
    print("  %.2f      %5.1f        %6.1f" % (t, tilt, (h - (-0.115)) * 1000))
print("倒下(傾斜 > 35 度):", ("在 %.2f 秒" % fall_t) if fall_t is not None else "4 秒內沒有倒")
tau = [abs(data.actuator_force[a]) for a in range(model.nu)]
print("4 秒後各馬達力矩(Nm):最大 %.2f(上限 %.1f),有 %d 顆超過上限 80%%" % (max(tau), model.actuator_forcerange[0][1], sum(1 for t in tau if t > 0.8 * model.actuator_forcerange[0][1])))
