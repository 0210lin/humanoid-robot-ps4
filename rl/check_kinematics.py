"""
驗證:MuJoCo 的物理模型(含平行四邊形閉合約束)和 3D 畫面用的運動學(rig.json 的節點)是不是同一個東西。
做法:底座固定、無重力,隨機給 16 顆馬達位置,讓模擬沉澱到平衡,再比較每個 body 的位置(用零件質心當測試點)。
"""
import json
import math
import os
import sys

import mujoco
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SUF = os.environ.get("ROBOT_SUFFIX", "")
rig = json.load(open(os.path.join(ROOT, os.environ.get("ROBOT_STL_DIR", "robot_stl"), "rig.json"), encoding="utf-8"))
meta = json.load(open(os.path.join(HERE, "robot_meta%s.json" % SUF), encoding="utf-8"))
NODES = rig["nodes"]
JOINTS = {j["motor"]: j for j in rig["joints"]}
DEG = math.pi / 180.0


# ---------- 和 Rig.java 一樣的運動學 ----------
def rot_about(axis, pivot, ang):
    axis = np.array(axis, float); pivot = np.array(pivot, float)
    n = np.linalg.norm(axis)
    if n < 1e-9:
        return np.eye(4)
    x, y, z = axis / n
    c, s = math.cos(ang), math.sin(ang); t = 1 - c
    R = np.array([[t*x*x + c, t*x*y - s*z, t*x*z + s*y],
                  [t*x*y + s*z, t*y*y + c, t*y*z - s*x],
                  [t*x*z - s*y, t*y*z + s*x, t*z*z + c]])
    M = np.eye(4); M[:3, :3] = R; M[:3, 3] = pivot - R @ pivot
    return M


def node_matrices(pos):
    cnt = len(NODES)
    mats = [np.eye(4)] + [None] * cnt

    def angle_rad(m):
        j = JOINTS[m]
        return (j["sign"] * (pos[m] - j["zero"]) * j["degPer"]) * DEG

    def resolve(i):
        if mats[i] is not None:
            return mats[i]
        n = NODES[i - 1]
        parent = mats[0] if n["parent"] == 0 else resolve(n["parent"])
        ms = n["motors"]; mu = n["mult"]
        psi = sum(mu[k] * angle_rad(ms[k]) for k in range(len(ms))) / max(1, len(ms)) if ms else 0.0
        if n["type"] == "arc":
            S = np.array(n["point"], float)
            S2 = (rot_about(n["axis"], n["pivot"], psi) @ np.append(S, 1))[:3]
            local = np.eye(4); local[:3, 3] = S2 - S
        else:
            local = rot_about(n["axis"], n["pivot"], psi)
        mats[i] = parent @ local
        return mats[i]

    for i in range(1, cnt + 1):
        resolve(i)
    return mats


# ---------- MuJoCo ----------
model = mujoco.MjModel.from_xml_path(os.path.join(HERE, "robot_fixed%s.xml" % SUF))
data = mujoco.MjData(model)
body_node = {b: v["node"] for b, v in meta["bodies"].items()}
node_points = {}
# 每個 body 的測試點:它的零件質心。簡單起見用 body 的 inertial 質心(CAD 座標)
for b in range(model.nbody):
    nm = mujoco.mj_id2name(model, mujoco.mjtObj.mjOBJ_BODY, b)
    node_points[nm] = np.array(model.body_ipos[b])

rng = np.random.default_rng(7)
worst_all = 0.0
for trial in range(8):
    pos = {m: 1500.0 for m in range(1, 27)}
    for a in meta["actuators"]:
        pos[a["motor"]] = 1500 + rng.uniform(-350, 350)
    mujoco.mj_resetData(model, data)
    targets = []
    for a in meta["actuators"]:
        aid = mujoco.mj_name2id(model, mujoco.mjtObj.mjOBJ_ACTUATOR, "a_m%02d" % a["motor"])
        targets.append((aid, a["factor"] * (pos[a["motor"]] - a["zero"])))
    RAMP = int(os.environ.get("RAMP", "0"))       # 控制量慢慢推上去的步數(0 = 一次跳到位)
    for step in range(RAMP):
        k = (step + 1) / RAMP
        for aid, v in targets:
            data.ctrl[aid] = v * k
        mujoco.mj_step(model, data)
    for aid, v in targets:
        data.ctrl[aid] = v
    # 沉澱:黏滯阻尼大一點,慢慢收斂
    for _ in range(int(os.environ.get("SETTLE", "6000"))):
        mujoco.mj_step(model, data)
    mats = node_matrices(pos)
    worst = 0.0; worst_name = ""
    for b in range(model.nbody):
        nm = mujoco.mj_id2name(model, mujoco.mjtObj.mjOBJ_BODY, b)
        if nm == "world":
            continue
        node = body_node.get(nm) or 0
        p0 = node_points[nm]
        mj = (np.array(data.xpos[b]) + np.array(data.xmat[b]).reshape(3, 3) @ p0) * 1000.0
        an = (mats[node] @ np.append(p0 * 1000.0, 1))[:3]
        err = np.linalg.norm(mj - an)
        if err > worst:
            worst, worst_name = err, "%s(%s)" % (nm, meta["bodies"].get(nm, {}).get("node_name", ""))
    # 閉合約束殘差
    res = float(np.abs(data.efc_pos[:model.neq * 3]).max()) * 1000.0 if model.neq else 0.0
    print("試驗 %d: 最大位置誤差 %.3f mm(%s);閉合約束殘差 %.3f mm" % (trial + 1, worst, worst_name, res))
    worst_all = max(worst_all, worst)
print("全部試驗最大位置誤差: %.3f mm" % worst_all)
