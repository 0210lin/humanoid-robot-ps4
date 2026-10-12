"""
多技能的「動作幀」搜尋:直走、後退、右/左側移、右/左轉身。
每個技能 = N 個動作幀(預設 4 幀,每幀 200 ms)重複播放,開環(沒有任何回饋,和真機照幀播放一樣),
在「隨機化」的模擬裡(摩擦、重量、伺服力量、延遲都亂動)用演化策略直接搜尋每顆馬達的位置。
這些幀可以變成 MotorAdj 的動作幀 + custom.ino 的 SetFrameRun 迴圈,Micro 就跑得動。

座標:正面 = 模型的 -Z、右手邊 = +X;yaw 正 = 往右轉。
"""
import json
import os

import numpy as np

import export_gait as xg
from robot_env import make_env

HERE = os.path.dirname(os.path.abspath(__file__))
LIMIT = 0.6                      # 每顆馬達離站姿最多 ±0.6 弧度(約 34°)
N_FRAMES = 4
T_MS = 200                       # 每幀 200 ms,一圈 0.8 秒
CRITERIA = 3                     # 評分 / 達標標準的版本號:改了標準就加 1,舊的最佳紀錄會用新標準重新評

ORDER = ["forward", "backward", "side_r", "side_l", "turn_r", "turn_l"]
LABEL = {"forward": "直走", "backward": "後退", "side_r": "右側移", "side_l": "左側移", "turn_r": "右轉身", "turn_l": "左轉身"}
NEED_LIFT = {"forward": True, "backward": True, "side_r": False, "side_l": False, "turn_r": False, "turn_l": False}
# 每個技能的 4 個動作幀放在 MotorAdj 的哪幾號(你現在用到 0~32,所以從 33 開始;每技能 4 幀)
FIRST_FRAME = {"forward": 33, "backward": 37, "side_r": 41, "side_l": 45, "turn_r": 49, "turn_l": 53}

# 達標標準(20 秒的測試):我自己訂的,可以改
GOAL = {
    "forward": "20 秒前進 ≥ 100 cm、橫向偏離 ≤ 25 cm、轉向 ≤ 10°、腳最高離地 ≥ 10 mm、沒倒",
    "backward": "20 秒後退 ≥ 100 cm、橫向偏離 ≤ 25 cm、轉向 ≤ 10°、腳最高離地 ≥ 10 mm、沒倒",
    "side_r": "20 秒往右橫移 ≥ 100 cm、前後偏離 ≤ 25 cm、轉向 ≤ 10°、腳最高 ≤ 60 mm、沒倒(允許滑行)",
    "side_l": "20 秒往左橫移 ≥ 100 cm、前後偏離 ≤ 25 cm、轉向 ≤ 10°、腳最高 ≤ 60 mm、沒倒(允許滑行)",
    "turn_r": "20 秒往右轉 ≥ 180°、位置偏移 ≤ 25 cm、腳最高 ≤ 60 mm、沒倒",
    "turn_l": "20 秒往左轉 ≥ 180°、位置偏移 ≤ 25 cm、腳最高 ≤ 60 mm、沒倒",
}


def fitness(skill, res):
    """越大越好。倒下重扣;其餘依技能:要的方向給分,不要的方向(偏離、亂轉)扣分"""
    if res["fell"]:
        return -100.0 + res["seconds_survived"] * 5.0
    f, lat, yaw = res["forward_cm"], res["lateral_cm"], res["yaw_deg"]
    pk, ev, slip = res["peak_lift_mm"], res["lift_events_per_20s"], res.get("slip_cm_s", 0.0)
    if skill in ("forward", "backward"):
        main = min(f if skill == "forward" else -f, 250.0)           # 速度設上限(20 秒 250 cm):不要為了追速度練出不真實的跑法
        sc = main - 2.5 * abs(lat) - 1.2 * abs(yaw)
        sc += 30.0 * min(1.0, pk / 10.0) + 1.0 * min(ev, 20.0)      # 規則:抬腳要整塊離地 ≥ 1 公分
        sc -= 0.5 * max(0.0, pk - 40.0)                              # 不要亂甩腿
        sc -= 4.0 * max(0.0, slip - 1.5)                             # 貼地的腳滑行小扣
    elif skill in ("side_r", "side_l"):
        main = min(lat if skill == "side_r" else -lat, 250.0)        # 速度上限:模擬裡橫移 800 多公分(約 40 cm/s)是滑行 / 甩腿的漏洞,真機做不到
        sc = main - 1.5 * abs(f) - 1.0 * abs(yaw)
        sc -= 1.0 * max(0.0, pk - 50.0)                              # 腳抬超過 5 公分就重扣(不要靠大幅甩腿)
    else:  # 轉身
        main = min(yaw if skill == "turn_r" else -yaw, 360.0)
        sc = main - 1.0 * (abs(f) + abs(lat))
        sc -= 1.0 * max(0.0, pk - 50.0)
    return sc


