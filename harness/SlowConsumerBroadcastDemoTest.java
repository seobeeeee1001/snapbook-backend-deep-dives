package com.example.easybooking;

import com.example.easybooking.auth.util.JwtUtil;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * 재현 하니스: "찔끔찔끔 읽는(trickling)" STOMP 구독자들이 공유 clientOutboundChannel
 * 스레드풀(기본 코어x2, 무한 큐)을 점유할 때, 무관한 방의 브로드캐스트가 얼마나 지연되는지
 * 타임라인으로 측정한다.
 *
 * 완전히 읽기를 멈춘 소비자는 sendBufferSizeLimit(기본 512KB) 보호로 즉시 끊기지만,
 * 조금씩 소비하는 클라이언트는 그 보호를 비껴가며 outbound 스레드를 계속 붙잡는다.
 *
 * 실제 애플리케이션 구성(WebSocketConfig + JwtChannelInterceptor + SimpleBroker)을 그대로 띄운다.
 * 기본 테스트 스위트에서 제외되며 `./gradlew wsDemoTest`로 실행한다.
 */
@Tag("wsdemo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SlowConsumerBroadcastDemoTest {

    private static final Pattern TS = Pattern.compile("ts:(\\d+);");
    private static final int SLOW_CLIENTS = 24;            // outbound 스레드(10코어 -> 20개)보다 많게
    private static final long SLOW_FRAME_DELAY_MS = 300;   // 프레임당 소비 지연 = trickling
    private static final String PAD = "y".repeat(32 * 1024);
    private static final long WINDOW_MS = 25_000;          // 측정 창

    @LocalServerPort int port;
    @Autowired JwtUtil jwtUtil;
    @Autowired SimpMessagingTemplate messagingTemplate;

    /** (발행 시점 오프셋 ms, 수신 지연 ms) */
    private final ConcurrentLinkedQueue<long[]> healthySamples = new ConcurrentLinkedQueue<>();
    private final AtomicInteger slowFramesReceived = new AtomicInteger();

    @Test
    void 찔끔읽는_구독자가_다른_방_브로드캐스트를_지연시키는지_측정한다() throws Exception {
        String url = "ws://localhost:" + port + "/ws-connect/websocket";
        String token = jwtUtil.generateAccessToken(1L, "OWNER");
        long t0 = System.nanoTime();

        // 1) 정상 클라이언트: /topic/chat/1 구독
        StompSession healthy = connect(url, token);
        healthy.subscribe("/topic/chat/1", new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return String.class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) {
                Matcher m = TS.matcher((String) payload);
                if (m.find()) {
                    long sentNano = Long.parseLong(m.group(1));
                    healthySamples.add(new long[]{
                            (sentNano - t0) / 1_000_000,
                            (System.nanoTime() - sentNano) / 1_000_000});
                }
            }
        });
        Thread.sleep(300);

        // 2) 기준선: 방해 없이 10회
        for (int i = 0; i < 10; i++) {
            messagingTemplate.convertAndSend("/topic/chat/1", "ts:" + System.nanoTime() + ";ping");
            Thread.sleep(100);
        }
        Thread.sleep(1000);
        List<long[]> baseline = new ArrayList<>(healthySamples);
        healthySamples.clear();

        // 3) trickling 구독자들: /topic/chat/2, 프레임당 300ms씩 소비
        List<StompSession> slowSessions = new ArrayList<>();
        for (int i = 0; i < SLOW_CLIENTS; i++) {
            StompSession slow = connect(url, token);
            slow.subscribe("/topic/chat/2", new StompFrameHandler() {
                @Override public Type getPayloadType(StompHeaders headers) { return String.class; }
                @Override public void handleFrame(StompHeaders headers, Object payload) {
                    slowFramesReceived.incrementAndGet();
                    try { Thread.sleep(SLOW_FRAME_DELAY_MS); } catch (InterruptedException ignored) { }
                }
            });
            slowSessions.add(slow);
        }
        Thread.sleep(500);

        // 4) 측정 창 내내: 슬로우 방에 32KB를 200ms마다 지속 발행 (백그라운드)
        AtomicBoolean publishing = new AtomicBoolean(true);
        Thread filler = new Thread(() -> {
            while (publishing.get()) {
                messagingTemplate.convertAndSend("/topic/chat/2", "ts:0;" + PAD);
                try { Thread.sleep(200); } catch (InterruptedException e) { return; }
            }
        }, "slow-room-filler");
        filler.start();

        // 5) 같은 창에서 정상 방 핑을 500ms마다
        int pings = (int) (WINDOW_MS / 500);
        for (int i = 0; i < pings; i++) {
            messagingTemplate.convertAndSend("/topic/chat/1", "ts:" + System.nanoTime() + ";ping");
            Thread.sleep(500);
        }
        Thread.sleep(10_000); // 늦게 도착하는 핑 수거
        publishing.set(false);
        filler.join(1000);

        // 6) 리포트
        System.out.println("[baseline] " + summarize(latencies(baseline)));
        List<long[]> degraded = new ArrayList<>(healthySamples);
        System.out.println("[degraded] " + summarize(latencies(degraded)));
        System.out.println("[timeline] offsetMs,latencyMs");
        degraded.stream()
                .sorted((a, b) -> Long.compare(a[0], b[0]))
                .forEach(s -> System.out.println("[timeline] " + s[0] + "," + s[1]));
        long alive = slowSessions.stream().filter(StompSession::isConnected).count();
        System.out.printf("[result] pings=%d arrived=%d lost=%d | slowFrames=%d slowAlive=%d/%d%n",
                pings, degraded.size(), pings - degraded.size(),
                slowFramesReceived.get(), alive, SLOW_CLIENTS);

        for (StompSession s : slowSessions) {
            try { s.disconnect(); } catch (Exception ignored) { }
        }
        healthy.disconnect();
    }

    private StompSession connect(String url, String token) throws Exception {
        // Tomcat WS "클라이언트" 컨테이너 기본 텍스트 버퍼는 8KB — 32KB 프레임을 받으면
        // 1009(too big)로 즉시 끊겨 백프레셔 실험 자체가 무효가 된다. 1MB로 상향.
        jakarta.websocket.WebSocketContainer container =
                jakarta.websocket.ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(1024 * 1024);
        container.setDefaultMaxBinaryMessageBufferSize(1024 * 1024);
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient(container));
        client.setMessageConverter(new StringMessageConverter());
        client.setDefaultHeartbeat(new long[]{0, 0});
        client.setInboundMessageSizeLimit(256 * 1024);
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);
        return client.connectAsync(url, new WebSocketHttpHeaders(), connectHeaders,
                new StompSessionHandlerAdapter() { }).get(10, TimeUnit.SECONDS);
    }

    private List<Long> latencies(List<long[]> samples) {
        return samples.stream().map(s -> s[1]).sorted().toList();
    }

    private String summarize(List<Long> sorted) {
        if (sorted.isEmpty()) {
            return "도착 0건";
        }
        return String.format("n=%d min=%dms p50=%dms p95=%dms max=%dms",
                sorted.size(), sorted.get(0), sorted.get(sorted.size() / 2),
                sorted.get(Math.max(0, (int) ((sorted.size() - 1) * 0.95))),
                sorted.get(sorted.size() - 1));
    }
}
