package com.hajacheck.counsel.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSenderType;
import com.hajacheck.counsel.entity.ChatSession;
import com.hajacheck.counsel.entity.ChatSessionType;
import com.hajacheck.counsel.repository.ChatMessageRepository;
import com.hajacheck.counsel.repository.ChatSessionRepository;
import com.hajacheck.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * /api/chat-sessions MVC·시큐리티 통합 테스트(#1467/HAJA-647).
 *
 * <p>핵심 검증은 <b>cross-user 세션 접근 차단</b>이다 — 인증만 통과한 다른 사용자가 남의 sessionId 로
 * 이력을 조회하면 403이어야 한다("인증됨 ≠ 인가됨").
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ChatSessionControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private ChatSessionRepository chatSessionRepository;
    @Autowired
    private ChatMessageRepository chatMessageRepository;

    private LoginUser owner;
    private LoginUser otherUser;
    private ChatSession ownerSession;

    private static final String CREATE_BODY = """
            {"sessionType":"RAG"}
            """;

    @BeforeEach
    void setUp() {
        owner = new LoginUser(saveUser("chat-owner@haja.com", "세션 소유자"));
        otherUser = new LoginUser(saveUser("chat-other@haja.com", "타인"));
        ownerSession = chatSessionRepository.save(
                ChatSession.start(owner.getUserId(), ChatSessionType.RAG));
        chatMessageRepository.save(
                ChatMessage.createText(ownerSession.getId(), ChatSenderType.USER, "균열 보수 기준은?"));
        chatMessageRepository.save(
                ChatMessage.createText(ownerSession.getId(), ChatSenderType.BOT, "손상 정도에 따라 다릅니다."));
    }

    private User saveUser(String email, String name) {
        return userRepository.save(User.builder()
                .email(email)
                .name(name)
                .role(Role.INSPECTOR)
                .passwordHash(passwordEncoder.encode("pw123456"))
                .status(UserStatus.ACTIVE)
                .build());
    }

    private UsernamePasswordAuthenticationToken auth(LoginUser user) {
        return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
    }

    @Test
    void 세션생성_미인증_401() throws Exception {
        mockMvc.perform(post("/api/chat-sessions").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void 세션생성_인증됨_201과sessionId반환() throws Exception {
        mockMvc.perform(post("/api/chat-sessions").with(csrf()).with(authentication(auth(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sessionId").isNumber())
                .andExpect(jsonPath("$.data.sessionType").value("RAG"));
    }

    @Test
    void 세션생성_sessionType누락_400() throws Exception {
        mockMvc.perform(post("/api/chat-sessions").with(csrf()).with(authentication(auth(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void 이력조회_소유자본인_200과메시지반환() throws Exception {
        mockMvc.perform(get("/api/chat-sessions/{id}/messages", ownerSession.getId())
                        .with(authentication(auth(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].sender").value("USER"))
                .andExpect(jsonPath("$.data[0].content").value("균열 보수 기준은?"))
                .andExpect(jsonPath("$.data[0].citations.length()").value(0))
                .andExpect(jsonPath("$.data[1].sender").value("BOT"));
    }

    @Test
    void 이력조회_타인세션_403_CHAT_SESSION_FORBIDDEN() throws Exception {
        // 이 PR의 핵심 보안 요건 — 인증만 통과한 다른 사용자는 남의 세션 이력을 볼 수 없다.
        mockMvc.perform(get("/api/chat-sessions/{id}/messages", ownerSession.getId())
                        .with(authentication(auth(otherUser))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("CHAT_SESSION_FORBIDDEN"));
    }

    @Test
    void 이력조회_없는세션_타인세션과동일한403() throws Exception {
        // 존재 여부를 흘리지 않기 위해 404가 아니라 403으로 통일한다(cross-user 열거 방지).
        mockMvc.perform(get("/api/chat-sessions/{id}/messages", 99999999L)
                        .with(authentication(auth(owner))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CHAT_SESSION_FORBIDDEN"));
    }

    @Test
    void 이력조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/chat-sessions/{id}/messages", ownerSession.getId()))
                .andExpect(status().isUnauthorized());
    }
}
