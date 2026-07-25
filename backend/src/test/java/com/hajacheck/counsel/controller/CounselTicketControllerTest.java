package com.hajacheck.counsel.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.counsel.entity.BotScenario;
import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSenderType;
import com.hajacheck.counsel.entity.ChatSession;
import com.hajacheck.counsel.entity.ChatSessionType;
import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselTicketStatus;
import com.hajacheck.counsel.repository.BotScenarioRepository;
import com.hajacheck.counsel.repository.ChatMessageRepository;
import com.hajacheck.counsel.repository.ChatSessionRepository;
import com.hajacheck.counsel.repository.CounselTicketRepository;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전문 상담 티켓 MVC 통합 테스트(#20/HAJA-33) — 플랜 게이팅/role 경계/셀프-클레임/소유권.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CounselTicketControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private UserPlanRepository userPlanRepository;
    @Autowired
    private CounselTicketRepository ticketRepository;
    @Autowired
    private BotScenarioRepository botScenarioRepository;
    @Autowired
    private ChatSessionRepository chatSessionRepository;
    @Autowired
    private ChatMessageRepository chatMessageRepository;

    /** INSPECTION_REPORT 서브트리(root→mid→"상담원 연결" 리프)를 시드하고 리프 id 를 반환한다. */
    private Long saveCounselorLeafScenario() {
        BotScenario root = botScenarioRepository.save(
                BotScenario.create(null, "INSPECTION_REPORT", "점검 결과서 관련", null, false, 1));
        BotScenario mid = botScenarioRepository.save(
                BotScenario.create(root.getId(), "INSPECTION_REPORT", "AI 분석 결과 등급 문의", "안내", false, 1));
        BotScenario leaf = botScenarioRepository.save(
                BotScenario.create(mid.getId(), "INSPECTION_REPORT", "상담원 연결", null, true, 1));
        return leaf.getId();
    }

    private String createBody(Long scenarioId) {
        return "{\"scenarioId\":" + scenarioId + "}";
    }

    private Plan saveCounselorPlan(boolean hasCounselorAccess) {
        planRepository.findByName(PlanName.STANDARD).ifPresent(planRepository::delete);
        planRepository.flush();
        return planRepository.save(Plan.create(PlanName.STANDARD, 10, 1000, 3, false,
                hasCounselorAccess, false, BigDecimal.valueOf(99000)));
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

    // ── 생성 + 플랜 게이팅 ──

    @Test
    void 티켓생성_상담원접근플랜_시나리오스냅샷_201_WAITING() throws Exception {
        Plan plan = saveCounselorPlan(true);
        User user = saveUser("ticket@haja.com", Role.USER);
        userPlanRepository.save(UserPlan.forUser(user.getId(), plan.getId()));
        Long leafId = saveCounselorLeafScenario();

        mockMvc.perform(post("/api/counsel/tickets").with(csrf()).with(authentication(authOf(user)))
                        .contentType("application/json").content(createBody(leafId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.queuePosition").value(1))
                .andExpect(jsonPath("$.data.category").value("INSPECTION_REPORT"))
                .andExpect(jsonPath("$.data.title").value("AI 분석 결과 등급 문의"))
                .andExpect(jsonPath("$.data.ticketNumber").value(org.hamcrest.Matchers.startsWith("CS-")));
    }

    @Test
    void 티켓생성_리프아닌노드_403_TICKET_FORBIDDEN() throws Exception {
        Plan plan = saveCounselorPlan(true);
        User user = saveUser("nonleaf@haja.com", Role.USER);
        userPlanRepository.save(UserPlan.forUser(user.getId(), plan.getId()));
        // 중간 노드(leadsToCounselor=false)로 진입 시도.
        BotScenario root = botScenarioRepository.save(
                BotScenario.create(null, "INSPECTION_REPORT", "점검 결과서 관련", null, false, 1));
        BotScenario mid = botScenarioRepository.save(
                BotScenario.create(root.getId(), "INSPECTION_REPORT", "AI 분석 결과 등급 문의", "안내", false, 1));

        mockMvc.perform(post("/api/counsel/tickets").with(csrf()).with(authentication(authOf(user)))
                        .contentType("application/json").content(createBody(mid.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COUNSEL_TICKET_FORBIDDEN"));
    }

    @Test
    void 티켓생성_상담원접근불가플랜_403_COUNSEL_PLAN_REQUIRED() throws Exception {
        Plan plan = saveCounselorPlan(false);
        User user = saveUser("noaccess@haja.com", Role.USER);
        userPlanRepository.save(UserPlan.forUser(user.getId(), plan.getId()));

        mockMvc.perform(post("/api/counsel/tickets").with(csrf()).with(authentication(authOf(user)))
                        .contentType("application/json").content(createBody(999L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COUNSEL_PLAN_REQUIRED"));
    }

    @Test
    void 티켓생성_scenarioId누락_400() throws Exception {
        Plan plan = saveCounselorPlan(true);
        User user = saveUser("nobody-scenario@haja.com", Role.USER);
        userPlanRepository.save(UserPlan.forUser(user.getId(), plan.getId()));

        mockMvc.perform(post("/api/counsel/tickets").with(csrf()).with(authentication(authOf(user)))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 티켓생성_미인증_401() throws Exception {
        mockMvc.perform(post("/api/counsel/tickets").with(csrf())
                        .contentType("application/json").content(createBody(1L)))
                .andExpect(status().isUnauthorized());
    }

    // ── 대기열 role 경계 ──

    @Test
    void 대기열조회_일반사용자_403() throws Exception {
        User user = saveUser("plainuser@haja.com", Role.USER);

        mockMvc.perform(get("/api/counsel/tickets").with(authentication(authOf(user))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 대기열조회_상담원_200() throws Exception {
        Plan plan = saveCounselorPlan(true);
        User requester = saveUser("q-user@haja.com", Role.USER);
        userPlanRepository.save(UserPlan.forUser(requester.getId(), plan.getId()));
        ticketRepository.save(CounselTicket.request(requester.getId(), 1, "INSPECTION_REPORT", "AI 분석 결과 등급 문의"));
        User counselor = saveUser("counselor@haja.com", Role.COUNSELOR);

        mockMvc.perform(get("/api/counsel/tickets").with(authentication(authOf(counselor))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].status").value("WAITING"));
    }

    // ── 셀프-클레임 배정 + 종료 ──

    @Test
    void 배정_상담원셀프클레임_200_IN_PROGRESS() throws Exception {
        User requester = saveUser("assign-user@haja.com", Role.USER);
        CounselTicket ticket = ticketRepository.save(CounselTicket.request(requester.getId(), 1, "INSPECTION_REPORT", "AI 분석 결과 등급 문의"));
        User counselor = saveUser("assign-counselor@haja.com", Role.COUNSELOR);

        mockMvc.perform(post("/api/counsel/tickets/" + ticket.getId() + "/assign")
                        .with(csrf()).with(authentication(authOf(counselor))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.counselorId").value(counselor.getId()));

        CounselTicket assigned = ticketRepository.findById(ticket.getId()).orElseThrow();
        assertThat(assigned.getStatus()).isEqualTo(CounselTicketStatus.IN_PROGRESS);
        assertThat(assigned.getSessionId()).isNotNull();
    }

    @Test
    void 배정_일반사용자_403_role경계() throws Exception {
        User requester = saveUser("assign-user2@haja.com", Role.USER);
        CounselTicket ticket = ticketRepository.save(CounselTicket.request(requester.getId(), 1, "INSPECTION_REPORT", "AI 분석 결과 등급 문의"));
        User plainUser = saveUser("plain2@haja.com", Role.USER);

        mockMvc.perform(post("/api/counsel/tickets/" + ticket.getId() + "/assign")
                        .with(csrf()).with(authentication(authOf(plainUser))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 종료_담당상담원본인_200_RESOLVED() throws Exception {
        User requester = saveUser("resolve-user@haja.com", Role.USER);
        CounselTicket ticket = ticketRepository.save(CounselTicket.request(requester.getId(), 1, "INSPECTION_REPORT", "AI 분석 결과 등급 문의"));
        User counselor = saveUser("resolve-counselor@haja.com", Role.COUNSELOR);
        mockMvc.perform(post("/api/counsel/tickets/" + ticket.getId() + "/assign")
                        .with(csrf()).with(authentication(authOf(counselor))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/counsel/tickets/" + ticket.getId() + "/resolve")
                        .with(csrf()).with(authentication(authOf(counselor))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"));
    }

    @Test
    void 종료_담당아닌상담원_403_TICKET_FORBIDDEN() throws Exception {
        User requester = saveUser("resolve-user2@haja.com", Role.USER);
        CounselTicket ticket = ticketRepository.save(CounselTicket.request(requester.getId(), 1, "INSPECTION_REPORT", "AI 분석 결과 등급 문의"));
        User counselor = saveUser("resolve-counselor2@haja.com", Role.COUNSELOR);
        mockMvc.perform(post("/api/counsel/tickets/" + ticket.getId() + "/assign")
                        .with(csrf()).with(authentication(authOf(counselor))))
                .andExpect(status().isOk());
        User otherCounselor = saveUser("other-counselor@haja.com", Role.COUNSELOR);

        mockMvc.perform(post("/api/counsel/tickets/" + ticket.getId() + "/resolve")
                        .with(csrf()).with(authentication(authOf(otherCounselor))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COUNSEL_TICKET_FORBIDDEN"));
    }

    // ── 오프라인 이탈 소유권 ──

    @Test
    void 이탈_소유자본인_200_OFFLINE_LEFT() throws Exception {
        User owner = saveUser("leave-owner@haja.com", Role.USER);
        CounselTicket ticket = ticketRepository.save(CounselTicket.request(owner.getId(), 1, "INSPECTION_REPORT", "AI 분석 결과 등급 문의"));

        mockMvc.perform(post("/api/counsel/tickets/" + ticket.getId() + "/leave-offline")
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OFFLINE_LEFT"));
    }

    @Test
    void 이탈_타인_404_TICKET_NOT_FOUND_열거방지() throws Exception {
        User owner = saveUser("leave-owner2@haja.com", Role.USER);
        CounselTicket ticket = ticketRepository.save(CounselTicket.request(owner.getId(), 1, "INSPECTION_REPORT", "AI 분석 결과 등급 문의"));
        User stranger = saveUser("stranger@haja.com", Role.USER);

        mockMvc.perform(post("/api/counsel/tickets/" + ticket.getId() + "/leave-offline")
                        .with(csrf()).with(authentication(authOf(stranger))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COUNSEL_TICKET_NOT_FOUND"));
    }

    // ── 내 상담 이력 / 대화 조회 / 내보내기 (IDOR) ──

    @Test
    void 내이력_본인티켓만_반환() throws Exception {
        User me = saveUser("mine@haja.com", Role.USER);
        User other = saveUser("other-user@haja.com", Role.USER);
        ticketRepository.save(CounselTicket.request(me.getId(), 1, "INSPECTION_REPORT", "AI 분석 결과 등급 문의"));
        ticketRepository.save(CounselTicket.request(other.getId(), 2, "ACCOUNT_BILLING", "요금제 변경/해지"));

        mockMvc.perform(get("/api/counsel/tickets/mine").with(authentication(authOf(me))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].category").value("INSPECTION_REPORT"));
    }

    @Test
    void 대화조회_당사자_200() throws Exception {
        User requester = saveUser("msg-user@haja.com", Role.USER);
        User counselor = saveUser("msg-counselor@haja.com", Role.COUNSELOR);
        CounselTicket ticket = saveInProgressTicketWithMessage(requester, counselor, "상담 내용입니다");

        mockMvc.perform(get("/api/counsel/tickets/" + ticket.getId() + "/messages")
                        .with(authentication(authOf(requester))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].content").value("상담 내용입니다"));
    }

    @Test
    void 대화조회_비당사자_404_TICKET_NOT_FOUND() throws Exception {
        User requester = saveUser("msg-user2@haja.com", Role.USER);
        User counselor = saveUser("msg-counselor2@haja.com", Role.COUNSELOR);
        CounselTicket ticket = saveInProgressTicketWithMessage(requester, counselor, "비밀 대화");
        User intruder = saveUser("msg-intruder@haja.com", Role.USER);

        mockMvc.perform(get("/api/counsel/tickets/" + ticket.getId() + "/messages")
                        .with(authentication(authOf(intruder))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COUNSEL_TICKET_NOT_FOUND"));
    }

    @Test
    void 내보내기_당사자_텍스트다운로드() throws Exception {
        User requester = saveUser("export-user@haja.com", Role.USER);
        User counselor = saveUser("export-counselor@haja.com", Role.COUNSELOR);
        CounselTicket ticket = saveInProgressTicketWithMessage(requester, counselor, "내보낼 대화");

        mockMvc.perform(get("/api/counsel/tickets/" + ticket.getId() + "/export")
                        .with(authentication(authOf(requester))))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Disposition",
                                org.hamcrest.Matchers.containsString(".txt")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("내보낼 대화")));
    }

    @Test
    void 내보내기_비당사자_404() throws Exception {
        User requester = saveUser("export-user2@haja.com", Role.USER);
        User counselor = saveUser("export-counselor2@haja.com", Role.COUNSELOR);
        CounselTicket ticket = saveInProgressTicketWithMessage(requester, counselor, "비밀");
        User intruder = saveUser("export-intruder@haja.com", Role.USER);

        mockMvc.perform(get("/api/counsel/tickets/" + ticket.getId() + "/export")
                        .with(authentication(authOf(intruder))))
                .andExpect(status().isNotFound());
    }

    private CounselTicket saveInProgressTicketWithMessage(User requester, User counselor, String content) {
        ChatSession session = chatSessionRepository.save(
                ChatSession.start(requester.getId(), ChatSessionType.COUNSEL));
        CounselTicket ticket = ticketRepository.save(
                CounselTicket.request(requester.getId(), 1, "INSPECTION_REPORT", "AI 분석 결과 등급 문의"));
        ticket.assign(counselor.getId(), session);
        CounselTicket saved = ticketRepository.saveAndFlush(ticket);
        chatMessageRepository.save(
                ChatMessage.create(session.getId(), ChatSenderType.USER, content, null, null, null));
        return saved;
    }
}
