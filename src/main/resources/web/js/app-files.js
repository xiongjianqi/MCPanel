// ===================== 文件管理 =====================
var currentPath = '/';
var selectedPaths = new Set();
var clipboard = null;

function filesInitPath() {
  if (currentInstanceId) {
    currentPath = '/server';
  } else {
    currentPath = '/';
  }
  selectedPaths = new Set();
}

function shouldHideEntry(name, isDir) {
  if (isDir) {
    if (name === 'backup') return !hasPerm('backup');
    if (name === 'plugins' || name === 'plugins-data') return !hasPerm('plugins');
    if (name === 'logs') return !hasPerm('terminal');
    return false;
  }
  if (name === 'config.properties' || name === 'mcperms.json') {
    return !hasPerm('config');
  }
  return false;
}

function makeMenuAction(iconSvg, text, isDanger, onClick) {
  var a = document.createElement('a');
  a.style.display = 'flex';
  a.style.alignItems = 'center';
  a.style.gap = '10px';
  a.innerHTML = iconSvg + ' <span>' + text + '</span>';
  if (isDanger) a.style.color = '#ff5c5c';
  a.onclick = function(e) { e.stopPropagation(); onClick(); };
  return a;
}

function showFileActionMenu(e, f) {
  e.stopPropagation();
  
  var btn = e.currentTarget;
  var oldMenu = document.getElementById('global-file-menu');
  
  // ★ 修复2：如果点击的是已经展开的同一个按钮，则收起下拉栏
  if (btn.classList.contains('active') && oldMenu) {
    btn.classList.remove('active');
    oldMenu.classList.remove('show');
    setTimeout(function() { if (oldMenu.parentNode) oldMenu.remove(); }, 150);
    return;
  }
  
  // 清理已存在的旧菜单
  if (oldMenu) {
    oldMenu.classList.remove('show');
    setTimeout(function() { if (oldMenu.parentNode) oldMenu.remove(); }, 150);
  }
  document.querySelectorAll('.btn-file-op.active').forEach(function(b) { b.classList.remove('active'); });

  // 激活当前按钮，箭头翻转
  btn.classList.add('active');

  var menu = document.createElement('div');
  menu.id = 'global-file-menu';
  menu.className = 'file-action-menu'; 

  var closeAnd = function(fn) { 
    return function() { 
      var activeBtn = document.querySelector('.btn-file-op.active');
      if (activeBtn) activeBtn.classList.remove('active');
      
      menu.classList.remove('show');
      setTimeout(function() { if (menu.parentNode) menu.remove(); }, 150);
      fn(); 
    }; 
  };

  if (!f.isDir) {
    var iconDown = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg>';
    menu.appendChild(makeMenuAction(iconDown, '下载', false, closeAnd(function() { filesDownload(f.name); })));

    if (hasPerm('share')) {
      var iconShare = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><circle cx="18" cy="5" r="3"></circle><circle cx="6" cy="12" r="3"></circle><circle cx="18" cy="19" r="3"></circle><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"></line><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"></line></svg>';
      menu.appendChild(makeMenuAction(iconShare, '分享', false, closeAnd(function() { filesShare(f.name); })));
    }
  } else {
    var iconZip = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"></path><polyline points="3.27 6.96 12 12.01 20.73 6.96"></polyline><line x1="12" y1="22.08" x2="12" y2="12"></line></svg>';
    menu.appendChild(makeMenuAction(iconZip, '下载为ZIP', false, closeAnd(function() {
      toast('正在打包压缩包，请稍候...');
      postApi('/api/files/compress', { paths: [pathJoin(currentPath, f.name)], target: pathJoin(currentPath, f.name + '.zip') }).then(function(r) {
        if (r && r.ok) { filesDownload(f.name + '.zip'); }
        else toast((r && r.error) || '打包失败', 'err');
      });
    })));
  }

  if (!f.isDir) {
    var iconEdit = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path></svg>';
    menu.appendChild(makeMenuAction(iconEdit, '编辑', false, closeAnd(function() { filesEdit(f.name, f.size); })));
  }

  if (f.isArchive) {
    var iconExtract = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="17 8 12 3 7 8"></polyline><line x1="12" y1="3" x2="12" y2="15"></line></svg>';
    menu.appendChild(makeMenuAction(iconExtract, '解压', false, closeAnd(function() { filesExtract(f.name); })));
  }

  var iconCopy = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>';
  menu.appendChild(makeMenuAction(iconCopy, '复制', false, closeAnd(function() {
    clipboard = { action: 'copy', paths: [pathJoin(currentPath, f.name)] };
    var info = document.getElementById('files-clip-info');
    var txt = document.getElementById('files-clip-text');
    if (info && txt) { txt.textContent = '已复制 1 项'; info.style.display = 'flex'; }
    toast('已复制 1 项', 'ok');
    filesRefresh();
  })));

  var iconCut = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><circle cx="6" cy="6" r="3"></circle><circle cx="6" cy="18" r="3"></circle><line x1="20" y1="4" x2="8.12" y2="15.88"></line><line x1="14.47" y1="14.48" x2="20" y2="20"></line><line x1="8.12" y1="8.12" x2="12" y2="12"></line></svg>';
  menu.appendChild(makeMenuAction(iconCut, '剪切', false, closeAnd(function() {
    clipboard = { action: 'cut', paths: [pathJoin(currentPath, f.name)] };
    var info = document.getElementById('files-clip-info');
    var txt = document.getElementById('files-clip-text');
    if (info && txt) { txt.textContent = '已剪切 1 项'; info.style.display = 'flex'; }
    toast('已剪切 1 项', 'ok');
    filesRefresh();
  })));

  var iconCompress = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><polyline points="4 14 10 14 10 20"></polyline><polyline points="20 10 14 10 14 4"></polyline><line x1="14" y1="10" x2="21" y2="3"></line><line x1="3" y1="21" x2="10" y2="14"></line></svg>';
  menu.appendChild(makeMenuAction(iconCompress, '仅压缩', false, closeAnd(function() {
    uiPrompt({ title: '压缩', label: '压缩包名', value: f.name + '.zip' }).then(function(name) {
      if (!name) return;
      if (!name.toLowerCase().endsWith('.zip')) name += '.zip';
      postApi('/api/files/compress', { paths: [pathJoin(currentPath, f.name)], target: pathJoin(currentPath, name) }).then(function(r) {
        if (r && r.ok) { toast('压缩完成', 'ok'); filesRefresh(); }
        else toast((r && r.error) || '压缩失败', 'err');
      });
    });
  })));

  var iconRename = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"></path><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"></path></svg>';
  menu.appendChild(makeMenuAction(iconRename, '重命名', false, closeAnd(function() { filesRename(f.name); })));

  var iconDel = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"></polyline><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path></svg>';
  menu.appendChild(makeMenuAction(iconDel, '删除', true, closeAnd(function() { filesDelete(f.name); })));

  document.body.appendChild(menu);

  // ★ 修复1：基于按钮位置计算菜单的位置，而不再是鼠标点击的位置
  var rect = btn.getBoundingClientRect();
  var menuRect = menu.getBoundingClientRect();
  var menuWidth = menuRect.width;
  var menuHeight = menuRect.height;
  
  var left = rect.left;
  var top = rect.bottom + 6;
  
  // 水平防溢出
  if (left + menuWidth > window.innerWidth - 10) {
    left = rect.right - menuWidth;
  }
  if (left < 10) left = 10;
  
  // 垂直防溢出（判断向上还是向下）
  if (rect.bottom + menuHeight + 10 > window.innerHeight) {
    // 下方空间不够，向上弹出
    top = rect.top - menuHeight - 6;
    menu.classList.add('drop-up');
  }
  
  menu.style.left = left + 'px';
  menu.style.top = top + 'px';
  
  // 触发显示动画
  setTimeout(function() { menu.classList.add('show'); }, 10);
}

