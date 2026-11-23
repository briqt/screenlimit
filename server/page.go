package main

import (
	"html/template"
	"net/http"
	"sort"
	"time"
)

// 审批页与配置页。刻意做成单文件内联模板 + 内联 CSS：
// 这台服务器上不放静态资源目录，部署就是 scp 一个二进制 + 一个 systemd unit。
// 配色沿用 App 的语义色板（守护蓝 #3B6FE0），手机端和网页端看起来是一个东西。

const shellCSS = `
:root{
  --surface:#0F1115; --card:#171A21; --card-hi:#212630; --hi:#E8EAF0; --mid:#A6ACC0;
  --low:#6E7482; --line:#2A2F3A; --primary:#7EA0F5; --on-primary:#0B1020;
  --ok:#7BD1A0; --ok-bg:#1E3A2C; --danger:#F0857C; --danger-bg:#44231F;
  --warn:#E0AC5E; --warn-bg:#3B2E16; --nav-h:64px;
}
@media (prefers-color-scheme: light){
  :root{--surface:#F4F5F8;--card:#FFF;--card-hi:#EDEFF4;--hi:#1A1D24;--mid:#5B6170;--low:#9AA0AD;
        --line:#E3E7EE;--primary:#3B6FE0;--on-primary:#FFF;
        --ok:#2E9E63;--ok-bg:#D8F0E2;--danger:#D4493E;--danger-bg:#FBDEDB;--warn:#B8720E;--warn-bg:#FCEBCF}
}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;background:var(--surface);color:var(--hi);
  font:15px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft Yahei",sans-serif;
  padding:0 0 calc(var(--nav-h) + env(safe-area-inset-bottom) + 16px)}
.top{position:sticky;top:0;z-index:5;background:color-mix(in srgb,var(--surface) 88%,transparent);
  backdrop-filter:blur(12px);padding:calc(env(safe-area-inset-top) + 14px) 16px 12px;
  border-bottom:1px solid var(--line);display:flex;align-items:center;gap:10px}
.top h1{font-size:18px;margin:0;flex:1}
.top .sub{display:block;font-size:11.5px;color:var(--low);font-weight:400;margin-top:1px}
.wrap{max-width:680px;margin:0 auto;padding:16px}
.card{background:var(--card);border-radius:20px;padding:16px;margin-bottom:12px;border:1px solid var(--line)}
.card h2{font-size:13px;color:var(--low);margin:0 0 12px;font-weight:600;letter-spacing:.04em}
.row{display:flex;align-items:center;gap:12px;padding:11px 0;border-top:1px solid var(--line)}
.row:first-of-type{border-top:0}
.row .t{flex:1;min-width:0}
.row .t b{display:block;font-weight:500;font-size:15px}
.row .t span{display:block;font-size:11.5px;color:var(--low);margin-top:1px;word-break:break-all}
.chip{display:inline-block;padding:3px 10px;border-radius:9px;font-size:11.5px;white-space:nowrap}
.c-pending{background:var(--warn-bg);color:var(--warn)}
.c-approved{background:var(--ok-bg);color:var(--ok)}
.c-denied{background:var(--danger-bg);color:var(--danger)}
input[type=text],input[type=password],input[type=number],input[type=time]{
  background:var(--card-hi);border:1px solid var(--line);color:var(--hi);
  border-radius:13px;padding:11px 13px;font-size:16px;width:100%;font-family:inherit}
input::placeholder{color:var(--low)}
.pin{letter-spacing:6px;text-align:center;font-size:19px}
button{border:0;border-radius:13px;padding:12px 18px;font-size:15px;font-family:inherit;cursor:pointer}
.b-pri{background:var(--primary);color:var(--on-primary);font-weight:600;width:100%}
.b-gho{background:transparent;color:var(--mid);border:1px solid var(--line);width:100%}
.stack{display:flex;flex-direction:column;gap:8px;margin-top:12px}
.two{display:flex;gap:8px}.two>*{flex:1}
.empty{color:var(--low);text-align:center;padding:52px 0;font-size:14px}
.sw{position:relative;width:50px;height:30px;flex:0 0 auto}
.sw input{opacity:0;width:100%;height:100%;margin:0;position:absolute;z-index:2}
.sw i{position:absolute;inset:0;background:var(--card-hi);border:1px solid var(--line);
  border-radius:16px;transition:.18s}
.sw i:after{content:"";position:absolute;width:24px;height:24px;border-radius:50%;
  background:var(--low);top:2px;left:2px;transition:.18s}
.sw input:checked + i{background:var(--primary);border-color:var(--primary)}
.sw input:checked + i:after{background:#fff;transform:translateX(20px)}
.seg{display:flex;gap:6px;flex:0 0 auto}
.seg label{position:relative}
.seg input{position:absolute;opacity:0;pointer-events:none}
.seg span{display:block;padding:6px 11px;border-radius:10px;font-size:12px;color:var(--mid);
  background:var(--card-hi);border:1px solid var(--line);cursor:pointer;white-space:nowrap}
.seg input:checked + span{background:var(--primary);color:var(--on-primary);border-color:var(--primary)}
.q{width:76px!important;padding:7px 9px!important;font-size:13px!important;text-align:center}
.nav{position:fixed;left:0;right:0;bottom:0;z-index:9;display:flex;
  background:color-mix(in srgb,var(--card) 94%,transparent);backdrop-filter:blur(14px);
  border-top:1px solid var(--line);padding-bottom:env(safe-area-inset-bottom)}
.nav a{flex:1;text-align:center;padding:10px 0 12px;text-decoration:none;color:var(--low);font-size:11px}
.nav a.on{color:var(--primary)}
.nav a b{display:block;font-size:19px;line-height:1.25;font-weight:400}
.note{font-size:11.5px;color:var(--low);line-height:1.75;margin-top:10px}
.bad{background:var(--danger-bg);color:var(--danger);padding:10px 13px;border-radius:12px;
  font-size:13px;margin-bottom:12px}
.good{background:var(--ok-bg);color:var(--ok);padding:10px 13px;border-radius:12px;
  font-size:13px;margin-bottom:12px}
.info{background:var(--card);color:var(--mid);border:1px solid var(--line);padding:10px 13px;
  border-radius:12px;font-size:12.5px;margin-bottom:12px;line-height:1.6}
.searchbar{position:sticky;top:0;z-index:3;margin:-4px 0 10px}
.searchbar input{padding-left:38px}
.searchbar:before{content:"\1F50D";position:absolute;left:13px;top:50%;transform:translateY(-50%);
  font-size:14px;opacity:.55;z-index:2}
.filters{display:flex;gap:6px;margin:0 0 10px;flex-wrap:wrap}
.filters label{position:relative}
.filters input{position:absolute;opacity:0;pointer-events:none}
.filters span{display:block;padding:6px 12px;border-radius:99px;font-size:12px;color:var(--mid);
  background:var(--card-hi);border:1px solid var(--line);cursor:pointer}
.filters input:checked + span{background:var(--primary);color:var(--on-primary);border-color:var(--primary)}
.filters b{font-weight:600}
.approw{transition:.12s}
.approw .seg{flex-wrap:wrap;justify-content:flex-end}
.approw .seg span{padding:7px 10px;font-size:12px}
.hidden-app{display:none}
.review{position:fixed;inset:0;z-index:50;background:color-mix(in srgb,#000 55%,transparent);
  display:none;align-items:flex-end}
.review.show{display:flex}
.review .sheet{background:var(--card);width:100%;max-width:680px;margin:0 auto;border-radius:22px 22px 0 0;
  padding:20px 18px calc(20px + env(safe-area-inset-bottom));max-height:86vh;overflow:auto;border:1px solid var(--line)}
.review h2{margin:0 0 4px;font-size:17px}
.review .sub{color:var(--low);font-size:12.5px;margin-bottom:14px}
.diff-list{margin:0 0 16px}
.diff-item{padding:10px 0;border-top:1px solid var(--line);font-size:13.5px}
.diff-item:first-child{border-top:0}
.diff-item .what{color:var(--low);font-size:12px;margin-top:2px}
.arrow{color:var(--low);padding:0 6px}
.tag-w{color:var(--ok)} .tag-g{color:var(--warn)} .tag-d{color:var(--danger)}
.sticky-save{position:sticky;bottom:calc(58px + env(safe-area-inset-bottom));margin-top:14px}
.req-meta{font-size:12px;color:var(--low)}
.duration-pill{display:inline-block;background:var(--primary);color:var(--on-primary);
  border-radius:8px;padding:2px 9px;font-size:12px;font-weight:600;margin-left:6px}
`

