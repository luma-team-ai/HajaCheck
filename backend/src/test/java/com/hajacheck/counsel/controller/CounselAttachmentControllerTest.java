package com.hajacheck.counsel.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.auth.support.FileStorageService;
import com.hajacheck.auth.support.FileStorageService.StoredFile;
import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSenderType;
import com.hajacheck.counsel.entity.ChatSession;
import com.hajacheck.counsel.entity.ChatSessionType;
import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselType;
import com.hajacheck.counsel.repository.ChatMessageRepository;
import com.hajacheck.counsel.repository.ChatSessionRepository;
import com.hajacheck.counsel.repository.CounselTicketRepository;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.hajacheck.support.PostgresTestSupport;

/**
 * 상담 채팅 이미지 첨부 MVC 통합 테스트(#20/HAJA-33) — 매직바이트 위조 검증 + 업로드/서빙 IDOR.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CounselAttachmentControllerTest extends PostgresTestSupport {

    private static final byte[] JPEG_BYTES = jpegBytes();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CounselTicketRepository ticketRepository;
    @Autowired
    private ChatSessionRepository chatSessionRepository;
    @Autowired
    private ChatMessageRepository chatMessageRepository;
    @Autowired
    private FileStorageService fileStorage;

    @Test
    void 업로드_정상JPEG_201_저장키반환() throws Exception {
        Fixture f = fixture();
        MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", JPEG_BYTES);

        mockMvc.perform(multipart("/api/counsel/tickets/{id}/attachments", f.ticket.getId())
                        .file(file).with(csrf()).with(authentication(authOf(f.requester))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attachmentKey").value(Matchers.startsWith("counsel-attachment/")))
                .andExpect(jsonPath("$.data.mimeType").value("image/jpeg"));
    }

    @Test
    void 업로드_매직바이트위조_400_FILE_INVALID_TYPE() throws Exception {
        Fixture f = fixture();
        // 선언은 image/jpeg 지만 실제 바이트는 JPEG 시그니처가 아님 → 매직바이트 검증 실패.
        MockMultipartFile forged =
                new MockMultipartFile("file", "a.jpg", "image/jpeg", "NOT-A-REAL-JPEG-1234".getBytes());

        mockMvc.perform(multipart("/api/counsel/tickets/{id}/attachments", f.ticket.getId())
                        .file(forged).with(csrf()).with(authentication(authOf(f.requester))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FILE_INVALID_TYPE"));
    }

    @Test
    void 업로드_비당사자_404_TICKET_NOT_FOUND() throws Exception {
        Fixture f = fixture();
        User intruder = saveUser("att-intruder@haja.com", Role.USER);
        MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", JPEG_BYTES);

        mockMvc.perform(multipart("/api/counsel/tickets/{id}/attachments", f.ticket.getId())
                        .file(file).with(csrf()).with(authentication(authOf(intruder))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COUNSEL_TICKET_NOT_FOUND"));
    }

    @Test
    void 서빙_당사자_200_바이트반환() throws Exception {
        Fixture f = fixture();
        ChatMessage message = saveMessageWithAttachment(f);

        mockMvc.perform(get("/api/counsel/tickets/{tid}/messages/{mid}/attachment",
                        f.ticket.getId(), message.getId())
                        .with(authentication(authOf(f.counselor))))
                .andExpect(status().isOk());
    }

    @Test
    void 서빙_비당사자_404_TICKET_NOT_FOUND() throws Exception {
        Fixture f = fixture();
        ChatMessage message = saveMessageWithAttachment(f);
        User intruder = saveUser("serve-intruder@haja.com", Role.USER);

        mockMvc.perform(get("/api/counsel/tickets/{tid}/messages/{mid}/attachment",
                        f.ticket.getId(), message.getId())
                        .with(authentication(authOf(intruder))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COUNSEL_TICKET_NOT_FOUND"));
    }

    // ── fixtures ──

    private record Fixture(User requester, User counselor, ChatSession session, CounselTicket ticket) {
    }

    private Fixture fixture() {
        User requester = saveUser("att-user-" + System.nanoTime() + "@haja.com", Role.USER);
        User counselor = saveUser("att-counselor-" + System.nanoTime() + "@haja.com", Role.COUNSELOR);
        ChatSession session = chatSessionRepository.save(
                ChatSession.start(requester.getId(), ChatSessionType.COUNSEL));
        CounselTicket ticket = ticketRepository.save(
                CounselTicket.request(requester.getId(), CounselType.ANALYSIS_RESULT, 1, "INSPECTION_REPORT", "AI 분석 결과 등급 문의"));
        ticket.assign(counselor.getId(), session);
        ticketRepository.saveAndFlush(ticket);
        return new Fixture(requester, counselor, session, ticket);
    }

    private ChatMessage saveMessageWithAttachment(Fixture f) {
        StoredFile stored = fileStorage.store(
                new MockMultipartFile("file", "a.jpg", "image/jpeg", JPEG_BYTES),
                "counsel-attachment", List.of("image/jpeg"), 20_000_000L);
        return chatMessageRepository.save(ChatMessage.create(
                f.session.getId(), ChatSenderType.USER, "", null, stored.storageKey(), "image/jpeg"));
    }

    private User saveUser(String email, Role role) {
        return userRepository.save(User.builder()
                .email(email).name("사용자").role(role)
                .passwordHash("$2a$10$hashed").companyId(null).status(UserStatus.ACTIVE).build());
    }

    private UsernamePasswordAuthenticationToken authOf(User user) {
        LoginUser principal = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private static byte[] jpegBytes() {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }
}