document.addEventListener('click', function(e) {
  var oldMenu = document.getElementById('global-file-menu');
  if (oldMenu && !oldMenu.contains(e.target)) {
    oldMenu.classList.remove('show');
    setTimeout(function() { if (oldMenu.parentNode) oldMenu.remove(); }, 150);
    document.querySelectorAll('.btn-file-op.active').forEach(function(btn) { btn.classList.remove('active'); });
  }
});

// ★ 修复1续：当页面滚动时，关闭下拉栏以防位置脱离
window.addEventListener('scroll', function() {
  var oldMenu = document.getElementById('global-file-menu');
  if (oldMenu) {
    oldMenu.classList.remove('show');
    setTimeout(function() { if (oldMenu.parentNode) oldMenu.remove(); }, 150);
    document.querySelectorAll('.btn-file-op.active').forEach(function(btn) { btn.classList.remove('active'); });
  }
}, true);

function filesToggleBatchMenu(ev) {
  if (ev) ev.stopPropagation();
  var m = document.getElementById('files-batch-menu');
  var btn = document.getElementById('files-batch-btn');
  if (m) {
    var isShow = m.classList.contains('show');
    m.classList.toggle('show');
    if (btn) btn.classList.toggle('active', !isShow);
  }
}

function filesCloseBatchMenu() {
  var m = document.getElementById('files-batch-menu');
  if (m && m.classList.contains('show')) {
    m.classList.remove('show');
    var btn = document.getElementById('files-batch-btn');
    if (btn) btn.classList.remove('active');
  }
}
document.addEventListener('click', filesCloseBatchMenu);

