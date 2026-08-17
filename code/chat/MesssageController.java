package com.example.easybooking.chat.presentation;

import com.example.easybooking.auth.domain.AuthenticatedUser;
import com.example.easybooking.auth.annotation.RequireAuthenticatedUser;
import com.example.easybooking.chat.dto.response.MessageResponse;
import com.example.easybooking.chat.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/chat/rooms/{chatRoomId}/messages")
public class MesssageController {

    private final MessageService messageService;

    @GetMapping
    public ResponseEntity<List<MessageResponse>> getMessageHistory(
            @PathVariable Long chatRoomId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "50") int size,
            @RequireAuthenticatedUser AuthenticatedUser user) {
        return ResponseEntity.ok(
                messageService.getMessageHistory(
                        chatRoomId,
                        user.getUserId(),
                        cursor,
                        size
                )
        );
    }

}
