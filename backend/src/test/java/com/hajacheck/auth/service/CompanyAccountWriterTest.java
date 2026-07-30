package com.hajacheck.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.CompanyMembershipStatus;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserConsentRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.membership.service.PlanProvisioningService;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 기업 가입 원자 저장 — FREE 플랜 배정(#517) + 가입 즉시 자동승인·오너 멤버십 발급(#1324) 검증.
 */
@ExtendWith(MockitoExtension.class)
class CompanyAccountWriterTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private CompanyMembershipRepository companyMembershipRepository;
    @Mock
    private UserConsentRepository userConsentRepository;
    @Mock
    private PlanProvisioningService planProvisioningService;

    @InjectMocks
    private CompanyAccountWriter accountWriter;

    private Company stubSavedCompany() {
        Company company = mock(Company.class);
        when(company.getId()).thenReturn(99L);
        when(companyRepository.save(any(Company.class))).thenReturn(company);
        return company;
    }

    private User stubSavedUser() {
        User user = User.createCompanyOwner("owner@haja.com", "김민수", "$2a$hashed");
        when(userRepository.save(any(User.class))).thenReturn(user);
        return user;
    }

    private Company createAccount() {
        return accountWriter.createAccount(
                "owner@haja.com", "김민수", "$2a$hashed",
                "(주)하자체크", "1234567890", "서울시 강남구", "101호",
                "/files/x.png", "{}", "1.0", "1.0",
                LocalDate.of(2020, 1, 1));
    }

    @Test
    void createAccount_회사저장후_FREE회사플랜배정호출() {
        stubSavedUser();
        Company company = stubSavedCompany();

        Company result = createAccount();

        assertThat(result).isEqualTo(company);
        verify(planProvisioningService).ensureFreePlanForCompany(99L);
    }

    @Test
    void createAccount_진위확인결과와무관하게_VERIFIED전이호출() {
        stubSavedUser();
        Company company = stubSavedCompany();

        createAccount();

        // #1324 전면 자동승인 — 국세청 SKIPPED(키 미설정·장애 fail-open)도 VERIFIED 로 승격한다.
        // VERIFIED 는 스코프 판정·DB 트리거의 필수 조건이라 PENDING 으로 남기면 점검 생성이 막힌다.
        verify(company).markBusinessVerified();
    }

    @Test
    void createAccount_회사를_즉시_자동승인한다() {
        stubSavedUser();
        Company company = stubSavedCompany();

        createAccount();

        // 관리자 승인 미배선 상태에서 PENDING_REVIEW 로 두면 영구 미승인이 된다(#1324).
        verify(company).autoApprove();
    }

    @Test
    void createAccount_VERIFIED전이가_autoApprove보다_먼저다() {
        stubSavedUser();
        Company company = stubSavedCompany();

        createAccount();

        // 순서 계약 고정 — 나중에 autoApprove() 에 VERIFIED 선행 가드가 추가되어도 깨지지 않도록,
        // 그리고 "승인됐지만 진위 PENDING" 이라는 중간 상태가 커밋 경로에 남지 않도록 순서를 못박는다.
        InOrder order = inOrder(company);
        order.verify(company).markBusinessVerified();
        order.verify(company).autoApprove();
    }

    @Test
    void createAccount_오너의_APPROVED멤버십을_같은트랜잭션에서_발급한다() {
        User user = stubSavedUser();
        stubSavedCompany();

        createAccount();

        ArgumentCaptor<CompanyMembership> membershipCaptor =
                ArgumentCaptor.forClass(CompanyMembership.class);
        verify(companyMembershipRepository).save(membershipCaptor.capture());
        CompanyMembership membership = membershipCaptor.getValue();

        // 스코프 판정의 세 번째 조건 — 유효 APPROVED 멤버십(approved_at 있음 · revoked_at 없음 · 미만료).
        assertThat(membership.getCompanyId()).isEqualTo(99L);
        assertThat(membership.getUserId()).isEqualTo(user.getId());
        assertThat(membership.getStatus()).isEqualTo(CompanyMembershipStatus.APPROVED);
        // DB check 제약 ck_company_memberships_approved_at 은 APPROVED 에 approved_at 을 요구한다.
        assertThat(membership.getApprovedAt()).isNotNull();
        assertThat(membership.getRevokedAt()).isNull();
        assertThat(membership.getExpiresAt()).isNull();
        // 오너의 최초 멤버십은 초대자가 없다(V1 company_memberships.invited_by 주석과 정합).
        assertThat(membership.getInvitedBy()).isNull();
    }

    @Test
    void createAccount_멤버십의_companyId와_user의_companyId가_일치한다() {
        User user = stubSavedUser();
        stubSavedCompany();

        createAccount();

        ArgumentCaptor<CompanyMembership> membershipCaptor =
                ArgumentCaptor.forClass(CompanyMembership.class);
        verify(companyMembershipRepository).save(membershipCaptor.capture());

        // 스코프 쿼리·DB 트리거가 users.company_id = company_memberships.company_id 를 요구한다.
        // 둘이 어긋나면 가입은 되지만 점검 생성·담당자 배정이 조용히 막힌다.
        assertThat(user.getCompanyId()).isEqualTo(membershipCaptor.getValue().getCompanyId());
    }

    @Test
    void createAccount_owner계정은_회사관리자ADMIN으로_저장된다() {
        stubSavedUser();
        stubSavedCompany();

        createAccount();

        // 기업 owner=회사 관리자(#636) → 저장되는 user 는 ADMIN 이어야 한다.
        // (DB 트리거도 담당자 배정에 INSPECTOR/ADMIN 역할을 요구한다.)
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getRole()).isEqualTo(Role.ADMIN);
    }
}