const navHTML = `
<nav class="nav">
  <a href="/" class="{{if eq .Tab "req"}}on{{end}}"><b>&#9203;</b>待审批{{if .Pending}} · {{.Pending}}{{end}}</a>
  <a href="/config" class="{{if eq .Tab "cfg"}}on{{end}}"><b>&#9881;</b>规则配置</a>
  <a href="/logout"><b>&#9099;</b>退出</a>
</nav>`

const headHTML = `<!doctype html><html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="color-scheme" content="dark light">
<meta name="theme-color" content="#0F1115" media="(prefers-color-scheme: dark)">
<meta name="theme-color" content="#F4F5F8" media="(prefers-color-scheme: light)">
<link rel="manifest" href="/manifest.webmanifest">
<link rel="apple-touch-icon" href="/icon-192.png">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<title>{{.Title}}</title>
<style>` + shellCSS + `</style></head><body>`

const swReg = `<script>
if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(function(){});
</script>`

var tplIndex = template.Must(template.New("i").Parse(headHTML + `
<div class="top"><h1>远程授权<span class="sub">批准后手机一个轮询周期（约 5 秒）内解锁 · 时长仅在应用前台打开时扣减</span></h1></div>
<div class="wrap">
{{if not .Reqs}}<div class="empty">当前没有待处理的申请</div>{{end}}
{{range .Reqs}}
<div class="card">
  <div class="row">
    <div class="t"><b>{{.App}}</b><span>{{.Pkg}} · {{.Age}}</span></div>
    <span class="chip {{if eq .State "pending"}}c-pending{{else if eq .State "approved"}}c-approved{{else}}c-denied{{end}}">
      {{if eq .State "pending"}}待处理{{else if eq .State "approved"}}已批准{{else}}已拒绝{{end}}</span>
  </div>
  {{if eq .State "pending"}}
  <form method="post" action="/admin/approve" autocomplete="off" class="stack approve-form">
    <input type="hidden" name="csrf" value="{{$.CSRF}}"><input type="hidden" name="id" value="{{.ID}}">
    <input class="pin" type="password" name="password" inputmode="numeric" pattern="[0-9]{6}"
           maxlength="6" placeholder="6 位解锁密码" required>
    <button class="b-pri" type="submit">批准</button>
  </form>
  <form method="post" action="/admin/deny" class="stack">
    <input type="hidden" name="csrf" value="{{$.CSRF}}"><input type="hidden" name="id" value="{{.ID}}">
    <button class="b-gho" type="submit">拒绝</button>
  </form>
  {{end}}
</div>
{{end}}
<div class="note">密码不在服务端留存明文记录：批准后仅暂存一次，被手机取走即清空（浏览器本地自动记住上次输入的解锁密码以便一键秒批）。</div>
</div>` + navHTML + `
<script>
(function(){
  var saved = '';
  try { saved = localStorage.getItem('sl_saved_pin') || ''; } catch(e){}
  var pins = document.querySelectorAll('input.pin');
  for (var i = 0; i < pins.length; i++) {
    if (saved.length === 6 && !pins[i].value) {
      pins[i].value = saved;
      pins[i].dataset.autofilled = '1';
    }
    pins[i].addEventListener('input', function(){ this.dataset.autofilled = ''; });
  }
  var forms = document.querySelectorAll('form.approve-form');
  for (var j = 0; j < forms.length; j++) {
    forms[j].addEventListener('submit', function(){
      var p = this.querySelector('input.pin');
      if (p && p.value.length === 6) {
        try { localStorage.setItem('sl_saved_pin', p.value); } catch(e){}
      }
    });
  }
  setInterval(function(){
    var busy = Array.prototype.some.call(document.querySelectorAll('input.pin'), function(el){
      return document.activeElement === el || (el.value && el.dataset.autofilled !== '1');
    });
    if (!busy && !document.hidden) location.reload();
  }, 5000);
})();
</script>` + swReg + `</body></html>`))

