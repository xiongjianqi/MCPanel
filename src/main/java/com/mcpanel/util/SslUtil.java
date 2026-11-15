package com.mcpanel.util;

import javax.net.ssl.*;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public class SslUtil {

    public static void generateSelfSigned(File certFile, File keyFile) throws Exception {
        java.security.KeyPairGenerator keyGen = java.security.KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        java.security.KeyPair keyPair = keyGen.generateKeyPair();

        long now = System.currentTimeMillis();
        java.util.Date notBefore = new java.util.Date(now);
        java.util.Date notAfter = new java.util.Date(now + 3650L * 24 * 3600 * 1000);

        org.bouncycastle.asn1.x500.X500Name subject = new org.bouncycastle.asn1.x500.X500Name("CN=MCPanel Self-Signed");
        java.math.BigInteger serial = java.math.BigInteger.valueOf(System.currentTimeMillis());

        org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder builder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, keyPair.getPublic());
        
        org.bouncycastle.operator.ContentSigner signer = new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                .build(keyPair.getPrivate());
        org.bouncycastle.cert.X509CertificateHolder certHolder = builder.build(signer);
        
        try (java.io.FileWriter fw = new java.io.FileWriter(certFile)) {
            fw.write("-----BEGIN CERTIFICATE-----\n");
            fw.write(java.util.Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(certHolder.getEncoded()));
            fw.write("\n-----END CERTIFICATE-----\n");
        }

        try (java.io.FileWriter fw = new java.io.FileWriter(keyFile)) {
            fw.write("-----BEGIN PRIVATE KEY-----\n");
            fw.write(java.util.Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keyPair.getPrivate().getEncoded()));
            fw.write("\n-----END PRIVATE KEY-----\n");
        }
    }

    public static SSLContext createSslContextFromPem(File certFile, File keyFile) throws Exception {
        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        java.util.List<java.security.cert.Certificate> certs = new java.util.ArrayList<>();
        try (java.io.InputStream is = new java.io.FileInputStream(certFile)) {
            certs.addAll(cf.generateCertificates(is));
        }

        String keyPem = new String(java.nio.file.Files.readAllBytes(keyFile.toPath()), java.nio.charset.StandardCharsets.UTF_8)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] keyBytes = java.util.Base64.getDecoder().decode(keyPem);
        java.security.spec.PKCS8EncodedKeySpec spec = new java.security.spec.PKCS8EncodedKeySpec(keyBytes);
        java.security.PrivateKey privateKey = java.security.KeyFactory.getInstance("RSA").generatePrivate(spec);

        java.security.KeyStore ks = java.security.KeyStore.getInstance("JKS");
        ks.load(null, null);
        String password = java.util.UUID.randomUUID().toString();
        ks.setKeyEntry("server", privateKey, password.toCharArray(), certs.toArray(new java.security.cert.Certificate[0]));

        javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password.toCharArray());
        javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);
        return sslContext;
    }

    public static SSLContext createSslContext(File certFile, File keyFile) throws Exception {
        // ★ 修复：必须向外抛出异常，不能被吞掉
        try {
            java.nio.file.Path p = keyFile.toPath();
            java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
                    java.nio.file.Files.getPosixFilePermissions(p);
            if (perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ)
                    || perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ)) {
                throw new RuntimeException("私钥文件权限过宽，拒绝加载。请执行 chmod 600 " + keyFile.getAbsolutePath());
            }
        } catch (UnsupportedOperationException ignored) {
            // Windows 忽略
        } catch (RuntimeException e) {
            throw e; // ★ 修复：必须向外抛出，不能被下方吞掉
        } catch (Exception e) {
            throw new RuntimeException("检查私钥权限失败: " + e.getMessage());
        }

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert;
        try (FileInputStream fis = new FileInputStream(certFile)) {
            cert = (X509Certificate) cf.generateCertificate(fis);
        }

        String keyContent = new String(Files.readAllBytes(keyFile.toPath()), StandardCharsets.UTF_8);
        keyContent = keyContent.replace("-----BEGIN PRIVATE KEY-----", "")
                               .replace("-----END PRIVATE KEY-----", "")
                               .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                               .replace("-----END RSA PRIVATE KEY-----", "")
                               .replace("-----BEGIN EC PRIVATE KEY-----", "")
                               .replace("-----END EC PRIVATE KEY-----", "")
                               .replaceAll("\\s+", "");
        
        byte[] keyBytes = Base64.getDecoder().decode(keyContent);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        
        PrivateKey privateKey = null;
        String[] algs = {"RSA", "EC", "DSA"};
        Exception lastEx = null;
        for (String alg : algs) {
            try {
                KeyFactory kf = KeyFactory.getInstance(alg);
                privateKey = kf.generatePrivate(spec);
                break;
            } catch (Exception e) {
                lastEx = e;
            }
        }
        if (privateKey == null) {
            throw new RuntimeException("无法解析私钥，请确保是标准的 PKCS#8 格式 (BEGIN PRIVATE KEY 开头)", lastEx);
        }

        String randomPass = java.util.UUID.randomUUID().toString();
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        keyStore.setKeyEntry("server", privateKey, randomPass.toCharArray(), new Certificate[]{cert});

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, randomPass.toCharArray());

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);
        return sslContext;
    }
}