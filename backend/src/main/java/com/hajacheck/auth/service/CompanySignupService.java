package com.hajacheck.auth.service;

import com.hajacheck.auth.config.AuthProperties;
import com.hajacheck.auth.config.FileStorageProperties;
import com.hajacheck.auth.config.PolicyProperties;
import com.hajacheck.auth.dto.CompanySignupRequest;
import com.hajacheck.auth.dto.CompanySignupResponse;
import com.hajacheck.auth.dto.SignupStatusResponse;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.support.FileStorageService;
import com.hajacheck.auth.support.FileStorageService.StoredFile;
import com.hajacheck.auth.support.TokenNamespaces;
import com.hajacheck.auth.support.TokenStore;
import com.hajacheck.bizverify.service.NtsBusinessVerifyClient;
import com.hajacheck.bizverify.service.NtsVerificationOutcome;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기업 회원가입 오케스트레이션.
 *
 * <p>signup() 은 트랜잭션 밖(no-tx)에서 실행한다: 파일 저장(IO)은 트랜잭션 밖에서 먼저 수행하고,
 * DB 원자저장은 {@link CompanyAccountWriter}(별도 @Transactional 빈)에 위임한다. 이렇게 해야
 * 긴 IO 가 DB 커넥션/트랜잭션을 점유하지 않고, writer 의 REQUIRED 트랜잭션이 새로 열린다.
 *
 * <p>실패 보상: writer 가 예외를 던지면 저장한 파일을 삭제(보상삭제)하고, unique 위반은 email/brn 을
 * 구분해 409 로 매핑한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanySignupService {

    private static final String OCR_STUB_RAW = "{\"source\":\"MANUAL_INPUT\"}";
    private static final String FILE_CATEGORY = "business-registration";

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final CompanyAccountWriter accountWriter;
    private final NtsBusinessVerifyClient ntsBusinessVerifyClient;
    private final FileStorageService fileStorage;
    private final FileStorageProperties fileStorageProperties;
    private final TokenStore tokenStore;
    private final PasswordEncoder passwordEncoder;
    private final PolicyProperties policyProperties;
    private final AuthProperties authProperties;

    /**
     * 회원가입: ①이메일/사업자번호 선검사(조기 409) ②파일 저장(트랜잭션 밖 IO)
     * ③User+Company+Consents 원자저장(writer) ④가입상태 토큰 발급 ⑤마스킹 응답.
     */
    public CompanySignupResponse signup(CompanySignupRequest request) {
        String email = request.email();
        String normalizedBrn = normalizeBrn(request.businessRegistrationNumber());

        // ① 선검사 — 명확한 중복은 파일 저장 전에 조기 차단.
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.AUTH_EMAIL_DUPLICATED);
        }
        if (companyRepository.existsByBusinessRegistrationNumber(normalizedBrn)) {
            throw new BusinessException(ErrorCode.AUTH_BUSINESS_NUMBER_DUPLICATED);
        }

        // ②' 국세청 진위확인(트랜잭션·파일저장 전 외부 호출). 진위 불일치·휴/폐업·미등록은 가입 차단,
        //     외부 장애·미설정은 fail-open(스킵) — 정상 가입을 막지 않는다(#596).
        NtsVerificationOutcome verification = ntsBusinessVerifyClient.validate(
                normalizedBrn, request.representativeName(), request.businessStartDate());
        if (isVerificationBlocked(verification)) {
            throw new BusinessException(ErrorCode.AUTH_BUSINESS_VERIFICATION_FAILED);
        }
        boolean businessVerified = verification == NtsVerificationOutcome.VERIFIED;

        // ② 파일 저장(트랜잭션 밖). 검증 실패는 FILE_* 로 던진다.
        StoredFile stored = fileStorage.store(request.businessRegistrationFile(), FILE_CATEGORY,
                fileStorageProperties.getAllowedContentTypes(), fileStorageProperties.getMaxSizeBytes());

        // ③ 원자저장 — 실패 시 파일 보상삭제.
        Company company;
        try {
            String passwordHash = passwordEncoder.encode(request.password());
            company = accountWriter.createAccount(
                    email, request.representativeName(), passwordHash,
                    request.companyName(), normalizedBrn, request.address(), request.addressDetail(),
                    stored.url(), OCR_STUB_RAW,
                    policyProperties.getTermsVersion(), policyProperties.getPrivacyVersion(),
                    request.businessStartDate(), businessVerified);
        } catch (DataIntegrityViolationException e) {
            // 선검사와 저장 사이의 경합(동시 가입) — unique 위반. 파일 정리 후 email/brn 구분해 409.
            fileStorage.delete(stored.storageKey());
            if (userRepository.existsByEmail(email)) {
                throw new BusinessException(ErrorCode.AUTH_EMAIL_DUPLICATED);
            }
            throw new BusinessException(ErrorCode.AUTH_BUSINESS_NUMBER_DUPLICATED);
        } catch (RuntimeException e) {
            // 그 외 실패도 저장 파일을 남기지 않는다(고아 파일 방지).
            fileStorage.delete(stored.storageKey());
            throw e;
        }

        // ④ 가입 상태 토큰(장기, peek 용) — 값은 companyId.
        String signupToken = tokenStore.issue(
                TokenNamespaces.SIGNUP_STATUS,
                company.getId().toString(),
                authProperties.getSignupStatusTtl());

        // ⑤ 마스킹 응답.
        return CompanySignupResponse.from(company, email, signupToken);
    }

    /**
     * 이메일(아이디) 중복확인 — available=true 면 사용 가능.
     */
    @Transactional(readOnly = true)
    public boolean isEmailAvailable(String email) {
        return !userRepository.existsByEmail(email);
    }

    /**
     * 가입 상태 조회(승인 대기 새로고침) — signupToken(peek) → companyId → 회사 상태.
     */
    @Transactional(readOnly = true)
    public SignupStatusResponse getSignupStatus(String signupToken) {
        String companyId = tokenStore.peek(TokenNamespaces.SIGNUP_STATUS, signupToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_SIGNUP_TOKEN_INVALID));
        Company company = companyRepository.findById(Long.valueOf(companyId))
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_SIGNUP_TOKEN_INVALID));
        return SignupStatusResponse.from(company);
    }

    /**
     * 사업자등록번호 정규화 — 하이픈 제거(숫자 10자리 정규형). 저장·조회 전 항상 적용해 표기 차이로 인한
     * unique 우회를 막는다(계약은 하이픈 포함/미포함 모두 허용).
     */
    static String normalizeBrn(String raw) {
        return raw == null ? null : raw.replaceAll("-", "").trim();
    }

    /**
     * 국세청 진위확인 결과가 가입 차단 사유인지 판정(#596). 불일치·휴업·폐업(미등록은 불일치로 매핑)은 차단,
     * VERIFIED(성공)·SKIPPED(fail-open)는 가입 진행. 휴업 차단은 보수적 처리다(재검토 여지).
     */
    private static boolean isVerificationBlocked(NtsVerificationOutcome outcome) {
        return outcome == NtsVerificationOutcome.MISMATCH
                || outcome == NtsVerificationOutcome.SUSPENDED
                || outcome == NtsVerificationOutcome.CLOSED;
    }
}
