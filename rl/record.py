"""錄影:python record.py [模型名稱] [--zero]  → runs\<名稱>\demo.gif(被推的 20 秒,左邊 AI、可加 --zero 看對照)"""
import os, sys
from PIL import Image
import mujoco, numpy as np
from robot_env import make_env
HERE = os.path.dirname(os.path.abspath(__file__))
name = next((a for a in sys.argv[1:] if not a.startswith("--")), "stand")
zero = "--zero" in sys.argv
pol = None
if not zero:
    from stable_baselines3 import PPO
    pol = PPO.load(os.path.join(HERE, "runs", name, "model"), device="cpu")
env = make_env(name, seed=3)
obs, _ = env.reset()
r = mujoco.Renderer(env.model, 360, 480)
cam = env.model.camera('view').id
frames = []; falls = 0
for k in range(250):
    if k == 0: still = None
    a = np.zeros(env.nu) if zero else pol.predict(obs, deterministic=True)[0]
    obs, _, te, tr, _ = env.step(a)
    if k % 2 == 0:
        r.update_scene(env.data, cam); frames.append(r.render())
    if te or tr:
        falls += te; obs, _ = env.reset()
out = os.path.join(HERE, "runs", name, "demo%s.gif" % ("_zero" if zero else ""))
ims = [Image.fromarray(f) for f in frames]; ims[0].save(out, save_all=True, append_images=ims[1:], duration=80, loop=0)
print("存好了:", out, "| 這段期間倒了", falls, "次")
