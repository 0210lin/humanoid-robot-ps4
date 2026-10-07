"""
機器人站立訓練環境(gymnasium)。
  動作:15 顆馬達相對『站姿(動作檔第 0 號)』的偏移(-1~1 對應 ±ACT_SCALE 弧度),每個控制步 40 ms(25 Hz,和真機 USB 控制速率一樣)
  觀察:15 個關節角、15 個關節速度、骨盆的「重力方向」(3)、骨盆角速度(3)、上一步動作(15)
  獎勵:站得直、高度維持、動作平滑、力矩小;隨機推力;每回合隨機化摩擦、質量、伺服力量、延遲
"""
import json
import os

import gymnasium as gym
import mujoco
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ACT_SCALE = 0.3           # 動作 ±1 = 零位 ±0.3 弧度(約 17°)
SUBSTEPS = 20             # 20 × 2 ms = 40 ms
MAX_STEPS = 500           # 20 秒


class StandEnv(gym.Env):
    metadata = {"render_modes": []}

    def __init__(self, randomize=True, push=True, xml="robot.xml", seed=None):
        spec = mujoco.MjSpec.from_file(os.path.join(HERE, xml))
        for nm, az, el in [("view", 145, 12), ("front", 180, 5), ("side", 90, 5), ("back", 0, 5)]:   # Y 軸朝上的相機(模型的「上」是 Y,不是 MuJoCo 預設的 Z)
            a, e = np.radians(az), np.radians(el)
            look = np.array([0.0, 0.0, 0.0])
            z = np.array([np.sin(a) * np.cos(e), np.sin(e), np.cos(a) * np.cos(e)])
            x = np.cross([0, 1, 0], z); x /= np.linalg.norm(x)
            y = np.cross(z, x)
            spec.worldbody.add_camera(name=nm, pos=(look + 0.6 * z).tolist(), xyaxes=np.concatenate([x, y]).tolist()).mode = mujoco.mjtCamLight.mjCAMLIGHT_TRACKCOM
        self.model = spec.compile()
        self.data = mujoco.MjData(self.model)
        meta = json.load(open(os.path.join(HERE, "robot_meta.json"), encoding="utf-8"))
        self.nu = self.model.nu
        self.randomize, self.push = randomize, push
        self.rng = np.random.default_rng(seed)
        # 一切都照 MuJoCo 的馬達順序(a_m15, a_m01, ...);meta 檔的順序不一樣,不能直接混用
        by_motor = {a["motor"]: a for a in meta["actuators"]}
        self.motors = [int(mujoco.mj_id2name(self.model, mujoco.mjtObj.mjOBJ_ACTUATOR, i)[3:]) for i in range(self.model.nu)]
        acts = [by_motor[m] for m in self.motors]
        self.jadr = [self.model.joint(a["joint"]).qposadr[0] for a in acts]
        self.vadr = [self.model.joint(a["joint"]).dofadr[0] for a in acts]
        # 動作 → ctrl:我們直接用「關節角(弧度)」,zero 姿勢的 ctrl = 0
        self.lo = self.model.actuator_ctrlrange[:, 0].copy()
        self.hi = self.model.actuator_ctrlrange[:, 1].copy()
        self.base = dict(
            mass=self.model.body_mass.copy(), inertia=self.model.body_inertia.copy(),
            gain=self.model.actuator_gainprm[:, 0].copy(), bias=self.model.actuator_biasprm[:, 1].copy(),
            fmax=self.model.actuator_forcerange.copy(), fric=self.model.geom_friction.copy())
        pose = json.load(open(os.path.join(HERE, "stand_pose.json")))      # 你的動作檔第 0 號 = 站姿
        self.ctrl0 = np.clip(np.array([a["factor"] * (pose[str(a["motor"])] - a["zero"]) for a in acts]), self.lo, self.hi)
        self.pelvis_h0 = None
        n_obs = 15 * 3 + 3 + 3
        self.observation_space = gym.spaces.Box(-np.inf, np.inf, (n_obs,), np.float32)
        self.action_space = gym.spaces.Box(-1, 1, (self.nu,), np.float32)
        self.prev_act = np.zeros(self.nu)
        self.delay_buf = np.zeros(self.nu)
        self.t = 0

    # ---- 每回合隨機化(模擬真機和模擬的差距)
    def _randomize(self):
        m, b, r = self.model, self.base, self.rng
        s = r.uniform(0.85, 1.15)                         # 整體質量 ±15%
        m.body_mass[:] = b["mass"] * s
        m.body_inertia[:] = b["inertia"] * s
        g = r.uniform(0.7, 1.2, self.nu)                  # 每顆伺服的力量/增益不同(電池電量、個體差)
        m.actuator_gainprm[:, 0] = b["gain"] * g
        m.actuator_biasprm[:, 1] = b["bias"] * g
        m.actuator_forcerange[:] = b["fmax"] * g[:, None]
        m.geom_friction[:] = b["fric"]
        m.geom_friction[:, 0] = b["fric"][:, 0] * r.uniform(0.6, 1.2)
        self.delay = int(r.integers(0, 2))                # 0~1 步(0~40 ms)的指令延遲

    def _obs(self):
        d = self.data
        R = d.xmat[1].reshape(3, 3)
        grav_local = R.T @ np.array([0.0, -1.0, 0.0])     # 重力在骨盆座標的方向;站直時 = (0,-1,0)
        q = d.qpos[self.jadr]
        qd = d.qvel[self.vadr]
        gyro = d.qvel[3:6]
        o = np.concatenate([q, qd * 0.1, grav_local, gyro * 0.3, self.prev_act])
        return o.astype(np.float32)

    def reset(self, seed=None, options=None):
        if seed is not None:
            self.rng = np.random.default_rng(seed)
        mujoco.mj_resetData(self.model, self.data)
        self.delay = 0
        if self.randomize:
            self._randomize()
        mujoco.mj_forward(self.model, self.data)
        self.prev_act[:] = 0
        self.delay_buf[:] = 0
        self.t = 0
        self.next_push = int(self.rng.integers(25, 75))
        self.push_left = 0
        self.push_force = np.zeros(3)
        for k in range(600):                              # 控制量慢慢推到站姿(避免連桿跳到另一解),共 1.2 秒
            self.data.ctrl[:] = self.ctrl0 * min(1.0, k / 300)
            mujoco.mj_step(self.model, self.data)
        if self.pelvis_h0 is None:
            self.pelvis_h0 = float(self.data.xpos[1][1])
        return self._obs(), {}

    def step(self, action):
        action = np.clip(action, -1, 1)
        cmd = self.delay_buf if self.delay else action
        if self.delay:
            self.delay_buf = action.copy()
        target = np.clip(self.ctrl0 + cmd * ACT_SCALE, self.lo, self.hi)
        self.data.ctrl[:] = target
        # 隨機推力(骨盆,水平方向,0.2 秒)
        if self.push:
            if self.t == self.next_push:
                a = self.rng.uniform(0, 2 * np.pi)
                f = self.rng.uniform(2.0, 10.0)           # 牛頓;零動作大約扛 4 N、8 N 就倒
                self.push_force = np.array([f * np.cos(a), 0.0, f * np.sin(a)])
                self.push_left = 5
                self.next_push = self.t + int(self.rng.integers(25, 75))
            self.data.xfrc_applied[1, :3] = self.push_force if self.push_left > 0 else 0
            self.push_left = max(0, self.push_left - 1)
        for _ in range(SUBSTEPS):
            mujoco.mj_step(self.model, self.data)
        self.t += 1

        d = self.data
        up = d.xmat[1].reshape(3, 3)[:, 1][1]             # 骨盆「上」的 y 分量,1 = 站直
        h = d.xpos[1][1] - self.pelvis_h0
        tau = np.abs(d.actuator_force)
        da = action - self.prev_act
        drift = np.hypot(d.qpos[0], d.qpos[2])            # 離開原點的水平距離
        r = (1.0 + 2.0 * (up - 1.0) + 5.0 * h
             - 0.05 * float(da @ da) - 0.02 * float(np.mean(tau)) - 0.05 * float(np.sum(d.qvel[3:6] ** 2)) - 0.1 * drift)
        fell = up < 0.7 or h < -0.04
        self.prev_act = action.astype(np.float64)
        terminated = bool(fell)
        if fell:
            r -= 5.0
        truncated = self.t >= MAX_STEPS
        return self._obs(), float(r), terminated, truncated, {}


