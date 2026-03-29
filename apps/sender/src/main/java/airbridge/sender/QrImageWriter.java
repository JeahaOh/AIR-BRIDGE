package airbridge.sender;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;

final class QrImageWriter {
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

    BufferedImage generateQrImage(String data, String labelLine1, String labelLine2) throws WriterException {
        QRCodeWriter writer = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, qrErrorLevel);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 2);

        BitMatrix matrix = writer.encode(data, BarcodeFormat.QR_CODE, qrImageSize, qrImageSize, hints);

        BufferedImage qrImage = MatrixToImageWriter.toBufferedImage(matrix);
        int qrW = qrImage.getWidth();
        int qrH = qrImage.getHeight();

        BufferedImage finalImage = new BufferedImage(qrW, qrH + labelHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = finalImage.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, qrW, qrH + labelHeight);
        g.drawImage(qrImage, 0, 0, null);

        g.setColor(Color.BLACK);
        Font fontBig = new Font(Font.SANS_SERIF, Font.BOLD, 22);
        g.setFont(fontBig);
        FontMetrics fmBig = g.getFontMetrics();
        String display1 = truncateLabel(labelLine1, fmBig, qrW - 20);
        int x1 = (qrW - fmBig.stringWidth(display1)) / 2;
        int y1 = qrH + fmBig.getAscent() + 8;
        g.drawString(display1, x1, y1);

        g.setColor(new Color(80, 80, 80));
        Font fontSmall = new Font(Font.MONOSPACED, Font.PLAIN, 14);
        g.setFont(fontSmall);
        FontMetrics fmSmall = g.getFontMetrics();
        String display2 = truncateLabel(labelLine2, fmSmall, qrW - 20);
        int x2 = (qrW - fmSmall.stringWidth(display2)) / 2;
        int y2 = y1 + fmSmall.getAscent() + 10;
        g.drawString(display2, x2, y2);

        g.dispose();
        return finalImage;
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
