import os, shutil, re
# 1) 套用 patch4
exec(open("patch4.py", encoding="utf-8").read())
# 2) layout.py -> layout_A.py;build/autoroute 改成依環境變數 SERVO_VARIANT 選版本、輸出檔名帶版本
if os.path.exists("layout.py") and not os.path.exists("layout_A.py"):
    shutil.copy("layout.py", "layout_A.py")
s = open("build.py", encoding="utf-8").read()
s = s.replace("import layout as L", "V = os.environ.get('SERVO_VARIANT', 'A')\nimport importlib\nL = importlib.import_module('layout_' + V)")
s = s.replace('os.path.join(HERE, "pads.json")', 'os.path.join(HERE, "pads_%s.json" % V)')
s = s.replace('rj = os.path.join(HERE, "routes.json")', 'rj = os.path.join(HERE, "routes_%s.json" % V)')
s = s.replace('out = os.path.join(HERE, "ServoID_Module.kicad_pcb")', 'out = os.path.join(HERE, "ServoID_%s.kicad_pcb" % V)')
open("build.py", "w", encoding="utf-8").write(s)
a = open("autoroute.py", encoding="utf-8").read()
a = a.replace("import layout as L", "V = os.environ.get('SERVO_VARIANT', 'A')\nimport importlib\nL = importlib.import_module('layout_' + V)")
a = a.replace('os.path.join(HERE, "pads.json")', 'os.path.join(HERE, "pads_%s.json" % V)')
a = a.replace('os.path.join(HERE, "routes.json")', 'os.path.join(HERE, "routes_%s.json" % V)')
open("autoroute.py", "w", encoding="utf-8").write(a)
print("ok", V if 'V' in dir() else "")
