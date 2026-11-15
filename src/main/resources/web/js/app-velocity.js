// ===================== Velocity 配置 =====================
var velDirty = {};

function velRefresh() {
    if (panelExited) return;
    var container = document.getElementById('vel-container');
    if (!container) return;
    container.innerHTML = '<div class="placeholder">正在加载 velocity.toml...</div>';

    fetch('/api/serverprops/get').then(r => r.json()).then(d => {
        if (d.error) {
            container.innerHTML = '<div class="placeholder">' + escapeHtml(d.error) + '</div>';
            return;
        }
        if (!d.exists) {
            container.innerHTML = '<div class="placeholder">velocity.toml 不存在。启动一次 Velocity 后会自动生成。</div>';
            return;
        }
        
        velDirty = {};
        container.innerHTML = '';
        
        var portSec = d.sections.find(s => s.name === '');
        var serversSec = d.sections.find(s => s.name === 'servers');
        var tryItem = serversSec ? serversSec.items.find(i => i.key === 'try') : null;
        
        // 1. 基础信息（监听端口、try）
        var basicTitle = document.createElement('h3');
        basicTitle.textContent = '基础设置';
        container.appendChild(basicTitle);
        
        var basicTable = document.createElement('table');
        basicTable.className = 'cfg-table sp-table';
        if (portSec) {
            portSec.items.forEach(it => {
                var tr = renderVelRow(it);
                basicTable.appendChild(tr);
            });
        }
        
        // ★ 渲染 try 字段（复选框模式）
        var trTry = document.createElement('tr');
        var tdKTry = document.createElement('td');
        tdKTry.className = 'cfg-key';
        tdKTry.innerHTML = '<div style="color:#e8ebf5;margin-bottom:2px;">默认进入的服务器 (try)</div><div style="color:var(--text-dim);font-size:11px;font-family:var(--mono);opacity:0.7;">try</div>';
        var tdVTry = document.createElement('td');
        tdVTry.className = 'cfg-val';
        
        // 获取所有可用的后端服务器名
        var serverNames = [];
        if (serversSec && serversSec.items) {
            serversSec.items.forEach(i => { if (i.key !== 'try') serverNames.push(i.key); });
        }
        
        var currentTryStr = tryItem ? tryItem.value : "";
        // ★ 修复：剥离因前端界面显示而混入的序号（例如把 "1. lobby" 变成 "lobby"）
        var selectedTries = currentTryStr.split(',').map(s => s.replace(/^\d+\.\s*/, '').trim()).filter(s => s);
        
        var updateDirty = function() {
            var newVal = selectedTries.join(', ');
            var dirtyKey = tryItem ? tryItem.line : 'try-new';
            if (newVal === currentTryStr) delete velDirty[dirtyKey];
            else velDirty[dirtyKey] = newVal;
            trTry.classList.toggle('sp-row-dirty', velDirty[dirtyKey] !== undefined);
        };

        var tryContainer = document.createElement('div');
        tryContainer.style.cssText = 'display:flex; flex-direction:column; gap:8px;';

        var renderTags = function() {
            tryContainer.innerHTML = '';
            var tagWrap = document.createElement('div');
            tagWrap.style.cssText = 'display:flex; flex-wrap:wrap; gap:8px; align-items:center; min-height:32px;';
            
            if (selectedTries.length === 0) {
                tagWrap.innerHTML = '<span style="color:var(--text-dim); font-size:12px;">（未设置，玩家可能无法进入）</span>';
            }
            
            selectedTries.forEach((name, idx) => {
                var tag = document.createElement('div');
                tag.style.cssText = 'display:inline-flex; align-items:center; background:var(--surface-3); border:1px solid var(--brand); border-radius:6px; padding:4px 8px; color:#fff; font-size:12.5px;';
                
                var nameSpan = document.createElement('span');
                nameSpan.textContent = (idx + 1) + '. ' + name;
                tag.appendChild(nameSpan);
                
                var btnUp = document.createElement('span');
                btnUp.innerHTML = '↑';
                btnUp.style.cssText = 'margin-left:10px; cursor:pointer; color:#7aa2f7; font-weight:bold; opacity:' + (idx===0?'0.2':'1');
                if (idx > 0) btnUp.onclick = () => { var tmp = selectedTries[idx]; selectedTries[idx] = selectedTries[idx-1]; selectedTries[idx-1] = tmp; renderTags(); updateDirty(); };
                tag.appendChild(btnUp);

                var btnDown = document.createElement('span');
                btnDown.innerHTML = '↓';
                btnDown.style.cssText = 'margin-left:6px; cursor:pointer; color:#7aa2f7; font-weight:bold; opacity:' + (idx===selectedTries.length-1?'0.2':'1');
                if (idx < selectedTries.length - 1) btnDown.onclick = () => { var tmp = selectedTries[idx]; selectedTries[idx] = selectedTries[idx+1]; selectedTries[idx+1] = tmp; renderTags(); updateDirty(); };
                tag.appendChild(btnDown);

                var btnDel = document.createElement('span');
                btnDel.innerHTML = '×';
                btnDel.style.cssText = 'margin-left:10px; cursor:pointer; color:#ff5c5c; font-weight:bold; font-size:15px;';
                btnDel.onclick = () => { selectedTries.splice(idx, 1); renderTags(); updateDirty(); };
                tag.appendChild(btnDel);

                tagWrap.appendChild(tag);
            });
            tryContainer.appendChild(tagWrap);

            var avail = serverNames.filter(n => !selectedTries.includes(n));
            if (avail.length > 0) {
                var addWrap = document.createElement('div');
                addWrap.style.cssText = 'margin-top:4px; border-top:1px dashed var(--border); padding-top:8px; display:flex; flex-wrap:wrap; gap:6px;';
                avail.forEach(n => {
                    var btnAdd = document.createElement('button');
                    btnAdd.type = 'button';
                    btnAdd.className = 'btn small';
                    btnAdd.textContent = '+ ' + n;
                    btnAdd.onclick = () => { selectedTries.push(n); renderTags(); updateDirty(); };
                    addWrap.appendChild(btnAdd);
                });
                tryContainer.appendChild(addWrap);
            }
        };
        
        renderTags();
        tdVTry.appendChild(tryContainer);
        
        trTry.appendChild(tdKTry);
        trTry.appendChild(tdVTry);
        basicTable.appendChild(trTry);
        
        container.appendChild(basicTable);

        // 2. 服务器列表
        var serversTitle = document.createElement('h3');
        serversTitle.style.marginTop = '28px';
        serversTitle.textContent = '后端服务器列表 (servers)';
        container.appendChild(serversTitle);

        var serversTable = document.createElement('table');
        serversTable.className = 'cfg-table sp-table';
        
        // 过滤出非 try 的条目
        var serverItems = serversSec ? serversSec.items.filter(i => i.key !== 'try') : [];
        
        if (serverItems.length === 0) {
            serversTable.innerHTML = '<tr><td colspan="3" class="empty" style="text-align:center;">暂无后端服务器</td></tr>';
        } else {
            serverItems.forEach(it => {
                var tr = renderVelRow(it, true);
                serversTable.appendChild(tr);
            });
        }
        container.appendChild(serversTable);

        // 添加服务器按钮
        var addBar = document.createElement('div');
        addBar.style.marginTop = '10px';
        addBar.style.marginBottom = '20px';
        var btnAdd = document.createElement('button');
        btnAdd.className = 'btn primary';
        btnAdd.textContent = '+ 添加后端服务器';
        btnAdd.onclick = velAddServer;
        addBar.appendChild(btnAdd);
        container.appendChild(addBar);

        // 3. 保存按钮
        var toolbar = document.createElement('div');
        toolbar.className = 'toolbar';
        toolbar.style.marginTop = '20px';
        var saveBtn = document.createElement('button');
        saveBtn.className = 'btn primary';
        saveBtn.textContent = '保存更改';
        saveBtn.onclick = velSave;
        toolbar.appendChild(saveBtn);
        container.appendChild(toolbar);

    }).catch(e => {
        container.innerHTML = '<div class="placeholder">读取失败: ' + e.message + '</div>';
    });
}

