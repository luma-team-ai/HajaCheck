package com.hajacheck.platformadmin.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.support.PostgresTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 회사별 마지막 ADMIN 보호(requireNotLastCompanyAdmin) TOCTOU 방지(PR #656 PR머신 2차 검토 P2) —
 * 실 PG(Testcontainers)로 같은 회사의 서로 다른 두 ADMIN을 동시에 정지시켜도 CompanyRepository
 * #findByIdForUpdate 행 잠금이 직렬화해 회사의 활성 ADMIN이 0명이 되지 않는지 검증한다.
 *
 * <p>InspectionServiceConcurrencyTest와 동일 이유로 클래스 레벨 {@code @Transactional}을 붙이지
 * 않는다 — 각 워커 스레드가 PlatformAdminUserService 프록시를 통해 독립된 실 트랜잭션을 얻어야
 * 진짜 동시 경쟁을 재현한다. 커밋된 데이터는 {@link #tearDown()}에서 직접 정리한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PlatformAdminUserServiceConcurrencyTest extends PostgresTestSupport {

    @Autowired
    private PlatformAdminUserService platformAdminUserService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanyRepository companyRepository;

    private Long companyId;
    private Long admin1Id;
    private Long admin2Id;

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(User.builder()
                .email("pa-concurrency-owner-" + System.nanoTime() + "@haja.com")
                .name("동시성테스트대표")
                .role(Role.ADMIN)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .build());

        String brn = "pabrn-" + (System.nanoTime() % 10_000_000_000L);
        Company company = companyRepository.save(Company.createPendingReview(
                owner.getId(), "(주)동시성관리자테스트", brn,
                "김대표", "서울시 강남구", null, "http://files/brn.png", "{}"));
        company.markBusinessVerified();
        company.approve(owner.getId());
        company = companyRepository.save(company);

        User admin1 = userRepository.save(User.builder()
                .email("pa-concurrency-admin1-" + System.nanoTime() + "@haja.com")
                .name("관리자1")
                .role(Role.ADMIN)
                .companyId(company.getId())
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .build());
        User admin2 = userRepository.save(User.builder()
                .email("pa-concurrency-admin2-" + System.nanoTime() + "@haja.com")
                .name("관리자2")
                .role(Role.ADMIN)
                .companyId(company.getId())
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .build());

        this.companyId = company.getId();
        this.admin1Id = admin1.getId();
        this.admin2Id = admin2.getId();
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteById(admin1Id);
        userRepository.deleteById(admin2Id);

        Company company = companyRepository.findById(companyId).orElseThrow();
        User owner = userRepository.findById(company.getOwnerUserId()).orElseThrow();
        companyRepository.deleteById(companyId);
        userRepository.deleteById(owner.getId());
    }

    @Test
    void 활성ADMIN2명인회사에서_동시정지요청은_최소한건이_거부되고_활성ADMIN이0명이되지않는다() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Long> targets = List.of(admin1Id, admin2Id);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (Long targetId : targets) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    platformAdminUserService.changeStatus(targetId, UserStatus.SUSPENDED);
                    return true;
                } catch (BusinessException e) {
                    if (e.getErrorCode() != ErrorCode.ADMIN_PROTECTED_ACCOUNT) {
                        throw e;
                    }
                    return false;
                }
            }));
        }
        ready.await();
        start.countDown();

        int succeeded = 0;
        int rejected = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(30, TimeUnit.SECONDS)) {
                succeeded++;
            } else {
                rejected++;
            }
        }
        executor.shutdown();

        // 잠금이 없었다면 두 요청 모두 count=2>1을 보고 통과해(succeeded=2) 활성 ADMIN이 0명이 될 수 있다.
        // 직렬화가 되면 뒤에 처리되는 쪽은 감소한 카운트(=1)를 보고 거부된다.
        assertThat(succeeded).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);

        long remainingActiveAdmins = userRepository.findAll().stream()
                .filter(u -> companyId.equals(u.getCompanyId()))
                .filter(u -> u.getRole() == Role.ADMIN)
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .count();
        assertThat(remainingActiveAdmins).isEqualTo(1);
    }
}
