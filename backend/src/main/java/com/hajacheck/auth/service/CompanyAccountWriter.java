package com.hajacheck.auth.service;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.ConsentPolicyType;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserConsent;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserConsentRepository;
import com.hajacheck.auth.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * User + Company + UserConsent(약관 2건) 원자 저장 전담 — 별도 빈으로 분리해 self-invocation 회피
 * (같은 클래스 내부 호출은 @Transactional 프록시가 안 걸리므로, 트랜잭션 경계를 별도 빈으로 확보).
 *
 * <p>저장 순서(FK 정합): users.company_id 는 nullable → 유저 먼저 저장(company_id=null) →
 * 회사 저장(owner_user_id=user.id) → user.assignToCompany(company.id)(dirty flush) → 동의 이력 saveAll.
 * users↔companies 상호 FK 를 유저 선삽입 + 사후 업데이트로 순환 없이 해소한다.
 *
 * <p>이메일/사업자번호 unique 위반은 여기서 DataIntegrityViolationException 으로 전파되고,
 * 호출부(CompanySignupService)가 파일 보상삭제 + 409 매핑을 담당한다.
 */
@Component
@RequiredArgsConstructor
public class CompanyAccountWriter {

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final UserConsentRepository userConsentRepository;

    /**
     * @return 저장된 Company(관리 상태, id 채워짐)
     */
    @Transactional
    public Company createAccount(String email, String representativeName, String passwordHash,
                                 String companyName, String businessRegistrationNumber,
                                 String address, String addressDetail,
                                 String fileUrl, String ocrRaw,
                                 String termsVersion, String privacyVersion) {

        // user.name = 대표자명(표시명). role=USER, status=ACTIVE.
        User user = userRepository.save(User.createCompanyOwner(email, representativeName, passwordHash));

        Company company = companyRepository.save(Company.createPendingReview(
                user.getId(), companyName, businessRegistrationNumber, representativeName,
                address, addressDetail, fileUrl, ocrRaw));

        // 상호 FK 배선 — dirty checking 으로 커밋 시 users.company_id 업데이트.
        user.assignToCompany(company.getId());

        userConsentRepository.saveAll(List.of(
                UserConsent.of(user.getId(), ConsentPolicyType.TERMS_OF_SERVICE, termsVersion),
                UserConsent.of(user.getId(), ConsentPolicyType.PRIVACY_POLICY, privacyVersion)
        ));

        return company;
    }
}
