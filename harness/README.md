# 재현·측정 하니스

두 사례를 **실제 애플리케이션으로** 재현하고 측정하는 코드입니다. 목업이나 단위 테스트가 아닙니다.

두 하니스 모두 JUnit 태그로 기본 테스트 스위트에서 분리되어 있습니다. 측정용 코드가 CI를 느리게 만들면 안 되기 때문입니다.

```gradle
tasks.named('test') {
    useJUnitPlatform { excludeTags 'wsdemo', 'deploydemo' }   // 평소 테스트는 제외
}
tasks.register('wsDemoTest', Test) {
    useJUnitPlatform { includeTags 'wsdemo' }
    testLogging { showStandardStreams = true }
}
tasks.register('deployStormTest', Test) {
    useJUnitPlatform { includeTags 'deploydemo' }
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}
```

## 01 · `SlowConsumerBroadcastDemoTest.java`

느린 구독자가 **다른 방**의 브로드캐스트를 지연시키는지 측정합니다.

```bash
./gradlew wsDemoTest
```

- `@SpringBootTest(RANDOM_PORT)` — 실제 WebSocket 설정·인증 인터셉터·브로커가 모두 살아있는 서버를 띄웁니다.
- 정상 클라이언트 1명(`/topic/chat/1`)의 수신 지연을 payload에 심은 발행 시각과 비교해 타임라인으로 기록합니다.
- 느린 클라이언트 24명(`/topic/chat/2`)은 프레임 핸들러에서 `Thread.sleep(300)`으로 **찔끔찔끔 읽기**를 흉내 냅니다. 24개인 이유는 배달 스레드가 코어×2(=20)라 그보다 많아야 전부 점유되기 때문입니다.

**설계에서 중요했던 점** — 초기 실험이 무증상이었는데, 원인은 서버가 아니라 테스트 클라이언트 쪽 버퍼(8KB 기본값)가 32KB 프레임에 먼저 연결을 끊은 것이었습니다. 그래서:

```java
// 이 설정이 없으면 클라이언트가 먼저 죽어 실험 자체가 성립하지 않는다
container.setDefaultMaxTextMessageBufferSize(1024 * 1024);
```

그리고 `slowFrames`, `slowAlive` 같은 **자기 검증 지표**를 출력합니다. "아무것도 안 잡혔다"가 *문제가 없어서*인지 *실험이 깨져서*인지 구분하기 위해서입니다.

## 02 · `DeployStormClientDemoTest.java` + `prober.py`

배포 절단과 재접속 폭풍을 측정합니다. 컨테이너는 이 테스트가 띄우지 않고, **밖에서 실제 배포 명령으로** 죽입니다.

```bash
# 1) 앱 컨테이너를 실제 배포 스크립트와 같은 방식으로 실행
docker run -d --name deploy-demo-app -p 8080:8080 --env-file .env <image>

# 2) HTTP 가용성 프로버 (50ms 간격)
python3 prober.py probe.log 100 &

# 3) 클라이언트 하니스 — 정책을 골라서 실행
DEMO_MODE=naive  ./gradlew deployStormTest    # 개선 전: 1초 고정 재시도
DEMO_MODE=jitter ./gradlew deployStormTest    # 개선 후: 0~8초 지터 + 지수 백오프

# 4) READY 로그를 본 뒤, 측정 중에 실제 배포를 실행
docker rm -f deploy-demo-app                  # 개선 전 (SIGKILL)
docker stop --time 30 deploy-demo-app         # 개선 후 (SIGTERM + 유예)
docker run -d --name deploy-demo-app ...
```

기록되는 것:

| 이벤트 | 의미 |
|---|---|
| `DISCONNECTED` | 절단 시점 (전원이 동시에 끊기는지 확인) |
| `CLOSE_CODE` | 서버가 보낸 종료 코드 — `1006`(비정상) vs `1012`/reason(계획된 재시작) |
| `ATTEMPT_FAIL` | 다운타임 중 실패한 재접속 시도 = **재시도 폭풍의 크기** |
| `RECONNECT_OK` | 재접속 성공 시점 — 초 단위 히스토그램으로 **피크 동시성** 계산 |
| `REFETCH_OK` | 재접속 직후 전체 재조회의 응답 시간 (콜드 JVM에 걸리는 부하) |

모든 이벤트는 `EVT|epochMs|clientId|event|detail` 한 줄로 stdout에 남깁니다. 집계는 하니스 밖에서 하므로, 원본 로그만 있으면 다르게 다시 집계할 수 있습니다.

**관측자 클라이언트를 따로 둔 이유** — STOMP 클라이언트 추상화는 close code를 그대로 노출하지 않습니다. 그래서 raw WebSocket 세션 하나를 별도로 붙여 서버가 실제로 보낸 코드와 reason을 기록합니다. 이 관측자 덕분에 "**서버는 1012를 보냈는데 클라이언트는 1002로 받는다**"는 사실을 발견했습니다.

## 주의

- 하니스에 들어 있는 JWT 값은 로컬 데모 전용이며 `DEMO_JWT_SECRET`으로 주입합니다. 운영 키가 아닙니다.
- 로컬 루프백은 실제 모바일 네트워크보다 버퍼가 관대합니다. 측정값은 **하한**으로 읽어야 합니다.
