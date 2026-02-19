package com.airbridge.frame;

import com.airbridge.core.Params;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Optional;

public final class FrameCodec {
    private final Params params;
    private final int cols;
    private final int rows;
    private final int capacityBytes;
    private final int maxPayloadBytes;

    public FrameCodec(Params params) {
        this.params = params;
        this.cols = params.width() / params.cellSize();
        this.rows = params.codecGridHeight() / params.cellSize();
        int capacityNibbles = cols * rows * 3;
        this.capacityBytes = capacityNibbles / 2;
        this.maxPayloadBytes = capacityBytes - 4;

        if (rows <= 0 || cols <= 0) {
            throw new IllegalArgumentException("Invalid frame grid dimensions");
        }
        if (maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("Frame payload capacity is too small");
        }
    }

    public int maxPayloadBytes() {
        return maxPayloadBytes;
    }

    public BufferedImage encode(byte[] payload, String overlayText) {
        if (payload.length > maxPayloadBytes) {
            throw new IllegalArgumentException("Payload exceeds frame capacity. payload=" + payload.length + ", max=" + maxPayloadBytes);
        }

        byte[] wrapped = ByteBuffer.allocate(4 + payload.length)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(payload.length)
                .put(payload)
                .array();

        BufferedImage image = new BufferedImage(params.width(), params.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, params.width(), params.height());

            drawOverlay(g, overlayText);
            drawPayloadGrid(g, wrapped);
        } finally {
            g.dispose();
        }

        return image;
    }

    public Optional<byte[]> decode(BufferedImage image) {
        if (image.getWidth() != params.width() || image.getHeight() != params.height()) {
            return Optional.empty();
        }

        int capacityNibbles = cols * rows * 3;
        int[] nibbles = new int[capacityNibbles];

        int index = 0;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int x = col * params.cellSize() + (params.cellSize() / 2);
                int y = params.overlayHeight() + row * params.cellSize() + (params.cellSize() / 2);
                int rgb = image.getRGB(x, y);

                nibbles[index++] = quantizeNibble((rgb >>> 16) & 0xFF);
                nibbles[index++] = quantizeNibble((rgb >>> 8) & 0xFF);
                nibbles[index++] = quantizeNibble(rgb & 0xFF);
            }
        }

        byte[] raw = new byte[capacityBytes];
        for (int i = 0; i < raw.length; i++) {
            int high = nibbles[i * 2];
            int low = nibbles[i * 2 + 1];
            raw[i] = (byte) ((high << 4) | low);
        }

        if (raw.length < 4) {
            return Optional.empty();
        }

        int payloadLength = ByteBuffer.wrap(raw, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        if (payloadLength < 0 || payloadLength > maxPayloadBytes) {
            return Optional.empty();
        }
        if (payloadLength > raw.length - 4) {
            return Optional.empty();
        }

        return Optional.of(Arrays.copyOfRange(raw, 4, 4 + payloadLength));
    }

    private void drawOverlay(Graphics2D g, String overlayText) {
        g.setColor(new Color(16, 16, 16));
        g.fillRect(0, 0, params.width(), params.overlayHeight());

        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.max(16, params.overlayHeight() / 3)));

        String text = overlayText == null ? "" : overlayText;
        if (text.length() > 180) {
            text = text.substring(0, 177) + "...";
        }

        int baseline = Math.max(24, params.overlayHeight() / 2 + 8);
        g.drawString(text, 18, baseline);
    }

    private void drawPayloadGrid(Graphics2D g, byte[] wrappedPayload) {
        int requiredNibbles = wrappedPayload.length * 2;
        int nibbleIndex = 0;

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int rNib = nibbleIndex < requiredNibbles ? nibbleAt(wrappedPayload, nibbleIndex++) : 0;
                int gNib = nibbleIndex < requiredNibbles ? nibbleAt(wrappedPayload, nibbleIndex++) : 0;
                int bNib = nibbleIndex < requiredNibbles ? nibbleAt(wrappedPayload, nibbleIndex++) : 0;

                Color color = new Color(rNib * 17, gNib * 17, bNib * 17);
                int x = col * params.cellSize();
                int y = params.overlayHeight() + row * params.cellSize();
                g.setColor(color);
                g.fillRect(x, y, params.cellSize(), params.cellSize());
            }
        }
    }

    private static int nibbleAt(byte[] bytes, int nibbleIndex) {
        int value = bytes[nibbleIndex / 2] & 0xFF;
        return (nibbleIndex & 1) == 0 ? (value >>> 4) & 0x0F : value & 0x0F;
    }

    private static int quantizeNibble(int value) {
        int nibble = (value + 8) / 17;
        if (nibble < 0) {
            return 0;
        }
        if (nibble > 15) {
            return 15;
        }
        return nibble;
    }
}
