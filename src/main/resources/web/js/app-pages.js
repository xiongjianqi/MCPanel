// ===================== 备份管理 =====================

function backupRefresh() {
  if (panelExited) return;
  postApi('/api/backup/list', {}).then(function (list) {
    if (panelExited) return;
    var tbody = document.getElementById('backup-list');
    tbody.innerHTML = '';
    if (!Array.isArray(list) || list.length === 0) {
      tbody.innerHTML = '<tr><td colspan="4" class="empty">' + ((list && list.error) ? escapeHtml(list.error) : '暂无备份') + '</td></tr>';
      return;
    }
    list.sort(function (a, b) { return (b.name || '').localeCompare(a.name || ''); });
    list.forEach(function (b) {
      var tr = document.createElement('tr');
      var td1 = document.createElement('td'); td1.textContent = b.name;
      var td2 = document.createElement('td');
      td2.className = 'dim'; td2.textContent = b.desc || '（无描述）';
      var td3 = document.createElement('td');
      td3.className = 'dim'; td3.textContent = fmtSize(b.size);
      var td4 = document.createElement('td');

      var b0 = document.createElement('button');
      b0.className = 'btn small primary'; b0.textContent = '回档';
      b0.onclick = (function (n) { return function () { backupRollback(n); }; })(b.name);
      td4.appendChild(b0);

      var b1 = document.createElement('button');
      b1.className = 'btn small'; b1.textContent = '重命名'; b1.style.marginLeft = '6px';
      b1.onclick = (function (n, d) { return function () { backupRename(n, d); }; })(b.name, b.desc);
      td4.appendChild(b1);

      var b2 = document.createElement('button');
      b2.className = 'btn small danger'; b2.textContent = '删除'; b2.style.marginLeft = '6px';
      b2.onclick = (function (n) { return function () { backupDelete(n); }; })(b.name);
      td4.appendChild(b2);

      tr.appendChild(td1); tr.appendChild(td2); tr.appendChild(td3); tr.appendChild(td4);
      tbody.appendChild(tr);
    });
  }).catch(function (e) { console.error(e); });
}

function backupAdd() {
  var now = new Date();
  var pad = function (n) { return (n < 10 ? '0' : '') + n; };
  var def = 'backup-'
    + now.getFullYear() + pad(now.getMonth() + 1) + pad(now.getDate())
    + '-' + pad(now.getHours()) + pad(now.getMinutes()) + pad(now.getSeconds());
  var html =
    '<label>备份名（仅限字母数字-_）</label>' +
    '<input id="bk-name" type="text" value="' + def + '">' +
    '<label>描述</label>' +
    '<input id="bk-desc" type="text" placeholder="可选">';
  openModal('新建备份', html, function () {
    var name = document.getElementById('bk-name').value.trim();
    var desc = document.getElementById('bk-desc').value;
    if (!name) { toast('请输入备份名', 'err'); return; }
    if (!/^[A-Za-z0-9_\-]+$/.test(name)) { toast('备份名只能包含字母、数字、-、_', 'err'); return; }
    closeModal();
    toast('正在备份，请稍候...');
    postApi('/api/backup/create', { name: name, desc: desc }).then(function (r) {
      if (r && r.ok) { toast('备份完成', 'ok'); backupRefresh(); }
      else toast((r && r.error) || '备份失败', 'err');
    });
  }, '开始备份');
}

function backupRename(oldName, oldDesc) {
  var html =
    '<label>新名称</label>' +
    '<input id="bk-newname" type="text" value="' + escapeHtml(oldName || '') + '">' +
    '<label>描述</label>' +
    '<input id="bk-newdesc" type="text" value="' + escapeHtml(oldDesc || '') + '">';
  openModal('重命名备份', html, function () {
    var nn = document.getElementById('bk-newname').value.trim();
    var nd = document.getElementById('bk-newdesc').value;
    if (!nn) { toast('请输入名称', 'err'); return; }
    if (!/^[A-Za-z0-9_\-]+$/.test(nn)) { toast('名称只能包含字母、数字、-、_', 'err'); return; }
    closeModal();
    postApi('/api/backup/rename', { old: oldName, name: nn, desc: nd }).then(function (r) {
      if (r && r.ok) { toast('已更新', 'ok'); backupRefresh(); }
      else toast((r && r.error) || '更新失败', 'err');
    });
  });
}

function backupDelete(name) {
  uiConfirm({
    title: '删除备份',
    message: '确定删除备份 ' + name + '？\n此操作不可恢复！',
    danger: true,
    okText: '删除'
  }).then(function (ok) {
    if (!ok) return;
    postApi('/api/backup/delete', { name: name }).then(function () {
      toast('已删除', 'ok'); backupRefresh();
    });
  });
}

