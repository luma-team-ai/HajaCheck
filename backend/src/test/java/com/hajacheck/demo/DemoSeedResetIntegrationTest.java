package com.hajacheck.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.config.DemoProperties;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.CompanyAccountWriter;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.demo.init.DemoDataSeeder;
import com.hajacheck.demo.repository.DemoResetRepository;
import com.hajacheck.demo.service.DemoResetService;
import com.hajacheck.demo.service.DemoSeedService;
import com.hajacheck.support.PostgresTestSupport;
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
    private FacilityRepository facilityRepository;
    @Autowired
    private CompanyAccountWriter companyAccountWriter;
    @Autowired
    private PasswordEncoder passwordEncoder;

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
}
