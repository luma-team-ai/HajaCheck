package com.hajacheck.counsel.websocket;

import com.hajacheck.auth.entity.Role;
import java.io.Serializable;
import java.security.Principal;

/**
 * STOMP 세션의 인증 주체 — 핸드셰이크에서 검증한 {@code userId}·{@code role}을 보유한다. {@code getName()} 은
 * {@code String.valueOf(userId)} 로, {@code convertAndSendToUser(userId, ...)} 의 사용자 목적지 매칭 키가 된다.
 * role은 상담원 전용 토픽({@code /topic/counsel-queue}) SUBSCRIBE 인가에 쓰인다(#1001 후속).
 */
public class StompUserPrincipal implements Principal, Serializable {

    private static final long serialVersionUID = 1L;

    private final Long userId;
    private final Role role;

    public StompUserPrincipal(Long userId, Role role) {
        this.userId = userId;
        this.role = role;
    }

    public Long getUserId() {
        return userId;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public String getName() {
        return String.valueOf(userId);
    }
}
