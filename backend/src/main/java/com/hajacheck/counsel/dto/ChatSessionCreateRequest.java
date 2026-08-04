package com.hajacheck.counsel.dto;

import com.hajacheck.counsel.entity.ChatSessionType;
import jakarta.validation.constraints.NotNull;

/**
 * 채팅 세션 생성 요청(#1467/HAJA-647, {@code POST /api/chat-sessions}).
 *
 * <p>소유자({@code userId})는 요청 바디에서 받지 않고 {@code @AuthenticationPrincipal} 에서만 취득한다
 * — 바디로 받으면 임의 사용자 명의 세션 생성이 가능해진다.
 */
public record ChatSessionCreateRequest(@NotNull ChatSessionType sessionType) {
}
