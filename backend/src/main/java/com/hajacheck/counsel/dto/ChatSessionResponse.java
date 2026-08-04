package com.hajacheck.counsel.dto;

import com.hajacheck.counsel.entity.ChatSession;
import com.hajacheck.counsel.entity.ChatSessionType;
import java.time.Instant;

/** 채팅 세션 생성 응답(#1467/HAJA-647). 소유자 userId 는 응답에 노출하지 않는다(요청자 본인 것이 자명). */
public record ChatSessionResponse(Long sessionId, ChatSessionType sessionType, Instant startedAt) {

    public static ChatSessionResponse from(ChatSession session) {
        return new ChatSessionResponse(session.getId(), session.getSessionType(), session.getStartedAt());
    }
}
