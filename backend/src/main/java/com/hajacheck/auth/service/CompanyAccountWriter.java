package com.hajacheck.auth.service;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.ConsentPolicyType;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserConsent;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserConsentRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.membership.service.PlanProvisioningService;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * User + Company + CompanyMembership + UserConsent(약관 2건) 원자 저장 전담 — 별도 빈으로 분리해
 * self-invocation 회피(같은 클래스 내부 호출은 @Transactional 프록시가 안 걸리므로, 트랜잭션 경계를
 * 별도 빈으로 확보).
 *
 * <p>저장 순서(FK 정합): users.company_id 는 nullable → ①유저 먼저 저장(company_id=null) →
 * ②회사 저장(owner_user_id=user.id) → ③회사 VERIFIED+APPROVED 전이(#1324) →
 * ④user.assignToCompany(company.id)(dirty flush) → ⑤오너 APPROVED 멤버십 저장(#1324) →
 * ⑥동의 이력 saveAll → ⑦FREE 플랜 배정(#517). 전부 같은 트랜잭션이다.
 * users↔companies 상호 FK 를 유저 선삽입 + 사후 업데이트로 순환 없이 해소한다.
 *
 * <p><b>가입 즉시 회사 스코프를 여는 3요소(#1324)</b> — 스코프 판정
 * ({@code CompanyMembershipRepository.existsEffectiveApprovedMembership} 및 동일 불변식의 DB 트리거
 * {@code trg_inspections_check_assigned_inspector_company})는 ⓐ회사 {@code APPROVED}
 * ⓑ회사 {@code VERIFIED} ⓒ오너의 유효 {@code APPROVED} 멤버십({@code approved_at} 있음 ·
 * {@code revoked_at} 없음 · 미만료 · {@code users.company_id = memberships.company_id})을
 * <b>모두</b> 요구한다. 셋 중 하나라도 빠지면 가입은 되지만 점검 생성·담당자 배정이 막힌다 —
 * 그래서 ③④⑤를 같은 트랜잭션에서 함께 처리한다.
 *
 * <p>이메일/사업자번호 unique 위반은 여기서 DataIntegrityViolationException 으로 전파되고,
 * 호출부(CompanySignupService)가 파일 보상삭제 + 409 매핑을 담당한다.
 */
@Component
@RequiredArgsConstructor
public class CompanyAccountWriter {

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final CompanyMembershipRepository companyMembershipRepository;
    private final UserConsentRepository userConsentRepository;
    private final PlanProvisioningService planProvisioningService;

    /**
     * @return 저장된 Company(관리 상태, id 채워짐 — VERIFIED + APPROVED 상태)
     */
    @Transactional
    public Company createAccount(String email, String representativeName, String passwordHash,
                                 String companyName, String businessRegistrationNumber,
                                 String address, String addressDetail,
                                 String fileUrl, String ocrRaw,
                                 String termsVersion, String privacyVersion,
                                 LocalDate businessStartDate) {

        // user.name = 대표자명(표시명). role=ADMIN(회사 owner=회사 관리자, #636), status=ACTIVE.
        User user = userRepository.save(User.createCompanyOwner(email, representativeName, passwordHash));

        Company company = companyRepository.save(Company.createPendingReview(
                user.getId(), companyName, businessRegistrationNumber, representativeName,
                address, addressDetail, fileUrl, ocrRaw, businessStartDate));

        // 진위확인 결과와 무관하게 VERIFIED 로 승격한다(#1324 운영 결정) — 국세청 SKIPPED(키 미설정·장애
        // fail-open)도 통과시킨다. 확정 불량(MISMATCH/SUSPENDED/CLOSED)은 이 지점에 도달하기 전
        // CompanySignupService 가 이미 가입을 차단하므로, 여기 오는 회사는 "불량으로 확정되지 않은" 회사다.
        // VERIFIED 는 스코프 판정·DB 트리거의 필수 조건이라 PENDING 으로 남기면 점검 생성이 막힌다.
        company.markBusinessVerified();

        // 가입 즉시 자동승인(#1324) — 관리자 승인 화면·API 미배선 + 프론트가 승인 대기 단계를 제거한 상태라
        // PENDING_REVIEW 로 두면 신규 기업이 영구 미승인으로 남는다.
        company.autoApprove();

        // 상호 FK 배선 — dirty checking 으로 커밋 시 users.company_id 업데이트.
        user.assignToCompany(company.getId());

        // 오너의 유효 APPROVED 멤버십 발급(#1324) — 스코프 판정의 세 번째 조건. assignToCompany 와 같은
        // 트랜잭션이라 커밋 시점에 users.company_id = company_memberships.company_id 가 일치한다
        // (스코프 쿼리·DB 트리거가 이 일치를 요구한다). approvedOwner 는 status=APPROVED 와 함께
        // approved_at 을 채우므로 DB check 제약(ck_company_memberships_approved_at)을 만족한다.
        // invited_by=null = 오너의 최초 멤버십(초대자 없음, V1 컬럼 주석과 정합).
        companyMembershipRepository.save(
                CompanyMembership.approvedOwner(company.getId(), user.getId()));

        userConsentRepository.saveAll(List.of(
                UserConsent.of(user.getId(), ConsentPolicyType.TERMS_OF_SERVICE, termsVersion),
                UserConsent.of(user.getId(), ConsentPolicyType.PRIVACY_POLICY, privacyVersion)
        ));

        planProvisioningService.ensureFreePlanForCompany(company.getId());

        return company;
    }
}
