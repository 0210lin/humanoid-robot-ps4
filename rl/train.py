"""
訓練(PPO)。
用法:python train.py [總步數] [名稱] [--from 舊模型名稱]
  例:python train.py 4000000 walk5 --from walk4     接著 walk4 的經驗(神經網路參數)繼續練
      python train.py 4000000 walk5                  從零開始
  名稱以 walk 開頭 = 走路環境,否則 = 站立環境。--from 的模型輸入輸出格式要一樣(走路的各版本都一樣,站立和走路不一樣)。
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


def parse_args():
    args = sys.argv[1:]
    src = None
    if "--from" in args:
        i = args.index("--from")
        src = args[i + 1]
        del args[i:i + 2]
    total = int(args[0]) if len(args) > 0 else 300000
    name = args[1] if len(args) > 1 else "stand"
    return total, name, src


TOTAL, NAME, SRC = parse_args()


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
    out = os.path.join(HERE, "runs", NAME)
    os.makedirs(out, exist_ok=True)
    env = VecMonitor(SubprocVecEnv([make(i) for i in range(N_ENV)]))
    if SRC:
        path = os.path.join(HERE, "runs", SRC, "model.zip")
        model = PPO.load(path, env=env, device="cpu")
        # 舊模型的探索雜訊已經縮得很小;獎勵改了,要重新探索,所以把雜訊放大到至少 e^-2.2 ≈ 0.11(舊的各關節約 0.04~0.14,放太大一開始就會把自己甩倒)
        model.policy.log_std.data.clamp_(min=-2.2)
        print("接著 %s 的經驗繼續練(神經網路參數沿用,獎勵用新的)" % SRC, flush=True)
    else:
        model = PPO("MlpPolicy", env, n_steps=512, batch_size=1280, learning_rate=3e-4, gamma=0.98,
                    policy_kwargs=dict(net_arch=[128, 128], log_std_init=-1.5), device="cpu", verbose=0, seed=0)
        print("從零開始練", flush=True)
    model.learn(total_timesteps=TOTAL, callback=Log(out))
    model.save(os.path.join(out, "model"))
    print("完成,模型存在", out)
