// ===================== 全局状态 =====================
var panelExited = false;
var dashPollTimer = null;
var dashUptimeTimer = null;
var instanceListTimer = null;
var currentTab = 'instances';
var accessibleNodeCount = -1;
var currentInstanceType = 'mc';
var chartRange = 'live';

var currentNodeId = 'local';
var currentInstanceId = '';

(function patchFetch() {
  var _orig = window.fetch;
  window.fetch = function (url, opts) {
    try {
      if (typeof url === 'string' && url.indexOf('/api/') === 0) {
        var isGlobal = url.indexOf('/api/account') === 0 || url.indexOf('/api/userdata') === 0 || 
                       url.indexOf('/api/users') === 0 || 
                       url.indexOf('/api/system') === 0 || url.indexOf('/api/smtp') === 0 || 
                       url.indexOf('/api/plugin-pages') === 0 || url.indexOf('/api/plugin-api') === 0 ||
                       url.indexOf('/api/instances/current') === 0;
        if (!isGlobal) {
          if (currentInstanceId && url.indexOf('instance=') < 0) {
            var sep2 = url.indexOf('?') >= 0 ? '&' : '?';
            url = url + sep2 + 'instance=' + encodeURIComponent(currentInstanceId);
          }
        }
      }
    } catch (e) {}
    return _orig.call(this, url, opts);
  };
})();

// ===================== 基础工具补齐 =====================
function pathJoin() {
  var parts = [];
  for (var i = 0; i < arguments.length; i++) {
    var p = arguments[i];
    if (p === null || p === undefined || p === '') continue;
    parts.push(String(p));
  }
  var joined = parts.join('/').replace(/\/+/g, '/');
  if (joined.length > 1) joined = joined.replace(/\/$/, '');
  return joined || '/';
}

function b64enc(s) {
  try {
    return btoa(unescape(encodeURIComponent(String(s == null ? '' : s))));
  } catch (e) { return ''; }
}

function toast(msg, type) {
  var box = document.getElementById('toast-box');
  if (!box) return;
  var el = document.createElement('div');
  el.className = 'toast' + (type ? ' ' + type : '');
  el.textContent = msg;
  box.appendChild(el);
  requestAnimationFrame(function () { el.classList.add('show'); });
  setTimeout(function () {
    el.classList.remove('show');
    setTimeout(function () { el.remove(); }, 280);
  }, 2400);
}

function postApi(url, data) {
  return fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify(data || {})
  }).then(function (r) {
    return r.text().then(function (t) {
      try { return JSON.parse(t); } catch (e) { return t; }
    });
  });
}

function fmtSize(b) {
  b = Number(b);
  if (!b || b < 0 || isNaN(b)) return '0 B';
  if (b < 1024) return b + ' B';
  if (b < 1024 * 1024) return (b / 1024).toFixed(1) + ' KB';
  if (b < 1024 * 1024 * 1024) return (b / 1024 / 1024).toFixed(1) + ' MB';
  return (b / 1024 / 1024 / 1024).toFixed(2) + ' GB';
}

function fmtUptime(ms) {
  if (!ms || ms < 0) return '-';
  var s = Math.floor(ms / 1000);
  var d = Math.floor(s / 86400), h = Math.floor(s % 86400 / 3600),
      m = Math.floor(s % 3600 / 60), sec = s % 60;
  return (d ? d + 'd ' : '') + (h ? h + 'h ' : '') + (m ? m + 'm ' : '') + sec + 's';
}

function escapeHtml(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function copyToClipboard(text) {
  return new Promise(function (resolve, reject) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(resolve).catch(reject);
    } else {
      try {
        var ta = document.createElement('textarea');
        ta.value = text;
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        ta.remove();
        resolve();
      } catch (e) { reject(e); }
    }
  });
}

function uiConfirm(opts) {
  opts = opts || {};
  return new Promise(function (resolve) {
    var m = document.getElementById('ui-modal');
    if (!m) { resolve(window.confirm(opts.message || '')); return; }

    _uiModalKind = 'confirm';
    _uiModalGetter = null;
    _uiModalResolve = resolve;

    document.getElementById('ui-modal-title').textContent = opts.title || '确认操作';
    var body = document.getElementById('ui-modal-body');
    body.innerHTML = '';
    var p = document.createElement('div');
    p.className = 'ui-message';
    p.textContent = opts.message || '';
    body.appendChild(p);

    var okBtn = document.getElementById('ui-modal-ok');
    var cancelBtn = document.getElementById('ui-modal-cancel');
    okBtn.textContent = opts.okText || '确定';
    cancelBtn.textContent = opts.cancelText || '取消';
    okBtn.className = 'btn ' + (opts.danger ? 'danger' : 'primary');
    cancelBtn.style.display = '';

    okBtn.onclick = function () { _uiModalFinish(true); };
    cancelBtn.onclick = function () { _uiModalFinish(false); };

    m.classList.add('show');
    setTimeout(function () { okBtn.focus(); }, 30);
  });
}

