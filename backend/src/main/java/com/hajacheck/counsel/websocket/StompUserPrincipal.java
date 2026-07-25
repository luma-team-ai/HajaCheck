package com.hajacheck.counsel.websocket;

import java.io.Serializable;
import java.security.Principal;

/**
 * STOMP 세션의 인증 주체 — 핸드셰이크에서 검증한 {@code userId}만 보유한다. {@code getName()} 은
 * {@code String.valueOf(userId)} 로, {@code convertAndSendToUser(userId, ...)} 의 사용자 목적지 매칭 키가 된다.
 */
public class StompUserPrincipal implements Principal, Serializable {

    private static final long serialVersionUID = 1L;

    private final Long userId;

    public StompUserPrincipal(Long userId) {
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }

    @Override
    public String getName() {
        return String.valueOf(userId);
    }
}