def success(skill, res):
    if res["fell"]:
        return False
    f, lat, yaw, pk = res["forward_cm"], res["lateral_cm"], res["yaw_deg"], res["peak_lift_mm"]
    if skill == "forward":
        return f >= 100 and abs(lat) <= 25 and abs(yaw) <= 10 and pk >= 10
    if skill == "backward":
        return -f >= 100 and abs(lat) <= 25 and abs(yaw) <= 10 and pk >= 10
    if skill == "side_r":
        return lat >= 100 and abs(f) <= 25 and abs(yaw) <= 10 and pk <= 60
    if skill == "side_l":
        return -lat >= 100 and abs(f) <= 25 and abs(yaw) <= 10 and pk <= 60
    if skill == "turn_r":
        return yaw >= 180 and abs(f) <= 25 and abs(lat) <= 25 and pk <= 60
    return -yaw >= 180 and abs(f) <= 25 and abs(lat) <= 25 and pk <= 60


# ---------- 搜尋用的工作程序 ----------
_env = None


def init_worker(env_name):
    global _env
    _env = make_env(env_name, seed=0, randomize=True, push=False)


def eval_x(args):
    skill, x, n, T_ms, seconds, seed = args
    _env.rng = np.random.default_rng(seed)        # 同一代的候選用同一組隨機,比較才公平
    kf = _env.ctrl0 + x.reshape(n, -1)
    res = xg.play_open_loop(_env, kf, n, seconds=seconds, T=T_ms / 1000.0, ramp=T_ms / 1000.0)
    return fitness(skill, res)


def rank_shape(f):
    r = np.empty(len(f))
    r[np.argsort(f)] = np.arange(len(f))
    return r / (len(f) - 1) - 0.5


class ES:
    """OpenAI-ES(對稱取樣 + Adam),一個技能一個物件,可以一段一段繼續練"""

    def __init__(self, dim, theta0, sigma=0.04, lr=0.02, seed=0):
        self.theta = np.clip(theta0.copy(), -LIMIT, LIMIT)
        self.sigma, self.lr = sigma, lr
        self.m, self.v, self.t = np.zeros(dim), np.zeros(dim), 0
        self.rng = np.random.default_rng(seed)
        self.gen = 0

    def set_sigma(self, sigma):
        """搜尋步伐(越小越精細)。學習率跟著步伐走"""
        self.sigma = float(sigma)
        self.lr = self.sigma * 0.5

    def reset_to(self, theta):
        """退回某組參數,清掉動量"""
        self.theta = np.clip(theta.copy(), -LIMIT, LIMIT)
        self.m[:] = 0
        self.v[:] = 0
        self.t = 0

    def step(self, pool, skill, n, T_ms, seconds, pop=48):
        half = pop // 2
        D = self.theta.size
        eps = self.rng.standard_normal((half, D))
        cands = np.clip(np.concatenate([self.theta + self.sigma * eps, self.theta - self.sigma * eps, self.theta[None, :]]), -LIMIT, LIMIT)
        seed = 100000 + self.gen
        fits = np.array(pool.map(eval_x, [(skill, c, n, T_ms, seconds, seed) for c in cands], chunksize=2))
        shaped = rank_shape(fits[:2 * half])
        grad = ((shaped[:half] - shaped[half:])[:, None] * eps).sum(0) / (half * self.sigma)
        self.t += 1
        self.m = 0.9 * self.m + 0.1 * grad
        self.v = 0.999 * self.v + 0.001 * grad ** 2
        mh, vh = self.m / (1 - 0.9 ** self.t), self.v / (1 - 0.999 ** self.t)
        self.theta = np.clip(self.theta + self.lr * mh / (np.sqrt(vh) + 1e-8), -LIMIT, LIMIT)
        self.gen += 1
        return float(fits[-1]), float(fits[:2 * half].max())


def robust_eval(env_rand, skill, theta, n=N_FRAMES, T_ms=T_MS, k=12, seconds=20.0, seed=321):
    """在隨機化的模擬裡各跑 k 次 seconds 秒,回傳達標比例和平均數字。
    seed:每次評分都用同一組隨機環境(新舊參數公平比較)。以前每次抽新的,成績起伏很大,
    運氣好的那一次會變成「最好成績」,之後怎麼練都贏不了它,搜尋就卡住。seed=None 才是每次抽新的。"""
    if seed is not None:
        env_rand.rng = np.random.default_rng(seed)
    kf = env_rand.ctrl0 + theta.reshape(n, -1)
    rs = [xg.play_open_loop(env_rand, kf, n, seconds=seconds, T=T_ms / 1000.0, ramp=T_ms / 1000.0) for _ in range(k)]
    ok = sum(1 for r in rs if success(skill, r))
    mean = lambda key: round(float(np.mean([r[key] for r in rs])), 1)
    return dict(success_pct=round(100.0 * ok / k, 1), fitness=round(float(np.mean([fitness(skill, r) for r in rs])), 1),
                falls=int(sum(r["fell"] for r in rs)), forward_cm=mean("forward_cm"), lateral_cm=mean("lateral_cm"),
                yaw_deg=mean("yaw_deg"), peak_lift_mm=mean("peak_lift_mm"), slip_cm_s=mean("slip_cm_s"), episodes=k, criteria=CRITERIA)


def score(r):
    """挑「最好」用的分數:以 k 場平均分數為主(已經包含倒下的重扣),達標比例加一點分(每 1% 加 0.5 分)。
    以前是「達標比例優先」:早期有一場剛好達標(6%)就會鎖住,之後平均分數再高的參數也比不過它。"""
    return r["fitness"] + 0.5 * r["success_pct"]


def better(a, b):
    """a 比 b 好?"""
    if b is None:
        return True
    return score(a) > score(b)
