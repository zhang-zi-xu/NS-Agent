package com.nongxin.integration.storage;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/** JPEG normalization is separate from metadata persistence and attachment lifecycle decisions. */
public final class JpegImagePreparation {
    private static final int MAX_EDGE = 1600;
    private static final int MIN_EDGE = 200;
    private static final float JPEG_QUALITY = 0.85f;

    private JpegImagePreparation() {}

    public static BufferedImage decode(byte[] raw) {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IOException failure) {
            throw new IllegalArgumentException("图片读取失败，请重新选择文件");
        }
        if (source == null) throw new IllegalArgumentException("这不是可识别的图片文件（可能已损坏或格式不受支持）");
        int width = source.getWidth(), height = source.getHeight();
        if (Math.min(width, height) < MIN_EDGE) {
            throw new IllegalArgumentException(
                    "图片分辨率太低（" + width + "×" + height + "），病害识别需要更清楚的照片，请靠近重拍");
        }
        return source;
    }

    public static double scale(int width, int height) {
        int longest = Math.max(width, height);
        return longest <= MAX_EDGE ? 1.0 : (double) MAX_EDGE / longest;
    }

    /** 重编码为 JPEG：透明区域铺白底，EXIF/GPS 等元数据不会带入新文件。 */
    public static byte[] encodeJpeg(BufferedImage source) {
        double factor = scale(source.getWidth(), source.getHeight());
        int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ImageOutputStream stream = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(canvas, null, null), param);
            stream.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("图片处理失败：" + e.getMessage());
        } finally {
            writer.dispose();
        }
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(data))
                    .substring(0, 32);
        } catch (Exception e) {
            return "";
        }
    }
}
