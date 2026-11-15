package com.mcpanel.util;

import com.mcpanel.config.SmtpConfig;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SmtpManager {
    private static final int DEFAULT_TIMEOUT_MS = 10000;

    private static final ExecutorService SEND_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "smtp-sender");
        t.setDaemon(true);
        return t;
    });

    public static boolean sendEmail(String to, String subject, String contentHtml) {
        SmtpConfig smtpCfg = SmtpConfig.getInstance();
        SmtpConfig.Data cfg = smtpCfg.get();

        String host = cfg.host;
        String port = cfg.port;
        String user = cfg.user;
        String from = cfg.from;
        String ssl = cfg.ssl;
        
        if (host.isEmpty() || user.isEmpty()) {
            System.err.println("[SMTP] 未配置 SMTP 服务");
            return false;
        }

        int timeoutMs = 10000;
        if (timeoutMs < 1000) timeoutMs = 1000;

        String pass = smtpCfg.getDecryptedPass();
        
        final String finalPass = pass;
        final String finalUser = user;
        final String finalFrom = from;
        final int finalTimeoutMs = timeoutMs;

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", port);
        props.put("mail.smtp.connectiontimeout", String.valueOf(timeoutMs));
        props.put("mail.smtp.timeout", String.valueOf(timeoutMs));
        props.put("mail.smtp.writetimeout", String.valueOf(timeoutMs));

        if ("ssl".equals(ssl)) {
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            props.put("mail.smtp.socketFactory.port", port);
        } else if ("tls".equals(ssl)) {
            props.put("mail.smtp.starttls.enable", "true");
        }

        final Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(finalUser, finalPass);
            }
        });

        Future<Boolean> future = SEND_POOL.submit(() -> {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(finalFrom, "MC Panel"));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject);
            message.setContent(contentHtml, "text/html; charset=utf-8");
            Transport.send(message);
            return Boolean.TRUE;
        });
        try {
            Boolean ok = future.get(finalTimeoutMs, TimeUnit.MILLISECONDS);
            return ok != null && ok.booleanValue();
        } catch (TimeoutException e) {
            future.cancel(true);
            System.err.println("[SMTP] 发送邮件超时（" + finalTimeoutMs + "ms），已放弃本次发送");
            return false;
        } catch (Exception e) {
            System.err.println("[SMTP] 发送邮件失败: " + e.getMessage());
            return false;
        }
    }
}