package airbridge.sender;

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.List;

final class SenderDefaults {
    // 한 QR에 넣을 원본 데이터 청크(= fountain 심볼) 크기다.
    static final int DEFAULT_CHUNK_DATA_SIZE = 2000;
    // fountain 복구 심볼 여유분 비율이다. k개 소스 심볼에 더해 ceil(k*비율)개의 복구 심볼을
    // 추가로 내보내, 단방향 채널에서 일부 프레임을 놓쳐도 복원되도록 한다.
    static final double DEFAULT_REPAIR_OVERHEAD = 0.5;
    // encode 병렬 처리에 사용할 기본 워커 수다(파일·청크 QR 생성 동시 실행).
    static final int DEFAULT_ENCODE_WORKERS = Math.max(1, Runtime.getRuntime().availableProcessors());
    // 생성할 QR PNG 한 변의 기본 픽셀 크기다.
    static final int DEFAULT_QR_IMAGE_SIZE = 1200;
    // QR 생성 시 사용할 기본 오류 정정 레벨이다.
    static final ErrorCorrectionLevel DEFAULT_QR_ERROR_LEVEL = ErrorCorrectionLevel.M;
    // QR 아래 파일명/청크 정보 라벨 영역 높이다.
    static final int DEFAULT_LABEL_HEIGHT = 80;
    // 출력 폴더를 원본 디렉터리 구조대로 나눌지 여부다.
    static final boolean DEFAULT_FOLDER_STRUCTURE = true;
    // 단일 출력 폴더에 담을 최대 파일 수다.
    static final int DEFAULT_FILES_PER_FOLDER = 500;
    // encode 대상에 기본 포함할 확장자 목록이다.
    static final List<String> DEFAULT_TARGET_EXTENSIONS = List.of(
            ".java", ".jsp", ".xml", ".js", ".html", ".htm", ".css",
            ".properties", ".sql", ".txt", ".json", ".yml", ".yaml",
            ".sh", ".bat", ".py", ".md", ".cfg", ".ini", ".log",
            ".ts", ".tsx", ".jsx", ".vue", ".scss", ".less",
            ".csv", ".xls", ".xlsx", ".doc", ".docx", ".ppt", ".pptx",
            ".zip", ".pdf"
    );
    // source 수집 시 기본으로 건너뛸 디렉터리 목록이다.
    static final List<String> DEFAULT_SKIP_DIRS = List.of(
            "node_modules", "__pycache__", "target", "build", "dist",
            ".git", ".svn", ".idea", ".settings", "bin", "out"
    );

    private SenderDefaults() {
    }
}
