// ===================== 服务器配置 =====================

var spData = [];
var spVelocitySections = [];
var spDirty = {};
var spFilter = '';
var spExists = false;
var spEula = { exists: false, agreed: false };
var spType = 'mc';

// ===================== 自定义下拉组件 =====================

function spBuildDropdown(value, options, onChange) {
  var wrap = document.createElement('div');
  wrap.className = 'sp-dd';

  var btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'sp-dd-btn';
  btn.dataset.value = value;

  function findLabel(v) {
    for (var i = 0; i < options.length; i++) {
      if (options[i][0] === v) return options[i][1];
    }
    return v;
  }
  btn.textContent = findLabel(value);

  var menu = document.createElement('div');
  menu.className = 'sp-dd-menu';
  options.forEach(function (opt) {
    var item = document.createElement('div');
    item.className = 'sp-dd-item';
    item.dataset.value = opt[0];
    item.textContent = opt[1];
    if (opt[0] === value) item.classList.add('selected');
    item.onclick = function (e) {
      e.stopPropagation();
      btn.textContent = opt[1];
      btn.dataset.value = opt[0];
      menu.querySelectorAll('.sp-dd-item').forEach(function (x) { x.classList.remove('selected'); });
      item.classList.add('selected');
      wrap.classList.remove('open');
      onChange(opt[0]);
    };
    menu.appendChild(item);
  });

  btn.onclick = function (e) {
    e.stopPropagation();
    document.querySelectorAll('.sp-dd.open').forEach(function (x) {
      if (x !== wrap) x.classList.remove('open');
    });
    wrap.classList.toggle('open');
  };

  wrap.appendChild(btn);
  wrap.appendChild(menu);
  return wrap;
}

document.addEventListener('click', function () {
  document.querySelectorAll('.sp-dd.open').forEach(function (x) { x.classList.remove('open'); });
});

// ===================== 工具 =====================

function spOrigValueMc(key) {
  for (var i = 0; i < spData.length; i++) {
    if (spData[i].key === key) return spData[i].value;
  }
  return '';
}

// ===================== 刷新入口 =====================

function spRefresh() {
  if (panelExited || !currentInstanceId) return;

  fetch('/api/serverprops/type').then(function (r) { return r.json(); }).then(function (t) {
    spType = (t && t.type) || 'mc';
    spDirty = {};
    if (spType === 'velocity') {
      spRefreshVelocity();
    } else {
      spRefreshMc();
    }
  }).catch(function (e) {
    console.error(e);
    spType = 'mc';
    spRefreshMc();
  });
}

function spFilterChange() {
  var el = document.getElementById('sp-search');
  spFilter = el ? el.value.trim().toLowerCase() : '';
  if (spType === 'velocity') {
    spRenderVelocity();
  } else {
    spRenderMc();
  }
}

function spUpdateSaveBtn() {
  var btn = document.getElementById('sp-save-all');
  if (!btn) return;
  var n = Object.keys(spDirty).length;
  if (n === 0) {
    btn.disabled = true;
    btn.textContent = '保存所有更改';
  } else {
    btn.disabled = false;
    btn.textContent = '保存所有更改 (' + n + ')';
  }
}

function spSaveAll() {
  if (spType === 'velocity') spSaveAllVelocity();
  else spSaveAllMc();
}

function spMarkDirtyRows() {
  var container = document.getElementById('sp-container');
  if (!container) return;
  container.querySelectorAll('tr[data-key]').forEach(function (tr) {
    var k = tr.dataset.key;
    if (spDirty[k] !== undefined) tr.classList.add('sp-row-dirty');
    else tr.classList.remove('sp-row-dirty');
  });
  container.querySelectorAll('tr[data-line]').forEach(function (tr) {
    var line = tr.dataset.line;
    if (spDirty[line] !== undefined) tr.classList.add('sp-row-dirty');
    else tr.classList.remove('sp-row-dirty');
  });
}

// ===================== MC =====================

function spRefreshMc() {
  var eula = document.getElementById('sp-eula-card');
  if (eula) eula.style.display = '';
  var search = document.getElementById('sp-search');
  if (search) search.style.display = '';

  fetch('/api/serverprops/get').then(function (r) { return r.json(); }).then(function (d) {
    if (d && d.error) {
      var container = document.getElementById('sp-container');
      if (container) container.innerHTML =
        '<div class="placeholder">' + escapeHtml(d.error) + '</div>';
      return;
    }
    spExists = !!d.exists;
    spData = (d && d.items) || [];
    spDirty = {};
    spEula = (d && d.eula) || { exists: false, agreed: false };
    spUpdateEulaUI();
    spRenderMc();
    spUpdateSaveBtn();
  }).catch(function (e) {
    console.error(e);
    toast('读取 server.properties 失败', 'err');
  });
}