function backupRollback(name) {
  uiConfirm({
    title: '回档确认',
    message: '确定用备份 [' + name + '] 回档？\n\n'
      + '流程：\n'
      + '1. 游戏内 tellraw 倒计时 10 秒\n'
      + '2. 关闭 MC 服务器\n'
      + '3. 用备份覆盖 server/world\n'
      + '4. 自动重新启动 MC\n\n'
      + '此操作会丢失当前 world 未备份的进度！',
    danger: true,
    okText: '继续'
  }).then(function (ok) {
    if (!ok) return;
    uiConfirm({
      title: '最终确认',
      message: '真的要回档吗？',
      danger: true,
      okText: '回档'
    }).then(function (ok2) {
      if (!ok2) return;
      postApi('/api/backup/rollback', { name: name }).then(function (r) {
        if (r && r.ok) toast('回档已启动，请查看终端日志', 'ok');
        else toast((r && r.error) || '回档失败', 'err');
      });
    });
  });
}

// ===================== 配置管理 =====================
var cfgData = {};

var CFG_LABEL = {
  'mc.start.command':           'MC 启动命令（无沙箱时自动去除非 JVM 参数）',
  'mc.server.dir':              'MC 服务端目录（相对路径）',
  'mc.auto.start':              '面板启动时自动启动 MC (true/false)',
  'mc.port':                    'MC 监听端口',

  'velocity.start.command':     'Velocity 启动命令',
  'velocity.server.dir':        'Velocity 服务端目录（相对路径）',
  'velocity.auto.start':        '面板启动时自动启动 Velocity (true/false)',
  'velocity.port':              'Velocity 监听端口',

  'terminal.shutdown.command':  '关闭命令（MC: stop / Velocity: shutdown）',
  'terminal.auto.restart':      '异常退出时自动重启 (true/false)',

  'web.port':                   '面板 Web 端口',
  'share.port':                 '分享服务端口',
  'panel.host':                 '服务器对外地址（玩家客户端里填的域名 / IP）',
  'web.ssl.enabled':            '是否启用 HTTPS (true/false)',
  'web.ssl.cert':               'HTTPS 证书路径（相对路径）',
  'web.ssl.key':                'HTTPS 私钥路径（相对路径）',
  'web.session.timeout.hours':  'Web 登录有效期（小时，默认 360 = 15 天，活跃自动续期）',
  
  'security.no_sandbox.lock':   '无沙箱时锁定核心文件 (true/false)',
  
  'container.enabled':          '启用 Docker 容器模式 (true/false)',
  'container.image':            'Docker 容器镜像（如 itzg/minecraft-server:latest）',
  'container.memory':           'Docker 容器内存限制（如 2G）',

  'remote.ssh.port':            'SSH 远程服务端口',
  'remote.sftp.port':           'SFTP 远程文件传输端口'
};

function cfgLabel(key) {
  return CFG_LABEL[key] || key;
}

function cfgGroupOf(key) {
  if (key.indexOf('proxy.') === 0) return null;
  if (key.indexOf('panel.') === 0) return null;

  var isGlobal = !currentInstanceId;

  if (key.indexOf('mc.') === 0)       return isGlobal ? null : 'server';
  if (key.indexOf('velocity.') === 0) return isGlobal ? null : 'server';
  if (key.indexOf('terminal.') === 0) return isGlobal ? null : 'terminal';

  if (key.indexOf('web.') === 0)      return isGlobal ? 'web'   : null;
  if (key.indexOf('share.') === 0)    return isGlobal ? 'share' : null;
  if (key.indexOf('security.') === 0) return isGlobal ? 'web'   : null;
  if (key.indexOf('container.') === 0) return isGlobal ? 'web'  : null;
  if (key.indexOf('remote.') === 0)   return isGlobal ? 'remote': null;

  return 'other';
}

var CFG_GROUP_NAME = {
  server:   '服务器',
  terminal: '终端',
  web:      'Web 面板',
  share:    '分享',
  remote:   '远程管理',
  panel:    '对外连接',
  other:    '其他'
};

var CFG_GROUP_ORDER = ['server', 'terminal', 'web', 'share', 'remote', 'panel', 'other'];

function cfgRefresh() {
  if (panelExited) return;
  fetch('/api/config/get').then(function (r) { return r.json(); }).then(function (d) {
    if (panelExited) return;
    cfgData = d || {}; cfgRender();
  }).catch(function (e) { console.error(e); toast('读取配置失败', 'err'); });
}

