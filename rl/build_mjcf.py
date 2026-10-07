"""
把這台機器人(robot_stl 的 STL 零件 + rig.json 的結構)轉成 MuJoCo 物理模型。

  python build_mjcf.py              產生 robot.xml(浮動底座,可以站在地上)和 robot_meta.json
  python build_mjcf.py --fixed-base 同時產生 robot_fixed.xml(底座固定、無重力,只用來驗證運動學)

做法:
  ・所有 body 的座標系都和「零位姿勢」的 CAD 世界座標重合(pos = 0),關節位置、零件網格、質心都直接用 CAD 座標,
    重力設成 -Y(CAD 的 Y 軸朝上),所以不需要任何座標轉換。
  ・腿是雙平行四邊形:曲柄(軸虛連趕)、假連桿(虛虛連趕)各自一個 body,用 hinge 接起來,再用 connect 約束把迴路閉合。
    伺服推的是「曲柄 ↔ 膝蓋塊」之間的 hinge。
  ・質量:零件體積 × 密度(伺服用固定質量),再縮放到 total_mass_kg。
"""
import json
import math
import os
import re
import shutil
import sys

import numpy as np
import trimesh

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
STL_DIR = os.path.join(ROOT, os.environ.get("ROBOT_STL_DIR", "robot_stl"))      # 例如 ROBOT_STL_DIR=robot_stl_sym(對稱版)
SUF = os.environ.get("ROBOT_SUFFIX", "")                                          # 輸出檔名後綴,例如 _sym → robot_sym.xml
MESH_DIR = os.path.join(HERE, "meshes" + SUF)

rig = json.load(open(os.path.join(STL_DIR, "rig.json"), encoding="utf-8"))
params = json.load(open(os.path.join(HERE, "robot_params.json"), encoding="utf-8"))

NODES = rig["nodes"]                       # index 0 = 節點 1
JOINTS = {j["motor"]: j for j in rig["joints"]}
OWNER = rig.get("owner", {})
DEG = math.pi / 180.0


# ---------------------------------------------------------------- 零件的質量性質
def part_props(path, servo, density, servo_mass):
    m = trimesh.load(path, force="mesh")
    m.apply_scale(0.001)                   # mm → m
    ok = m.is_watertight and m.volume > 1e-12
    if not ok:
        m = m.convex_hull                  # 不是封閉網格:用凸包估
    vol = abs(m.volume)
    com = np.array(m.center_mass)
    inertia_unit = np.array(m.moment_inertia)       # 密度 1 時繞質心的慣量
    mass = servo_mass if servo else density * vol
    inertia = inertia_unit * (mass / vol)
    return mass, com, inertia, ok


def combine(props):
    """多個零件合成一個 body 的 (質量, 質心, 繞質心的慣量)"""
    M = sum(p[0] for p in props)
    if M <= 0:
        return 1e-6, np.zeros(3), np.eye(3) * 1e-12
    com = sum(p[0] * p[1] for p in props) / M
    I = np.zeros((3, 3))
    for m, c, ii in [(p[0], p[1], p[2]) for p in props]:
        d = c - com
        I += ii + m * (np.dot(d, d) * np.eye(3) - np.outer(d, d))
    return M, com, I


# ---------------------------------------------------------------- 讀零件
parts = []                                   # {name, file, owner(node), servo, bbox, mass, com, I}
files = sorted(f for f in os.listdir(STL_DIR) if f.lower().endswith(".stl"))
os.makedirs(MESH_DIR, exist_ok=True)
for i, f in enumerate(files):
    name = os.path.splitext(f)[0]
    servo = params["servo_key"] in name
    path = os.path.join(STL_DIR, f)
    mass, com, I, ok = part_props(path, servo, params["density_kg_m3"], params["servo_mass_kg"])
    mesh_name = "m%02d" % i
    shutil.copyfile(path, os.path.join(MESH_DIR, mesh_name + ".stl"))
    raw = trimesh.load(path, force="mesh")
    parts.append(dict(name=name, mesh=mesh_name, owner=int(OWNER.get(name, 0)), servo=servo,
                      bmin=raw.bounds[0] * 0.001, bmax=raw.bounds[1] * 0.001, mass=mass, com=com, I=I, watertight=ok))

