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
import com.hajacheck.counsel.dto.ChatMessageResponse;
import com.hajacheck.counsel.dto.CounselTicketResponse;
import com.hajacheck.counsel.dto.CounselTicketSummaryResponse;
import com.hajacheck.counsel.entity.BotScenario;
import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSenderType;
import com.hajacheck.counsel.entity.ChatSession;
import com.hajacheck.counsel.entity.ChatSessionType;
import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselTicketStatus;
import com.hajacheck.counsel.entity.CounselType;
import com.hajacheck.counsel.entity.CounselorSkillId;
import com.hajacheck.counsel.repository.BotScenarioRepository;
import com.hajacheck.counsel.repository.ChatMessageRepository;
import com.hajacheck.counsel.repository.ChatSessionRepository;
import com.hajacheck.counsel.repository.CounselTicketRepository;
import com.hajacheck.counsel.repository.CounselorSkillRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * CounselTicketService 단위테스트 — 플랜 게이팅/시나리오 스냅샷/셀프-클레임 배정 경합/소유권·이력 IDOR(#20/HAJA-33).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CounselTicketServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long COUNSELOR_ID = 9L;
    private static final Long PLAN_ID = 100L;
    private static final Long TICKET_ID = 50L;
    private static final Long SCENARIO_LEAF_ID = 30L;

    @Mock
    private CounselTicketRepository ticketRepository;
    @Mock
    private ChatSessionRepository chatSessionRepository;
    @Mock
    private ChatMessageRepository chatMessageRepository;
    @Mock
    private BotScenarioRepository botScenarioRepository;
    @Mock
    private CounselorSkillRepository counselorSkillRepository;
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
        service = new CounselTicketService(ticketRepository, chatSessionRepository, chatMessageRepository,
                botScenarioRepository, counselorSkillRepository, userRepository, userPlanRepository, planRepository,
                companyMembershipRepository, messagingTemplate);
    }

    // ── createTicket: 플랜 게이팅 + 시나리오 스냅샷 ──

    @Test
    void 티켓생성_상담원접근플랜_시나리오스냅샷_티켓번호부여() {
        givenCounselorAccess(true);
        givenScenarioTree();
        when(ticketRepository.countByStatus(CounselTicketStatus.WAITING)).thenReturn(2L);
        when(ticketRepository.saveAndFlush(any(CounselTicket.class))).thenAnswer(inv -> {
            CounselTicket t = inv.getArgument(0);
            if (t.getId() == null) {
                ReflectionTestUtils.setField(t, "id", TICKET_ID);
                ReflectionTestUtils.setField(t, "createdAt", LocalDateTime.of(2026, 7, 25, 10, 0));
            }
            return t;
        });

        CounselTicketResponse response = service.createTicket(USER_ID, SCENARIO_LEAF_ID);

        assertThat(response.status()).isEqualTo(CounselTicketStatus.WAITING);
        assertThat(response.queuePosition()).isEqualTo(3);
        assertThat(response.category()).isEqualTo("INSPECTION_REPORT");
        assertThat(response.title()).isEqualTo("AI 분석 결과 등급 문의");
        assertThat(response.ticketNumber()).isEqualTo("CS-20260725-050");
    }

    @Test
    void 티켓생성_리프아닌노드_403_TICKET_FORBIDDEN_잘못된진입점() {
        givenCounselorAccess(true);
        BotScenario nonLeaf = scenario(SCENARIO_LEAF_ID, 20L, "INSPECTION_REPORT", "AI 분석 결과 등급 문의", false);
        when(botScenarioRepository.findById(SCENARIO_LEAF_ID)).thenReturn(Optional.of(nonLeaf));

        assertThatThrownBy(() -> service.createTicket(USER_ID, SCENARIO_LEAF_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_TICKET_FORBIDDEN);
        verify(ticketRepository, never()).saveAndFlush(any());
    }

    @Test
    void 티켓생성_매핑안된카테고리_500_COUNSEL_TYPE_MAPPING_NOT_FOUND() {
        givenCounselorAccess(true);
        BotScenario leaf = scenario(SCENARIO_LEAF_ID, null, "UNKNOWN_CATEGORY", "선택", true);
        when(botScenarioRepository.findById(SCENARIO_LEAF_ID)).thenReturn(Optional.of(leaf));

        assertThatThrownBy(() -> service.createTicket(USER_ID, SCENARIO_LEAF_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_TYPE_MAPPING_NOT_FOUND);
        verify(ticketRepository, never()).saveAndFlush(any());
    }

    @Test
    void 티켓생성_시나리오없음_404_SCENARIO_NOT_FOUND() {
        givenCounselorAccess(true);
        when(botScenarioRepository.findById(SCENARIO_LEAF_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createTicket(USER_ID, SCENARIO_LEAF_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_SCENARIO_NOT_FOUND);
    }

    @Test
    void 티켓생성_상담원접근불가플랜_403_COUNSEL_PLAN_REQUIRED() {
        givenCounselorAccess(false);

        assertThatThrownBy(() -> service.createTicket(USER_ID, SCENARIO_LEAF_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_PLAN_REQUIRED);
        verify(botScenarioRepository, never()).findById(any());
    }

    @Test
    void 티켓생성_활성플랜없음_403_COUNSEL_PLAN_REQUIRED() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser()));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createTicket(USER_ID, SCENARIO_LEAF_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_PLAN_REQUIRED);
    }

    // ── 내 상담 이력(IDOR: userId 는 세션 주체만) ──

    @Test
    void 내이력_전체_본인userId로만조회() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<CounselTicket> page = new PageImpl<>(List.of(waitingTicket()));
        when(ticketRepository.findByUserIdOrderByCreatedAtDesc(USER_ID, pageable)).thenReturn(page);

        service.getMyTickets(USER_ID, null, pageable);

        verify(ticketRepository).findByUserIdOrderByCreatedAtDesc(USER_ID, pageable);
        verify(ticketRepository, never()).findByUserIdAndStatusOrderByCreatedAtDesc(any(), any(), any());
    }

    @Test
    void 내이력_상태필터_본인userId로만조회() {
        Pageable pageable = PageRequest.of(0, 20);
        when(ticketRepository.findByUserIdAndStatusOrderByCreatedAtDesc(
                USER_ID, CounselTicketStatus.RESOLVED, pageable))
                .thenReturn(new PageImpl<>(List.of()));

        service.getMyTickets(USER_ID, CounselTicketStatus.RESOLVED, pageable);

        verify(ticketRepository).findByUserIdAndStatusOrderByCreatedAtDesc(
                USER_ID, CounselTicketStatus.RESOLVED, pageable);
    }

    // ── 상담원 이름 배치 조회(N+1 방지) — 페이지 내 서로 다른 상담원이 각자의 티켓에만 매핑되는지 ──

    @Test
    void 대기열조회_페이지내_서로다른상담원이름이_각티켓에정확히매핑() {
        Long otherCounselorId = 11L;
        CounselTicket ticketA = inProgressTicket();
        CounselTicket ticketB = waitingTicket();
        ReflectionTestUtils.setField(ticketB, "id", 51L);
        ReflectionTestUtils.setField(ticketB, "counselorId", otherCounselorId);
        Pageable pageable = PageRequest.of(0, 20);
        when(ticketRepository.findByStatusOrderByCreatedAtAsc(CounselTicketStatus.WAITING, pageable))
                .thenReturn(new PageImpl<>(List.of(ticketA, ticketB)));
        when(userRepository.findAllById(any())).thenReturn(List.of(
                counselorUser(COUNSELOR_ID, "김상담"), counselorUser(otherCounselorId, "이상담")));

        Page<CounselTicketSummaryResponse> page =
                service.getQueue(CounselTicketStatus.WAITING, pageable, COUNSELOR_ID, true);

        assertThat(page.getContent())
                .filteredOn(r -> r.id().equals(TICKET_ID)).extracting(CounselTicketSummaryResponse::counselorName)
                .containsExactly("김상담");
        assertThat(page.getContent())
                .filteredOn(r -> r.id().equals(51L)).extracting(CounselTicketSummaryResponse::counselorName)
                .containsExactly("이상담");
        verify(ticketRepository).findByStatusOrderByCreatedAtAsc(CounselTicketStatus.WAITING, pageable);
    }

    // ── 대기열 스킬 필터(#1019/HAJA-501) ──

    @Test
    void 대기열조회_PLATFORM_ADMIN_스킬무관전체노출() {
        Pageable pageable = PageRequest.of(0, 20);
        CounselTicket ticket = waitingTicket();
        when(ticketRepository.findByStatusOrderByCreatedAtAsc(CounselTicketStatus.WAITING, pageable))
                .thenReturn(new PageImpl<>(List.of(ticket)));

        Page<CounselTicketSummaryResponse> page =
                service.getQueue(CounselTicketStatus.WAITING, pageable, 999L, true);

        assertThat(page.getContent()).hasSize(1);
        verify(ticketRepository).findByStatusOrderByCreatedAtAsc(CounselTicketStatus.WAITING, pageable);
        verify(counselorSkillRepository, never()).findCounselTypesByCounselorId(any());
        verify(ticketRepository, never())
                .findByStatusAndCounselTypeInOrderByCreatedAtAsc(any(), any(), any());
    }

    @Test
    void 대기열조회_COUNSELOR_본인스킬밖티켓제외() {
        Pageable pageable = PageRequest.of(0, 20);
        when(counselorSkillRepository.findCounselTypesByCounselorId(COUNSELOR_ID))
                .thenReturn(List.of(CounselType.ANALYSIS_RESULT));
        CounselTicket ticket = waitingTicket();
        when(ticketRepository.findByStatusAndCounselTypeInOrderByCreatedAtAsc(
                CounselTicketStatus.WAITING, List.of(CounselType.ANALYSIS_RESULT), pageable))
                .thenReturn(new PageImpl<>(List.of(ticket)));

        Page<CounselTicketSummaryResponse> page =
                service.getQueue(CounselTicketStatus.WAITING, pageable, COUNSELOR_ID, false);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).id()).isEqualTo(TICKET_ID);
        verify(ticketRepository, never()).findByStatusOrderByCreatedAtAsc(any(), any());
    }

    @Test
    void 대기열조회_COUNSELOR_스킬여러개면모두포함() {
        Pageable pageable = PageRequest.of(0, 20);
        List<CounselType> skills = List.of(CounselType.ANALYSIS_RESULT, CounselType.USAGE);
        when(counselorSkillRepository.findCounselTypesByCounselorId(COUNSELOR_ID)).thenReturn(skills);
        CounselTicket ticketA = waitingTicket();
        CounselTicket ticketB = CounselTicket.request(USER_ID, CounselType.USAGE, 2, "USAGE_GUIDE", "이용 방법");
        ReflectionTestUtils.setField(ticketB, "id", 51L);
        when(ticketRepository.findByStatusAndCounselTypeInOrderByCreatedAtAsc(
                CounselTicketStatus.WAITING, skills, pageable))
                .thenReturn(new PageImpl<>(List.of(ticketA, ticketB)));

        Page<CounselTicketSummaryResponse> page =
                service.getQueue(CounselTicketStatus.WAITING, pageable, COUNSELOR_ID, false);

        assertThat(page.getContent()).extracting(CounselTicketSummaryResponse::id)
                .containsExactlyInAnyOrder(TICKET_ID, 51L);
    }

    @Test
    void 대기열조회_COUNSELOR_스킬없음_빈페이지() {
        Pageable pageable = PageRequest.of(0, 20);
        when(counselorSkillRepository.findCounselTypesByCounselorId(COUNSELOR_ID)).thenReturn(List.of());

        Page<CounselTicketSummaryResponse> page =
                service.getQueue(CounselTicketStatus.WAITING, pageable, COUNSELOR_ID, false);

        assertThat(page.getContent()).isEmpty();
        verify(ticketRepository, never())
                .findByStatusAndCounselTypeInOrderByCreatedAtAsc(any(), any(), any());
    }

    @Test
    void 대기열조회_COUNSELOR_IN_PROGRESS는_담당자본인기준_스킬무관() {
        Pageable pageable = PageRequest.of(0, 20);
        CounselTicket ticket = inProgressTicket();
        when(ticketRepository.findByStatusAndCounselorIdOrderByCreatedAtAsc(
                CounselTicketStatus.IN_PROGRESS, COUNSELOR_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(ticket)));

        Page<CounselTicketSummaryResponse> page =
                service.getQueue(CounselTicketStatus.IN_PROGRESS, pageable, COUNSELOR_ID, false);

        assertThat(page.getContent()).extracting(CounselTicketSummaryResponse::id).containsExactly(TICKET_ID);
        verify(counselorSkillRepository, never()).findCounselTypesByCounselorId(any());
    }

    private User counselorUser(Long id, String name) {
        User user = User.builder()
                .email(id + "@haja.com").name(name).role(Role.COUNSELOR)
                .passwordHash("$2a$10$hashed").companyId(null).status(UserStatus.ACTIVE).build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    // ── 대화 조회 IDOR ──

    @Test
    void 대화조회_당사자_메시지반환() {
        CounselTicket ticket = inProgressTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        ChatMessage msg = ChatMessage.create(700L, ChatSenderType.USER, "안녕하세요", null, null, null);
        ReflectionTestUtils.setField(msg, "id", 5L);
        when(chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(700L)).thenReturn(List.of(msg));

        List<ChatMessageResponse> messages = service.getMessages(TICKET_ID, USER_ID);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).content()).isEqualTo("안녕하세요");
    }

    @Test
    void 대화조회_비당사자_404_TICKET_NOT_FOUND_열거방지() {
        CounselTicket ticket = inProgressTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.getMessages(TICKET_ID, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_TICKET_NOT_FOUND);
    }

    @Test
    void 내보내기_당사자_트랜스크립트_파일명티켓번호() {
        CounselTicket ticket = inProgressTicket();
        ReflectionTestUtils.setField(ticket, "ticketNumber", "CS-20260725-050");
        ReflectionTestUtils.setField(ticket, "createdAt", LocalDateTime.of(2026, 7, 25, 10, 0));
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        ChatMessage msg = ChatMessage.create(700L, ChatSenderType.COUNSELOR, "안내드립니다", null, null, null);
        ReflectionTestUtils.setField(msg, "createdAt", LocalDateTime.of(2026, 7, 25, 10, 1));
        when(chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(700L)).thenReturn(List.of(msg));

        CounselTicketService.Transcript transcript = service.exportTranscript(TICKET_ID, COUNSELOR_ID);

        assertThat(transcript.fileName()).isEqualTo("CS-20260725-050.txt");
        assertThat(new String(transcript.content(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("CS-20260725-050").contains("COUNSELOR: 안내드립니다");
    }

    @Test
    void 내보내기_비당사자_404_TICKET_NOT_FOUND() {
        CounselTicket ticket = inProgressTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.exportTranscript(TICKET_ID, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_TICKET_NOT_FOUND);
    }

    // ── assign: 셀프-클레임 ──

    @Test
    void 배정_대기티켓_성공_사용자에게알림() {
        CounselTicket ticket = waitingTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(userRepository.existsById(COUNSELOR_ID)).thenReturn(true);
        when(counselorSkillRepository.existsById(new CounselorSkillId(COUNSELOR_ID, CounselType.ANALYSIS_RESULT)))
                .thenReturn(true);
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
        when(counselorSkillRepository.existsById(new CounselorSkillId(COUNSELOR_ID, CounselType.ANALYSIS_RESULT)))
                .thenReturn(true);
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
    void 배정_상담유형스킬없음_403_COUNSEL_SKILL_MISMATCH() {
        CounselTicket ticket = waitingTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(userRepository.existsById(COUNSELOR_ID)).thenReturn(true);
        when(counselorSkillRepository.existsById(new CounselorSkillId(COUNSELOR_ID, CounselType.ANALYSIS_RESULT)))
                .thenReturn(false);

        assertThatThrownBy(() -> service.assignToCounselor(TICKET_ID, COUNSELOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_SKILL_MISMATCH);
        verify(chatSessionRepository, never()).save(any());
    }

    @Test
    void 배정_티켓없음_404_TICKET_NOT_FOUND() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignToCounselor(TICKET_ID, COUNSELOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_TICKET_NOT_FOUND);
    }

    // ── resolve / leaveOffline 소유권 ──

    @Test
    void 종료_담당상담원본인_성공() {
        CounselTicket ticket = inProgressTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(chatSessionRepository.findById(700L)).thenReturn(Optional.of(withId(
                ChatSession.start(USER_ID, ChatSessionType.COUNSEL), 700L)));
        when(ticketRepository.saveAndFlush(any(CounselTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        CounselTicketResponse response = service.resolve(TICKET_ID, COUNSELOR_ID, false);

        assertThat(response.status()).isEqualTo(CounselTicketStatus.RESOLVED);
    }

    @Test
    void 종료_티켓소유고객본인_성공() {
        CounselTicket ticket = inProgressTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(chatSessionRepository.findById(700L)).thenReturn(Optional.of(withId(
                ChatSession.start(USER_ID, ChatSessionType.COUNSEL), 700L)));
        when(ticketRepository.saveAndFlush(any(CounselTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        CounselTicketResponse response = service.resolve(TICKET_ID, USER_ID, false);

        assertThat(response.status()).isEqualTo(CounselTicketStatus.RESOLVED);
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

    private void givenCounselorAccess(boolean hasAccess) {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser()));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan()));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan(hasAccess)));
    }

    private void givenScenarioTree() {
        BotScenario leaf = scenario(SCENARIO_LEAF_ID, 20L, "INSPECTION_REPORT", "상담원 연결", true);
        BotScenario parent = scenario(20L, 10L, "INSPECTION_REPORT", "AI 분석 결과 등급 문의", false);
        BotScenario root = scenario(10L, null, "INSPECTION_REPORT", "점검 결과서 관련", false);
        when(botScenarioRepository.findById(SCENARIO_LEAF_ID)).thenReturn(Optional.of(leaf));
        when(botScenarioRepository.findById(20L)).thenReturn(Optional.of(parent));
        when(botScenarioRepository.findById(10L)).thenReturn(Optional.of(root));
    }

    private BotScenario scenario(Long id, Long parentId, String category, String label, boolean leaf) {
        BotScenario scenario = BotScenario.create(parentId, category, label, "응답", leaf, 0);
        ReflectionTestUtils.setField(scenario, "id", id);
        return scenario;
    }

    private User individualUser() {
        User user = User.builder()
                .email("u@haja.com").name("사용자").role(Role.USER)
                .passwordHash("$2a$10$hashed").companyId(null).status(UserStatus.ACTIVE).build();
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
        CounselTicket ticket = CounselTicket.request(USER_ID, CounselType.ANALYSIS_RESULT, 1, "INSPECTION_REPORT", "AI 분석 결과 등급 문의");
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