function cfgRender() {
  var searchEl = document.getElementById('cfg-search');
  var q = searchEl ? searchEl.value.trim().toLowerCase() : '';
  var container = document.getElementById('cfg-container');
  if (!container) return;
  container.innerHTML = '';

  var keys = Object.keys(cfgData).filter(function (k) {
    if (!cfgGroupOf(k)) return false;
    if (!q) return true;
    var label = cfgLabel(k).toLowerCase();
    return k.toLowerCase().indexOf(q) >= 0
        || label.indexOf(q) >= 0
        || String(cfgData[k]).toLowerCase().indexOf(q) >= 0;
  });

  if (keys.length === 0) {
    container.innerHTML = '<div class="placeholder">没有匹配的配置项</div>'; return;
  }

  var groups = {};
  keys.forEach(function (k) {
    var g = cfgGroupOf(k);
    if (!g) return;
    if (!groups[g]) groups[g] = [];
    groups[g].push(k);
  });

  CFG_GROUP_ORDER.forEach(function (g) {
    if (!groups[g] || groups[g].length === 0) return;
    var title = document.createElement('div');
    title.className = 'cfg-group-title';
    title.textContent = CFG_GROUP_NAME[g] || g;
    container.appendChild(title);

    var table = document.createElement('table');
    table.className = 'cfg-table';
    groups[g].sort().forEach(function (k) {
      var tr = document.createElement('tr');

      var tdK = document.createElement('td');
      tdK.className = 'cfg-key';
      var labelDiv = document.createElement('div');
      labelDiv.textContent = cfgLabel(k);
      labelDiv.style.color = '#e8ebf5';
      labelDiv.style.fontFamily = 'inherit';
      labelDiv.style.fontSize = '13px';
      labelDiv.style.marginBottom = '2px';
      var keyDiv = document.createElement('div');
      keyDiv.textContent = k;
      keyDiv.style.color = 'var(--text-dim)';
      keyDiv.style.fontSize = '11px';
      keyDiv.style.fontFamily = 'var(--mono)';
      keyDiv.style.opacity = '0.7';
      tdK.appendChild(labelDiv);
      tdK.appendChild(keyDiv);

      var tdV = document.createElement('td');
      tdV.className = 'cfg-val';
      var v = String(cfgData[k]);
      if (v === '') { tdV.classList.add('empty'); tdV.textContent = '(空)'; }
      else tdV.textContent = v;

      var tdO = document.createElement('td');
      tdO.className = 'cfg-ops';
      var bE = document.createElement('button');
      bE.className = 'btn small'; bE.textContent = '编辑';
      bE.onclick = (function (key, val) { return function () { cfgEdit(key, val); }; })(k, v);
      tdO.appendChild(bE);
      var bD = document.createElement('button');
      bD.className = 'btn small danger'; bD.textContent = '删除';
      bD.style.marginLeft = '6px';
      bD.onclick = (function (key) { return function () { cfgDelete(key); }; })(k);
      tdO.appendChild(bD);

      tr.appendChild(tdK); tr.appendChild(tdV); tr.appendChild(tdO);
      table.appendChild(tr);
    });
    container.appendChild(table);
  });
}

function cfgEdit(key, currentVal) {
  uiPrompt({
    title: '修改配置',
    label: cfgLabel(key) + '（' + key + '）',
    value: currentVal
  }).then(function (nv) {
    if (nv === null) return;
    var o = {}; o[key] = nv;
    postApi('/api/config/set', o).then(function () {
      cfgData[key] = nv; cfgRender(); toast('已更新', 'ok');
      if (key === 'web.port' || key === 'share.port') {
        toast(key + ' 已修改，需重启面板才生效', 'err');
      }
    });
  });
}

function cfgDelete(key) {
  uiConfirm({
    title: '清空配置',
    message: '确定清空配置项 ' + cfgLabel(key) + '？',
    danger: true,
    okText: '清空'
  }).then(function (ok) {
    if (!ok) return;
    var o = {}; o[key] = '';
    postApi('/api/config/set', o).then(function () {
      cfgData[key] = ''; cfgRender(); toast('已清空', 'ok');
    });
  });
}

function cfgAddNew() {
  uiPrompt({ title: '添加配置项', label: '键名（如 my.custom.key）' }).then(function (key) {
    if (!key) return;
    uiPrompt({ title: '添加配置项', label: '值（可留空）', value: '' }).then(function (val) {
      if (val === null) return;
      var o = {}; o[key] = val;
      postApi('/api/config/set', o).then(function () {
        cfgData[key] = val; cfgRender(); toast('已添加', 'ok');
      });
    });
  });
}