function filesRefresh() {
  if (panelExited) return;
  postApi('/api/files/list', { path: currentPath }).then(function (res) {
    if (panelExited) return;
    var tbody = document.getElementById('files-list');
    if (!tbody) return;
    
    if (res && res.error) {
      tbody.innerHTML = '<tr><td colspan="5" class="empty" style="color:#ff5c5c;">加载失败：' + res.error + '</td></tr>';
      updateFilesToolbar();
      return;
    }
    
    tbody.innerHTML = '';
    
    var pathEl = document.getElementById('files-path');
    if (pathEl) {
      var mode = hasPerm('files_all') && !currentInstanceId ? '完全' : '实例';
      pathEl.textContent = '[' + mode + '] ' + (currentPath || '/');
    }
    if (!res || !res.files || res.files.length === 0) {
      tbody.innerHTML = '<tr><td colspan="5" class="empty">空目录</td></tr>';
      updateFilesToolbar(); return;
    }
    var files = res.files.filter(function (f) {
      return !shouldHideEntry(f.name, f.isDir);
    });
    if (files.length === 0) {
      tbody.innerHTML = '<tr><td colspan="5" class="empty">空目录</td></tr>';
      updateFilesToolbar(); return;
    }

    var valid = new Set();
    files.forEach(function (f) { valid.add(f.name); });
    var ns = new Set();
    selectedPaths.forEach(function (n) { if (valid.has(n)) ns.add(n); });
    selectedPaths = ns;
    
    files.sort(function (a, b) {
      var da = a.isDir ? 1 : 0, db = b.isDir ? 1 : 0;
      if (da !== db) return db - da;
      return String(a.name || '').localeCompare(String(b.name || ''));
    });
    
    files.forEach(function (f) {
      var tr = document.createElement('tr');
      var tdChk = document.createElement('td');
      var chk = document.createElement('input');
      chk.type = 'checkbox'; chk.className = 'row-check';
      chk.checked = selectedPaths.has(f.name);
      chk.onchange = function () {
        if (chk.checked) selectedPaths.add(f.name); else selectedPaths.delete(f.name);
        tr.classList.toggle('row-selected', chk.checked);
        updateFilesToolbar();
      };
      tdChk.appendChild(chk); tr.appendChild(tdChk);

      var tdName = document.createElement('td');
      var iconSpan = document.createElement('span');
      iconSpan.className = 'file-icon';
      
      var iconSvg = '';
      if (f.isDir) {
        iconSvg = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path></svg>';
      } else if (f.isImage) {
        iconSvg = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect><circle cx="8.5" cy="8.5" r="1.5"></circle><polyline points="21 15 16 10 5 21"></polyline></svg>';
      } else if (f.isVideo) {
        iconSvg = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="23 7 16 12 23 17 23 7"></polygon><rect x="1" y="5" width="15" height="14" rx="2" ry="2"></rect></svg>';
      } else if (f.isAudio) {
        iconSvg = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18V5l12-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="18" cy="16" r="3"></circle></svg>';
      } else {
        iconSvg = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"></path><polyline points="13 2 13 9 20 9"></polyline></svg>';
      }
      iconSpan.innerHTML = iconSvg;
      tdName.appendChild(iconSpan);
      
      var link = document.createElement('a');
      link.className = 'file-link'; link.href = 'javascript:void(0)';
      link.textContent = f.name;
      link.onclick = function () { filesOpen(f.name, f); };
      tdName.appendChild(link); tr.appendChild(tdName);

      var tdSize = document.createElement('td');
      tdSize.className = 'dim'; tdSize.textContent = f.isDir ? '-' : fmtSize(f.size);
      tr.appendChild(tdSize);
      var tdTime = document.createElement('td');
      tdTime.className = 'dim';
      var d = new Date(f.mtime || 0);
      tdTime.textContent = isNaN(d.getTime()) ? '-' : d.toLocaleString();
      tr.appendChild(tdTime);

      var tdAct = document.createElement('td');
      var actBtn = document.createElement('button');
      // ★ 新增 btn-file-op 专属类名，防止和批量操作按钮冲突
      actBtn.className = 'btn small btn-action btn-file-op';
      actBtn.innerHTML = '<span>操作</span> <svg class="arrow-svg" viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="margin-left:4px;"><polyline points="6 9 12 15 18 9"></polyline></svg>';
      actBtn.onclick = function (e) {
        showFileActionMenu(e, f);
      };
      tdAct.appendChild(actBtn);
      tr.appendChild(tdAct);
      
      if (chk.checked) tr.classList.add('row-selected');
      tbody.appendChild(tr);
    });
    updateFilesToolbar();
  }).catch(function(e) {
    console.error(e);
    var tb = document.getElementById('files-list');
    if (tb) tb.innerHTML = '<tr><td colspan="5" class="empty">加载失败</td></tr>';
  });
}

