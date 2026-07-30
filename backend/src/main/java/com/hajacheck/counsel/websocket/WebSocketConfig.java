package com.hajacheck.counsel.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket 설정(FR-7, #20/HAJA-33) — 컨벤션 확정 경로(SpringBoot_코드_컨벤션.md §WebSocket).
 *
 * <ul>
 *   <li>엔드포인트 {@code /ws} (SockJS 미사용 — dev(Vite)/prod(nginx) 동일 오리진 프록시 전제)</li>
 *   <li>메모리 브로커 {@code /topic}(상담방 브로드캐스트) · {@code /queue}(개인 알림)</li>
 *   <li>애플리케이션 목적지 prefix {@code /app}, 사용자 목적지 prefix {@code /user}</li>
 * </ul>
 *
 * 인증은 핸드셰이크({@link CounselHandshakeInterceptor})와 인바운드 채널({@link StompAuthChannelInterceptor})
 * 2단계로 강제한다.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final CounselHandshakeInterceptor counselHandshakeInterceptor;
    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 동일 오리진 프록시 전제 — 기본(same-origin) 오리진 정책 유지, SockJS 미사용.
        // "/ws/"(trailing slash)도 등록 — 클라이언트(buildWsUrl)가 trailing slash를 붙여 연결하는데,
        // "/ws"만 등록하면 "/ws/"가 WebSocket 핸들러 대신 MVC DispatcherServlet으로 fallback돼
        // NoResourceFoundException(404)이 발생한다(nginx 로그 404, Spring 로그 "No static resource ws." 확인됨).
        registry.addEndpoint("/ws", "/ws/")
                .addInterceptors(counselHandshakeInterceptor);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
