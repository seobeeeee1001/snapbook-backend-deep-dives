# 실제 반영한 변경

측정으로 원인을 확인한 뒤 실제로 반영한 프로덕션 코드·설정입니다. 변경 자체는 작고, **판단의 근거가 주석에 남아 있습니다.**

## 01 — 세션의 스레드 점유 시간에 상한 (`WebSocketConfig.java`)

```java
registry.setSendTimeLimit(1_000)          // 점유 창 10초 → 1초
        .setSendBufferSizeLimit(128 * 1024);
```

`configureWebSocketTransport`를 오버라이드하지 않으면 기본값(10초 / 512KB)이 적용됩니다. 문제는 값의 크기가 아니라 **그 비용을 공유 스레드풀이 낸다는 사실을 모른 채 기본값을 쓰고 있었다**는 것이었습니다.

## 02 — 종료 순서를 계약으로

| 파일 | 역할 |
|---|---|
| `WebSocketSessionRegistry.java` | 살아있는 세션 추적. 계획된 종료 때 한 번에 닫기 위한 목록 |
| `WebSocketShutdownDrain.java` | `SmartLifecycle(phase=MAX_VALUE)` — 종료 시 **가장 먼저** 전 세션에 `1012 "server-restart"` close 전송 |
| `application.yml.diff` | `server.shutdown: graceful`, `spring.lifecycle.timeout-per-shutdown-phase: 20s` |
| `dev-deploy.yml.diff` | `docker rm -f`(SIGKILL) → `docker stop --time 30`(SIGTERM + 유예) |

세션 추적은 `WebSocketConfig`의 `addDecoratorFactory`로 붙였습니다. 별도 이벤트 리스너를 만들지 않고 이미 있는 전송 설정 지점에 얹어, 설정과 추적이 한 곳에 모이게 했습니다.

### 종료 순서가 왜 중요한가

```
SIGTERM 수신
 └─ 1. 웹소켓 세션에 1012 전송      ← WebSocketShutdownDrain (phase MAX)
    2. 진행 중 HTTP 요청 드레인      ← server.shutdown=graceful
    3. 컨테이너 종료
```

`SmartLifecycle.stop()`은 phase가 큰 것부터 실행됩니다. 웹서버 graceful 단계보다 **먼저** 세션을 닫아야, 클라이언트가 "재시작"을 알고 질서 있게 재접속할 수 있습니다.

세 층 중 하나만 고치면 무효라는 점이 중요합니다 — **배포 스크립트가 SIGKILL을 보내면 위 코드는 실행 기회조차 없습니다.**

### 규격과 현실이 다른 지점

```java
/** RFC 6455 1012 Service Restart — 스프링 CloseStatus에 상수가 없어 직접 정의한다. */
static final CloseStatus SERVICE_RESTART = new CloseStatus(1012, "server-restart");
```

실측 결과 서버가 보낸 `1012`를 클라이언트는 `1002`로 보고했습니다(원 규격 유효 코드가 1011까지라 후속 등록 코드를 프로토콜 위반으로 재해석). **reason 문자열은 보존**되므로 신호를 code + reason으로 이중화했습니다.
