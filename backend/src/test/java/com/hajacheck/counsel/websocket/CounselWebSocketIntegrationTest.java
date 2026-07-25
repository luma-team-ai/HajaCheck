package com.hajacheck.counsel.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.counsel.dto.ChatMessageResponse;
import com.hajacheck.counsel.dto.ChatMessageSendRequest;
import com.hajacheck.counsel.entity.ChatSession;
import com.hajacheck.counsel.entity.ChatSessionType;
import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.repository.ChatMessageRepository;
import com.hajacheck.counsel.repository.ChatSessionRepository;
import com.hajacheck.counsel.repository.CounselTicketRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * 상담 WebSocket 종단간 통합 테스트(#20/HAJA-33 Critical) — 실제 embedded 서버 + STOMP 클라이언트로 2단계
 * 인증 배선을 검증한다: 쿠키 없이 연결 거부 / 정상 세션 연결 성공 + 메시지 SEND→DB 저장+브로드캐스트 /
 * 타인 티켓 SUBSCRIBE 거부.
 *
 * <p>세부 인증 판정(핸드셰이크·CONNECT·SUBSCRIBE 각 분기)은 결정론적 단위테스트
 * ({@link CounselHandshakeInterceptorTest}·{@link StompAuthChannelInterceptorTest}·
 * {@link CounselWsSessionAuthenticatorTest})가 담당하고, 이 테스트는 실제 소켓 경로의 배선을 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CounselWebSocketIntegrationTest extends PostgresTestSupport {

    private static final String SECURITY_CONTEXT_KEY = "SPRING_SECURITY_CONTEXT";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * 테스트 프로파일은 Spring Session 을 끄므로(store-type=none) SessionRepository 빈이 없다. 운영(Redis)과
     * 동일하게 (1) 인터셉터의 ObjectProvider 가 해석할 SessionRepository 를 제공하고, (2)
     * {@code @EnableSpringHttpSession} 으로 SESSION 쿠키→HttpSession 복원을 활성화해 Spring Security 가
     * {@code /ws/**.authenticated()} 를 그 세션으로 통과시키도록 한다(운영에서 Spring Session+Security 통합과
     * 동일한 배선). in-memory MapSessionRepository 로 운영의 Redis 저장소를 대체한다.
     */
    @TestConfiguration
    @org.springframework.session.config.annotation.web.http.EnableSpringHttpSession
    static class SessionTestConfig {
        @Bean
        MapSessionRepository sessionRepository() {
            return new MapSessionRepository(new ConcurrentHashMap<>());
        }
    }

    @LocalServerPort
    private int port;
    @Autowired
    private MapSessionRepository sessionRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CounselTicketRepository ticketRepository;
    @Autowired
    private ChatSessionRepository chatSessionRepository;
    @Autowired
    private ChatMessageRepository chatMessageRepository;

    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // 비트랜잭션 테스트라 커밋된 데이터를 직접 정리한다(FK 순서: 메시지→티켓→세션→사용자).
        chatMessageRepository.deleteAll();
        ticketRepository.deleteAll();
        chatSessionRepository.deleteAll();
        createdUserIds.forEach(userRepository::deleteById);
        createdUserIds.clear();
    }

    @Test
    void 연결_쿠키없음_거부() {
        WebSocketStompClient client = stompClient();

        assertThatThrownBy(() -> client.connectAsync(
                        wsUrl(), new WebSocketHttpHeaders(), new StompSessionHandlerAdapter() {})
                .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isNotNull();
    }

    @Test
    void 정상세션_연결_메시지전송_저장_브로드캐스트() throws Exception {
        User requester = saveUser("ws-user@haja.com", Role.USER);
        User counselor = saveUser("ws-counselor@haja.com", Role.COUNSELOR);
        CounselTicket ticket = saveInProgressTicket(requester.getId(), counselor.getId());
        String cookie = cookieFor(requester);

        StompSession session = connect(cookie);
        BlockingQueue<ChatMessageResponse> received = new LinkedBlockingQueue<>();
        session.subscribe("/topic/counsel/" + ticket.getId(), frameHandler(received));
        // SUBSCRIBE 프레임이 브로커에 등록될 여유를 준 뒤 전송(같은 커넥션이라 순서는 보장되지만 등록 완료 대기).
        Thread.sleep(300);
        session.send("/app/counsel/" + ticket.getId() + "/send",
                new ChatMessageSendRequest("안녕하세요 상담원님", null));

        ChatMessageResponse broadcast = received.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(broadcast).isNotNull();
        assertThat(broadcast.content()).isEqualTo("안녕하세요 상담원님");

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(ticket.getSessionId()))
                        .hasSize(1)
                        .allSatisfy(m -> assertThat(m.getContent()).isEqualTo("안녕하세요 상담원님")));

        session.disconnect();
    }

    @Test
    void 타인티켓_구독_거부() throws Exception {
        User owner = saveUser("ws-owner@haja.com", Role.USER);
        User counselor = saveUser("ws-counselor2@haja.com", Role.COUNSELOR);
        CounselTicket othersTicket = saveInProgressTicket(owner.getId(), counselor.getId());
        User intruder = saveUser("ws-intruder@haja.com", Role.USER);
        String cookie = cookieFor(intruder);

        CountDownLatch errorLatch = new CountDownLatch(1);
        StompSession session = connect(cookie, errorLatch);
        session.subscribe("/topic/counsel/" + othersTicket.getId(),
                frameHandler(new LinkedBlockingQueue<>()));

        // 비당사자 구독은 인터셉터가 거부 → 서버가 ERROR 프레임을 보내 세션을 종료한다.
        assertThat(errorLatch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    }

    // ── helpers ──

    private WebSocketStompClient stompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(new ObjectMapper().registerModule(new JavaTimeModule()));
        client.setMessageConverter(converter);
        return client;
    }

    private StompSession connect(String cookie) throws Exception {
        return connect(cookie, new CountDownLatch(1));
    }

    private StompSession connect(String cookie, CountDownLatch errorLatch) throws Exception {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Cookie", "SESSION=" + cookie);
        return stompClient().connectAsync(wsUrl(), handshakeHeaders,
                        new StompSessionHandlerAdapter() {
                            @Override
                            public void handleException(StompSession s, org.springframework.messaging.simp.stomp.StompCommand command,
                                                        StompHeaders headers, byte[] payload, Throwable exception) {
                                errorLatch.countDown();
                            }

                            @Override
                            public void handleTransportError(StompSession s, Throwable exception) {
                                errorLatch.countDown();
                            }
                        })
                .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    private StompFrameHandler frameHandler(BlockingQueue<ChatMessageResponse> queue) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return ChatMessageResponse.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add((ChatMessageResponse) payload);
            }
        };
    }

    private String wsUrl() {
        return "ws://localhost:" + port + "/ws";
    }

    private User saveUser(String email, Role role) {
        User user = userRepository.save(User.builder()
                .email(email).name("사용자").role(role)
                .passwordHash("$2a$10$hashed").companyId(null).status(UserStatus.ACTIVE).build());
        createdUserIds.add(user.getId());
        return user;
    }

    private CounselTicket saveInProgressTicket(Long requesterId, Long counselorId) {
        ChatSession session = chatSessionRepository.save(
                ChatSession.start(requesterId, ChatSessionType.COUNSEL));
        CounselTicket ticket = ticketRepository.save(
                CounselTicket.request(requesterId, 1, "INSPECTION_REPORT", "AI 분석 결과 등급 문의"));
        ticket.assign(counselorId, session);
        return ticketRepository.saveAndFlush(ticket);
    }

    private String cookieFor(User user) {
        MapSession session = sessionRepository.createSession();
        LoginUser principal = new LoginUser(user);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        session.setAttribute(SECURITY_CONTEXT_KEY, context);
        sessionRepository.save(session);
        return Base64.getEncoder().encodeToString(session.getId().getBytes());
    }
}