function uiPrompt(opts) {
  opts = opts || {};
  return new Promise(function (resolve) {
    var m = document.getElementById('ui-modal');
    if (!m) { resolve(window.prompt(opts.label || '', opts.value || '')); return; }

    _uiModalKind = 'prompt';
    _uiModalResolve = resolve;

    document.getElementById('ui-modal-title').textContent = opts.title || '输入';
    var body = document.getElementById('ui-modal-body');
    body.innerHTML = '';

    if (opts.label) {
      var lbl = document.createElement('label');
      lbl.textContent = opts.label;
      body.appendChild(lbl);
    }
    var input = document.createElement('input');
    input.type = 'text';
    input.value = opts.value != null ? opts.value : '';
    input.placeholder = opts.placeholder || '';
    input.className = 'ui-input';
    body.appendChild(input);

    _uiModalGetter = function () { return input.value; };

    var okBtn = document.getElementById('ui-modal-ok');
    var cancelBtn = document.getElementById('ui-modal-cancel');
    okBtn.textContent = opts.okText || '确定';
    cancelBtn.textContent = opts.cancelText || '取消';
    okBtn.className = 'btn primary';
    cancelBtn.style.display = '';

    okBtn.onclick = function () { _uiModalFinish(true); };
    cancelBtn.onclick = function () { _uiModalFinish(false); };

    input.onkeydown = function (e) {
      if (e.key === 'Enter') { e.preventDefault(); _uiModalFinish(true); }
      else if (e.key === 'Escape') { e.preventDefault(); _uiModalFinish(false); }
    };

    m.classList.add('show');
    setTimeout(function () { input.focus(); input.select(); }, 30);
  });
}

var _uiModalResolve = null;
var _uiModalKind = 'confirm';
var _uiModalGetter = null;

function _uiModalFinish(confirmed) {
  var m = document.getElementById('ui-modal');
  if (m) m.classList.remove('show');
  var r = _uiModalResolve;
  var kind = _uiModalKind;
  var getter = _uiModalGetter;
  _uiModalResolve = null;
  _uiModalGetter = null;
  if (!r) return;
  if (!confirmed) {
    r(kind === 'prompt' ? null : false);
    return;
  }
  if (kind === 'prompt') {
    r(getter ? getter() : '');
  } else {
    r(true);
  }
}

// ===================== 权限与侧边栏 =====================
var currentPerms = [];
var currentIsMain = false;
var PERM_MAP = {
  terminal: 'terminal', files: 'files', backup: 'backup', config: 'config',
  users: 'users', plugins: 'plugins', share: 'share', serverprops: 'config',
  installer: 'config', velocitycfg: 'config', 'panel-log': 'panel_log', java: 'java'
};

function hasPerm(p) {
  if (currentIsMain) return true;
  return currentPerms.indexOf(p) >= 0;
}

function updateSidebarVisibility() {
  var inNode = true;
  var inInstance = !!currentInstanceId;

  document.querySelectorAll('.sidebar a[data-tab]').forEach(function (a) {
    if (a.dataset.pluginPage) return;
    var t = a.dataset.tab;

    if (t === 'account') { a.style.display = ''; return; }
    if (t === 'personalization') { a.style.display = ''; return; }
    if (t === 'smtp') { a.style.display = currentIsMain ? '' : 'none'; return; }

    if (t === 'panel-log') {
      var canViewLog = hasPerm('panel_log') || hasPerm('config');
      a.style.display = canViewLog ? '' : 'none';
      return;
    }
    
    if (t === 'panel-plugins') {
      a.style.display = (!inInstance && hasPerm('plugins')) ? '' : 'none';
      return;
    }

    if (t === 'instances') { a.style.display = ''; return; }
    if (t === 'users')     { a.style.display = hasPerm('users') ? '' : 'none'; return; }
    if (t === 'config')    { a.style.display = hasPerm('config') ? '' : 'none'; return; }
    
    if (t === 'files') {
        if (inInstance) {
            a.style.display = (hasPerm('files') || hasPerm('files_all')) ? '' : 'none';
        } else {
            a.style.display = hasPerm('files_all') ? '' : 'none';
        }
        return;
    }
    
    if (t === 'share')     { a.style.display = hasPerm('share') ? '' : 'none'; return; }
    
    if (t === 'remote') {
        if (inInstance) {
            a.style.display = (hasPerm('config') || currentIsMain) ? '' : 'none';
        } else {
            a.style.display = currentIsMain ? '' : 'none';
        }
        return;
    }

    if (t === 'java') {
      a.style.display = (inInstance && (hasPerm('java') || hasPerm('config'))) ? '' : 'none';
      return;
    }
    if (t === 'installer') {
      var isVel = (currentInstanceType === 'velocity');
      a.style.display = (inInstance && hasPerm('config') && !isVel) ? '' : 'none';
      return;
    }
    if (t === 'serverprops') {
      a.style.display = (inInstance && hasPerm('config')) ? '' : 'none';
      return;
    }
    if (!inInstance) { a.style.display = 'none'; return; }
    var need = PERM_MAP[t];
    a.style.display = (!need || hasPerm(need)) ? '' : 'none';
  });
}

