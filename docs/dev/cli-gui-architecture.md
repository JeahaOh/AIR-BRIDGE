# cli / gui architecture

CLI와 GUI가 같은 기능을 공유해야 할 때의 기본 구조 초안입니다.

목표는 `slide`처럼 UI와 상태 전이가 한 클래스에 강하게 묶이는 구조를 피하고, 같은 코어 로직을 `Picocli`와 `Swing` 양쪽에서 재사용하는 것입니다.

## 범위

- 공통 비즈니스 로직 분리
- CLI 어댑터와 GUI 어댑터의 역할 구분
- 진행률, 로그, 결과 전달 방식
- 점진적 리팩터링 순서

## 배경

현재 `slide`는 실제로 동작하는 Swing 앱이지만, `SlideApp` 내부에 아래가 함께 들어 있습니다.

- 화면 구성
- 사용자 입력 수집
- 재생 상태 보관
- 타이머 전이
- 이미지 로딩 제어
- 운영용 포커스/전체화면 정책

이 방식은 작은 기능에서는 빠르지만, 같은 기능을 CLI와 GUI에서 같이 써야 할 때는 재사용성이 떨어집니다.

문제는 다음과 같습니다.

- 핵심 로직 테스트가 UI 수명주기와 섞인다
- CLI와 GUI 동작이 쉽게 벌어진다
- 다른 UI를 붙일 때 `SlideApp` 내부 상태를 다시 건드려야 한다
- 장기적으로 클래스가 비대해진다

## 목표 구조

권장 구조는 `core + adapters`입니다.

- `core`
  - 순수한 입력 검증
  - 상태 전이
  - 작업 실행
  - 결과 생성
- `cli`
  - 인자 파싱
  - stdout/stderr 출력
  - 종료 코드 매핑
- `gui`
  - 폼 입력 수집
  - 버튼/이벤트 처리
  - 화면 갱신

핵심 원칙:

- CLI는 GUI를 호출하지 않는다
- GUI는 CLI를 호출하지 않는다
- 둘 다 같은 service를 호출한다
- service는 Swing이나 Picocli 타입을 몰라야 한다

## 제안 패키지

예시는 아래처럼 잡는 편이 무난합니다.

- `airbridge.<domain>.core`
- `airbridge.<domain>.cli`
- `airbridge.<domain>.gui`

코어 쪽 기본 타입:

- `Request`
- `Result`
- `ProgressEvent`
- `ProgressListener`
- `Service`

예:

- `TransferRequest`
- `TransferResult`
- `TransferProgressEvent`
- `TransferService`

## core 책임

`core`는 실제 기능의 기준 구현이어야 합니다.

포함할 것:

- 옵션 정규화와 기본값 보정
- 파일/입력 경로 검증
- 작업 순서 제어
- 중간 진행률 이벤트 발행
- 성공/실패 결과 생성

넣지 말아야 할 것:

- `JFrame`, `JButton`, `JLabel`
- `CommandLine`, `ParseResult`
- `System.exit(...)`
- 직접적인 `stdout` 출력

즉 코어는 "무슨 일을 어떤 순서로 할지"만 알고, "어떻게 보여줄지"는 몰라야 합니다.

## 서비스 인터페이스

최소 형태는 아래 정도면 충분합니다.

```java
public interface TransferService {
    TransferResult run(TransferRequest request, ProgressListener listener) throws Exception;
}
```

진행률 전달은 문자열 로그보다 이벤트 객체가 낫습니다.

이유:

- CLI는 이벤트를 텍스트로 바꿔 출력할 수 있다
- GUI는 같은 이벤트를 상태 라벨, 진행 바, 로그 패널에 반영할 수 있다
- 테스트에서는 이벤트 목록만 검증하면 된다

예:

```java
public record TransferProgressEvent(
        String stage,
        String message,
        int current,
        int total
) {}
```

## CLI 책임

CLI는 얇아야 합니다.

역할:

- Picocli로 옵션 파싱
- `Request` 생성
- `Service` 호출
- 진행률 이벤트를 콘솔 출력으로 변환
- 예외를 사용자용 에러 메시지와 종료 코드로 변환

즉 CLI 커맨드는 orchestration adapter에 가깝고, 실제 업무 로직을 가지면 안 됩니다.

예:

1. args 파싱
2. `Request` 생성
3. `service.run(...)`
4. 결과 출력
5. 종료 코드 반환

## GUI 책임

GUI도 마찬가지로 얇아야 합니다.

역할:

- 입력 필드와 체크박스 값을 읽어 `Request` 생성
- 백그라운드 스레드에서 `Service` 실행
- `ProgressEvent`를 받아 화면 갱신
- 취소 버튼, 재시도 버튼, 파일 선택창 같은 UI 행동 처리

