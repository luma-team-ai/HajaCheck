package com.hajacheck.core.rag.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.ai.dto.RagEmbedResponse;
import com.hajacheck.core.ai.service.AiProxyService;
import com.hajacheck.core.rag.entity.ChatMessageCitation;
import com.hajacheck.core.rag.repository.ChatMessageCitationRepository;
import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSenderType;
import com.hajacheck.counsel.entity.ChatSession;
import com.hajacheck.counsel.entity.ChatSessionType;
import com.hajacheck.counsel.repository.ChatMessageRepository;
import com.hajacheck.counsel.repository.ChatSessionRepository;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.support.PostgresTestSupport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * /api/admin/rag-documents MVC·시큐리티 통합 테스트(#22/HAJA-35) — 전용 시큐리티 매처(/api/admin/rag-documents/**
 * → hasRole(PLATFORM_ADMIN), PR #685 리뷰 P1)를 실 PostgreSQL(Testcontainers)에서 검증한다. 회사 ADMIN은
 * 더 이상 접근 불가(전 테넌트 공유 지식베이스라 PLATFORM_ADMIN 전용) — 매처 순서(구체 패턴 선행)가 깨지면
 * "/api/admin/**"(ADMIN 전용) 이 먼저 매칭돼 이 테스트들이 회귀를 잡아낸다. 외부 FastAPI 호출은 다른
 * admin/ai 컨트롤러 테스트와 동일하게 AiProxyService를 @MockBean으로 스텁해 네트워크 의존을 제거한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RagDocumentControllerTest extends PostgresTestSupport {

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
    @Autowired
    private ChatMessageCitationRepository chatMessageCitationRepository;

    @MockBean
    private AiProxyService aiProxyService;

    private LoginUser platformAdminUser;
    private LoginUser adminUser;
    private LoginUser normalUser;

    @BeforeEach
    void setUp() {
        User platformAdmin = userRepository.save(User.builder()
                .email("rag-platform-admin@haja.com")
                .name("플랫폼관리자")
                .role(Role.PLATFORM_ADMIN)
                .passwordHash(passwordEncoder.encode("pw123456"))
                .status(UserStatus.ACTIVE)
                .build());
        platformAdminUser = new LoginUser(platformAdmin);

        User admin = userRepository.save(User.builder()
                .email("rag-admin@haja.com")
                .name("관리자")
                .role(Role.ADMIN)
                .passwordHash(passwordEncoder.encode("pw123456"))
                .status(UserStatus.ACTIVE)
                .build());
        adminUser = new LoginUser(admin);

        User user = userRepository.save(User.builder()
                .email("rag-user@haja.com")
                .name("일반사용자")
                .role(Role.USER)
                .passwordHash(passwordEncoder.encode("pw123456"))
                .status(UserStatus.ACTIVE)
                .build());
        normalUser = new LoginUser(user);
    }

    @Test
    void 목록조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/admin/rag-documents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 목록조회_일반사용자_403() throws Exception {
        mockMvc.perform(get("/api/admin/rag-documents").with(authentication(authOf(normalUser))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 목록조회_회사관리자_403() throws Exception {
        // PR #685 리뷰 P1 회귀 테스트 — 전 테넌트 공유 지식베이스라 회사 ADMIN은 더 이상 접근 불가.
        mockMvc.perform(get("/api/admin/rag-documents").with(authentication(authOf(adminUser))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 목록조회_플랫폼관리자_200_빈배열() throws Exception {
        mockMvc.perform(get("/api/admin/rag-documents").with(authentication(authOf(platformAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void 업로드_플랫폼관리자_AI서버성공_201_EMBEDDING상태() throws Exception {
        // #1328 — FastAPI가 청킹만 동기로 마치고 실제 임베딩은 BackgroundTasks로 넘기므로, 업로드
        // 응답 시점에는 아직 완료를 확정하지 않는다(RagEmbeddingCompletionPoller가 비동기로 폴링해
        // 나중에 DONE으로 전환 — 그 완료 확정 로직 자체는 RagEmbeddingCompletionPollerTest가
        // 검증한다). 컨트롤러 계약상 응답은 이제 EMBEDDING이 정상이다.
        when(aiProxyService.embedRagDocument(any())).thenReturn(ApiResponse.ok(new RagEmbedResponse(3, "batch-1")));

        mockMvc.perform(multipart("/api/admin/rag-documents")
                        .file(pdfPart())
                        .param("title", "시설물의 안전관리에 관한 특별법")
                        .param("sourceType", "LAW")
                        .param("targetCollection", "REGULATIONS")
                        .param("publisher", "국토교통부")
                        .with(csrf()).with(authentication(authOf(platformAdminUser))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.title").value("시설물의 안전관리에 관한 특별법"))
                .andExpect(jsonPath("$.data.embeddingStatus").value("EMBEDDING"));
    }

    @Test
    void 업로드_AI서버실패_201이지만FAILED상태_업로드자체는성공() throws Exception {
        when(aiProxyService.embedRagDocument(any()))
                .thenReturn(ApiResponse.fail("VALIDATION_ERROR", "청크 분할 실패"));

        mockMvc.perform(multipart("/api/admin/rag-documents")
                        .file(pdfPart())
                        .param("title", "하자 유형별 보수 지침")
                        .param("sourceType", "GUIDELINE")
                        .param("targetCollection", "DEFECT_KB")
                        .with(csrf()).with(authentication(authOf(platformAdminUser))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.embeddingStatus").value("FAILED"));
    }

    @Test
    void 업로드_일반사용자_403() throws Exception {
        mockMvc.perform(multipart("/api/admin/rag-documents")
                        .file(pdfPart())
                        .param("title", "제목")
                        .param("sourceType", "LAW")
                        .param("targetCollection", "REGULATIONS")
                        .with(csrf()).with(authentication(authOf(normalUser))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 업로드_회사관리자_403() throws Exception {
        // PR #685 리뷰 P1 회귀 테스트.
        mockMvc.perform(multipart("/api/admin/rag-documents")
                        .file(pdfPart())
                        .param("title", "제목")
                        .param("sourceType", "LAW")
                        .param("targetCollection", "REGULATIONS")
                        .with(csrf()).with(authentication(authOf(adminUser))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 업로드_제목누락_400() throws Exception {
        mockMvc.perform(multipart("/api/admin/rag-documents")
                        .file(pdfPart())
                        .param("sourceType", "LAW")
                        .param("targetCollection", "REGULATIONS")
                        .with(csrf()).with(authentication(authOf(platformAdminUser))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 업로드_PDF아닌파일_400() throws Exception {
        MockMultipartFile textFile = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "그냥 텍스트".getBytes());

        mockMvc.perform(multipart("/api/admin/rag-documents")
                        .file(textFile)
                        .param("title", "제목")
                        .param("sourceType", "LAW")
                        .param("targetCollection", "REGULATIONS")
                        .with(csrf()).with(authentication(authOf(platformAdminUser))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 재임베딩_플랫폼관리자_200_EMBEDDING상태로재전환() throws Exception {
        // #1328 — 재임베딩 시작 직후 응답도 업로드와 동일하게 완료를 확정하지 않는다. restartEmbedding()은
        // PENDING/DONE/FAILED에서만 허용되므로(RagDocument 참고), 업로드 직후 아직 EMBEDDING인 문서를
        // 바로 재임베딩 대상으로 쓰면 409가 난다 — AI 서버 실패 응답으로 먼저 FAILED를 동기로 만든 뒤
        // 그 문서를 재임베딩한다.
        when(aiProxyService.embedRagDocument(any()))
                .thenReturn(ApiResponse.fail("VALIDATION_ERROR", "청크 분할 실패"))
                .thenReturn(ApiResponse.ok(new RagEmbedResponse(9, "batch-1")));

        String uploadResponse = mockMvc.perform(multipart("/api/admin/rag-documents")
                        .file(pdfPart())
                        .param("title", "재임베딩 대상 문서")
                        .param("sourceType", "LAW")
                        .param("targetCollection", "REGULATIONS")
                        .with(csrf()).with(authentication(authOf(platformAdminUser))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.embeddingStatus").value("FAILED"))
                .andReturn().getResponse().getContentAsString();

        Long id = extractId(uploadResponse);

        mockMvc.perform(post("/api/admin/rag-documents/{id}/re-embed", id)
                        .with(csrf()).with(authentication(authOf(platformAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.embeddingStatus").value("EMBEDDING"));
    }

    @Test
    void 재임베딩_존재하지않는문서_404() throws Exception {
        mockMvc.perform(post("/api/admin/rag-documents/{id}/re-embed", 999999L)
                        .with(csrf()).with(authentication(authOf(platformAdminUser))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RAG_DOCUMENT_NOT_FOUND"));
    }

    @Test
    void 재임베딩_일반사용자_403() throws Exception {
        mockMvc.perform(post("/api/admin/rag-documents/{id}/re-embed", 1L)
                        .with(csrf()).with(authentication(authOf(normalUser))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 재임베딩_회사관리자_403() throws Exception {
        // PR #685 리뷰 P1 회귀 테스트.
        mockMvc.perform(post("/api/admin/rag-documents/{id}/re-embed", 1L)
                        .with(csrf()).with(authentication(authOf(adminUser))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 삭제_플랫폼관리자_200_목록에서제거() throws Exception {
        when(aiProxyService.embedRagDocument(any())).thenReturn(ApiResponse.ok(new RagEmbedResponse(2, "batch-1")));

        String uploadResponse = mockMvc.perform(multipart("/api/admin/rag-documents")
                        .file(pdfPart())
                        .param("title", "삭제 대상 문서")
                        .param("sourceType", "LAW")
                        .param("targetCollection", "REGULATIONS")
                        .with(csrf()).with(authentication(authOf(platformAdminUser))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long id = extractId(uploadResponse);

        mockMvc.perform(delete("/api/admin/rag-documents/{id}", id)
                        .with(csrf()).with(authentication(authOf(platformAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(post("/api/admin/rag-documents/{id}/re-embed", id)
                        .with(csrf()).with(authentication(authOf(platformAdminUser))))
                .andExpect(status().isNotFound());
    }

    // #1597 — V1 baseline이 document_id FK에 ON DELETE 절을 빠뜨려 인용 이력이 있는 문서는 Chroma
    // 청크만 지워진 채 PG FK 위반(23503)으로 삭제가 영구 실패했다(RagDocumentService.delete()). V49로
    // ON DELETE SET NULL을 붙였으니 실 PostgreSQL에서 정상 삭제되고, citation 행은 document_id=NULL로
    // 살아남아야 한다(locator/snippet은 citation 행 자체 보관이라 그대로 유지).
    @Test
    void 삭제_인용이력있어도_정상삭제되고_citation은_document_id_NULL로_남는다() throws Exception {
        when(aiProxyService.embedRagDocument(any())).thenReturn(ApiResponse.ok(new RagEmbedResponse(1, "batch-1")));

        String uploadResponse = mockMvc.perform(multipart("/api/admin/rag-documents")
                        .file(pdfPart())
                        .param("title", "인용된 문서")
                        .param("sourceType", "LAW")
                        .param("targetCollection", "REGULATIONS")
                        .with(csrf()).with(authentication(authOf(platformAdminUser))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long documentId = extractId(uploadResponse);

        ChatSession session = chatSessionRepository.save(
                ChatSession.start(userRepository.findByEmail("rag-user@haja.com").orElseThrow().getId(),
                        ChatSessionType.RAG));
        ChatMessage botMessage = chatMessageRepository.save(
                ChatMessage.createText(session.getId(), ChatSenderType.BOT, "인용된 답변"));
        ChatMessageCitation citation = chatMessageCitationRepository.save(ChatMessageCitation.create(
                botMessage.getId(), documentId, "1_1", "제1조", "인용 발췌"));

        mockMvc.perform(delete("/api/admin/rag-documents/{id}", documentId)
                        .with(csrf()).with(authentication(authOf(platformAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ChatMessageCitation reloaded = chatMessageCitationRepository.findById(citation.getId()).orElseThrow();
        assertThat(reloaded.getDocumentId()).isNull();
        assertThat(reloaded.getDocument()).isNull();
        assertThat(reloaded.getLocator()).isEqualTo("제1조");
        assertThat(reloaded.getSnippet()).isEqualTo("인용 발췌");

        // 목록에서도 실제로 사라졌어야 한다(FK 위반으로 반쪽 삭제가 재발하지 않았는지 확인).
        mockMvc.perform(get("/api/admin/rag-documents").with(authentication(authOf(platformAdminUser))))
                .andExpect(jsonPath("$.data[?(@.title == '인용된 문서')]").doesNotExist());
    }

    @Test
    void 삭제_AI서버실패_DB에는그대로남아재시도가능() throws Exception {
        when(aiProxyService.embedRagDocument(any())).thenReturn(ApiResponse.ok(new RagEmbedResponse(1, "batch-1")));

        String uploadResponse = mockMvc.perform(multipart("/api/admin/rag-documents")
                        .file(pdfPart())
                        .param("title", "AI서버실패 문서")
                        .param("sourceType", "LAW")
                        .param("targetCollection", "REGULATIONS")
                        .with(csrf()).with(authentication(authOf(platformAdminUser))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long id = extractId(uploadResponse);

        doThrow(new BusinessException(ErrorCode.AI_SERVER_UNREACHABLE))
                .when(aiProxyService).deleteRagDocumentChunks(anyString(), anyString());

        mockMvc.perform(delete("/api/admin/rag-documents/{id}", id)
                        .with(csrf()).with(authentication(authOf(platformAdminUser))))
                .andExpect(status().isServiceUnavailable());

        // DB/파일이 그대로 남아 재시도(=다시 삭제 호출)로 회복 가능해야 한다 — 목록조회로 확인.
        mockMvc.perform(get("/api/admin/rag-documents").with(authentication(authOf(platformAdminUser))))
                .andExpect(jsonPath("$.data[?(@.title == 'AI서버실패 문서')]").exists());
    }

    @Test
    void 삭제_존재하지않는문서_404() throws Exception {
        mockMvc.perform(delete("/api/admin/rag-documents/{id}", 999999L)
                        .with(csrf()).with(authentication(authOf(platformAdminUser))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RAG_DOCUMENT_NOT_FOUND"));
    }

    @Test
    void 삭제_일반사용자_403() throws Exception {
        mockMvc.perform(delete("/api/admin/rag-documents/{id}", 1L)
                        .with(csrf()).with(authentication(authOf(normalUser))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 삭제_회사관리자_403() throws Exception {
        mockMvc.perform(delete("/api/admin/rag-documents/{id}", 1L)
                        .with(csrf()).with(authentication(authOf(adminUser))))
                .andExpect(status().isForbidden());
    }

    private Long extractId(String json) {
        // 간단한 응답 바디에서 "id":<number> 값만 뽑는다(전용 JSON 라이브러리 파싱 없이 최소 의존으로).
        var matcher = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("업로드 응답에서 id를 찾을 수 없습니다: " + json);
        }
        return Long.valueOf(matcher.group(1));
    }

    private MockMultipartFile pdfPart() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText("Article 1 (Purpose) This guideline defines facility safety inspections.");
                stream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return new MockMultipartFile("file", "law.pdf", "application/pdf", out.toByteArray());
        }
    }

    private UsernamePasswordAuthenticationToken authOf(LoginUser user) {
        return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
    }
}