class WalkEnv(StandEnv):
    """走路:往正面(模型的 -Z 方向)走。多一個步態節拍(週期 0.8 秒)當輸入,
    獎勵 = 前進速度接近目標 + 該抬的腳有抬起來(右腳 / 左腳輪流)+ 站直 + 動作平順。"""
    PERIOD = 20            # 20 步 × 40 ms = 0.8 秒一個完整步伐
    V_TARGET = 0.12        # 目標前進速度 m/s
    CLEAR = 0.015          # 目標抬腳高度 15 mm

    def __init__(self, randomize=True, push=False, **kw):
        super().__init__(randomize=randomize, push=push, **kw)
        self.observation_space = gym.spaces.Box(-np.inf, np.inf, (15 * 3 + 3 + 3 + 2,), np.float32)
        names = {mujoco.mj_id2name(self.model, mujoco.mjtObj.mjOBJ_GEOM, g): g for g in range(self.model.ngeom)}
        self.foot_r, self.foot_l = names["foot_b15"], names["foot_b23"]     # b15 = 右腳掌,b23 = 左腳掌
        self.act_scale = 0.5

    def _obs(self):
        ph = 2 * np.pi * (self.t % self.PERIOD) / self.PERIOD
        return np.concatenate([super()._obs(), [np.sin(ph), np.cos(ph)]]).astype(np.float32)

    def reset(self, seed=None, options=None):
        super().reset(seed=seed, options=options)
        d = self.data
        self.foot_y0 = (d.geom_xpos[self.foot_r][1], d.geom_xpos[self.foot_l][1])
        return self._obs(), {}

    def step(self, action):
        action = np.clip(action, -1, 1)
        cmd = self.delay_buf if self.delay else action
        if self.delay:
            self.delay_buf = action.copy()
        self.data.ctrl[:] = np.clip(self.ctrl0 + cmd * self.act_scale, self.lo, self.hi)
        if self.push:
            if self.t == self.next_push:
                a = self.rng.uniform(0, 2 * np.pi); f = self.rng.uniform(1.0, 4.0)
                self.push_force = np.array([f * np.cos(a), 0.0, f * np.sin(a)])
                self.push_left = 5
                self.next_push = self.t + int(self.rng.integers(25, 75))
            self.data.xfrc_applied[1, :3] = self.push_force if self.push_left > 0 else 0
            self.push_left = max(0, self.push_left - 1)
        for _ in range(SUBSTEPS):
            mujoco.mj_step(self.model, self.data)
        self.t += 1

        d = self.data
        up = d.xmat[1].reshape(3, 3)[:, 1][1]
        h = d.xpos[1][1] - self.pelvis_h0
        tau = np.abs(d.actuator_force)
        da = action - self.prev_act
        vfwd = -d.qvel[2]                                      # 正面 = -Z
        vside = d.qvel[0]
        phase = (self.t % self.PERIOD) / self.PERIOD
        lift_r = np.clip(d.geom_xpos[self.foot_r][1] - self.foot_y0[0], 0, self.CLEAR) / self.CLEAR
        lift_l = np.clip(d.geom_xpos[self.foot_l][1] - self.foot_y0[1], 0, self.CLEAR) / self.CLEAR
        swing, stance = (lift_r, lift_l) if phase < 0.5 else (lift_l, lift_r)    # 前半拍抬右腳,後半拍抬左腳
        r = (2.0 * np.exp(-((vfwd - self.V_TARGET) / 0.08) ** 2)
             + 1.0 * swing - 1.0 * stance
             + 0.5 + 2.0 * (up - 1.0) + 5.0 * h
             - 0.05 * float(da @ da) - 0.02 * float(np.mean(tau)) - 0.1 * float(np.sum(d.qvel[3:6] ** 2)) - 1.0 * abs(vside))
        fell = up < 0.7 or h < -0.04
        self.prev_act = action.astype(np.float64)
        if fell:
            r -= 5.0
        return self._obs(), float(r), bool(fell), self.t >= MAX_STEPS, {}


def make_env(name, **kw):
    return WalkEnv(**kw) if name.startswith("walk") else StandEnv(**kw)
