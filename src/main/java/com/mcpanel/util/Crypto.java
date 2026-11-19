package com.mcpanel.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

public class Crypto {
    private static SecretKey aesKey;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    public static void initKey(String path) throws Exception {
        File file = new File(path);
        if (file.exists()) {
            try {
                java.util.Set<java.nio.file.attribute.PosixFilePermission> perms = java.nio.file.Files.getPosixFilePermissions(file.toPath());
                if (perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ) || perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ)) {
                    // ★ 核心修复：直接拒绝，不再只警告
                    throw new RuntimeException("密钥文件权限过宽（组或其他用户可读）。请执行 chmod 600 " + file.getAbsolutePath() + " 后再启动。");
                }
            } catch (UnsupportedOperationException ignored) {
                // Windows 环境下忽略
            }
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] keyBytes = new byte[32];
                // ★ 修复：FileInputStream.read(byte[]) 不保证一次读满，旧代码用单次 read 判断
                // != 32 可能把正常密钥文件误判为损坏（进而拒绝启动）；这里循环读满。
                int off = 0;
                while (off < keyBytes.length) {
                    int n = fis.read(keyBytes, off, keyBytes.length - off);
                    if (n < 0) break;
                    off += n;
                }
                if (off != keyBytes.length) {
                    throw new RuntimeException("密钥文件损坏（长度不为 32 字节）");
                }
                aesKey = new SecretKeySpec(keyBytes, "AES");
            }
        } else {
            SecureRandom rng = new SecureRandom();
            byte[] keyBytes = new byte[32];
            rng.nextBytes(keyBytes);
            aesKey = new SecretKeySpec(keyBytes, "AES");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(keyBytes);
            }
            try {
                file.setReadable(false, false);
                file.setReadable(true, true);
                file.setWritable(false, false);
                file.setWritable(true, true);
            } catch (Exception ignored) {}
        }
    }

    public static String hashPassword(String password) {
        try {
            SecureRandom random = new SecureRandom();
            byte[] salt = new byte[16];
            random.nextBytes(salt);
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120000, 256);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = skf.generateSecret(spec).getEncoded();
            
            byte[] combined = new byte[salt.length + hash.length];
            System.arraycopy(salt, 0, combined, 0, salt.length);
            System.arraycopy(hash, 0, combined, salt.length, hash.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("密码哈希失败", e);
        }
    }

    public static boolean verifyPassword(String password, String stored) {
        if (stored == null || stored.isEmpty()) return false;
        try {
            byte[] combined = Base64.getDecoder().decode(stored);
            if (combined.length < 16) return false;
            
            byte[] salt = new byte[16];
            System.arraycopy(combined, 0, salt, 0, 16);
            
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120000, 256);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = skf.generateSecret(spec).getEncoded();
            
            if (combined.length - 16 != hash.length) return false;
            int diff = 0;
            for (int i = 0; i < hash.length; i++) {
                diff |= combined[16 + i] ^ hash[i];
            }
            return diff == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, spec);
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("加密失败", e);
        }
    }

    public static String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) return "";
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText);
            if (combined.length < GCM_IV_LENGTH) throw new IllegalArgumentException("密文过短");
            
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, spec);
            
            byte[] encrypted = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, encrypted, 0, encrypted.length);
            
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new SecurityException("解密失败：密文被篡改或密钥错误", e);
        }
    }
}