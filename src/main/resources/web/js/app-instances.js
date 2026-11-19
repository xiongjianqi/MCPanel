// ===================== 多实例 =====================

var lastInstanceListJson = '';

function mountInstanceTypeDropdown(hostId, inputId, initial) {
  var host = document.getElementById(hostId);
  if (!host) return;
  if (typeof spBuildDropdown !== 'function') return;

  var hidden = document.getElementById(inputId);
  var opts = [
    ['mc',       'Minecraft 服务端（Paper / Fabric / Forge）'],
    ['velocity', 'Velocity 代理']
  ];

  var dd = spBuildDropdown(initial || 'mc', opts, function (v) {
    if (hidden) hidden.value = v;
  });
  dd.classList.add('inline');
  host.appendChild(dd);
}

function panelPluginsShow() {
  switchTab('panel-plugins');
}

function renderPanelPluginsPage() {
  var container = document.getElementById('panel-plugins-content');
  if (!container) return;
  container.innerHTML =
    '<div class="hint" style="max-width:none;">' +
    '  面板插件运行在主面板级别，不绑定任何实例。<br>' +
    '  插件可通过 <code>ctx.registerWebHandler(...)</code> 在 <code>/plugins/&lt;ID&gt;/*</code> 提供 HTTP 服务。<br>' +
    '  插件可通过 <code>ctx.registerPanelPage(new PanelPage(...))</code> 在侧边栏添加自定义标签页。' +
    '</div>' +
    '<div class="toolbar">' +
    '  <button class="btn primary" onclick="panelPluginsUploadClick()">上传面板插件</button>' +
    '  <button class="btn" onclick="panelPluginsRefresh()">刷新</button>' +
    '  <a class="btn" href="/plugin-dev-guide.txt" download="MC-Panel-插件开发指南.txt" target="_blank" rel="noopener noreferrer">下载开发指南</a>' +
    '  <input type="file" id="panel-plugins-upload-input" class="hidden-file" accept=".jar">' +
    '</div>' +
    '<div class="table-wrap">' +
    '  <table><thead><tr><th>名称</th><th>版本</th><th>作者</th><th>状态</th><th>操作</th></tr></thead>' +
    '  <tbody id="panel-plugins-list"></tbody></table>' +
    '</div>' +
    '<h3 style="margin-top:28px;">已注册的侧边栏标签页</h3>' +
    '<div class="table-wrap">' +
    '  <table><thead><tr><th>标题</th><th>所属插件</th><th>作用范围</th><th>需要权限</th></tr></thead>' +
    '  <tbody id="panel-pages-list"></tbody></table>' +
    '</div>';
  setTimeout(function() {
    var inp = document.getElementById('panel-plugins-upload-input');
    if (inp) inp.addEventListener('change', panelPluginsUpload);
  }, 10);
  panelPluginsRefresh();
  panelPagesRefresh();
}

function panelPagesRefresh() {
  fetch('/api/plugin-pages/list').then(function(r) { return r.json(); }).then(function(list) {
    var tbody = document.getElementById('panel-pages-list');
    if (!tbody) return;
    tbody.innerHTML = '';
    if (!list || list.length === 0) {
      tbody.innerHTML = '<tr><td colspan="4" class="empty">暂无自定义标签页（插件调用 ctx.registerPanelPage 后会出现）</td></tr>';
      return;
    }
    var scopeMap = { global: '仅节点', instance: '仅实例', both: '全部' };
    list.forEach(function(p) {
      var tr = document.createElement('tr');
      var td1 = document.createElement('td'); td1.textContent = p.title || p.id;
      var td2 = document.createElement('td'); td2.className = 'dim'; td2.textContent = p.pluginName || '-';
      var td3 = document.createElement('td'); td3.className = 'dim'; td3.textContent = scopeMap[p.scope] || p.scope || '-';
      var td4 = document.createElement('td'); td4.className = 'dim'; td4.textContent = p.perm || '无';
      tr.appendChild(td1); tr.appendChild(td2); tr.appendChild(td3); tr.appendChild(td4);
      tbody.appendChild(tr);
    });
  }).catch(function() {});
}

