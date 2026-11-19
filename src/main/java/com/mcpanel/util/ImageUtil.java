package com.mcpanel.util;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;

public class ImageUtil {
    /**
     * 将任意图片流转换为 WebP 格式（不改变分辨率）。
     */
    public static boolean convertToWebP(InputStream input, File output) {
        try {
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                return false; // 不是有效的图片或格式不支持
            }
            if (output.getParentFile() != null) {
                output.getParentFile().mkdirs();
            }
            return ImageIO.write(image, "webp", output);
        } catch (Throwable e) {
            System.err.println("[ImageUtil] WebP 转换失败: " + e.getMessage());
            return false;
        }
    }
}
