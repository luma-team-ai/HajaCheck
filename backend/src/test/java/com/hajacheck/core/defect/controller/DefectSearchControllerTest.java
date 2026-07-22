package com.hajacheck.core.defect.controller;

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
import com.hajacheck.core.defect.dto.NlSearchFilters;
import com.hajacheck.core.defect.dto.NlSearchResult;
import com.hajacheck.core.defect.service.NlSearchService;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
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
 * /api/defects/nl-search MVC·시큐리티 통합 테스트(HAJA-120). 게이트/FastAPI 호출 상세 로직은
 * NlSearchServiceTest가 담당 — 여기서는 인증·역할 경계와 응답 배선만 검증(AiProxyControllerTest 패턴).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DefectSearchControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private NlSearchService nlSearchService;

    private LoginUser inspectorUser;
    private LoginUser normalUser;

    private static final String REQUEST_BODY = """
            {"query":"D등급 이상 조치 대기 하자"}
            """;

    @BeforeEach
    void setUp() {
        User inspector = userRepository.save(User.builder()
                .email("inspector-nlsearch@haja.com")
                .name("점검자")
                .role(Role.INSPECTOR)
                .passwordHash(passwordEncoder.encode("pw123456"))
                .status(UserStatus.ACTIVE)
                .build());
        inspectorUser = new LoginUser(inspector);

        User normal = userRepository.save(User.builder()
                .email("user-nlsearch@haja.com")
                .name("일반사용자")
                .role(Role.USER)
                .passwordHash(passwordEncoder.encode("pw123456"))
                .status(UserStatus.ACTIVE)
                .build());
        normalUser = new LoginUser(normal);
    }

    @Test
    void 자연어검색_미인증_401() throws Exception {
        mockMvc.perform(post("/api/defects/nl-search").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void 자연어검색_점검자아닌역할_403_FORBIDDEN() throws Exception {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                normalUser, null, normalUser.getAuthorities());

        mockMvc.perform(post("/api/defects/nl-search").with(csrf()).with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 자연어검색_점검자_게이트통과_200과필터반환() throws Exception {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                inspectorUser, null, inspectorUser.getAuthorities());
        NlSearchResult result = new NlSearchResult(
                new NlSearchFilters(List.of(), List.of("D", "E"), List.of("ACTION_PENDING"), null),
                List.of(), null, 0.92);
        when(nlSearchService.search(anyLong(), any())).thenReturn(ApiResponse.ok(result));

        mockMvc.perform(post("/api/defects/nl-search").with(csrf()).with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.filters.grade[0]").value("D"))
                .andExpect(jsonPath("$.data.filters.status[0]").value("ACTION_PENDING"));
    }

    @Test
    void 자연어검색_점검자_AI부가기능없음_403_AI_ADDON_REQUIRED() throws Exception {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                inspectorUser, null, inspectorUser.getAuthorities());
        when(nlSearchService.search(anyLong(), any()))
                .thenThrow(new BusinessException(ErrorCode.AI_ADDON_REQUIRED));

        mockMvc.perform(post("/api/defects/nl-search").with(csrf()).with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AI_ADDON_REQUIRED"));
    }

    @Test
    void 자연어검색_점검자_빈질의_400_INVALID_INPUT() throws Exception {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                inspectorUser, null, inspectorUser.getAuthorities());
        when(nlSearchService.search(anyLong(), any()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_INPUT));

        mockMvc.perform(post("/api/defects/nl-search").with(csrf()).with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }
}
