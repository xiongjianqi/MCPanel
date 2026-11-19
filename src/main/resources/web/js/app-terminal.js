// ===================== 终端 =====================
var ws = null, termReady = false;
var mcRunning = false, mcStarting = false, mcNeedsEula = false, mcInstalling = false;
var lastSeq = 0;

function updatePowerButton(running, starting, needsEula, installing) {
  mcRunning = running;
  mcStarting = !!starting;
  mcNeedsEula = !!needsEula;
  mcInstalling = !!installing;

  var btn = document.getElementById('term-power-btn');
  var restartBtn = document.getElementById('term-restart-btn');
  var killBtn = document.getElementById('term-kill-btn');

  if (restartBtn) restartBtn.disabled = !running && !starting;
  if (killBtn) killBtn.disabled = !running && !starting;

  if (!btn) return;

  if (mcInstalling) {
    btn.textContent = '安装中...';
    btn.className = 'btn';
    btn.disabled = true;
  } else if (mcNeedsEula) {
    btn.textContent = '同意 EULA 并启动';
    btn.className = 'btn primary';
    btn.disabled = false;
  } else if (mcStarting) {
    btn.textContent = '启动中...';
    btn.className = 'btn';
    btn.disabled = true;
  } else if (running) {
    btn.textContent = '关闭';
    btn.className = 'btn danger';
    btn.disabled = false;
  } else {
    btn.textContent = '启动';
    btn.className = 'btn primary';
    btn.disabled = false;
  }
}

function appendLogLine(line) {
  var out = document.getElementById('term-output');
  if (!out) return;

  // 1. 先转义 HTML
  var esc = escapeHtml(line);
  
  // 2. 替换前缀（注意先转义再替换前缀标签，避免注入）
  if (esc.startsWith('[Panel]')) {
    esc = '<span style="color:#7aa2f7;font-weight:bold;">[Panel]</span>' + esc.substring(7);
  } else if (esc.startsWith('[Instance]')) {
    esc = '<span style="color:#ffb86c;font-weight:bold;">[Instance]</span>' + esc.substring(10);
  } else if (esc.startsWith('[ServerProcess]')) {
    esc = '<span style="color:#ffb86c;font-weight:bold;">[ServerProcess]</span>' + esc.substring(15);
  } else if (esc.startsWith('[Plugin]')) {
    esc = '<span style="color:#c678dd;font-weight:bold;">[Plugin]</span>' + esc.substring(8);
  } else if (esc.startsWith('[Console]')) {
    esc = '<span style="color:#7aa2f7;font-weight:bold;">[Console]</span>' + esc.substring(9);
  }

  // 3. 匹配日志级别（如 [INFO], [WARN], [ERROR], /INFO）
  esc = esc.replace(/\[(INFO|WARN|WARNING|ERROR)\]/g, function(m, lvl) {
      var c = lvl === 'INFO' ? 'log-info' : (lvl === 'ERROR' ? 'log-error' : 'log-warn');
      return '<span class="' + c + '">[' + lvl + ']</span>';
  });
  esc = esc.replace(/\/(INFO|WARN|WARNING|ERROR)\]/g, function(m, lvl) {
      var c = lvl === 'INFO' ? 'log-info' : (lvl === 'ERROR' ? 'log-error' : 'log-warn');
      return '<span class="' + c + '">/' + lvl + ']</span>';
  });

  if (out.innerHTML.trim() === '' || out.innerHTML.trim() === '未连接') {
      out.innerHTML = '';
  }
  out.insertAdjacentHTML('beforeend', esc + '\n');
  
  if (typeof chartRange !== 'undefined' && chartRange === 'live') {
    setTimeout(function() { out.scrollTop = out.scrollHeight; }, 0);
  }
}

