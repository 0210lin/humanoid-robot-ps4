// 手機網頁(存在韌體裡)。連上模組的 WiFi 後,瀏覽器開 http://192.168.4.1
// 兩個分頁:編 ID 與測試 / 多顆馬達(和電腦版 ServoID_Tool.exe 一樣)
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
header{background:linear-gradient(135deg,#1e3a8a,#2563eb 60%,#38bdf8);color:#fff;padding:22px 18px 20px;margin:0 -14px 6px;border-radius:0 0 22px 22px;box-shadow:var(--shadow)}
header h1{margin:0;font-size:22px;letter-spacing:.5px}
header p{margin:4px 0 0;font-size:13px;opacity:.85}
.tabs{position:sticky;top:0;z-index:5;display:flex;gap:6px;background:var(--bg);padding:8px 0}
.tabs button{flex:1;background:var(--card);color:var(--fg);border:1px solid var(--line);border-radius:12px;padding:10px 2px;font-size:15px;box-shadow:var(--shadow)}
.tabs button.on{background:var(--acc);color:#fff;border-color:var(--acc)}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:14px 16px;margin:12px 0;box-shadow:var(--shadow)}
.card h2{font-size:16px;margin:0 0 10px;display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.card h2 small{font-weight:400;color:var(--sub);font-size:13px}
.step{display:inline-flex;align-items:center;justify-content:center;width:24px;height:24px;border-radius:50%;background:var(--acc);color:#fff;font-size:13px;font-weight:700;flex:none}
button,select,input{font:inherit;font-size:16px}
select,input[type=number],input.t{padding:10px 12px;border-radius:10px;border:1px solid var(--line);background:var(--bg);color:var(--fg)}
input.t{width:100%;margin-top:8px;min-width:0}
.modal{position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:20;display:flex;align-items:center;justify-content:center;padding:16px}
.modal[hidden]{display:none}
.modal .card{max-width:420px;width:100%;margin:0}
.sub2{font-size:14px;font-weight:700;margin:12px 0 2px}
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
.chips{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}
.chips button{padding:8px 14px}
ul.tight{margin:6px 0 0;padding-left:20px;font-size:14px}
</style></head><body>
<div class="wrap">
<header><h1>伺服馬達 ID 工具</h1><p id="hdrnet">連上模組 WiFi 後使用</p></header>
<div id="netmodal" class="modal" hidden><div class="card">
  <h2>第一次使用:要設定模組去找的網路嗎?</h2>
  <div class="note" style="margin-top:0">設定後,模組會自己連上那個網路(例如你的手機熱點或家裡的 WiFi),你就不用切換 WiFi,在那個網路上開 <b>http://servoid.local</b> 就能用。模組自己的熱點也照樣開著。只能設定一個網路,之後隨時可以在「網路設定」改。</div>
  <div class="row nowrap" style="margin-top:12px"><button id="bnetyes" class="grow">現在設定</button><button id="bnetno" class="sec grow">不要</button></div>
</div></div>
<nav class="tabs"><button data-t="t1" class="on">編 ID 與測試</button><button data-t="t3">多顆馬達</button></nav>

<section id="t1">
<div class="card">
  <h2><span class="step">1</span>電池與馬達電壓<small>(先做這步,再插馬達)</small></h2>
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
<div class="card" id="netcard">
  <h2>網路設定</h2>
  <div class="sub2">這塊板子的熱點</div>
  <div id="apinfo" class="note" style="margin-top:0"></div>
  <details><summary>改熱點密碼</summary>
    <input id="appw1" class="t" type="password" placeholder="新密碼(8 個字以上)">
    <input id="appw2" class="t" type="password" placeholder="再輸入一次">
    <button id="bappw" style="width:100%;margin-top:8px">改密碼</button>
    <div class="note">忘記密碼時:按住板子上的 <b>BOOT</b> 鍵 3 秒,密碼會回到出廠密碼。</div>
  </details>
  <div class="sub2">模組要去找的網路<small style="font-weight:400;color:var(--sub)">(只能一個)</small></div>
  <div id="stainfo" class="msg" style="margin-top:0"></div>
  <div id="staform" hidden>
    <div class="row nowrap" style="margin-top:4px"><input id="stassid" class="t grow" style="margin-top:0" placeholder="網路名稱(例如:小明的 iPhone)"><button id="bscannet" class="sec">掃描</button></div>
    <div id="netlist" class="chips"></div>
    <input id="stapw" class="t" type="password" placeholder="密碼(沒有密碼就留空)">
    <button id="bstasave" style="width:100%;margin-top:8px">設定這個網路</button>
    <div class="note">只能連 2.4 GHz。iPhone 熱點要到「設定 → 個人熱點」打開「最大化相容性」。</div>
  </div>
  <div class="row nowrap" style="margin-top:8px"><button id="bstaedit" class="sec grow">設定 / 更換</button><button id="bstaclr" class="sec grow">清除</button></div>
  <div id="netres" class="msg"></div>
</div>
</section>

<section id="t3" hidden>
<div class="card">
  <h2>找出所有馬達</h2>
  <button id="bscanall" style="width:100%">掃描,列出全部馬達</button>
  <div id="scanallres" class="msg"></div>
  <div id="chips" class="chips"></div>
  <div class="note">點號碼,那顆馬達會左右動一下,方便認出是哪一顆(要先在第一頁選好電壓)。多顆同號時只會看到一個號碼。</div>
</div>
<div class="card">
  <h2>全部一起來回轉<small>(不用管 ID)</small></h2>
  <div class="row">
    <span>左端</span><input id="mlo" type="number" min="800" max="2200" value="1100" style="width:6em">
    <span>右端</span><input id="mhi" type="number" min="800" max="2200" value="1900" style="width:6em">
  </div>
  <div class="row" style="margin-top:8px">
    <span>單程</span><input id="mst" type="number" min="0.8" max="5" step="0.1" value="1.5" style="width:5em"><span>秒</span>
    <span>最長</span><input id="mmin" type="number" min="0" max="600" value="2" style="width:5em"><span>分鐘</span>
  </div>
  <div class="row nowrap" style="margin-top:10px"><button id="bmulti" class="grow">全部一起開始轉</button><button id="bmultistop" class="danger grow">停止</button></div>
  <div id="msweepres" class="msg"></div>
  <div class="note">有回應的號碼(最近一趟):<b id="macks">—</b></div>
  <div class="note">用眼睛看:不轉、只抖、卡卡、有怪聲、特別慢的就是有問題。多顆同號時回應會互相干擾,號碼清單可能不完整。</div>
</div>
<div class="card">
  <h2>全部一起</h2>
  <div class="row nowrap"><button id="bcenterall" class="sec grow">全部回中 1500</button><button id="breleaseall" class="sec grow">全部放鬆</button></div>
  <div id="allres" class="msg"></div>
  <ul class="tight note"><li>只接同一種電壓的馬達(和第一頁選的一樣)</li><li>馬達要空載,不要裝在機器人上</li><li>電流會加總:先接 4 ~ 5 顆試,板子保險絲 5 A</li></ul>
</div>
<div class="card">
  <h2>全部改成同一個 ID</h2>
  <div class="row nowrap"><select id="allnewid" class="grow"></select><button id="bsetall" class="danger">全部改成這個號碼</button></div>
  <div id="setallres" class="msg"></div>
  <ul class="tight bad"><li>接上的每一顆都會改成同一個號碼</li><li>改完就分不出誰是誰,之後要一顆一顆重新編</li><li>不要接在機器人上:馬達號碼會全部亂掉</li><li>會先掃描給你確認,改完再掃一次檢查</li></ul>
</div>
</section>

<div class="card"><h2>記錄</h2><div id="log"></div></div>
</div>

<script>
const $=id=>document.getElementById(id);
function setH(el,h){if(el._h!==h){el._h=h;el.innerHTML=h;}}   // 內容沒變就不重畫,畫面不會跳動
function span(c,t){return '<span class="'+c+'">'+t+'</span>';}
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
function idsOf(j){return j.ids===undefined?null:j.ids;}
for(let i=1;i<=32;i++){for(const s of ['newid','allnewid']){const o=document.createElement('option');o.value=i;o.textContent=i+' 號';$(s).appendChild(o);}}

// ---------- 分頁 ----------
document.querySelectorAll('.tabs button').forEach(b=>b.onclick=()=>{
  document.querySelectorAll('.tabs button').forEach(x=>x.classList.toggle('on',x===b));
  for(const s of ['t1','t3'])$(s).hidden=(s!==b.dataset.t);
  window.scrollTo(0,0);
});

// ---------- 分頁 1:編 ID 與測試 ----------
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
  setH($('batmsg'),span(c,m));
  if(s.sel==0){setH($('clsmsg'),span('warn','請選擇你要編的馬達電壓。'));}
  else if(s.match){setH($('clsmsg'),span('ok','電池和你選的馬達電壓相符,可以插馬達。'));}
  else{setH($('clsmsg'),span('bad','電池和選的馬達不符!不要插馬達,已插的請馬上拔掉。'));}
  if(s.sel!=0){document.querySelector('input[name=cls][value="'+s.sel+'"]').checked=true;}
  const canMove=!!(s.match&&!s.low);
  for(const b of ['bm100','bp100','bcenter','bsweep','bmulti'])$(b).disabled=!canMove;
}
setInterval(refresh,2500);refresh();

$('bscan').onclick=()=>run($('bscan'),async()=>{
  setH($('scanres'),'掃描中…(約 1 秒)');
  const j=await api('/api/scan');
  if(!j.echo&&j.ids!==undefined)log('警告:沒聽到自己送出的資料(回音),請檢查 RX 接線和分壓。');
  if(j.ids===undefined){setH($('scanres'),span('bad',j.msg));return;}
  if(j.ids.length===0){setH($('scanres'),span('warn','沒找到馬達。馬達有接好、有通電嗎?'));log('掃描:沒有馬達');}
  else if(j.ids.length===1){setH($('scanres'),span('ok big','找到 1 顆:'+j.ids[0]+' 號'));$('tid').value=j.ids[0];log('掃描:'+j.ids[0]+' 號');}
  else{setH($('scanres'),span('bad','找到 '+j.ids.length+' 顆:'+j.ids.join('、')+'。要改 ID 請只接一顆。'));log('掃描:'+j.ids.join(','));}
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
    setH($('testres'),span(j.ok?'ok':'bad',(j.target!==undefined&&j.ok?'目標 '+j.target:j.msg)));
    if(!j.ok||/極限/.test(j.msg||''))log(j.msg);
  }catch(e){setH($('testres'),span('bad','連不上模組'));}
}
$('bm100').onclick=()=>fastMove('/api/move?id='+$('tid').value+'&delta=-100');
$('bp100').onclick=()=>fastMove('/api/move?id='+$('tid').value+'&delta=100');
$('bcenter').onclick=()=>fastMove('/api/center?id='+$('tid').value);

// ---------- 來回掃(一顆:分頁 1;全部一起:分頁 3)----------
let sweepTimer=null;
async function sweepPoll(){
  const j=await api('/api/sweepstate');
  if(j.on===undefined)return;
  const mine=j.all?'msweepres':'sweepres',other=j.all?'sweepres':'msweepres';
  if(j.on){
    setH($(mine),span('ok big',(j.all?'全部一起轉中:':'來回掃中:')+'已經 '+j.cycles+' 趟,'+j.sec+' 秒('+j.lo+' ↔ '+j.hi+')'));
    setH($(other),span('warn',j.all?'現在是「全部一起轉」(多顆馬達頁),要先在那一頁按停止':'現在有一顆在來回掃(第一頁),要先按停止'));
    if(j.all)setH($('macks'),j.nack>0?j.acks.split(',').join('、')+(j.nack>20?' …':'')+'(共 '+j.nack+' 個號碼)':span('warn','這一趟沒有收到回應(多顆同號時常見;以眼睛看為準)'));
    if(!sweepTimer)sweepTimer=setInterval(sweepPoll,1000);
  }else{
    if(j.reason||j.cycles)setH($(mine),span('warn',(j.reason?('已停止:'+j.reason):'沒有在轉')+'(共 '+j.cycles+' 趟)'));
    setH($(other),'');
    if(sweepTimer){clearInterval(sweepTimer);sweepTimer=null;}
  }
}
function sweepWatch(){if(!sweepTimer){sweepTimer=setInterval(sweepPoll,1000);}sweepPoll();}
async function sweepStart(q,res){
  const j=await api(q);
  log(j.msg);
  setH($(res),span(j.ok?'ok':'bad',j.msg));
  if(j.ok)sweepWatch();
}
async function sweepStop(res){
  const j=await api('/api/sweepstop');
  log(j.msg);
  setH($(res),span(j.ok?'ok':'bad',j.msg));
  setTimeout(sweepPoll,800);
}
$('bsweep').onclick=()=>sweepStart('/api/sweep?id='+$('tid').value+'&lo='+$('slo').value+'&hi='+$('shi').value+'&t='+$('st').value+'&min='+$('smin').value,'sweepres');
$('bsweepstop').onclick=()=>sweepStop('sweepres');
$('bmulti').onclick=()=>{
  if(!confirm('接上的馬達會全部一起來回轉。\n馬達都空載、沒有裝在機器人上了嗎?'))return;
  setH($('macks'),'等第一趟…');
  sweepStart('/api/sweep?all=1&id=0&lo='+$('mlo').value+'&hi='+$('mhi').value+'&t='+$('mst').value+'&min='+$('mmin').value,'msweepres');
};
$('bmultistop').onclick=()=>sweepStop('msweepres');
sweepPoll();

// ---------- 網路設定 ----------
function esc(t){return String(t).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));}
let netAsked=false;
async function netLoad(){
  const j=await api('/api/net');
  if(j.ap===undefined)return;
  setH($('apinfo'),'名稱:<b>'+esc(j.ap)+'</b>(出廠時設定,不能改)');
  if(!j.ssid)setH($('stainfo'),span('warn','還沒設定'));
  else setH($('stainfo'),'「<b>'+esc(j.ssid)+'</b>」'+(j.connected?span('ok','已連上')+',在那個網路上開 <b>http://servoid.local</b>(或 <b>http://'+j.ip+'</b>)':span('warn','還沒連上(那個網路有開嗎?密碼對嗎?)')));
  setH($('hdrnet'),j.connected?'也可以用:http://servoid.local(在「'+esc(j.ssid)+'」上)':'連上模組 WiFi 後使用');
  $('bstaclr').disabled=!j.ssid;
  if(!j.ssid&&!j.skip&&!netAsked){netAsked=true;$('netmodal').hidden=false;}
}
function openStaForm(){
  $('staform').hidden=false;
  document.querySelectorAll('.tabs button').forEach(x=>x.classList.toggle('on',x.dataset.t==='t1'));
  $('t1').hidden=false;$('t3').hidden=true;
  setTimeout(()=>$('netcard').scrollIntoView({behavior:'smooth'}),50);
}
$('bnetyes').onclick=()=>{$('netmodal').hidden=true;openStaForm();};
$('bnetno').onclick=async()=>{$('netmodal').hidden=true;const j=await api('/api/netskip');log(j.msg||'');};
$('bstaedit').onclick=()=>{$('staform').hidden=!$('staform').hidden;};
$('bscannet').onclick=()=>run($('bscannet'),async()=>{
  setH($('netlist'),'<span class="note">掃描中…(約 3 秒,手機可能會短暫斷線)</span>');
  const j=await api('/api/netscan');
  if(!j.list){setH($('netlist'),span('bad',j.msg||'掃描失敗'));return;}
  if(!j.list.length){setH($('netlist'),span('warn','附近沒找到網路(熱點有開嗎?是 2.4 GHz 嗎?)'));return;}
  $('netlist').innerHTML='';$('netlist')._h='';
  for(const n of j.list){const b=document.createElement('button');b.className='sec';b.textContent=n.s;b.onclick=()=>{$('stassid').value=n.s;$('stapw').focus();};$('netlist').appendChild(b);}
});
$('bstasave').onclick=()=>run($('bstasave'),async()=>{
  const ss=$('stassid').value,pw=$('stapw').value;
  if(!ss){setH($('netres'),span('bad','請輸入網路名稱'));return;}
  if(!confirm('設定模組去找「'+ss+'」?\n(只能一個,會取代原本的設定。模組會重新開機,手機要重新連一次熱點)'))return;
  const j=await api('/api/netset?ssid='+encodeURIComponent(ss)+'&pass='+encodeURIComponent(pw));
  setH($('netres'),span(j.ok?'ok':'bad',esc(j.msg||'')));log(j.msg||'');
  if(j.ok){$('staform').hidden=true;$('stapw').value='';}
});
$('bstaclr').onclick=()=>run($('bstaclr'),async()=>{
  if(!confirm('清除模組要找的網路?(模組會重新開機)'))return;
  const j=await api('/api/netclear');setH($('netres'),span(j.ok?'ok':'bad',esc(j.msg||'')));log(j.msg||'');
});
$('bappw').onclick=()=>run($('bappw'),async()=>{
  const a=$('appw1').value,b=$('appw2').value;
  if(a.length<8){setH($('netres'),span('bad','密碼要 8 個字以上'));return;}
  if(a!==b){setH($('netres'),span('bad','兩次輸入的密碼不一樣'));return;}
  if(!confirm('改熱點密碼?\n模組會重新開機,手機要用新密碼重新連線。忘記時按住 BOOT 3 秒可以回到出廠密碼。'))return;
  const j=await api('/api/appass?pass='+encodeURIComponent(a));
  setH($('netres'),span(j.ok?'ok':'bad',esc(j.msg||'')));log(j.msg||'');
  if(j.ok){$('appw1').value='';$('appw2').value='';}
});
netLoad();setInterval(netLoad,10000);

// ---------- 分頁 3:多顆馬達 ----------
$('bscanall').onclick=()=>run($('bscanall'),async()=>{
  setH($('scanallres'),'掃描中…(約 1 秒)');setH($('chips'),'');
  const j=await api('/api/scan');const ids=idsOf(j);
  if(ids===null){setH($('scanallres'),span('bad',j.msg));return;}
  if(!ids.length){setH($('scanallres'),span('warn','沒找到馬達。馬達有接好、有通電嗎?'));log('掃描全部:沒有馬達');return;}
  setH($('scanallres'),span('ok big','找到 '+ids.length+(ids.length>=16?' 顆以上':' 顆')+':'+ids.join('、')+' 號'));
  log('掃描全部:'+ids.join(','));
  $('chips').innerHTML='';$('chips')._h='';
  for(const id of ids){
    const b=document.createElement('button');b.className='sec';b.textContent=id+' 號';
    b.onclick=()=>run(b,async()=>{const r=await api('/api/wiggle?id='+id);setH($('scanallres'),span(r.ok?'ok':'bad',r.msg));});
    $('chips').appendChild(b);
  }
});
$('bsetall').onclick=()=>run($('bsetall'),async()=>{
  const n=$('allnewid').value;
  setH($('setallres'),'先掃描…');
  let j=await api('/api/scan');const ids=idsOf(j);
  if(ids===null){setH($('setallres'),span('bad',j.msg));return;}
  if(!ids.length){setH($('setallres'),span('warn','沒找到馬達。馬達有接好、有通電嗎?'));return;}
  setH($('setallres'),'');
  if(!confirm('找到 '+ids.length+' 個號碼:'+ids.join('、')+' 號(多顆同號時只看得到一個)。\n\n要把接上的全部馬達都改成 '+n+' 號嗎?\n改完它們就分不出誰是誰,要一顆一顆重新編。\n不要接在機器人上!')){setH($('setallres'),'已取消,沒有改。');return;}
  setH($('setallres'),'正在全部改成 '+n+' 號…(約 2 秒)');
  log('全部改 ID → '+n+'(原本 '+ids.join(',')+')');
  j=await api('/api/setall?new='+n);
  log(j.msg);
  setH($('setallres'),span(j.ok?'ok':'bad',j.msg));
  setH($('chips'),'');setH($('scanallres'),'');   // 號碼都變了,舊的清單作廢
});
async function allCmd(btn,path){
  run(btn,async()=>{const j=await api(path);setH($('allres'),span(j.ok?'ok':'bad',j.msg));log(j.msg);});
}
$('bcenterall').onclick=()=>allCmd($('bcenterall'),'/api/allpos?pos=1500&t=600');
$('breleaseall').onclick=()=>allCmd($('breleaseall'),'/api/allrelease');

</script></body></html>)HTML";
