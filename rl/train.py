"""
訓練(PPO)。
用法:python train.py [總步數] [名稱] [--from 舊模型名稱]
  例:python train.py 4000000 walk5 --from walk4     接著 walk4 的經驗(神經網路參數)繼續練
      python train.py 4000000 walk5                  從零開始
  名稱以 walk 開頭 = 走路環境,否則 = 站立環境。--from 的模型輸入輸出格式要一樣(走路的各版本都一樣,站立和走路不一樣)。
輸出:runs\<名稱>\  model.zip(最新)、model_<步數>.zip(每 20 萬步存一份)、progress.csv(獎勵曲線)
"""
import csv
import json
import os
import subprocess
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
    clip_min = 30.0
    forever = False
    if "--from" in args:
        i = args.index("--from")
        src = args[i + 1]
        del args[i:i + 2]
    if "--clip-minutes" in args:
        i = args.index("--clip-minutes")
        clip_min = float(args[i + 1])
        del args[i:i + 2]
    if "--forever" in args:
        forever = True
        args.remove("--forever")
    total = int(args[0]) if len(args) > 0 else 300000
    name = args[1] if len(args) > 1 else "stand"
    if forever:
        total = 2_000_000_000       # 不間斷:幾乎練不完,要停就把訓練程序關掉
    return total, name, src, forever, clip_min


TOTAL, NAME, SRC, FOREVER, CLIP_MIN = parse_args()


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
        if self.num_timesteps - self.last_save >= (1000000 if FOREVER else 200000):
            self.last_save = self.num_timesteps
            self.model.save(os.path.join(self.out, "model_%d" % self.num_timesteps))
        self.model.save(os.path.join(self.out, "model"))

    def _on_step(self):
        return True


class ClipCallback(BaseCallback):
    """每隔 N 分鐘(預設 30):存模型、錄 10 秒影片、量走直不直和抬腳、更新 runs\\<名稱>\\clips\\index.html。
    錄影和量測用另外的程序做,不會干擾訓練,也不會讓訓練停下來重來。"""

    def __init__(self, name, minutes):
        super().__init__()
        self.name, self.every, self.t_last = name, minutes * 60.0, 0.0

    def _snapshot(self):
        try:
            self.model.save(os.path.join(HERE, "runs", self.name, "model"))
            tag = "clip_%s_%dk" % (time.strftime("%Y%m%d_%H%M"), self.num_timesteps // 1000)
            d = os.path.join(HERE, "runs", self.name, "clips")
            os.makedirs(d, exist_ok=True)
            env = dict(os.environ, PYTHONIOENCODING="utf-8")
            subprocess.run([sys.executable, "record.py", self.name, "--out", os.path.join(d, tag + ".gif")], cwd=HERE, env=env, timeout=900)
            subprocess.run([sys.executable, "eval_walk.py", self.name, "--env", self.name, "--csv", os.path.join(d, "metrics.csv"), "--tag", tag],
                           cwd=HERE, env=env, timeout=900)
            self._update_best(tag)
            import clips_index
            print("[影片] 已存 %s,網頁:%s" % (tag, clips_index.build(self.name)), flush=True)
        except Exception as ex:                      # 錄影失敗不要影響訓練
            print("[影片] 失敗:%s" % ex, flush=True)

    def _update_best(self, tag):
        """記住量到最好的那一版(橫向偏離 + 1.5×轉向 越小越好;沒達標抬腳、倒下都重罰),存到 runs\<名稱>_best\model.zip"""
        try:
            rows = list(csv.DictReader(open(os.path.join(HERE, "runs", self.name, "clips", "metrics.csv"), encoding="utf-8")))
            r = rows[-1]
            if r.get("tag") != tag:
                return
            score = float(r["lateral_cm"]) + 1.5 * float(r["yaw_deg"]) + (0 if r["lift_ok"] == "True" else 100) + 200 * int(r["falls"])
            bj = os.path.join(HERE, "runs", self.name, "best.json")
            best = json.load(open(bj, encoding="utf-8")) if os.path.exists(bj) else None
            if best is None or score < best["score"]:
                bd = os.path.join(HERE, "runs", self.name + "_best")
                os.makedirs(bd, exist_ok=True)
                self.model.save(os.path.join(bd, "model"))
                json.dump({"score": round(score, 1), "tag": tag}, open(bj, "w", encoding="utf-8"), ensure_ascii=False)
                print("[最佳] 新紀錄 %s,分數 %.1f(越小越好),已存到 runs\%s_best" % (tag, score, self.name), flush=True)
        except Exception as ex:
            print("[最佳] 判斷失敗:%s" % ex, flush=True)

    def _on_training_start(self):
        self.t_last = time.time()
        self._snapshot()                             # 起點(還沒練之前)也錄一段,方便比較

    def _on_rollout_end(self):
        if time.time() - self.t_last >= self.every:
            self.t_last = time.time()
            self._snapshot()

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
        if SRC != NAME:
            model.policy.log_std.data.clamp_(min=-2.2)
            print("接著 %s 的經驗繼續練(神經網路參數沿用,獎勵用新的)" % SRC, flush=True)
        else:
            # 續練同一個模型(例如電腦或程式重開後接著練):不要再把雜訊放大,不然每重開一次就倒退一小段
            print("續練 %s(探索雜訊維持原本學到的大小)" % SRC, flush=True)
    else:
        model = PPO("MlpPolicy", env, n_steps=512, batch_size=1280, learning_rate=3e-4, gamma=0.98,
                    policy_kwargs=dict(net_arch=[128, 128], log_std_init=-1.5), device="cpu", verbose=0, seed=0)
        print("從零開始練", flush=True)
    callbacks = [Log(out)] + ([ClipCallback(NAME, CLIP_MIN)] if FOREVER else [])
    model.learn(total_timesteps=TOTAL, callback=callbacks)
    model.save(os.path.join(out, "model"))
    print("完成,模型存在", out)
