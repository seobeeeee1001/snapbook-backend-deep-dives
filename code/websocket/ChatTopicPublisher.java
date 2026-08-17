package com.example.easybooking.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChatTopicPublisher {
    private final SimpMessagingTemplate messagingTemplate;

    public void publishToRoom(Long chatRoomId, Object payload) {
        messagingTemplate.convertAndSend("/topic/chat/" + chatRoomId, payload);
    }
}


