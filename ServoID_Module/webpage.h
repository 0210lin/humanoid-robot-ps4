// 手機網頁(存在韌體裡)。連上模組的 WiFi 後,瀏覽器開 http://192.168.4.1
#pragma once
#include <pgmspace.h>

static const char PAGE[] PROGMEM = R"HTML(<!doctype html>
<html lang="zh-Hant"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
<meta name="theme-color" content="#1e3a8a">
<title>伺服馬達 ID 工具</title>
<style>
:root{--bg:#eef2f7;--fg:#101828;--sub:#5b667a;--card:#ffffff;--line:#e2e8f0;--acc:#2563eb;--acc2:#1d4ed8;--ok:#15803d;--bad:#dc2626;--warn:#b45309;--shadow:0 1px 3px rgba(16,24,40,.08),0 6px 16px rgba(16,24,40,.06)}
@media (prefers-color-scheme:dark){:root{--bg:#0b1220;--fg:#e6edf3;--sub:#93a1b5;--card:#131c2e;--line:#243049;--acc:#3b82f6;--acc2:#2563eb;--ok:#4ade80;--bad:#f87171;--warn:#fbbf24;--shadow:none}}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html{-webkit-text-size-adjust:100%;text-size-adjust:100%}
body{margin:0;background:var(--bg);color:var(--fg);font-family:-apple-system,system-ui,"PingFang TC","Noto Sans TC","Microsoft JhengHei",sans-serif;font-size:16px;line-height:1.5;touch-action:manipulation}
input,select,button,label,summary{touch-action:manipulation}
button,label.opt,summary{-webkit-user-select:none;user-select:none}
.wrap{max-width:520px;margin:0 auto;padding:0 14px 40px}
header{background:linear-gradient(135deg,#1e3a8a,#2563eb 60%,#38bdf8);color:#fff;padding:22px 18px 20px;margin:0 -14px 14px;border-radius:0 0 22px 22px;box-shadow:var(--shadow)}
header h1{margin:0;font-size:22px;letter-spacing:.5px}
header p{margin:4px 0 0;font-size:13px;opacity:.85}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:14px 16px;margin:12px 0;box-shadow:var(--shadow)}
.card h2{font-size:16px;margin:0 0 10px;display:flex;align-items:center;gap:8px}
.step{display:inline-flex;align-items:center;justify-content:center;width:24px;height:24px;border-radius:50%;background:var(--acc);color:#fff;font-size:13px;font-weight:700;flex:none}
button,select,input{font:inherit;font-size:16px}
select,input[type=number]{padding:10px 12px;border-radius:10px;border:1px solid var(--line);background:var(--bg);color:var(--fg)}
button{background:var(--acc);color:#fff;border:0;border-radius:12px;padding:12px 16px;font-weight:600;cursor:pointer;transition:background .15s,transform .05s}
button:active{transform:scale(.98);background:var(--acc2)}
button.sec{background:var(--bg);color:var(--fg);border:1px solid var(--line)}
button.danger{background:var(--bad)}
button:disabled{opacity:.4}
.big{font-size:20px;font-weight:700}
.ok{color:var(--ok)}.bad{color:var(--bad)}.warn{color:var(--warn)}
.row{display:flex;gap:10px;flex-wrap:wrap;align-items:center}
.row.nowrap{flex-wrap:nowrap}
.grow{flex:1}
.stat{min-height:64px}
.msg{min-height:26px;margin-top:6px}
.note{font-size:13px;color:var(--sub);margin-top:8px}
.opt{display:flex;align-items:center;gap:10px;padding:12px;border:1px solid var(--line);border-radius:12px;margin-top:8px;background:var(--bg)}
.opt input{width:20px;height:20px;accent-color:var(--acc);flex:none}
.pad{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:12px}
.pad button{padding:18px 0;font-size:22px}
.pad .wide{grid-column:1 / 3}
.result{min-height:32px;margin-top:10px;font-size:20px;font-weight:700}
#log{white-space:pre-wrap;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12.5px;height:150px;overflow:auto;background:var(--bg);border:1px solid var(--line);border-radius:10px;padding:8px}
summary{cursor:pointer;color:var(--sub);padding:6px 0}
</style></head><body>
<div class="wrap">
<header><h1>伺服馬達 ID 工具</h1><p>連上模組 WiFi 後使用 · 一次只接一顆馬達</p></header>

<div class="card">
  <h2><span class="step">1</span>電池與馬達電壓<span style="font-weight:400;color:var(--sub);font-size:13px">(先做這步,再插馬達)</span></h2>
  <div class="stat"><div id="bat" class="big">讀取中…</div><div id="batmsg" class="msg"></div></div>
  <label class="opt"><input type="radio" name="cls" value="74"><span>我要編的是 <b>7.4 V</b> 馬達(接 2S 電池)</span></label>
  <label class="opt"><input type="radio" name="cls" value="12"><span>我要編的是 <b>12 V</b> 馬達(接 3S 電池)</span></label>
  <div id="clsmsg" class="msg"></div>
</div>

<div class="card">
  <h2><span class="step">2</span>找馬達</h2>
  <button id="bscan" style="width:100%">掃描(找出馬達現在是幾號)</button>
  <div id="scanres" class="msg"></div>
</div>

<div class="card">
  <h2><span class="step">3</span>改成新的 ID</h2>
  <div class="row nowrap"><select id="newid" class="grow"></select><button id="bset" class="danger">改成這個號碼</button></div>
  <div class="note">匯流排上只能接一顆馬達。改完會自動讀回確認。</div>
</div>

<div class="card">
  <h2><span class="step">4</span>測試</h2>
  <div class="row"><span>馬達 ID</span><input id="tid" type="number" min="1" max="127" value="1" style="width:5.5em"></div>
  <div class="pad">
    <button id="bm100">−100</button>
    <button id="bp100">+100</button>
    <button id="bcenter" class="sec wide">1500 回中</button>
  </div>
  <div id="testres" class="result"></div>
  <div class="note">−100 / +100 可以一直連按,一路加到極限(800 ~ 2200)。馬達要空載。</div>
  <details style="margin-top:10px"><summary>來回掃(讓潤滑油平均,選用)</summary>
  <div class="row" style="margin-top:8px">
    <span>左端</span><input id="slo" type="number" min="800" max="2200" value="900" style="width:6em">
    <span>右端</span><input id="shi" type="number" min="800" max="2200" value="2100" style="width:6em">
  </div>
  <div class="row" style="margin-top:8px">
    <span>單程</span><input id="st" type="number" min="0.8" max="5" step="0.1" value="1.5" style="width:5em"><span>秒</span>
    <span>最長</span><input id="smin" type="number" min="0" max="600" value="0" style="width:5em"><span>分鐘(0 = 不限)</span>
  </div>
  <div class="row nowrap" style="margin-top:10px">
    <button id="bsweep" class="grow">開始來回掃</button>
    <button id="bsweepstop" class="danger grow">停止</button>
  </div>
  <div id="sweepres" class="msg"></div>
  <div class="note">會一直在左右兩端來回,直到按「停止」。發燙或有怪聲就按停止。</div>
  </details>
</div>

<div class="card"><h2>記錄</h2><div id="log"></div></div>
</div>

<script>
const $=id=>document.getElementById(id);
function setH(el,h){if(el._h!==h){el._h=h;el.innerHTML=h;}}   // 內容沒變就不重畫,畫面不會跳動
let busy=false;
function log(t){const l=$('log');l.textContent=new Date().toLocaleTimeString()+'  '+t+'\n'+l.textContent;}
async function api(p){
  try{const r=await fetch(p);return await r.json();}
  catch(e){return {ok:false,msg:'連不上模組:'+e};}
}
async function run(btn,fn){
  if(busy)return;busy=true;btn.disabled=true;
  try{await fn();}finally{busy=false;btn.disabled=false;}
}
for(let i=1;i<=32;i++){const o=document.createElement('option');o.value=i;o.textContent=i+' 號';$('newid').appendChild(o);}
document.querySelectorAll('input[name=cls]').forEach(r=>r.addEventListener('change',async()=>{
  const v=document.querySelector('input[name=cls]:checked').value;
  const j=await api('/api/class?c='+v);log('選擇 '+(v==='74'?'7.4 V':'12 V')+' 馬達');refresh();
}));
async function refresh(){
  if(busy)return;
  const s=await api('/api/state');
  if(s.vbat===undefined){setH($('bat'),'連不上模組');return;}
  const names={none:'沒接電池(只有 USB)',"2S":'2S 電池(約 7.4 V)',"3S":'3S 電池(約 11.1 V)',bad:'電壓不在 2S / 3S 範圍,請檢查'};
  setH($('bat'),'電池 '+s.vbat.toFixed(2)+' V → '+(names[s.cls]||s.cls));
  let m='',c='';
  if(s.cls==='none'){m='請先接上電池。';c='warn';}
  else if(s.cls==='bad'){m='電壓異常,先不要插馬達。';c='bad';}
  else if(s.low){m='電池電量偏低,請換電池或充電。';c='warn';}
  else {m='電池電壓正常。';c='ok';}
  setH($('batmsg'),'<span class="'+c+'">'+m+'</span>');
  if(s.sel==0){setH($('clsmsg'),'<span class="warn">請選擇你要編的馬達電壓。</span>');}
  else if(s.match){setH($('clsmsg'),'<span class="ok">電池和你選的馬達電壓相符,可以插馬達。</span>');}
  else{setH($('clsmsg'),'<span class="bad">電池和選的馬達不符!不要插馬達,已插的請馬上拔掉。</span>');}
  if(s.sel!=0){document.querySelector('input[name=cls][value="'+s.sel+'"]').checked=true;}
  const canMove=!!(s.match&&!s.low);
  $('bm100').disabled=!canMove;$('bp100').disabled=!canMove;$('bcenter').disabled=!canMove;$('bsweep').disabled=!canMove;
}
setInterval(refresh,2500);refresh();

$('bscan').onclick=()=>run($('bscan'),async()=>{
  setH($('scanres'),'掃描中…(約 1 秒)');
  const j=await api('/api/scan');
  if(!j.echo)log('警告:沒聽到自己送出的資料(回音),請檢查 RX 接線和分壓。');
  if(j.ids===undefined){setH($('scanres'),'<span class="bad">'+j.msg+'</span>');return;}
  if(j.ids.length===0){setH($('scanres'),'<span class="warn">沒找到馬達。馬達有接好、有通電嗎?</span>');log('掃描:沒有馬達');}
  else if(j.ids.length===1){setH($('scanres'),'<span class="ok big">找到 1 顆:'+j.ids[0]+' 號</span>');$('tid').value=j.ids[0];log('掃描:'+j.ids[0]+' 號');}
  else{setH($('scanres'),'<span class="bad">找到 '+j.ids.length+' 顆:'+j.ids.join('、')+'。要改 ID 請只接一顆。</span>');log('掃描:'+j.ids.join(','));}
});
$('bset').onclick=()=>run($('bset'),async()=>{
  const n=$('newid').value;
  if(!confirm('把馬達的 ID 改成 '+n+' 號?\n(匯流排上只能有這一顆馬達)'))return;
  log('改 ID → '+n+' …(約 3 秒)');
  const j=await api('/api/setid?new='+n);
  log(j.msg||JSON.stringify(j));
  setH($('scanres'),'<span class="'+(j.ok?'ok':'bad')+' big">'+(j.msg||'')+'</span>');
  if(j.ok)$('tid').value=n;
});
async function fastMove(path){
  try{
    const r=await fetch(path);const j=await r.json();
    setH($('testres'),'<span class="'+(j.ok?'ok':'bad')+'">'+(j.target!==undefined&&j.ok?'目標 '+j.target:j.msg)+'</span>');
    if(!j.ok||/極限/.test(j.msg||''))log(j.msg);
  }catch(e){setH($('testres'),'<span class="bad">連不上模組</span>');}
}
let sweepTimer=null;
async function sweepPoll(){
  const j=await api('/api/sweepstate');
  if(j.on===undefined)return;
  if(j.on){
    setH($('sweepres'),'<span class="ok big">來回掃中:已經 '+j.cycles+' 趟,'+j.sec+' 秒 ('+j.lo+' ↔ '+j.hi+')</span>');
  }else{
    setH($('sweepres'),'<span class="warn">'+(j.reason?('已停止:'+j.reason):'沒有在來回掃')+'(共 '+j.cycles+' 趟)</span>');
    if(sweepTimer){clearInterval(sweepTimer);sweepTimer=null;}
  }
}
function sweepWatch(){if(!sweepTimer){sweepTimer=setInterval(sweepPoll,1000);}sweepPoll();}
$('bsweep').onclick=async()=>{
  const q='/api/sweep?id='+$('tid').value+'&lo='+$('slo').value+'&hi='+$('shi').value+'&t='+$('st').value+'&min='+$('smin').value;
  const j=await api(q);
  log(j.msg);
  setH($('sweepres'),'<span class="'+(j.ok?'ok':'bad')+'">'+j.msg+'</span>');
  if(j.ok)sweepWatch();
};
$('bsweepstop').onclick=async()=>{
  const j=await api('/api/sweepstop');
  log(j.msg);
  setH($('sweepres'),'<span class="'+(j.ok?'ok':'bad')+'">'+j.msg+'</span>');
  setTimeout(sweepPoll,800);
};
sweepPoll();
$('bm100').onclick=()=>fastMove('/api/move?id='+$('tid').value+'&delta=-100');
$('bp100').onclick=()=>fastMove('/api/move?id='+$('tid').value+'&delta=100');
$('bcenter').onclick=()=>fastMove('/api/center?id='+$('tid').value);
</script></body></html>)HTML";
