"""
產生 runs\<名稱>\clips\index.html:列出每 30 分鐘錄的 10 秒影片和當時量到的數字,最新的在最上面。
瀏覽器打開它就好,每 2 分鐘自己重新整理。
"""
import csv
import html
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def build(name):
    d = os.path.join(HERE, "runs", name, "clips")
    os.makedirs(d, exist_ok=True)
    rows = {}
    p = os.path.join(d, "metrics.csv")
    if os.path.exists(p):
        with open(p, encoding="utf-8") as f:
            for r in csv.DictReader(f):
                rows[r.get("tag", "")] = r
    best_tag = ""
    bp = os.path.join(HERE, "runs", name, "best.json")
    if os.path.exists(bp):
        import json
        best_tag = json.load(open(bp, encoding="utf-8")).get("tag", "")
    gifs = sorted([f for f in os.listdir(d) if f.startswith("clip_") and f.endswith(".gif")], reverse=True)
    out = ["<!doctype html><html lang='zh-Hant'><head><meta charset='utf-8'><meta http-equiv='refresh' content='120'>",
           "<meta name='viewport' content='width=device-width,initial-scale=1'><title>直走訓練 - 每 30 分鐘的影片</title>",
           "<style>body{font-family:'Microsoft JhengHei',system-ui,sans-serif;background:#0d1117;color:#e6edf3;max-width:760px;margin:auto;padding:16px}"
           ".c{background:#161b22;border:1px solid #30363d;border-radius:12px;padding:12px;margin:14px 0}img{max-width:100%;border-radius:8px}"
           ".ok{color:#3fb950;font-weight:700}.no{color:#f85149;font-weight:700}table{border-collapse:collapse;width:100%}td,th{border-bottom:1px solid #30363d;padding:4px 6px;text-align:right}th:first-child,td:first-child{text-align:left}</style></head><body>",
           "<h1>直走訓練:每 30 分鐘一段 10 秒影片</h1>",
           "<p>目標:<b>走直線</b>(20 秒橫向偏離 ≤ 25 cm、轉向 ≤ 10°)而且<b>抬腳</b>(腳最高離地 ≥ 10 mm、20 秒內至少抬 8 次)。畫面左上角的數字是即時的。</p>"]
    if not gifs:
        out.append("<p>還沒有影片(訓練開始後會先錄一段當作起點)。</p>")
    for g in gifs:
        tag = g[:-4]
        r = rows.get(tag)
        star = " &nbsp;⭐ 目前最佳(模型存在 runs\%s_best)" % html.escape(name) if tag == best_tag else ""
        out.append("<div class='c'><h3>%s%s</h3><img src='%s'>" % (html.escape(tag.replace("clip_", "")), star, html.escape(g)))
        if r:
            sd = "<span class='ok'>走直 OK</span>" if r.get("straight_ok") == "True" else "<span class='no'>走直 未達標</span>"
            lf = "<span class='ok'>抬腳 OK</span>" if r.get("lift_ok") == "True" else "<span class='no'>抬腳 未達標</span>"
            out.append("<p>%s &nbsp; %s</p>" % (sd, lf))
            out.append("<table><tr><td>前進(20 秒)</td><td>%s cm</td></tr><tr><td>橫向偏離(平均)</td><td>%s cm</td></tr>"
                       "<tr><td>身體轉向(平均)</td><td>%s°</td></tr><tr><td>腳最高離地(平均)</td><td>%s mm</td></tr>"
                       "<tr><td>有腳離地 ≥ 10 mm 的時間</td><td>%s %%</td></tr><tr><td>20 秒內抬腳次數</td><td>%s</td></tr>"
                       "<tr><td>倒下次數(%s 次測試)</td><td>%s</td></tr></table>" % (
                           r.get("forward_cm"), r.get("lateral_cm"), r.get("yaw_deg"), r.get("peak_lift_mm"),
                           r.get("lift_time_pct"), r.get("steps_per_20s"), r.get("episodes"), r.get("falls")))
        out.append("</div>")
    out.append("</body></html>")
    with open(os.path.join(d, "index.html"), "w", encoding="utf-8") as f:
        f.write("\n".join(out))
    return os.path.join(d, "index.html")


if __name__ == "__main__":
    print(build(sys.argv[1] if len(sys.argv) > 1 else "straight"))
