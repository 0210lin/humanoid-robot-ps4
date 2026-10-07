"""錄影:python record.py [模型名稱] [--zero]  → runs\<名稱>\demo.gif(10 秒;畫面上寫著走了多遠、速度、腳離地)"""
import os
import sys

import mujoco
import numpy as np
from PIL import Image, ImageDraw, ImageFont

from hud import Progress
from robot_env import make_env

HERE = os.path.dirname(os.path.abspath(__file__))
name = next((a for a in sys.argv[1:] if not a.startswith("--")), "stand")
zero = "--zero" in sys.argv
pol = None
if not zero:
    from stable_baselines3 import PPO
    pol = PPO.load(os.path.join(HERE, "runs", name, "model"), device="cpu")

font = None
for f in ["C:/Windows/Fonts/msjh.ttc", "C:/Windows/Fonts/mingliu.ttc", "C:/Windows/Fonts/msyh.ttc"]:
    if os.path.exists(f):
        font = ImageFont.truetype(f, 13)
        break

env = make_env(name, seed=3)
obs, _ = env.reset()
prog = Progress(env)
prog.info = {}
r = mujoco.Renderer(env.model, 360, 480)
cam = env.model.camera("view").id
frames = []
falls = 0
for k in range(250):
    a = np.zeros(env.nu) if zero else pol.predict(obs, deterministic=True)[0]
    obs, _, te, tr, info = env.step(a)
    prog.update(info)
    if k % 2 == 0:
        r.update_scene(env.data, cam)
        im = Image.fromarray(r.render())
        if font is not None:
            dr = ImageDraw.Draw(im)
            y = 4
            for line in prog.chinese():
                dr.rectangle([2, y - 1, 2 + dr.textlength(line, font=font) + 6, y + 16], fill=(0, 0, 0))
                dr.text((5, y), line, font=font, fill=(255, 255, 255))
                y += 19
        frames.append(im)
    if te or tr:
        falls += te
        obs, _ = env.reset()
        prog.reset()
out = os.path.join(HERE, "runs", name, "demo%s.gif" % ("_zero" if zero else ""))
frames[0].save(out, save_all=True, append_images=frames[1:], duration=80, loop=0)
print("存好了:", out, "| 這段期間倒了", falls, "次")
