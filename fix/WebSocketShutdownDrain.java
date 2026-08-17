package com.example.easybooking.chat.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

/**
 * 계획된 종료(SIGTERM) 때 웹소켓 세션을 질서 있게 닫는 단계.
 *
 * 종료 순서 계약:
 *   1. (이 컴포넌트, 가장 먼저) 모든 웹소켓 세션에 1012 SERVICE_RESTART close 전송
 *      → 클라이언트는 "재시작"임을 알고 지터를 둔 재접속 + 재조회로 복구한다.
 *   2. server.shutdown=graceful 이 진행 중 HTTP 요청을 드레인
 *   3. 컨테이너 종료
 *
 * SmartLifecycle의 stop은 phase가 큰 것부터 실행되므로, 웹서버 graceful 단계
 * (DEFAULT_PHASE - 1024)보다 먼저 실행되도록 최대 phase를 쓴다.
 * 전제: 배포 스크립트가 SIGKILL(docker rm -f)이 아니라 SIGTERM(docker stop)을 보내야
 * 이 경로가 실행된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketShutdownDrain implements SmartLifecycle {

    /** RFC 6455 1012 Service Restart — 스프링 CloseStatus에 상수가 없어 직접 정의한다. */
    static final CloseStatus SERVICE_RESTART = new CloseStatus(1012, "server-restart");

    private final WebSocketSessionRegistry sessionRegistry;
    private volatile boolean running = false;

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        int total = sessionRegistry.size();
        int closed = sessionRegistry.closeAll(SERVICE_RESTART); // 1012
        log.info("계획된 종료: 웹소켓 세션 {}개 중 {}개에 1012(SERVICE_RESTART) 전송", total, closed);
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE; // 종료 시 가장 먼저 실행
    }
}