function panelPluginsRefresh() {
  postApi('/api/panel/plugins/list', {}).then(function (list) {
    var tbody = document.getElementById('panel-plugins-list');
    if (!tbody) return;
    tbody.innerHTML = '';
    if (!list || list.length === 0) {
      tbody.innerHTML = '<tr><td colspan="5" class="empty">暂无面板插件</td></tr>';
      return;
    }
    list.forEach(function (p) {
      var tr = document.createElement('tr');
      var td1 = document.createElement('td'); td1.textContent = p.name || p.jarName;
      var td2 = document.createElement('td'); td2.className = 'dim'; td2.textContent = p.version || '-';
      var td3 = document.createElement('td'); td3.className = 'dim'; td3.textContent = p.author || '-';
      var td4 = document.createElement('td');
      if (p.loaded && p.enabled) td4.innerHTML = '<span style="color:#3ddc84">● 已启用</span>';
      else if (p.loaded) td4.innerHTML = '<span style="color:#f0c040">○ 已加载</span>';
      else td4.innerHTML = '<span style="color:#8b94ab">— 未加载</span>';
      var td5 = document.createElement('td');
      var b1 = document.createElement('button'); b1.className = 'btn small';
      if (!p.loaded) {
        b1.textContent = '加载';
        b1.onclick = (function (jar) { return function () { panelPluginsAct('load', jar); }; })(p.jarName);
      } else if (p.enabled) {
        b1.textContent = '禁用';
        b1.onclick = (function (jar) { return function () { panelPluginsAct('disable', jar); }; })(p.jarName);
      } else {
        b1.textContent = '启用';
        b1.className = 'btn small primary';
        b1.onclick = (function (jar) { return function () { panelPluginsAct('enable', jar); }; })(p.jarName);
      }
      td5.appendChild(b1);
      var b2 = document.createElement('button');
      b2.className = 'btn small danger'; b2.textContent = '删除'; b2.style.marginLeft = '6px';
      b2.onclick = (function (jar) { return function () {
        uiConfirm({title: '删除面板插件', message: '确定删除 ' + jar + '？', danger: true, okText: '删除'}).then(function (ok) {
          if (!ok) return;
          panelPluginsAct('delete', jar);
        });
      }; })(p.jarName);
      td5.appendChild(b2);
      tr.appendChild(td1); tr.appendChild(td2); tr.appendChild(td3); tr.appendChild(td4); tr.appendChild(td5);
      tbody.appendChild(tr);
    });
  }).catch(function (e) { toast('读取面板插件列表失败', 'err'); });
}

function panelPluginsAct(action, jarName) {
  postApi('/api/panel/plugins/' + action, { jarName: jarName }).then(function (r) {
    if (r && r.ok) { toast('已' + action, 'ok'); panelPluginsRefresh(); }
    else toast((r && r.error) || '操作失败', 'err');
  });
}

function panelPluginsUploadClick() {
  var inp = document.getElementById('panel-plugins-upload-input');
  if (inp) inp.click();
}

function panelPluginsUpload(e) {
  var file = e.target.files[0];
  if (!file) return;
  if (!file.name.endsWith('.jar')) { toast('只支持 .jar', 'err'); e.target.value = ''; return; }
  fetch('/api/panel/plugins/upload?name=' + encodeURIComponent(file.name), {
    method: 'POST', body: file
  }).then(function (r) { return r.json(); }).then(function (r) {
    if (r && r.ok) { toast('上传成功', 'ok'); panelPluginsRefresh(); }
    else toast((r && r.error) || '上传失败', 'err');
  }).catch(function () { toast('上传出错', 'err'); });
  e.target.value = '';
}

