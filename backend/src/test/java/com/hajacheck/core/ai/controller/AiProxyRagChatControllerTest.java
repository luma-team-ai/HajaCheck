package com.hajacheck.core.ai.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.ai.dto.RagChatResponse;
import com.hajacheck.core.ai.service.AiProxyService;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.support.PostgresTestSupport;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * /api/ai/rag-chat MVC·시큐리티 통합 테스트(HAJA-32, #467). AiProxyControllerTest(defect-explain)와
 * 동일 패턴 — 외부 FastAPI 호출은 AiProxyService 를 @MockBean 으로 스텁해 네트워크 의존을 제거한다.
 *
 * <p>이 엔드포인트는 고객지원 챗봇이라 ADMIN 한정이 아니라 로그인 사용자 전체 대상이다(SecurityConfig
 * anyRequest().authenticated() 그대로 적용, 컨트롤러 레벨 별도 @PreAuthorize 없음).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AiProxyRagChatControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private AiProxyService aiProxyService;

    private LoginUser loginUser;

    // 프론트 계약(frontend/src/features/support/types.ts RagChatRequest)은 query 필드를 쓴다.
    private static final String REQUEST_BODY = """
            {"query":"균열 보수 기준은 무엇인가요?"}
            """;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.builder()
                .email("support-user@haja.com")
                .name("일반 사용자")
                .role(Role.INSPECTOR)
                .passwordHash(passwordEncoder.encode("pw123456"))
                .status(UserStatus.ACTIVE)
                .build());
        loginUser = new LoginUser(user);
    }

    @Test
    void RAG챗봇_미인증_401() throws Exception {
        mockMvc.perform(post("/api/ai/rag-chat").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void RAG챗봇_인증됨_AI서버성공_200과데이터반환() throws Exception {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                loginUser, null, loginUser.getAuthorities());

        RagChatResponse response = new RagChatResponse(
                "균열 보수는 손상 정도와 구조 안전성 평가 결과에 따라 보수 공법을 선택합니다.",
                List.of(new RagChatResponse.SourceCitation(
                        "42", "시설물의 안전 및 유지관리에 관한 특별법", "regulations",
                        "제12조", "관리주체는 시설물의 안전점검을 정기적으로 실시하여야 한다.", "42_3")));
        when(aiProxyService.ragChat(anyLong(), any())).thenReturn(ApiResponse.ok(response));

        mockMvc.perform(post("/api/ai/rag-chat").with(csrf()).with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.answer").value("균열 보수는 손상 정도와 구조 안전성 평가 결과에 따라 보수 공법을 선택합니다."))
                .andExpect(jsonPath("$.data.sources[0].doc_id").value("42"))
                .andExpect(jsonPath("$.data.sources[0].collection").value("regulations"))
                .andExpect(jsonPath("$.data.sources[0].locator").value("제12조"))
                .andExpect(jsonPath("$.data.sources[0].chunk_ref").value("42_3"));
    }

    @Test
    void RAG챗봇_검색결과0건_200과RAG_NO_RESULT에러envelope() throws Exception {
        // 계약(contract.md): 검색 0건은 예외가 아니라 success:false + error.code=RAG_NO_RESULT 로
        // HTTP 200 envelope 그대로 내려간다(useRagChat.ts가 이를 "근거 없음" 안내로 표시).
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                loginUser, null, loginUser.getAuthorities());
        when(aiProxyService.ragChat(anyLong(), any()))
                .thenReturn(ApiResponse.fail("RAG_NO_RESULT", "관련 근거를 찾지 못했습니다"));

        mockMvc.perform(post("/api/ai/rag-chat").with(csrf()).with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RAG_NO_RESULT"));
    }

    @Test
    void RAG챗봇_질의공백_400_INVALID_INPUT() throws Exception {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                loginUser, null, loginUser.getAuthorities());
        String invalidBody = """
                {"query":""}
                """;

        mockMvc.perform(post("/api/ai/rag-chat").with(csrf()).with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }
}
