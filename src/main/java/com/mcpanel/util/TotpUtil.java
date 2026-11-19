package com.mcpanel.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

public class TotpUtil {
    private static final int TIME_STEP = 30;
    private static final int CODE_DIGITS = 6;

    public static String generateQRBase64(String text) {
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, 200, 200);
            ByteArrayOutputStream pngOutputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", pngOutputStream);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(pngOutputStream.toByteArray());
        } catch (Exception e) {
            return "";
        }
    }

    // 生成 16 字符的 Base32 密钥
    public static String generateSecret() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[10];
        random.nextBytes(bytes);
        return new String(encodeBase32(bytes)).toUpperCase();
    }

    public static boolean verifyCode(String secret, int code) {
        // ★ 修复：generateCode 出错时会返回 -1，而旧实现直接和传入的 code 比较，
        // 攻击者提交 "-1" 即可在密钥损坏/解析失败时通过校验（2FA 绕过）。
        if (secret == null || secret.isEmpty()) return false;
        if (code < 0 || code > 999999) return false;
        long time = System.currentTimeMillis() / 1000L;
        for (int i = -1; i <= 1; i++) { // 允许前后 30 秒的误差
            if (generateCode(secret, time / TIME_STEP + i) == code) return true;
        }
        return false;
    }

    public static int generateCode(String secret, long timeSlice) {
        try {
            byte[] key = decodeBase32(secret);
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(timeSlice).array());
            int offset = hash[hash.length - 1] & 0xF;
            int binary = ((hash[offset] & 0x7f) << 24) |
                    ((hash[offset + 1] & 0xff) << 16) |
                    ((hash[offset + 2] & 0xff) << 8) |
                    (hash[offset + 3] & 0xff);
            return binary % (int) Math.pow(10, CODE_DIGITS);
        } catch (Exception e) { return -1; }
    }

    // 简易 Base32 实现
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static String encodeBase32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bitsLeft = 0;
        for (byte b : data) {
            buffer <<= 8; buffer |= (b & 0xFF); bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1F)); bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) sb.append(ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        return sb.toString();
    }

    private static byte[] decodeBase32(String base32) {
        base32 = base32.toUpperCase().replaceAll("[^A-Z2-7]", "");
        int bits = 0, value = 0, index = 0;
        byte[] out = new byte[base32.length() * 5 / 8];
        for (char c : base32.toCharArray()) {
            int idx = ALPHABET.indexOf(c);
            if (idx < 0) continue;
            value = (value << 5) | idx; bits += 5;
            if (bits >= 8) { out[index++] = (byte) ((value >> (bits - 8)) & 0xFF); bits -= 8; }
        }
        return out;
    }
}
