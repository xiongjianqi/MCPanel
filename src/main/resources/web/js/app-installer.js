var installerTimer = null;

var loaderNames = {
    "paper": "Paper",
    "vanilla": "Vanilla",
    "fabric": "Fabric",
    "forge": "Forge",
    "neoforge": "NeoForge"
};
var loaderDescriptions = {
    "paper": "高性能优化端",
    "vanilla": "Mojang 官方原版",
    "fabric": "轻量级 Mod 加载器",
    "forge": "老牌 Mod 加载器",
    "neoforge": "新一代 Mod 加载器"
};

function loadInstallers() {
    fetch('/json/loader.json').then(r => {
        if (!r.ok) throw new Error();
        return r.json();
    }).then(data => {
        if (data && typeof data === 'object') {
            for (var k in data) {
                if (data[k]) loaderDescriptions[k] = data[k];
            }
        }
    }).catch(() => {}).finally(() => {
        renderInstallerCards();
    });
}

function renderInstallerCards() {
    var container = document.getElementById('installer-core-list');
    if (!container) return;
    container.innerHTML = '';

    // ★ 移除了 'velocity'，Velocity 由创建实例时自动处理
    var loaders = ['paper', 'vanilla', 'fabric', 'forge', 'neoforge'];
    loaders.forEach(function(id) {
        var name = loaderNames[id] || id;
        var desc = loaderDescriptions[id] || '';
        
        var card = document.createElement('div');
        card.className = 'card';
        card.style.cssText = 'text-align:center; cursor:pointer; display:flex; flex-direction:column; align-items:center; padding:20px;';
        card.onclick = function() { openInstallModal(id, name); };

        var iconWrap = document.createElement('div');
        iconWrap.style.cssText = 'width:48px; height:48px; margin-bottom:12px; display:flex; align-items:center; justify-content:center;';
        
        var img = document.createElement('img');
        img.style.cssText = 'max-width:100%; max-height:100%; object-fit:contain; border-radius:8px;';
        img.src = '/icons/' + id + '.png';
        
        img.onerror = function() {
            if (img.src.endsWith('.png')) {
                img.src = '/icons/' + id + '.ico';
            } else if (img.src.endsWith('.ico')) {
                img.src = '/icons/' + id + '.jpg';
            } else if (img.src.endsWith('.jpg')) {
                img.src = '/icons/' + id + '.webp';
            } else if (img.src.endsWith('.webp')) {
                img.src = '/icons/' + id + '.svg';
            } else {
                img.style.display = 'none';
                var fallback = document.createElement('div');
                fallback.style.cssText = 'width:100%; height:100%; background:var(--brand); color:#fff; display:flex; align-items:center; justify-content:center; font-size:24px; font-weight:bold; border-radius:8px;';
                fallback.textContent = name.charAt(0);
                iconWrap.appendChild(fallback);
            }
        };
        iconWrap.appendChild(img);

        var val = document.createElement('div');
        val.className = 'value';
        val.textContent = name;
        
        var lbl = document.createElement('div');
        lbl.className = 'label';
        lbl.style.marginTop = '8px';
        lbl.textContent = desc;

        card.appendChild(iconWrap);
        card.appendChild(val);
        card.appendChild(lbl);
        container.appendChild(card);
    });
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', loadInstallers);
} else {
    loadInstallers();
}

function openInstallModal(core, coreName) {
  var isFabric = (core === 'fabric');
  
  var html =
    '<label>目标核心：<b>' + coreName + '</b></label>' +
    '<label>Minecraft 版本</label>' +
    '<div id="inst-mc-ver-dd" style="color:#8b94ab;font-size:13px;">正在加载...</div>' +
    '<input type="hidden" id="inst-mc-ver" value="">' +
    '<label>' + (isFabric ? 'Loader 版本' : '核心版本') + '</label>' +
    '<div id="inst-core-ver-dd" style="color:#8b94ab;font-size:13px;"></div>' +
    '<input type="hidden" id="inst-core-ver" value="">' +
    (isFabric ? 
      '<label>Installer 版本</label>' +
      '<div id="inst-installer-ver-dd" style="color:#8b94ab;font-size:13px;">正在加载...</div>' +
      '<input type="hidden" id="inst-installer-ver" value="">' 
      : '') +
    '<label class="perm-item" style="margin-top:16px;">' +
    '  <span>保留原有世界数据 (仅限 world 文件夹)</span>' +
    '  <input type="checkbox" id="inst-keep-world" checked>' +
    '</label>' +
    '<div id="inst-progress-area" style="display:none; margin-top:24px;">' +
    '  <div style="display:flex; justify-content:space-between; margin-bottom:8px;">' +
    '    <span style="font-size:13px; color:#fff;">正在下载服务端...</span>' +
    '    <span id="inst-pct" style="font-size:13px; color:#e94560; font-weight:bold;">0%</span>' +
    '  </div>' +
    '  <div style="height:8px;background:#1b212e;border-radius:4px;overflow:hidden;position:relative;">' +
    '    <div id="inst-progress-fill" style="height:100%;width:0%;background:#e94560;transition:width 0.2s; position:absolute; left:0; top:0;"></div>' +
    '  </div>' +
    '  <div id="inst-error-msg" style="color:#ff5c5c; text-align:center; margin-top:16px; font-size:13px; font-weight:bold; display:none;"></div>' +
    '</div>';

  openModal('安装 ' + coreName + ' 服务端', html, function () {}, '开始下载');

  var saveBtn = document.getElementById('edit-modal-save');
  saveBtn.onclick = function() { startInstall(core, isFabric); };

  var headerCloseBtn = document.querySelector('#edit-modal .edit-modal-header .btn');
  if(headerCloseBtn) headerCloseBtn.style.display = '';
  var footerBtns = document.querySelectorAll('#edit-modal .edit-modal-footer .btn');
  if(footerBtns.length >= 2) footerBtns[1].style.display = '';

  loadMcVersions(core, isFabric);
}

