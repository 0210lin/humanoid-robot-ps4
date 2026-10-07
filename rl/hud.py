"""
訓練畫面上的數字:機器人走了多遠、速度、轉向、腳離地多高。watch.py(視窗)和 record.py(GIF)共用。
座標:正面 = 模型的 -Z,右手邊 = +X。位移從這一回合開始算。
"""
import numpy as np


class Progress:
    def __init__(self, env):
        self.env = env
        self.reset()

    def reset(self):
        d = self.env.data
        self.z0, self.x0 = float(d.qpos[2]), float(d.qpos[0])
        self.steps = 0
        self.info = {}
        self.peak_r = 0.0
        self.peak_l = 0.0
        f = -d.xmat[1].reshape(3, 3)[:, 2]
        self.yaw0 = float(np.degrees(np.arctan2(f[0], -f[2])))

    def update(self, info):
        self.steps += 1
        if info and "clr_r" in info:
            self.peak_r = max(self.peak_r, info["clr_r"])
            self.peak_l = max(self.peak_l, info["clr_l"])
        self.info = info or {}

    def values(self):
        d = self.env.data
        f = -d.xmat[1].reshape(3, 3)[:, 2]
        yaw = float(np.degrees(np.arctan2(f[0], -f[2]))) - self.yaw0
        return dict(
            t=self.steps * 0.04,
            fwd=-(float(d.qpos[2]) - self.z0) * 100,        # 往正面走了多少 cm
            side=(float(d.qpos[0]) - self.x0) * 100,         # 往右偏了多少 cm
            speed=-float(d.qvel[2]) * 100,                   # cm/s
            yaw=yaw,
            clr_r=max(0.0, self.info.get("clr_r", 0.0)) * 1000 if getattr(self, "info", None) else None,
            clr_l=max(0.0, self.info.get("clr_l", 0.0)) * 1000 if getattr(self, "info", None) else None,
            peak_r=self.peak_r * 1000, peak_l=self.peak_l * 1000)

    def english(self):
        """給 MuJoCo 視窗用(內建字型沒有中文):回傳 (左欄標籤, 右欄數字)"""
        v = self.values()
        labels = ["Time", "Forward", "Side (right +)", "Speed", "Yaw"]
        vals = ["%.1f s" % v["t"], "%+.0f cm" % v["fwd"], "%+.0f cm" % v["side"], "%+.1f cm/s" % v["speed"], "%+.0f deg" % v["yaw"]]
        if v["clr_r"] is not None:
            labels += ["Foot lift now R/L", "Foot lift max R/L"]
            vals += ["%.0f / %.0f mm" % (v["clr_r"], v["clr_l"]), "%.0f / %.0f mm  (rule: >= 10)" % (v["peak_r"], v["peak_l"])]
        return "\n".join(labels), "\n".join(vals)

    def chinese(self):
        """給 GIF 用:回傳一行一行的文字"""
        v = self.values()
        lines = ["%.1f 秒   前進 %+.0f cm   側向 %+.0f cm   速度 %+.1f cm/s   轉向 %+.0f°" % (v["t"], v["fwd"], v["side"], v["speed"], v["yaw"])]
        if v["clr_r"] is not None:
            lines.append("腳離地(目前)右 %.0f / 左 %.0f mm   最高 右 %.0f / 左 %.0f mm(規則:至少 10)" % (v["clr_r"], v["clr_l"], v["peak_r"], v["peak_l"]))
        return lines