function updateFilesToolbar() {
  var has = selectedPaths.size > 0;
  
  var unselBtn = document.getElementById('files-unselect-btn');
  if (unselBtn) unselBtn.style.display = has ? 'inline-flex' : 'none';
  
  var pasteBtn = document.getElementById('btn-files-paste-dynamic');
  if (!pasteBtn) {
    var toolbar = document.querySelector('#tab-files .toolbar');
    if (toolbar) {
      pasteBtn = document.createElement('button');
      pasteBtn.id = 'btn-files-paste-dynamic';
      pasteBtn.className = 'btn primary';
      pasteBtn.textContent = '粘贴';
      pasteBtn.onclick = filesPaste;
      toolbar.appendChild(pasteBtn); 
    }
  }
  if (pasteBtn) {
    pasteBtn.style.display = clipboard ? 'inline-flex' : 'none';
  }

  var batchDropdown = document.getElementById('files-batch-dropdown');
  if (batchDropdown) batchDropdown.style.display = has ? 'inline-block' : 'none';
}

function filesSelectAll() {
  postApi('/api/files/list', { path: currentPath }).then(function (res) {
    if (!res || !res.files) return;
    selectedPaths = new Set(
      res.files
        .filter(function (f) { return !shouldHideEntry(f.name, f.isDir); })
        .map(function (f) { return f.name; })
    );
    filesRefresh();
  });
}

function filesUnselectAll() { selectedPaths = new Set(); filesRefresh(); }

function filesBatchDownload() {
  if (selectedPaths.size === 0) { toast('请先勾选文件', 'err'); return; }
  uiConfirm({
    title: '批量下载',
    message: '确定要下载选中的 ' + selectedPaths.size + ' 个文件吗？\n\n提示：由于浏览器限制，可能会拦截自动打开的下载标签页，请允许本站点打开多个标签页。',
    okText: '开始下载'
  }).then(function(ok) {
    if (!ok) return;
    var paths = Array.from(selectedPaths).map(function(n) { return pathJoin(currentPath, n); });
    var i = 0;
    function triggerNext() {
      if (panelExited || i >= paths.length) {
        if (!panelExited) toast('所有下载任务已推送', 'ok');
        return;
      }
      var p = paths[i++];
      postApi('/api/files/prepare-download', { path: p }).then(function(r) {
        if (r && r.url) {
          var a = document.createElement('a');
          a.href = r.url;
          a.target = '_blank';
          a.style.display = 'none';
          document.body.appendChild(a);
          a.click();
          a.remove();
        }
        setTimeout(triggerNext, 400);
      }).catch(function() {
        setTimeout(triggerNext, 400);
      });
    }
    triggerNext();
  });
  filesCloseBatchMenu();
}

function filesOpen(name, meta) {
  if (!meta) return;
  if (meta.isDir) {
    currentPath = pathJoin(currentPath, name);
    selectedPaths = new Set();
    filesRefresh();
    return;
  }
  var ext = (name.split('.').pop() || '').toLowerCase();
  if (ext === 'flac') { filesDownload(name); return; }
  if (meta.isImage || meta.isVideo || meta.isAudio) { previewFile(name, meta); return; }
  if (!meta.isDir) { filesEdit(name, meta.size); return; }
  filesDownload(name);
}

