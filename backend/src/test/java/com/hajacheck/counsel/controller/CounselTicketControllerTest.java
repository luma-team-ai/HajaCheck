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
import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselTicketStatus;
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
    void 티켓생성_상담원접근플랜_201_WAITING() throws Exception {
        Plan plan = saveCounselorPlan(true);
        User user = saveUser("ticket@haja.com", Role.USER);
        userPlanRepository.save(UserPlan.forUser(user.getId(), plan.getId()));

        mockMvc.perform(post("/api/counsel/tickets").with(csrf()).with(authentication(authOf(user))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.queuePosition").value(1));
    }

    @Test
    void 티켓생성_상담원접근불가플랜_403_COUNSEL_PLAN_REQUIRED() throws Exception {
        Plan plan = saveCounselorPlan(false);
        User user = saveUser("noaccess@haja.com", Role.USER);
        userPlanRepository.save(UserPlan.forUser(user.getId(), plan.getId()));

        mockMvc.perform(post("/api/counsel/tickets").with(csrf()).with(authentication(authOf(user))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COUNSEL_PLAN_REQUIRED"));
    }

    @Test
    void 티켓생성_미인증_401() throws Exception {
        mockMvc.perform(post("/api/counsel/tickets").with(csrf()))
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
        ticketRepository.save(CounselTicket.request(requester.getId(), 1));
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
        CounselTicket ticket = ticketRepository.save(CounselTicket.request(requester.getId(), 1));
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
        CounselTicket ticket = ticketRepository.save(CounselTicket.request(requester.getId(), 1));
        User plainUser = saveUser("plain2@haja.com", Role.USER);

        mockMvc.perform(post("/api/counsel/tickets/" + ticket.getId() + "/assign")
                        .with(csrf()).with(authentication(authOf(plainUser))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 종료_담당상담원본인_200_RESOLVED() throws Exception {
        User requester = saveUser("resolve-user@haja.com", Role.USER);
        CounselTicket ticket = ticketRepository.save(CounselTicket.request(requester.getId(), 1));
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
        CounselTicket ticket = ticketRepository.save(CounselTicket.request(requester.getId(), 1));
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
        CounselTicket ticket = ticketRepository.save(CounselTicket.request(owner.getId(), 1));

        mockMvc.perform(post("/api/counsel/tickets/" + ticket.getId() + "/leave-offline")
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OFFLINE_LEFT"));
    }

    @Test
    void 이탈_타인_404_TICKET_NOT_FOUND_열거방지() throws Exception {
        User owner = saveUser("leave-owner2@haja.com", Role.USER);
        CounselTicket ticket = ticketRepository.save(CounselTicket.request(owner.getId(), 1));
        User stranger = saveUser("stranger@haja.com", Role.USER);

        mockMvc.perform(post("/api/counsel/tickets/" + ticket.getId() + "/leave-offline")
                        .with(csrf()).with(authentication(authOf(stranger))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COUNSEL_TICKET_NOT_FOUND"));
    }
}
