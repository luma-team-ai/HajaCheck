package com.hajacheck.bizverify.service;

import com.hajacheck.bizverify.config.BizVerifyProperties;
import com.hajacheck.bizverify.dto.NtsValidateRequest;
import com.hajacheck.bizverify.dto.NtsValidateResponse;
import com.hajacheck.global.exception.ErrorCode;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 국세청 사업자등록정보 진위확인 클라이언트(#596) — data.go.kr
 * "국세청_사업자등록정보 진위확인 및 상태조회" validate API 를 호출한다. core.ai 의 AiProxyService 와
 * 동일한 예외 매핑 골격을 따른다(WebClient 금지, 내장 RestClient 사용).
 *
 * <p><b>fail-open 정책</b>: serviceKey 미설정 또는 국세청 API 장애(연결 실패·타임아웃·5xx·4xx·응답 파싱
 * 실패)는 예외를 던지지 않고 {@link NtsVerificationOutcome#SKIPPED} 를 반환한다 — 외부 의존성 문제로
 * 정상 가입을 막지 않기 위함이다. NTS_* ErrorCode 는 구조화 로깅(경보)용으로만 쓴다(응답 미노출).
 *
 * <p><b>개인정보 로깅 금지</b>: 사업자등록번호·대표자명·개업일자는 로그에 남기지 않는다(결과 코드만 기록).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NtsBusinessVerifyClient {

    private static final String VALIDATE_PATH = "/api/nts-businessman/v1/validate";
    // 국세청 status.b_stt_cd 코드 — 01 계속사업자 / 02 휴업 / 03 폐업.
    private static final String STT_CONTINUING = "01";
    private static final String STT_SUSPENDED = "02";
    private static final String STT_CLOSED = "03";
    // 국세청 valid 코드 — 01 일치 / 02 불일치.
    private static final String VALID_MATCH = "01";
    private static final String VALID_MISMATCH = "02";

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
            NtsValidateResponse response = bizVerifyRestClient.post()
                    // serviceKey 는 반드시 percent-encoding 되어야 한다(data.go.kr "Decoding" 키는 +,/,= 를
                    // 포함할 수 있고, 미인코딩 시 서버가 + 를 공백으로 해석 → 인증실패 → 조용한 no-op). 리터럴
                    // queryParam 값은 DefaultUriBuilderFactory(TEMPLATE_AND_VALUES)가 인코딩하지 않으므로,
                    // URI 템플릿 변수({serviceKey})로 넘겨 변수값 인코딩을 강제한다.
                    .uri(uriBuilder -> uriBuilder.path(VALIDATE_PATH)
                            .queryParam("serviceKey", "{serviceKey}")
                            .build(bizVerifyProperties.getServiceKey()))
                    .body(body)
                    .retrieve()
                    .body(NtsValidateResponse.class);
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
