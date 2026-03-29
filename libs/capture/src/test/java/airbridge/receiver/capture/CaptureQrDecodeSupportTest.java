package airbridge.receiver.capture;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CaptureQrDecodeSupportTest {

    @Test
    void decodesStandardQrImage() throws Exception {
        BufferedImage image = createQrImage("payload-standard", 320);

        assertEquals("payload-standard", CaptureQrDecodeSupport.decodeQrPayloadWithRetries(image));
    }

    @Test
    void decodesRotatedQrImageOnLargeCanvas() throws Exception {
        BufferedImage qr = createQrImage("payload-rotated", 260);
        BufferedImage rotated = rotate(qr, 90);
        BufferedImage canvas = new BufferedImage(900, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        g.drawImage(rotated, 520, 120, null);
        g.dispose();

        assertEquals("payload-rotated", CaptureQrDecodeSupport.decodeQrPayloadWithRetries(canvas));
    }

    @Test
    void throwsNotFoundForImageWithoutQr() {
        BufferedImage blank = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = blank.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 400, 400);
        g.dispose();

        assertThrows(NotFoundException.class, () -> CaptureQrDecodeSupport.decodeQrPayloadWithRetries(blank));
    }

    private static BufferedImage createQrImage(String payload, int size) throws Exception {
        QRCodeWriter writer = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 2);
        return MatrixToImageWriter.toBufferedImage(writer.encode(payload, BarcodeFormat.QR_CODE, size, size, hints));
    }

    private static BufferedImage rotate(BufferedImage source, int degrees) {
        int width = source.getWidth();
        int height = source.getHeight();
        boolean swap = degrees == 90 || degrees == 270;
        BufferedImage rotated = new BufferedImage(
                swap ? height : width,
                swap ? width : height,
                BufferedImage.TYPE_INT_RGB
        );
        Graphics2D g = rotated.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, rotated.getWidth(), rotated.getHeight());
        g.translate(rotated.getWidth() / 2.0, rotated.getHeight() / 2.0);
        g.rotate(Math.toRadians(degrees));
        g.translate(-width / 2.0, -height / 2.0);
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return rotated;
    }
}
