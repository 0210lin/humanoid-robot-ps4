// 手機網頁(存在韌體裡)。連上模組的 WiFi 後,瀏覽器開 http://192.168.4.1
#pragma once
#include <pgmspace.h>

static const char PAGE[] PROGMEM = R"HTML(<!doctype html>
<html lang="zh-Hant"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>伺服馬達 ID 工具</title>
<style>
:root{--ok:#1a7f37;--bad:#cf222e;--warn:#9a6700;--bg:#fff;--fg:#1f2328;--card:#f6f8fa;--line:#d0d7de;--btn:#0969da}
@media (prefers-color-scheme:dark){:root{--bg:#0d1117;--fg:#e6edf3;--card:#161b22;--line:#30363d;--btn:#2f81f7;--ok:#3fb950;--bad:#f85149;--warn:#d29922}}
*{box-sizing:border-box}
body{font-family:-apple-system,system-ui,"PingFang TC","Noto Sans TC",sans-serif;background:var(--bg);color:var(--fg);margin:0 auto;padding:12px 16px 40px;max-width:520px;font-size:17px}
h1{font-size:20px;margin:6px 0 12px}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:12px 14px;margin:12px 0}
.card h2{font-size:16px;margin:0 0 8px}
button,select,input{font-size:17px;padding:10px 14px;border-radius:10px;border:1px solid var(--line);background:var(--bg);color:var(--fg)}
button{background:var(--btn);color:#fff;border:0;margin:4px 4px 4px 0}
button.sec{background:var(--card);color:var(--fg);border:1px solid var(--line)}
button.danger{background:var(--bad)}
button:disabled{opacity:.4}
.big{font-size:22px;font-weight:700}
.ok{color:var(--ok)}.bad{color:var(--bad)}.warn{color:var(--warn)}
#log{white-space:pre-wrap;font-family:ui-monospace,Menlo,monospace;font-size:13px;max-height:220px;overflow:auto;background:var(--bg);border:1px solid var(--line);border-radius:8px;padding:8px}
.row{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
label.rad{display:block;padding:8px 0}
</style></head><body>
<h1>伺服馬達 ID 工具</h1>

<div class="card">
  <h2>1. 電池與馬達電壓(先做這步,再插馬達)</h2>
  <div id="bat" class="big">讀取中…</div>
  <div id="batmsg"></div>
  <label class="rad"><input type="radio" name="cls" value="74"> 我要編的是 <b>7.4 V</b> 馬達(要接 2S 電池)</label>
  <label class="rad"><input type="radio" name="cls" value="12"> 我要編的是 <b>12 V</b> 馬達(要接 3S 電池)</label>
  <div id="clsmsg"></div>
</div>

<div class="card">
  <h2>2. 找馬達</h2>
  <button id="bscan">掃描(找出馬達現在是幾號)</button>
  <div id="scanres"></div>
</div>

<div class="card">
  <h2>3. 改成新的 ID</h2>
  <div class="row"><span>新 ID:</span><select id="newid"></select><button id="bset" class="danger">改成這個號碼</button></div>
  <div class="warn">匯流排上只能接一顆馬達。改完會自動讀回確認。</div>
</div>

<div class="card">
  <h2>4. 測試</h2>
  <div class="row"><span>馬達 ID:</span><input id="tid" type="number" min="1" max="127" value="1" style="width:5em"></div>
  <div class="row">
    <button id="bstat" class="sec">讀狀態</button>
    <select id="amp"><option value="100">小角度</option><option value="150" selected>中角度</option><option value="250">大角度</option></select>
    <button id="btest">測試轉動</button>
    <button id="brel" class="sec">放鬆</button>
  </div>
  <div class="row" style="margin-top:6px">
    <span>微調:</span>
    <button id="bm100" class="big">−100</button>
    <button id="bp100" class="big">+100</button>
    <button id="bcenter" class="sec">回中點</button>
    <button id="bhome" class="sec">回原位</button>
  </div>
  <div class="warn">−100 / +100:每按一次動 100 單位(約 16°),不限次數(位置夾在 800~2200)。「回原位」= 第一次按時的位置,「回中點」= 1500。馬達要空載。</div>
  <hr style="border:0;border-top:1px solid var(--line);margin:12px 0">
  <b>來回掃(讓潤滑油平均)</b>
  <div class="row" style="margin-top:6px">
    <span>左端</span><input id="slo" type="number" min="800" max="2200" value="900" style="width:5.5em">
    <span>右端</span><input id="shi" type="number" min="800" max="2200" value="2100" style="width:5.5em">
  </div>
  <div class="row" style="margin-top:6px">
    <span>單程</span><input id="st" type="number" min="0.8" max="5" step="0.1" value="1.5" style="width:4.5em"><span>秒</span>
    <span>最長</span><input id="smin" type="number" min="0" max="600" value="0" style="width:4.5em"><span>分鐘(0 = 不限時)</span>
  </div>
  <div class="row" style="margin-top:6px">
    <button id="bsweep" class="big">開始來回掃</button>
    <button id="bsweepstop" class="danger big">停止</button>
  </div>
  <div id="sweepres"></div>
  <div class="warn">會一直在左端和右端之間來回,直到按「停止」(停下來時回到中間)。馬達一定要空載,發燙或有怪聲就按停止。電池電量低、電壓不符、馬達沒回應時會自動停。要掃滿整個行程,左端填 800、右端填 2200(預設留了一點餘裕)。</div>
  <div id="testres"></div>
</div>

<div class="card"><h2>記錄</h2><div id="log"></div></div>

<script>
const $=id=>document.getElementById(id);
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
  if(s.vbat===undefined){$('bat').textContent='連不上模組';return;}
  const names={none:'沒接電池(只有 USB)',"2S":'2S 電池(約 7.4 V)',"3S":'3S 電池(約 11.1 V)',bad:'電壓不在 2S / 3S 範圍,請檢查'};
  $('bat').innerHTML='電池 '+s.vbat.toFixed(2)+' V → '+(names[s.cls]||s.cls);
  let m='',c='';
  if(s.cls==='none'){m='請先接上電池。';c='warn';}
  else if(s.cls==='bad'){m='電壓異常,先不要插馬達。';c='bad';}
  else if(s.low){m='電池電量偏低,請換電池或充電。';c='warn';}
  else {m='電池電壓正常。';c='ok';}
  $('batmsg').innerHTML='<span class="'+c+'">'+m+'</span>';
  if(s.sel==0){$('clsmsg').innerHTML='<span class="warn">請選擇你要編的馬達電壓。</span>';}
  else if(s.match){$('clsmsg').innerHTML='<span class="ok">電池和你選的馬達電壓相符,可以插馬達。</span>';}
  else{$('clsmsg').innerHTML='<span class="bad big">電池和選的馬達不符!不要插馬達,已插的請馬上拔掉。</span>';}
  if(s.sel!=0){document.querySelector('input[name=cls][value="'+s.sel+'"]').checked=true;}
  const canMove=!!(s.match&&!s.low);
  $('btest').disabled=!canMove;$('bm100').disabled=!canMove;$('bp100').disabled=!canMove;$('bhome').disabled=!canMove;$('bcenter').disabled=!canMove;$('bsweep').disabled=!canMove;
}
setInterval(refresh,2500);refresh();

$('bscan').onclick=()=>run($('bscan'),async()=>{
  $('scanres').textContent='掃描中…(約 1 秒)';
  const j=await api('/api/scan');
  if(!j.echo)log('警告:沒聽到自己送出的資料(回音),請檢查 RX 接線和分壓。');
  if(j.ids===undefined){$('scanres').innerHTML='<span class="bad">'+j.msg+'</span>';return;}
  if(j.ids.length===0){$('scanres').innerHTML='<span class="warn">沒找到馬達。馬達有接好、有通電嗎?</span>';log('掃描:沒有馬達');}
  else if(j.ids.length===1){$('scanres').innerHTML='<span class="ok big">找到 1 顆:'+j.ids[0]+' 號</span>';$('tid').value=j.ids[0];log('掃描:'+j.ids[0]+' 號');}
  else{$('scanres').innerHTML='<span class="bad">找到 '+j.ids.length+' 顆:'+j.ids.join('、')+'。要改 ID 請只接一顆。</span>';log('掃描:'+j.ids.join(','));}
});
$('bset').onclick=()=>run($('bset'),async()=>{
  const n=$('newid').value;
  if(!confirm('把馬達的 ID 改成 '+n+' 號?\n(匯流排上只能有這一顆馬達)'))return;
  log('改 ID → '+n+' …(約 3 秒)');
  const j=await api('/api/setid?new='+n);
  log(j.msg||JSON.stringify(j));
  $('scanres').innerHTML='<span class="'+(j.ok?'ok':'bad')+' big">'+(j.msg||'')+'</span>';
  if(j.ok)$('tid').value=n;
});
$('bstat').onclick=()=>run($('bstat'),async()=>{
  const j=await api('/api/status?id='+$('tid').value);
  if(!j.ok){$('testres').innerHTML='<span class="bad">'+j.msg+'</span>';log(j.msg);return;}
  $('testres').innerHTML='位置 '+j.pos+'(1500 = 中點)<br>輸入電壓(原始值) '+j.volt+'<br>電流(原始值) '+j.cur+'<br>溫度(原始值) '+j.temp+'<br>ID 暫存器 '+j.idreg+'<br>馬達自己的左 / 右端點設定 '+j.limL+' / '+j.limR+'(0 = 讀不到)';
  log('狀態 位置='+j.pos+' 電壓原始='+j.volt+' 電流原始='+j.cur+' 溫度原始='+j.temp);
});
$('btest').onclick=()=>run($('btest'),async()=>{
  $('testres').textContent='測試轉動中…(約 2 秒,請讓馬達空載)';
  const j=await api('/api/test?id='+$('tid').value+'&amp='+$('amp').value);
  $('testres').innerHTML='<span class="'+(j.ok?'ok':'bad')+'">'+j.msg+'</span>';log(j.msg);
});
function nudge(btn,d){return run(btn,async()=>{
  $('testres').textContent='移動中…';
  const j=await api('/api/move?id='+$('tid').value+'&delta='+d);
  $('testres').innerHTML='<span class="'+(j.ok?'ok':'bad')+'">'+j.msg+'</span>';log((d>0?'+':'')+d+':'+j.msg);
});}
$('bcenter').onclick=()=>run($('bcenter'),async()=>{
  const j=await api('/api/center?id='+$('tid').value);
  $('testres').innerHTML='<span class="'+(j.ok?'ok':'bad')+'">'+j.msg+'</span>';log(j.msg);
});
let sweepTimer=null;
async function sweepPoll(){
  const j=await api('/api/sweepstate');
  if(j.on===undefined)return;
  if(j.on){
    $('sweepres').innerHTML='<span class="ok big">來回掃中:已經 '+j.cycles+' 趟,'+j.sec+' 秒 ('+j.lo+' ↔ '+j.hi+')</span>';
  }else{
    $('sweepres').innerHTML='<span class="warn">'+(j.reason?('已停止:'+j.reason):'沒有在來回掃')+'(共 '+j.cycles+' 趟)</span>';
    if(sweepTimer){clearInterval(sweepTimer);sweepTimer=null;}
  }
}
function sweepWatch(){if(!sweepTimer){sweepTimer=setInterval(sweepPoll,1000);}sweepPoll();}
$('bsweep').onclick=async()=>{
  const q='/api/sweep?id='+$('tid').value+'&lo='+$('slo').value+'&hi='+$('shi').value+'&t='+$('st').value+'&min='+$('smin').value;
  const j=await api(q);
  log(j.msg);
  $('sweepres').innerHTML='<span class="'+(j.ok?'ok':'bad')+'">'+j.msg+'</span>';
  if(j.ok)sweepWatch();
};
$('bsweepstop').onclick=async()=>{
  const j=await api('/api/sweepstop');
  log(j.msg);
  $('sweepres').innerHTML='<span class="'+(j.ok?'ok':'bad')+'">'+j.msg+'</span>';
  setTimeout(sweepPoll,800);
};
sweepPoll();
$('bm100').onclick=()=>nudge($('bm100'),-100);
$('bp100').onclick=()=>nudge($('bp100'),100);
$('bhome').onclick=()=>run($('bhome'),async()=>{
  const j=await api('/api/home?id='+$('tid').value);
  $('testres').innerHTML='<span class="'+(j.ok?'ok':'bad')+'">'+j.msg+'</span>';log(j.msg);
});
$('brel').onclick=()=>run($('brel'),async()=>{
  const j=await api('/api/release?id='+$('tid').value);
  $('testres').innerHTML='<span class="'+(j.ok?'ok':'bad')+'">'+j.msg+'</span>';log(j.msg);
});
</script></body></html>)HTML";
