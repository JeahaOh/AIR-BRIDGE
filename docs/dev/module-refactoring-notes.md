# 구조 리팩터링 및 모듈 분리 검토 보고

이 문서는 `libs/common` 내의 전송 코어(LtFountain)와 광학 캐리어(QR) 간의 정식 모듈 분리에 대한 후속 검토 보고서입니다.

---

## 1. 아키텍처 의존성 설계 분석

현재 `libs/common` 내부의 패키지들은 기능적으로 다음과 같이 자립적인 구조를 띠고 있습니다.

1.  **전송 프로토콜 코어 (Transfer Core)**
    *   **패키지**: `airbridge.common.fountain` (`LtDecoder`, `LtFountain`, `LtPeelTracker`), `airbridge.common` (`CodecSupport`, `RelativePathSupport`, `VersionSupport`)
    *   **의존성**: 오직 Java 표준 SDK 라이브러리(`java.util.*`, `java.io.*` 등)만을 의존하여 구현되어 있습니다. 외부의 ZXing 라이브러리나 OS 그래픽 라이브러리(Swing/AWT)의 개입을 원천 배제하여, 타 캐리어 플랫폼으로 이식이 완벽하게 용이합니다.
2.  **광학 캐리어 (Carrier QR)**
    *   **패키지**: `airbridge.common.qr` (`QrImageDecoder`), `airbridge.common` (`QrPayloadSupport`)
    *   **의존성**: 전송 프로토콜 코어 정보(`QrPayloadSupport`가 Fountain ESI 구조를 매핑)와 외부 바코드 라이브러리(`com.google.zxing:core`)를 활용해 프레임을 이미지로 렌더링하거나 디코딩합니다.

즉, **`Carrier QR -> Transfer Core` 의 단방향 의존성 흐름**이 논리적으로 완벽하게 성립하고 있습니다.

---

## 2. 물리적 모듈 분리(Gradle Subproject) 검토

기존 TODO의 구상대로 `libs/common`을 `libs/transfer-core`와 `libs/carrier-qr` 두 개의 Gradle 서브프로젝트로 강제 분할하는 방안에 대해, 다음과 같이 장단점을 대조 검토했습니다.

### 장점 (Pros)
*   **완전한 컴파일타임 격리**: `transfer-core`가 ZXing 라이브러리에 오염되는 것을 빌드 시스템 레벨에서 원천 방어합니다.
*   **멀티 캐리어 확장성**: 향후 4색 컬러 심볼, 혹은 광학이 아닌 소리(Audio)나 무선 캐리어를 추가 도입할 때 `transfer-core` 빌드를 수정 없이 그대로 복사/참조하여 재사용할 수 있습니다.

### 단점 (Cons)
*   **빌드 오버헤드 증가**: Gradle 서브프로젝트가 6개에서 7~8개로 늘어남에 따라, 빌드 환경 구성 및 의존성 분석 캐싱 비용이 늘어 컴파일 속도가 둔화됩니다.
*   **소수의 프레이밍 클래스의 이동 모호성**: `QrPayloadSupport`와 같이 Fountain 페이로드 메타데이터 규격을 직접 담는 프레임 빌더는 QR 규격과 Fountain 규격을 양쪽 다 포함하므로 물리적 분리 시 경계선 상의 순환 참조 리스크가 발생합니다.
*   **유지보수 복잡도 증가**: 현재 모듈 규모(약 10여 개의 클래스 수준)에서 물리적으로 분할하는 것은 불필요하게 보일러플레이트 파일(`build.gradle`)을 늘려 개발자의 유지보수 피로도를 유발합니다.

---

## 3. 리팩터링 최종 검토 결론

> [!IMPORTANT]
> **구조 규칙(AGENTS.md) 준수 결론**
> `AGENTS.md`의 아키텍처 제약 조건인 *"기존 모듈 분리가 이미 충분하다면 가상의 제네릭 레이어를 도입하는 것을 피하고, 무관한 모듈을 리라이트하지 말 것"*에 근거하여, **물리적인 Gradle 모듈 분할은 보류하고 현행 논리적 패키지 격리 방식을 고수**하기로 결정했습니다.

*   대신, `libs/common` 내부 패키지의 단방향 격리가 영구히 깨지지 않도록 주기적인 컴파일 확인 및 정적 분석(ArchUnit 등)을 통해 구조적 무결성을 지키는 편이 실리적입니다.
*   향후 실제로 다른 캐리어가 물리적으로 개발되어 메인 코드에 유입되는 시점에 맞춰, `libs/common/fountain` 및 관련 코덱 헬퍼들을 `libs/transfer-core` 로 격리 추출하는 작업을 2단계로 착수하는 로드맵이 가장 안전합니다.
