package com.hajacheck.auth.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.User;
import com.hajacheck.support.PostgresTestSupport;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 상태 머신 Entity의 {@code @Version}이 stale 상태 전이를 실제 PostgreSQL에서 거부하는지 검증한다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class StateTransitionOptimisticLockTest extends PostgresTestSupport {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentCompanyReview_onlyFirstTransitionCommits() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long[] ids = inTransaction(() -> {
            User owner = userRepository.saveAndFlush(
                    User.createCompanyOwner(
                            "lock-" + suffix + "@haja.test",
                            "optimistic-lock-owner",
                            "test-hash"));
            Company company = Company.createPendingReview(
                    owner.getId(),
                    "optimistic-lock-company",
                    "LOCK-" + suffix,
                    "owner",
                    "Seoul",
                    null,
                    "https://files.example/registration.pdf",
                    "{\"source\":\"TEST\"}");
            company.markBusinessVerified();
            companyRepository.saveAndFlush(company);
            return new Long[]{owner.getId(), company.getId()};
        });

        try {
            Company firstRequest = inTransaction(
                    () -> companyRepository.findById(ids[1]).orElseThrow());
            Company staleSecondRequest = inTransaction(
                    () -> companyRepository.findById(ids[1]).orElseThrow());

            inTransaction(() -> {
                firstRequest.approve(ids[0]);
                return companyRepository.saveAndFlush(firstRequest);
            });

            assertThatThrownBy(() -> inTransaction(() -> {
                staleSecondRequest.approve(ids[0]);
                return companyRepository.saveAndFlush(staleSecondRequest);
            })).isInstanceOf(OptimisticLockingFailureException.class);
        } finally {
            inTransaction(() -> {
                companyRepository.deleteById(ids[1]);
                userRepository.deleteById(ids[0]);
                return null;
            });
        }
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
