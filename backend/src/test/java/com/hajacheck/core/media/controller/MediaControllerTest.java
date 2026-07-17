package com.hajacheck.core.media.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미인증 요청이 SecurityConfig 의 anyRequest().authenticated() 로 실제 차단되어 401을
 * 반환하는지 고정하는 회귀 테스트(리뷰 P2). 두 엔드포인트 모두 @AuthenticationPrincipal
 * LoginUser 를 컨트롤러 진입 즉시 역참조하므로, 필터체인이 이 경로를 보호하지 못하게 되면
 * 401 대신 NPE(500)로 응답한다 — 이 테스트가 그 회귀를 잡는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MediaControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 업로드_미인증_401() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "a.png", MediaType.IMAGE_PNG_VALUE, "PNGDATA".getBytes());

        mockMvc.perform(multipart("/api/inspections/{id}/media", 1L)
                        .file(file)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 썸네일조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/media/{id}/thumbnail", 1L))
                .andExpect(status().isUnauthorized());
    }
}
