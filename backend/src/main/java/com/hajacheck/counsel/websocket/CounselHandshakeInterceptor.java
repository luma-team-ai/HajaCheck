package com.hajacheck.counsel.websocket;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * WebSocket 인증 1단계 — 핸드셰이크(HTTP 업그레이드) 시점에 세션 쿠키를 검증한다. {@code SESSION} 쿠키
 * (Base64 인코딩된 Spring Session ID)를 디코드해 Spring Session 저장소에서 인증 주체를 확인하고, 미인증이면
 * 핸드셰이크 자체를 거부(401)한다. 통과 시 검증한 {@code userId} 와 세션ID 를 WebSocket 세션 속성에 저장해
 * 2단계(STOMP CONNECT 재검증)로 전달한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CounselHandshakeInterceptor implements HandshakeInterceptor {

    private static final String SESSION_COOKIE_NAME = "SESSION";
    // 2단계로 넘기는 WebSocket 세션 속성 키.
    static final String ATTR_USER_ID = "counsel.userId";
    static final String ATTR_SESSION_ID = "counsel.springSessionId";

    private final CounselWsSessionAuthenticator sessionAuthenticator;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String springSessionId = resolveSpringSessionId(request);
        Long userId = sessionAuthenticator.resolveUserId(springSessionId);
        if (userId == null) {
            log.debug("상담 WebSocket 핸드셰이크 거부 — 유효 세션 없음");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ATTR_USER_ID, userId);
        attributes.put(ATTR_SESSION_ID, springSessionId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    /** {@code SESSION} 쿠키 값(Base64) → 원본 Spring Session ID. 없거나 디코드 실패 시 null. */
    private String resolveSpringSessionId(ServerHttpRequest request) {
        List<String> cookieHeaders = request.getHeaders().get(HttpHeaders.COOKIE);
        if (cookieHeaders == null) {
            return null;
        }
        for (String header : cookieHeaders) {
            for (String pair : header.split(";")) {
                String trimmed = pair.trim();
                if (trimmed.startsWith(SESSION_COOKIE_NAME + "=")) {
                    String encoded = trimmed.substring(SESSION_COOKIE_NAME.length() + 1);
                    return decodeBase64(encoded);
                }
            }
        }
        return null;
    }

    private String decodeBase64(String encoded) {
        try {
            return new String(Base64.getDecoder().decode(encoded));
        } catch (IllegalArgumentException e) {
            log.debug("SESSION 쿠키 Base64 디코드 실패");
            return null;
        }
    }
}
