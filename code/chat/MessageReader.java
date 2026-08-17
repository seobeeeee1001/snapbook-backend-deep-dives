package com.example.easybooking.chat;

import java.util.List;

import org.springframework.stereotype.Component;

import com.example.easybooking.chat.domain.ChatRoom;
import com.example.easybooking.chat.domain.Message;
import com.example.easybooking.chat.repository.ChatRoomRepository;
import com.example.easybooking.chat.repository.MessageRepository;
import com.example.easybooking.errors.errorcode.ChatErrorCode;
import com.example.easybooking.errors.exception.ChatException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MessageReader {
    private final MessageRepository messageRepository;
    private final ChatRoomRepository chatRoomRepository;

    public Message read(Long messageId) {
        return messageRepository.findById(messageId)
                .orElseThrow(() -> new ChatException(ChatErrorCode.MESSAGE_NOT_FOUND));
    }

    public List<Message> readAllByIds(List<Long> messageIds) {
        return messageRepository.findAllById(messageIds);
    }

    public List<Message> readMessages(ChatRoom chatRoom, Long cursorId, int size, Long userId) {
        List<Message> messages;
        if (cursorId == null) {
            messages = messageRepository.findLatestMessages(chatRoom.getId(), size);
            if (!messages.isEmpty()) {
                advanceLastReadMessageId(chatRoom, messages.get(0).getId(), userId);
            }
        } else {
            messages = messageRepository.findMessagesBeforeCursor(chatRoom.getId(), cursorId, size);
        }
        return messages;
    }

    private void advanceLastReadMessageId(ChatRoom chatRoom, Long messageId, Long userId) {
        if (chatRoom.getOwnerId().equals(userId)) {
            chatRoomRepository.advanceOwnerLastReadMessageId(chatRoom.getId(), userId, messageId);
        } else if (chatRoom.getCustomerId().equals(userId)) {
            chatRoomRepository.advanceCustomerLastReadMessageId(chatRoom.getId(), userId, messageId);
        }
    }


    public int countUnreadMessages(Long chatRoomId, Long lastReadMessageId, Long userId) {
        return messageRepository.countUnreadMessages(chatRoomId, lastReadMessageId, userId);
    }
}