// ===================== 背景与初始化 =====================
function loadPersonalization() {
  var defaultBg = '/api/system/default-bg?type=panel&t=' + Date.now();
  document.body.style.backgroundImage = "linear-gradient(rgba(13,16,23,0.85), rgba(13,16,23,0.85)), url('" + defaultBg + "')";
  document.body.style.backgroundSize = 'cover';
  document.body.style.backgroundPosition = 'center';
  document.body.style.backgroundAttachment = 'fixed';

  fetch('/api/userdata/get').then(r => r.json()).then(d => {
    var avatarSrc = d.hasAvatar ? '/api/userdata/avatar?t=' + Date.now() : '/icons/default.png';
    var footerAvatar = document.getElementById('footer-avatar');
    var accAvatarPreview = document.getElementById('acc-avatar-preview');
    if (footerAvatar) footerAvatar.src = avatarSrc;
    if (accAvatarPreview) accAvatarPreview.src = avatarSrc;

    if (d.hasBg) {
      var userBg = '/api/userdata/bg?t=' + Date.now();
      document.body.style.backgroundImage = "linear-gradient(rgba(13,16,23,0.85), rgba(13,16,23,0.85)), url('" + userBg + "')";
    }

    if (d.hasFont) {
      var fStyle = document.createElement('style');
      fStyle.innerHTML = '@font-face { font-family: "CustomFont"; src: url("/api/userdata/font"); } body, .sp-input, .term-input { font-family: "CustomFont", "Inter", sans-serif !important; }';
      document.head.appendChild(fStyle);
    }

    if (d.hasCss) {
      var link = document.createElement('link');
      link.rel = 'stylesheet';
      link.href = '/api/userdata/css?t=' + Date.now();
      document.head.appendChild(link);
    }

    if (currentIsMain) {
      var sysCard = document.getElementById('sys-default-bg-card');
      if (sysCard) sysCard.style.display = 'block';
    }
  }).catch(e => console.error("加载用户数据失败", e));
}

// ===================== SPA 路由核心 =====================
function parseUrlAndState() {
  var pathParts = window.location.pathname.split('/').filter(p => p);
  var urlTab = 'instances';
  
  var globalTabs = ['account', 'smtp', 'personalization', 'users', 'config', 'share', 'files', 'panel-log', 'panel-plugins', 'remote', 'instances'];
  var instTabs = ['terminal', 'backup', 'serverprops', 'java', 'installer', 'velocitycfg', 'plugins', 'files', 'remote'];

  currentInstanceId = '';

  if (pathParts.length > 0) {
    if (globalTabs.indexOf(pathParts[0]) >= 0) {
      urlTab = pathParts[0];
    } else {
      if (pathParts.length > 1) {
        if (instTabs.indexOf(pathParts[0]) >= 0) {
          urlTab = pathParts[0];
        } else {
          currentInstanceId = pathParts[0];
          urlTab = pathParts[1] || 'terminal';
        }
      }
    }
  }

  var finalUrl = '/';
  if (urlTab === 'instances') {
    currentInstanceId = '';
    finalUrl = '/instances';
  } else {
    if (instTabs.indexOf(urlTab) >= 0 && !currentInstanceId && urlTab !== 'files' && urlTab !== 'remote') {
      window.location.href = '/instances';
      return 'instances';
    }
    finalUrl = '/' + (currentInstanceId ? currentInstanceId + '/' : '') + urlTab;
  }

  if (window.location.pathname !== finalUrl) {
    window.history.replaceState(null, '', finalUrl);
  }

  return urlTab;
}