function filesUp() {
  if (currentPath === '/' || currentPath === '') return;
  var p = currentPath.replace(/\/$/, '');
  p = p.substring(0, p.lastIndexOf('/'));
  currentPath = p || '/'; selectedPaths = new Set(); filesRefresh();
}

function filesToggleNewMenu(ev) {
  if (ev) ev.stopPropagation();
  var m = document.getElementById('files-new-menu');
  if (m) m.classList.toggle('show');
}

function filesCloseNewMenu() {
  var m = document.getElementById('files-new-menu');
  if (m) m.classList.remove('show');
}

document.addEventListener('click', filesCloseNewMenu);

function filesMkdir() {
  filesCloseNewMenu();
  uiPrompt({ title: '新建文件夹', label: '文件夹名', placeholder: '例如：plugins' })
    .then(function (name) {
      if (!name) return;
      postApi('/api/files/mkdir', { path: pathJoin(currentPath, name) }).then(filesRefresh);
    });
}

function filesNewFile() {
  filesCloseNewMenu();
  uiPrompt({ title: '新建文件', label: '文件名（含扩展名）', placeholder: '例如：readme.txt' })
    .then(function (name) {
      if (!name) return;
      postApi('/api/files/newfile', { path: pathJoin(currentPath, name) }).then(function (r) {
        if (r && r.ok) { toast('已创建', 'ok'); filesRefresh(); }
        else toast((r && r.error) || '创建失败', 'err');
      });
    });
}

function filesDelete(name) {
  uiConfirm({
    title: '删除文件',
    message: '确定删除 ' + name + ' 吗？\n此操作不可恢复！',
    danger: true,
    okText: '删除'
  }).then(function (ok) {
    if (!ok) return;
    postApi('/api/files/delete', { path: pathJoin(currentPath, name) }).then(filesRefresh);
  });
}

function filesDeleteSelected() {
  if (selectedPaths.size === 0) { toast('请先勾选文件', 'err'); return; }
  var count = selectedPaths.size;
  uiConfirm({
    title: '删除选中项',
    message: '确定删除选中的 ' + count + ' 项？\n此操作不可恢复！',
    danger: true,
    okText: '删除'
  }).then(function (ok) {
    if (!ok) return;
    var paths = Array.from(selectedPaths).map(function (n) { return pathJoin(currentPath, n); });
    postApi('/api/files/delete', { paths: paths }).then(function () {
      toast('已删除 ' + count + ' 项', 'ok');
      selectedPaths = new Set(); filesRefresh();
    });
  });
  filesCloseBatchMenu();
}

function filesRename(oldName) {
  uiPrompt({ title: '重命名', label: '新名称', value: oldName }).then(function (newName) {
    if (!newName || newName === oldName) return;
    postApi('/api/files/rename', {
      from: pathJoin(currentPath, oldName), to: pathJoin(currentPath, newName)
    }).then(filesRefresh);
  });
}

function filesDownload(name) {
  var full = pathJoin(currentPath, name);
  postApi('/api/files/prepare-download', { path: full }).then(function (r) {
    if (r && r.url) {
      var a = document.createElement('a');
      a.href = r.url; a.download = name;
      document.body.appendChild(a); a.click(); a.remove();
      toast('开始下载：' + name);
    } else toast((r && r.error) || '准备下载失败', 'err');
  });
}

function filesExtract(name) {
  uiConfirm({
    title: '解压文件',
    message: '解压 ' + name + ' 到当前目录？\n同名文件会被覆盖。',
    okText: '解压'
  }).then(function (ok) {
    if (!ok) return;
    postApi('/api/files/extract', { path: pathJoin(currentPath, name) }).then(function (r) {
      if (r && r.ok) { toast('解压完成', 'ok'); filesRefresh(); }
      else toast((r && r.error) || '解压失败', 'err');
    });
  });
}

function filesCopySelected() {
  if (selectedPaths.size === 0) { toast('请先勾选文件', 'err'); return; }
  clipboard = { action: 'copy',
    paths: Array.from(selectedPaths).map(function (n) { return pathJoin(currentPath, n); }) };
  var info = document.getElementById('files-clip-info');
  var txt = document.getElementById('files-clip-text');
  if (info && txt) { txt.textContent = '已复制 ' + clipboard.paths.length + ' 项'; info.style.display = 'flex'; }
  toast('已复制 ' + clipboard.paths.length + ' 项', 'ok'); filesCloseBatchMenu();
  filesUnselectAll();
}