# 電池:STL 沒有電池本體,只有「電池盒」外殼。電池是一個固定質量,放在電池盒的外框中心
extra = params.get("extra_electronics_kg", 0.0)
batt = params.get("battery_kg", 0.0)
if batt > 0:
    box = next((p for p in parts if params.get("battery_part", "電池盒-1") in p["name"]), None)
    if box is None:
        raise SystemExit("找不到電池盒零件:" + params.get("battery_part", "電池盒-1"))
    size = box["bmax"] - box["bmin"]
    Ib = batt / 12.0 * np.diag([size[1] ** 2 + size[2] ** 2, size[0] ** 2 + size[2] ** 2, size[0] ** 2 + size[1] ** 2])
    parts.append(dict(name="(電池)", mesh=None, owner=box["owner"], servo=False, fixed=True, bmin=box["bmin"], bmax=box["bmax"],
                      mass=batt, com=(box["bmin"] + box["bmax"]) / 2, I=Ib, watertight=True))
for p in parts:
    p.setdefault("fixed", p["servo"])             # 伺服、電池的質量是實際數字,不縮放

# 縮放:固定質量的(伺服、電池、其他電子)不動,其餘結構零件縮放到剛好湊成總重
target = params.get("total_mass_kg")
fixed_sum = sum(p["mass"] for p in parts if p["fixed"]) + extra
free_sum = sum(p["mass"] for p in parts if not p["fixed"])
scale = 1.0
if target:
    scale = (target - fixed_sum) / free_sum
    if scale <= 0:
        raise SystemExit("總重 %.3f kg 比固定質量(伺服 + 電池 + 其他)%.3f kg 還小,請檢查 robot_params.json" % (target, fixed_sum))
    for p in parts:
        if not p["fixed"]:
            p["mass"] *= scale
            p["I"] = p["I"] * scale
print("零件 %d 個(不是封閉網格、用凸包估的有 %d 個)" % (len(parts), sum(1 for p in parts if not p["watertight"])))
print("伺服 %d 顆 × %.0f g = %.0f g;電池 %.0f g;其他電子 %.0f g;結構零件原估 %.0f g → 縮放 %.3f 倍 → %.0f g;總重 %.0f g"
      % (sum(1 for p in parts if p["servo"]), params["servo_mass_kg"] * 1000, sum(p["mass"] for p in parts if p["servo"]) * 1000, batt * 1000, extra * 1000,
         free_sum * 1000, scale, sum(p["mass"] for p in parts if not p["fixed"]) * 1000, (sum(p["mass"] for p in parts) + extra) * 1000))


# ---------------------------------------------------------------- body 結構
class Body:
    def __init__(self, name):
        self.name = name
        self.parent = None
        self.joint = None          # dict(name,pos,axis,actuated(motor,ctrl_sign,mult),range)
        self.children = []
        self.parts = []
        self.node = None


bodies = {}
pelvis = Body("pelvis")
bodies["pelvis"] = pelvis
node_body = {0: pelvis}


def find_node(side, kind):
    for i, n in enumerate(NODES):
        if n["name"].startswith(side) and kind in n["name"]:
            return i + 1
    raise KeyError((side, kind))


for i, n in enumerate(NODES):
    b = Body("b%02d" % (i + 1))
    b.node = i + 1
    bodies[b.name] = b
    node_body[i + 1] = b

loops = []        # connect 約束:(body1, body2, anchor)
actuators = []    # 每個有馬達驅動的 hinge

for side in ("右", "左"):
    pass


def kind_of(n):
    nm = n["name"]
    for k in ("曲柄1", "假連桿1", "膝蓋塊", "曲柄2", "假連桿2", "腳踝支架"):
        if k in nm:
            return k
    return None


# 先決定每個 body 的 parent / joint
for i, n in enumerate(NODES):
    idx = i + 1
    b = node_body[idx]
    k = kind_of(n)
    parent_node = n["parent"]
    side = n["name"][0]
    axis = n["axis"]
    mot = n["motors"][0] if n["motors"] else None
    mult = n["mult"][0] if n["mult"] else 1
    if k is None:                                   # 一般旋轉節點
        b.parent = node_body[parent_node]
        b.joint = dict(pos=n["pivot"], axis=axis, motor=mot, mult=mult, ctrl_sign=1.0, passive=False)
    elif k == "曲柄1":                              # 大腿 → 曲柄1(被動)
        b.parent = node_body[parent_node]
        b.joint = dict(pos=n["pivot"], axis=axis, motor=None, passive=True)
    elif k == "假連桿1":
        b.parent = node_body[parent_node]
        b.joint = dict(pos=n["pivot"], axis=axis, motor=None, passive=True)
    elif k == "膝蓋塊":                             # 曲柄1 → 膝蓋塊(伺服的 hinge,位置在伺服軸 S)
        crank = node_body[find_node(side, "曲柄1")]
        b.parent = crank
        b.joint = dict(pos=n["point"], axis=axis, motor=mot, mult=mult, ctrl_sign=-1.0, passive=False)
    elif k == "曲柄2":                              # 膝蓋塊 → 曲柄2(伺服的 hinge,位置在伺服軸 S2)
        b.parent = node_body[parent_node]
        b.joint = dict(pos=n["pivot"], axis=axis, motor=mot, mult=mult, ctrl_sign=1.0, passive=False)
    elif k == "假連桿2":
        b.parent = node_body[parent_node]
        b.joint = dict(pos=n["pivot"], axis=axis, motor=None, passive=True)
    elif k == "腳踝支架":                           # 曲柄2 → 腳踝支架(被動,位置在 B2)
        crank = node_body[find_node(side, "曲柄2")]
        b.parent = crank
        b.joint = dict(pos=n["point"], axis=axis, motor=None, passive=True)
    b.joint["name"] = "j_" + b.name
    b.parent.children.append(b)

