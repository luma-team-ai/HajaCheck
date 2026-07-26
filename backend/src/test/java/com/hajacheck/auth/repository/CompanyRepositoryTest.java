package com.hajacheck.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.BusinessVerificationStatus;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.User;
import com.hajacheck.support.PostgresTestSupport;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

// 실 PG DDL(companies) 대조를 위해 임베디드 교체를 끄고 Testcontainers PostgreSQL 사용(FacilityRepositoryTest 패턴).
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class CompanyRepositoryTest extends PostgresTestSupport {

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private TestEntityManager em;

    private Long seedCompany(String email, BusinessVerificationStatus verificationStatus, LocalDate businessStartDate) {
        User owner = User.createCompanyOwner(email, "대표자", "$2a$10$testtesttesttesttesttes");
        em.persist(owner);
        Company company = Company.createPendingReview(
                owner.getId(),
                email + " 회사",
                "BRN-" + Math.abs(email.hashCode()),
                "대표자",
                "서울",
                null,
                "https://example.com/brn",
                "{\"source\":\"TEST\"}",
                businessStartDate);
        if (verificationStatus == BusinessVerificationStatus.VERIFIED) {
            company.markBusinessVerified();
        } else if (verificationStatus == BusinessVerificationStatus.FAILED) {
            company.markBusinessVerificationFailed();
        }
        em.persist(company);
        em.flush();
        return company.getId();
    }

    // PendingBusinessReverifyScheduler(#888) 대상 조회 — PENDING + businessStartDate 있음만 반환.
    @Test
    void PENDING이면서_개업일자가있는회사만_조회된다() {
        Long targetId = seedCompany("target@haja.test", BusinessVerificationStatus.PENDING, LocalDate.of(2020, 1, 1));
        seedCompany("verified@haja.test", BusinessVerificationStatus.VERIFIED, LocalDate.of(2020, 1, 1));
        seedCompany("failed@haja.test", BusinessVerificationStatus.FAILED, LocalDate.of(2020, 1, 1));
        // 개업일자 없는 레거시 backfill 회사 — PENDING이어도 제외 대상.
        seedCompany("legacy-no-start-date@haja.test", BusinessVerificationStatus.PENDING, null);
        em.clear();

        List<Company> result = companyRepository.findByVerificationStatusAndBusinessStartDateIsNotNull(
                BusinessVerificationStatus.PENDING, PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "id")));

        assertThat(result).extracting(Company::getId).containsExactly(targetId);
    }

    @Test
    void Pageable로_회차당최대처리건수를제한한다() {
        seedCompany("a@haja.test", BusinessVerificationStatus.PENDING, LocalDate.of(2020, 1, 1));
        seedCompany("b@haja.test", BusinessVerificationStatus.PENDING, LocalDate.of(2020, 1, 2));
        seedCompany("c@haja.test", BusinessVerificationStatus.PENDING, LocalDate.of(2020, 1, 3));
        em.clear();

        List<Company> result = companyRepository.findByVerificationStatusAndBusinessStartDateIsNotNull(
                BusinessVerificationStatus.PENDING, PageRequest.of(0, 2, Sort.by(Sort.Direction.ASC, "id")));

        assertThat(result).hasSize(2);
    }
}
