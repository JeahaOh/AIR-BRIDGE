package airbridge.receiver.capture;

public final class CaptureDefaults {
    // 기본 카메라 디바이스 인덱스다.
    public static final int DEFAULT_DEVICE_INDEX = 0;
    // 캡처 요청 기본 해상도 너비다.
    public static final int DEFAULT_WIDTH = 1920;
    // 캡처 요청 기본 해상도 높이다.
    public static final int DEFAULT_HEIGHT = 1080;
    // 초당 캡처할 기본 프레임 수다.
    public static final double DEFAULT_FPS = 15.0d;
    // 제한 없이 캡처하려고 할 때 쓰는 기본 duration 값이다.
    public static final long DEFAULT_DURATION_SECONDS = 0L;
    // 제한 없이 payload를 받을 때 쓰는 기본 최대 수다.
    public static final int DEFAULT_MAX_PAYLOADS = 0;
    // 진행 로그를 찍는 기본 주기다.
    public static final long DEFAULT_STATUS_INTERVAL_MS = 10_000L;
    // 같은 신호가 유지될 때 자동 종료로 보는 기본 시간이다.
    public static final long DEFAULT_SAME_SIGNAL_SECONDS = 180L;

    // 원본 프레임을 분석 전에 적재하는 큐 크기다.
    public static final int RAW_QUEUE_CAPACITY = 64;
    // 저장 대상 프레임을 PNG writer 전에 적재하는 큐 크기다.
    public static final int SAVE_QUEUE_CAPACITY = 128;
    // 동시에 진행할 decode 작업 최대 수다.
    public static final int MAX_PENDING_DECODE = 32;
    // fingerprint 계산에 쓰는 기본 worker 수다.
    public static final int FINGERPRINT_WORKERS = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
    // PNG 저장을 병렬 처리하는 기본 worker 수다.
    public static final int SAVE_WORKERS = 2;
    // 동시에 진행할 저장 작업 최대 수다.
    public static final int MAX_PENDING_SAVE = 32;
    // 동시에 계산할 fingerprint 작업 최대 수다.
    public static final int MAX_PENDING_FINGERPRINT = 16;
    // 거의 검은 화면으로 볼 밝기 임계값이다.
    public static final int BLACK_FRAME_LUMA_THRESHOLD = 8;
    // 같은 화면으로 간주할 fingerprint 거리 임계값이다.
    public static final int SAME_SCREEN_DISTANCE_THRESHOLD = 10;
    // UI 프리뷰를 갱신하는 최소 간격이다.
    public static final long PREVIEW_FRAME_INTERVAL_MS = 66L;
    // UI 프리뷰 축소 시 허용할 최대 너비다.
    public static final int PREVIEW_MAX_WIDTH = 960;
    // UI 프리뷰 축소 시 허용할 최대 높이다.
    public static final int PREVIEW_MAX_HEIGHT = 540;

    private CaptureDefaults() {
    }
}