function instanceListRefresh(force) {
  if (panelExited) return;

  fetch('/api/instances/list').then(function (r) { return r.json(); }).then(function (list) {
    if (panelExited) return;
    var json = JSON.stringify(list || []);
    if (!force && json === lastInstanceListJson) return;
    lastInstanceListJson = json;

    var grid = document.getElementById('instance-grid');
    if (!grid) return;

    if (!Array.isArray(list)) {
      grid.innerHTML = '<div class="placeholder">加载实例失败，请检查后端状态或刷新重试。</div>';
      return;
    }
    if (list.length === 0) {
      grid.innerHTML = '';
      var addCard = document.createElement('div');
      addCard.className = 'instance-add-card';
      addCard.textContent = '+ 新建实例';
      addCard.onclick = instanceCreatePrompt;
      grid.appendChild(addCard);
      return;
    }

    var canCreate = hasPerm('instance_create');
    var canEdit   = hasPerm('instance_edit');
    var canDelete = hasPerm('instance_delete');

    var existingCards = {};
    grid.querySelectorAll('.instance-card').forEach(function(card) {
      var id = card.dataset.id;
      if (id) existingCards[id] = card;
    });

    var currentIds = {};

    if (list && list.length > 0) {
      list.forEach(function (inst) {
        currentIds[inst.id] = true;
        var card = existingCards[inst.id];
        var isNew = false;

        if (!card) {
          isNew = true;
          card = document.createElement('div');
          card.className = 'instance-card';
          card.setAttribute('data-id', inst.id);

          var bg = document.createElement('div');
          bg.className = 'instance-card-bg';
          var bgUrl = '/api/system/default-bg?type=instance&t=' + Date.now();
          if (inst.hasBg) {
            bgUrl = '/api/instances/bg?id=' + encodeURIComponent(inst.id)
                      + '&t=' + Date.now();
          }
          bg.style.backgroundImage = "url('" + bgUrl + "')";
          bg.style.backgroundSize = 'cover';
          bg.style.backgroundPosition = 'center';
          bg.style.boxShadow = 'inset 0 0 20px rgba(0,0,0,0.5)';

          var typeBadge = document.createElement('div');
          typeBadge.className = 'instance-type-badge ' + (inst.type === 'velocity' ? 'proxy' : 'mc');
          typeBadge.textContent = (inst.type === 'velocity') ? 'Velocity' : 'MC';
          bg.appendChild(typeBadge);

          if (inst.isDefault) {
            var defBadge = document.createElement('div');
            defBadge.className = 'instance-type-badge';
            defBadge.style.cssText = 'left: auto; right: 10px; background: rgba(233,69,96,.8); border-color: #e94560;';
            defBadge.textContent = '默认';
            bg.appendChild(defBadge);
          }

          var st = document.createElement('div');
          st.className = 'instance-card-status';
          bg.appendChild(st);
          card.appendChild(bg);

          var body = document.createElement('div');
          body.className = 'instance-card-body';
          var nm = document.createElement('div');
          nm.className = 'instance-card-name';
          var ds = document.createElement('div');
          ds.className = 'instance-card-desc';
          body.appendChild(nm); body.appendChild(ds);
          card.appendChild(body);

          var ft = document.createElement('div');
          ft.className = 'instance-card-footer';

          var enterBtn = document.createElement('button');
          enterBtn.className = 'btn primary small';
          enterBtn.textContent = '进入';
          enterBtn.onclick = (function (id) { return function (e) { e.stopPropagation(); enterInstance(id); }; })(inst.id);
          ft.appendChild(enterBtn);

          if (!inst.isDefault) {
            var defBtn = document.createElement('button');
            defBtn.className = 'btn small';
            defBtn.textContent = '设为默认';
            defBtn.onclick = (function (id) { return function (e) { 
              e.stopPropagation(); 
              postApi('/api/instances/set-default', { id: id }).then(function(r) {
                if (r && r.ok) { toast('已设为默认实例', 'ok'); instanceListRefresh(true); }
                else toast((r && r.error) || '设置失败', 'err');
              });
            }; })(inst.id);
            ft.appendChild(defBtn);
          }

          if (canEdit) {
            var editBtn = document.createElement('button');
            editBtn.className = 'btn small'; editBtn.textContent = '编辑';
            editBtn.onclick = (function (i) { return function (e) { e.stopPropagation(); instanceEditPrompt(i); }; })(inst);
            ft.appendChild(editBtn);
          }

          if (canDelete) {
            var delBtn = document.createElement('button');
            delBtn.className = 'btn danger small'; delBtn.textContent = '删除';
            delBtn.style.marginLeft = 'auto';
            delBtn.onclick = (function (id, name) { return function (e) {
              e.stopPropagation();
              uiConfirm({ title: '删除实例', message: '确定删除实例 ' + (name || id) + '？\n\n此操作会删除该实例的全部文件，不可恢复！', danger: true, okText: '删除' }).then(function (ok) {
                if (!ok) return;
                postApi('/api/instances/delete', { id: id }).then(function (r) {
                  if (r && r.ok) { toast('已删除', 'ok'); instanceListRefresh(true); }
                  else toast((r && r.error) || '删除失败', 'err');
                });
              });
            }; })(inst.id, inst.name);
            ft.appendChild(delBtn);
          }

          card.appendChild(ft);
          card.onclick = function () { enterInstance(inst.id); };
        }

        card.querySelector('.instance-card-name').textContent = inst.name || inst.id;
        card.querySelector('.instance-card-desc').textContent = inst.desc || '（无描述）';
        
        var stEl = card.querySelector('.instance-card-status');
        if (stEl) {
            var state = 'stopped';
            if (inst.running) state = inst.starting ? 'starting' : 'running';
            stEl.className = 'instance-card-status ' + state;
            if (state === 'starting') stEl.textContent = '启动中';
            else if (state === 'running') stEl.textContent = '● 运行中';
            else stEl.textContent = '○ 已停止';
        }

        if (isNew) grid.appendChild(card);
      });
    }

    for (var id in existingCards) {
      if (!currentIds[id]) existingCards[id].remove();
    }

    var addCard = grid.querySelector('.instance-add-card');
    if (!addCard) {
      addCard = document.createElement('div');
      addCard.className = 'instance-add-card';
      addCard.textContent = '+ 新建实例';
      addCard.onclick = instanceCreatePrompt;
    }
    grid.appendChild(addCard);

  }).catch(function (e) {
    console.error(e);
  });
}