var tplConfig = template.Must(template.New("c").Parse(headHTML + `
<div class="top"><h1>规则配置<span class="sub">先改、再预览变更，确认后才下发到手机</span></h1></div>
<div class="wrap">
{{if .Saved}}<div class="good">已下发，手机会在下一个拉取周期（约 60 秒）内应用 · 配置版本 {{.Version}}</div>{{end}}
{{if .CfgPending}}<div class="info">⏳ 上一次下发手机还没应用（约 60 秒内应用）。下面显示的是应用后的值。</div>
{{else if .FromDevice}}<div class="info">下面是手机此刻实际生效的规则（{{.AppsAt}}）。只有你改动的项会下发。</div>
{{else if not .NoApps}}<div class="info">手机端版本较旧，没有上报实际规则；下面是服务端上次下发的值，可能与手机不一致。</div>{{end}}
{{if .NoApps}}<div class="bad">手机还没上报应用列表，名单部分暂不可编辑。手机端联网后会自动上报。</div>{{end}}
<form method="post" action="/admin/config" id="cfgForm">
<input type="hidden" name="csrf" value="{{.CSRF}}">

<div class="card"><h2>限时规则</h2>
  <div class="row"><div class="t"><b>启用限时</b><span>关闭时所有应用不受限制</span></div>
    <label class="sw"><input type="checkbox" name="master" value="1" {{if .Master}}checked{{end}}><i></i></label></div>
  <div class="row"><div class="t"><b>开始时间</b></div>
    <input type="time" name="winStart" value="{{.WinStart}}" style="width:130px" required></div>
  <div class="row"><div class="t"><b>结束时间</b><span>结束早于开始＝跨午夜</span></div>
    <input type="time" id="winEndTime" value="{{if eq .WinEnd "24:00"}}23:59{{else}}{{.WinEnd}}{{end}}" style="width:130px" {{if eq .WinEnd "24:00"}}disabled{{end}}>
    <input type="hidden" name="winEnd" id="winEndField" value="{{.WinEnd}}">
    <label style="display:flex;align-items:center;gap:6px;font-size:12px;color:var(--mid);white-space:nowrap">
      <input type="checkbox" id="winEnd24" {{if eq .WinEnd "24:00"}}checked{{end}}> 止于 24:00</label></div>
</div>

<div class="card"><h2>防绕过</h2>
  <div class="row"><div class="t"><b>看守系统设置</b><span>拦住卸载弹窗、无障碍设置页、应用信息页。依赖手机端无障碍服务</span></div>
    <label class="sw"><input type="checkbox" name="guard" value="1" {{if .Guard}}checked{{end}}><i></i></label></div>
</div>

<div class="card"><h2>应急使用（整机共用）</h2>
  <div class="row"><div class="t"><b>每次可用</b><span>分钟</span></div>
    <input class="q" type="number" name="coolUse" min="1" max="60" value="{{.CoolUse}}"></div>
  <div class="row"><div class="t"><b>冷却间隔</b><span>分钟。用掉后所有应用一起冷却，冷却满自动恢复</span></div>
    <input class="q" type="number" name="coolIval" min="1" max="720" value="{{.CoolIval}}"></div>
</div>

{{if .Apps}}
<div class="card" id="appsCard"><h2>应用名单 · 共 {{len .Apps}} 个<span style="float:right;font-weight:400">{{.AppsAt}}</span></h2>
  <div class="searchbar"><input type="text" id="appSearch" placeholder="搜索应用名或包名" autocomplete="off"></div>
  <div class="filters" id="appFilters">
    <label><input type="radio" name="af" value="all" checked><span>全部 <b id="cntAll"></b></span></label>
    <label><input type="radio" name="af" value="white"><span>放行 <b id="cntWhite"></b></span></label>
    <label><input type="radio" name="af" value="gray"><span>配额 <b id="cntGray"></b></span></label>
    <label><input type="radio" name="af" value="deny"><span>禁止 <b id="cntDeny"></b></span></label>
    <label><input type="radio" name="af" value="changed"><span>已修改 <b id="cntChanged"></b></span></label>
  </div>
  {{range .Apps}}
  <div class="row approw" data-label="{{.Label}}" data-pkg="{{.Pkg}}">
    <div class="t"><b>{{.Label}}</b><span>{{if .UsedMin}}今日已用 {{.UsedMin}} 分钟 · {{end}}{{.Pkg}}</span></div>
    <div class="seg">
      <label><input type="radio" name="st_{{.Pkg}}" value="white" {{if eq .State "white"}}checked{{end}}><span>放行</span></label>
      <label><input type="radio" name="st_{{.Pkg}}" value="gray" {{if eq .State "gray"}}checked{{end}}><span>配额</span></label>
      <label><input type="radio" name="st_{{.Pkg}}" value="deny" {{if eq .State "deny"}}checked{{end}}><span>禁止</span></label>
    </div>
    <input class="q" type="number" name="q_{{.Pkg}}" min="1" max="1440" value="{{if .Quota}}{{.Quota}}{{else}}30{{end}}">
  </div>
  {{end}}
</div>
{{end}}

<div class="sticky-save"><button class="b-pri" type="button" id="btnReview">预览变更</button></div>
<div class="note">手机拿到配置后无条件应用，所以下发前请在预览里确认每一条。</div>
</form>
</div>

<div class="review" id="review">
  <div class="sheet">
    <h2 id="reviewTitle">变更预览</h2>
    <div class="sub" id="reviewSub">确认无误后点「确认下发」，手机约 60 秒内应用</div>
    <div class="diff-list" id="diffList"></div>
    <div class="two">
      <button class="b-gho" type="button" id="btnBack">返回修改</button>
      <button class="b-pri" type="button" id="btnConfirm">确认下发</button>
    </div>
  </div>
</div>

<script>
var INIT = {{.Initial}};
var STATE_LABEL = {white:"放行", gray:"配额", deny:"禁止"};
function $(s,r){return (r||document).querySelector(s);}
function $all(s,r){return Array.prototype.slice.call((r||document).querySelectorAll(s));}

/* ---- 结束时间 24:00 开关：time 控件不接受 24:00，用勾选代替 ---- */
var endTime=$("#winEndTime"), endField=$("#winEndField"), end24=$("#winEnd24");
function syncEnd(){
  end24.checked ? (endTime.disabled=true, endField.value="24:00")
                : (endTime.disabled=false, endField.value=endTime.value||"00:00");
}
end24.onchange=function(){syncEnd();};
endTime.onchange=function(){if(!end24.checked) endField.value=endTime.value;};
syncEnd();

/* ---- 应用搜索 / 状态筛选 ---- */
var rows=$all(".approw"), filter="all", query="";
function rowState(r){var p=r.getAttribute("data-pkg");
  return $all('input[name="st_'+p+'"]', r).filter(function(i){return i.checked;})[0].value;}
function isChanged(r){var p=r.getAttribute("data-pkg");
  var cur=rowState(r);
  if(cur!==INIT.apps[p].s) return true;
  return cur==="gray" && String($('input[name="q_'+p+'"]',r).value)!==String(INIT.apps[p].q);}
function refreshCounts(){
  var c={all:rows.length,white:0,gray:0,deny:0,changed:0};
  rows.forEach(function(r){c[rowState(r)]++; if(isChanged(r)) c.changed++;});
  $("#cntAll").textContent=c.all;$("#cntWhite").textContent=c.white;
  $("#cntGray").textContent=c.gray;$("#cntDeny").textContent=c.deny;
  $("#cntChanged").textContent=c.changed;
}
function applyView(){
  rows.forEach(function(r){
    var hit=!query || r.getAttribute("data-label").toLowerCase().indexOf(query)>=0
              || r.getAttribute("data-pkg").toLowerCase().indexOf(query)>=0;
    var okF=filter==="all" ? true : filter==="changed" ? isChanged(r) : rowState(r)===filter;
    r.classList.toggle("hidden-app", !(hit&&okF));
  });
  refreshCounts();
}
$("#appSearch").addEventListener("input",function(){query=this.value.trim().toLowerCase();applyView();});
$all("#appFilters input").forEach(function(i){i.onchange=function(){filter=i.value;applyView();};});
document.addEventListener("change",function(e){if(e.target.name&&e.target.name.indexOf("st_")===0){applyView();}},true);
document.addEventListener("input",function(e){if(e.target.name&&e.target.name.indexOf("q_")===0){refreshCounts();}});
applyView();

/* ---- 变更预览 ---- */
function curAppState(pkg){return $all('input[name="st_'+pkg+'"]').filter(function(i){return i.checked;})[0].value;}
function buildDiff(){
  var d=[];
  function add(title,what){d.push({title:title,what:what});}
  var master=$('input[name=master]').checked, guard=$('input[name=guard]').checked;
  if(master!==INIT.master) add("限时开关", (INIT.master?"开 → 关":"关 → 开"));
  var ws=$('input[name=winStart]').value||"00:00";
  if(ws!==INIT.winStart) add("开始时间", INIT.winStart+" → "+ws);
  syncEnd();
  if(endField.value!==INIT.winEnd) add("结束时间", INIT.winEnd+" → "+endField.value);
  if(guard!==INIT.guard) add("看守系统设置", (INIT.guard?"开 → 关":"关 → 开"));
  var cu=String($('input[name=coolUse]').value||""), ci=String($('input[name=coolIval]').value||"");
  if(cu!==String(INIT.coolUse)) add("应急每次可用", INIT.coolUse+" → "+cu+" 分钟");
  if(ci!==String(INIT.coolIval)) add("应急冷却间隔", INIT.coolIval+" → "+ci+" 分钟");
  rows.forEach(function(r){
    var pkg=r.getAttribute("data-pkg"), cur=curAppState(pkg), ini=INIT.apps[pkg];
    if(cur!==ini.s){
      add(r.getAttribute("data-label"), STATE_LABEL[ini.s]+"<span class='arrow'>→</span>"+STATE_LABEL[cur]);
    }
    if(cur==="gray"){var q=$('input[name="q_'+pkg+'"]',r).value;
      if(String(q)!==String(ini.q)) add(r.getAttribute("data-label")+" 配额", ini.q+" → "+q+" 分钟");}
  });
  return d;
}
$("#btnReview").onclick=function(){
  var d=buildDiff();
  var list=$("#diffList");
  if(!d.length){
    $("#reviewTitle").textContent="没有修改";
    $("#reviewSub").textContent="当前表单与已下发配置一致，无需下发";
    list.innerHTML="";
    $("#btnConfirm").style.display="none";
  }else{
    $("#reviewTitle").textContent="将下发 "+d.length+" 项变更";
    $("#reviewSub").textContent="手机拿到后无条件应用，请逐条确认";
    list.innerHTML=d.map(function(x){
      return '<div class="diff-item"><div>'+x.title+'</div><div class="what">'+x.what+'</div></div>';}).join("");
    $("#btnConfirm").style.display="";
  }
  $("#review").classList.add("show");
};
$("#btnBack").onclick=function(){$("#review").classList.remove("show");};
$("#btnConfirm").onclick=function(){$("#cfgForm").submit();};
</script>` + navHTML + swReg + `</body></html>`))

