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
import com.hajacheck.membership.service.PaymentGraceService;
import com.hajacheck.membership.repository.UserPlanRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    private static final Long COMPANY_ID = 77L;
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
    @Mock
    private PaymentGraceService paymentGraceService;
    // 데모 계정 상담 티켓 생성 차단(#1631 security P2).
    @Mock
    private com.hajacheck.auth.service.DemoAccountGuard demoAccountGuard;

    private CounselTicketService service;

    @BeforeEach
    void setUp() {
        service = new CounselTicketService(ticketRepository, chatSessionRepository, chatMessageRepository,
                botScenarioRepository, counselorSkillRepository, userRepository, userPlanRepository, planRepository,
                paymentGraceService, companyMembershipRepository, messagingTemplate, demoAccountGuard);
    }

    /**
     * 미결제 유예(#1177) — 이 테스트들은 유예와 무관하므로 <b>항상 구독 요금제 그대로</b>를 돌려주도록
     * 스텁한다(유예가 아닐 때의 실제 동작과 같다). 유예 중 차단은 별도 회귀 테스트가 검증한다.
     */
    @BeforeEach
    void stubNoPaymentGrace() {
        when(paymentGraceService.resolveEffectivePlan(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
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
    void 티켓생성_미결제유예중이면_403_COUNSEL_PLAN_REQUIRED() {
        // #1177 리뷰 P1 회귀선 — 유료→유료 하향은 결제 없이 대상 요금제를 발급한다. 그 유예 구독의
        // plan_id 는 STANDARD 라 이 게이트가 원본 요금제를 그대로 읽으면 <b>상담사 연결을 무상으로</b>
        // 쓸 수 있다. QuotaService 가 낮추는 한도 3종과 달리 이 판정은 QuotaService 를 거치지 않으므로,
        // 여기서 PaymentGraceService 를 경유하지 않으면 막을 방법이 없다.
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(individualUser()));
        when(userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(USER_ID, UserPlanStatus.ACTIVE))
                .thenReturn(Optional.of(userPlan()));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan(true)));
        // 유예 중이면 엔타이틀먼트가 FREE 로 낮아진다(hasCounselorAccess=false).
        when(paymentGraceService.resolveEffectivePlan(any(), any())).thenReturn(plan(false));

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

    @Test
    void 티켓생성_데모계정은_플랜접근권과무관하게_409_DEMO_ACCOUNT_PROTECTED() {
        // #1631 security P2 — 결제 가드 해제로 데모 계정도 STANDARD/ENTERPRISE(hasCounselorAccess=true)를
        // 얻을 수 있지만, 상담 티켓 생성만은 실상담원 대기열 오염을 막기 위해 여전히 차단한다. 플랜 게이트
        // (requireCounselorAccess) 조회 전에 막혀야 하므로, 이후 조회가 전혀 일어나지 않았음도 함께 고정한다.
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.DEMO_ACCOUNT_PROTECTED))
                .when(demoAccountGuard).requireNotDemoAccountUser(USER_ID);

        assertThatThrownBy(() -> service.createTicket(USER_ID, SCENARIO_LEAF_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DEMO_ACCOUNT_PROTECTED);

        verify(userPlanRepository, never())
                .findFirstByUserIdAndStatusOrderByStartedAtDesc(any(), any());
        verify(botScenarioRepository, never()).findById(any());
        verify(ticketRepository, never()).saveAndFlush(any());
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

    // ── 고객 상담 이력(#1001 후속) ──

    @Test
    void 고객이력_담당상담원본인_현재티켓제외_최신순조회() {
        Pageable pageable = PageRequest.of(0, 20);
        CounselTicket current = inProgressTicket();
        CounselTicket past = CounselTicket.request(USER_ID, CounselType.USAGE, 2, "USAGE_GUIDE", "이용 방법");
        ReflectionTestUtils.setField(past, "id", 51L);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(current));
        when(ticketRepository.findByUserIdOrderByCreatedAtDesc(USER_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(current, past)));

        List<CounselTicketSummaryResponse> history = service.getCustomerHistory(TICKET_ID, COUNSELOR_ID, false);

        assertThat(history).extracting(CounselTicketSummaryResponse::id).containsExactly(51L);
    }

    @Test
    void 고객이력_담당아닌상담원_403_TICKET_FORBIDDEN() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(inProgressTicket()));

        assertThatThrownBy(() -> service.getCustomerHistory(TICKET_ID, 999L, false))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_TICKET_FORBIDDEN);
    }

    @Test
    void 고객이력_PLATFORM_ADMIN은_담당아니어도조회가능() {
        Pageable pageable = PageRequest.of(0, 20);
        CounselTicket current = inProgressTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(current));
        when(ticketRepository.findByUserIdOrderByCreatedAtDesc(USER_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(current)));

        List<CounselTicketSummaryResponse> history = service.getCustomerHistory(TICKET_ID, 999L, true);

        assertThat(history).isEmpty();
    }

    @Test
    void 고객이력대화_다른상담원이담당했던티켓도_현재담당티켓기준허용() {
        Long otherCounselorId = 11L;
        CounselTicket anchor = inProgressTicket();
        CounselTicket past = CounselTicket.request(USER_ID, CounselType.USAGE, 2, "USAGE_GUIDE", "이용 방법");
        ReflectionTestUtils.setField(past, "id", 51L);
        ReflectionTestUtils.setField(past, "counselorId", otherCounselorId);
        ReflectionTestUtils.setField(past, "sessionId", 800L);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(anchor));
        when(ticketRepository.findById(51L)).thenReturn(Optional.of(past));
        ChatMessage msg = ChatMessage.create(800L, ChatSenderType.COUNSELOR, "예전 상담 내용", null, null, null);
        when(chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(800L)).thenReturn(List.of(msg));

        List<ChatMessageResponse> messages =
                service.getCustomerHistoryMessages(TICKET_ID, 51L, COUNSELOR_ID, false);

        assertThat(messages).extracting(ChatMessageResponse::content).containsExactly("예전 상담 내용");
    }

    @Test
    void 고객이력대화_다른고객의티켓ID_404_TICKET_NOT_FOUND() {
        CounselTicket anchor = inProgressTicket();
        CounselTicket unrelated = CounselTicket.request(999L, CounselType.USAGE, 1, "USAGE_GUIDE", "이용 방법");
        ReflectionTestUtils.setField(unrelated, "id", 52L);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(anchor));
        when(ticketRepository.findById(52L)).thenReturn(Optional.of(unrelated));

        assertThatThrownBy(() -> service.getCustomerHistoryMessages(TICKET_ID, 52L, COUNSELOR_ID, false))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_TICKET_NOT_FOUND);
    }

    @Test
    void 고객이력대화_앵커티켓담당아닌상담원_403_TICKET_FORBIDDEN() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(inProgressTicket()));

        assertThatThrownBy(() -> service.getCustomerHistoryMessages(TICKET_ID, 51L, 999L, false))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_TICKET_FORBIDDEN);
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

        List<ChatMessageResponse> messages = service.getMessages(TICKET_ID, USER_ID, false);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).content()).isEqualTo("안녕하세요");
    }

    @Test
    void 대화조회_비당사자_platformAdmin_false면_404_TICKET_NOT_FOUND_열거방지_회귀() {
        CounselTicket ticket = inProgressTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.getMessages(TICKET_ID, 999L, false))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_TICKET_NOT_FOUND);
    }

    @Test
    void 대화조회_비당사자여도_platformAdmin_true면_조회허용() {
        CounselTicket ticket = inProgressTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        ChatMessage msg = ChatMessage.create(700L, ChatSenderType.USER, "관리자 조회 대상 대화", null, null, null);
        ReflectionTestUtils.setField(msg, "id", 6L);
        when(chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(700L)).thenReturn(List.of(msg));

        List<ChatMessageResponse> messages = service.getMessages(TICKET_ID, 999L, true);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).content()).isEqualTo("관리자 조회 대상 대화");
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
        // #1506 — 내보내기 텍스트는 raw enum(COUNSELOR/INSPECTION_REPORT/IN_PROGRESS) 대신 한글 라벨을
        // 쓴다. 담당 상담원 실명이 없으면(이 테스트는 userRepository에 COUNSELOR_ID를 스텁하지 않음)
        // "상담원"만 표기한다(CounselTicketService#exportSenderLabel 참고).
        assertThat(new String(transcript.content(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("CS-20260725-050")
                .contains("카테고리: 점검 결과서 관련")
                .contains("상태: 상담중")
                .contains("상담원: 안내드립니다");
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
    void 배정_성공시_상담원에게도_배정알림() {
        // #1506 — 사용자에게만 보내던 걸 상담원 콘솔도 받아야 "연결됨"이 즉시 반영된다.
        CounselTicket ticket = waitingTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(userRepository.existsById(COUNSELOR_ID)).thenReturn(true);
        when(counselorSkillRepository.existsById(new CounselorSkillId(COUNSELOR_ID, CounselType.ANALYSIS_RESULT)))
                .thenReturn(true);
        when(chatSessionRepository.save(any(ChatSession.class)))
                .thenAnswer(inv -> withId((ChatSession) inv.getArgument(0), 700L));
        when(ticketRepository.saveAndFlush(any(CounselTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        service.assignToCounselor(TICKET_ID, COUNSELOR_ID);

        verify(messagingTemplate).convertAndSendToUser(
                eq(String.valueOf(COUNSELOR_ID)), eq("/queue/counsel/assigned"), any(Object.class));
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
    void 종료_성공시_상담원에게_종료알림과_대기열갱신브로드캐스트() {
        // #1506 — 사용자만 알던 종료 사실을 담당 상담원 콘솔도 실시간으로 받아야 한다.
        // 진입 시점에 ticket.resolve()가 IN_PROGRESS만 허용하므로 counselorId는 항상 non-null.
        CounselTicket ticket = inProgressTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(chatSessionRepository.findById(700L)).thenReturn(Optional.of(withId(
                ChatSession.start(USER_ID, ChatSessionType.COUNSEL), 700L)));
        when(ticketRepository.saveAndFlush(any(CounselTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        service.resolve(TICKET_ID, COUNSELOR_ID, false);

        verify(messagingTemplate).convertAndSendToUser(
                eq(String.valueOf(USER_ID)), eq("/queue/counsel/ended"), any(Object.class));
        verify(messagingTemplate).convertAndSendToUser(
                eq(String.valueOf(COUNSELOR_ID)), eq("/queue/counsel/ended"), any(Object.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/counsel-queue"), eq("TICKET_RESOLVED"));
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
    void 이탈_담당상담원배정된_상태에서는_상담원에게_종료알림과_대기열갱신브로드캐스트() {
        // #1506 — 이전엔 leaveOffline이 아무에게도 알리지 않아 상담원 콘솔이 이탈 사실을 몰랐다.
        CounselTicket ticket = inProgressTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(ticketRepository.saveAndFlush(any(CounselTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        service.leaveOffline(TICKET_ID, USER_ID);

        verify(messagingTemplate).convertAndSendToUser(
                eq(String.valueOf(COUNSELOR_ID)), eq("/queue/counsel/ended"), any(Object.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/counsel-queue"), eq("TICKET_RESOLVED"));
    }

    @Test
    void 이탈_담당상담원없는_WAITING상태에서는_상담원알림_생략() {
        CounselTicket ticket = waitingTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(ticketRepository.saveAndFlush(any(CounselTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        service.leaveOffline(TICKET_ID, USER_ID);

        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any(Object.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/counsel-queue"), eq("TICKET_RESOLVED"));
    }

    // ── getTicket(#1506) ──

    @Test
    void 티켓조회_당사자_상태반환() {
        CounselTicket ticket = inProgressTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        CounselTicketResponse response = service.getTicket(TICKET_ID, USER_ID, false);

        assertThat(response.status()).isEqualTo(CounselTicketStatus.IN_PROGRESS);
    }

    @Test
    void 티켓조회_비당사자_404_TICKET_NOT_FOUND() {
        CounselTicket ticket = inProgressTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.getTicket(TICKET_ID, 999L, false))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_TICKET_NOT_FOUND);
    }

    @Test
    void 티켓조회_PLATFORM_ADMIN은_비당사자여도_조회허용() {
        CounselTicket ticket = inProgressTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        CounselTicketResponse response = service.getTicket(TICKET_ID, 999L, true);

        assertThat(response.status()).isEqualTo(CounselTicketStatus.IN_PROGRESS);
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

    // ── 플랫폼 관리자 날짜별 상담 목록(#1168) ──

    @Test
    void 관리자_날짜별목록_정상_고객프로필포함() {
        LocalDate date = LocalDate.of(2026, 7, 20);
        Pageable pageable = PageRequest.of(0, 20);
        CounselTicket ticket = waitingTicket();
        when(ticketRepository.findByCreatedAtBetweenOrderByCreatedAtDescIdDesc(
                eq(date.atStartOfDay()), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(ticket)));

        User customer = User.builder()
                .email("customer@haja.com").name("고객").role(Role.USER)
                .passwordHash("$2a$10$hashed").companyId(null).status(UserStatus.ACTIVE).build();
        ReflectionTestUtils.setField(customer, "id", USER_ID);
        ReflectionTestUtils.setField(customer, "createdAt", LocalDateTime.of(2026, 1, 1, 0, 0));
        when(userRepository.findAllById(any())).thenReturn(List.of(customer));

        UserPlan userPlan = UserPlan.forUser(USER_ID, PLAN_ID);
        when(userPlanRepository.findByUserIdInAndStatus(any(), eq(UserPlanStatus.ACTIVE)))
                .thenReturn(List.of(userPlan));
        Plan plan = plan(true);
        ReflectionTestUtils.setField(plan, "id", PLAN_ID);
        when(planRepository.findAllById(any())).thenReturn(List.of(plan));

        Page<CounselTicketSummaryResponse> page = service.getAdminTicketsByDate(date, pageable);

        assertThat(page.getContent()).hasSize(1);
        CounselTicketSummaryResponse summary = page.getContent().get(0);
        assertThat(summary.customerName()).isEqualTo("고객");
        assertThat(summary.customerEmail()).isEqualTo("customer@haja.com");
        assertThat(summary.customerPlan()).isEqualTo("STANDARD");
        assertThat(summary.customerJoinedAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    @Test
    void 관리자_날짜별목록_빈케이스() {
        LocalDate date = LocalDate.of(2026, 7, 20);
        Pageable pageable = PageRequest.of(0, 20);
        when(ticketRepository.findByCreatedAtBetweenOrderByCreatedAtDescIdDesc(any(), any(), eq(pageable)))
                .thenReturn(Page.empty(pageable));

        Page<CounselTicketSummaryResponse> page = service.getAdminTicketsByDate(date, pageable);

        assertThat(page.getContent()).isEmpty();
        verify(userRepository, never()).findAllById(any());
    }

    // ── 회사 소속 고객 요금제 표시(#1263) ──

    /**
     * 회귀 고정(#1263 P2-1) — user_plans 는 owner XOR 라 회사 귀속 구독 행은 user_id 가 NULL 이다.
     * 개인 구독만 조회하던 1차 구현에서는 회사 소속 고객의 요금제가 항상 null("-")로 떨어졌다
     * (상담 권한은 회사 구독에서 나오는데 화면만 미가입처럼 보임).
     */
    @Test
    void 관리자_날짜별목록_회사소속고객은_회사구독_요금제를_표시한다() {
        LocalDate date = LocalDate.of(2026, 7, 20);
        Pageable pageable = PageRequest.of(0, 20);
        when(ticketRepository.findByCreatedAtBetweenOrderByCreatedAtDescIdDesc(any(), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(waitingTicket())));
        when(userRepository.findAllById(any())).thenReturn(List.of(companyUser()));

        UserPlan companyPlan = UserPlan.forCompany(COMPANY_ID, PLAN_ID);
        when(userPlanRepository.findByCompanyIdInAndStatus(any(), eq(UserPlanStatus.ACTIVE)))
                .thenReturn(List.of(companyPlan));
        Plan plan = plan(true);
        ReflectionTestUtils.setField(plan, "id", PLAN_ID);
        when(planRepository.findAllById(any())).thenReturn(List.of(plan));

        Page<CounselTicketSummaryResponse> page = service.getAdminTicketsByDate(date, pageable);

        assertThat(page.getContent().get(0).customerPlan()).isEqualTo("STANDARD");
        // 회사 소속 고객은 개인 구독을 조회할 이유가 없다(빈 IN 절 쿼리도 나가지 않는다).
        verify(userPlanRepository, never()).findByUserIdInAndStatus(any(), any());
    }

    /**
     * 미결제 유예(#1177/#1263 P3) — 발급된 요금제 이름이 유료여도 실제 엔타이틀먼트는 FREE 다.
     * 관리자 화면이 원본 이름을 그대로 보여주면 표시("STANDARD")와 실권한(FREE)이 어긋난다.
     */
    @Test
    void 관리자_날짜별목록_미결제유예_고객은_실효요금제로_표시한다() {
        LocalDate date = LocalDate.of(2026, 7, 20);
        Pageable pageable = PageRequest.of(0, 20);
        when(ticketRepository.findByCreatedAtBetweenOrderByCreatedAtDescIdDesc(any(), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(waitingTicket())));
        when(userRepository.findAllById(any())).thenReturn(List.of(individualCustomer()));
        when(userPlanRepository.findByUserIdInAndStatus(any(), eq(UserPlanStatus.ACTIVE)))
                .thenReturn(List.of(UserPlan.forUser(USER_ID, PLAN_ID)));
        Plan paidPlan = plan(true);
        ReflectionTestUtils.setField(paidPlan, "id", PLAN_ID);
        when(planRepository.findAllById(any())).thenReturn(List.of(paidPlan));

        Plan freePlan = Plan.create(PlanName.FREE, 1, 10, 1, false, false, false, BigDecimal.ZERO);
        when(paymentGraceService.resolveEffectivePlan(any(), any())).thenReturn(freePlan);

        Page<CounselTicketSummaryResponse> page = service.getAdminTicketsByDate(date, pageable);

        assertThat(page.getContent().get(0).customerPlan()).isEqualTo("FREE");
    }

    /**
     * 같은 주체에 활성 구독 행이 둘 이상이어도 목록 전체가 500이 되지 않고 최신 구독(startedAt DESC)이
     * 표시돼야 한다(#1263) — 배치 조회의 {@code Collectors.toMap} 은 키 중복 시 기본이 예외다.
     */
    @Test
    void 관리자_날짜별목록_활성구독이_중복이면_최신구독을_쓴다() {
        LocalDate date = LocalDate.of(2026, 7, 20);
        Pageable pageable = PageRequest.of(0, 20);
        when(ticketRepository.findByCreatedAtBetweenOrderByCreatedAtDescIdDesc(any(), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(waitingTicket())));
        when(userRepository.findAllById(any())).thenReturn(List.of(individualCustomer()));

        Long newerPlanId = 101L;
        UserPlan older = UserPlan.forUser(USER_ID, PLAN_ID);
        ReflectionTestUtils.setField(older, "startedAt", Instant.parse("2026-01-01T00:00:00Z"));
        UserPlan newer = UserPlan.forUser(USER_ID, newerPlanId);
        ReflectionTestUtils.setField(newer, "startedAt", Instant.parse("2026-06-01T00:00:00Z"));
        when(userPlanRepository.findByUserIdInAndStatus(any(), eq(UserPlanStatus.ACTIVE)))
                .thenReturn(List.of(older, newer));

        Plan oldPlan = plan(true);
        ReflectionTestUtils.setField(oldPlan, "id", PLAN_ID);
        Plan newPlan = Plan.create(PlanName.ENTERPRISE, 50, 5000, 10, true, true, true,
                BigDecimal.valueOf(199000));
        ReflectionTestUtils.setField(newPlan, "id", newerPlanId);
        when(planRepository.findAllById(any())).thenReturn(List.of(oldPlan, newPlan));

        Page<CounselTicketSummaryResponse> page = service.getAdminTicketsByDate(date, pageable);

        assertThat(page.getContent().get(0).customerPlan()).isEqualTo("ENTERPRISE");
    }

    /**
     * 타임존 계약 회귀 고정(#1263) — 경계를 만드는 주체는 이 서비스다. createdAt 은 존 정보 없는
     * 서버 벽시계({@code @CreatedDate})이므로 조회 경계도 같은 벽시계여야 하고, JVM 기본 타임존이
     * 무엇이든 같은 값이 나가야 한다. 여기에 {@code ZoneId} 환산을 넣으면(저장값과 9시간 어긋나는
     * 수정) 이 단정이 깨진다 — 리포지토리를 직접 호출하던 기존 테스트는 이 회귀를 잡지 못했다.
     */
    @Test
    void 관리자_날짜별목록_조회경계는_JVM_기본타임존과_무관하다() {
        LocalDate date = LocalDate.of(2026, 7, 20);
        Pageable pageable = PageRequest.of(0, 20);
        when(ticketRepository.findByCreatedAtBetweenOrderByCreatedAtDescIdDesc(any(), any(), eq(pageable)))
                .thenReturn(Page.empty(pageable));

        TimeZone original = TimeZone.getDefault();
        try {
            // 배포 환경 TZ 고정(Asia/Seoul)이 빠진 상황을 모사 — 컨테이너 기본값 UTC.
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            service.getAdminTicketsByDate(date, pageable);
        } finally {
            TimeZone.setDefault(original);
        }

        ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(ticketRepository).findByCreatedAtBetweenOrderByCreatedAtDescIdDesc(
                start.capture(), end.capture(), eq(pageable));
        assertThat(start.getValue()).isEqualTo(LocalDateTime.of(2026, 7, 20, 0, 0, 0));
        // PG timestamp 는 마이크로초 정밀도 — 999_999_000ns(=999999µs)에서 끊어야 자정 캐리가 없다.
        assertThat(end.getValue()).isEqualTo(LocalDateTime.of(2026, 7, 20, 23, 59, 59, 999_999_000));
    }

    private User individualCustomer() {
        User customer = User.builder()
                .email("customer@haja.com").name("고객").role(Role.USER)
                .passwordHash("$2a$10$hashed").companyId(null).status(UserStatus.ACTIVE).build();
        ReflectionTestUtils.setField(customer, "id", USER_ID);
        ReflectionTestUtils.setField(customer, "createdAt", LocalDateTime.of(2026, 1, 1, 0, 0));
        return customer;
    }

    private User companyUser() {
        User customer = individualCustomer();
        ReflectionTestUtils.setField(customer, "companyId", COMPANY_ID);
        return customer;
    }
}
