var _pluginEventWs = null;
var _pluginEventHandlers = {};

function loadPluginPages() {
  fetch('/api/plugin-pages/list').then(r => r.json()).then(list => {
    if (!list || !Array.isArray(list)) return;
    var sidebar = document.querySelector('.sidebar');
    
    sidebar.querySelectorAll('a[data-plugin-page]').forEach(a => a.remove());
    document.querySelectorAll('.plugin-tab-container').forEach(d => d.remove());
    
    list.forEach(p => {
      if (p.scope === 'global' && currentInstanceId) return;
      if (p.scope === 'instance' && !currentInstanceId) return;
      
      var a = document.createElement('a');
      a.dataset.pluginPage = p.id;
      a.innerHTML = '<svg viewBox="0 0 24 24"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/></svg><span>' + escapeHtml(p.title) + '</span>';
      a.onclick = function() { switchTab('__plugin__' + p.id); };
      
      var footer = document.getElementById('sidebar-user-footer');
      sidebar.insertBefore(a, footer);
      
      var div = document.createElement('div');
      div.id = 'tab-__plugin__' + p.id;
      div.className = 'plugin-tab-container';
      div.style.display = 'none';
      document.querySelector('.main').appendChild(div);
    });
  }).catch(() => {});
}

function ensurePluginFrame(tabId) {
  var pageId = tabId.substring('__plugin__'.length);
  var container = document.getElementById('tab-' + tabId);
  if (!container) return;
  if (container.dataset.loaded === '1') return;
  
  var iframe = document.createElement('iframe');
  iframe.className = 'plugin-page-frame';
  // ★ 核心修复：移除 allow-same-origin 防止插件 iframe 越权访问父页面
  iframe.sandbox = 'allow-scripts allow-forms allow-popups';
  var qs = '?id=' + encodeURIComponent(pageId);
  if (currentInstanceId) qs += '&instance=' + encodeURIComponent(currentInstanceId);
  iframe.src = '/api/plugin-pages/render' + qs;
  container.appendChild(iframe);
  container.dataset.loaded = '1';
}

function ensurePluginEventSocket() {
  if (_pluginEventWs) return;
  var proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
  _pluginEventWs = new WebSocket(proto + location.host + '/ws/plugin-events');
  _pluginEventWs.onmessage = function(ev) {
    try {
      var msg = JSON.parse(ev.data);
      if (_pluginEventHandlers['*']) _pluginEventHandlers['*'].forEach(fn => fn(msg.data, msg));
      if (_pluginEventHandlers[msg.event]) _pluginEventHandlers[msg.event].forEach(fn => fn(msg.data, msg));
    } catch(e) {}
  };
  _pluginEventWs.onclose = function() {
    _pluginEventWs = null;
    setTimeout(ensurePluginEventSocket, 5000);
  };
}

function onPluginEvent(event, fn) {
  if (!_pluginEventHandlers[event]) _pluginEventHandlers[event] = [];
  _pluginEventHandlers[event].push(fn);
}