function enterInstance(id) {
  navigateTo('/' + id + '/terminal');
}

function instanceCreatePrompt() {
  var html =
    '<label>实例类型</label>' +
    '<div id="inst-type-dd"></div>' +
    '<input type="hidden" id="inst-type" value="mc">' +
    '<label>实例名字</label>' +
    '<input id="inst-name" type="text" placeholder="例如：生存服" autocomplete="off">' +
    '<label>描述</label>' +
    '<input id="inst-desc" type="text" placeholder="可选" autocomplete="off">' +
    '<label>背景图 / 视频（可选）</label>' +
    '<input id="inst-bg-file" type="file" accept="image/*">' +
    '<div class="hint" style="margin-top:14px;">' +
    '  MC 实例：外网端口从 25565 起自动分配<br>' +
    '  Velocity 实例：监听端口从 25577 起自动分配，<b>创建后会自动安装最新版 Velocity</b>' +
    '</div>';

  openModal('新建实例', html, function () {
    var type = document.getElementById('inst-type').value || 'mc';
    var name = document.getElementById('inst-name').value.trim();
    var desc = document.getElementById('inst-desc').value.trim();
    var fileInput = document.getElementById('inst-bg-file');
    var file = fileInput && fileInput.files && fileInput.files[0];
    if (!name) { toast('请输入名字', 'err'); return; }
    closeModal();
    
    postApi('/api/instances/create', {
      type: type,
      nameB64: b64enc(name),
      descB64: b64enc(desc)
    }).then(function (r) {
      if (!r || !r.ok) { toast((r && r.error) || '创建失败', 'err'); return; }
      var newId = r.id;

      var finishCreate = function() {
        if (type === 'velocity') {
          showExitOverlay('shutting', '正在安装 Velocity...', '正在获取最新版本并下载，请稍候。');
          
          fetch('/api/installer/versions?core=velocity')
            .then(res => res.json())
            .then(vers => {
               var mcVer = Array.isArray(vers) && vers.length > 0 ? vers[0] : '1.21';
               return postApi('/api/installer/install', {
                 core: 'velocity',
                 mc: mcVer,
                 coreVer: 'latest',
                 instVer: '',
                 keepWorld: false
               });
            })
            .then(res => {
               if (res && res.error) {
                 toast('Velocity 下载失败: ' + res.error, 'err');
                 setTimeout(() => location.reload(), 1500);
               } else {
                 pollInstallAndReload(newId);
               }
            })
            .catch(e => {
               toast('请求安装失败', 'err');
               setTimeout(() => location.reload(), 1500);
            });
          return;
        }
        toast('已创建', 'ok');
        instanceListRefresh(true);
      };

      if (file) {
        if (!file.type.startsWith('image/')) {
          toast('背景图仅支持图片格式', 'err');
          finishCreate();
          return;
        }
        uploadInstanceBg(newId, file).then(finishCreate).catch(function () {
          toast('已创建，但背景上传失败', 'err');
          finishCreate();
        });
      } else {
        finishCreate();
      }
    });
  }, '创建');

  mountInstanceTypeDropdown('inst-type-dd', 'inst-type', 'mc');
}

