"""
把左腿對齊成右腿的鏡像(產生 robot_stl_sym\\,不動原本的 robot_stl\\):
  1. 左髖的 2 個零件(旋轉髖關節 1、2)換成「右髖那塊的鏡像」
  2. 左腿(髖以下)整條往上移,讓左右腿的連桿對稱(量出來約 2.6 mm)
用法:python symmetrize_left_leg.py            只量偏差,不產生檔案
      python symmetrize_left_leg.py --write    產生 robot_stl_sym\\
"""
import json
import os
import shutil
import struct
import sys

import numpy as np
import trimesh

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "robot_stl")
DST = os.path.join(ROOT, "robot_stl_sym")


if os.path.exists(os.path.join(SRC, "mix - 連趕踝關節 (鏡射)-3.STL")):
    sys.exit("robot_stl 已經是對稱版(左髖已換成右髖的鏡像),不要再執行,不然左腿會被移動第二次。要還原:把 robot_stl\_original 裡的檔案搬回 robot_stl。")


def load(name):
    return trimesh.load(os.path.join(SRC, "mix - %s.STL" % name), force="mesh")


def bbox_center(name):
    m = load(name)
    return (m.bounds[0] + m.bounds[1]) / 2


# 對稱面:用成對的伺服馬達和腳底板算 x
pairs_x = []
for a, b in [("cas-1880舵機-1", "cas-1880舵機-10"), ("cas-1880舵機-2", "cas-1880舵機-11"), ("cas-1880舵機-3", "cas-1880舵機-12"),
             ("腳底板-1", "腳底板-2"), ("膝蓋-1", "膝蓋-3"), ("膝蓋-2", "膝蓋-4")]:
    pairs_x.append((bbox_center(a)[0] + bbox_center(b)[0]) / 2)
xc = float(np.mean(pairs_x))
print("對稱面 x = %.2f mm(各組成對零件算出來:%s)" % (xc, ", ".join("%.1f" % v for v in pairs_x)))

# 左右對應零件的偏差:右腳零件鏡射到左邊,和左腳對應零件比
pairs = [("cas-1880舵機-1", "cas-1880舵機-10"), ("cas-1880舵機-2", "cas-1880舵機-11"), ("cas-1880舵機-3", "cas-1880舵機-12"),
         ("膝蓋-1", "膝蓋-3"), ("膝蓋-2", "膝蓋-4"), ("腳底板-1", "腳底板-2"),
         ("軸虛連趕-1", "軸虛連趕-9"), ("軸虛連趕-2", "軸虛連趕-6"), ("虛虛連趕-1", "虛虛連趕-9"), ("虛虛連趕-2", "虛虛連趕-5"),
         ("軸虛連趕-3", "軸虛連趕-8"), ("軸虛連趕-4", "軸虛連趕-7"), ("虛虛連趕-3", "虛虛連趕-8"), ("虛虛連趕-4", "虛虛連趕-6"),
         ("連桿墊片-3", "連桿墊片-7"), ("連桿墊片-4", "連桿墊片-8"), ("連桿墊片-1", "連桿墊片-5"), ("連桿墊片-2", "連桿墊片-6")]
diffs = []
print("右腳零件鏡射後 vs 左腳對應零件(中心位置差,mm;dy = 左 − 右鏡射):")
for r, l in pairs:
    cr, cl = bbox_center(r), bbox_center(l)
    mr = np.array([2 * xc - cr[0], cr[1], cr[2]])
    d = cl - mr
    diffs.append(d)
    print("  %-16s ↔ %-16s  dx=%+5.2f dy=%+5.2f dz=%+5.2f" % (r, l, d[0], d[1], d[2]))
D = np.array(diffs)
print("平均 dx=%+.2f dy=%+.2f dz=%+.2f;dy 的範圍 %.2f ~ %.2f" % (D[:, 0].mean(), D[:, 1].mean(), D[:, 2].mean(), D[:, 1].min(), D[:, 1].max()))

hipR = load("連趕踝關節 (鏡射)-1")
l1, l2 = load("旋轉髖關節1-2"), load("旋轉髖關節2-2")
lb = np.array([min(l1.bounds[0][i], l2.bounds[0][i]) for i in range(3)]), np.array([max(l1.bounds[1][i], l2.bounds[1][i]) for i in range(3)])
rb = hipR.bounds
print("右髖支架外框 y %.1f ~ %.1f(高 %.1f);左髖兩個零件合起來 y %.1f ~ %.1f(高 %.1f)" % (rb[0][1], rb[1][1], rb[1][1] - rb[0][1], lb[0][1], lb[1][1], lb[1][1] - lb[0][1]))

if "--write" not in sys.argv:
    sys.exit(0)

# ---------- 產生 robot_stl_sym ----------
dy_shift = -float(D[:, 1].mean())           # 左腿要往上移多少(dy 是左比右低,所以移 −dy)
print("\n左腿(髖以下)整條移動 dy = %+.2f mm" % dy_shift)
if os.path.exists(DST):
    shutil.rmtree(DST)
shutil.copytree(SRC, DST, ignore=shutil.ignore_patterns("_unused", "rig.json.bak*"))


def write_stl(mesh, path):
    f = mesh.faces
    v = mesh.vertices
    with open(path, "wb") as fh:
        fh.write(b"mirrored/shifted by symmetrize_left_leg.py".ljust(80, b" "))
        fh.write(struct.pack("<I", len(f)))
        for tri in f:
            p = v[tri]
            n = np.cross(p[1] - p[0], p[2] - p[0])
            ln = np.linalg.norm(n)
            n = n / ln if ln > 0 else n
            fh.write(struct.pack("<3f", *n))
            for q in p:
                fh.write(struct.pack("<3f", *q))
            fh.write(b"\x00\x00")


# 左腿的零件(髖以下):用 rig.json 的歸屬判斷
rig = json.load(open(os.path.join(SRC, "rig.json"), encoding="utf-8"))
left_leg = ["膝蓋-3", "膝蓋-4", "軸虛連趕-6", "軸虛連趕-7", "軸虛連趕-8", "軸虛連趕-9", "虛虛連趕-5", "虛虛連趕-6", "虛虛連趕-8", "虛虛連趕-9",
            "連桿墊片-5", "連桿墊片-6", "連桿墊片-7", "連桿墊片-8", "連趕踝關節 (鏡射)-2", "腳底板-2", "連接塊-5", "連接塊-6",
            "cas-1880舵機-10", "cas-1880舵機-11", "cas-1880舵機-12"]
for name in left_leg:
    path = os.path.join(DST, "mix - %s.STL" % name)
    m = trimesh.load(path, force="mesh")
    m.apply_translation([0, dy_shift, 0])
    write_stl(m, path)
print("已移動左腿零件 %d 個" % len(left_leg))

# 左髖:拿掉旋轉髖關節 1、2,放進右髖支架的鏡像
os.remove(os.path.join(DST, "mix - 旋轉髖關節1-2.STL"))
os.remove(os.path.join(DST, "mix - 旋轉髖關節2-2.STL"))
mir = hipR.copy()
mir.vertices = mir.vertices * np.array([-1, 1, 1]) + np.array([2 * xc, 0, 0])
mir.faces = mir.faces[:, ::-1]                    # 鏡射會讓面的方向反過來,翻回來
write_stl(mir, os.path.join(DST, "mix - 連趕踝關節 (鏡射)-3.STL"))
print("左髖支架:已用右髖支架的鏡像取代旋轉髖關節 1、2(檔名:連趕踝關節 (鏡射)-3)")
print("robot_stl_sym 已產生:", DST)