function renderVelRow(it, showDeleteBtn) {
    var tr = document.createElement('tr');
    tr.dataset.line = String(it.line);
    
    var tdK = document.createElement('td');
    tdK.className = 'cfg-key';
    var labelDiv = document.createElement('div');
    labelDiv.textContent = it.label || it.key;
    labelDiv.style.color = '#e8ebf5';
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
    var inp = document.createElement('input');
    inp.type = 'text';
    inp.className = 'sp-input';
    inp.value = it.value;
    
    // 如果是 try 字段或特定字段，给出更直观的提示
    if (it.key === 'try') {
        inp.placeholder = '例如: lobby, mini-game';
    } else if (it.key === 'bind') {
        inp.placeholder = '例如: 0.0.0.0:25577';
    }
    
    inp.oninput = function() {
        if (inp.value === it.value) delete velDirty[it.line];
        else velDirty[it.line] = inp.value;
        tr.classList.toggle('sp-row-dirty', velDirty[it.line] !== undefined);
    };
    tdV.appendChild(inp);
    
    tr.appendChild(tdK);
    tr.appendChild(tdV);

    if (showDeleteBtn) {
        var tdO = document.createElement('td');
        tdO.style.width = '80px';
        tdO.style.textAlign = 'right';
        var btnDel = document.createElement('button');
        btnDel.className = 'btn small danger';
        btnDel.textContent = '删除';
        btnDel.onclick = function() {
            uiConfirm({
                title: '删除服务器',
                message: '确定要删除后端服务器「' + it.key + '」吗？\n保存后生效。',
                danger: true,
                okText: '删除'
            }).then(ok => {
                if (!ok) return;
                velDeleteServer(it.line, it.key);
            });
        };
        tdO.appendChild(btnDel);
        tr.appendChild(tdO);
    }
    
    return tr;
}