function navigateTo(url) {
  if (window.location.pathname === url) return;
  window.history.pushState(null, '', url);
  var tab = parseUrlAndState();
  updateSidebarVisibility();
  switchTab(tab);
}

window.addEventListener('popstate', function() {
  var tab = parseUrlAndState();
  updateSidebarVisibility();
  switchTab(tab);
});

var _appInited = false;
function initApp() {
  if (_appInited) return;
  _appInited = true;

  var urlTab = parseUrlAndState();

  // ★ 核心修复：先拉取账户信息，确认 isMain，再初始化个性化设置
  fetch('/api/account/info').then(r => r.json()).then(d => {
    currentPerms = d.perms || [];
    currentIsMain = !!d.isMain;
    var un = document.getElementById('footer-username');
    if (un) un.textContent = d.username || '用户';
    
    loadPersonalization();
  }).catch(() => {
    loadPersonalization();
  }).finally(() => {
    fetch('/api/instances/current').then(r => r.json()).catch(() => ({})).then(inst => {
      if (inst && inst.type) currentInstanceType = inst.type;

      updateSidebarVisibility();
      switchTab(urlTab);

      if (typeof loadPluginPages === 'function') loadPluginPages();
      if (typeof ensurePluginEventSocket === 'function') ensurePluginEventSocket();
    });
  });
}

// ===================== 标签页切换 =====================
function switchTab(tab) {
  if (panelExited) return;
  var isPluginTab = (tab.indexOf('__plugin__') === 0);

  currentTab = tab;

  var targetUrl = '/';
  if (tab === 'instances') {
    targetUrl = '/instances';
  } else {
    targetUrl = '/' + (currentInstanceId ? currentInstanceId + '/' : '') + tab;
  }
  if (window.location.pathname !== targetUrl) {
    window.history.replaceState(null, '', targetUrl);
  }

  document.querySelectorAll('.sidebar a').forEach(function (x) {
    var xTab = x.dataset.pluginPage ? ('__plugin__' + x.dataset.pluginPage) : x.dataset.tab;
    x.classList.toggle('active', xTab === tab);
  });
  
  document.querySelectorAll('div[id^="tab-"]').forEach(function (d) { 
    d.style.display = 'none'; 
    d.style.backgroundColor = 'transparent';
  });
  
  var el = document.getElementById('tab-' + tab);
  if (el) {
    el.style.display = 'block';
  }

  if (isPluginTab) {
    if (typeof ensurePluginFrame === 'function') ensurePluginFrame(tab);
    return;
  }

  try {
    if (tab === 'panel-log' && typeof initPanelLog === 'function') initPanelLog();
    if (tab === 'smtp' && typeof smtpLoad === 'function') smtpLoad();
    if (tab === 'instances' && typeof instanceListRefresh === 'function') instanceListRefresh(true);
    if (tab === 'terminal' && hasPerm('terminal')) { 
        if (typeof initTerminal === 'function') initTerminal(); 
        if (typeof termLoadSettings === 'function') termLoadSettings(); 
        if (typeof initLiveChartData === 'function') initLiveChartData(); 
        if (typeof loadDashboard === 'function') loadDashboard(); 
    }
    if (tab === 'files' && (hasPerm('files') || hasPerm('files_all'))) { 
        if (typeof filesInitPath === 'function') filesInitPath(); 
        if (typeof filesRefresh === 'function') filesRefresh(); 
    }
    if (tab === 'backup' && hasPerm('backup') && typeof backupRefresh === 'function') backupRefresh();
    if (tab === 'config' && hasPerm('config') && typeof cfgRefresh === 'function') cfgRefresh();
    if (tab === 'serverprops' && hasPerm('config') && typeof spRefresh === 'function') spRefresh();
    if (tab === 'java' && (hasPerm('java') || hasPerm('config')) && typeof javaRefresh === 'function') javaRefresh();
    if (tab === 'users' && hasPerm('users') && typeof usersRefresh === 'function') usersRefresh();
    
    if (tab === 'share' && hasPerm('share')) {
        if (typeof shareRefresh === 'function') shareRefresh();
        if (typeof shareRefreshLogs === 'function') shareRefreshLogs();
    }
    
    if (tab === 'remote' && typeof remoteInit === 'function') remoteInit();
    
    if (tab === 'plugins' && hasPerm('plugins') && typeof pluginsRefresh === 'function') pluginsRefresh();
    if (tab === 'panel-plugins' && hasPerm('plugins') && typeof renderPanelPluginsPage === 'function') renderPanelPluginsPage();
    if (tab === 'account' && typeof accLoad === 'function') accLoad();
  } catch (e) {
    console.error("Tab switch error: ", e);
  }
}

