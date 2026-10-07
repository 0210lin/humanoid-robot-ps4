"""從 MotorAdj 的 config 檔讀某一幀(預設 light1.2.config 的 1 號 = 站姿),存成 stand_pose.json({馬達號: 位置})。
用 config 裡的數字(不含 Offset):Offset 是補真機機械誤差的零位微調,理想機器人不需要。換動作檔後重跑。
用法:python stand_pose.py [幀號=1] [config 檔=light1.2.config]"""
import json, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
FRAME = int(sys.argv[1]) if len(sys.argv) > 1 else 1
CFG = sys.argv[2] if len(sys.argv) > 2 else "light1.2.config"
d = json.load(open(os.path.join(os.path.dirname(HERE), "Tools_MotorAdj", CFG), encoding="utf-8"))
pos = {str(i + 1): int(m[1]) for i, m in enumerate(d["MotorPos"][FRAME])}
json.dump(pos, open(os.path.join(HERE, "stand_pose.json"), "w"))
print("%s 第 %d 號動作:" % (CFG, FRAME), {k: v for k, v in pos.items() if int(k) <= 15})
