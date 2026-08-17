package com.example.easybooking.chat.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtChannelInterceptor jwtChannelInterceptor;
    private final WebSocketSessionRegistry sessionRegistry;
    @Override
    public void configureMessageBroker(org.springframework.messaging.simp.config.MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/pub");
    }

    @Override
    public void registerStompEndpoints(org.springframework.web.socket.config.annotation.StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-connect")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(jwtChannelInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        // 브로드캐스트는 공유 clientOutboundChannel 스레드풀에서 실행되므로,
        // 천천히 읽는 세션 하나가 스레드를 오래 붙잡으면 다른 모든 방의 전송이 그 뒤에 줄을 선다.
        // 기본값(sendTimeLimit 10초, 버퍼 512KB)은 그 점유 창을 너무 길게 허용한다.
        // 1초 안에 128KB를 못 받아가는 세션은 채팅 UX상 이미 죽은 연결로 보고 끊는다.
        // 끊어도 안전한 근거: 클라이언트는 재접속 시 목록 전체 재조회로 복구한다(#136).
        registry.setSendTimeLimit(1_000)
                .setSendBufferSizeLimit(128 * 1024)
                // 계획된 종료 때 1012를 보내기 위한 살아있는 세션 추적 (WebSocketShutdownDrain)
                .addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
                    @Override
                    public void afterConnectionEstablished(@NonNull WebSocketSession session) throws Exception {
                        sessionRegistry.add(session);
                        super.afterConnectionEstablished(session);
                    }

                    @Override
                    public void afterConnectionClosed(@NonNull WebSocketSession session,
                            @NonNull CloseStatus closeStatus) throws Exception {
                        sessionRegistry.remove(session);
                        super.afterConnectionClosed(session, closeStatus);
                    }
                });
    }
}
