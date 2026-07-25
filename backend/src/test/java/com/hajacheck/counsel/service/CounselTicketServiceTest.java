package com.hajacheck.counsel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.counsel.dto.CounselTicketResponse;
import com.hajacheck.counsel.entity.ChatSession;
import com.hajacheck.counsel.entity.ChatSessionType;
import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselTicketStatus;
import com.hajacheck.counsel.repository.ChatSessionRepository;
import com.hajacheck.counsel.repository.CounselTicketRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * CounselTicketService 단위테스트 — 플랜 게이팅/셀프-클레임 배정 경합/소유권 검증(#20/HAJA-33).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CounselTicketServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long COUNSELOR_ID = 9L;
    private static final Long PLAN_ID = 100L;
    private static final Long TICKET_ID = 50L;

    @Mock
    private CounselTicketRepository ticketRepository;
    @Mock
    private ChatSessionRepository chatSessionRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserPlanRepository userPlanRepository;
    @Mock
    private PlanRepository planRepository;
    @Mock
    private CompanyMembershipRepository companyMembershipRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private CounselTicketService service;

    @BeforeEach
    void setUp() {
        service = new CounselTicketService(ticketRepository, chatSessionRepository, userRepository,
                userPlanRepository, planRepository, companyMembershipRepository, messagingTemplate);
    }

    // ── createTicket: 플랜 게이팅 ──

    @Test
    void 티켓생성_상담원접근플랜_성공_대기순번스냅샷() {
        User user = individualUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan()));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan(true)));
        when(ticketRepository.countByStatus(CounselTicketStatus.WAITING)).thenReturn(2L);
        when(ticketRepository.save(any(CounselTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        CounselTicketResponse response = service.createTicket(USER_ID);

        assertThat(response.status()).isEqualTo(CounselTicketStatus.WAITING);
        assertThat(response.queuePosition()).isEqualTo(3);
    }

    @Test
    void 티켓생성_상담원접근불가플랜_403_COUNSEL_PLAN_REQUIRED() {
        User user = individualUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan()));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan(false)));

        assertThatThrownBy(() -> service.createTicket(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_PLAN_REQUIRED);
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void 티켓생성_활성플랜없음_403_COUNSEL_PLAN_REQUIRED() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser()));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createTicket(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_PLAN_REQUIRED);
    }

    @Test
    void 티켓생성_회사소속_유효멤버십없음_403_COUNSEL_PLAN_REQUIRED() {
        User companyUser = companyUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(companyUser));
        when(companyMembershipRepository.existsEffectiveApprovedMembership(eq(20L), eq(USER_ID), any(Instant.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> service.createTicket(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_PLAN_REQUIRED);
    }

    // ── assign: 셀프-클레임 ──

    @Test
    void 배정_대기티켓_성공_사용자에게알림() {
        CounselTicket ticket = waitingTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(userRepository.existsById(COUNSELOR_ID)).thenReturn(true);
        when(chatSessionRepository.save(any(ChatSession.class)))
                .thenAnswer(inv -> withId((ChatSession) inv.getArgument(0), 700L));
        when(ticketRepository.saveAndFlush(any(CounselTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        CounselTicketResponse response = service.assignToCounselor(TICKET_ID, COUNSELOR_ID);

        assertThat(response.status()).isEqualTo(CounselTicketStatus.IN_PROGRESS);
        assertThat(response.counselorId()).isEqualTo(COUNSELOR_ID);
        verify(messagingTemplate).convertAndSendToUser(eq(String.valueOf(USER_ID)), anyString(), any(Object.class));
    }

    @Test
    void 배정_이미진행중티켓_409_ASSIGNMENT_CONFLICT() {
        CounselTicket ticket = waitingTicket();
        ReflectionTestUtils.setField(ticket, "status", CounselTicketStatus.IN_PROGRESS);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.assignToCounselor(TICKET_ID, COUNSELOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_SESSION_ASSIGNMENT_CONFLICT);
        verify(chatSessionRepository, never()).save(any());
    }

    @Test
    void 배정_동시클레임_낙관적락충돌_409_ASSIGNMENT_CONFLICT() {
        CounselTicket ticket = waitingTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(userRepository.existsById(COUNSELOR_ID)).thenReturn(true);
        when(chatSessionRepository.save(any(ChatSession.class)))
                .thenAnswer(inv -> withId((ChatSession) inv.getArgument(0), 700L));
        when(ticketRepository.saveAndFlush(any(CounselTicket.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(CounselTicket.class, TICKET_ID));

        assertThatThrownBy(() -> service.assignToCounselor(TICKET_ID, COUNSELOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_SESSION_ASSIGNMENT_CONFLICT);
        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any(Object.class));
    }

    @Test
    void 배정_티켓없음_404_TICKET_NOT_FOUND() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignToCounselor(TICKET_ID, COUNSELOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_TICKET_NOT_FOUND);
    }

    // ── resolve: 소유권 ──

    @Test
    void 종료_담당상담원본인_성공_사용자에게종료알림() {
        CounselTicket ticket = inProgressTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(chatSessionRepository.findById(700L)).thenReturn(Optional.of(withId(
                ChatSession.start(USER_ID, ChatSessionType.COUNSEL), 700L)));
        when(ticketRepository.saveAndFlush(any(CounselTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        CounselTicketResponse response = service.resolve(TICKET_ID, COUNSELOR_ID, false);

        assertThat(response.status()).isEqualTo(CounselTicketStatus.RESOLVED);
        verify(messagingTemplate).convertAndSendToUser(eq(String.valueOf(USER_ID)), anyString(), any(Object.class));
    }

    @Test
    void 종료_담당아닌상담원_403_TICKET_FORBIDDEN() {
        CounselTicket ticket = inProgressTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.resolve(TICKET_ID, 999L, false))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_TICKET_FORBIDDEN);
    }

    @Test
    void 종료_플랫폼관리자_담당아니어도_성공() {
        CounselTicket ticket = inProgressTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(chatSessionRepository.findById(700L)).thenReturn(Optional.empty());
        when(ticketRepository.saveAndFlush(any(CounselTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        CounselTicketResponse response = service.resolve(TICKET_ID, 999L, true);

        assertThat(response.status()).isEqualTo(CounselTicketStatus.RESOLVED);
    }

    // ── leaveOffline: 소유권 ──

    @Test
    void 이탈_소유자본인_성공() {
        CounselTicket ticket = waitingTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(ticketRepository.saveAndFlush(any(CounselTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        CounselTicketResponse response = service.leaveOffline(TICKET_ID, USER_ID);

        assertThat(response.status()).isEqualTo(CounselTicketStatus.OFFLINE_LEFT);
    }

    @Test
    void 이탈_타인_404_TICKET_NOT_FOUND_열거방지() {
        CounselTicket ticket = waitingTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.leaveOffline(TICKET_ID, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_TICKET_NOT_FOUND);
    }

    // ── fixtures ──

    private User individualUser() {
        return user(null);
    }

    private User companyUser() {
        return user(20L);
    }

    private User user(Long companyId) {
        User user = User.builder()
                .email("u@haja.com")
                .name("사용자")
                .role(Role.USER)
                .passwordHash("$2a$10$hashed")
                .companyId(companyId)
                .status(UserStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    private UserPlan userPlan() {
        UserPlan userPlan = UserPlan.forUser(USER_ID, PLAN_ID);
        ReflectionTestUtils.setField(userPlan, "id", 500L);
        return userPlan;
    }

    private Plan plan(boolean hasCounselorAccess) {
        return Plan.create(PlanName.STANDARD, 10, 1000, 3, false, hasCounselorAccess, false,
                BigDecimal.valueOf(99000));
    }

    private CounselTicket waitingTicket() {
        CounselTicket ticket = CounselTicket.request(USER_ID, 1);
        ReflectionTestUtils.setField(ticket, "id", TICKET_ID);
        return ticket;
    }

    private CounselTicket inProgressTicket() {
        CounselTicket ticket = waitingTicket();
        ReflectionTestUtils.setField(ticket, "status", CounselTicketStatus.IN_PROGRESS);
        ReflectionTestUtils.setField(ticket, "counselorId", COUNSELOR_ID);
        ReflectionTestUtils.setField(ticket, "sessionId", 700L);
        return ticket;
    }

    private static ChatSession withId(ChatSession session, Long id) {
        ReflectionTestUtils.setField(session, "id", id);
        return session;
    }
}