function initTerminal() {
  if (panelExited) return;
  if (!currentInstanceId) return;
  
  if (ws) {
    if (ws.readyState === WebSocket.CONNECTING || ws.readyState === WebSocket.OPEN) {
      if (ws.url.indexOf('instance=' + encodeURIComponent(currentInstanceId)) < 0) {
        try { ws.close(); } catch(e) {}
        ws = null;
      } else {
        return;
      }
    } else {
      ws = null;
    }
  }

  var outEl = document.getElementById('term-output');
  if (outEl) outEl.innerHTML = '';
  lastSeq = 0;

  var proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
  var curWs = new WebSocket(proto + location.host + '/ws/terminal?since=0&instance=' + encodeURIComponent(currentInstanceId));
  ws = curWs;
  
  curWs.onopen = function () {
    if (ws !== curWs) return;
    var st = document.getElementById('term-status');
    if (st) { st.textContent = '已连接'; st.style.color = '#3ddc84'; }
    termReady = true;
  };
  curWs.onmessage = function (ev) {
    if (ws !== curWs) return;
    var text = ev.data;
    if (text.indexOf('@STATUS@:') === 0) {
      var stt = text.substring('@STATUS@:'.length).trim();
      var stEl = document.getElementById('term-status');
      if (stt === 'installing') {
        if (stEl) { stEl.textContent = '安装中...'; stEl.style.color = '#7aa2f7'; }
        updatePowerButton(false, false, false, true);
      } else if (stt === 'eula') {
        if (stEl) { stEl.textContent = '需要同意 EULA'; stEl.style.color = '#f0c040'; }
        updatePowerButton(false, false, true, false);
      } else if (stt === 'starting') {
        if (stEl) { stEl.textContent = '启动中...'; stEl.style.color = '#f0c040'; }
        updatePowerButton(true, true, false, false);
      } else if (stt === 'running') {
        if (stEl) { stEl.textContent = '运行中'; stEl.style.color = '#3ddc84'; }
        updatePowerButton(true, false, false, false);
      } else {
        if (stEl) { stEl.textContent = '已停止'; stEl.style.color = '#ff5c5c'; }
        updatePowerButton(false, false, false, false);
      }
      return;
    }
    var idx = text.indexOf('|');
    if (idx <= 0) return;
    var seq = parseInt(text.substring(0, idx), 10);
    var line = text.substring(idx + 1);
    if (seq <= lastSeq) return;
    lastSeq = seq;
    appendLogLine(line);
  };
  function handleDisconnect() {
    if (ws !== curWs) return;
    ws = null; termReady = false;
    if (panelExited) return;
    var st = document.getElementById('term-status');
    if (st) { st.textContent = '连接已断开，2 秒后重连'; st.style.color = '#ff5c5c'; }
    setTimeout(function () {
      if (hasPerm('terminal') && !panelExited && currentInstanceId && currentTab === 'terminal') initTerminal();
    }, 2000);
  }
  curWs.onclose = handleDisconnect;
  curWs.onerror = handleDisconnect;
}

function termSend() {
  var inp = document.getElementById('term-input');
  if (!inp) return;
  var cmd = inp.value.trim();
  if (!cmd) return;
  if (mcInstalling) { toast('正在安装服务端，请稍候', 'err'); return; }
  if (!termReady || !ws || ws.readyState !== WebSocket.OPEN) { toast('WebSocket 未连接', 'err'); return; }
  ws.send(cmd); inp.value = '';
}

(function() {
  var ti = document.getElementById('term-input');
  if (ti) {
    ti.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); termSend(); }
    });
  }
})();

function termAgreeEulaAndStart() {
  if (!termReady || !ws || ws.readyState !== WebSocket.OPEN) { toast('WebSocket 未连接，请稍后重试', 'err'); return; }
  toast('正在同意 EULA 并启动...');
  ws.send('Y');
}

function termToggle() {
  try {
    if (mcInstalling) { toast('正在安装服务端，请稍候', 'err'); return; }
    if (mcNeedsEula) { termAgreeEulaAndStart(); return; }
    if (mcStarting) { toast('正在启动中，请稍候', 'err'); return; }
    if (mcRunning) {
      uiConfirm({ title: '关闭', message: '确定关闭当前实例？', danger: true, okText: '关闭' })
      .then(function (ok) { if (ok) termAction('stop'); });
    } else {
      termAction('start');
    }
  } catch(e) { console.error(e); }
}

function termAction(action) {
  try {
    if (mcInstalling) { toast('正在安装服务端，请稍候', 'err'); return; }
    if ((action === 'restart' || action === 'kill') && !mcRunning && !mcStarting) {
      toast('服务器未运行，无法执行此操作', 'err'); return;
    }
    if ((action === 'start' || action === 'restart') && mcNeedsEula) { termAgreeEulaAndStart(); return; }

    if (action === 'kill') {
      uiConfirm({ title: '强制终止', message: '确定强制终止进程？\n\n可能导致数据损坏，仅在其他方法都关不掉时使用。', danger: true, okText: '强制终止' })
      .then(function (ok) { if (ok) postApi('/api/terminal/action', { action: 'kill' }); });
      return;
    }
    if (action === 'restart') {
      uiConfirm({ title: '重启', message: '确定重启当前实例？', okText: '重启' })
      .then(function (ok) { if (ok) postApi('/api/terminal/action', { action: 'restart' }); });
      return;
    }

    postApi('/api/terminal/action', { action: action }).then(function (r) {
      if (r && r.needsEula) {
        updatePowerButton(false, false, true, false);
        toast('需要先同意 EULA 才能启动', 'err');
      } else if (r && r.installing) {
        updatePowerButton(false, false, false, true);
        toast('正在安装服务端，请稍候', 'err');
      } else if (r && r.error) {
        toast(r.error, 'err');
      }
    });
  } catch(e) { console.error(e); }
}

