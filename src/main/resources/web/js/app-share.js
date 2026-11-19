function shareRefresh() {
  if (panelExited) return;
  postApi('/api/share/list', {}).then(list => {
    var tbody = document.getElementById('share-list');
    if (!tbody) return;
    tbody.innerHTML = '';
    if (!Array.isArray(list) || list.length === 0) {
      tbody.innerHTML = '<tr><td colspan="6" class="empty">' + ((list && list.error) ? escapeHtml(list.error) : '暂无分享') + '</td></tr>';
      return;
    }
    list.forEach(s => {
      var tr = document.createElement('tr');
      var baseUrl = location.protocol + '//' + location.hostname + ':' + (s.sharePort || 33333);
      var url = baseUrl + '/share/' + s.token;
      
      var td1 = document.createElement('td'); td1.textContent = s.name;
      var td2 = document.createElement('td');
      td2.className = 'dim';
      var a = document.createElement('a');
      a.href = url;
      a.target = '_blank';
      a.className = 'file-link';
      a.textContent = '点击访问';
      td2.appendChild(a);
      var td3 = document.createElement('td'); 
      if (s.expireAt > 0 && Date.now() > s.expireAt) {
          td3.textContent = '已过期';
          td3.style.color = '#ff5c5c';
      } else {
          td3.textContent = s.hasPassword ? '🔒 有密码' : '公开'; 
      }
      var td5 = document.createElement('td'); td5.className = 'dim'; td5.textContent = new Date(s.createdAt).toLocaleString();
      var td6 = document.createElement('td'); td6.className = 'dim'; td6.textContent = s.createdBy || '未知';
      
      var td7 = document.createElement('td');
      var b1 = document.createElement('button'); b1.className = 'btn small danger'; b1.textContent = '删除';
      b1.onclick = () => {
        uiConfirm({ title: '删除分享', message: '确定删除此分享吗？', danger: true, okText: '删除' }).then(ok => {
          if (!ok) return;
          postApi('/api/share/delete', { token: s.token }).then(r => {
            if (r && r.ok) { toast('已删除', 'ok'); shareRefresh(); }
            else toast('删除失败', 'err');
          });
        });
      };
      td7.appendChild(b1);
      
      tr.appendChild(td1); tr.appendChild(td2); tr.appendChild(td3); tr.appendChild(td5); tr.appendChild(td6); tr.appendChild(td7);
      tbody.appendChild(tr);
    });
  }).catch(() => {});
}

function shareRefreshLogs() {
  if (panelExited) return;
  postApi('/api/share/logs', {}).then(list => {
    var tbody = document.getElementById('share-logs');
    if (!tbody) return;
    tbody.innerHTML = '';
    if (!Array.isArray(list) || list.length === 0) {
      tbody.innerHTML = '<tr><td colspan="5" class="empty">' + ((list && list.error) ? escapeHtml(list.error) : '暂无记录') + '</td></tr>';
      return;
    }
    list.forEach(l => {
      var tr = document.createElement('tr');
      
      var td1 = document.createElement('td'); td1.className = 'dim'; td1.textContent = new Date(l.time).toLocaleString();
      var td2 = document.createElement('td'); td2.textContent = l.name || l.token;
      var td3 = document.createElement('td'); td3.className = 'dim'; td3.textContent = l.ip;
      var td4 = document.createElement('td'); td4.className = 'dim'; td4.textContent = fmtSize(l.bytes);
      
      // ★ 核心修复：根据状态渲染不同颜色，ok 变绿底 200 OK
      var td5 = document.createElement('td'); 
      if (l.status === 'ok') {
          td5.innerHTML = '<span style="color:#3ddc84; font-weight:600;">200 OK</span>';
      } else if (l.status === 'denied-pwd') {
          td5.innerHTML = '<span style="color:#f0c040;">密码错误</span>';
      } else if (l.status === 'expired') {
          td5.innerHTML = '<span style="color:#ff5c5c;">已过期</span>';
      } else if (l.status === 'not-found') {
          td5.innerHTML = '<span style="color:#ff5c5c;">未找到</span>';
      } else {
          td5.innerHTML = '<span style="color:#ff5c5c;">' + l.status + '</span>';
      }

      tr.appendChild(td1); tr.appendChild(td2); tr.appendChild(td3); tr.appendChild(td4); tr.appendChild(td5);
      tbody.appendChild(tr);
    });
  }).catch(() => {});
}