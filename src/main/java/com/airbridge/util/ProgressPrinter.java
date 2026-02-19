package com.airbridge.util;

public final class ProgressPrinter {
    private final String stage;
    private long lastPercent = Long.MIN_VALUE;

    public ProgressPrinter(String stage) {
        this.stage = stage;
    }

    public void update(long current, long total) {
        long safeTotal = Math.max(total, 1L);
        long percent = total <= 0
                ? 100L
                : Math.max(0L, Math.min(100L, Math.round((current * 100.0) / safeTotal)));

        if (current == 0 || current == total || percent != lastPercent) {
            System.out.printf("[%s] %d/%d (%d%%)%n", stage, current, total, percent);
            lastPercent = percent;
        }
    }
}