function spUpdateEulaUI() {
  var t = document.getElementById('sp-eula-toggle');
  var card = document.getElementById('sp-eula-card');
  if (t) t.checked = !!spEula.agreed;
  if (card) {
    if (spEula.agreed) card.classList.add('agreed');
    else card.classList.remove('agreed');
  }
}

function spToggleEula(el) {
  var agreed = el.checked;
  postApi('/api/serverprops/eula', { agreed: agreed ? 'true' : 'false' }).then(function (r) {
    if (r && r.ok) {
      spEula.agreed = !!r.agreed;
      spEula.exists = true;
      spUpdateEulaUI();
      toast(agreed ? '已同意 EULA' : '已取消同意 EULA', 'ok');
    } else {
      toast((r && r.error) || '操作失败', 'err');
      el.checked = !el.checked;
    }
  });
}

function spOnChangeMc(key, value) {
  var orig = spOrigValueMc(key);
  if (value === orig) delete spDirty[key];
  else spDirty[key] = value;
  spMarkDirtyRows();
  spUpdateSaveBtn();
}

function spSaveAllMc() {
  var items = Object.keys(spDirty).map(function (k) {
    return { key: k, value: spDirty[k] };
  });
  if (items.length === 0) return;

  var btn = document.getElementById('sp-save-all');
  if (btn) { btn.disabled = true; btn.textContent = '保存中...'; }

  postApi('/api/serverprops/set', { items: items }).then(function (r) {
    if (r && r.ok) {
      toast('已保存 ' + (r.changed || items.length) + ' 项', 'ok');
      spRefreshMc();
    } else {
      toast((r && r.error) || '保存失败', 'err');
      spUpdateSaveBtn();
    }
  }).catch(function () {
    toast('保存出错', 'err');
    spUpdateSaveBtn();
  });
}

function spRenderMc() {
  var container = document.getElementById('sp-container');
  if (!container) return;

  if (!spExists) {
    container.innerHTML =
      '<div class="placeholder">server.properties 不存在<br>'
      + '<span style="font-size:12px;color:var(--text-dim);">'
      + '先勾选上方的 EULA，然后启动一次 MC，MC 会自动生成默认配置文件</span></div>';
    return;
  }

  var q = spFilter.toLowerCase();
  var filtered = spData.filter(function (it) {
    if (!q) return true;
    return it.key.toLowerCase().indexOf(q) >= 0
        || (it.label || it.key).toLowerCase().indexOf(q) >= 0
        || String(it.value).toLowerCase().indexOf(q) >= 0;
  });

  container.innerHTML = '';
  if (filtered.length === 0) {
    container.innerHTML = '<div class="placeholder">没有匹配的配置项</div>';
    return;
  }

  var table = document.createElement('table');
  table.className = 'cfg-table sp-table';

  filtered.forEach(function (it) {
    var tr = document.createElement('tr');
    tr.dataset.key = it.key;

    var tdK = document.createElement('td');
    tdK.className = 'cfg-key';
    var labelDiv = document.createElement('div');
    labelDiv.textContent = it.label || it.key;
    labelDiv.style.color = '#e8ebf5';
    labelDiv.style.fontFamily = 'inherit';
    labelDiv.style.fontSize = '13px';
    labelDiv.style.marginBottom = '2px';
    var keyDiv = document.createElement('div');
    keyDiv.textContent = it.key;
    keyDiv.style.color = 'var(--text-dim)';
    keyDiv.style.fontSize = '11px';
    keyDiv.style.fontFamily = 'var(--mono)';
    keyDiv.style.opacity = '0.7';
    tdK.appendChild(labelDiv);
    tdK.appendChild(keyDiv);

    var tdV = document.createElement('td');
    tdV.className = 'cfg-val';
    var type = it.type || 'string';
    var ctl = null;

    if (type === 'bool') {
      var boolOpts = [['true', '启用'], ['false', '禁用']];
      var curBool = (it.value === 'true') ? 'true' : 'false';
      ctl = spBuildDropdown(curBool, boolOpts, function (v) {
        spOnChangeMc(it.key, v);
      });
      tdV.appendChild(ctl);
    } else {
      ctl = document.createElement('input');
      ctl.type = 'text';
      ctl.className = 'sp-input';
      ctl.value = it.value;
      ctl.addEventListener('input', function () {
        spOnChangeMc(it.key, ctl.value);
      });
      tdV.appendChild(ctl);
    }

    tr.appendChild(tdK);
    tr.appendChild(tdV);
    table.appendChild(tr);
  });

  container.appendChild(table);
  spMarkDirtyRows();
}

// ===================== Velocity =====================

