package com.airbridge.core;

public record Params(
        int width,
        int height,
        int overlayHeight,
        int cellSize,
        int fps,
        int chunkSize
) {
    public static final int DEFAULT_CHUNK_SIZE = 32 * 1024;

    public static Params default1080p() {
        return new Params(1920, 1080, 96, 8, 24, DEFAULT_CHUNK_SIZE);
    }

    public static Params default4k() {
        return new Params(3840, 2160, 144, 8, 24, DEFAULT_CHUNK_SIZE);
    }

    public int codecGridHeight() {
        return height - overlayHeight;
    }
}
