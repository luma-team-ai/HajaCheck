package com.hajacheck.global.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.support.PostgresTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 미존재 경로 라우팅 통합 테스트(#330).
 *
 * <p>GlobalExceptionHandlerTest 는 핸들러 메서드 계약만 보므로, 실제 디스패처 체인에서
 * NoResourceFoundException 이 포괄 {@code @ExceptionHandler(Exception.class)} 가 아니라
 * 전용 핸들러로 도달하는지는 여기서 검증한다(= 500 회귀 차단).
 *
 * <p>AuthControllerTest 와 동일하게 슬라이스(@WebMvcTest) 대신 @SpringBootTest 를 쓴다 —
 * oauth2Login 필터가 ClientRegistrationRepository 를 요구하기 때문.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotFoundRoutingIntegrationTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("매핑되지 않은 경로는 500이 아니라 404 + RESOURCE_NOT_FOUND 표준 봉투로 응답한다")
    void unmappedPath_returns404NotFound() throws Exception {
        mockMvc.perform(get("/api/this-path-does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }
}
