package com.hajacheck.counsel.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.counsel.dto.ChatSessionCreateRequest;
import com.hajacheck.counsel.dto.ChatSessionMessageResponse;
import com.hajacheck.counsel.dto.ChatSessionResponse;
import com.hajacheck.counsel.service.ChatSessionService;
import com.hajacheck.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 채팅 세션 API(#1467/HAJA-647) — RAG 챗봇 대화 맥락 유지용 세션 생성·이력 조회.
 *
 * <p>SecurityConfig 의 {@code anyRequest().authenticated()} 로 인증은 보장되지만 "인증됨 ≠ 인가됨"이므로,
 * 세션 소유자 검증은 {@link ChatSessionService} 가 담당한다(타인 세션 접근 시 403).
 */
@Tag(name = "Chat Session", description = "채팅 세션 API")
@RestController
@RequestMapping("/api/chat-sessions")
@RequiredArgsConstructor
public class ChatSessionController {

    private final ChatSessionService chatSessionService;

    @Operation(summary = "채팅 세션 생성",
            description = "로그인 사용자 소유의 채팅 세션을 생성한다(RAG 챗봇은 sessionType=RAG). "
                    + "소유자는 인증 컨텍스트에서만 취득하며 요청 바디로 받지 않는다.")
    @PostMapping
    public ResponseEntity<ApiResponse<ChatSessionResponse>> createSession(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody ChatSessionCreateRequest request) {
        ChatSessionResponse response =
                chatSessionService.createSession(loginUser.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @Operation(summary = "채팅 세션 이력 조회",
            description = "세션의 메시지를 생성순으로 반환한다(답변 근거 citations 포함). "
                    + "세션 소유자만 조회할 수 있으며, 타인 세션·미존재 세션은 동일하게 403.")
    @GetMapping("/{sessionId}/messages")
    public ResponseEntity<ApiResponse<List<ChatSessionMessageResponse>>> findMessages(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long sessionId) {
        return ResponseEntity.ok(
                ApiResponse.ok(chatSessionService.findMessages(loginUser.getUserId(), sessionId)));
    }
}
