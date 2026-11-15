// ===================== 面板日志 =====================
var _panelLogWs = null;
function initPanelLog() {
  if (panelExited) return;
  var out = document.getElementById('panel-log-output');
  if (!out) return;
  
  if (_panelLogWs) {
    if (_panelLogWs.readyState === WebSocket.OPEN || _panelLogWs.readyState === WebSocket.CONNECTING) return;
  }
  
  out.innerHTML = '';
  var proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
  var wsUrl = proto + location.host + '/ws/panel-log';
  _panelLogWs = new WebSocket(wsUrl);
  
  _panelLogWs.onmessage = function(ev) {
    var line = ev.data;
    var div = document.createElement('div');
    div.textContent = line;
    out.appendChild(div);
    if (out.childNodes.length > 1000) out.removeChild(out.firstChild);
    out.scrollTop = out.scrollHeight;
  };
  _panelLogWs.onclose = function() {
    _panelLogWs = null;
    if (currentTab === 'panel-log') {
      setTimeout(initPanelLog, 3000);
    }
  };
}

function panelShutdown() {
  uiConfirm({
    title: '关闭面板',
    message: '确定要关闭整个面板吗？所有 Minecraft 服务端实例将被安全停止。\n\n此操作需要手动重新启动面板才能恢复。',
    danger: true,
    okText: '关闭面板'
  }).then(ok => {
    if (!ok) return;
    toast('正在关闭面板...');
    postApi('/api/panel/shutdown', {}).then(r => {
      if (r && r.ok) {
        showExitOverlay('shutting', '面板正在关闭', '正在停止所有实例并关闭后端进程...');
        panelExited = true;
      } else {
        toast((r && r.error) || '关闭失败', 'err');
      }
    });
  });
}
