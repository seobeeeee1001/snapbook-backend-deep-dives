package com.example.easybooking.chat.service;

import com.example.easybooking.chat.ChatRoomReader;
import com.example.easybooking.chat.MessageWriter;
import com.example.easybooking.chat.domain.ChatRoom;
import com.example.easybooking.chat.dto.request.ChatMessageRequest;
import com.example.easybooking.chat.dto.response.MessageResponse;
import com.example.easybooking.errors.errorcode.AuthErrorCode;
import com.example.easybooking.errors.errorcode.ChatRoomErrorCode;
import com.example.easybooking.errors.exception.AuthException;
import com.example.easybooking.errors.exception.ChatRoomException;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatService {

    private final MessageWriter messageWriter;
    private final ChatRoomReader chatRoomReader;

    public MessageResponse saveMessage(Long chatRoomId, Long userId, ChatMessageRequest request) {
        ChatRoom chatRoom = chatRoomReader.read(chatRoomId);

        if (!chatRoom.isParticipant(userId)) {
            throw new ChatRoomException(ChatRoomErrorCode.CHAT_PARTICIPANT_REQUIRED);
        }

        return messageWriter.save(chatRoomId, userId, request);
    }

    public Long extractUserIdFromPrincipal(Principal principal) {
        if (principal == null) {
            throw new AuthException(AuthErrorCode.UNAUTHENTICATED_USER);
        }

        if (principal instanceof UsernamePasswordAuthenticationToken) {
            UsernamePasswordAuthenticationToken token =
                    (UsernamePasswordAuthenticationToken) principal;
            Object principalObj = token.getPrincipal();

            if (principalObj instanceof Long) {
                return (Long) principalObj;
            }
        }

        throw new AuthException(AuthErrorCode.INVALID_AUTH_PRINCIPAL);
    }
}