function genSelfSsl() {
  uiConfirm({ title: '生成自签证书', message: '将覆盖当前证书文件，并自动开启 HTTPS。继续吗？', okText: '生成' }).then(ok => {
    if(!ok) return;
    toast('正在生成...');
    postApi('/api/config/generate-ssl', {}).then(r => {
      if(r.ok) { toast('生成成功，请重启面板', 'ok'); closeModal(); cfgRefresh(); }
      else toast(r.error || '生成失败', 'err');
    });
  });
}

function cfgSslUpload() {
  var html =
    '<label>证书内容 (-----BEGIN CERTIFICATE----- 开头)</label>' +
    '<textarea id="ssl-cert-content" class="edit-textarea" style="min-height:120px; font-family:monospace;" placeholder="粘贴证书内容..."></textarea>' +
    '<label style="margin-top:12px;">私钥内容 (-----BEGIN PRIVATE KEY----- 开头)</label>' +
    '<textarea id="ssl-key-content" class="edit-textarea" style="min-height:120px; font-family:monospace;" placeholder="粘贴私钥内容..."></textarea>' +
    '<div class="hint" style="margin-top:14px;">' +
    '保存后需 <b>重启面板</b> 才能生效。<br>' +
    '请确保私钥是 PKCS#8 格式（如果是 -----BEGIN RSA PRIVATE KEY----- 开头，请先用 openssl 转换）。' +
    '</div>' +
    '<div style="margin-top:12px;"><button class="btn" onclick="genSelfSsl()">一键生成自签证书</button></div>';
  openModal('设置 SSL 证书', html, function() {
    var cert = document.getElementById('ssl-cert-content').value.trim();
    var key = document.getElementById('ssl-key-content').value.trim();
    if (!cert || !key) {
      toast('请填写证书和私钥内容', 'err'); return;
    }
    postApi('/api/config/upload-ssl', { cert: cert, key: key }).then(res => {
      if (res && res.ok) {
        toast('SSL 配置已保存，请重启面板生效', 'ok');
        closeModal();
        cfgRefresh();
      } else {
        toast((res && res.error) || '保存失败', 'err');
      }
    });
  }, '保存配置');
}

// ===================== 子用户 =====================
var currentInstanceList = [];

var PERM_GROUPS = [
  {
    name: '实例管理',
    perms: [
      ['instance_create', '新建实例'],
      ['instance_edit',   '编辑实例信息'],
      ['instance_delete', '删除实例']
    ]
  },
  {
    name: '运行控制',
    perms: [
      ['terminal', '终端控制 (启停/发指令)'],
      ['config',   '配置修改 (全局/服务器配置)'],
      ['java',     'Java 运行时管理']
    ]
  },
  {
    name: '文件与数据',
    perms: [
      ['files',     '实例文件管理'],
      ['files_all', '全盘文件管理'],
      ['backup',    '备份创建与回档'],
      ['share',     '文件分享管理']
    ]
  },
  {
    name: '面板管理',
    perms: [
      ['panel_log',   '查看面板日志'],
      ['users',       '子用户管理'],
      ['plugins',     '面板插件管理']
    ]
  }
];

var ALL_PERMS = (function () {
  var out = [];
  PERM_GROUPS.forEach(function (g) {
    g.perms.forEach(function (p) { out.push(p); });
  });
  return out;
})();

function permsHtmlGrouped(currentInstances, currentInstancePerms, grantable) {
  var html = '';
  
  html += '<details class="perm-box" open>';
  html += '<summary><div style="flex:1;">面板全局权限</div><label class="select-all-label"><input type="checkbox" class="row-check" onchange="toggleGroup(this, \'panel-perm\')"> 全选</label></summary>';
  html += '<div class="perm-content">';
  var panelPerms = [['users','子用户'],['plugins','插件管理'],['smtp','SMTP管理'],['share','分享管理'],['panel_log','面板日志'],['instance_create','新建实例'],['instance_delete','删除实例'],['config','全局配置管理']];
  panelPerms.forEach(function(p) {
    if (!currentIsMain && grantable.indexOf(p[0]) < 0) return;
    var checked = (currentInstances || []).indexOf(p[0]) >= 0 ? ' checked' : '';
    html += '<label class="perm-item"><span>' + p[1] + '</span><input type="checkbox" class="row-check panel-perm" value="' + p[0] + '"' + checked + '></label>';
  });
  html += '</div></details>';

  if (currentInstanceList && currentInstanceList.length > 0) {
    currentInstanceList.forEach(function(inst) {
      var ip = (currentInstancePerms && currentInstancePerms[inst.id]) || [];
      html += '<details class="perm-box">';
      html += '<summary><div style="flex:1;">实例：' + escapeHtml(inst.name || inst.id) + '</div>';
      html += '<label class="select-all-label"><input type="checkbox" class="row-check" onchange="toggleInstGroup(this, \'' + inst.id + '\')"> 全选</label></summary>';
      html += '<div class="perm-content">';
      var instPerms = [['terminal','终端'],['files','文件管理'],['backup','备份管理'],['serverprops','服务器配置'],['velocitycfg','Velocity配置'],['java','Java环境'],['installer','安装服务端'],['remote','远程控制 (SSH/SFTP)']];
      
      instPerms.forEach(function(p) {
        if (p[0] === 'remote' && !currentIsMain) return;
        var checked = ip.indexOf(p[0]) >= 0 ? ' checked' : '';
        html += '<label class="perm-item"><span>' + p[1] + '</span><input type="checkbox" class="row-check inst-perm" data-inst="' + inst.id + '" value="' + p[0] + '"' + checked + '></label>';
      });
      html += '</div></details>';
    });
  }
  return html;
}

