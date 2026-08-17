package com.example.easybooking.chat;

import com.example.easybooking.chat.domain.Message;
import com.example.easybooking.chat.dto.request.ChatMessageRequest;
import com.example.easybooking.chat.dto.response.MessageResponse;
import com.example.easybooking.chat.repository.ChatRoomRepository;
import com.example.easybooking.chat.repository.MessageRepository;
import com.example.easybooking.errors.errorcode.ChatErrorCode;
import com.example.easybooking.errors.exception.ChatException;
import com.example.easybooking.user.UserReader;
import com.example.easybooking.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class MessageWriter {
    private final MessageRepository messageRepository;
    private final UserReader userReader;
    private final ChatRoomRepository chatRoomRepository;

    @Transactional
    public MessageResponse save(Long chatRoomId, Long userId, ChatMessageRequest request) {
        User user = userReader.read(userId);
        Message message;

        if (!request.hasImage() && !request.hasText()) {
            throw new ChatException(ChatErrorCode.MESSAGE_CONTENT_INVALID);
        } else if (!request.hasImage()) {
            message = Message.create(
                    chatRoomId,
                    userId,
                    request.getMessage()
            );
        } else if (request.hasText()) {
            message = Message.createImageWithTextMessage(
                    chatRoomId,
                    userId,
                    request.getMessage(),
                    request.getImageUrl()
            );
        } else {
            message = Message.createImageMessgae(
                    chatRoomId,
                    userId,
                    request.getImageUrl()
            );
        }

        messageRepository.save(message);
        // 비관적 락(SELECT ... FOR UPDATE) 없이 방 미리보기 포인터만 단조 조건부 UPDATE로 전진시킨다.
        chatRoomRepository.advanceLastMessage(chatRoomId, message.getId(), message.getSentAt());
        MessageResponse response = MessageResponse.from(message, user.getName());
        return response;

    }
}