function pollInstallAndReload(newId) {
    var timer = setInterval(() => {
        fetch('/api/installer/status').then(r => r.json()).then(res => {
            var title = document.querySelector('.panel-exit-overlay h2');
            var p = document.querySelector('.panel-exit-overlay p');
            if (title) title.textContent = '正在安装 Velocity...';
            if (p) {
                if (res.totalBytes > 0) {
                    var pct = Math.floor((res.downloadedBytes / res.totalBytes) * 100);
                    p.textContent = '下载进度: ' + pct + '% (' + fmtSize(res.downloadedBytes) + ')';
                } else {
                    p.textContent = '正在下载...';
                }
            }
            if (res.finished && !res.running) {
                clearInterval(timer);
                if (res.success) {
                    window.location.href = '/' + newId + '/terminal';
                } else {
                    document.getElementById('panel-exit-overlay').classList.remove('show');
                    toast('安装失败: ' + res.error, 'err');
                    instanceListRefresh(true);
                }
            }
        }).catch(() => {});
    }, 500);
}

function uploadInstanceBg(id, file) {
  return fetch('/api/instances/upload-bg?id=' + encodeURIComponent(id)
              + '&name=' + encodeURIComponent(file.name), {
    method: 'POST',
    body: file
  }).then(function (r) { return r.json(); });
}

function instanceEditPrompt(inst) {
  var currentType = inst.type || 'mc';
  var html =
    '<label>实例类型</label>' +
    '<div id="ie-type-dd"></div>' +
    '<input type="hidden" id="ie-type" value="' + currentType + '">' +
    '<label>实例名字</label>' +
    '<input id="ie-name" type="text" value="' + escapeHtml(inst.name || '') + '" autocomplete="off">' +
    '<label>描述</label>' +
    '<input id="ie-desc" type="text" value="' + escapeHtml(inst.desc || '') + '" autocomplete="off">' +
    '<label>当前背景文件名</label>' +
    '<input id="ie-bg" type="text" value="' + escapeHtml(inst.bg || '') + '" placeholder="例如 bg.png" autocomplete="off">' +
    '<label>上传新背景（可选）</label>' +
    '<input id="ie-bg-file" type="file" accept="image/*">' +
    '<div class="hint" style="margin-top:14px;">修改类型后需要 <b>重启面板</b> 生效。</div>';

  openModal('编辑实例 ' + inst.id, html, function () {
    var newType = document.getElementById('ie-type').value || 'mc';
    var name = document.getElementById('ie-name').value.trim();
    var desc = document.getElementById('ie-desc').value.trim();
    var bg = document.getElementById('ie-bg').value.trim();
    var fileInput = document.getElementById('ie-bg-file');
    var file = fileInput && fileInput.files && fileInput.files[0];
    if (!name) { toast('名字不能为空', 'err'); return; }
    closeModal();

    var finish = function (bgName) {
      postApi('/api/instances/update', {
        id: inst.id,
        type: newType,
        nameB64: b64enc(name),
        descB64: b64enc(desc),
        bg: bgName
      }).then(function (r) {
        if (r && r.ok) {
          toast('已更新', 'ok');
          if (newType !== currentType) toast('类型已修改，请重启面板生效', 'err');
          instanceListRefresh(true);
        } else {
          toast((r && r.error) || '更新失败', 'err');
        }
      });
    };

    if (file) {
      if (!file.type.startsWith('image/')) {
        toast('背景图仅支持图片格式', 'err');
        return;
      }
      uploadInstanceBg(inst.id, file).then(function () {
        finish(file.name.replace(/\.[^.]+$/, '.webp'));
      }).catch(function () {
        toast('背景上传失败', 'err');
      });
    } else {
      finish(bg);
    }
  });

  mountInstanceTypeDropdown('ie-type-dd', 'ie-type', currentType);
}

