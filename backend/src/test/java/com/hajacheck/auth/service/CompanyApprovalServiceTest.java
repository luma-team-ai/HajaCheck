package com.hajacheck.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.dto.CompanyApprovalResponse;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 기업 가입 승인/반려 서비스(#363) 단위 테스트 — Company/CompanyMembership 두 상태 머신을
 * 같은 트랜잭션에서 함께 전이시키는지, 승인 시점에만 오너의 companyId가 배선되는지 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class CompanyApprovalServiceTest {

    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private CompanyMembershipRepository companyMembershipRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CompanyApprovalService companyApprovalService;

    @Test
    void approve_회사_멤버십_오너companyId를_모두_전이한다() {
        Company company = mock(Company.class);
        when(company.getId()).thenReturn(1L);
        when(company.getOwnerUserId()).thenReturn(10L);
        when(company.getStatus()).thenReturn(com.hajacheck.auth.entity.CompanyStatus.APPROVED);
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));

        CompanyMembership membership = mock(CompanyMembership.class);
        when(companyMembershipRepository.findByCompanyIdAndUserId(1L, 10L)).thenReturn(Optional.of(membership));

        User owner = mock(User.class);
        when(userRepository.findById(10L)).thenReturn(Optional.of(owner));

        CompanyApprovalResponse response = companyApprovalService.approve(99L, 1L);

        verify(company).approve(99L);
        verify(membership).approve();
        verify(owner).assignToCompany(1L);
        assertThat(response.companyId()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo("APPROVED");
    }

    @Test
    void approve_존재하지않는회사면_COMPANY_NOT_FOUND이고_아무것도_전이하지않는다() {
        when(companyRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> companyApprovalService.approve(99L, 1L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.COMPANY_NOT_FOUND));
        verify(companyMembershipRepository, never()).findByCompanyIdAndUserId(anyLong(), anyLong());
        verify(userRepository, never()).findById(any());
    }

    @Test
    void approve_회사상태전이가_실패하면_멤버십과_owner는_건드리지않는다() {
        Company company = mock(Company.class);
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        org.mockito.Mockito.doThrow(new com.hajacheck.global.exception.DomainStateTransitionException("불가"))
                .when(company).approve(99L);

        assertThatThrownBy(() -> companyApprovalService.approve(99L, 1L))
                .isInstanceOf(com.hajacheck.global.exception.DomainStateTransitionException.class);
        verify(companyMembershipRepository, never()).findByCompanyIdAndUserId(anyLong(), anyLong());
        verify(userRepository, never()).findById(any());
    }

    @Test
    void reject_회사와_멤버십을_반려로_전이하고_owner는_건드리지않는다() {
        Company company = mock(Company.class);
        when(company.getId()).thenReturn(1L);
        when(company.getOwnerUserId()).thenReturn(10L);
        when(company.getStatus()).thenReturn(com.hajacheck.auth.entity.CompanyStatus.REJECTED);
        when(company.getRejectionReason()).thenReturn("서류 미비");
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));

        CompanyMembership membership = mock(CompanyMembership.class);
        when(companyMembershipRepository.findByCompanyIdAndUserId(1L, 10L)).thenReturn(Optional.of(membership));

        CompanyApprovalResponse response = companyApprovalService.reject(99L, 1L, "서류 미비");

        verify(company).reject(99L, "서류 미비");
        verify(membership).reject();
        verify(userRepository, never()).findById(any());
        assertThat(response.rejectionReason()).isEqualTo("서류 미비");
    }
}
