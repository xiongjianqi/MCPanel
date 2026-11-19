// app-java.js
var javaPollTimer = null;

function javaRefresh() {
  if (panelExited) return;
  fetch('/api/java/list').then(r => r.json()).then(d => {
    if (!d || !d.list) return;
    
    var cur = d.current;
    var badge = document.getElementById('java-current-badge');
    if (badge) {
      if (!cur) {
        badge.textContent = '当前使用：系统默认 Java';
        badge.style.color = '';
      } else {
        var m = cur.match(/(jre|jdk)-(\d+)/);
        badge.textContent = '当前使用：' + (m ? (m[1].toUpperCase() + ' ' + m[2]) : '自定义');
        badge.style.color = '#3ddc84';
      }
    }

    var container = document.getElementById('java-list');
    if (!container) return;
    container.innerHTML = '';

    var defCard = document.createElement('div');
    defCard.className = 'card';
    defCard.style.borderColor = (!cur) ? 'var(--brand)' : 'var(--border)';
    defCard.innerHTML = '<div class="label">系统环境</div><div class="value" style="font-size:16px;">系统默认 Java</div>' +
      '<div style="margin-top:14px;"><button class="btn ' + (!cur ? 'primary' : '') + ' small" id="btn-java-default">应用</button></div>';
    container.appendChild(defCard);

    document.getElementById('btn-java-default').onclick = function() {
      postApi('/api/java/set', { version: 'default' }).then(r => {
        if (r && r.ok) { toast('已应用 Java 环境', 'ok'); javaRefresh(); } 
        else toast('设置失败', 'err');
      });
    };

    var hasDownloading = false;

    d.list.forEach(function(j) {
      var isCur = cur && cur.indexOf(j.type + '-' + j.version) >= 0;
      var typeName = j.type.toUpperCase();
      var card = document.createElement('div');
      card.className = 'card';
      if (isCur) card.style.borderColor = 'var(--brand)';
      
      var html = '<div class="label">' + typeName + ' ' + j.version + '</div>';
      if (j.installed) {
        html += '<div class="value" style="font-size:16px;color:#3ddc84;">已安装</div>';
        html += '<div style="margin-top:14px; display:flex; gap:8px;">';
        html += '<button class="btn ' + (isCur ? '' : 'primary') + ' small" id="btn-java-set-' + j.type + '-' + j.version + '">' + (isCur ? '使用中' : '应用') + '</button>';
        if (!isCur) {
          html += '<button class="btn danger small" id="btn-java-del-' + j.type + '-' + j.version + '">删除</button>';
        }
        html += '</div>';
      } else if (j.downloading) {
        hasDownloading = true;
        var pct = Math.floor((j.progress || 0) * 100);
        html += '<div class="value" style="font-size:16px;color:#f0c040;">' + pct + '%</div>';
        html += '<div style="margin-top:14px;"><button class="btn small" disabled>请稍候</button></div>';
      } else {
        html += '<div class="value" style="font-size:16px;color:var(--text-dim);">未安装</div>';
        html += '<div style="margin-top:14px;"><button class="btn primary small" id="btn-java-dl-' + j.type + '-' + j.version + '">下载</button></div>';
      }
      card.innerHTML = html;
      container.appendChild(card);

      var btnSet = document.getElementById('btn-java-set-' + j.type + '-' + j.version);
      if (btnSet) btnSet.onclick = () => javaSet(j.version, j.type);
      
      var btnDel = document.getElementById('btn-java-del-' + j.type + '-' + j.version);
      if (btnDel) btnDel.onclick = () => javaDelete(j.version, j.type);

      var btnDl = document.getElementById('btn-java-dl-' + j.type + '-' + j.version);
      if (btnDl) btnDl.onclick = () => javaDownload(j.version, j.type);
    });

    if (hasDownloading) {
      if (!javaPollTimer) javaPollTimer = setInterval(javaRefresh, 1500);
    } else {
      if (javaPollTimer) { clearInterval(javaPollTimer); javaPollTimer = null; }
    }
  }).catch(e => {});
}

function javaDownload(version, type) {
  toast('开始下载 ' + type.toUpperCase() + ' ' + version + '...', 'ok');
  postApi('/api/java/download', { version: String(version), type: type }).then(r => {
    if (r && r.error) toast('下载失败: ' + r.error, 'err');
    else javaRefresh();
  });
}

function javaSet(version, type) {
  var payload = { version: String(version) };
  if (type) payload.type = type;
  postApi('/api/java/set', payload).then(r => {
    if (r && r.ok) { toast('已应用 Java 环境', 'ok'); javaRefresh(); } 
    else toast('设置失败', 'err');
  });
}

function javaDelete(version, type) {
  uiConfirm({ title: '删除运行时', message: '确定要删除 ' + type.toUpperCase() + ' ' + version + ' 吗？', danger: true, okText: '删除' }).then(function(ok) {
    if (!ok) return;
    postApi('/api/java/delete', { version: String(version), type: type }).then(r => {
      if (r && r.ok) { toast('已删除', 'ok'); javaRefresh(); } 
      else toast('删除失败，可能正在被占用', 'err');
    });
  });
}