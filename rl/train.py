"""
訓練站穩 + 被推不倒(PPO)。
用法:python train.py [總步數]      預設 300000(煙霧測試);正式訓練用 3000000 以上
輸出:runs\<名稱>\  model.zip(最新)、model_<步數>.zip(每 20 萬步存一份)、progress.csv(獎勵曲線)
"""
import csv
import os
import sys
import time

import numpy as np
from stable_baselines3 import PPO
from stable_baselines3.common.callbacks import BaseCallback
from stable_baselines3.common.vec_env import SubprocVecEnv, VecMonitor

from robot_env import make_env

HERE = os.path.dirname(os.path.abspath(__file__))
N_ENV = 10
NAME = sys.argv[2] if len(sys.argv) > 2 else "stand"


def make(i):
    return lambda: make_env(NAME, seed=1000 + i)


class Log(BaseCallback):
    def __init__(self, out):
        super().__init__()
        self.out, self.t0, self.last_save = out, time.time(), 0
        self.f = open(os.path.join(out, "progress.csv"), "w", newline="")
        self.w = csv.writer(self.f)
        self.w.writerow(["steps", "minutes", "mean_reward", "mean_episode_len"])

    def _on_rollout_end(self):
        buf = self.model.ep_info_buffer
        if buf:
            r = np.mean([e["r"] for e in buf]); l = np.mean([e["l"] for e in buf])
            self.w.writerow([self.num_timesteps, round((time.time() - self.t0) / 60, 2), round(r, 1), round(l, 1)])
            self.f.flush()
            print("%8d 步  %5.1f 分鐘  平均獎勵 %7.1f  平均撐住 %5.0f / 500 步" % (self.num_timesteps, (time.time() - self.t0) / 60, r, l), flush=True)
        if self.num_timesteps - self.last_save >= 200000:
            self.last_save = self.num_timesteps
            self.model.save(os.path.join(self.out, "model_%d" % self.num_timesteps))
        self.model.save(os.path.join(self.out, "model"))

    def _on_step(self):
        return True


if __name__ == "__main__":
    total = int(sys.argv[1]) if len(sys.argv) > 1 else 300000
    name = sys.argv[2] if len(sys.argv) > 2 else "stand"
    out = os.path.join(HERE, "runs", name)
    os.makedirs(out, exist_ok=True)
    env = VecMonitor(SubprocVecEnv([make(i) for i in range(N_ENV)]))
    model = PPO("MlpPolicy", env, n_steps=512, batch_size=1280, learning_rate=3e-4, gamma=0.98,
                policy_kwargs=dict(net_arch=[128, 128], log_std_init=-1.5), device="cpu", verbose=0, seed=0)
    model.learn(total_timesteps=total, callback=Log(out))
    model.save(os.path.join(out, "model"))
    print("完成,模型存在", out)
