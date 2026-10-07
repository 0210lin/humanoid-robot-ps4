"""
看機器人被訓練出來的樣子(開一個 3D 視窗,可以用滑鼠轉視角)。
用法:python watch.py [模型名稱,預設 stand] [--zero 不用 AI,全零動作對照]
視窗左上角寫著這一回合走了多遠、速度、轉向、腳離地多高(地板每個深淺格約 10 公分,亮線每 20 公分)。
走路模型沒有隨機推力;站立模型每 1~3 秒有一個隨機推力。關掉視窗就結束。
"""
import os
import sys
import time

import mujoco
import mujoco.viewer
import numpy as np

from hud import Progress
from robot_env import make_env, SUBSTEPS

HERE = os.path.dirname(os.path.abspath(__file__))
name = next((a for a in sys.argv[1:] if not a.startswith("--")), "stand")
zero = "--zero" in sys.argv
policy = None
if not zero:
    from stable_baselines3 import PPO
    policy = PPO.load(os.path.join(HERE, "runs", name, "model"), device="cpu")

env = make_env(name, seed=int(time.time()))
obs, _ = env.reset()
prog = Progress(env)
prog.info = {}
with mujoco.viewer.launch_passive(env.model, env.data) as v:
    v.cam.type = mujoco.mjtCamera.mjCAMERA_FIXED          # 腳在下面、近一點;左側面板 Rendering → Camera 可換 view/front/side/back
    v.cam.fixedcamid = env.model.camera("view").id
    while v.is_running():
        t0 = time.time()
        a = np.zeros(env.nu) if zero else policy.predict(obs, deterministic=True)[0]
        obs, r, te, tr, info = env.step(a)
        prog.update(info)
        labels, vals = prog.english()
        v.set_texts((mujoco.mjtFontScale.mjFONTSCALE_150, mujoco.mjtGridPos.mjGRID_TOPLEFT, labels, vals))
        v.sync()
        if te or tr:
            time.sleep(1.0)                               # 停一下,讓你看到這一回合的最後數字
            obs, _ = env.reset()
            prog.reset()
            if not zero:
                try:
                    policy = PPO.load(os.path.join(HERE, "runs", name, "model"), device="cpu")   # 訓練中:每回合換成最新的模型
                except Exception:
                    pass
        time.sleep(max(0, 0.04 - (time.time() - t0)))     # 即時速度(25 Hz)
