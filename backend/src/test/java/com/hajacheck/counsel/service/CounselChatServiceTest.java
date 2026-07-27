package com.hajacheck.counsel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.counsel.dto.ChatMessageResponse;
import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSenderType;
import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselTicketStatus;
import com.hajacheck.counsel.entity.CounselType;
import com.hajacheck.counsel.repository.ChatMessageRepository;
import com.hajacheck.counsel.repository.CounselTicketRepository;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.service.NotificationService;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * CounselChatService 단위테스트 — 비진행 티켓/비참여자 발신 드롭, 참여자 발신 저장+브로드캐스트(#20/HAJA-33).
 *
 * <p>COUNSEL_REPLIED 알림은 실제 트랜잭션 커밋 이후에만 발행되도록 afterCommit 콜백으로 등록된다(#993 P2).
 * 여기서는 순수 Mockito 단위테스트라 실제 Spring 트랜잭션이 없으므로,
 * {@code TransactionSynchronizationManager}의 synchronization을 수동으로 활성화한 뒤 커밋을 시뮬레이션해서
 * 콜백이 등록·실행되는지 검증한다. 실제 커밋/롤백 인과성(메시지 롤백 시 알림도 안 남는지)은 mock으로 증명할
 * 수 없어 별도 통합테스트(CounselChatServiceNotificationIntegrationTest, Testcontainers)로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CounselChatServiceTest {

    private static final Long TICKET_ID = 50L;
    private static final Long SESSION_ID = 700L;
    private static final Long USER_ID = 1L;
    private static final Long COUNSELOR_ID = 9L;

    @Mock
    private CounselTicketRepository ticketRepository;
    @Mock
    private ChatMessageRepository chatMessageRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private NotificationService notificationService;

    private CounselChatService service;

    @BeforeEach
    void setUp() {
        service = new CounselChatService(
                ticketRepository, chatMessageRepository, userRepository, messagingTemplate, notificationService);
        User counselor = User.builder()
                .email("counselor@haja.com").name("상담원").role(Role.COUNSELOR)
                .passwordHash("$2a$10$hashed").companyId(null).status(UserStatus.ACTIVE).build();
        ReflectionTestUtils.setField(counselor, "id", COUNSELOR_ID);
        when(userRepository.findById(COUNSELOR_ID)).thenReturn(Optional.of(counselor));
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** afterCommit 콜백으로 등록된 알림 발행을 실제 커밋처럼 시뮬레이션해서 실행한다. */
    private void simulateCommit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
    }

    @Test
    void 발신_사용자참여자_USER발신자로_저장및브로드캐스트() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(inProgressTicket()));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        service.sendMessage(TICKET_ID, USER_ID, "안녕하세요", null);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository).save(captor.capture());
        assertThat(captor.getValue().getSender()).isEqualTo(ChatSenderType.USER);
        assertThat(captor.getValue().getSessionId()).isEqualTo(SESSION_ID);
        verify(messagingTemplate).convertAndSend(eq("/topic/counsel/" + TICKET_ID), any(ChatMessageResponse.class));
    }

    @Test
    void 발신_상담원참여자_COUNSELOR발신자로_저장() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(inProgressTicket()));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        service.sendMessage(TICKET_ID, COUNSELOR_ID, "무엇을 도와드릴까요", null);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository).save(captor.capture());
        assertThat(captor.getValue().getSender()).isEqualTo(ChatSenderType.COUNSELOR);
    }

    @Test
    void 발신_상담원답변시_티켓사용자에게_COUNSEL_REPLIED_알림발행() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(inProgressTicket()));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        service.sendMessage(TICKET_ID, COUNSELOR_ID, "무엇을 도와드릴까요", null);
        verify(notificationService, never()).notify(any(), any(), anyString());

        simulateCommit();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(eq(USER_ID), eq(NotificationType.COUNSEL_REPLIED), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).contains("\"ticketId\":" + TICKET_ID);
    }

    @Test
    void 발신_사용자발신시_알림미발행() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(inProgressTicket()));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        service.sendMessage(TICKET_ID, USER_ID, "안녕하세요", null);

        verify(notificationService, never()).notify(any(), any(), anyString());
    }

    @Test
    void 발신_알림발행실패해도_메시지저장및브로드캐스트는_정상수행() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(inProgressTicket()));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.doThrow(new RuntimeException("알림 저장 실패"))
                .when(notificationService).notify(any(), any(), anyString());

        service.sendMessage(TICKET_ID, COUNSELOR_ID, "무엇을 도와드릴까요", null);

        verify(chatMessageRepository).save(any(ChatMessage.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/counsel/" + TICKET_ID), any(ChatMessageResponse.class));

        // afterCommit 콜백 실행(=notify() 실패) 시점에도 이미 완료된 저장·브로드캐스트는 영향받지 않는다 —
        // 콜백 자체가 예외를 던지지 않고 조용히 흡수하는지만 확인(던지면 이 호출에서 테스트가 실패한다).
        simulateCommit();
    }

    @Test
    void 발신_비참여자_드롭() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(inProgressTicket()));

        service.sendMessage(TICKET_ID, 999L, "몰래 주입", null);

        verify(chatMessageRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void 발신_비진행티켓_드롭() {
        CounselTicket waiting = inProgressTicket();
        ReflectionTestUtils.setField(waiting, "status", CounselTicketStatus.WAITING);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(waiting));

        service.sendMessage(TICKET_ID, USER_ID, "아직 배정 전", null);

        verify(chatMessageRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void 발신_티켓없음_드롭() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.empty());

        service.sendMessage(TICKET_ID, USER_ID, "없는 티켓", null);

        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    void 발신_이미지첨부_저장키격리검증_MIME확장자도출() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(inProgressTicket()));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        service.sendMessage(TICKET_ID, USER_ID, null, "counsel-attachment/abc123.jpg");

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository).save(captor.capture());
        assertThat(captor.getValue().getAttachmentKey()).isEqualTo("counsel-attachment/abc123.jpg");
        assertThat(captor.getValue().getAttachmentMimeType()).isEqualTo("image/jpeg");
        assertThat(captor.getValue().getContent()).isEmpty();
    }

    @Test
    void 발신_타도메인저장키_드롭_격리() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(inProgressTicket()));

        // 점검 미디어 등 다른 도메인 저장키를 상담 첨부로 참조하려는 시도 → 드롭.
        service.sendMessage(TICKET_ID, USER_ID, null, "inspection-media/x.jpg");

        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    void 발신_내용과첨부모두없음_드롭() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(inProgressTicket()));

        service.sendMessage(TICKET_ID, USER_ID, "  ", null);

        verify(chatMessageRepository, never()).save(any());
    }

    private CounselTicket inProgressTicket() {
        CounselTicket ticket = CounselTicket.request(USER_ID, CounselType.ANALYSIS_RESULT, 1, "INSPECTION_REPORT", "AI 분석 결과 등급 문의");
        ReflectionTestUtils.setField(ticket, "id", TICKET_ID);
        ReflectionTestUtils.setField(ticket, "status", CounselTicketStatus.IN_PROGRESS);
        ReflectionTestUtils.setField(ticket, "counselorId", COUNSELOR_ID);
        ReflectionTestUtils.setField(ticket, "sessionId", SESSION_ID);
        return ticket;
    }
}