// ===================== 实例插件 =====================
function pluginsRefresh() {
  if (panelExited) return;
  postApi('/api/plugins/list', {}).then(list => {
    var tbody = document.getElementById('plugins-list');
    if (!tbody) return;
    tbody.innerHTML = '';
    if (!list || list.length === 0) {
      tbody.innerHTML = '<tr><td colspan="6" class="empty">暂无实例插件</td></tr>';
      return;
    }
    list.forEach(p => {
      var tr = document.createElement('tr');
      var td1 = document.createElement('td'); td1.textContent = p.name || p.jarName;
      var td2 = document.createElement('td'); td2.className = 'dim'; td2.textContent = p.version || '-';
      var td3 = document.createElement('td'); td3.className = 'dim'; td3.textContent = p.author || '-';
      var td4 = document.createElement('td'); td4.className = 'dim'; td4.textContent = p.description || '-';
      var td5 = document.createElement('td');
      if (p.loaded && p.enabled) td5.innerHTML = '<span style="color:#3ddc84">● 已启用</span>';
      else if (p.loaded) td5.innerHTML = '<span style="color:#f0c040">○ 已加载</span>';
      else td5.innerHTML = '<span style="color:#8b94ab">— 未加载</span>';
      
      var td6 = document.createElement('td');
      var b1 = document.createElement('button'); b1.className = 'btn small';
      if (!p.loaded) {
        b1.textContent = '加载';
        b1.onclick = () => pluginsAct('load', p.jarName);
      } else if (p.enabled) {
        b1.textContent = '禁用';
        b1.onclick = () => pluginsAct('disable', p.jarName);
      } else {
        b1.textContent = '启用';
        b1.className = 'btn small primary';
        b1.onclick = () => pluginsAct('enable', p.jarName);
      }
      td6.appendChild(b1);
      var b2 = document.createElement('button');
      b2.className = 'btn small danger'; b2.textContent = '删除'; b2.style.marginLeft = '6px';
      b2.onclick = () => {
        uiConfirm({title: '删除插件', message: '确定删除 ' + p.jarName + '？', danger: true, okText: '删除'}).then(ok => {
          if (ok) pluginsAct('delete', p.jarName);
        });
      };
      td6.appendChild(b2);
      
      tr.appendChild(td1); tr.appendChild(td2); tr.appendChild(td3); tr.appendChild(td4); tr.appendChild(td5); tr.appendChild(td6);
      tbody.appendChild(tr);
    });
  }).catch(() => toast('读取插件列表失败', 'err'));
}

function pluginsAct(action, jarName) {
  postApi('/api/plugins/' + action, { jarName: jarName }).then(r => {
    if (r && r.ok) { toast('已' + action, 'ok'); pluginsRefresh(); }
    else toast((r && r.error) || '操作失败', 'err');
  });
}

function pluginsUploadClick() {
  var inp = document.getElementById('plugins-upload-input');
  if (inp) inp.click();
}

function pluginsUpload(e) {
  var file = e.target.files[0];
  if (!file) return;
  if (!file.name.endsWith('.jar')) { toast('只支持 .jar', 'err'); e.target.value = ''; return; }
  fetch('/api/plugins/upload?name=' + encodeURIComponent(file.name), {
    method: 'POST', body: file
  }).then(r => r.json()).then(r => {
    if (r && r.ok) { toast('上传成功', 'ok'); pluginsRefresh(); }
    else toast((r && r.error) || '上传失败', 'err');
  }).catch(() => toast('上传出错', 'err'));
  e.target.value = '';
}

// 初始化绑定事件
setTimeout(() => {
  var inp = document.getElementById('plugins-upload-input');
  if (inp) inp.addEventListener('change', pluginsUpload);
}, 100);
