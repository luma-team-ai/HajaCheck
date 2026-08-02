package com.hajacheck.bizverify.service;

import com.hajacheck.bizverify.config.BizVerifyProperties;
import com.hajacheck.bizverify.dto.NtsStatusRequest;
import com.hajacheck.bizverify.dto.NtsStatusResponse;
import com.hajacheck.bizverify.dto.NtsValidateRequest;
import com.hajacheck.bizverify.dto.NtsValidateResponse;
import com.hajacheck.global.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * <p><b>일시적 실패 재시도(#880) — 실시간 경로 전용(PR #889 P1, 2026-07-26 정정)</b>: 조사 결과
 * odcloud 국세청 게이트웨이가 간헐적으로 503(즉답, ~0.06s)과 20초+ 타임아웃을 함께 반환하는 것으로
 * 실측됐다. {@link #executeWithRetry}가 연결 실패({@link ResourceAccessException})와 5xx
 * ({@link RestClientResponseException})에 한해 {@code biz-verify.retry-max-attempts}(기본 1, 총 최대
 * 2회 시도)만큼 {@code retry-backoff-ms}(기본 300ms) 간격으로 재시도한다. 4xx·응답 파싱 실패
 * ({@link RestClientException})·해석 불가는 재시도해도 결과가 같으므로 재시도하지 않는다(즉시 fail-open).
 *
 * <p><b>재시도는 {@link #verifyRealtime}(status 호출 + 내부 {@link #validateForRealtime})에만
 * 적용되고, 공개 {@link #validate}(회원가입 제출 경로)는 재시도하지 않는다.</b> 이유(PR #889 P1 리뷰):
 * {@code POST /api/auth/companies}는 permitAll + rate-limit 미적용 무인증 엔드포인트라, 요청당 외부
 * 호출 대기시간이 늘면 국세청 장애가 장기화(이 서비스는 과거에도 며칠~수주 장애 이력 반복)될 때 Tomcat
 * 워커 스레드가 고갈될 수 있다. 제출 경로는 실패해도 fail-open이라 가입이 그대로 통과하고
 * (verificationStatus=PENDING) 후속 #888(PENDING 자동 재검증 스케줄러)이 나중에 확정하므로, 재시도로
 * 얻는 이득(제출 즉시 VERIFIED 확정 확률)보다 무인증 엔드포인트의 스레드 점유 비용이 크다고 판단했다.
 * 반면 실시간 경로는 사용자가 결과를 기다리는 인터랙티브 버튼 클릭이고 전역 rate-limit(분당10/일300)으로
 * 보호되므로 재시도 가치가 있다. 이 구분을 위해 RestClient 빈도 경로별로 분리했다
 * ({@link com.hajacheck.bizverify.config.BizVerifyConfig} 참고).
 *
 * <p><b>대기 시간 상한 근거(경로별, connect-timeout 포함 — PR #889 P3 정정)</b>: {@code executeWithRetry}가
 * 재시도 대상으로 잡는 {@link ResourceAccessException}은 connect-timeout(3s)·read-timeout 양쪽에서 모두
 * 발생할 수 있어, 호출 1건의 이론상 최악 대기는 (connect-timeout + read-timeout)을 기준으로 잡는다.
 * <ul>
 *   <li><b>제출 경로</b>({@link #validate}, 재시도 없음): 단일 시도, connect(3s)+read(5s) ≈ <b>8s</b> —
 *       PR #880 이전과 동일(변경 없음). 무인증 엔드포인트의 스레드 점유 리스크가 이 PR로 늘지 않는다.
 *   <li><b>실시간 경로</b>({@link #verifyRealtime}, 재시도 최대 2회 시도): 단일 HTTP 호출 최악
 *       ≈ connect(3s)+read(8s)=11s, 재시도+백오프(0.3s) 적용 시 호출 1건 최악 ≈ 11×2+0.3 ≈ <b>22.3s</b>.
 *       status→validateForRealtime 2단계 모두 최악이 겹치면 이론상 총 최대 ≈ <b>44.6s</b>까지 갈 수
 *       있어 애초 목표(25s)를 넘는다 — 다만 이 경로는 전역 rate-limit(분당10/일300)으로 보호되는
 *       인터랙티브 버튼 클릭 경로라 무인증 대량 유입으로 인한 스레드 고갈 리스크가 없고, "두 단계 모두
 *       진짜 20초+ 타임아웃이 겹치는" 경우는 실측상 극히 드문 이중 장애 시나리오다(5xx는 대부분
 *       즉답 0.06s). 빈도 재발 시 {@code retry-max-attempts}를 0으로 낮추면 즉시 완화 가능(설정값).
 * </ul>
 *
 * <p><b>개인정보 로깅 금지</b>: 사업자등록번호·대표자명·개업일자는 로그에 남기지 않는다(결과 코드만 기록).
 * 재시도 로그도 동일 규칙을 따른다(예외 클래스명/상태코드만 기록, 응답바디·스택 미로깅).
 */
@Slf4j
@Component
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

    private final RestClient submitRestClient;
    private final RestClient realtimeRestClient;
    private final BizVerifyProperties bizVerifyProperties;

    public NtsBusinessVerifyClient(
            @Qualifier("bizVerifySubmitRestClient") RestClient submitRestClient,
            @Qualifier("bizVerifyRealtimeRestClient") RestClient realtimeRestClient,
            BizVerifyProperties bizVerifyProperties) {
        this.submitRestClient = submitRestClient;
        this.realtimeRestClient = realtimeRestClient;
        this.bizVerifyProperties = bizVerifyProperties;
    }

    /**
     * 기동 시 1회 경고(#1324 P2) — serviceKey 가 비어 있으면 모든 진위확인이 {@code SKIPPED}(fail-open)로
     * 떨어진다. #1324 이후로는 SKIPPED 도 그대로 자동승인되므로, 키 미설정 = <b>전 가입이 무검증으로
     * 승인되는 상태</b>다. 그런데 개별 호출 로그는 INFO 라 운영 로그에 묻히고, 기동 시점에는 아무 신호도
     * 없어서 "키가 빠진 채로 배포됐다"를 알아챌 방법이 없었다. 여기서 한 번 크게 남긴다.
     *
     * <p>⚠️ 키 값 자체는 절대 로그에 남기지 않는다 — 설정 여부(boolean)만 표현한다.
     */
    @PostConstruct
    void warnWhenServiceKeyMissing() {
        if (!StringUtils.hasText(bizVerifyProperties.getServiceKey())) {
            log.warn("[진위확인 미설정] biz-verify.service-key 가 비어 있다(configured=false) — 국세청 "
                    + "진위확인이 전건 SKIPPED(fail-open)로 처리되고, 기업 가입은 무검증으로 자동승인된다"
                    + "(#1324). 운영 환경이라면 키를 주입할 것.");
        }
    }

    /**
     * 사업자등록번호+대표자명+개업일자를 국세청 등록정보와 대조한다 — <b>회원가입 제출 경로 전용</b>
     * ({@code CompanySignupService}). permitAll+rate-limit 없는 무인증 엔드포인트라 재시도하지 않는다
     * (클래스 상단 Javadoc "실시간 경로 전용" 단락 참고). 실시간 진위확인 흐름에서 계속사업자 확정 후
     * 대조할 때는 이 메서드 대신 재시도가 적용된 {@link #validateForRealtime}을 쓴다.
     *
     * @param normalizedBrn      하이픈 제거된 사업자등록번호(숫자 10자리)
     * @param representativeName 대표자명
     * @param businessStartDate  개업일자
     * @return 진위확인 결과. 외부 실패·미설정은 {@link NtsVerificationOutcome#SKIPPED}(가입 진행).
     */
    public NtsVerificationOutcome validate(String normalizedBrn, String representativeName,
                                           LocalDate businessStartDate) {
        return validate(normalizedBrn, representativeName, businessStartDate, submitRestClient, false);
    }

    /**
     * {@link #verifyRealtime}이 계속사업자(01) 확정 후 호출하는 내부용 대조 — 제출 경로와 달리 재시도가
     * 적용된 {@link #realtimeRestClient}(read-timeout 8s)를 사용한다.
     */
    private NtsVerificationOutcome validateForRealtime(String normalizedBrn, String representativeName,
                                                        LocalDate businessStartDate) {
        return validate(normalizedBrn, representativeName, businessStartDate, realtimeRestClient, true);
    }

    private NtsVerificationOutcome validate(String normalizedBrn, String representativeName,
                                             LocalDate businessStartDate, RestClient restClient,
                                             boolean retryEnabled) {
        if (!StringUtils.hasText(bizVerifyProperties.getServiceKey())) {
            log.info("사업자 진위확인 스킵: serviceKey 미설정 — fail-open 가입 진행(PENDING)");
            return NtsVerificationOutcome.SKIPPED;
        }

        NtsValidateRequest body = new NtsValidateRequest(List.of(new NtsValidateRequest.Business(
                normalizedBrn,
                businessStartDate.format(DateTimeFormatter.BASIC_ISO_DATE), // YYYYMMDD
                representativeName)));

        try {
            NtsValidateResponse response = executeWithRetry(() -> restClient.post()
                    // serviceKey 는 반드시 percent-encoding 되어야 한다(data.go.kr "Decoding" 키는 +,/,= 를
                    // 포함할 수 있고, 미인코딩 시 서버가 + 를 공백으로 해석 → 인증실패 → 조용한 no-op). 리터럴
                    // queryParam 값은 DefaultUriBuilderFactory(TEMPLATE_AND_VALUES)가 인코딩하지 않으므로,
                    // URI 템플릿 변수({serviceKey})로 넘겨 변수값 인코딩을 강제한다.
                    .uri(uriBuilder -> uriBuilder.path(VALIDATE_PATH)
                            .queryParam("serviceKey", "{serviceKey}")
                            .build(bizVerifyProperties.getServiceKey()))
                    .body(body)
                    .retrieve()
                    .body(NtsValidateResponse.class), retryEnabled);
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
            statusResponse = executeWithRetry(() -> realtimeRestClient.post()
                    // serviceKey 인코딩 규칙은 validate()와 동일(클래스 상단 주석 참고).
                    .uri(uriBuilder -> uriBuilder.path(STATUS_PATH)
                            .queryParam("serviceKey", "{serviceKey}")
                            .build(bizVerifyProperties.getServiceKey()))
                    .body(new NtsStatusRequest(List.of(normalizedBrn)))
                    .retrieve()
                    .body(NtsStatusResponse.class), true);
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

        // 상태조회가 계속사업자(01)를 확인했을 때만 validateForRealtime() 으로 대표자명·개업일자 일치
        // 여부를 확정한다(제출 경로 전용 validate()가 아니라 재시도 적용된 실시간 전용 오버로드).
        return validateForRealtime(normalizedBrn, representativeName, businessStartDate);
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
     * 일시적 실패(연결 실패·타임아웃·5xx)에만 짧게 재시도하는 공통 헬퍼(#880) — {@link #validate}(제출
     * 경로 오버로드)와 {@link #validateForRealtime}, {@link #verifyRealtime}의 status 호출에서
     * {@code retryEnabled} 로 재시도 적용 여부만 다르게 재사용한다(SRP, 복붙 금지).
     *
     * <p>재시도 대상: {@link ResourceAccessException}(연결 실패·타임아웃), 5xx
     * {@link RestClientResponseException}. <b>재시도하지 않음</b>: 4xx {@link RestClientResponseException}
     * (키 거부·요청 오류 — 재시도해도 동일), 그 외 {@link RestClientException}(응답 파싱 실패 등, 재시도해도
     * 동일 실패). 재시도 소진 시 마지막 예외를 그대로 던져 호출부의 기존 fail-open catch 블록이 처리한다
     * (정책·로깅 변경 없음).
     *
     * @param httpCall     {@code RestClient} 호출+역직렬화(예: {@code .retrieve().body(Class)})
     * @param retryEnabled {@code false}면 1회만 시도하고 실패 즉시 던진다(제출 경로 전용, PR #889 P1).
     *                     {@code true}면 {@code biz-verify.retry-max-attempts} 만큼 재시도한다(실시간 경로).
     */
    private <T> T executeWithRetry(Supplier<T> httpCall, boolean retryEnabled) {
        // 최소 1회는 반드시 시도한다 — retry-max-attempts 를 음수로 잘못 설정하면 루프가 한 번도 돌지
        // 않아 아래 IllegalStateException 이 호출부의 fail-open catch(ResourceAccess/RestClient 계열)를
        // 우회해 500 으로 새어 나간다. 설정 실수가 공개 가입 API 를 깨뜨리지 않도록 하한을 둔다.
        int maxAttempts = retryEnabled ? Math.max(1, bizVerifyProperties.getRetryMaxAttempts() + 1) : 1;
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
