package com.hajacheck.bizverify.service;

import com.hajacheck.bizverify.config.BizVerifyProperties;
import com.hajacheck.bizverify.dto.NtsStatusRequest;
import com.hajacheck.bizverify.dto.NtsStatusResponse;
import com.hajacheck.bizverify.dto.NtsValidateRequest;
import com.hajacheck.bizverify.dto.NtsValidateResponse;
import com.hajacheck.global.exception.ErrorCode;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 국세청 사업자등록정보 진위확인 클라이언트(#596, 상태조회 조합은 #648) — data.go.kr
 * "국세청_사업자등록정보 진위확인 및 상태조회" API 를 호출한다. core.ai 의 AiProxyService 와
 * 동일한 예외 매핑 골격을 따른다(WebClient 금지, 내장 RestClient 사용).
 *
 * <p>{@link #validate}는 회원가입 게이트({@code CompanySignupService})가 쓰는 validate API 단독 호출,
 * {@link #verifyRealtime}는 실시간 진위확인 전용 API(#648)가 쓰는 status+validate 조합 호출이다.
 *
 * <p><b>fail-open 정책</b>: serviceKey 미설정 또는 국세청 API 장애(연결 실패·타임아웃·5xx·4xx·응답 파싱
 * 실패)는 예외를 던지지 않고 {@link NtsVerificationOutcome#SKIPPED} 를 반환한다 — 외부 의존성 문제로
 * 정상 가입을 막지 않기 위함이다. NTS_* ErrorCode 는 구조화 로깅(경보)용으로만 쓴다(응답 미노출).
 *
 * <p><b>일시적 실패 재시도(#880)</b>: 2026-07-26 조사 결과 odcloud 국세청 게이트웨이가 간헐적으로
 * 503(즉답, ~0.06s)과 20초+ 타임아웃을 함께 반환하는 것으로 실측됐다. {@link #executeWithRetry}가
 * 연결 실패({@link ResourceAccessException})와 5xx({@link RestClientResponseException})에 한해
 * {@code biz-verify.retry-max-attempts}(기본 1, 총 최대 2회 시도)만큼 {@code retry-backoff-ms}(기본
 * 300ms) 간격으로 재시도한다. 4xx·응답 파싱 실패({@link RestClientException})·해석 불가는 재시도해도
 * 결과가 같으므로 재시도하지 않는다(즉시 fail-open).
 *
 * <p><b>대기 시간 상한 근거</b>: {@code read-timeout-ms}=8000(5000→8000 상향) 기준, 호출 1건의
 * 이론상 최악 대기는 (읽기타임아웃 8s + 백오프 0.3s) × 최대 2회 시도 ≈ 16.3s. {@link #verifyRealtime}은
 * status→validate 2단계라 이 둘이 모두 최악으로 겹치면 이론상 최대 ≈32.6s로 애초 목표(25s) 를
 * 넘는다 — 다만 실측상 5xx는 대부분 즉답(0.06s)이라 "두 단계 모두 진짜 타임아웃(20초+)이 겹치는" 경우는
 * 극히 드문 이중 장애 시나리오이고, 이 경로는 어차피 fail-open(UNAVAILABLE)이라 사용자 가입 자체를
 * 막지 않는다. 재시도 횟수를 더 낮추면 정작 재시도가 필요한 일반적 5xx/짧은 지연 케이스의 복원력이
 * 줄어들므로, 이 극단적 이중 타임아웃 케이스는 상한 초과를 감수하고 재시도 횟수를 유지한다(빈도 재발 시
 * {@code retry-max-attempts}를 0으로 낮추는 것으로 즉시 완화 가능 — 설정값이라 재배포만 필요).
 *
 * <p><b>개인정보 로깅 금지</b>: 사업자등록번호·대표자명·개업일자는 로그에 남기지 않는다(결과 코드만 기록).
 * 재시도 로그도 동일 규칙을 따른다(예외 클래스명/상태코드만 기록, 응답바디·스택 미로깅).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NtsBusinessVerifyClient {

    private static final String VALIDATE_PATH = "/api/nts-businessman/v1/validate";
    private static final String STATUS_PATH = "/api/nts-businessman/v1/status";
    // 국세청 status.b_stt_cd 코드 — 01 계속사업자 / 02 휴업 / 03 폐업.
    private static final String STT_CONTINUING = "01";
    private static final String STT_SUSPENDED = "02";
    private static final String STT_CLOSED = "03";
    // 국세청 valid 코드 — 01 일치 / 02 불일치.
    private static final String VALID_MATCH = "01";
    private static final String VALID_MISMATCH = "02";
    // 상태조회(status) 응답의 tax_type 에 담기는 미등록 안내문 — 정확한 구두점은 국세청 응답에 따라
    // 달라질 수 있어(문헌마다 표기가 조금씩 다름) 부분 일치로 방어적으로 판정한다.
    private static final String NOT_REGISTERED_MARKER = "등록되지 않은 사업자등록번호";

    private final RestClient bizVerifyRestClient;
    private final BizVerifyProperties bizVerifyProperties;

    /**
     * 사업자등록번호+대표자명+개업일자를 국세청 등록정보와 대조한다.
     *
     * @param normalizedBrn      하이픈 제거된 사업자등록번호(숫자 10자리)
     * @param representativeName 대표자명
     * @param businessStartDate  개업일자
     * @return 진위확인 결과. 외부 실패·미설정은 {@link NtsVerificationOutcome#SKIPPED}(가입 진행).
     */
    public NtsVerificationOutcome validate(String normalizedBrn, String representativeName,
                                           LocalDate businessStartDate) {
        if (!StringUtils.hasText(bizVerifyProperties.getServiceKey())) {
            log.info("사업자 진위확인 스킵: serviceKey 미설정 — fail-open 가입 진행(PENDING)");
            return NtsVerificationOutcome.SKIPPED;
        }

        NtsValidateRequest body = new NtsValidateRequest(List.of(new NtsValidateRequest.Business(
                normalizedBrn,
                businessStartDate.format(DateTimeFormatter.BASIC_ISO_DATE), // YYYYMMDD
                representativeName)));

        try {
            NtsValidateResponse response = executeWithRetry(() -> bizVerifyRestClient.post()
                    // serviceKey 는 반드시 percent-encoding 되어야 한다(data.go.kr "Decoding" 키는 +,/,= 를
                    // 포함할 수 있고, 미인코딩 시 서버가 + 를 공백으로 해석 → 인증실패 → 조용한 no-op). 리터럴
                    // queryParam 값은 DefaultUriBuilderFactory(TEMPLATE_AND_VALUES)가 인코딩하지 않으므로,
                    // URI 템플릿 변수({serviceKey})로 넘겨 변수값 인코딩을 강제한다.
                    .uri(uriBuilder -> uriBuilder.path(VALIDATE_PATH)
                            .queryParam("serviceKey", "{serviceKey}")
                            .build(bizVerifyProperties.getServiceKey()))
                    .body(body)
                    .retrieve()
                    .body(NtsValidateResponse.class));
            return interpret(response);
        } catch (ResourceAccessException e) {
            // 연결 실패/타임아웃 — 원인으로 UNREACHABLE/TIMEOUT 구분(로깅용). 모두 fail-open.
            // (ResourceAccessException 메시지엔 쿼리·응답바디가 실리지 않아 e 스택 로깅 무방.)
            ErrorCode code = classifyConnectionFailure(e);
            log.warn("사업자 진위확인 외부 호출 실패: {} — fail-open 가입 진행(PENDING)", code, e);
            return NtsVerificationOutcome.SKIPPED;
        } catch (RestClientResponseException e) {
            // 국세청이 HTTP 오류(4xx/5xx)로 응답 — 서버에 도달했으나 거부(예: 키 만료)된 것이므로
            // "서버 다운(UNREACHABLE)"이 아닌 NTS_REQUEST_REJECTED 로 로깅한다. 판정 불가라 fail-open.
            log.warn("사업자 진위확인 HTTP 오류: {} (status={}) — fail-open 가입 진행(PENDING)",
                    ErrorCode.NTS_REQUEST_REJECTED, e.getStatusCode().value());
            return NtsVerificationOutcome.SKIPPED;
        } catch (RestClientException e) {
            // 응답 역직렬화 실패 등 형식 불량 — fail-open. ⚠️ Jackson 예외 메시지엔 원본 응답바디
            // 스니펫(b_no=사업자번호 등 개인정보)이 실릴 수 있으므로 e 스택을 로깅하지 않고 예외 클래스명만 남긴다.
            log.warn("사업자 진위확인 응답 처리 실패: {} (cause={}) — fail-open 가입 진행(PENDING)",
                    ErrorCode.NTS_INVALID_RESPONSE, e.getClass().getSimpleName());
            return NtsVerificationOutcome.SKIPPED;
        }
    }

    /**
     * 실시간 진위확인 전용 API(#648, {@code POST /api/auth/business-verification})가 사용하는 조합
     * 판정. 회원가입 게이트({@link #validate})와 달리 <b>상태조회(status) API를 먼저 호출</b>해
     * "미등록"과 "등록됐으나 이름/개업일 불일치"를 구분한다({@link NtsValidateResponse}의 valid=02는 이
     * 둘을 구분하지 못한다 — 클래스 상단 및 {@link NtsVerificationOutcome#NOT_REGISTERED} 참고).
     *
     * <p>흐름: ①상태조회 → 미등록이면 즉시 {@link NtsVerificationOutcome#NOT_REGISTERED} ②휴업/폐업이면
     * 즉시 SUSPENDED/CLOSED(이름·개업일 대조가 의미 없다) ③계속사업자(01)일 때만 {@link #validate}를
     * 호출해 VERIFIED/MISMATCH를 확정한다. 상태조회 자체가 실패(미설정·연결실패·타임아웃·5xx·파싱실패)하면
     * {@link #validate}를 호출하지 않고 fail-open(SKIPPED)한다 — 호출부(BusinessVerificationService)가
     * 사용자 대면 결과 UNAVAILABLE로 매핑한다.
     *
     * @param normalizedBrn      하이픈 제거된 사업자등록번호(숫자 10자리)
     * @param representativeName 대표자명
     * @param businessStartDate  개업일자
     * @return 실시간 진위확인 결과(회원가입 게이트와 달리 NOT_REGISTERED를 반환할 수 있다)
     */
    public NtsVerificationOutcome verifyRealtime(String normalizedBrn, String representativeName,
                                                 LocalDate businessStartDate) {
        if (!StringUtils.hasText(bizVerifyProperties.getServiceKey())) {
            log.info("사업자 진위확인(실시간) 스킵: serviceKey 미설정 — fail-open(UNAVAILABLE)");
            return NtsVerificationOutcome.SKIPPED;
        }

        NtsStatusResponse statusResponse;
        try {
            statusResponse = executeWithRetry(() -> bizVerifyRestClient.post()
                    // serviceKey 인코딩 규칙은 validate()와 동일(클래스 상단 주석 참고).
                    .uri(uriBuilder -> uriBuilder.path(STATUS_PATH)
                            .queryParam("serviceKey", "{serviceKey}")
                            .build(bizVerifyProperties.getServiceKey()))
                    .body(new NtsStatusRequest(List.of(normalizedBrn)))
                    .retrieve()
                    .body(NtsStatusResponse.class));
        } catch (ResourceAccessException e) {
            ErrorCode code = classifyConnectionFailure(e);
            log.warn("사업자 진위확인(실시간) 상태조회 호출 실패: {} — fail-open(UNAVAILABLE)", code, e);
            return NtsVerificationOutcome.SKIPPED;
        } catch (RestClientResponseException e) {
            log.warn("사업자 진위확인(실시간) 상태조회 HTTP 오류: {} (status={}) — fail-open(UNAVAILABLE)",
                    ErrorCode.NTS_REQUEST_REJECTED, e.getStatusCode().value());
            return NtsVerificationOutcome.SKIPPED;
        } catch (RestClientException e) {
            // ⚠️ Jackson 예외 메시지엔 원본 응답바디 스니펫(b_no 등)이 실릴 수 있어 e 스택 로깅하지 않는다.
            log.warn("사업자 진위확인(실시간) 상태조회 응답 처리 실패: {} (cause={}) — fail-open(UNAVAILABLE)",
                    ErrorCode.NTS_INVALID_RESPONSE, e.getClass().getSimpleName());
            return NtsVerificationOutcome.SKIPPED;
        }

        NtsVerificationOutcome statusOutcome = interpretStatus(statusResponse);
        if (statusOutcome != null) {
            return statusOutcome; // NOT_REGISTERED / SUSPENDED / CLOSED / SKIPPED(파싱 불가)
        }

        // 상태조회가 계속사업자(01)를 확인했을 때만 validate() 로 대표자명·개업일자 일치 여부를 확정한다.
        return validate(normalizedBrn, representativeName, businessStartDate);
    }

    /**
     * 상태조회 응답을 해석한다. NOT_REGISTERED/SUSPENDED/CLOSED/SKIPPED(해석 불가) 중 하나면 그 값을,
     * 계속사업자(01)라 {@link #validate} 로 넘겨야 하면 {@code null} 을 반환한다.
     */
    private NtsVerificationOutcome interpretStatus(NtsStatusResponse response) {
        if (response == null || response.data() == null || response.data().isEmpty()) {
            log.warn("사업자 진위확인(실시간) 상태조회 응답 비정상(data 없음): {} — fail-open(UNAVAILABLE)",
                    ErrorCode.NTS_INVALID_RESPONSE);
            return NtsVerificationOutcome.SKIPPED;
        }
        NtsStatusResponse.BusinessStatus item = response.data().get(0);
        if (item == null) {
            log.warn("사업자 진위확인(실시간) 상태조회 응답 비정상(항목 없음): {} — fail-open(UNAVAILABLE)",
                    ErrorCode.NTS_INVALID_RESPONSE);
            return NtsVerificationOutcome.SKIPPED;
        }

        if (StringUtils.hasText(item.taxType()) && item.taxType().contains(NOT_REGISTERED_MARKER)) {
            return NtsVerificationOutcome.NOT_REGISTERED;
        }

        String sttCd = item.bSttCd();
        if (STT_SUSPENDED.equals(sttCd)) {
            return NtsVerificationOutcome.SUSPENDED;
        }
        if (STT_CLOSED.equals(sttCd)) {
            return NtsVerificationOutcome.CLOSED;
        }
        if (STT_CONTINUING.equals(sttCd)) {
            return null; // 계속사업자 — validate() 로 위임(호출부에서 처리)
        }
        // 01/02/03 외 미상 코드(빈 값 포함, 미등록 마커도 없음) — 해석 불가 → fail-open.
        log.warn("사업자 진위확인(실시간) 상태조회 응답 미상 b_stt_cd: {} — fail-open(UNAVAILABLE)",
                ErrorCode.NTS_INVALID_RESPONSE);
        return NtsVerificationOutcome.SKIPPED;
    }

    /**
     * 국세청 응답을 가입 판정 결과로 해석한다. 해석 불가(데이터 없음·미상 valid)는 fail-open(SKIPPED).
     */
    private NtsVerificationOutcome interpret(NtsValidateResponse response) {
        if (response == null || response.data() == null || response.data().isEmpty()) {
            log.warn("사업자 진위확인 응답 비정상(data 없음): {} — fail-open", ErrorCode.NTS_INVALID_RESPONSE);
            return NtsVerificationOutcome.SKIPPED;
        }
        NtsValidateResponse.ValidatedBusiness item = response.data().get(0);
        if (item == null || !StringUtils.hasText(item.valid())) {
            log.warn("사업자 진위확인 응답 비정상(valid 없음): {} — fail-open", ErrorCode.NTS_INVALID_RESPONSE);
            return NtsVerificationOutcome.SKIPPED;
        }

        if (VALID_MISMATCH.equals(item.valid())) {
            return NtsVerificationOutcome.MISMATCH; // 불일치(미등록 포함)
        }
        if (!VALID_MATCH.equals(item.valid())) {
            // 01/02 외 예상 밖 코드 — 해석 불가 → fail-open(정상 가입을 잘못 차단하지 않는다).
            log.warn("사업자 진위확인 응답 미상 valid 코드: {} — fail-open", ErrorCode.NTS_INVALID_RESPONSE);
            return NtsVerificationOutcome.SKIPPED;
        }

        // valid == 01(일치) → 사업 상태로 세분.
        String sttCd = item.status() == null ? null : item.status().bSttCd();
        if (STT_CONTINUING.equals(sttCd)) {
            return NtsVerificationOutcome.VERIFIED;
        }
        if (STT_SUSPENDED.equals(sttCd)) {
            return NtsVerificationOutcome.SUSPENDED;
        }
        if (STT_CLOSED.equals(sttCd)) {
            return NtsVerificationOutcome.CLOSED;
        }
        // 일치하지만 상태 미상(빈 b_stt_cd 등) — 보수적으로 차단.
        return NtsVerificationOutcome.MISMATCH;
    }

    /**
     * 일시적 실패(연결 실패·타임아웃·5xx)에만 짧게 재시도하는 공통 헬퍼(#880) — {@link #validate}와
     * {@link #verifyRealtime}의 status 호출 양쪽에서 재사용한다(SRP, 복붙 금지).
     *
     * <p>재시도 대상: {@link ResourceAccessException}(연결 실패·타임아웃), 5xx
     * {@link RestClientResponseException}. <b>재시도하지 않음</b>: 4xx {@link RestClientResponseException}
     * (키 거부·요청 오류 — 재시도해도 동일), 그 외 {@link RestClientException}(응답 파싱 실패 등, 재시도해도
     * 동일 실패). 재시도 소진 시 마지막 예외를 그대로 던져 호출부의 기존 fail-open catch 블록이 처리한다
     * (정책·로깅 변경 없음).
     *
     * @param httpCall {@code RestClient} 호출+역직렬화(예: {@code .retrieve().body(Class)})
     */
    private <T> T executeWithRetry(Supplier<T> httpCall) {
        int maxAttempts = bizVerifyProperties.getRetryMaxAttempts() + 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return httpCall.get();
            } catch (ResourceAccessException e) {
                if (attempt >= maxAttempts) {
                    throw e;
                }
                log.warn("사업자 진위확인 외부 호출 일시 실패({}) — {}ms 후 재시도({}/{})",
                        e.getClass().getSimpleName(), bizVerifyProperties.getRetryBackoffMs(), attempt, maxAttempts - 1);
                sleepBackoff();
            } catch (RestClientResponseException e) {
                if (!e.getStatusCode().is5xxServerError() || attempt >= maxAttempts) {
                    throw e;
                }
                log.warn("사업자 진위확인 외부 호출 5xx(status={}) — {}ms 후 재시도({}/{})",
                        e.getStatusCode().value(), bizVerifyProperties.getRetryBackoffMs(), attempt, maxAttempts - 1);
                sleepBackoff();
            }
        }
        // 위 루프는 attempt==maxAttempts 에서 항상 throw 하므로 도달 불가(컴파일러용).
        throw new IllegalStateException("executeWithRetry: unreachable");
    }

    private void sleepBackoff() {
        try {
            Thread.sleep(bizVerifyProperties.getRetryBackoffMs());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * {@link ResourceAccessException} 원인을 연결 실패(UNREACHABLE)와 응답 지연(TIMEOUT)으로 구분한다
     * (AiProxyService 와 동일 규칙 — JdkClientHttpRequestFactory 기준). 로깅용 분류일 뿐 모두 fail-open.
     */
    private ErrorCode classifyConnectionFailure(ResourceAccessException e) {
        Throwable cause = e.getCause();
        if (cause instanceof HttpConnectTimeoutException) {
            return ErrorCode.NTS_SERVER_UNREACHABLE;
        }
        if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
            return ErrorCode.NTS_SERVER_TIMEOUT;
        }
        return ErrorCode.NTS_SERVER_UNREACHABLE;
    }
}
