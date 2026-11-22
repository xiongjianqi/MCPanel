function remoteInit() {
  if (panelExited) return;
  
  var card = document.getElementById('remote-card');
  var disabled = document.getElementById('remote-disabled');
  var need2fa = document.getElementById('remote-need-2fa');
  var needKey = document.getElementById('remote-need-key');
  
  var container = document.getElementById('tab-remote');
  if(!container) return;
  
  if (card) card.style.display = 'none';
  if (disabled) { disabled.style.display = 'none'; disabled.textContent = ''; }
  if (need2fa) need2fa.style.display = 'none';
  if (needKey) needKey.style.display = 'none';

  // ================= 1. 全局 Root 视图 =================
  if (!currentInstanceId) {
    if (!currentIsMain) {
      if (disabled) {
        disabled.style.display = 'block';
        disabled.textContent = "只有所有者可以查看全局 Root 远程管理。";
      }
      return;
    }
    
    postApi('/api/remote/master', {}).then(r => {
      if (r && r.need2fa) {
        if (need2fa) need2fa.style.display = 'block';
        return;
      }
      if (r && r.needKeySetup) {
        if (needKey) needKey.style.display = 'block';
        return;
      }
      if (r && r.enabled) {
        if (card) card.style.display = 'block';
        var elDom = document.getElementById('remote-domain');
        if(elDom) elDom.textContent = r.domain || '未配置域名';
        var elUser = document.getElementById('remote-user');
        if(elUser) elUser.textContent = r.user || 'root';
        
        var elSsh = document.getElementById('remote-ssh-port');
        if(elSsh) elSsh.textContent = r.sshPort || '-';
        var elSftp = document.getElementById('remote-sftp-port');
        if(elSftp) elSftp.textContent = r.sftpPort || '-';
        
        var btnKey = document.getElementById('btn-download-key');
        if(btnKey) {
            btnKey.style.display = 'inline-flex';
            btnKey.textContent = '下载 Root 私钥';
            btnKey.onclick = downloadRootKey;
        }
      } else {
        if (disabled) {
          disabled.style.display = 'block';
          disabled.textContent = (r && r.reason) ? r.reason : "当前运行环境不支持。如需使用 Root 远程管理，请确认面板以 Root 身份运行且未开启 Docker 沙箱隔离。";
        }
      }
    }).catch((e) => {
      if (disabled) {
        disabled.style.display = 'block';
        disabled.textContent = "请求出错: " + (e.message || "网络异常，请检查后端是否正常");
      }
    });
    return;
  }

  // ================= 2. 实例视图 =================
  var url = '/api/remote/info?instance=' + encodeURIComponent(currentInstanceId);
  postApi(url, {}).then(r => {
    if (r && r.error) {
        if (disabled) {
            disabled.style.display = 'block';
            disabled.textContent = r.error;
        }
        return;
    }
    if (r && r.enabled) {
      if (card) card.style.display = 'block';
      
      var elDom = document.getElementById('remote-domain');
      if(elDom) elDom.textContent = r.domain || '未配置域名';
      var elUser = document.getElementById('remote-user');
      if(elUser) elUser.textContent = r.user;
      
      // ★ 修复：补上遗漏的 SSH 端口赋值
      var elSsh = document.getElementById('remote-ssh-port');
      if(elSsh) elSsh.textContent = r.sshPort || '-';
      
      var elSftp = document.getElementById('remote-sftp-port');
      if(elSftp) elSftp.textContent = r.sftpPort || '-';
      
      // 已改为仅密钥登录：密码栏、认证方式栏都已移除
      var btnKey = document.getElementById('btn-download-key');
      if (btnKey) {
        if (r.hasKey) {
          btnKey.style.display = 'inline-flex';
          btnKey.textContent = '下载私钥';
          btnKey.onclick = function() {
              window.location.href = '/api/remote/ssh-key?instance=' + encodeURIComponent(currentInstanceId);
          };
        } else {
          btnKey.style.display = 'none';
        }
      }
      
      var sshCmdBtn = document.querySelector('button[onclick="copyRemoteCmd(\'ssh\')"]');
      if (sshCmdBtn) sshCmdBtn.style.display = 'block';
      
      var sftpCmdBtn = document.querySelector('button[onclick="copyRemoteCmd(\'sftp\')"]');
      if (sftpCmdBtn) sftpCmdBtn.textContent = '复制 SFTP 命令';
      
    } else {
      if (disabled) {
        disabled.style.display = 'block';
        disabled.textContent = (r && r.reason) ? r.reason : "当前实例不支持系统级权限隔离。";
      }
    }
  }).catch((e) => {
    if (disabled) {
      disabled.style.display = 'block';
      disabled.textContent = "获取实例远程信息失败: " + (e.message || "未知错误");
    }
  });
}

