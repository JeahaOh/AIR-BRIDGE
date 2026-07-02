package airbridge.sender;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

final class QrImageWriter {
    private static final Font LABEL_FONT_BIG = new Font(Font.SANS_SERIF, Font.BOLD, 22);
    private static final Font LABEL_FONT_SMALL = new Font(Font.MONOSPACED, Font.PLAIN, 14);
    private static final Color LABEL_COLOR_SMALL = new Color(80, 80, 80);
    // JDK PNG writer quality knob: 0.75 selects a faster deflate level with row filtering,
    // which on QR module imagery is both faster and no larger than the default (0.5).
    private static final float PNG_COMPRESSION_QUALITY = 0.75f;

    private final int qrImageSize;
    private final int labelHeight;
    private final ErrorCorrectionLevel qrErrorLevel;

    QrImageWriter(int qrImageSize, int labelHeight, ErrorCorrectionLevel qrErrorLevel) {
        if (qrImageSize < 1) {
            throw new IllegalArgumentException("qrImageSize must be >= 1");
        }
        if (labelHeight < 0) {
            throw new IllegalArgumentException("labelHeight must be >= 0");
        }
        this.qrImageSize = qrImageSize;
        this.labelHeight = labelHeight;
        this.qrErrorLevel = qrErrorLevel;
    }

    BufferedImage generateQrImage(byte[] data, String labelLine1, String labelLine2) throws WriterException {
        QRCodeWriter writer = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, qrErrorLevel);
        // ISO-8859-1 maps one byte to one char, so ZXing encodes the raw bytes in QR 8-bit byte
        // mode with no ECI segment; the decoder recovers the bytes with the same charset.
        hints.put(EncodeHintType.CHARACTER_SET, "ISO-8859-1");
        hints.put(EncodeHintType.MARGIN, 2);

        String content = new String(data, StandardCharsets.ISO_8859_1);
        BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, qrImageSize, qrImageSize, hints);
        int qrW = matrix.getWidth();
        int qrH = matrix.getHeight();

        // QR is black/white and the label is gray text, so 8-bit grayscale is sufficient and
        // produces much smaller PNGs (faster to write on encode and read on decode) than RGB.
        // The matrix is written straight into the grayscale raster: going through an
        // intermediate RGB image plus drawImage color conversion costs ~40ms per 1200px QR,
        // the direct fill ~2ms, with bit-identical pixels.
        BufferedImage finalImage = new BufferedImage(qrW, qrH + labelHeight, BufferedImage.TYPE_BYTE_GRAY);
        byte[] pixels = ((DataBufferByte) finalImage.getRaster().getDataBuffer()).getData();
        Arrays.fill(pixels, (byte) 0xFF);
        for (int y = 0; y < qrH; y++) {
            int row = y * qrW;
            for (int x = 0; x < qrW; x++) {
                if (matrix.get(x, y)) {
                    pixels[row + x] = 0;
                }
            }
        }

        Graphics2D g = finalImage.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(Color.BLACK);
        g.setFont(LABEL_FONT_BIG);
        FontMetrics fmBig = g.getFontMetrics();
        String display1 = truncateLabel(labelLine1, fmBig, qrW - 20);
        int x1 = (qrW - fmBig.stringWidth(display1)) / 2;
        int y1 = qrH + fmBig.getAscent() + 8;
        g.drawString(display1, x1, y1);

        g.setColor(LABEL_COLOR_SMALL);
        g.setFont(LABEL_FONT_SMALL);
        FontMetrics fmSmall = g.getFontMetrics();
        String display2 = truncateLabel(labelLine2, fmSmall, qrW - 20);
        int x2 = (qrW - fmSmall.stringWidth(display2)) / 2;
        int y2 = y1 + fmSmall.getAscent() + 10;
        g.drawString(display2, x2, y2);

        g.dispose();
        return finalImage;
    }

    /**
     * Writes {@code image} as a PNG with the tuned compression setting. Same output pixels as
     * {@code ImageIO.write(image, "PNG", ...)}, only the deflate speed/size trade-off differs.
     */
    static void writePng(BufferedImage image, Path target) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(PNG_COMPRESSION_QUALITY);
        try (ImageOutputStream out = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static String truncateLabel(String text, FontMetrics fm, int maxWidth) {
        if (fm.stringWidth(text) <= maxWidth) {
            return text;
        }
        while (fm.stringWidth(text + "...") > maxWidth && text.length() > 10) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "...";
    }
}
