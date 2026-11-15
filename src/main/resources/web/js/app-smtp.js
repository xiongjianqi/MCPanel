function smtpLoad() {
  if (panelExited) return;
  fetch('/api/smtp/get').then(r => r.json()).then(d => {
    if (d.error) { toast(d.error, 'err'); return; }
    if (document.getElementById('smtp-host')) document.getElementById('smtp-host').value = d.host || '';
    if (document.getElementById('smtp-port')) document.getElementById('smtp-port').value = d.port || '465';
    if (document.getElementById('smtp-ssl')) document.getElementById('smtp-ssl').value = d.ssl || 'ssl';
    if (document.getElementById('smtp-from')) document.getElementById('smtp-from').value = d.from || '';
    if (document.getElementById('smtp-user')) document.getElementById('smtp-user').value = d.user || '';
    if (document.getElementById('smtp-pass')) document.getElementById('smtp-pass').value = '';
  }).catch(() => toast('读取 SMTP 配置失败', 'err'));
}

function smtpSave() {
  var payload = {
    host: document.getElementById('smtp-host').value.trim(),
    port: document.getElementById('smtp-port').value.trim(),
    ssl: document.getElementById('smtp-ssl').value,
    from: document.getElementById('smtp-from').value.trim(),
    user: document.getElementById('smtp-user').value.trim(),
    pass: document.getElementById('smtp-pass').value
  };
  postApi('/api/smtp/save', payload).then(r => {
    if (r && r.ok) { toast('SMTP 配置已保存', 'ok'); }
    else toast((r && r.error) || '保存失败', 'err');
  });
}

function smtpTest() {
  uiPrompt({ title: '测试 SMTP', label: '接收测试邮件的邮箱', placeholder: 'example@qq.com' }).then(email => {
    if (!email) return;
    toast('正在发送测试邮件...');
    postApi('/api/smtp/test', { email: email }).then(r => {
      if (r && r.ok) toast('发送成功，请检查收件箱', 'ok');
      else toast((r && r.error) || '发送失败', 'err');
    });
  });
}