// ===================== 图表 =====================
var dashRunning = false, dashUptimeAtFetch = 0, dashFetchedAt = 0;
var liveData = { cpu: [], mem: [], in: [], out: [], cpuTimes: [], memTimes: [], inTimes: [], outTimes: [] };
var historyData = { cpu: [], mem: [], in: [], out: [], cpuTimes: [], memTimes: [], inTimes: [], outTimes: [] };
var maxChartPoints = 60;
var _chartInit = false;

function fetchLiveChartData() {
  if (panelExited || !currentInstanceId) return;
  fetch('/api/dashboard/history?range=live').then(r => r.json()).then(data => {
    if (panelExited) return;
    if (Array.isArray(data) && data.length > 0) {
      liveData = { cpu: [], mem: [], in: [], out: [], cpuTimes: [], memTimes: [], inTimes: [], outTimes: [] };
      data.forEach(pt => {
        var mPct = pt.memTotal > 0 ? (pt.mem / pt.memTotal * 100) : 0;
        if (mPct > 100) mPct = 100;
        liveData.cpu.push(pt.cpu); liveData.cpuTimes.push(pt.time);
        liveData.mem.push(mPct); liveData.memTimes.push(pt.time);
        liveData.in.push(pt.netIn / 1024); liveData.inTimes.push(pt.time);
        liveData.out.push(pt.netOut / 1024); liveData.outTimes.push(pt.time);
      });
    } else {
      liveData = { cpu: [], mem: [], in: [], out: [], cpuTimes: [], memTimes: [], inTimes: [], outTimes: [] };
    }
    if (chartRange === 'live') drawChart();
  }).catch(()=>{});
}

function initLiveChartData() {
  if (_chartInit) return;
  _chartInit = true;
  fetchLiveChartData();
}

function switchChartRange(range) {
  chartRange = range;
  document.querySelectorAll('.toolbar .btn[id^="btn-chart-"]').forEach(function(b) { b.classList.remove('primary'); });
  var btn = document.getElementById('btn-chart-' + range);
  if (btn) btn.classList.add('primary');
  
  if (range === 'live') {
    fetchLiveChartData();
  } else {
    fetch('/api/dashboard/history?range=' + range).then(r => r.json()).then(data => {
      if (!Array.isArray(data) || data.length === 0) {
        toast('暂无足够的历史数据', 'err');
        historyData = { cpu: [], mem: [], in: [], out: [], cpuTimes: [], memTimes: [], inTimes: [], outTimes: [] };
        drawChart();
        return;
      }
      historyData = { cpu: [], mem: [], in: [], out: [], cpuTimes: [], memTimes: [], inTimes: [], outTimes: [] };
      data.forEach(pt => {
        var mPct = pt.memTotal > 0 ? (pt.mem / pt.memTotal * 100) : 0;
        if (mPct > 100) mPct = 100;
        historyData.cpu.push(pt.cpu); historyData.cpuTimes.push(pt.time);
        historyData.mem.push(mPct); historyData.memTimes.push(pt.time);
        historyData.in.push(pt.netIn / 1024); historyData.inTimes.push(pt.time);
        historyData.out.push(pt.netOut / 1024); historyData.outTimes.push(pt.time);
      });
      drawChart();
    }).catch(() => toast('获取历史数据失败', 'err'));
  }
}

function drawChart() {
  var isLive = (chartRange === 'live');
  var dataToUse = isLive ? liveData : historyData;
  
  var now = Date.now();
  var span = 24 * 3600 * 1000;
  if (chartRange === 'week') span = 7 * 24 * 3600 * 1000;
  var startTime = now - span;

  drawSingleChart('chart-cpu', dataToUse.cpu, dataToUse.cpuTimes, '#7aa2f7', 100, isLive, startTime, now);
  drawSingleChart('chart-mem', dataToUse.mem, dataToUse.memTimes, '#3ddc84', 100, isLive, startTime, now);
  
  var maxNet = 10;
  dataToUse.in.forEach(v => { if (v > maxNet) maxNet = v; });
  dataToUse.out.forEach(v => { if (v > maxNet) maxNet = v; });
  
  drawSingleChart('chart-netin', dataToUse.in, dataToUse.inTimes, '#f0c040', maxNet, isLive, startTime, now);
  drawSingleChart('chart-netout', dataToUse.out, dataToUse.outTimes, '#e94560', maxNet, isLive, startTime, now);
}

