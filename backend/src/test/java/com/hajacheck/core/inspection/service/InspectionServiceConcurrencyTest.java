package com.hajacheck.core.inspection.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.dto.InspectionCreateRequest;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 회차(round_no) 채번 동시성 경쟁 방지(dev-05-02 리뷰 P2) — 실 PG(Testcontainers)로 여러 스레드가
 * 같은 시설물에 동시에 점검 회차를 생성해도 PESSIMISTIC_WRITE 행 잠금이 정상 직렬화하는지 검증한다.
 *
 * <p>⚠️ 의도적으로 클래스 레벨 {@code @Transactional} 을 붙이지 않는다 — InspectionService.createInspection()
 * 자체가 {@code @Transactional} 이므로, 각 워커 스레드가 그 프록시를 통해 독립된 실 트랜잭션을 얻어야
 * 진짜 동시 경쟁을 재현할 수 있다(테스트 메서드를 감싸는 공용 트랜잭션이 있으면 한 커넥션을 공유해 의미가 없다).
 * 대신 커밋된 데이터를 {@link #tearDown()} 에서 직접 정리한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class InspectionServiceConcurrencyTest extends PostgresTestSupport {

    @Autowired
    private InspectionService inspectionService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private CompanyMembershipRepository companyMembershipRepository;
    @Autowired
    private FacilityRepository facilityRepository;
    @Autowired
    private InspectionRepository inspectionRepository;

    private Long ownerId;
    private Long inspectorId;
    private Long facilityId;
    private Long companyId;
    private Long ownerMembershipId;
    private Long inspectorMembershipId;

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(User.builder()
                .email("concurrency-owner-" + System.nanoTime() + "@haja.com")
                .name("동시성테스트소유자")
                .role(Role.USER)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .build());

        // business_registration_number 는 varchar(20) — 접두사 짧게 + nanoTime 뒷자리로 유니크성 확보.
        String brn = "brn-" + (System.nanoTime() % 10_000_000_000L);
        Company company = companyRepository.save(Company.createPendingReview(
                owner.getId(), "(주)동시성테스트", brn,
                "김대표", "서울시 강남구", null, "http://files/brn.png", "{}"));
        owner.assignToCompany(company.getId());
        userRepository.save(owner);

        User inspector = userRepository.save(User.builder()
                .email("concurrency-inspector-" + System.nanoTime() + "@haja.com")
                .name("동시성테스트점검자")
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .companyId(company.getId())
                .status(UserStatus.ACTIVE)
                .build());

        CompanyMembership ownerMembership = companyMembershipRepository.save(
                CompanyMembership.approvedOwner(company.getId(), owner.getId()));
        CompanyMembership inspectorMembership = CompanyMembership.invite(
                company.getId(), inspector.getId(), owner.getId(), null);
        inspectorMembership.approve();
        inspectorMembership = companyMembershipRepository.save(inspectorMembership);

        Facility facility = facilityRepository.save(
                Facility.builder().ownerId(owner.getId()).name("동시성테스트시설").type("BUILDING").build());

        this.ownerId = owner.getId();
        this.inspectorId = inspector.getId();
        this.facilityId = facility.getId();
        this.companyId = company.getId();
        this.ownerMembershipId = ownerMembership.getId();
        this.inspectorMembershipId = inspectorMembership.getId();
    }

    @AfterEach
    void tearDown() {
        inspectionRepository.findByFacilityIdIn(List.of(facilityId)).forEach(inspectionRepository::delete);
        facilityRepository.deleteById(facilityId);

        companyMembershipRepository.deleteById(inspectorMembershipId);
        companyMembershipRepository.deleteById(ownerMembershipId);

        // circular FK(companies.owner_user_id ↔ users.company_id) — company_id 를 먼저 끊어야 순서대로 지운다.
        User owner = userRepository.findById(ownerId).orElseThrow();
        owner.assignToCompany(null);
        userRepository.save(owner);
        User inspector = userRepository.findById(inspectorId).orElseThrow();
        inspector.assignToCompany(null);
        userRepository.save(inspector);

        companyRepository.deleteById(companyId);
        userRepository.deleteById(inspectorId);
        userRepository.deleteById(ownerId);
    }

    @Test
    void createInspection_동시생성요청_round_no중복없이모두성공() throws Exception {
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                InspectionCreateRequest request =
                        new InspectionCreateRequest(facilityId, LocalDate.now(), inspectorId);
                return inspectionService.createInspection(request, ownerId).roundNo();
            }));
        }
        ready.await();
        start.countDown();

        List<Integer> roundNos = new ArrayList<>();
        for (Future<Integer> future : futures) {
            roundNos.add(future.get(30, TimeUnit.SECONDS));
        }
        executor.shutdown();

        // 락이 없었다면 동시에 같은 max+1 을 읽어 round_no 가 중복되거나(unique 위반) 누락됐을 것 —
        // 8개 스레드 모두 예외 없이 성공하고 1..8 이 정확히 한 번씩만 나오면 직렬화가 제대로 동작한 것.
        assertThat(roundNos).containsExactlyInAnyOrderElementsOf(
                IntStream.rangeClosed(1, threadCount).boxed().toList());
    }
}
