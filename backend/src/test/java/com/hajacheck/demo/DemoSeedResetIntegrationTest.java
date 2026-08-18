package com.hajacheck.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.config.DemoProperties;
import com.hajacheck.auth.entity.BusinessVerificationStatus;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.CompanyAccountWriter;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.report.entity.Report;
import com.hajacheck.core.report.repository.ReportRepository;
import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSenderType;
import com.hajacheck.counsel.entity.ChatSession;
import com.hajacheck.counsel.entity.ChatSessionType;
import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselTicketNote;
import com.hajacheck.counsel.entity.CounselType;
import com.hajacheck.counsel.repository.ChatMessageRepository;
import com.hajacheck.counsel.repository.ChatSessionRepository;
import com.hajacheck.counsel.repository.CounselTicketNoteRepository;
import com.hajacheck.counsel.repository.CounselTicketRepository;
import com.hajacheck.demo.init.DemoDataSeeder;
import com.hajacheck.demo.repository.DemoResetRepository;
import com.hajacheck.demo.service.DemoResetService;
import com.hajacheck.demo.service.DemoSeedService;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UsageCounter;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UsageCounterRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.support.PostgresTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * 데모 시드·리셋(#1626) 통합 테스트 — 실 PG(Testcontainers)에서 다음을 고정한다:
 * ① 시더 멱등성(두 번 실행해도 중복 없음) ② 리셋의 <b>회사 스코프 격리</b>(타 회사 데이터 무접촉 —
 * destructive 안전장치의 핵심 증명) ③ 리셋 후 시드 상태 복원 ④ owner 불일치 시 아무것도 지우지 않는
 * 설정 실수 방어.
 *
 * <p>{@code app.demo-seed.enabled} 는 기본 false 로 두고(컨텍스트 기동 시 러너가 공유 DB 에 커밋하는
 * 것을 피한다) 시더는 테스트 안에서 로컬 인스턴스로 실행한다 — 모든 데이터는 테스트 트랜잭션과 함께
 * 롤백된다. 비밀번호 프로퍼티는 테스트 더미값이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DemoSeedResetIntegrationTest extends PostgresTestSupport {

    private static final String DEMO_LOGIN_ID = "demo-seed-it@hajacheck.demo";
    private static final int SEEDED_FACILITIES = 3;
    private static final int SEEDED_MEDIA_ROWS = 5;

    @Autowired
    private DemoProperties demoProperties;
    @Autowired
    private DemoSeedService demoSeedService;
    @Autowired
    private DemoResetService demoResetService;
    @Autowired
    private DemoResetRepository demoResetRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private FacilityRepository facilityRepository;
    @Autowired
    private CompanyAccountWriter companyAccountWriter;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private ReportRepository reportRepository;
    @Autowired
    private ChatSessionRepository chatSessionRepository;
    @Autowired
    private ChatMessageRepository chatMessageRepository;
    @Autowired
    private CounselTicketRepository counselTicketRepository;
    @Autowired
    private CounselTicketNoteRepository counselTicketNoteRepository;
    @Autowired
    private UserPlanRepository userPlanRepository;
    @Autowired
    private UsageCounterRepository usageCounterRepository;
    @Autowired
    private PlanRepository planRepository;
    @PersistenceContext
    private EntityManager entityManager;

    private String originalLoginId;
    private String originalPassword;

    /**
     * ⚠️ 새 컨텍스트를 만들지 않는다 — {@code @SpringBootTest(properties=...)} 로 데모 설정을 주면 이
     * 클래스만의 컨텍스트(+Hikari 풀)가 캐시에 쌓여 PG 테스트컨테이너 max_connections(100) 를 넘긴다
     * (실측: 무관한 테스트가 "too many clients already" 로 붕괴). 기본 컨텍스트의 {@link DemoProperties}
     * 빈을 테스트 중에만 변경하고 반드시 원복한다(DemoLoginIntegrationTest 와 동일 패턴).
     */
    @BeforeEach
    void setUp() {
        originalLoginId = demoProperties.getLoginId();
        originalPassword = demoProperties.getAdminPassword();
        demoProperties.setLoginId(DEMO_LOGIN_ID);
        demoProperties.setAdminPassword("demo-it-dummy1");
    }

    @AfterEach
    void tearDown() {
        demoProperties.setLoginId(originalLoginId);
        demoProperties.setAdminPassword(originalPassword);
    }

    private Long demoCompanyId() {
        return userRepository.findByEmail(DEMO_LOGIN_ID).orElseThrow().getCompanyId();
    }

    /** 정식 가입 경로로 "데모가 아닌" 회사를 만든다 — 격리 증명의 대조군. */
    private Company createOtherCompany(String email, String brn) {
        return companyAccountWriter.createAccount(
                email, "타사대표", passwordEncoder.encode("otherpw1"),
                "타사건설", brn, "서울시 어딘가", null,
                "storage/other-license", "{\"source\":\"TEST\"}", "1.0", "1.0",
                LocalDate.of(2019, 3, 2));
    }

    @Test
    void 시더는_멱등이다_두번_실행해도_중복_시드가_없다() throws Exception {
        DemoDataSeeder seeder = new DemoDataSeeder(demoProperties, userRepository, demoSeedService);
        ReflectionTestUtils.setField(seeder, "seedEnabled", true);

        seeder.run(null);
        seeder.run(null);

        Long companyId = demoCompanyId();
        assertThat(demoResetRepository.countFacilities(companyId)).isEqualTo(SEEDED_FACILITIES);
        assertThat(demoResetRepository.findCompanyMedia(companyId)).hasSize(SEEDED_MEDIA_ROWS);
    }

    @Test
    void 리셋은_데모_회사만_비우고_시드_상태로_복원하며_타_회사는_건드리지_않는다() {
        demoSeedService.seedAll();
        Long demoCompanyId = demoCompanyId();
        Long demoAdminId = userRepository.findByEmail(DEMO_LOGIN_ID).orElseThrow().getId();

        // 대조군: 타 회사 + 그 회사 시설물(리셋이 절대 건드리면 안 되는 데이터).
        Company other = createOtherCompany("other-owner@haja.com", "999-99-99999");
        facilityRepository.save(Facility.builder()
                .companyId(other.getId()).name("타사 사옥").type("건물").build());

        // 방문자 흔적: 데모 회사에 시설물 1건 + 콘솔 생성 사용자 1명.
        facilityRepository.save(Facility.builder()
                .companyId(demoCompanyId).name("방문자가 만든 시설물").type("건물").build());
        userRepository.save(User.createByAdmin("visitor-made@haja.com", "방문자생성계정",
                Role.USER, passwordEncoder.encode("visitorpw1"), demoCompanyId));
        assertThat(demoResetRepository.countFacilities(demoCompanyId)).isEqualTo(SEEDED_FACILITIES + 1);

        List<String> reclaimedKeys = demoResetService.resetToSeedState();

        // 시드 상태 복원 — 시설물·미디어가 정확히 시드 수량으로 돌아온다(방문자 시설물 제거 + 재시드).
        assertThat(demoResetRepository.countFacilities(demoCompanyId)).isEqualTo(SEEDED_FACILITIES);
        assertThat(demoResetRepository.findCompanyMedia(demoCompanyId)).hasSize(SEEDED_MEDIA_ROWS);
        // 이전 시드+방문자 데이터의 저장 파일 키가 회수 대상으로 반환된다(원본·썸네일·상세 3종 × 5로우).
        assertThat(reclaimedKeys).hasSize(SEEDED_MEDIA_ROWS * 3);
        // 방문자 생성 계정은 삭제, 데모 ADMIN 본인은 유지.
        assertThat(userRepository.findByEmail("visitor-made@haja.com")).isEmpty();
        assertThat(userRepository.findByEmail(DEMO_LOGIN_ID)).isPresent()
                .get().extracting(User::getId).isEqualTo(demoAdminId);
        // 격리 증명 — 타 회사 시설물·계정은 그대로다(companyId 조건 누락이 있으면 여기서 무너진다).
        assertThat(demoResetRepository.countFacilities(other.getId())).isEqualTo(1);
        assertThat(userRepository.findByEmail("other-owner@haja.com")).isPresent();
    }

    @Test
    void 데모_loginId가_남의_회사_계정을_가리키면_아무것도_지우지_않는다() {
        // 설정 실수 시나리오 — app.demo.login-id 가 실사용(타인 소유) 회사의 구성원을 가리킨다.
        Company other = createOtherCompany("real-owner@haja.com", "888-88-88888");
        facilityRepository.save(Facility.builder()
                .companyId(other.getId()).name("실사용 시설물").type("건물").build());
        userRepository.save(User.createByAdmin(DEMO_LOGIN_ID, "오배선데모계정",
                Role.ADMIN, passwordEncoder.encode("misconfpw1"), other.getId()));

        List<String> reclaimedKeys = demoResetService.resetToSeedState();

        // owner 대조 가드에 걸려 삭제 0건 — 실사용 회사 데이터가 통째로 증발하는 사고를 막는다.
        assertThat(reclaimedKeys).isEmpty();
        assertThat(demoResetRepository.countFacilities(other.getId())).isEqualTo(1);
        assertThat(userRepository.findByEmail("real-owner@haja.com")).isPresent();
    }

    @Test
    void provenance_판정은_DB_round_trip의_공백_포함_jsonb에서도_통과한다() {
        // #1626 P1-A 회귀 — businessRegistrationOcrRaw 는 @JdbcTypeCode(JSON) String 이라 Postgres 가
        // jsonb 를 canonical text 로 저장하며 콜론 뒤 공백을 넣는다({"source": "DEMO_SEED"}). 운영 리셋은
        // 별도 트랜잭션 재조회라 공백 포함 텍스트를 보는데, 시더 원본은 공백이 없다. 이 테스트는 시드 후
        // flush+clear 로 1차 캐시를 비워 DB 재조회를 강제한다 — 그래야 공백 포함 canonical 텍스트가 오고,
        // substring 매칭이면 여기서 provenance 가드가 false 로 무너져 리셋이 0건이 된다(테스트 실패로 검출).
        demoSeedService.seedAll();
        Long demoCompanyId = demoCompanyId();

        // 방문자 흔적(리셋이 실제로 삭제·복원했는지 판별할 표식).
        facilityRepository.save(Facility.builder()
                .companyId(demoCompanyId).name("방문자 시설물").type("건물").build());
        assertThat(demoResetRepository.countFacilities(demoCompanyId)).isEqualTo(SEEDED_FACILITIES + 1);

        // DB round-trip 강제 — 시더가 넣은 원본 String(공백 없음)이 아니라 Postgres canonical 텍스트를 읽게 한다.
        entityManager.flush();
        entityManager.clear();

        // 재조회한 ocr_raw 는 canonical 공백 형식이어야 한다(회귀 재현 조건을 함께 고정 — substring 회귀 방지).
        Company reread = entityManager.find(Company.class, demoCompanyId);
        assertThat(reread.getBusinessRegistrationOcrRaw()).contains("\"source\": \"DEMO_SEED\"");

        List<String> reclaimed = demoResetService.resetToSeedState();

        // provenance 가 파싱으로 통과해 리셋이 실제로 실행됐다 — 방문자 시설물 제거 + 시드 복원.
        assertThat(reclaimed).isNotEmpty();
        assertThat(demoResetRepository.countFacilities(demoCompanyId)).isEqualTo(SEEDED_FACILITIES);
    }

    @Test
    void owner는_일치하지만_데모_provenance가_아니면_아무것도_지우지_않는다() {
        // #1626 P1-2 — 최악의 오설정: app.demo.login-id 가 실사용 회사의 OWNER 를 가리킨다. owner 체크는
        // 통과하지만, BRN·provenance 는 시더만 기록하므로 시드 상수(0000000000 · DEMO_SEED)와 달라
        // provenance 가드가 삭제를 막아야 한다("owner 일치 + 데모 아님" 케이스 — handoff 필수 테스트).
        Company real = companyAccountWriter.createAccount(
                DEMO_LOGIN_ID, "실사용대표", passwordEncoder.encode("realpw1"),
                "실사용건설", "1234567890", "서울시 실사용구", null,
                "storage/real-license", "{\"source\":\"NTS\"}", "1.0", "1.0", LocalDate.of(2018, 5, 2));
        facilityRepository.save(Facility.builder()
                .companyId(real.getId()).name("실사용 시설물").type("건물").build());

        List<String> reclaimedKeys = demoResetService.resetToSeedState();

        assertThat(reclaimedKeys).isEmpty();
        assertThat(demoResetRepository.countFacilities(real.getId())).isEqualTo(1);
        assertThat(userRepository.findByEmail(DEMO_LOGIN_ID)).isPresent();
    }

    @Test
    void 리셋은_상담_티켓_세션_메모까지_지우고_타사_상담은_건드리지_않는다() {
        // #1626 P2-1 — counsel_tickets(user_id·session_id) FK 누락이면 리셋 트랜잭션이 통째로 롤백돼
        // 무증상 영구 실패한다. 데모/타사 양쪽에 상담 세트를 심고 데모=0, 타사=무접촉을 증명한다.
        demoSeedService.seedAll();
        Long demoCompanyId = demoCompanyId();
        Long demoAdminId = userRepository.findByEmail(DEMO_LOGIN_ID).orElseThrow().getId();

        Company other = createOtherCompany("counsel-other@haja.com", "777-77-77777");
        Long otherUserId = other.getOwnerUserId();

        seedCounsel(demoAdminId);
        Long otherTicketId = seedCounsel(otherUserId).getId();

        demoResetService.resetToSeedState();

        // 데모 회사 상담 데이터는 전부 사라진다.
        assertThat(chatSessionRepository.findAll().stream()
                .anyMatch(s -> s.getUserId().equals(demoAdminId))).isFalse();
        assertThat(counselTicketRepository.findAll().stream()
                .anyMatch(t -> t.getUserId().equals(demoAdminId))).isFalse();
        // 타사 상담 데이터는 그대로다(격리).
        assertThat(counselTicketRepository.findById(otherTicketId)).isPresent();
        assertThat(counselTicketNoteRepository.findAll().stream()
                .anyMatch(n -> n.getTicketId().equals(otherTicketId))).isTrue();
    }

    @Test
    void 리셋_파일회수에_챗_첨부와_보고서_PDF_키가_포함된다() {
        // #1626 P2-3 — Media 키만이 아니라 chat_messages.attachment_key 와 reports.pdf_url 의 저장키도
        // 회수 대상에 포함돼야 방문자 입력 파일이 스토리지에 영구 잔존하지 않는다.
        demoSeedService.seedAll();
        Long demoAdminId = userRepository.findByEmail(DEMO_LOGIN_ID).orElseThrow().getId();

        // 챗 첨부 1건.
        ChatSession session = chatSessionRepository.save(ChatSession.start(demoAdminId, ChatSessionType.RAG));
        chatMessageRepository.save(ChatMessage.create(session.getId(), ChatSenderType.USER,
                "첨부 메시지", null, "chat/att-demo.bin", "application/octet-stream"));

        // 시드된 보고서(DRAFT, pdf 없음)에 pdf_url 을 심어 확정 상태를 흉내낸다 — /pdf/ 뒤 저장키만 회수돼야 한다.
        Report seededReport = reportRepository.findAll().stream().findFirst().orElseThrow();
        ReflectionTestUtils.setField(seededReport, "pdfUrl",
                "/api/reports/" + seededReport.getId() + "/pdf/demo-report-pdf-key");
        reportRepository.saveAndFlush(seededReport);

        List<String> reclaimed = demoResetService.resetToSeedState();

        assertThat(reclaimed).contains("chat/att-demo.bin", "demo-report-pdf-key");
    }

    @Test
    void 리셋은_FREE_플랜의_월누적_분석카운터를_보존한다() {
        // #1626 P2-4 — usage_counters 를 통째로 지우면 FREE 월 50 분석이 매일 리셋돼 사실상 일 50 이 된다.
        // FREE 플랜 카운터는 리셋이 건드리지 않아야 한다.
        demoSeedService.seedAll();
        Long demoCompanyId = demoCompanyId();
        UserPlan freePlan = userPlanRepository
                .findFirstByCompanyIdAndStatusOrderByStartedAtDesc(demoCompanyId, UserPlanStatus.ACTIVE)
                .orElseThrow();
        LocalDate period = LocalDate.now().withDayOfMonth(1);
        usageCounterRepository.saveAndFlush(
                UsageCounter.create(freePlan.getId(), period, 30, 3, 30, 1, 0, 0));

        demoResetService.resetToSeedState();

        assertThat(usageCounterRepository.findByUserPlanIdAndPeriod(freePlan.getId(), period))
                .isPresent()
                .get()
                .extracting(UsageCounter::getAnalyzedImageCount)
                .isEqualTo(30);
    }

    @Test
    void 크레덴셜_회전시_시더가_데모계정_비밀번호_해시를_재동기화한다() {
        // #1626 P2-2b — env DEMO_ADMIN_PASSWORD 를 회전하면 설정을 진실 소스로 DB 해시를 맞춰야
        // 데모 로그인(서버가 설정 비밀번호로 인증)이 깨지지 않는다.
        demoSeedService.seedAll();
        User before = userRepository.findByEmail(DEMO_LOGIN_ID).orElseThrow();
        assertThat(passwordEncoder.matches("demo-it-dummy1", before.getPasswordHash())).isTrue();

        demoProperties.setAdminPassword("rotated-dummy2");
        boolean rehashed = demoSeedService.syncAdminPasswordIfChanged();

        assertThat(rehashed).isTrue();
        User after = userRepository.findByEmail(DEMO_LOGIN_ID).orElseThrow();
        assertThat(passwordEncoder.matches("rotated-dummy2", after.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("demo-it-dummy1", after.getPasswordHash())).isFalse();
        // 이미 일치하면 no-op(불필요한 쓰기 없음).
        assertThat(demoSeedService.syncAdminPasswordIfChanged()).isFalse();
    }

    @Test
    void 데모_회사가_FAILED_상태로_재기동되면_시더가_VERIFIED로_자동복구한다() throws Exception {
        // #1648 — 재검증 배치가 데모 회사를 FAILED로 강등한 채 남아 있던 prod 시나리오 재현. 배치 자체는
        // 이제 데모 회사를 스킵하도록 고쳤지만, 고치기 전에 이미 FAILED가 찍힌 회사는 재기동 시 여기서
        // 자동 복구돼야 한다(런타임 복구 경로가 이것뿐).
        demoSeedService.seedAll();
        Long companyId = demoCompanyId();
        Company company = companyRepository.findById(companyId).orElseThrow();
        company.markBusinessVerificationFailed();
        companyRepository.saveAndFlush(company);
        assertThat(companyRepository.findById(companyId).orElseThrow().getVerificationStatus())
                .isEqualTo(BusinessVerificationStatus.FAILED);

        DemoDataSeeder seeder = new DemoDataSeeder(demoProperties, userRepository, demoSeedService);
        ReflectionTestUtils.setField(seeder, "seedEnabled", true);
        seeder.run(null);

        assertThat(companyRepository.findById(companyId).orElseThrow().getVerificationStatus())
                .isEqualTo(BusinessVerificationStatus.VERIFIED);
    }

    @Test
    void FAILED가_아니면_자가복구_호출은_아무것도_하지_않는다() {
        // #1648 — VERIFIED(정상) 상태의 데모 회사에 자가복구를 호출해도 no-op이어야 한다(불필요한 쓰기 없음).
        demoSeedService.seedAll();
        Long companyId = demoCompanyId();
        assertThat(companyRepository.findById(companyId).orElseThrow().getVerificationStatus())
                .isEqualTo(BusinessVerificationStatus.VERIFIED);

        boolean healed = demoSeedService.healFailedVerificationIfNeeded();

        assertThat(healed).isFalse();
        assertThat(companyRepository.findById(companyId).orElseThrow().getVerificationStatus())
                .isEqualTo(BusinessVerificationStatus.VERIFIED);
    }

    /** 주어진 사용자에게 챗 세션 + 상담 티켓(세션 참조) + 상담 메모 + 챗 메시지를 심는다. */
    private CounselTicket seedCounsel(Long userId) {
        ChatSession session = chatSessionRepository.save(ChatSession.start(userId, ChatSessionType.COUNSEL));
        CounselTicket ticket = CounselTicket.request(userId, CounselType.USAGE, 1, "이용문의", "상담 제목");
        ReflectionTestUtils.setField(ticket, "sessionId", session.getId());
        ticket = counselTicketRepository.saveAndFlush(ticket);
        counselTicketNoteRepository.saveAndFlush(CounselTicketNote.create(ticket.getId(), userId, "메모"));
        chatMessageRepository.saveAndFlush(ChatMessage.createText(session.getId(), ChatSenderType.USER, "안녕"));
        return ticket;
    }
}
