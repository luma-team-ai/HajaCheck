package com.hajacheck.counsel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.counsel.entity.ChatSession;
import com.hajacheck.counsel.entity.ChatSessionType;
import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselType;
import com.hajacheck.counsel.repository.ChatMessageRepository;
import com.hajacheck.counsel.repository.ChatSessionRepository;
import com.hajacheck.counsel.repository.CounselTicketRepository;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.repository.NotificationRepository;
import com.hajacheck.support.PostgresTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * COUNSEL_REPLIED 알림이 실제 트랜잭션 커밋 이후에만 발행되는지 검증하는 통합테스트(#993 P2) — 순수 Mockito
 * 단위테스트(CounselChatServiceTest)는 실제 트랜잭션 커밋/롤백 인과성을 증명할 수 없어, 이 테스트는 실제
 * PostgreSQL(Testcontainers)에 대고 진짜 커밋·롤백을 발생시켜 "메시지가 실제로 저장된 경우에만 알림도
 * 함께 남는다"는 계약을 고정한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class CounselChatServiceNotificationIntegrationTest extends PostgresTestSupport {

    @Autowired
    private CounselChatService counselChatService;
    @Autowired
    private CounselTicketRepository ticketRepository;
    @Autowired
    private ChatSessionRepository chatSessionRepository;
    @Autowired
    private ChatMessageRepository chatMessageRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long userId;
    private Long counselorId;
    private Long ticketId;
    private Long sessionId;

    @BeforeEach
    void setUp() {
        long unique = System.nanoTime();
        User user = userRepository.save(User.builder()
                .email("user-" + unique + "@haja.com").name("고객").role(Role.USER)
                .passwordHash("$2a$10$hashed").companyId(null).status(UserStatus.ACTIVE).build());
        User counselor = userRepository.save(User.builder()
                .email("counselor-" + unique + "@haja.com").name("상담원").role(Role.COUNSELOR)
                .passwordHash("$2a$10$hashed").companyId(null).status(UserStatus.ACTIVE).build());
        userId = user.getId();
        counselorId = counselor.getId();

        ChatSession session = chatSessionRepository.save(ChatSession.start(userId, ChatSessionType.COUNSEL));
        sessionId = session.getId();

        CounselTicket ticket = CounselTicket.request(
                userId, CounselType.ANALYSIS_RESULT, 1, "INSPECTION_REPORT", "AI 분석 결과 등급 문의");
        ticket.assign(counselorId, session);
        ticketId = ticketRepository.save(ticket).getId();
    }

    // PostgresTestSupport의 정적 Testcontainers 인스턴스는 같은 CI 실행의 모든 테스트 클래스(플랫폼 관리자
    // "전체 유저 목록" 조회 테스트 등)와 공유된다 — 정리 안 하면 이 테스트가 만든 User가 그쪽 count/content
    // assertion을 깨뜨린다(실제로 CI에서 재현된 회귀, PR #1006). FK 의존 역순으로 삭제.
    @AfterEach
    void tearDown() {
        chatMessageRepository.findAll().stream()
                .filter(m -> m.getSessionId().equals(sessionId))
                .forEach(m -> chatMessageRepository.deleteById(m.getId()));
        notificationRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, 10))
                .forEach(n -> notificationRepository.deleteById(n.getId()));
        ticketRepository.deleteById(ticketId);
        chatSessionRepository.deleteById(sessionId);
        userRepository.deleteById(userId);
        userRepository.deleteById(counselorId);
    }

    // 정적 Testcontainers 인스턴스를 다른 테스트 클래스와 공유하므로(PostgresTestSupport), 전역 findAll()
    // 대신 이 테스트가 만든 sessionId로 스코프를 좁혀 다른 테스트의 잔존 데이터와 섞이지 않게 한다.
    private boolean hasMessageForThisSession() {
        return chatMessageRepository.findAll().stream().anyMatch(m -> m.getSessionId().equals(sessionId));
    }

    private boolean hasCounselRepliedNotification() {
        return notificationRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, 10))
                .stream().anyMatch(n -> n.getType() == NotificationType.COUNSEL_REPLIED);
    }

    @Test
    void 실제_커밋되면_메시지와_알림모두_저장된다() {
        counselChatService.sendMessage(ticketId, counselorId, "무엇을 도와드릴까요", null);

        assertThat(hasMessageForThisSession()).isTrue();
        assertThat(hasCounselRepliedNotification()).isTrue();
    }

    @Test
    void 부모트랜잭션_커밋실패시_메시지도_알림도_남지않는다() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> txTemplate.execute(status -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("강제 커밋 실패 시뮬레이션(#993 재현)");
                }
            });
            counselChatService.sendMessage(ticketId, counselorId, "무엇을 도와드릴까요", null);
            return null;
        })).isInstanceOf(IllegalStateException.class);

        assertThat(hasMessageForThisSession()).isFalse();
        assertThat(hasCounselRepliedNotification()).isFalse();
    }
}