window.toggleGroup = function(el, cls) {
  document.querySelectorAll('.' + cls).forEach(function(c) { c.checked = el.checked; });
};
window.toggleInstGroup = function(el, instId) {
  document.querySelectorAll('.inst-perm[data-inst="' + instId + '"]').forEach(function(c) { c.checked = el.checked; });
};

function collectGroupedPerms() {
  var panelPerms = [];
  document.querySelectorAll('.panel-perm:checked').forEach(function(c) { panelPerms.push(c.value); });
  
  var instPerms = {};
  document.querySelectorAll('.inst-perm:checked').forEach(function(c) {
    var instId = c.dataset.inst;
    if (!instPerms[instId]) instPerms[instId] = [];
    instPerms[instId].push(c.value);
  });
  
  return { perms: panelPerms, instPerms: instPerms };
}

function usersRefresh() {
  if (panelExited) return;
  fetch('/api/users/list').then(function (r) { return r.json(); }).then(function (list) {
    if (panelExited) return;
    var tbody = document.getElementById('users-list');
    tbody.innerHTML = '';
    if (!Array.isArray(list) || list.length === 0) {
      tbody.innerHTML = '<tr><td colspan="4" class="empty">' + ((list && list.error) ? escapeHtml(list.error) : '暂无子用户') + '</td></tr>';
      return;
    }
    list.forEach(function (u) {
      var tr = document.createElement('tr');
      var td1 = document.createElement('td'); td1.textContent = u.name;

      var td2 = document.createElement('td');
      td2.className = 'dim';
      var perms = u.perms || [];
      if (perms.length === 0) {
        td2.textContent = '（无权限）';
      } else {
        var names = perms.map(function (p) {
          for (var i = 0; i < ALL_PERMS.length; i++) {
            if (ALL_PERMS[i][0] === p) return ALL_PERMS[i][1];
          }
          return p;
        });
        td2.textContent = names.join('、');
      }

      var insts = u.instances || [];
      var tdInst = document.createElement('td');
      tdInst.className = 'dim';
      tdInst.textContent = insts.length === 0 ? '无权限' : insts.join('、');

      var td3 = document.createElement('td');
      var b1 = document.createElement('button');
      b1.className = 'btn small'; b1.textContent = '修改';
      b1.onclick = (function (n, ps, is, ip) { return function () { usersEdit(n, ps, is, ip); }; })(u.name, perms, insts, u.instancePerms);
      td3.appendChild(b1);
      var b2 = document.createElement('button');
      b2.className = 'btn small danger'; b2.textContent = '删除'; b2.style.marginLeft = '6px';
      b2.onclick = (function (n) { return function () { usersDelete(n); }; })(u.name);
      td3.appendChild(b2);

      tr.appendChild(td1); tr.appendChild(td2); tr.appendChild(tdInst); tr.appendChild(td3);
      tbody.appendChild(tr);
    });
  }).catch(function (e) { console.error(e); });
}

