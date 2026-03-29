package airbridge.slide;

final class SlideDefaults {
    // 한 장의 이미지를 보여주는 기본 시간이다.
    static final int DEFAULT_PAGE_DISPLAY_MS = 400;
    // 장면 사이 블랙 프레임 기본 시간이다.
    static final int DEFAULT_BLACK_FRAME_MS = 100;
    // 슬라이드쇼를 반복 재생하는 기본 횟수다.
    static final int DEFAULT_LOOP_COUNT = 1;
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

    private SlideDefaults() {
    }
}