var tplLogin = template.Must(template.New("l").Parse(headHTML + `
<div class="wrap" style="padding-top:18vh;max-width:380px">
  <div style="text-align:center;margin-bottom:22px">
    <img src="/icon-192.png" width="64" height="64" alt="" style="border-radius:16px">
    <h1 style="font-size:19px;margin:12px 0 2px">屏幕限时守护</h1>
    <div style="color:var(--low);font-size:12.5px">远程授权与配置</div>
  </div>
  {{if .Err}}<div class="bad">{{.Err}}</div>{{end}}
  <form method="post" action="/login" class="stack">
    <input type="text" name="user" placeholder="用户名" autocomplete="username" required>
    <input type="password" name="password" placeholder="密码" autocomplete="current-password" required>
    <button class="b-pri" type="submit">登录</button>
  </form>
</div>` + swReg + `</body></html>`))

func renderLogin(w http.ResponseWriter, errMsg string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_ = tplLogin.Execute(w, map[string]any{"Title": "登录 · 屏幕限时守护", "Err": errMsg})
}

type pageReq struct{ ID, Pkg, App, Age, State string }

func (s *server) handleIndex(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	s.st.mu.Lock()
	s.st.gc()
	var list []pageReq
	pending := 0
	for _, q := range s.st.d.Reqs {
		if q.State == statePending {
			pending++
		}
		list = append(list, pageReq{
			ID: q.ID, Pkg: q.Pkg, App: q.App,
			Age:   fmtAge(time.Since(q.Created)),
			State: string(q.State),
		})
	}
	s.st.save()
	s.st.mu.Unlock()
	sort.Slice(list, func(i, j int) bool { return list[i].ID < list[j].ID })

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_ = tplIndex.Execute(w, map[string]any{
		"Title": "待审批 · 屏幕限时守护", "Tab": "req",
		"Reqs": list, "CSRF": s.csrf, "Pending": pending,
	})
}

func (s *server) handleConfigPage(w http.ResponseWriter, r *http.Request) {
	v := s.loadCfgView()
	v.Title = "规则配置 · 屏幕限时守护"
	v.Tab = "cfg"
	v.CSRF = s.csrf
	v.Saved = r.URL.Query().Get("saved") == "1"
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_ = tplConfig.Execute(w, v)
}

func intOr(p *int, def int) int {
	if p == nil {
		return def
	}
	return *p
}