function usersAdd() {
  Promise.all([
    fetch('/api/instances/list').then(r => r.json()),
    fetch('/api/smtp/get').then(r => r.json()).catch(() => ({}))
  ]).then(function (results) {
    currentInstanceList = results[0] || [];
    var smtpConf = results[1] || {};
    var hasSmtp = smtpConf && smtpConf.host;

    var emailHtml = '';
    if (hasSmtp) {
      emailHtml = '<label>受邀者邮箱（可选）</label>' +
                  '<input id="u-email" type="email" class="sp-input" style="margin-bottom:16px;" placeholder="填写后自动发邮件，留空生成链接" autocomplete="off">';
    } else {
      emailHtml = '<div class="hint" style="color:#f0c040; margin-bottom:16px;">当前未配置 SMTP 服务，将直接生成邀请链接。</div>';
    }

    var html = emailHtml + permsHtmlGrouped([], {}, currentPerms);
    
    openModal('邀请子用户', html, function () {
      var emailEl = document.getElementById('u-email');
      var email = emailEl ? emailEl.value.trim() : '';
      if (email && !email.includes('@')) { toast('请输入有效邮箱', 'err'); return; }
      var grouped = collectGroupedPerms();
      var instances = [];
      for (var iid in grouped.instPerms) { instances.push(iid); }
      closeModal();
      toast('正在发送邀请...', 'ok');
      postApi('/api/users/add', {
        email: email, perms: grouped.perms.join(','), instances: instances.join(','), instancePerms: JSON.stringify(grouped.instPerms)
      }).then(function (r) {
        if (r && r.ok) { 
          if (r.emailSent) toast('邀请邮件已发送', 'ok');
          else showInviteLink(location.origin + '/register?token=' + r.token);
          usersRefresh(); 
        }
        else toast((r && r.error) || '生成失败', 'err');
      });
    });
  }).catch(function (e) { console.error(e); toast('无法加载列表', 'err'); });
}

function usersEdit(name, userPerms, currentInstances, instancePerms) {
  fetch('/api/instances/list').then(r => r.json()).then(function (instList) {
    currentInstanceList = instList || [];
    var html =
      '<label>用户名</label><input type="text" value="' + escapeHtml(name) + '" disabled>' +
      '<label>新密码（留空则不修改）</label><input id="u-newpass" type="text" class="pwd-mask" autocomplete="off">' +
      permsHtmlGrouped(currentInstances || [], instancePerms || {}, currentPerms);
    openModal('修改子用户 ' + name, html, function () {
      var np = document.getElementById('u-newpass').value;
      if (np && np.length < 8) { toast('密码至少 8 位', 'err'); return; }
      var grouped = collectGroupedPerms();
      var instances = [];
      for (var iid in grouped.instPerms) { instances.push(iid); }
      closeModal();
      postApi('/api/users/update', {
        name: name, pass: np, perms: grouped.perms.join(','), instances: instances.join(','), instancePerms: JSON.stringify(grouped.instPerms)
      }).then(function (r) {
        if (r && r.ok) { toast('已更新', 'ok'); usersRefresh(); }
        else toast((r && r.error) || '更新失败', 'err');
      });
    });
  }).catch(function (e) { console.error(e); toast('无法加载实例列表', 'err'); });
}

function usersDelete(name) {
  uiConfirm({
    title: '删除子用户',
    message: '确定删除子用户 ' + name + ' ？',
    danger: true,
    okText: '删除'
  }).then(function (ok) {
    if (!ok) return;
    postApi('/api/users/delete', { name: name }).then(function () {
      toast('已删除', 'ok'); usersRefresh();
    });
  });
}

function showInviteLink(link) {
  var m = document.getElementById('ui-modal');
  if (!m) return;
  _uiModalKind = 'confirm';
  _uiModalResolve = null;
  document.getElementById('ui-modal-title').textContent = '邀请链接已生成';
  var body = document.getElementById('ui-modal-body');
  body.innerHTML = '<div class="hint">请复制下方链接发送给受邀者：</div>' +
    '<div style="display:flex; gap:8px; margin-top:10px;">' +
    '<input id="invite-link-input" class="ui-input" value="' + escapeHtml(link) + '" readonly style="flex:1; font-family:monospace;">' +
    '<button class="btn primary" onclick="copyInviteLink()">复制</button>' +
    '</div>';
  
  var okBtn = document.getElementById('ui-modal-ok');
  var cancelBtn = document.getElementById('ui-modal-cancel');
  okBtn.style.display = 'none';
  cancelBtn.textContent = '关闭';
  cancelBtn.onclick = function() { m.classList.remove('show'); };
  m.classList.add('show');
}

function copyInviteLink() {
  var inp = document.getElementById('invite-link-input');
  if (!inp) return;
  copyToClipboard(inp.value).then(function() {
    toast('链接已复制到剪贴板', 'ok');
  }).catch(function() {
    inp.select();
    document.execCommand('copy');
    toast('链接已复制', 'ok');
  });
}

// ===================== 账户管理 =====================

