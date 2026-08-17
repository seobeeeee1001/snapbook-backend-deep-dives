package com.example.easybooking.chat.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * 살아있는 웹소켓 세션 목록.
 *
 * 목적은 하나다: 계획된 종료(배포) 때 세션을 1006(비정상 절단)이 아니라
 * 1012(SERVICE_RESTART)로 닫아, 클라이언트가 "장애"가 아닌 "재시작"임을 알고
 * 지터를 둔 재접속을 하게 만드는 것. ({@link WebSocketShutdownDrain})
 */
@Slf4j
@Component
public class WebSocketSessionRegistry {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public void add(WebSocketSession session) {
        sessions.put(session.getId(), session);
    }

    public void remove(WebSocketSession session) {
        sessions.remove(session.getId());
    }

    public int size() {
        return sessions.size();
    }

    /** 모든 세션에 지정한 close code를 보낸다. 실패해도 다음 세션으로 진행한다. */
    public int closeAll(CloseStatus status) {
        int closed = 0;
        for (WebSocketSession session : sessions.values()) {
            try {
                if (session.isOpen()) {
                    session.close(status);
                    closed++;
                }
            } catch (Exception e) {
                log.warn("세션 종료 실패 sessionId={} code={}", session.getId(), status.getCode(), e);
            }
        }
        return closed;
    }
}
