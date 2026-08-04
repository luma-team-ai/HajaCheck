package com.hajacheck.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hajacheck.auth.config.AuthProperties;
import com.hajacheck.auth.config.FileStorageProperties;
import com.hajacheck.auth.config.PolicyProperties;
import com.hajacheck.auth.dto.CompanySignupRequest;
import com.hajacheck.auth.dto.CompanySignupResponse;
import com.hajacheck.auth.dto.SignupStatusResponse;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.support.BusinessLicenseUploadValidator;
import com.hajacheck.auth.support.FileStorageService;
import com.hajacheck.auth.support.FileStorageService.StoredFile;
import com.hajacheck.auth.support.TokenNamespaces;
import com.hajacheck.auth.support.TokenStore;
import com.hajacheck.bizverify.service.NtsBusinessVerifyClient;
import com.hajacheck.bizverify.service.NtsVerificationOutcome;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.Instant;
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

    private static final String FILE_CATEGORY = "business-registration";

    /**
     * jsonb 조립 전용 매퍼 — 빈으로 주입하지 않고 클래스 상수로 둔다. 애플리케이션 전역 ObjectMapper 는
     * 직렬화 설정이 바뀔 수 있는데, 여기서 만드는 값은 DB에 그대로 적재되는 <b>영속 감사 기록</b>이라
     * 전역 설정 변화에 흔들리면 안 된다(그리고 생성자 주입을 늘리지 않아 단위 테스트가 영향받지 않는다).
     */
    private static final ObjectMapper OCR_RAW_MAPPER = new ObjectMapper();

    /** OCR 원본 stub — 실제 OCR 연동 전까지 "수동 입력"임을 나타내는 출처 표기(V1 컬럼 주석 참조). */
    private static final String OCR_SOURCE_MANUAL_INPUT = "MANUAL_INPUT";

    /**
     * {@link #buildOcrRaw} 직렬화가 실패했을 때만 쓰는 최소 stub — <b>손으로 조립한 유일한 JSON 리터럴</b>
     * 이라 상수로 고정한다(가변값이 섞이지 않으므로 이스케이프 문제가 원천적으로 없다).
     * ⚠️ 여기엔 {@code ntsOutcome} 이 없다 = "증명할 수 없음" → {@code Company#isNtsVerified} 가 false 로
     * 판정한다. fail-safe 방향이라 의도된 동작이다.
     */
    private static final String OCR_RAW_FALLBACK = "{\"source\":\"" + OCR_SOURCE_MANUAL_INPUT + "\"}";

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
     * 회원가입: ①이메일/사업자번호 선검사(조기 409) ②국세청 진위확인(확정 불량이면 차단)
     * ③업로드 파일 사전 검증(선언↔실제 타입 일치·디코딩 가능성) ④파일 저장(트랜잭션 밖 IO)
     * ⑤User+Company(VERIFIED·APPROVED)+오너 멤버십+Consents 원자저장(writer) ⑥가입상태 토큰 발급
     * ⑦마스킹 응답.
     *
     * <p>응답 {@code status} 는 #1324 부터 항상 {@code APPROVED} 다 — 승인 대기 단계가 없다.
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

        // ② 국세청 진위확인(트랜잭션·파일저장 전 외부 호출). 진위 불일치·휴/폐업·미등록은 가입 차단,
        //    외부 장애·미설정은 fail-open(스킵) — 정상 가입을 막지 않는다(#596).
        //    ⚠️ 이 차단 로직은 #1324 자동승인의 유일한 품질 게이트다(확정 불량만 걸러낸다) — 풀지 말 것.
        NtsVerificationOutcome verification = ntsBusinessVerifyClient.validate(
                normalizedBrn, request.representativeName(), request.businessStartDate());
        if (isVerificationBlocked(verification)) {
            throw new BusinessException(ErrorCode.AUTH_BUSINESS_VERIFICATION_FAILED);
        }

        // ③ 업로드 파일 사전 검증(#1488) — 선언 Content-Type ↔ 실제 매직바이트 일치 + 이미지 디코딩
        //    가능성. 클라이언트가 자유롭게 바꿀 수 있는 Content-Type 헤더만 믿고 분기하지 않는다.
        BusinessLicenseUploadValidator.validate(request.businessRegistrationFile());

        // ④ 파일 저장(트랜잭션 밖). 검증 실패는 FILE_* 로 던진다.
        StoredFile stored = fileStorage.store(request.businessRegistrationFile(), FILE_CATEGORY,
                fileStorageProperties.getAllowedContentTypes(), fileStorageProperties.getMaxSizeBytes());

        // ⑤ 원자저장(회사 VERIFIED+APPROVED, 오너 멤버십 포함) — 실패 시 파일 보상삭제.
        Company company;
        try {
            String passwordHash = passwordEncoder.encode(request.password());
            company = accountWriter.createAccount(
                    email, request.representativeName(), passwordHash,
                    request.companyName(), normalizedBrn, request.address(), request.addressDetail(),
                    stored.url(), buildOcrRaw(verification),
                    policyProperties.getTermsVersion(), policyProperties.getPrivacyVersion(),
                    request.businessStartDate());
        } catch (DataIntegrityViolationException e) {
            // 선검사와 저장 사이의 경합(동시 가입) — unique 위반. 파일 정리 후 email/brn 구분해 409.
            //
            // 아래 분기는 "이메일 아니면 사업자번호"로 단정하는 휴리스틱이라 오분류가 가능하다 —
            // #1324 로 오너 멤버십 INSERT 가 추가되면서 company_memberships 제약 위반
            // (uk_company_memberships_company_user·uq_company_memberships_approved_user)도 같은
            // catch 로 들어올 수 있게 됐다. 원인 추적 수단을 남긴다.
            // ⚠️ 예외 메시지 원문에는 위반 행의 값(이메일·사업자번호)이 섞여 나올 수 있으므로
            //    원문을 찍지 않고 예외 클래스명만 남긴다(개인정보 로그 유출 방지).
            log.warn("기업 가입 원자저장 무결성 위반 — exception={} (이메일/사업자번호 중복으로 매핑 시도)",
                    e.getClass().getSimpleName());
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

        // 운영 가시성용 로그(#1324). 감사의 **진실 소스는 로그가 아니라 DB**다 —
        // companies.business_registration_ocr_raw.ntsOutcome 에 같은 값이 영속된다(buildOcrRaw).
        // 컨테이너 로그는 회전·유실되므로 여기에만 의존하면 배포 후 재구성이 불가능하다.
        // 개인정보(이메일·사업자번호·대표자명)는 남기지 않는다.
        log.info("기업 가입 자동승인(#1324) — companyId={}, 국세청 진위확인 결과={}",
                company.getId(), verification);

        // ⑥ 가입 상태 토큰(장기, peek 용) — 값은 companyId.
        String signupToken = tokenStore.issue(
                TokenNamespaces.SIGNUP_STATUS,
                company.getId().toString(),
                authProperties.getSignupStatusTtl());

        // ⑦ 마스킹 응답.
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
     * companies.business_registration_ocr_raw(jsonb, "감사·재처리용")에 적재할 값을 조립한다(#1324 P1).
     *
     * <p><b>왜 필요한가</b>: 자동승인은 진위확인 결과와 무관하게 회사를 VERIFIED 로 만든다. 그러면
     * 국세청이 실제로 확인해 준 회사({@code VERIFIED})와 키 미설정·장애로 확인하지 못한 회사
     * ({@code SKIPPED})의 companies 행이 완전히 같아져, 배포 후에는 "검증 없이 승인된 회사"를
     * 재구성할 방법이 사라진다. 그 provenance 를 스키마 변경 없이 기존 jsonb 컬럼에 남긴다.
     *
     * <p><b>⚠️ {@code ntsOutcome} 의 값 공간은 enum 이 아니다 — {@code valueOf()} 로 파싱하지 말 것.</b>
     * 여기서 쓰는 {@link NtsVerificationOutcome} 라벨에 더해, V38 마이그레이션이 심는
     * {@code UNKNOWN_BACKFILL}(소급 승인분 = 검증한 적 없음)·{@code LEGACY_VERIFIED}(#1324 이전에 진짜
     * 검증을 통과한 기존 회사)가 같은 키를 공유하고, 키가 <b>아예 없는</b> 행도 있다({@link #OCR_RAW_FALLBACK}
     * 낙하분·컬럼 null·V38 이전 데이터). 판정은 문자열 화이트리스트로 한다({@code Company#isNtsVerified}).
     * 시각 키도 출처에 따라 갈린다: {@code ntsCheckedAt}(여기, 실제 조회 시각) vs
     * {@code ntsBackfilledAt}(V38, 소급 스탬프 시각).
     *
     * <p>집계 쿼리(신규 가입 + V38 소급분을 한 번에) — "국세청 검증을 증명할 수 없는 회사":
     * {@code select count(*) from companies
     *        where business_registration_ocr_raw->>'ntsOutcome' not in ('VERIFIED','LEGACY_VERIFIED')
     *           or business_registration_ocr_raw->>'ntsOutcome' is null}
     *
     * <p>⚠️ 개인정보 금지 — enum 라벨과 타임스탬프만 남긴다(사업자번호·대표자명·이메일 절대 금지).
     * 문자열을 손으로 이어붙이지 않고 Jackson 으로 직렬화해 이스케이프를 보장한다(jsonb 는 문법이
     * 깨지면 flush 시점 원시 SQL 예외로 샌다 — {@code JsonValidator} 가 그 앞단을 지킨다).
     */
    private static String buildOcrRaw(NtsVerificationOutcome verification) {
        ObjectNode node = OCR_RAW_MAPPER.createObjectNode();
        node.put("source", OCR_SOURCE_MANUAL_INPUT);
        node.put("ntsOutcome", verification.name());
        node.put("ntsCheckedAt", Instant.now().toString());
        try {
            return OCR_RAW_MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // 고정 스키마(문자열 3개)라 실패할 수 없지만, 실패하더라도 가입 자체를 막지는 않는다 —
            // provenance 기록 실패가 회원가입 장애로 번지면 안 된다. 최소 stub 으로 낙하한다.
            log.warn("OCR 원본 provenance 직렬화 실패 — stub 으로 대체. exception={}",
                    e.getClass().getSimpleName());
            return OCR_RAW_FALLBACK;
        }
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