function spRefreshVelocity() {
  var eula = document.getElementById('sp-eula-card');
  if (eula) eula.style.display = 'none';
  var search = document.getElementById('sp-search');
  if (search) search.style.display = '';

  fetch('/api/serverprops/get').then(function (r) { return r.json(); }).then(function (d) {
    if (d && d.error) {
      var container = document.getElementById('sp-container');
      if (container) container.innerHTML =
        '<div class="placeholder">' + escapeHtml(d.error) + '</div>';
      return;
    }
    spExists = !!d.exists;
    spVelocitySections = (d && d.sections) || [];
    spDirty = {};
    spRenderVelocity();
    spUpdateSaveBtn();
  }).catch(function (e) {
    console.error(e);
    toast('读取 velocity.toml 失败', 'err');
  });
}

function spOnChangeVelocity(line, origValue, newValue) {
  if (String(newValue) === String(origValue)) {
    delete spDirty[line];
  } else {
    spDirty[line] = newValue;
  }
  spMarkDirtyRows();
  spUpdateSaveBtn();
}

function spSaveAllVelocity() {
  var items = Object.keys(spDirty).map(function (line) {
    return { line: parseInt(line, 10), value: spDirty[line] };
  });
  if (items.length === 0) return;

  var btn = document.getElementById('sp-save-all');
  if (btn) { btn.disabled = true; btn.textContent = '保存中...'; }

  postApi('/api/serverprops/set', { items: items }).then(function (r) {
    if (r && r.ok) {
      toast('已保存 ' + (r.changed || items.length) + ' 项', 'ok');
      spRefreshVelocity();
    } else {
      toast((r && r.error) || '保存失败', 'err');
      spUpdateSaveBtn();
    }
  }).catch(function () {
    toast('保存出错', 'err');
    spUpdateSaveBtn();
  });
}

function spBuildVelocityControl(it) {
  var onChange = function (v) { spOnChangeVelocity(it.line, it.value, v); };
  var type = it.type || 'string';

  if (type === 'bool') {
    var boolOpts = [['true', '启用'], ['false', '禁用']];
    var cur = (String(it.value).toLowerCase() === 'true') ? 'true' : 'false';
    return spBuildDropdown(cur, boolOpts, onChange);
  }
  if (type === 'int' || type === 'float') {
    var inp = document.createElement('input');
    inp.type = 'number';
    inp.className = 'sp-input';
    inp.value = it.value;
    inp.addEventListener('input', function () { onChange(inp.value); });
    return inp;
  }
  var inp2 = document.createElement('input');
  inp2.type = 'text';
  inp2.className = 'sp-input';
  inp2.value = it.value;
  inp2.addEventListener('input', function () { onChange(inp2.value); });
  return inp2;
}

function spRenderVelocity() {
  var container = document.getElementById('sp-container');
  if (!container) return;

  if (!spExists || spVelocitySections.length === 0) {
    container.innerHTML = '<div class="placeholder">velocity.toml 不存在或为空<br>'
      + '<span style="font-size:12px;color:var(--text-dim);">'
      + '启动一次 Velocity 后它会自动生成</span></div>';
    return;
  }

  container.innerHTML = '';

  var q = spFilter.toLowerCase();
  var hasAnyMatch = false;

  spVelocitySections.forEach(function (sec) {
    var items = (sec.items || []).filter(function (it) {
      if (!q) return true;
      return it.key.toLowerCase().indexOf(q) >= 0
          || (it.label || '').toLowerCase().indexOf(q) >= 0
          || String(it.value).toLowerCase().indexOf(q) >= 0;
    });
    if (items.length === 0) return;
    hasAnyMatch = true;

    var title = document.createElement('div');
    title.className = 'cfg-group-title';
    title.textContent = sec.label || sec.name || '基础';
    container.appendChild(title);

    var table = document.createElement('table');
    table.className = 'cfg-table sp-table';

    items.forEach(function (it) {
      var tr = document.createElement('tr');
      tr.dataset.line = String(it.line);

      var tdK = document.createElement('td');
      tdK.className = 'cfg-key';
      var labelDiv = document.createElement('div');
      labelDiv.textContent = it.label || it.key;
      labelDiv.style.color = '#e8ebf5';
      labelDiv.style.fontFamily = 'inherit';
      labelDiv.style.fontSize = '13px';
      labelDiv.style.marginBottom = '2px';
      var keyDiv = document.createElement('div');
      keyDiv.textContent = it.key;
      keyDiv.style.color = 'var(--text-dim)';
      keyDiv.style.fontSize = '11px';
      keyDiv.style.fontFamily = 'var(--mono)';
      keyDiv.style.opacity = '0.7';
      tdK.appendChild(labelDiv);
      tdK.appendChild(keyDiv);

      var tdV = document.createElement('td');
      tdV.className = 'cfg-val';
      tdV.appendChild(spBuildVelocityControl(it));

      tr.appendChild(tdK);
      tr.appendChild(tdV);
      table.appendChild(tr);
    });

    container.appendChild(table);
  });

  if (!hasAnyMatch) {
    container.innerHTML = '<div class="placeholder">没有匹配的配置项</div>';
    return;
  }

  spMarkDirtyRows();
}