function drawSingleChart(id, data, times, color, maxVal, isLive, startTime, endTime) {
  try {
    var canvas = document.getElementById(id);
    if (!canvas) return;
    var ctx = canvas.getContext('2d');
    var dpr = window.devicePixelRatio || 1;
    var rect = canvas.getBoundingClientRect();
    if (rect.width === 0 || rect.height === 0) return;

    canvas.width = rect.width * dpr;
    canvas.height = rect.height * dpr;
    ctx.scale(dpr, dpr);

    var w = rect.width;
    var h = rect.height;
    ctx.clearRect(0, 0, w, h);

    if (!maxVal || maxVal <= 0) maxVal = 1;

    ctx.strokeStyle = 'rgba(255,255,255,0.05)';
    ctx.lineWidth = 1;
    for (var i = 1; i < 4; i++) {
      var y = h - (h / 4) * i;
      ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke();
    }

    if (data.length === 0) return;
    if (data.length < 2) {
      var y = data.length === 1 ? h - (data[0] / maxVal) * h : h;
      ctx.beginPath(); ctx.strokeStyle = color; ctx.lineWidth = 2;
      ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke();
      return;
    }

    function getX(i) {
      if (isLive) {
        var stepX = w / (maxChartPoints - 1);
        return w - (data.length - 1) * stepX + i * stepX;
      } else {
        var t = times[i] || endTime;
        var x = ((t - startTime) / (endTime - startTime)) * w;
        return Math.max(0, Math.min(w, x));
      }
    }

    ctx.beginPath();
    if (isLive) ctx.moveTo(getX(0), h);
    
    for (var i = 0; i < data.length; i++) {
      var x = getX(i);
      var y = h - (data[i] / maxVal) * h;
      if (y < 0) y = 0;
      if (i === 0 && !isLive) ctx.moveTo(x, h);
      ctx.lineTo(x, y);
    }
    ctx.lineTo(getX(data.length - 1), h);
    ctx.closePath();
    ctx.fillStyle = color + '33';
    ctx.fill();

    ctx.beginPath();
    ctx.strokeStyle = color;
    ctx.lineWidth = 2;
    for (var i = 0; i < data.length; i++) {
      var x = getX(i);
      var y = h - (data[i] / maxVal) * h;
      if (y < 0) y = 0;
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }
    ctx.stroke();
  } catch(e) { console.error('图表绘制失败', e); }
}

window.addEventListener('resize', drawChart);

function loadDashboard() {
  if (panelExited || !currentInstanceId) return;
  
  fetch('/api/dashboard').then(function (r) {
    if (!r.ok) throw new Error("HTTP " + r.status);
    return r.json();
  }).then(function (d) {
    if (panelExited) return;
    if (d && d.error) throw new Error(d.error);

    dashRunning = !!d.running;
    dashUptimeAtFetch = d.uptime || 0;
    dashFetchedAt = Date.now();

    var isStopped = (d.state === 'stopped');

    var el = document.getElementById('d-running');
    if (el) {
      if (d.installing) { el.textContent = '安装中...'; el.style.color = '#7aa2f7'; }
      else if (d.needsEula) { el.textContent = '需要同意 EULA'; el.style.color = '#f0c040'; }
      else if (d.starting) { el.textContent = '启动中...'; el.style.color = '#f0c040'; }
      else if (dashRunning) { el.textContent = '运行中'; el.style.color = '#3ddc84'; }
      else { el.textContent = '已停止'; el.style.color = '#ff5c5c'; }
    }

    var p = document.getElementById('d-pid');
    if (p) p.textContent = isStopped ? '-' : (d.pid > 0 ? d.pid : '-');
    
    var u = document.getElementById('d-uptime');
    if (u) u.textContent = isStopped ? '-' : fmtUptime(dashUptimeAtFetch);
    
    var c = document.getElementById('d-cpu');
    if (c) {
      if (isStopped) {
        c.textContent = '-';
      } else {
        var cpuVal = Number(d.cpuLoad);
        if (isNaN(cpuVal)) cpuVal = 0;
        c.textContent = cpuVal.toFixed(1) + '%';
      }
    }
    
    var m = document.getElementById('d-mem');
    if (m) {
      if (isStopped) {
        m.textContent = '-';
      } else {
        var memText;
        if (d.memTotal > 0) memText = fmtSize(d.memUsed) + ' / ' + fmtSize(d.memTotal);
        else memText = fmtSize(d.memUsed) + ' / 未设置';
        m.textContent = memText;
      }
    }
    var dk = document.getElementById('d-disk');
    if (dk) dk.textContent = fmtSize(d.diskUsed); 

    var ni = document.getElementById('d-netin');
    if (ni) ni.textContent = isStopped ? '-' : fmtSize(d.netIn || 0) + '/s';
    var no = document.getElementById('d-netout');
    if (no) no.textContent = isStopped ? '-' : fmtSize(d.netOut || 0) + '/s';

    if (typeof updatePowerButton === 'function') {
      updatePowerButton(dashRunning, !!d.starting, !!d.needsEula, !!d.installing);
    }
    
    if (chartRange === 'live') {
      fetchLiveChartData();
    }

  }).catch(function (e) { 
    console.error("仪表盘加载失败:", e);
    var el = document.getElementById('d-running');
    if (el) { el.textContent = '获取失败'; el.style.color = '#ff5c5c'; }
  });
}

