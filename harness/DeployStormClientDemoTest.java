package com.example.easybooking;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.lang.NonNull;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * 배포 절단·재접속 폭풍 측정 하니스 (외부에서 docker rm -f / stop을 실행하는 동안 돌린다).
 *
 * - STOMP 클라이언트 N개: 접속 -> 구독 -> 연결이 끊기면 재접속 루프
 *   * DEMO_MODE=naive  : 즉시 + 1초 간격 재시도 (현재 FE 가정)
 *   * DEMO_MODE=jitter : 0~8초 무작위 지연 후 지수 백오프(상한 8초, ±25% 지터)
 * - raw WebSocket 관측 클라이언트 1개: 서버가 보내는 close code(1006 abnormal vs 1012 restart)를 기록
 * - 모든 사건을 "EVT|epochMs|clientId|event|detail" 형식으로 stdout에 남긴다. 집계는 밖에서 한다.
 *
 * 대상 앱은 이 테스트가 띄우지 않는다(도커 컨테이너). 기본 스위트에서 제외, `./gradlew deployStormTest`.
 */
@Tag("deploydemo")
class DeployStormClientDemoTest {

    private static final String BASE = env("DEMO_BASE", "http://localhost:8080");
    private static final String WS_URL = BASE.replaceFirst("^http", "ws") + "/ws-connect/websocket";
    private static final int CLIENTS = Integer.parseInt(env("DEMO_CLIENTS", "30"));
    private static final String MODE = env("DEMO_MODE", "naive");
    private static final long WINDOW_MS = Long.parseLong(env("DEMO_WINDOW_SEC", "75")) * 1000;
    // 측정 대상 컨테이너에 주입한 것과 같은 로컬 데모 값(운영 키가 아니다). env로 주입한다.
    private static final SecretKey KEY = Keys.hmacShaKeyFor(
            env("DEMO_JWT_SECRET", "local-demo-only-jwt-secret-min-32-bytes!!").getBytes());

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final AtomicInteger connected = new AtomicInteger();

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }

    private static void evt(Object clientId, String event, Object detail) {
        System.out.println("EVT|" + System.currentTimeMillis() + "|" + clientId + "|" + event + "|" + detail);
    }

    @Test
    void 배포_절단과_재접속_폭풍을_기록한다() throws Exception {
        long end = System.currentTimeMillis() + WINDOW_MS;
        evt("harness", "START", "mode=" + MODE + " clients=" + CLIENTS);

        // 종료 코드 관측자 (raw WebSocket + 수동 STOMP CONNECT 프레임)
        Thread observer = new Thread(() -> runCloseCodeObserver(end), "close-observer");
        observer.start();

        Thread[] threads = new Thread[CLIENTS];
        for (int i = 0; i < CLIENTS; i++) {
            final long userId = i + 1;
            threads[i] = new Thread(() -> runClient(userId, end), "client-" + userId);
            threads[i].start();
        }

        // 전원 최초 접속 완료 보고
        while (connected.get() < CLIENTS && System.currentTimeMillis() < end) {
            Thread.sleep(100);
        }
        evt("harness", "READY", "connected=" + connected.get());

        for (Thread t : threads) {
            t.join(Math.max(1, end - System.currentTimeMillis()) + 15_000);
        }
        observer.join(5_000);
        evt("harness", "DONE", "");
    }

    /** 클라이언트 1개의 수명: 접속 -> 끊기면 모드별 정책으로 재접속 -> 재접속 성공 시 전체 재조회. */
    private void runClient(long userId, long endMs) {
        String token = mintToken(userId);
        Random rnd = new Random(userId);
        boolean everConnected = false;
        long backoffMs = 1000;

        while (System.currentTimeMillis() < endMs) {
            CountDownLatch lost = new CountDownLatch(1);
            try {
                StompSession session = stompConnect(token, lost);
                session.subscribe("/topic/chat/" + userId, new StompFrameHandler() {
                    @Override public @NonNull Type getPayloadType(@NonNull StompHeaders headers) { return String.class; }
                    @Override public void handleFrame(@NonNull StompHeaders headers, Object payload) { }
                });
                if (!everConnected) {
                    everConnected = true;
                    connected.incrementAndGet();
                    evt(userId, "CONNECT_OK", "initial");
                } else {
                    evt(userId, "RECONNECT_OK", "");
                    refetch(userId, token);
                }
                backoffMs = 1000;
                lost.await(Math.max(1, endMs - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (System.currentTimeMillis() >= endMs) {
                    try { session.disconnect(); } catch (Exception ignored) { }
                    return;
                }
                evt(userId, "DISCONNECTED", "");
            } catch (Exception e) {
                evt(userId, "ATTEMPT_FAIL", e.getClass().getSimpleName());
            }

            // 재접속 대기 정책
            try {
                if ("jitter".equals(MODE)) {
                    long delay = everConnected
                            ? (long) (rnd.nextInt(8000))                    // 절단 직후 첫 재시도: 0~8초 분산
                            : 1000;
                    if (backoffMs > 1000) {                                  // 연속 실패 시 지수 백오프 ±25%
                        delay = (long) (backoffMs * (0.75 + rnd.nextDouble() * 0.5));
                    }
                    backoffMs = Math.min(backoffMs * 2, 8000);
                    Thread.sleep(delay);
                } else {
                    Thread.sleep(1000);                                      // naive: 1초 고정
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private StompSession stompConnect(String token, CountDownLatch lost) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new StringMessageConverter());
        client.setDefaultHeartbeat(new long[]{0, 0});
        StompHeaders h = new StompHeaders();
        h.add("Authorization", "Bearer " + token);
        return client.connectAsync(WS_URL, new WebSocketHttpHeaders(), h,
                new StompSessionHandlerAdapter() {
                    @Override public void handleTransportError(@NonNull StompSession s, @NonNull Throwable ex) {
                        lost.countDown();
                    }
                    @Override public void handleException(@NonNull StompSession s, StompCommand c,
                            @NonNull StompHeaders hd, byte[] p, @NonNull Throwable ex) {
                        lost.countDown();
                    }
                }).get(5, TimeUnit.SECONDS);
    }

    /** 재접속 직후의 "전체 재조회" — 방 목록 호출로 단순화. */
    private void refetch(long userId, String token) {
        long t0 = System.nanoTime();
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/chat/rooms/"))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            evt(userId, "REFETCH_" + (res.statusCode() == 200 ? "OK" : res.statusCode()),
                    (System.nanoTime() - t0) / 1_000_000 + "ms");
        } catch (Exception e) {
            evt(userId, "REFETCH_FAIL", e.getClass().getSimpleName());
        }
    }

    /** 서버가 보내는 close code를 그대로 기록하는 관측자. 끊기면 서버가 살아날 때까지 재접속. */
    private void runCloseCodeObserver(long endMs) {
        String token = mintToken(1L);
        while (System.currentTimeMillis() < endMs) {
            CountDownLatch closed = new CountDownLatch(1);
            try {
                StandardWebSocketClient raw = new StandardWebSocketClient();
                WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
                WebSocketSession ws = raw.execute(new TextWebSocketHandler() {
                    @Override public void afterConnectionEstablished(@NonNull WebSocketSession session) throws Exception {
                        session.sendMessage(new TextMessage(
                                "CONNECT\naccept-version:1.2\nAuthorization:Bearer " + token + "\n\n\0"));
                    }
                    @Override public void afterConnectionClosed(@NonNull WebSocketSession session,
                            @NonNull CloseStatus status) {
                        evt("observer", "CLOSE_CODE", status.getCode() + " reason=" + status.getReason());
                        closed.countDown();
                    }
                    @Override public void handleTransportError(@NonNull WebSocketSession session,
                            @NonNull Throwable ex) {
                        evt("observer", "TRANSPORT_ERROR", ex.getClass().getSimpleName());
                    }
                }, headers, URI.create(WS_URL)).get(5, TimeUnit.SECONDS);
                evt("observer", "OBSERVING", ws.getId());
                closed.await(Math.max(1, endMs - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (closed.getCount() == 0) {
                    Thread.sleep(1000);
                }
            } catch (Exception e) {
                try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private String mintToken(long userId) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("role", "CUSTOMER")
                .claim("type", "access")
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + 7_200_000))
                .signWith(KEY)
                .compact();
    }
}
