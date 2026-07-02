package airbridge.slide;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

final class SlideDefaults {
    private static final String SETTINGS_RESOURCE = "slide.properties";
    private static final int FALLBACK_PAGE_DISPLAY_MS = 100;
    private static final int FALLBACK_BLACK_FRAME_MS = 50;
    private static final int FALLBACK_LOOP_COUNT = 1;

    // 한 장의 이미지를 보여주는 기본 시간이다.
    static final int DEFAULT_PAGE_DISPLAY_MS;
    // 장면 사이 블랙 프레임 기본 시간이다.
    static final int DEFAULT_BLACK_FRAME_MS;
    // 슬라이드쇼를 반복 재생하는 기본 횟수다.
    static final int DEFAULT_LOOP_COUNT;
    // 한 장을 보여주는 시간(ms)의 하한이다. 모니터 주사율을 감안한 고속 재생용 최소값.
    static final int MIN_PAGE_DISPLAY_MS = 20;
    // 한 장을 보여주는 시간(ms)의 상한이다.
    static final int MAX_PAGE_DISPLAY_MS = 10_000;
    // 한 장을 보여주는 시간(ms)의 스피너 증감 단위다.
    static final int PAGE_DISPLAY_STEP_MS = 10;
    // 장면 사이 블랙 프레임 시간(ms)의 하한이다.
    static final int MIN_BLACK_FRAME_MS = 1;
    // 장면 사이 블랙 프레임 시간(ms)의 상한이다.
    static final int MAX_BLACK_FRAME_MS = 2_000;
    // 장면 사이 블랙 프레임 시간(ms)의 스피너 증감 단위다.
    static final int BLACK_FRAME_STEP_MS = 10;
    // 메모리에 유지할 이미지 캐시 최대 개수다.
    static final int MAX_CACHE_SIZE = 200;
    // 시작 직후 미리 읽어 둘 이미지 개수다.
    static final int PRELOAD_COUNT = 30;
    // 현재 인덱스 주변에서 추가로 앞뒤 선로딩할 이미지 개수다.
    static final int PREFETCH_COUNT = 20;
    // 마지막 재생 후 블랙 화면을 유지하는 기본 시간이다.
    static final int POST_FINISH_BLACKOUT_MS = 300_000;
    // 좌우 패널 분할선이 보이도록 둘 기본 두께다.
    static final int VISIBLE_DIVIDER_SIZE = 8;

    static {
        Properties properties = loadProperties();
        DEFAULT_PAGE_DISPLAY_MS = loadIntProperty(properties, "slide.page-display-ms", FALLBACK_PAGE_DISPLAY_MS);
        DEFAULT_BLACK_FRAME_MS = loadIntProperty(properties, "slide.black-frame-ms", FALLBACK_BLACK_FRAME_MS);
        DEFAULT_LOOP_COUNT = loadIntProperty(properties, "slide.loop-count", FALLBACK_LOOP_COUNT);
    }

    private SlideDefaults() {
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();
        try (InputStream input = SlideDefaults.class.getClassLoader().getResourceAsStream(SETTINGS_RESOURCE)) {
            if (input == null) {
                return properties;
            }
            properties.load(input);
            return properties;
        } catch (IOException ignored) {
            return properties;
        }
    }

    static int loadIntProperty(Properties properties, String key, int fallback) {
        String raw = properties.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
