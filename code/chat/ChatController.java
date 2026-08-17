package com.example.easybooking.chat.presentation;

import com.example.easybooking.chat.service.ChatService;
import com.example.easybooking.chat.dto.request.ChatMessageRequest;
import com.example.easybooking.chat.dto.response.MessageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @MessageMapping("/chat/{chatRoomId}")
    @SendTo("/topic/chat/{chatRoomId}")
    public MessageResponse sendMessage(
            @DestinationVariable Long chatRoomId,
            @Payload @Valid ChatMessageRequest request,
            Principal principal) {
        Long userId = chatService.extractUserIdFromPrincipal(principal);
        return chatService.saveMessage(chatRoomId, userId, request);
    }


}
