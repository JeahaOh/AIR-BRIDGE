package airbridge.receiver;

final class ReceiverDefaults {
    // decode 작업에서 병렬로 PNG를 읽을 기본 worker 수다.
    static final int DEFAULT_DECODE_WORKERS = 4;

    private ReceiverDefaults() {
    }
}
