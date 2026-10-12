# 一次產生多個版本:放零件 -> 自動佈線 -> 組成 PCB -> DRC -> 3D 圖;最後印出比較表
# 用法:"C:\Program Files\KiCad\10.0\bin\python.exe" run_variants.py A B D
import os, sys, subprocess, json, re, math
HERE = os.path.dirname(os.path.abspath(__file__))
K = r"C:\Program Files\KiCad\10.0\bin"
PY = os.path.join(K, "python.exe")
CLI = os.path.join(K, "kicad-cli.exe")

# 每個版本的佈線參數(環境變數)
PARAMS = {"D": {"SERVO_CLR": "0.3", "SERVO_MINW": "0.3"}, "C": {"SERVO_TURN": "1.5"}}


def run(v, *args, extra=None):
    env = dict(os.environ, SERVO_VARIANT=v, PYTHONIOENCODING="utf-8")
    env.update(PARAMS.get(v, {}))
    if extra:
        env.update(extra)
    return subprocess.run([PY] + list(args), cwd=HERE, env=env, capture_output=True, text=True)


rows = []
for v in sys.argv[1:]:
    for f in ("routes_%s.json" % v,):
        p = os.path.join(HERE, f)
        if os.path.exists(p):
            os.remove(p)
    r1 = run(v, "build.py", "--dump", "--noroute")
    r2 = run(v, "autoroute.py")
    failed = re.findall(r"failed (\[.*\])", r2.stdout)
    r3 = run(v, "build.py")
    pcb = os.path.join(HERE, "ServoID_%s.kicad_pcb" % v)
    rpt = os.path.join(HERE, "drc_%s.rpt" % v)
    d = subprocess.run([CLI, "pcb", "drc", "--output", rpt, "--format", "report", "--severity-all", "--units", "mm", pcb], capture_output=True, text=True)
    txt = open(rpt, encoding="utf-8").read()
    kinds = {}
    for m in re.findall(r"^\[(\w+)\]", txt, re.M):
        kinds[m] = kinds.get(m, 0) + 1
    unconnected = re.findall(r"發現 (\d+) 個未連接", d.stdout + d.stderr)
    subprocess.run([CLI, "pcb", "render", "--side", "top", "--width", "1000", "--height", "1500", "--quality", "basic", "--background", "opaque", "-o", os.path.join(HERE, "render_%s_top.png" % v), pcb], capture_output=True)
    subprocess.run([CLI, "pcb", "render", "--side", "bottom", "--width", "1000", "--height", "1500", "--quality", "basic", "--background", "opaque", "-o", os.path.join(HERE, "render_%s_bottom.png" % v), pcb], capture_output=True)
    R = json.load(open(os.path.join(HERE, "routes_%s.json" % v)))
    length = {}
    bottom_sig = 0.0
    for t in R["tracks"]:
        l = math.hypot(t["x2"] - t["x1"], t["y2"] - t["y1"])
        length[t["net"]] = length.get(t["net"], 0) + l
        if t["layer"] == "B" and t["net"] != "GND":
            bottom_sig += l
    sys.path.insert(0, HERE)
    import importlib
    Lm = importlib.import_module("layout_" + v)
    rows.append(dict(v=v, size="%gx%g" % (Lm.BOARD_W, Lm.BOARD_H), area=round(Lm.BOARD_W * Lm.BOARD_H),
                     failed=failed[0] if failed else "?", unconnected=unconnected[0] if unconnected else "?",
                     drc=kinds, vias=len(R["vias"]), tracks=len(R["tracks"]),
                     sw=round(length.get("SW", 0), 1), v5=round(length.get("V5", 0), 1), vbat=round(length.get("VBAT", 0), 1),
                     total=round(sum(length.values()), 1), bottom_sig=round(bottom_sig, 1)))
for r in rows:
    print(json.dumps(r, ensure_ascii=False))
json.dump(rows, open(os.path.join(HERE, "variants_summary.json"), "w"), ensure_ascii=False, indent=1)
