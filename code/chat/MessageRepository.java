package com.example.easybooking.chat.repository;

import com.example.easybooking.chat.domain.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message,Long> {
    @Query("SELECT COUNT(m) FROM Message m " +
            "WHERE m.chatRoomId = :chatRoomId " +
            "AND m.id > :lastReadMessageId " +
            "AND m.senderId <> :userId")
    int countUnreadMessages(
            @Param("chatRoomId") Long chatRoomId,
            @Param("lastReadMessageId") Long lastReadMessageId,
            @Param("userId") Long userId
    );

    @Query("SELECT m FROM Message m " +
            "WHERE m.chatRoomId = :chatRoomId " +
            "ORDER BY m.id DESC LIMIT :size")
    List<Message> findLatestMessages(
            @Param("chatRoomId") Long chatRoomId,
            @Param("size") int size);

    @Query("SELECT m FROM Message m " +
            "WHERE m.chatRoomId = :chatRoomId " +
            "AND m.id < :cursorId " +
            "ORDER BY m.id DESC LIMIT :size")
    List<Message> findMessagesBeforeCursor(
            @Param("chatRoomId") Long chatRoomId,
            @Param("cursorId") Long cursorId,
            @Param("size") int size);

    void deleteByChatRoomId(Long chatRoomId);
    void deleteBySenderId(Long senderId);
}