function filesCutSelected() {
  if (selectedPaths.size === 0) { toast('请先勾选文件', 'err'); return; }
  clipboard = { action: 'cut',
    paths: Array.from(selectedPaths).map(function (n) { return pathJoin(currentPath, n); }) };
  var info = document.getElementById('files-clip-info');
  var txt = document.getElementById('files-clip-text');
  if (info && txt) { txt.textContent = '已剪切 ' + clipboard.paths.length + ' 项'; info.style.display = 'flex'; }
  toast('已剪切 ' + clipboard.paths.length + ' 项', 'ok'); filesCloseBatchMenu();
  filesUnselectAll();
}

function filesPaste() {
  if (!clipboard) { toast('剪贴板为空', 'err'); return; }
  var endpoint = clipboard.action === 'copy' ? '/api/files/copy' : '/api/files/move';
  postApi(endpoint, { paths: clipboard.paths, target: currentPath }).then(function (r) {
    if (r && r.ok) {
      toast('粘贴完成', 'ok'); clipboard = null;
      selectedPaths = new Set(); filesRefresh();
      var info = document.getElementById('files-clip-info');
      if (info) info.style.display = 'none';
    } else toast((r && r.error) || '粘贴失败', 'err');
  });
  filesCloseBatchMenu();
}

function filesCompressSelected() {
  if (selectedPaths.size === 0) { toast('请先勾选文件', 'err'); return; }
  var def = 'archive.zip';
  if (selectedPaths.size === 1) {
    var only = Array.from(selectedPaths)[0];
    var dot = only.lastIndexOf('.');
    def = (dot > 0 ? only.substring(0, dot) : only) + '.zip';
  }
  filesCloseBatchMenu();
  uiPrompt({ title: '压缩选中项', label: '压缩包名', value: def }).then(function (name) {
    if (!name) return;
    if (!name.toLowerCase().endsWith('.zip')) name += '.zip';
    var paths = Array.from(selectedPaths).map(function (n) { return pathJoin(currentPath, n); });
    postApi('/api/files/compress', { paths: paths, target: pathJoin(currentPath, name) }).then(function (r) {
      if (r && r.ok) {
        toast('压缩完成：' + name, 'ok');
        selectedPaths = new Set(); filesRefresh();
      } else toast((r && r.error) || '压缩失败', 'err');
    });
  });
}

// ===================== 上传 =====================
function filesUploadClick() { 
  var el = document.getElementById('files-upload-input');
  if (el) el.click(); 
}

(function() {
  var inp = document.getElementById('files-upload-input');
  if (inp) {
    inp.addEventListener('change', function (e) {
      var file = e.target.files[0]; if (!file) return;
      var targetPath = pathJoin(currentPath, file.name);

      var progressEl = document.getElementById('upload-progress');
      var nameEl = document.getElementById('up-name');
      var barEl = document.getElementById('up-bar-fill');
      var pctEl = document.getElementById('up-pct');

      if (progressEl) {
        progressEl.classList.add('show');
        nameEl.textContent = file.name + ' (' + fmtSize(file.size) + ')';
        barEl.style.width = '0%';
        pctEl.textContent = '0%';
      }
      var uploadStartTime = Date.now();

      var xhr = new XMLHttpRequest();
      xhr.open('POST', '/api/files/upload?path=' + encodeURIComponent(targetPath), true);

      xhr.upload.addEventListener('progress', function (ev) {
        if (!ev.lengthComputable) return;
        var pct = Math.round(ev.loaded / ev.total * 100);
        if (barEl) barEl.style.width = pct + '%';
        if (pctEl) {
          var elapsed = (Date.now() - uploadStartTime) / 1000;
          var speed = elapsed > 0 ? ev.loaded / elapsed : 0;
          pctEl.textContent = pct + '% · '
            + fmtSize(ev.loaded) + ' / ' + fmtSize(ev.total)
            + (speed > 0 ? ' · ' + fmtSize(speed) + '/s' : '');
        }
      });

      xhr.addEventListener('load', function () {
        var ok = false, err = null;
        try {
          var r = JSON.parse(xhr.responseText || '{}');
          ok = !!r.ok;
          err = r.error || null;
        } catch (e2) { ok = (xhr.status >= 200 && xhr.status < 300); }
        if (barEl) barEl.style.width = '100%';
        if (pctEl) pctEl.textContent = '100%';
        setTimeout(function () { if (progressEl) progressEl.classList.remove('show'); }, 500);
        if (ok) { toast('上传完成', 'ok'); filesRefresh(); }
        else toast(err || ('上传失败 (' + xhr.status + ')'), 'err');
      });

      xhr.addEventListener('error', function () {
        if (progressEl) progressEl.classList.remove('show');
        toast('上传出错：网络中断', 'err');
      });

      xhr.send(file);
      e.target.value = '';
    });
  }
})();