# 閉合約束
for side in ("右", "左"):
    t1 = NODES[find_node(side, "曲柄1") - 1]; kk = NODES[find_node(side, "膝蓋塊") - 1]; d1 = NODES[find_node(side, "假連桿1") - 1]
    B, S, A = np.array(t1["pivot"]), np.array(kk["point"]), np.array(d1["pivot"])
    P1 = A - (B - S)
    loops.append((node_body[find_node(side, "假連桿1")], node_body[find_node(side, "膝蓋塊")], P1))
    c2 = NODES[find_node(side, "曲柄2") - 1]; an = NODES[find_node(side, "腳踝支架") - 1]; d2 = NODES[find_node(side, "假連桿2") - 1]
    S2, B2, P2 = np.array(an["pivot"]), np.array(an["point"]), np.array(d2["pivot"])
    A2 = P2 + (B2 - S2)
    loops.append((node_body[find_node(side, "假連桿2")], node_body[find_node(side, "腳踝支架")], A2))

# 零件歸屬
for p in parts:
    node_body[p["owner"]].parts.append(p)

# ---------------------------------------------------------------- 輸出 XML
sv = params["servo"]
ps = params["passive"]
rng = sv["range_deg"] * DEG


def f(v):
    return " ".join("%.6g" % x for x in v)


def emit_body(b, ind, out, fixed_root=False):
    pad = "  " * ind
    out.append('%s<body name="%s" pos="0 0 0">' % (pad, b.name))
    if b is pelvis:
        if not fixed_root:
            out.append('%s  <freejoint name="root"/>' % pad)
    else:
        j = b.joint
        if j["passive"]:
            out.append('%s  <joint name="%s" type="hinge" pos="%s" axis="%s" damping="%g" armature="%g"/>' % (pad, j["name"], f(np.array(j["pos"]) * 0.001), f(j["axis"]), ps["damping"], ps["armature"]))
        else:
            out.append('%s  <joint name="%s" type="hinge" pos="%s" axis="%s" range="%.5f %.5f" damping="%g" armature="%g" frictionloss="%g"/>'
                       % (pad, j["name"], f(np.array(j["pos"]) * 0.001), f(j["axis"]), -rng * 1.2, rng * 1.2, sv["joint_damping"], sv["joint_armature"], sv["joint_frictionloss"]))
    # 慣性
    props = [(p["mass"], p["com"], p["I"]) for p in b.parts]
    if b is pelvis and extra > 0:
        props.append((extra, np.array(pelvis_com_guess), np.eye(3) * extra * 1e-4))
    M, com, I = combine(props)
    I = (I + I.T) / 2 + np.eye(3) * 1e-10
    out.append('%s  <inertial pos="%s" mass="%.7g" fullinertia="%.6e %.6e %.6e %.6e %.6e %.6e"/>' % (pad, f(com), M, I[0, 0], I[1, 1], I[2, 2], I[0, 1], I[0, 2], I[1, 2]))
    for p in b.parts:
        if p["mesh"] is None:
            continue
        out.append('%s  <geom type="mesh" mesh="%s" group="1" contype="0" conaffinity="0" rgba="%s"/>' % (pad, p["mesh"], "0.55 0.6 0.68 1" if not p["servo"] else "0.75 0.4 0.55 1"))
        if "腳底板" in p["name"]:
            c = (p["bmin"] + p["bmax"]) / 2
            h = (p["bmax"] - p["bmin"]) / 2
            out.append('%s  <geom name="foot_%s" type="box" pos="%s" size="%s" group="0" contype="1" conaffinity="1" friction="%g 0.005 0.0001" rgba="0.2 0.8 0.3 0.3"/>' % (pad, b.name, f(c), f(h), params["foot_friction"]))
    for c in b.children:
        emit_body(c, ind + 1, out, fixed_root)
    out.append("%s</body>" % pad)


