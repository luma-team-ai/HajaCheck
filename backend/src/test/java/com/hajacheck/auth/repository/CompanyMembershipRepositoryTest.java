package com.hajacheck.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.support.PostgresTestSupport;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class CompanyMembershipRepositoryTest extends PostgresTestSupport {

    @Autowired
    private CompanyMembershipRepository companyMembershipRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void existsEffectiveApprovedMembership_승인검증회사와활성사용자_true() {
        Company company = saveCompany(CompanyState.APPROVED_VERIFIED);
        User member = saveMember(company.getId(), UserStatus.ACTIVE);
        saveApprovedMembership(company.getId(), member.getId());

        boolean exists = companyMembershipRepository.existsEffectiveApprovedMembership(
                company.getId(), member.getId(), Instant.now());

        assertThat(exists).isTrue();
    }

    @Test
    void existsEffectiveApprovedMembership_정지사용자_false() {
        Company company = saveCompany(CompanyState.APPROVED_VERIFIED);
        User member = saveMember(company.getId(), UserStatus.SUSPENDED);
        saveApprovedMembership(company.getId(), member.getId());

        boolean exists = companyMembershipRepository.existsEffectiveApprovedMembership(
                company.getId(), member.getId(), Instant.now());

        assertThat(exists).isFalse();
    }

    @Test
    void existsEffectiveApprovedMembership_반려회사_false() {
        Company company = saveCompany(CompanyState.REJECTED);
        User member = saveMember(company.getId(), UserStatus.ACTIVE);
        saveApprovedMembership(company.getId(), member.getId());

        boolean exists = companyMembershipRepository.existsEffectiveApprovedMembership(
                company.getId(), member.getId(), Instant.now());

        assertThat(exists).isFalse();
    }

    @Test
    void existsEffectiveApprovedMembership_미검증회사_false() {
        Company company = saveCompany(CompanyState.PENDING_VERIFICATION);
        User member = saveMember(company.getId(), UserStatus.ACTIVE);
        saveApprovedMembership(company.getId(), member.getId());

        boolean exists = companyMembershipRepository.existsEffectiveApprovedMembership(
                company.getId(), member.getId(), Instant.now());

        assertThat(exists).isFalse();
    }

    @Test
    void existsEffectiveApprovedMembership_멤버십없음_false() {
        Company company = saveCompany(CompanyState.APPROVED_VERIFIED);
        User member = saveMember(company.getId(), UserStatus.ACTIVE);

        boolean exists = companyMembershipRepository.existsEffectiveApprovedMembership(
                company.getId(), member.getId(), Instant.now());

        assertThat(exists).isFalse();
    }

    @Test
    void existsEffectiveApprovedMembership_PENDING멤버십_false() {
        Company company = saveCompany(CompanyState.APPROVED_VERIFIED);
        User member = saveMember(company.getId(), UserStatus.ACTIVE);
        companyMembershipRepository.saveAndFlush(CompanyMembership.invite(
                company.getId(), member.getId(), null, Instant.now().plus(1, ChronoUnit.DAYS)));

        boolean exists = companyMembershipRepository.existsEffectiveApprovedMembership(
                company.getId(), member.getId(), Instant.now());

        assertThat(exists).isFalse();
    }

    @Test
    void existsEffectiveApprovedMembership_REVOKED멤버십_false() {
        Company company = saveCompany(CompanyState.APPROVED_VERIFIED);
        User member = saveMember(company.getId(), UserStatus.ACTIVE);
        CompanyMembership membership = saveApprovedMembership(company.getId(), member.getId());
        membership.revoke();
        companyMembershipRepository.saveAndFlush(membership);

        boolean exists = companyMembershipRepository.existsEffectiveApprovedMembership(
                company.getId(), member.getId(), Instant.now());

        assertThat(exists).isFalse();
    }

    @Test
    void existsEffectiveApprovedMembership_EXPIRED멤버십_false() {
        Company company = saveCompany(CompanyState.APPROVED_VERIFIED);
        User member = saveMember(company.getId(), UserStatus.ACTIVE);
        saveApprovedMembership(company.getId(), member.getId());

        boolean exists = companyMembershipRepository.existsEffectiveApprovedMembership(
                company.getId(), member.getId(), Instant.now().plus(2, ChronoUnit.DAYS));

        assertThat(exists).isFalse();
    }

    @Test
    void existsEffectiveApprovedMembership_staleCompanyPointer_false() {
        Company company = saveCompany(CompanyState.APPROVED_VERIFIED);
        User member = saveMember(company.getId(), UserStatus.ACTIVE);
        saveApprovedMembership(company.getId(), member.getId());
        member.assignToCompany(null);
        userRepository.saveAndFlush(member);

        boolean exists = companyMembershipRepository.existsEffectiveApprovedMembership(
                company.getId(), member.getId(), Instant.now());

        assertThat(exists).isFalse();
    }

    private Company saveCompany(CompanyState state) {
        User owner = userRepository.save(User.createCompanyOwner(
                "membership-owner@haja.com", "멤버십 소유자", "$2a$10$hashed"));
        Company company = companyRepository.save(Company.createPendingReview(
                owner.getId(),
                "멤버십 테스트 회사",
                "1234567890",
                "대표자",
                "서울시 강남구",
                null,
                "https://files.example.com/registration.png",
                "{}"));
        owner.assignToCompany(company.getId());

        if (state == CompanyState.APPROVED_VERIFIED) {
            company.markBusinessVerified();
            company.approve(owner.getId());
        } else if (state == CompanyState.REJECTED) {
            company.reject(owner.getId(), "테스트 반려");
        }
        return company;
    }

    private User saveMember(Long companyId, UserStatus status) {
        return userRepository.save(User.builder()
                .email("membership-member@haja.com")
                .name("멤버십 구성원")
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$hashed")
                .companyId(companyId)
                .status(status)
                .build());
    }

    private CompanyMembership saveApprovedMembership(Long companyId, Long userId) {
        CompanyMembership membership = CompanyMembership.invite(
                companyId, userId, null, Instant.now().plus(1, ChronoUnit.DAYS));
        membership.approve();
        return companyMembershipRepository.saveAndFlush(membership);
    }

    private enum CompanyState {
        APPROVED_VERIFIED,
        REJECTED,
        PENDING_VERIFICATION
    }
}
