package com.hajacheck.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 플랜·쿼터 관리 API(#507) MVC 통합 테스트 — 전역 시큐리티 필터체인(/api/admin/** → hasRole(ADMIN))과
 * 회사 스코프·상속·플랜 변경 이력을 실 PostgreSQL(Testcontainers)에서 함께 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminPlanControllerTest extends PostgresTestSupport {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private CompanyMembershipRepository companyMembershipRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private UserPlanRepository userPlanRepository;

    // ── 인가(ADMIN role) 경계 ──

    @Test
    void 플랜조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/admin/plan"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 플랜조회_일반사용자_403() throws Exception {
        User user = saveUser(Role.USER, null);
        mockMvc.perform(get("/api/admin/plan").with(authentication(authOf(user))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 카탈로그조회_일반사용자_403() throws Exception {
        User user = saveUser(Role.USER, null);
        mockMvc.perform(get("/api/admin/plans").with(authentication(authOf(user))))
                .andExpect(status().isForbidden());
    }

    // ── 카탈로그 ──

    @Test
    void 카탈로그조회_관리자_200_전체요금제() throws Exception {
        seedPlans();
        User admin = saveUser(Role.ADMIN, null);
        mockMvc.perform(get("/api/admin/plans").with(authentication(authOf(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.plans.length()").value(3))
                .andExpect(jsonPath("$.data.plans[2].name").value("ENTERPRISE"))
                .andExpect(jsonPath("$.data.plans[2].maxSeats").value(nullValue()));
    }

    // ── 회사 스코프 + 상속 ──

    @Test
    void 현재플랜조회_승인된회사관리자_200() throws Exception {
        Fixture fx = approvedCompanyAdminWithPlan(PlanName.FREE);
        mockMvc.perform(get("/api/admin/plan").with(authentication(authOf(fx.admin()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.name").value("FREE"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.usage.analyzedImageCount").value(0));
    }

    @Test
    void 현재플랜조회_회사없는관리자_403() throws Exception {
        User admin = saveUser(Role.ADMIN, null);
        mockMvc.perform(get("/api/admin/plan").with(authentication(authOf(admin))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 현재플랜조회_승인멤버십없어도_200_companyId기준상속() throws Exception {
        // #887 방어 처리: 실제 가입 경로(CompanyAccountWriter)는 멤버십 행을 만들지 않으므로, 회사
        // 소속 관리자는 승인 멤버십이 전혀 없어도(PENDING 멤버십조차 없어도) users.company_id만으로
        // 회사 플랜을 정상 조회할 수 있어야 한다(원래는 여기서 404였다 — 그 결손이 이 이슈의 원인).
        Company company = saveApprovedCompany();
        User admin = saveUser(Role.ADMIN, company.getId());
        seedPlans();
        userPlanRepository.save(UserPlan.forCompany(company.getId(), planId(PlanName.FREE)));

        mockMvc.perform(get("/api/admin/plan").with(authentication(authOf(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.name").value("FREE"));
    }

    // ── 플랜 변경 + 이력 ──

    @Test
    void 플랜변경_FREE에서STANDARD_200_이력보존() throws Exception {
        Fixture fx = approvedCompanyAdminWithPlan(PlanName.FREE);

        mockMvc.perform(patch("/api/admin/plan")
                        .with(csrf()).with(authentication(authOf(fx.admin())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planName\":\"STANDARD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.name").value("STANDARD"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // 변경 이력: 최신순 = STANDARD(ACTIVE) → FREE(EXPIRED)
        mockMvc.perform(get("/api/admin/plan/history").with(authentication(authOf(fx.admin()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.history.length()").value(2))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.history[0].planName").value("STANDARD"))
                .andExpect(jsonPath("$.data.history[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.history[0].endedAt").doesNotExist())
                .andExpect(jsonPath("$.data.history[1].planName").value("FREE"))
                .andExpect(jsonPath("$.data.history[1].status").value("EXPIRED"))
                .andExpect(jsonPath("$.data.history[1].endedAt").exists());
    }

    @Test
    void 플랜변경이력조회_size로상한_totalElements는전체건수() throws Exception {
        // PR#525 머신 리뷰 P3: 이력이 페이지 크기를 초과해도 content 는 상한만, totalElements 는 전체 수.
        Fixture fx = approvedCompanyAdminWithPlan(PlanName.FREE);
        mockMvc.perform(patch("/api/admin/plan")
                        .with(csrf()).with(authentication(authOf(fx.admin())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planName\":\"STANDARD\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/admin/plan")
                        .with(csrf()).with(authentication(authOf(fx.admin())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planName\":\"ENTERPRISE\"}"))
                .andExpect(status().isOk());
        // 이 시점 이력 3건(FREE→STANDARD→ENTERPRISE). size=2 로 조회하면 content 는 2건, totalElements 는 3.

        mockMvc.perform(get("/api/admin/plan/history")
                        .param("size", "2")
                        .with(authentication(authOf(fx.admin()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.history.length()").value(2))
                .andExpect(jsonPath("$.data.history[0].planName").value("ENTERPRISE"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    @Test
    void 플랜변경_동일요금제_200_멱등_이력추가없음() throws Exception {
        Fixture fx = approvedCompanyAdminWithPlan(PlanName.STANDARD);

        mockMvc.perform(patch("/api/admin/plan")
                        .with(csrf()).with(authentication(authOf(fx.admin())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planName\":\"STANDARD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.name").value("STANDARD"));

        // 동일 요금제 재지정은 no-op → 이력은 여전히 1건(초기 ACTIVE)만.
        mockMvc.perform(get("/api/admin/plan/history").with(authentication(authOf(fx.admin()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.history.length()").value(1));
    }

    @Test
    void 플랜변경_일반사용자_403() throws Exception {
        User user = saveUser(Role.USER, null);
        mockMvc.perform(patch("/api/admin/plan")
                        .with(csrf()).with(authentication(authOf(user)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planName\":\"STANDARD\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 플랜변경_비소유자ADMIN멤버_403_결제게이트우회차단() throws Exception {
        // PR#525 머신 리뷰 P1: 승인된 멤버십만으로는 changePlan(즉시 ACTIVE 발급, 무결제)을 허용해선
        // 안 된다 — requestUpgrade 와 동일하게 회사 소유자(owner)만 허용해야 한다.
        Company company = saveApprovedCompany();
        User nonOwnerAdmin = saveUser(Role.ADMIN, company.getId());
        CompanyMembership membership = CompanyMembership.invite(
                company.getId(), nonOwnerAdmin.getId(), company.getOwnerUserId(), null);
        membership.approve();
        companyMembershipRepository.save(membership);
        seedPlans();
        userPlanRepository.save(UserPlan.forCompany(company.getId(), planId(PlanName.FREE)));

        mockMvc.perform(patch("/api/admin/plan")
                        .with(csrf()).with(authentication(authOf(nonOwnerAdmin)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planName\":\"ENTERPRISE\"}"))
                .andExpect(status().isForbidden());
    }

    // ── 유지할 구성원 직접 선택(#890 Phase 2, keepUserIds) — 여기가 뚫리면 계정 정지를 남이
    // 조종할 수 있으므로 실 PostgreSQL로 회사 스코프·좌석 초과·owner 보호를 끝까지 검증한다.

    @Test
    void 플랜변경_keepUserIds지정하면_선택된인원만_유지되고_나머지가정지된다() throws Exception {
        Fixture fx = approvedCompanyAdminWithPlan(PlanName.ENTERPRISE);
        User m1 = saveUser(Role.USER, fx.company().getId());
        User m2 = saveUser(Role.USER, fx.company().getId());
        User m3 = saveUser(Role.USER, fx.company().getId());
        User m4 = saveUser(Role.USER, fx.company().getId());
        User m5 = saveUser(Role.USER, fx.company().getId());
        // owner + 5명 = 6명 활성 — STANDARD(시드값 기준 좌석 3, HajaCheck_script.sql SOT)로 내리면
        // 좌석이 넘친다. 값을 하드코딩하지 않고 실측한다(시드가 바뀌어도 이 테스트의 전제가 깨지지
        // 않게, 위 플랜쿼터목록조회 테스트들과 동일 전략).
        Integer standardMaxSeats = planRepository.findByName(PlanName.STANDARD)
                .orElseThrow().getMaxSeats();
        assertThat(standardMaxSeats).isLessThan(6);

        // keepUserIds=[m3] 만 명시 선택 — "선택된 인원만 유지되고 나머지가 정지"(좌석에 여유가 있어도
        // 자동으로 채우지 않는다, 핸드오프 §2 확정 정책)이므로 owner+m3 딱 2명만 유지되고 나머지
        // 4명(m1,m2,m4,m5)이 전부 정지된다.
        mockMvc.perform(get("/api/admin/plan/change-preview")
                        .param("planName", "STANDARD")
                        .param("keepUserIds", String.valueOf(m3.getId()))
                        .with(authentication(authOf(fx.admin()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresConfirmation").value(true))
                .andExpect(jsonPath("$.data.seatsToSuspend[*].userId")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(
                                m1.getId().intValue(), m2.getId().intValue(),
                                m4.getId().intValue(), m5.getId().intValue())));

        mockMvc.perform(patch("/api/admin/plan")
                        .with(csrf()).with(authentication(authOf(fx.admin())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planName\":\"STANDARD\",\"confirmOverflow\":true,\"keepUserIds\":["
                                + m3.getId() + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.name").value("STANDARD"));

        assertThat(userRepository.findById(m1.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.SUSPENDED);
        assertThat(userRepository.findById(m2.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.SUSPENDED);
        assertThat(userRepository.findById(m4.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.SUSPENDED);
        assertThat(userRepository.findById(m5.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.SUSPENDED);
        assertThat(userRepository.findById(m3.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.ACTIVE);
        assertThat(userRepository.findById(fx.admin().getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void 플랜변경_타회사id를_keepUserIds로주입하면_403_거절되고_플랜은그대로다() throws Exception {
        Fixture fx = approvedCompanyAdminWithPlan(PlanName.ENTERPRISE);
        saveUser(Role.USER, fx.company().getId());
        saveUser(Role.USER, fx.company().getId());
        saveUser(Role.USER, fx.company().getId());

        // 다른 회사 소속 사용자 id를 유지 대상으로 주입 — 부작용이 전혀 없어야 한다.
        Fixture other = approvedCompanyAdminWithPlan(PlanName.FREE);
        Long strangerId = other.admin().getId();

        mockMvc.perform(get("/api/admin/plan/change-preview")
                        .param("planName", "STANDARD")
                        .param("keepUserIds", String.valueOf(strangerId))
                        .with(authentication(authOf(fx.admin()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PLAN_KEEP_USER_INVALID"));

        // confirmOverflow 는 일부러 지정하지 않는다(false 취급) — keepUserIds 검증은 "명시적 확인" 여부와
        // 무관하게 항상 적용돼야 하고, 검증이 만료(expire)/신규발급(saveAndFlush) **이전**에 먼저 걸려야
        // "부작용 0"이 성립한다(#890 Phase 2 핵심). confirmOverflow:true로 확인부터 건너뛰면 검증이
        // applyOverflow까지 미뤄져 만료·신규발급 SQL이 먼저 나가버린다 — 운영에서는 그 요청의 트랜잭션이
        // 그대로 롤백돼 문제가 없지만, 이 테스트처럼 같은 트랜잭션 안에서 곧바로 재조회하면(테스트 클래스
        // @Transactional 하나로 묶여 있어 요청 간에도 같은 커넥션을 공유) 아직 커밋되지 않은 값을 그대로
        // 읽어버려 "부작용 없음" 검증 자체가 성립하지 않는다.
        mockMvc.perform(patch("/api/admin/plan")
                        .with(csrf()).with(authentication(authOf(fx.admin())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planName\":\"STANDARD\",\"keepUserIds\":[" + strangerId + "]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PLAN_KEEP_USER_INVALID"));

        // 부작용 0 — 플랜은 여전히 ENTERPRISE, 아무도 정지되지 않았다.
        mockMvc.perform(get("/api/admin/plan").with(authentication(authOf(fx.admin()))))
                .andExpect(jsonPath("$.data.plan.name").value("ENTERPRISE"));
    }

    @Test
    void 플랜변경_선택인원이_좌석수를넘으면_403_거절된다() throws Exception {
        Fixture fx = approvedCompanyAdminWithPlan(PlanName.ENTERPRISE);
        User m1 = saveUser(Role.USER, fx.company().getId());
        User m2 = saveUser(Role.USER, fx.company().getId());
        User m3 = saveUser(Role.USER, fx.company().getId());

        // FREE 좌석 한도(실측, 하드코딩 금지)보다 owner+3명 유지 선택이 확실히 넘치게 만든다.
        Integer freeMaxSeats = planRepository.findByName(PlanName.FREE).orElseThrow().getMaxSeats();
        assertThat(freeMaxSeats).isLessThan(4);
        String keepUserIds = "[" + m1.getId() + "," + m2.getId() + "," + m3.getId() + "]";

        // confirmOverflow 미지정 — 위 테스트와 동일한 이유로, 검증이 만료/신규발급보다 먼저 걸려야
        // 이 테스트 안에서 "부작용 0"을 그대로 검증할 수 있다.
        mockMvc.perform(patch("/api/admin/plan")
                        .with(csrf()).with(authentication(authOf(fx.admin())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planName\":\"FREE\",\"keepUserIds\":" + keepUserIds + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PLAN_SEAT_QUOTA_EXCEEDED"));

        // 부작용 0 — 플랜 그대로, 아무도 정지되지 않았다.
        mockMvc.perform(get("/api/admin/plan").with(authentication(authOf(fx.admin()))))
                .andExpect(jsonPath("$.data.plan.name").value("ENTERPRISE"));
        assertThat(userRepository.findById(m1.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void 플랜변경_keepUserIds미지정이면_기존id오름차순_동작그대로다() throws Exception {
        // 재검토 F-6 — 기존 테스트는 활성 3명 vs STANDARD 3석이라 "초과 없음" 조기 반환에 걸려 정지
        // 로직 자체가 실행되지 않았다(유일한 단정 "m1 ACTIVE"가 정지 로직을 통째로 지워도 통과하는
        // 거짓 양성). 좌석 수를 실측해(하드코딩 금지) 정확히 좌석+1명을 활성으로 만들어 실제 초과를
        // 강제하고, "kept = owner + id 오름차순으로 좌석 수만큼"이라는 알고리즘 자체를 단정한다.
        Fixture fx = approvedCompanyAdminWithPlan(PlanName.ENTERPRISE);
        Integer standardMaxSeats = planRepository.findByName(PlanName.STANDARD)
                .orElseThrow().getMaxSeats();
        // owner 1석을 빼면 나머지 인원용 좌석은 (standardMaxSeats - 1) — id가 가장 작은 m1과 그 다음
        // (standardMaxSeats - 2)명(middle)까지가 유지 대상이 되려면 좌석이 최소 2석은 있어야 한다.
        assertThat(standardMaxSeats).isGreaterThanOrEqualTo(2);

        // 생성 순서 = id 오름차순: m1(가장 먼저) → middle(좌석 안에 들어가는 나머지) → overflow(좌석 밖,
        // 유일하게 정지될 1명). 활성 총원 = owner(1) + m1(1) + middle(standardMaxSeats-2) + overflow(1)
        //            = standardMaxSeats + 1 → 좌석을 정확히 1명 초과한다.
        User m1 = saveUser(Role.USER, fx.company().getId());
        java.util.List<User> middle = new java.util.ArrayList<>();
        for (int i = 0; i < standardMaxSeats - 2; i++) {
            middle.add(saveUser(Role.USER, fx.company().getId()));
        }
        User overflow = saveUser(Role.USER, fx.company().getId());

        mockMvc.perform(patch("/api/admin/plan")
                        .with(csrf()).with(authentication(authOf(fx.admin())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planName\":\"STANDARD\",\"confirmOverflow\":true}"))
                .andExpect(status().isOk());

        // keepUserIds 를 아예 보내지 않아도(하위 호환) id 오름차순 자동 선정이 그대로 동작한다 —
        // owner + m1 + middle 전원이 유지되고, 좌석 밖으로 밀려난 overflow 1명만 정지된다.
        assertThat(userRepository.findById(fx.admin().getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.ACTIVE);
        assertThat(userRepository.findById(m1.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.ACTIVE);
        for (User mid : middle) {
            assertThat(userRepository.findById(mid.getId()).orElseThrow().getStatus())
                    .isEqualTo(UserStatus.ACTIVE);
        }
        assertThat(userRepository.findById(overflow.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.SUSPENDED);
    }

    // ── 회사 멤버별 쿼터 목록(#525 팔로우업 — PR머신 P2: 이 엔드포인트가 테스트에서 전혀 검증되지 않았음) ──

    @Test
    void 플랜쿼터목록조회_활성구독있음_멤버별플랜값과stats() throws Exception {
        Fixture fx = approvedCompanyAdminWithPlan(PlanName.STANDARD);
        User member = saveUser(Role.USER, fx.company().getId());
        // HajaCheck_script.sql(#517/HAJA-308)이 신규 설치 시드를 이미 심어두므로 seedPlans()는
        // no-op이다 — 값을 하드코딩하면 시드가 바뀔 때마다 깨지니 실제 값을 조회해 비교한다.
        Integer standardQuotaLimit = planRepository.findByName(PlanName.STANDARD)
                .orElseThrow().getMaxMonthlyAnalyses();

        mockMvc.perform(get("/api/admin/plan-quota").with(authentication(authOf(fx.admin()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].plan").value("STANDARD"))
                .andExpect(jsonPath("$.data.content[0].quotaLimit").value(standardQuotaLimit))
                .andExpect(jsonPath("$.data.content[1].email").value(member.getEmail()))
                .andExpect(jsonPath("$.data.content[1].plan").value("STANDARD"))
                .andExpect(jsonPath("$.data.content[1].quotaLimit").value(standardQuotaLimit))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.stats.activeUsers").value(2))
                .andExpect(jsonPath("$.data.stats.companyPlan").value("STANDARD"));
    }

    @Test
    void 플랜쿼터목록조회_활성구독없음_plan과quotaLimit이null이어도200() throws Exception {
        // 회사·멤버십은 유효하지만 user_plans 자체가 없는 상태(getCurrentPlan과 달리 404로 실패시키지 않는다).
        Company company = saveApprovedCompany();
        User admin = saveUser(Role.ADMIN, company.getId());
        companyMembershipRepository.save(CompanyMembership.approvedOwner(company.getId(), admin.getId()));

        mockMvc.perform(get("/api/admin/plan-quota").with(authentication(authOf(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].plan").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].quotaLimit").doesNotExist())
                .andExpect(jsonPath("$.data.stats.companyPlan").doesNotExist());
    }

    @Test
    void 플랜쿼터목록조회_멤버십행자체없음_실가입흐름재현_200() throws Exception {
        // #887 핵심 재현 케이스: 실제 CompanyAccountWriter.createAccount()는 CompanyMembership 행을
        // 아예 만들지 않는다 — 이 테스트는 companyMembershipRepository.save()를 전혀 호출하지 않아
        // 그 실제 상태를 그대로 재현한다. 이전에는 여기서 PLAN_NOT_FOUND(404)가 그대로 터졌다.
        Company company = saveApprovedCompany();
        User admin = saveUser(Role.ADMIN, company.getId());
        seedPlans();
        userPlanRepository.save(UserPlan.forCompany(company.getId(), planId(PlanName.STANDARD)));
        Integer standardQuotaLimit = planRepository.findByName(PlanName.STANDARD)
                .orElseThrow().getMaxMonthlyAnalyses();

        mockMvc.perform(get("/api/admin/plan-quota").with(authentication(authOf(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].plan").value("STANDARD"))
                .andExpect(jsonPath("$.data.content[0].quotaLimit").value(standardQuotaLimit))
                .andExpect(jsonPath("$.data.stats.companyPlan").value("STANDARD"));
    }

    @Test
    void 플랜쿼터목록조회_keyword로_이름검색() throws Exception {
        Fixture fx = approvedCompanyAdminWithPlan(PlanName.FREE);
        User target = userRepository.save(User.builder()
                .email("target-member@haja.com")
                .name("검색대상")
                .role(Role.USER)
                .passwordHash("$2a$10$hashed")
                .companyId(fx.company().getId())
                .status(UserStatus.ACTIVE)
                .build());

        mockMvc.perform(get("/api/admin/plan-quota")
                        .param("keyword", "검색대상")
                        .with(authentication(authOf(fx.admin()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].email").value(target.getEmail()))
                // stats는 검색어와 무관하게 회사 전체 기준(관리자 포함 2명)이어야 한다.
                .andExpect(jsonPath("$.data.stats.activeUsers").value(2));
    }

    @Test
    void 플랜쿼터목록조회_size0이면400() throws Exception {
        Fixture fx = approvedCompanyAdminWithPlan(PlanName.FREE);
        mockMvc.perform(get("/api/admin/plan-quota")
                        .param("size", "0")
                        .with(authentication(authOf(fx.admin()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 플랜쿼터목록조회_일반사용자_403() throws Exception {
        User user = saveUser(Role.USER, null);
        mockMvc.perform(get("/api/admin/plan-quota").with(authentication(authOf(user))))
                .andExpect(status().isForbidden());
    }

    // ── 픽스처 헬퍼 ──

    private record Fixture(User admin, Company company) {
    }

    // fx.admin() 은 changePlan(owner 한정, PR#525 P1 픽스) 테스트에도 재사용되므로 실제 company.ownerUserId
    // 와 동일한 사용자로 만든다 — owner 아닌 승인 멤버는 별도 픽스처(플랜변경_비소유자ADMIN멤버_403...)로 다룬다.
    private Fixture approvedCompanyAdminWithPlan(PlanName planName) {
        int n = SEQ.incrementAndGet();
        User admin = saveUser(Role.ADMIN, null);
        Company company = Company.createPendingReview(
                admin.getId(), "회사" + n, "BRN-507-" + n, "대표", "서울", null,
                "https://files.example/brn.pdf", "{\"source\":\"MANUAL_INPUT\"}");
        company.markBusinessVerified();
        company.approve(admin.getId());
        company = companyRepository.save(company);
        admin.assignToCompany(company.getId());
        admin = userRepository.save(admin);
        companyMembershipRepository.save(
                CompanyMembership.approvedOwner(company.getId(), admin.getId()));
        seedPlans();
        userPlanRepository.save(UserPlan.forCompany(company.getId(), planId(planName)));
        return new Fixture(admin, company);
    }

    private Company saveApprovedCompany() {
        int n = SEQ.incrementAndGet();
        User owner = saveUser(Role.ADMIN, null);
        Company company = Company.createPendingReview(
                owner.getId(), "회사" + n, "BRN-507-" + n, "대표", "서울", null,
                "https://files.example/brn.pdf", "{\"source\":\"MANUAL_INPUT\"}");
        company.markBusinessVerified();
        company.approve(owner.getId());
        return companyRepository.save(company);
    }

    private User saveUser(Role role, Long companyId) {
        int n = SEQ.incrementAndGet();
        return userRepository.save(User.builder()
                .email("admin-plan-" + n + "@haja.com")
                .name("관리자" + n)
                .role(role)
                .passwordHash("$2a$10$hashed")
                .companyId(companyId)
                .status(UserStatus.ACTIVE)
                .build());
    }

    private void seedPlans() {
        if (planRepository.findByName(PlanName.FREE).isPresent()) {
            return;
        }
        planRepository.save(Plan.create(PlanName.FREE, 1, 30, 1, true, false, false, BigDecimal.ZERO));
        planRepository.save(Plan.create(
                PlanName.STANDARD, 10, 300, 5, false, true, true, new BigDecimal("29000.00")));
        planRepository.save(Plan.create(
                PlanName.ENTERPRISE, null, null, 50, false, true, true, new BigDecimal("99000.00")));
    }

    private Long planId(PlanName name) {
        return planRepository.findByName(name).orElseThrow().getId();
    }

    private UsernamePasswordAuthenticationToken authOf(User user) {
        LoginUser principal = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