function termLoadSettings() {
  fetch('/api/terminal/settings/get').then(function (r) { return r.json(); }).then(function (d) {
    var isVel = (d.type === 'velocity');

    var box = document.querySelector('.settings-box summary');
    if (box) box.textContent = isVel ? 'Velocity 代理设置' : '终端设置';

    var portInput = document.getElementById('ts-port');
    if (portInput && portInput.previousElementSibling) {
      portInput.previousElementSibling.textContent = isVel ? '代理监听端口（玩家连这个）' : '监听端口（玩家连这个）';
    }

    var startEl = document.getElementById('ts-start');
    var startLabel = document.getElementById('ts-start-label');
    
    if (startEl) {
      startEl.value = d.startCommand || d.start_cmd || '';
      startEl.placeholder = isVel ? 'java -Xms512M -Xmx512M -jar velocity.jar' : 'java -Xmx2G -jar server.jar nogui';
    }
    if (startLabel) startLabel.textContent = "启动命令";

    var stopEl = document.getElementById('ts-stop');
    if (stopEl) stopEl.value = d.shutdownCommand || d.stop_cmd || '';
    var delayEl = document.getElementById('ts-delay');
    if (delayEl) delayEl.value = d.restartDelay || d.restart_delay || '5000';
    var autoEl = document.getElementById('ts-auto');
    if (autoEl) autoEl.checked = (d.autoRestart === 'true' || d.autoRestart === true);
    var autostartEl = document.getElementById('ts-autostart');
    if (autostartEl) autostartEl.checked = (d.autoStart === 'true' || d.autoStart === true);
    var portEl = document.getElementById('ts-port');
    if (portEl) portEl.value = d.port || (isVel ? '25577' : '25565');
  }).catch(function () {});
}

function termSaveSettings() {
  var payload = {
    startCommand: document.getElementById('ts-start') ? document.getElementById('ts-start').value : '',
    shutdownCommand: document.getElementById('ts-stop') ? document.getElementById('ts-stop').value : '',
    restartDelay: document.getElementById('ts-delay') ? document.getElementById('ts-delay').value : '5000',
    autoRestart: document.getElementById('ts-auto') && document.getElementById('ts-auto').checked ? 'true' : 'false',
    autoStart: document.getElementById('ts-autostart') && document.getElementById('ts-autostart').checked ? 'true' : 'false',
    port: document.getElementById('ts-port') ? document.getElementById('ts-port').value : '25565'
  };
  postApi('/api/terminal/settings/set', payload).then(function (r) {
    if (r && r.ok) {
      toast('设置已保存', 'ok');
      if (mcRunning) {
        setTimeout(function () {
          uiConfirm({ title: '设置已保存', message: '进程正在运行中。\n\n如果改了端口或启动命令，需要重启才能生效。', okText: '立即重启' })
          .then(function (ok) { if (ok) termAction('restart'); });
        }, 300);
      }
    } else {
      toast((r && r.error) || '保存失败', 'err');
    }
  });
}

function termClearLog() {
  uiConfirm({ title: '清空日志', message: '确定清空保存的日志历史？', danger: true, okText: '清空' }).then(function (ok) {
    if (!ok) return;
    postApi('/api/terminal/log/clear').then(function () {
      var el = document.getElementById('term-output');
      if (el) el.innerHTML = '';
      lastSeq = 0; toast('日志已清空', 'ok');
    });
  });
}

// ===================== 终端高度自适应（电脑端） =====================
var _termFitTimer = null;

// 电脑端让日志区撑满可视高度，随窗口大小和「终端设置」展开自动重算；
// 窄屏保持原来的固定高度，靠页面滚动。
function fitTerminalHeight() {
  var tab = document.getElementById('tab-terminal');
  var out = document.getElementById('term-output');
  var main = document.querySelector('.main');
  if (!tab || !out || !main) return;

  if (window.innerWidth < 900 || tab.style.display === 'none') {
    out.style.height = '';
    return;
  }

  var mainRect = main.getBoundingClientRect();
  var top = out.getBoundingClientRect().top - mainRect.top + main.scrollTop;

  var below = 0;
  var row = out.nextElementSibling;
  if (row && row.className.indexOf('term-input-row') >= 0) below += row.offsetHeight + 10;
  var box = tab.querySelector('.settings-box');
  if (box) below += box.offsetHeight + 20;

  var pb = parseFloat(getComputedStyle(main).paddingBottom) || 0;
  var avail = main.clientHeight - top - below - pb - 8;
  out.style.height = Math.max(240, Math.round(avail)) + 'px';
}

window.addEventListener('resize', function () {
  if (_termFitTimer) clearTimeout(_termFitTimer);
  _termFitTimer = setTimeout(fitTerminalHeight, 120);
});

document.addEventListener('DOMContentLoaded', function () {
  var box = document.querySelector('#tab-terminal .settings-box');
  if (box) {
    box.addEventListener('toggle', function () { setTimeout(fitTerminalHeight, 0); });
  }
  // 直接以 /实例/terminal 打开时，initApp 可能早于本文件执行，这里补一次
  setTimeout(fitTerminalHeight, 0);
});