function loadMcVersions(core, isFabric) {
  var ddHost = document.getElementById('inst-mc-ver-dd');
  ddHost.innerHTML = '正在加载...';

  fetch('/api/installer/versions?core=' + core, {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: '{}'
  }).then(r => {
    if (!r.ok) throw new Error("HTTP " + r.status);
    return r.json();
  }).then(res => {
    if (res.error) throw new Error(res.error);
    var list = res;
    if (!Array.isArray(list) || list.length === 0) throw new Error("版本列表为空");

    ddHost.innerHTML = '';
    if (typeof spBuildDropdown !== 'function') return;
    var opts = list.map(v => [v, v]);
    
    var dd = spBuildDropdown(opts[0][0], opts, function(v) {
      document.getElementById('inst-mc-ver').value = v;
      loadCoreVersions(core, v, isFabric);
    });
    dd.classList.add('inline');
    ddHost.appendChild(dd);
    
    document.getElementById('inst-mc-ver').value = opts[0][0];
    loadCoreVersions(core, opts[0][0], isFabric);

    if (isFabric) loadFabricInstallerVersions();
  }).catch(e => {
    // ★ 安全修复：使用 DOM API 避免 HTML 注入
    ddHost.innerHTML = '';
    var span = document.createElement('span');
    span.style.color = '#ff5c5c';
    span.textContent = '获取失败: ' + e.message;
    ddHost.appendChild(span);
  });
}

function loadCoreVersions(core, mc, isFabric) {
  var ddHost = document.getElementById('inst-core-ver-dd');
  ddHost.innerHTML = '正在加载...';

  fetch('/api/installer/core-versions?core=' + core + '&mc=' + mc, {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: '{}'
  }).then(r => {
    if (!r.ok) throw new Error("HTTP " + r.status);
    return r.json();
  }).then(res => {
    if (res.error) throw new Error(res.error);
    var list = res;
    if (!Array.isArray(list) || list.length === 0) throw new Error("核心版本列表为空");

    ddHost.innerHTML = '';
    if (typeof spBuildDropdown !== 'function') return;
    var opts = list.map(v => [v, v]);
    
    var dd = spBuildDropdown(opts[0][0], opts, function(v) {
      document.getElementById('inst-core-ver').value = v;
    });
    dd.classList.add('inline');
    ddHost.appendChild(dd);
    document.getElementById('inst-core-ver').value = opts[0][0];
  }).catch(e => {
    // ★ 安全修复：使用 DOM API 避免 HTML 注入
    ddHost.innerHTML = '';
    var span = document.createElement('span');
    span.style.color = '#ff5c5c';
    span.textContent = '获取失败: ' + e.message;
    ddHost.appendChild(span);
  });
}

function loadFabricInstallerVersions() {
  var ddHost = document.getElementById('inst-installer-ver-dd');
  if (!ddHost) return;
  ddHost.innerHTML = '正在加载...';

  fetch('/api/installer/fabric-installer-versions', {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: '{}'
  }).then(r => {
    if (!r.ok) throw new Error("HTTP " + r.status);
    return r.json();
  }).then(res => {
    if (res.error) throw new Error(res.error);
    var list = res;
    if (!Array.isArray(list) || list.length === 0) throw new Error("安装器版本列表为空");

    ddHost.innerHTML = '';
    if (typeof spBuildDropdown !== 'function') return;
    var opts = list.map(v => [v, v]);
    
    var dd = spBuildDropdown(opts[0][0], opts, function(v) {
      document.getElementById('inst-installer-ver').value = v;
    });
    dd.classList.add('inline');
    ddHost.appendChild(dd);
    document.getElementById('inst-installer-ver').value = opts[0][0];
  }).catch(e => {
    // ★ 安全修复：使用 DOM API 避免 HTML 注入
    ddHost.innerHTML = '';
    var span = document.createElement('span');
    span.style.color = '#ff5c5c';
    span.textContent = '获取失败: ' + e.message;
    ddHost.appendChild(span);
  });
}