function accLoad() {
  if (panelExited) return;
  fetch('/api/account/info').then(r => r.json()).then(d => {
    if (!d) return;
    
    var unEl = document.getElementById('acc-username');
    if (unEl) unEl.textContent = d.username;
    
    var roleEl = document.getElementById('acc-role');
    if (roleEl) roleEl.textContent = d.isMain ? '所有者 (Master)' : '子用户';
    
    currentIsMain = !!d.isMain;
    if (d.needsPasswordChange) {
      toast('⚠ 为了安全，请立即修改初始密码！', 'err');
    }
    
    var emailCur = document.getElementById('acc-email-current');
    var emailBox = document.getElementById('acc-email-box');
    var emailNoSmtp = document.getElementById('acc-email-no-smtp');
    if (d.hasSmtp) {
      if (d.email) {
        if (emailCur) emailCur.style.display = 'block';
        if (document.getElementById('acc-email-text')) document.getElementById('acc-email-text').textContent = d.email;
        if (emailBox) emailBox.style.display = 'none';
      } else {
        if (emailCur) emailCur.style.display = 'none';
        if (emailBox) emailBox.style.display = 'block';
      }
      if (emailNoSmtp) emailNoSmtp.style.display = 'none';
    } else {
      if (emailCur) emailCur.style.display = 'none';
      if (emailBox) emailBox.style.display = 'none';
      if (emailNoSmtp) emailNoSmtp.style.display = 'block';
    }

    var keyBox = document.getElementById('acc-apikey-box');
    if (keyBox) {
      if (d.apiKey) {
        keyBox.textContent = d.apiKey;
        keyBox.classList.remove('empty');
      } else {
        keyBox.textContent = '（尚未生成 API Key）';
        keyBox.classList.add('empty');
      }
    }

    var totpBtnDis = document.getElementById('btn-totp-disable');
    var totpMethodSel = document.getElementById('acc-2fa-method');
    
    while (totpMethodSel.options.length > 1) totpMethodSel.remove(1);

    if (d.hasTotp) {
      if (totpBtnDis) totpBtnDis.style.display = 'inline-flex';
      var optT = document.createElement('option');
      optT.value = 'totp'; optT.textContent = '动态令牌 (TOTP)';
      totpMethodSel.appendChild(optT);
    } else {
      if (totpBtnDis) totpBtnDis.style.display = 'none';
    }

    if (d.email) {
      var optE = document.createElement('option');
      optE.value = 'email'; optE.textContent = '邮箱验证码';
      totpMethodSel.appendChild(optE);
    }

    totpMethodSel.value = d.twoFactorMethod || 'none';
    if (d.twoFactorMethod === 'none' || !d.hasTotp) {
       var warn = document.getElementById('acc-2fa-warning');
       if (warn) warn.style.display = (d.twoFactorMethod !== 'none' ? 'block' : 'none');
    }

  }).catch(e => console.error(e));
}

function accChangeUsername() {
  var name = document.getElementById('acc-new-username').value.trim();
  if (name.length < 2) { toast('用户名至少 2 位', 'err'); return; }
  postApi('/api/account/rename', { newUsername: name }).then(r => {
    if (r && r.ok) {
      toast('用户名已修改，请重新登录', 'ok');
      setTimeout(() => { location.href = '/login'; }, 1500);
    } else {
      toast((r && r.error) || '修改失败', 'err');
    }
  });
}

function accChangePassword() {
  var oldPass = document.getElementById('acc-old-pass').value;
  var newPass = document.getElementById('acc-new-pass').value;
  var newPass2 = document.getElementById('acc-new-pass2').value;
  
  if (newPass.length < 8) { toast('新密码至少 8 位', 'err'); return; }
  if (newPass !== newPass2) { toast('两次输入的新密码不一致', 'err'); return; }
  
  postApi('/api/account/password', { oldPassword: oldPass, newPassword: newPass }).then(r => {
    if (r && r.ok) {
      toast('密码修改成功', 'ok');
      document.getElementById('acc-old-pass').value = '';
      document.getElementById('acc-new-pass').value = '';
      document.getElementById('acc-new-pass2').value = '';
    } else {
      toast((r && r.error) || '修改失败', 'err');
    }
  });
}