dashPollTimer = setInterval(function () {
  if (panelExited) return;
  if (currentTab === 'terminal' && hasPerm('terminal') && currentInstanceId) loadDashboard();
}, 1000);

dashUptimeTimer = setInterval(function () {
  if (panelExited || !dashRunning) return;
  var est = dashUptimeAtFetch + (Date.now() - dashFetchedAt);
  var el = document.getElementById('d-uptime');
  if (el) el.textContent = fmtUptime(est);
}, 1000);

// ===================== 侧边栏绑定 =====================
document.querySelectorAll('.sidebar a[data-tab]').forEach(function (a) {
  a.onclick = function (e) { 
    e.preventDefault();
    var tab = a.dataset.tab;
    if (tab === 'instances') {
        navigateTo('/instances');
    } else {
        var instTabs = ['terminal', 'backup', 'serverprops', 'java', 'installer', 'velocitycfg', 'plugins'];
        
        if (!currentInstanceId) {
            if (tab === 'files') {
                navigateTo('/files');
                return;
            }
            if (instTabs.indexOf(tab) >= 0) {
                toast('请先进入一个实例', 'err');
                navigateTo('/instances');
                return;
            }
        }
        
        navigateTo('/' + (currentInstanceId ? currentInstanceId + '/' : '') + tab);
    }
  };
});

// ===================== 启动 =====================
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initApp);
} else {
  initApp();
}

// ===================== 弹窗 (Modal) =====================
var _modalSaveCallback = null;

function openModal(title, bodyHtml, onSave, saveText) {
  var m = document.getElementById('edit-modal');
  if (!m) return;
  
  document.getElementById('edit-modal-title').textContent = title || '编辑';
  var body = document.getElementById('edit-modal-body');
  body.innerHTML = bodyHtml || '';
  
  var saveBtn = document.getElementById('edit-modal-save');
  if (saveBtn) {
    saveBtn.textContent = saveText || '保存';
    saveBtn.style.display = onSave ? 'inline-flex' : 'none';
  }
  
  var headerClose = m.querySelector('.edit-modal-header .btn');
  if (headerClose) headerClose.style.display = '';
  
  var footerBtns = m.querySelectorAll('.edit-modal-footer .btn');
  if (footerBtns.length >= 2) {
    footerBtns[1].style.display = '';
    footerBtns[1].textContent = '取消';
    footerBtns[1].onclick = closeModal;
  }
  
  _modalSaveCallback = onSave;
  m.classList.add('show');
}

function closeModal() {
  var m = document.getElementById('edit-modal');
  if (m) m.classList.remove('show');
  _modalSaveCallback = null;
}

function modalSave() {
  if (typeof _modalSaveCallback === 'function') {
    _modalSaveCallback();
  } else {
    closeModal();
  }
}

// ===================== 退出遮罩层 (Exit Overlay) =====================
function showExitOverlay(icon, title, desc) {
  var overlay = document.getElementById('panel-exit-overlay');
  if (!overlay) return;
  
  var iconEl = overlay.querySelector('.icon');
  if (iconEl) iconEl.textContent = icon === 'shutting' ? '⏳' : '✓';
  
  var titleEl = overlay.querySelector('h2');
  if (titleEl) titleEl.textContent = title || '面板已退出';
  
  var pEl = overlay.querySelector('p');
  if (pEl) pEl.innerHTML = desc || '后端进程已关闭，所有轮询已停止。<br>你可以安全地关闭此标签页了。';
  
  overlay.classList.add('show');
}