// ===================== 编辑 =====================
function filesEdit(name, size) {
  if (size > 500 * 1024) { toast('文件过大（超过 500KB），无法打开', 'err'); return; }
  var full = pathJoin(currentPath, name);
  postApi('/api/files/read', { path: full }).then(function (r) {
    if (r && r.content !== undefined) {
      document.getElementById('file-edit-path').value = full;
      document.getElementById('file-edit-name').textContent = name;
      document.getElementById('file-edit-content').value = r.content;
      document.getElementById('file-edit-modal').classList.add('show');
    } else toast((r && r.error) || '读取失败', 'err');
  });
}

function filesSaveEdit() {
  var p = document.getElementById('file-edit-path').value;
  var content = document.getElementById('file-edit-content').value;
  postApi('/api/files/write', { path: p, content: content }).then(function (r) {
    if (r && r.ok) { toast('已保存', 'ok'); filesCloseEdit(); filesRefresh(); }
    else toast((r && r.error) || '保存失败', 'err');
  });
}

function filesCloseEdit() { document.getElementById('file-edit-modal').classList.remove('show'); }

// ===================== 预览 =====================
var previewCurrentName = null;

function previewFile(name, meta) {
  var full = pathJoin(currentPath, name);
  postApi('/api/files/prepare-download', { path: full, mode: 'inline' }).then(function (r) {
    if (!r || !r.url) { toast((r && r.error) || '无法预览', 'err'); return; }
    previewCurrentName = name;
    document.getElementById('preview-name').textContent = name;
    var body = document.getElementById('preview-body');
    body.innerHTML = '';
    var url = r.url;
    var media = null;

    if (meta.isImage) {
      media = document.createElement('img');
      media.alt = name; media.src = url;
      media.onerror = function () { body.innerHTML = '<div style="padding:40px;color:#ff5c5c;text-align:center;">图片加载失败</div>'; };
    } else if (meta.isVideo) {
      media = document.createElement('video');
      media.controls = true; media.preload = 'metadata'; media.src = url;
      media.onerror = function () { body.innerHTML = '<div style="padding:40px;color:#ff5c5c;text-align:center;">浏览器无法播放此视频。</div>'; };
    } else if (meta.isAudio) {
      media = document.createElement('audio');
      media.controls = true; media.preload = 'metadata'; media.src = url;
      media.style.width = '80%'; media.style.margin = '60px auto';
      media.onerror = function () { body.innerHTML = '<div style="padding:40px;color:#ff5c5c;text-align:center;">浏览器无法播放此音频。</div>'; };
    }
    if (media) body.appendChild(media);
    document.getElementById('preview-modal').classList.add('show');
  });
}

function closePreview() {
  document.getElementById('preview-modal').classList.remove('show');
  var body = document.getElementById('preview-body');
  if (body) body.innerHTML = '';
  previewCurrentName = null;
}

function filesDownloadFromPreview() {
  if (previewCurrentName) filesDownload(previewCurrentName);
}

function filesClearClip() {
  clipboard = null;
  var info = document.getElementById('files-clip-info');
  if (info) info.style.display = 'none';
  filesRefresh();
}

// 适配分享接口
function filesShare(name) {
  var full = pathJoin(currentPath, name);
  postApi('/api/share/create', { path: full }).then(function (r) {
    if (r && r.ok) {
      var port = r.sharePort || 33333;
      var proto = location.protocol;
      var host = location.hostname;
      var url = proto + '//' + host + ':' + port + '/share/' + r.token;
      copyToClipboard(url).then(function () {
        toast('分享链接已复制：' + url, 'ok');
      }).catch(function () {
        uiPrompt({ title: '分享链接', label: '请手动复制', value: url });
      });
    } else {
      toast((r && r.error) || '分享失败', 'err');
    }
  }).catch(function () {
    toast('分享请求失败', 'err');
  });
}