중요한 점:

- 코어 작업은 EDT에서 직접 돌리지 않는다
- Swing 갱신만 EDT로 보낸다
- 코어는 UI 스레드 존재를 전혀 가정하지 않는다

## 권장 실행 모델

진입점은 하나여도 되지만, 모드는 명확히 나누는 편이 좋습니다.

예:

- `app send ...`
- `app receive ...`
- `app gui`

또는 인자가 없을 때 GUI를 띄우고, 서브커맨드가 있으면 CLI로 가는 방식도 가능은 합니다.

다만 운영과 자동화 관점에서는 명시적 모드가 더 안전합니다.

권장:

- CLI 실행은 항상 서브커맨드 기반
- GUI 실행은 `gui` 같은 명시적 엔트리 제공

## 진행률과 취소

CLI와 GUI를 함께 지원하려면 진행률과 취소를 처음부터 코어 계약에 넣는 것이 좋습니다.

최소 권장 항목:

- 현재 단계 이름
- 현재 진행 수치
- 총량
- 사용자용 메시지
- 취소 요청 여부

취소는 아래 두 방식 중 하나가 무난합니다.

- `CancellationToken`을 `Request` 또는 별도 인자로 전달
- 인터럽트 가능 작업이면 스레드 인터럽트와 함께 사용

중요한 점은, GUI 취소 버튼이 코어에 전달되는 경로가 명확해야 한다는 것입니다.

## 테스트 경계

이 구조의 장점은 테스트 경계가 분명해진다는 점입니다.

코어 테스트:

- 입력 검증
- 상태 전이
- 파일 처리 결과
- 진행률 이벤트 순서
- 오류 분류

CLI 테스트:

- 옵션 파싱
- 종료 코드
- 메시지 포맷

GUI 테스트:

- 이벤트 연결
- 버튼 enable/disable
- 필드값 반영

우선순위는 코어 테스트가 가장 높고, GUI 테스트는 최소화하는 편이 효율적입니다.

## slide에 대한 적용 방향

`slide`를 그대로 복제해서 새 GUI를 만드는 것은 추천하지 않습니다.

대신 아래 순서가 낫습니다.

1. `SlideApp`에서 UI 독립적인 상태 전이와 재생 제어를 식별한다
2. 이를 `SlideService` 또는 `SlideController` 성격의 코어 클래스로 분리한다
3. Swing은 그 코어를 구독하고 조작하는 thin adapter로 남긴다
4. 필요하면 같은 코어를 CLI에서도 호출한다

다만 `slide`는 화면 표시, 전체화면, 포커스 복구, 타이머 운영이 제품 성격과 강하게 연결돼 있어서 완전한 순수 코어로 쪼개기 어려운 부분도 있습니다.

그래서 현실적인 분리 기준은 아래가 됩니다.

코어로 빼기 쉬운 것:

- 이미지 목록 수집
- 정렬 규칙
- 현재 인덱스 계산
- loop 계산
- black frame 삽입 규칙
- preload/prefetch 스케줄 결정

UI 쪽에 남기기 쉬운 것:

- Swing 레이아웃
- 단축키 바인딩
- 파일 선택 다이얼로그
- 전체화면 전환
- always-on-top
- foreground recovery

## 단계적 리팩터링 순서

처음부터 구조를 크게 뒤집기보다 아래 순서가 안전합니다.

1. `Request`, `Result`, `ProgressEvent` 타입을 먼저 정의
2. 기존 CLI 로직에서 순수 계산/검증 부분을 service로 이동
3. CLI는 새 service를 호출하도록 얇게 정리
4. 이후 GUI를 붙여 같은 service를 재사용
5. 마지막에 UI 전용 상태와 코어 상태를 더 명확히 분리

이 순서가 좋은 이유:

- 먼저 CLI 회귀를 막을 수 있다
- GUI가 나중에 와도 코어 계약이 이미 안정화된다
- 중간 단계에서도 항상 실행 가능한 상태를 유지할 수 있다

## 피해야 할 구조

아래는 다시 강결합으로 돌아가는 패턴입니다.

- service가 `JTextArea`에 직접 append
- service가 Picocli annotation 타입을 직접 참조
- GUI 버튼 핸들러 안에 실제 파일 처리 로직 구현
- CLI와 GUI가 각자 별도 검증 로직 보유
- `main(...)` 안에 모드별 업무 로직이 계속 누적

이 중 하나라도 생기기 시작하면 재사용보다 복제가 빨라지고, 이후 유지보수 비용이 커집니다.

## 한 줄 정리

CLI와 GUI를 같이 쓰려면 `Swing으로 감싼 CLI`가 아니라 `같은 core service를 호출하는 두 개의 adapter`로 보는 것이 맞습니다.