function velAddServer() {
    var html =
        '<label>服务器名称（英文代号，如 lobby）</label>' +
        '<input id="vel-new-name" type="text" placeholder="例如: lobby" autocomplete="off">' +
        '<label>服务器地址（IP:端口）</label>' +
        '<input id="vel-new-addr" type="text" placeholder="例如: 127.0.0.1:25565" autocomplete="off">' +
        '<div class="hint" style="margin-top:14px;">添加后，请在[基础设置]的 try 字段中添加该名称，以便玩家登录时分配。</div>';

    openModal('添加后端服务器', html, function () {
        var name = document.getElementById('vel-new-name').value.trim();
        var addr = document.getElementById('vel-new-addr').value.trim();
        if (!name || !addr) { toast('名称和地址不能为空', 'err'); return; }
        if (!/^[A-Za-z0-9_-]+$/.test(name)) { toast('名称只能包含字母、数字、-、_', 'err'); return; }
        
        closeModal();
        
        postApi('/api/serverprops/set', {
            adds: [ { section: 'servers', key: name, value: addr } ]
        }).then(r => {
            if (r && r.ok) {
                toast('服务器已添加，请刷新页面确认', 'ok');
                velRefresh();
            } else {
                toast((r && r.error) || '添加失败', 'err');
            }
        });
    }, '添加');
}

function velDeleteServer(line, key) {
    // 如果这个 key 已经在前端被修改，先撤销修改
    delete velDirty[line];

    postApi('/api/serverprops/set', {
        deletes: [ { line: line } ]
    }).then(r => {
        if (r && r.ok) {
            toast('服务器已删除', 'ok');
            velRefresh();
        } else {
            toast((r && r.error) || '删除失败', 'err');
        }
    });
}

function velSave() {
    var items = [];
    var adds = [];
    Object.keys(velDirty).forEach(k => {
        if (k === 'try-new') {
            adds.push({ section: 'servers', key: 'try', value: velDirty[k] });
        } else {
            items.push({ line: parseInt(k, 10), value: velDirty[k] });
        }
    });
    if (items.length === 0 && adds.length === 0) { toast('没有更改', 'err'); return; }
    
    postApi('/api/serverprops/set', { items: items, adds: adds }).then(r => {
        if (r && r.ok) {
            toast('已保存 ' + r.changed + ' 项', 'ok');
            velRefresh();
        } else {
            toast((r && r.error) || '保存失败', 'err');
        }
    });
}