// ===================== 头像 / 个性化 =====================
function accUploadAvatar(input) {
  if (!input.files || !input.files[0]) return;
  var file = input.files[0];
  if (file.size > 5 * 1024 * 1024) { toast('头像不能超过 5MB', 'err'); input.value = ''; return; }
  toast('正在上传头像...');
  fetch('/api/userdata/upload-avatar', { method: 'POST', body: file })
    .then(r => r.json())
    .then(d => {
      if (d && d.ok) {
        toast('头像已更新', 'ok');
        var ts = '?t=' + Date.now();
        var f = document.getElementById('footer-avatar');
        if (f) f.src = '/api/userdata/avatar' + ts;
        var a = document.getElementById('acc-avatar-preview');
        if (a) a.src = '/api/userdata/avatar' + ts;
      } else toast((d && d.error) || '上传失败', 'err');
    })
    .catch(() => toast('网络错误', 'err'));
  input.value = '';
}

function perUploadBg(input) {
  if (!input.files || !input.files[0]) return;
  var file = input.files[0];
  if (file.size > 20 * 1024 * 1024) { toast('背景图不能超过 20MB', 'err'); input.value = ''; return; }
  toast('正在上传背景图...');
  fetch('/api/userdata/upload-bg', { method: 'POST', body: file })
    .then(r => r.json())
    .then(d => {
      if (d && d.ok) {
        toast('背景已更新', 'ok');
        var bg = '/api/userdata/bg?t=' + Date.now();
        document.body.style.backgroundImage = "linear-gradient(rgba(13,16,23,0.85), rgba(13,16,23,0.85)), url('" + bg + "')";
      } else toast((d && d.error) || '上传失败', 'err');
    })
    .catch(() => toast('网络错误', 'err'));
  input.value = '';
}

function perRemoveBg() {
  postApi('/api/userdata/remove', { target: 'bg' }).then(d => {
    if (d && d.ok) { toast('已恢复默认背景', 'ok'); location.reload(); }
    else toast('操作失败', 'err');
  });
}

function perUploadFont(input) {
  if (!input.files || !input.files[0]) return;
  var file = input.files[0];
  if (file.size > 5 * 1024 * 1024) { toast('字体不能超过 5MB', 'err'); input.value = ''; return; }
  toast('正在上传字体...');
  fetch('/api/userdata/upload-font', { method: 'POST', body: file })
    .then(r => r.json())
    .then(d => {
      if (d && d.ok) { toast('字体已更新，刷新后生效', 'ok'); setTimeout(() => location.reload(), 1000); }
      else toast((d && d.error) || '上传失败', 'err');
    })
    .catch(() => toast('网络错误', 'err'));
  input.value = '';
}

function perRemoveFont() {
  postApi('/api/userdata/remove', { target: 'font' }).then(d => {
    if (d && d.ok) { toast('已恢复默认字体', 'ok'); location.reload(); }
    else toast('操作失败', 'err');
  });
}

function perUploadCss(input) {
  if (!input.files || !input.files[0]) return;
  var file = input.files[0];
  if (file.size > 100 * 1024) { toast('CSS 不能超过 100KB', 'err'); input.value = ''; return; }
  toast('正在上传 CSS...');
  fetch('/api/userdata/upload-css', { method: 'POST', body: file })
    .then(r => r.json())
    .then(d => {
      if (d && d.ok) { toast('CSS 已更新', 'ok'); setTimeout(() => location.reload(), 1000); }
      else toast((d && d.error) || '上传失败', 'err');
    })
    .catch(() => toast('网络错误', 'err'));
  input.value = '';
}

function perRemoveCss() {
  postApi('/api/userdata/remove', { target: 'css' }).then(d => {
    if (d && d.ok) { toast('已清除自定义 CSS', 'ok'); location.reload(); }
    else toast('操作失败', 'err');
  });
}

function uploadSysDefaultBg(input, type) {
  if (!input.files || !input.files[0]) return;
  var file = input.files[0];
  toast('正在上传默认背景...');
  fetch('/api/system/upload-default-bg?type=' + type, { method: 'POST', body: file })
    .then(r => r.json())
    .then(d => {
      if (d && d.ok) { toast('默认背景已更新', 'ok'); setTimeout(() => location.reload(), 1000); }
      else toast((d && d.error) || '上传失败', 'err');
    })
    .catch(() => toast('网络错误', 'err'));
  input.value = '';
}