function totpSetup() {
  postApi('/api/account/totp/setup', {}).then(r => {
    if (!r || !r.secret) { toast('获取失败', 'err'); return; }
    
    var html = 
      '<div class="hint">请使用 Google Authenticator 等应用扫描二维码：</div>' +
      '<div style="text-align:center; margin: 16px 0;">' +
      '  <img src="' + r.qrcode + '" style="width:200px; height:200px; border-radius:8px; background:#fff; padding:8px;">' +
      '</div>' +
      '<div class="hint">若无法扫描，手动输入密钥：<code>' + r.secret + '</code></div>' +
      '<label>当前密码（用于验证身份）</label>' +
      '<input id="totp-pass" type="text" class="pwd-mask" placeholder="输入登录密码">' +
      '<label>6 位动态验证码</label>' +
      '<input id="totp-code" type="text" placeholder="输入 App 显示的 6 位数字" maxlength="6">';
      
    openModal('绑定动态令牌', html, function() {
      var pass = document.getElementById('totp-pass').value;
      var code = document.getElementById('totp-code').value;
      if (!pass || !code) { toast('请填写完整', 'err'); return; }
      postApi('/api/account/totp/enable', { secret: r.secret, code: code, password: pass }).then(res => {
        if (res && res.ok) {
          toast('绑定成功', 'ok'); closeModal(); accLoad();
        } else {
          toast((res && res.error) || '验证码错误', 'err');
        }
      });
    }, '绑定');
  });
}

function totpDisable() {
  uiPrompt({ title: '解绑动态令牌', label: '请输入当前登录密码' }).then(pass => {
    if (!pass) return;
    postApi('/api/account/totp/disable', { password: pass }).then(r => {
      if (r && r.ok) { toast('解绑成功', 'ok'); accLoad(); }
      else toast((r && r.error) || '解绑失败', 'err');
    });
  });
}

function update2faMethod(sel) {
  var m = sel.value;
  postApi('/api/account/setting/2fa_method', { method: m }).then(r => {
    if (r && r.ok) { toast('验证方式已更新', 'ok'); accLoad(); }
    else { toast((r && r.error) || '更新失败', 'err'); accLoad(); }
  });
}

function generateRecovery() {
  uiPrompt({ title: '生成恢复码', label: '请输入当前登录密码' }).then(pass => {
    if (!pass) return;
    postApi('/api/account/recovery/generate', { password: pass }).then(r => {
      if (r && r.code) {
        var el = document.getElementById('acc-recovery-display');
        el.textContent = r.code;
        el.style.display = 'block';
        toast('请立刻保存此恢复码，之后不会再显示', 'ok');
      } else {
        toast((r && r.error) || '生成失败', 'err');
      }
    });
  });
}

function accSendEmailCode() {
  var email = document.getElementById('acc-email-input').value.trim();
  if (!email || !email.includes('@')) { toast('请输入有效邮箱', 'err'); return; }
  postApi('/api/account/bind-email/send-code', { email: email }).then(r => {
    if (r && r.ok) toast('验证码已发送', 'ok');
    else toast((r && r.error) || '发送失败', 'err');
  });
}

function accBindEmail() {
  var email = document.getElementById('acc-email-input').value.trim();
  var code = document.getElementById('acc-email-code').value.trim();
  if (!email || !code) { toast('请填写邮箱和验证码', 'err'); return; }
  postApi('/api/account/bind-email', { email: email, code: code }).then(r => {
    if (r && r.ok) { toast('绑定成功', 'ok'); accLoad(); }
    else toast((r && r.error) || '绑定失败', 'err');
  });
}

function accUnbindEmail() {
  uiPrompt({ title: '解绑邮箱', label: '请输入当前登录密码以验证身份' }).then(pass => {
    if (!pass) return;
    postApi('/api/account/unbind-email', { password: pass }).then(r => {
      if (r && r.ok) { toast('已解绑', 'ok'); accLoad(); }
      else toast((r && r.error) || '解绑失败', 'err');
    });
  });
}

function accGenerateKey() {
  postApi('/api/account/apikey/generate', {}).then(r => {
    if (r && r.apiKey) { toast('API Key 已生成', 'ok'); accLoad(); }
    else toast('生成失败', 'err');
  });
}

function accCopyKey() {
  var key = document.getElementById('acc-apikey-box').textContent;
  if (!key || key.indexOf('尚未') >= 0) return;
  copyToClipboard(key).then(() => toast('已复制到剪贴板', 'ok'));
}

function accRevokeKey() {
  uiConfirm({ title: '撤销 API Key', message: '撤销后原有脚本将无法调用接口，确定吗？', danger: true, okText: '撤销' }).then(ok => {
    if (!ok) return;
    postApi('/api/account/apikey/revoke', {}).then(r => {
      if (r && r.ok) { toast('已撤销', 'ok'); accLoad(); }
      else toast('撤销失败', 'err');
    });
  });
}

function accLogout() {
  postApi('/api/account/logout', {}).then(() => {
    location.href = '/login';
  });
}

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