function startInstall(core, isFabric) {
  var mc = document.getElementById('inst-mc-ver').value;
  var coreVer = document.getElementById('inst-core-ver').value;
  var instVer = isFabric ? document.getElementById('inst-installer-ver').value : '';
  var keepWorld = document.getElementById('inst-keep-world').checked;

  if (!mc) { toast("请选择 MC 版本", 'err'); return; }
  if (isFabric && !instVer) { toast("请选择 Installer 版本", 'err'); return; }

  if (core === 'forge' || core === 'neoforge') {
    sessionStorage.setItem('auto_tab', 'terminal');
    closeModal();

    fetch('/api/installer/install', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        core: core, mc: mc, coreVer: coreVer, instVer: '',
        keepWorld: keepWorld ? 'true' : 'false'
      })
    }).then(r => r.json()).then(res => {
      if (res && res.error) {
        alert('启动安装失败：' + res.error);
      }
      setTimeout(() => location.reload(), 300);
    }).catch(e => {
      alert('请求失败：' + e.message);
      setTimeout(() => location.reload(), 300);
    });
    return;
  }

  document.getElementById('inst-progress-area').style.display = 'block';
  document.getElementById('inst-error-msg').style.display = 'none';
  document.getElementById('inst-pct').textContent = '0%';
  document.getElementById('inst-progress-fill').style.width = '0%';
  
  var saveBtn = document.getElementById('edit-modal-save');
  saveBtn.textContent = '处理中...';
  saveBtn.disabled = true;

  fetch('/api/installer/install', {
    method: 'POST', headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({core: core, mc: mc, coreVer: coreVer, instVer: instVer, keepWorld: keepWorld ? 'true' : 'false'})
  }).then(r => r.json()).then(res => {
    if (res.error) {
      var errEl = document.getElementById('inst-error-msg');
      if (errEl) {
        errEl.textContent = res.error;
        errEl.style.display = 'block';
      }
      saveBtn.textContent = '重新下载';
      saveBtn.disabled = false;
      return;
    }
    pollInstallStatus();
  }).catch(e => {
    var errEl = document.getElementById('inst-error-msg');
    if (errEl) {
      errEl.textContent = "请求失败: " + e.message;
      errEl.style.display = 'block';
    }
    saveBtn.textContent = '重新下载';
    saveBtn.disabled = false;
  });
}

function pollInstallStatus() {
  if (installerTimer) clearInterval(installerTimer);
  installerTimer = setInterval(() => {
    fetch('/api/installer/status').then(r => r.json()).then(res => {
      var progressFill = document.getElementById('inst-progress-fill');
      var pctEl = document.getElementById('inst-pct');
      
      if (!progressFill) {
        if (res.finished && !res.running && res.success) {
          clearInterval(installerTimer);
          toast("后台安装已完成！正在跳转到终端...", 'ok');
          sessionStorage.setItem('auto_tab', 'terminal');
          setTimeout(() => { location.reload(); }, 300);
        }
        return;
      }

      if (res.totalBytes > 0) {
        var pct = (res.downloadedBytes / res.totalBytes) * 100;
        progressFill.style.width = pct + '%';
        progressFill.style.opacity = '1';
        progressFill.style.animation = 'none';
        if (pctEl) pctEl.textContent = Math.floor(pct) + '% (' + fmtSize(res.downloadedBytes) + ')';
      } else {
        progressFill.style.width = '100%';
        progressFill.style.opacity = '0.6';
        progressFill.style.animation = 'progressPulse 1.5s infinite';
        if (pctEl) pctEl.textContent = fmtSize(res.downloadedBytes) + ' / 未知';
      }

      if (res.finished && !res.running) {
        clearInterval(installerTimer);
        
        if (res.success) {
          closeModal();
          toast("安装完成！正在跳转到终端...", 'ok');
          sessionStorage.setItem('auto_tab', 'terminal');
          setTimeout(() => { location.reload(); }, 300);
        } else {
          var errEl = document.getElementById('inst-error-msg');
          if (errEl) {
            errEl.textContent = res.error || "安装失败";
            errEl.style.display = 'block';
          }
          var saveBtn = document.getElementById('edit-modal-save');
          if (saveBtn) {
            saveBtn.textContent = '重试';
            saveBtn.disabled = false;
          }
        }
      }
    }).catch(e => {});
  }, 300);
}