function setupRootKey() {
    uiConfirm({ title: '配置 Root 密钥', message: '这将自动生成一对 SSH 密钥，并将公钥写入 /root/.ssh/authorized_keys。\n\n生成后请务必下载私钥文件并妥善保管。', okText: '开始配置' }).then(ok => {
        if (!ok) return;
        uiPrompt({ title: '安全验证', label: '请输入动态验证码 (TOTP)', placeholder: '6 位数字' }).then(totp => {
            if (!totp) return;
            toast('正在生成并配置密钥...');
            postApi('/api/remote/master/setup-key', { totp: totp }).then(r => {
                if (r && r.ok) {
                    toast('密钥配置成功！请点击下方按钮下载（仅一次机会）', 'ok');
                    remoteInit();
                } else {
                    toast((r && r.error) || '配置失败', 'err');
                }
            });
        });
    });
}

function downloadRootKey() {
    uiConfirm({ title: '下载 Root 私钥', message: '出于安全考虑，该私钥仅能下载一次。\n下载后服务器将立即销毁该私钥文件，无法再次获取，只能重新生成。\n\n请确保你拥有安全的保存位置！', danger: true, okText: '我已知晓，继续' }).then(ok => {
        if (!ok) return;
        
        uiPrompt({ title: '安全验证', label: '请输入动态验证码 (TOTP)', placeholder: '6 位数字' }).then(totp => {
            if (!totp) return;
            toast('正在验证并下载...');
            
            fetch('/api/remote/master/download-key', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ totp: totp })
            }).then(r => {
                if (!r.ok) {
                    return r.text().then(t => {
                        var d = null; try { d = JSON.parse(t); } catch (e) {}
                        throw new Error((d && d.error) || ('HTTP ' + r.status));
                    });
                }
                return r.blob();
            }).then(blob => {
                var a = document.createElement('a');
                a.href = URL.createObjectURL(blob);
                a.download = 'root_rsa.key';
                document.body.appendChild(a);
                a.click();
                setTimeout(() => {
                    URL.revokeObjectURL(a.href);
                    if (a.parentNode) a.parentNode.removeChild(a);
                }, 1000);
                toast('下载成功，服务端私钥已销毁', 'ok');
                remoteInit(); // 刷新页面让按钮消失
            }).catch(e => {
                toast(e.message || '下载失败', 'err');
            });
        });
    });
}

function copyRemoteCmd(type) {
  var domain = document.getElementById('remote-domain');
  var user = document.getElementById('remote-user');
  if(!domain || !user) return;
  
  var portEl = type === 'ssh' ? document.getElementById('remote-ssh-port') : document.getElementById('remote-sftp-port');
  var port = portEl ? portEl.textContent : '22';
  if (port === '-') port = '22';
  
  if (!domain.textContent || user.textContent === '-' || domain.textContent === '-' || domain.textContent === '未配置域名') return;
  
  // 已改为仅密钥登录：两种命令都带 -i，提示替换成自己的私钥路径
  var cmd;
  if (type === 'ssh') {
      cmd = "ssh -i /path/to/private_key -p " + port + " " + user.textContent + "@" + domain.textContent;
  } else {
      cmd = "sftp -i /path/to/private_key -P " + port + " " + user.textContent + "@" + domain.textContent;
  }

  copyToClipboard(cmd).then(() => toast('已复制', 'ok'));
}