# 骨盆質心(電子零件放在骨盆的零件質心附近)
_pm = [(p["mass"], p["com"], p["I"]) for p in pelvis.parts]
pelvis_com_guess = combine(_pm)[1] if _pm else np.zeros(3)

foot_min_y = min(p["bmin"][1] for p in parts if "腳底板" in p["name"])


def build_xml(fixed):
    out = ['<mujoco model="humanoid_%s">' % ("fixed" if fixed else "free"),
           '  <compiler angle="radian" meshdir="meshes%s"' % SUF + ' autolimits="true"/>',
           '  <option gravity="%s" timestep="0.002" integrator="implicitfast">%s</option>' % ("0 0 0" if fixed else "0 -9.81 0", '<flag contact="disable"/>' if fixed else ""),
           '  <default><geom friction="1 0.005 0.0001"/></default>',
           "  <asset>"]
    for p in parts:
        if p["mesh"] is None:
            continue
        out.append('    <mesh name="%s" file="%s.stl" scale="0.001 0.001 0.001"/>' % (p["mesh"], p["mesh"]))
    out.append("  </asset>")
    out.append("  <worldbody>")
    if not fixed:
        out.append('    <geom name="floor" type="plane" pos="0 %.5f 0" zaxis="0 1 0" size="3 3 0.1" contype="1" conaffinity="1" friction="%g 0.005 0.0001" rgba="0.25 0.28 0.32 1"/>' % (foot_min_y, params["foot_friction"]))
        out.append('    <light pos="0 1 1" dir="0 -1 -1" diffuse="0.8 0.8 0.8"/>')
    emit_body(pelvis, 2, out, fixed_root=fixed)
    out.append("  </worldbody>")
    out.append("  <equality>")
    for b1, b2, anchor in loops:
        out.append('    <connect body1="%s" body2="%s" anchor="%s" solref="0.005 1"/>' % (b1.name, b2.name, f(np.array(anchor) * 0.001)))
    out.append("  </equality>")
    out.append("  <actuator>")
    meta_act = []
    for b in bodies.values():
        if b is pelvis or b.joint["passive"] or b.joint["motor"] is None:
            continue
        j = b.joint
        mot = j["motor"]
        jj = JOINTS[mot]
        factor = j["ctrl_sign"] * j["mult"] * jj["sign"] * jj["degPer"] * DEG      # ctrl(rad) = factor * (馬達位置 - 零位)
        out.append('    <position name="a_m%02d" joint="%s" kp="%g" kv="%g" ctrlrange="%.5f %.5f" forcerange="%g %g"/>' % (mot, j["name"], sv["kp"], sv["kv"], -rng, rng, -sv["max_torque_nm"], sv["max_torque_nm"]))
        meta_act.append(dict(motor=mot, joint=j["name"], body=b.name, node=b.node, node_name=NODES[b.node - 1]["name"], factor=factor, zero=jj["zero"]))
    out.append("  </actuator>")
    out.append("</mujoco>")
    return "\n".join(out), meta_act


xml, meta_act = build_xml(False)
open(os.path.join(HERE, "robot%s.xml" % SUF), "w", encoding="utf-8").write(xml)
meta = dict(
    gravity_axis="-Y (CAD 的 Y 朝上)",
    total_mass_kg=sum(p["mass"] for p in parts) + extra,
    foot_min_y_m=foot_min_y,
    actuators=sorted(meta_act, key=lambda a: a["motor"]),
    bodies={b.name: dict(node=b.node, node_name=(NODES[b.node - 1]["name"] if b.node else "骨盆"), parent=(b.parent.name if b.parent else None),
                         parts=[p["name"] for p in b.parts], mass=sum(p["mass"] for p in b.parts)) for b in bodies.values()},
    loops=[dict(body1=b1.name, body2=b2.name, anchor=list(map(float, a))) for b1, b2, a in loops],
)
json.dump(meta, open(os.path.join(HERE, "robot_meta%s.json" % SUF), "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print("已產生 robot.xml(%d 個 body,%d 個馬達驅動的關節,%d 個閉合約束),總質量 %.3f kg" % (len(bodies), len(meta_act), len(loops), meta["total_mass_kg"]))

if "--fixed-base" in sys.argv:
    xml2, _ = build_xml(True)
    open(os.path.join(HERE, "robot_fixed%s.xml" % SUF), "w", encoding="utf-8").write(xml2)
    print("已產生 robot_fixed.xml(底座固定、無重力,驗證運動學用)")
