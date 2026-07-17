package com.hajacheck.core.ai.service;

import com.hajacheck.core.ai.config.AiServerProperties;
import com.hajacheck.core.ai.dto.BriefingAiEnvelope;
import com.hajacheck.core.ai.dto.BriefingResponse;
import com.hajacheck.core.ai.dto.BriefingStatsRequest;
import com.hajacheck.core.ai.dto.DefectExplainAiEnvelope;
import com.hajacheck.core.ai.dto.DefectExplainRequest;
import com.hajacheck.core.ai.dto.DefectExplainResponse;
import com.hajacheck.core.ai.dto.ReportAiEnvelope;
import com.hajacheck.core.ai.dto.ReportRequest;
import com.hajacheck.core.ai.dto.ReportResponse;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 인증된 프록시로 FastAPI AI 서버를 호출한다(#228). 이 서비스는 DB 접근이 없어 @Transactional 을 붙이지 않는다
 * (handoff 미러 패턴 §서비스 참고).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiProxyService {

    private static final String DEFECT_EXPLAIN_PATH = "/ai/defect-explain";
    private static final String REPORT_PATH = "/ai/report";
    private static final String BRIEFING_PATH = "/ai/briefing";
    private static final String INTERNAL_KEY_HEADER = "X-Internal-Key";

    private final RestClient aiServerRestClient;
    private final AiServerProperties aiServerProperties;
    private final BriefingStatsService briefingStatsService;

    public ApiResponse<DefectExplainResponse> explainDefect(DefectExplainRequest request) {
        DefectExplainAiEnvelope envelope = callAiServer(request);
        if (envelope == null) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }

        if (!envelope.success()) {
            DefectExplainAiEnvelope.ErrorBody error = envelope.error();
            if (error == null) {
                throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
            }
            return ApiResponse.fail(error.code(), error.message());
        }

        if (envelope.data() == null) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        return ApiResponse.ok(envelope.data());
    }

    private DefectExplainAiEnvelope callAiServer(DefectExplainRequest request) {
        try {
            return aiServerRestClient.post()
                    .uri(DEFECT_EXPLAIN_PATH)
                    .headers(this::attachInternalKeyIfPresent)
                    .body(request)
                    .retrieve()
                    .body(DefectExplainAiEnvelope.class);
        } catch (ResourceAccessException e) {
            throw mapConnectionFailure(e);
        } catch (RestClientResponseException e) {
            throw mapResponseStatusFailure(e);
        } catch (RestClientException e) {
            // envelope 역직렬화 실패 등 상태코드가 없는 나머지 실패는 형식 불량으로 취급.
            log.warn("AI 서버 응답 처리 실패: {}", ErrorCode.AI_INVALID_RESPONSE, e);
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
    }

    public ApiResponse<ReportResponse> generateReport(ReportRequest request) {
        ReportAiEnvelope envelope = callAiServer(request);
        if (envelope == null) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }

        if (!envelope.success()) {
            ReportAiEnvelope.ErrorBody error = envelope.error();
            if (error == null) {
                throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
            }
            return ApiResponse.fail(error.code(), error.message());
        }

        if (envelope.data() == null) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        return ApiResponse.ok(envelope.data());
    }

    private ReportAiEnvelope callAiServer(ReportRequest request) {
        try {
            return aiServerRestClient.post()
                    .uri(REPORT_PATH)
                    .headers(this::attachInternalKeyIfPresent)
                    .body(request)
                    .retrieve()
                    .body(ReportAiEnvelope.class);
        } catch (ResourceAccessException e) {
            throw mapConnectionFailure(e);
        } catch (RestClientResponseException e) {
            throw mapResponseStatusFailure(e);
        } catch (RestClientException e) {
            log.warn("AI 서버 응답 처리 실패: {}", ErrorCode.AI_INVALID_RESPONSE, e);
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
    }

    /**
     * 로그인 사용자(ownerId) 소유 범위 현황을 {@link BriefingStatsService} 로 집계해 FastAPI
     * {@code /ai/briefing} 을 호출한다(#248 / HAJA-197). ownerId 는 컨트롤러가
     * {@code @AuthenticationPrincipal} 에서만 취득해 전달한다(IDOR 방지).
     */
    public ApiResponse<BriefingResponse> briefing(Long ownerId) {
        BriefingStatsRequest stats = briefingStatsService.buildStats(ownerId);
        BriefingAiEnvelope envelope = callAiServer(stats);
        if (envelope == null) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }

        if (!envelope.success()) {
            BriefingAiEnvelope.ErrorBody error = envelope.error();
            if (error == null) {
                throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
            }
            return ApiResponse.fail(error.code(), error.message());
        }

        if (envelope.data() == null) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        return ApiResponse.ok(envelope.data());
    }

    private BriefingAiEnvelope callAiServer(BriefingStatsRequest request) {
        try {
            return aiServerRestClient.post()
                    .uri(BRIEFING_PATH)
                    .headers(this::attachInternalKeyIfPresent)
                    .body(request)
                    .retrieve()
                    .body(BriefingAiEnvelope.class);
        } catch (ResourceAccessException e) {
            throw mapConnectionFailure(e);
        } catch (RestClientResponseException e) {
            throw mapResponseStatusFailure(e);
        } catch (RestClientException e) {
            log.warn("AI 서버 응답 처리 실패: {}", ErrorCode.AI_INVALID_RESPONSE, e);
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
    }

    /**
     * 이 레포는 httpclient5/okhttp 미의존이라 RestClient 가 JdkClientHttpRequestFactory
     * (java.net.http.HttpClient) 를 사용한다. connect 타임아웃은 {@link HttpConnectTimeoutException}
     * (커넥션 자체 실패로 보아 UNREACHABLE), read/응답 타임아웃은 {@link HttpTimeoutException}
     * (TIMEOUT) — HttpConnectTimeoutException 이 HttpTimeoutException 의 하위형이므로 먼저 검사한다.
     * {@link SocketTimeoutException} 은 향후 httpclient5/okhttp 로 클라이언트가 바뀔 경우를 대비해 함께 검사.
     */
    private BusinessException mapConnectionFailure(ResourceAccessException e) {
        Throwable cause = e.getCause();
        if (cause instanceof HttpConnectTimeoutException) {
            log.warn("AI 서버 연결 타임아웃: {}", ErrorCode.AI_SERVER_UNREACHABLE, e);
            return new BusinessException(ErrorCode.AI_SERVER_UNREACHABLE);
        }
        if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
            log.warn("AI 서버 응답 지연: {}", ErrorCode.AI_SERVER_TIMEOUT, e);
            return new BusinessException(ErrorCode.AI_SERVER_TIMEOUT);
        }
        log.warn("AI 서버 연결 실패: {}", ErrorCode.AI_SERVER_UNREACHABLE, e);
        return new BusinessException(ErrorCode.AI_SERVER_UNREACHABLE);
    }

    /**
     * AI 서버가 HTTP 상태코드와 함께 응답한 실패({@link RestClientResponseException})를 4xx/5xx 로
     * 구분한다(#334 P3). 4xx 는 우리 쪽에서 만든 요청이 AI 서버 계약에 안 맞아 거부된 것으로 보아
     * AI_REQUEST_REJECTED(400), 5xx 는 업스트림(AI 서버) 자체 장애로 보아 AI_SERVER_ERROR(502) 로 매핑한다.
     * 상태코드가 4xx/5xx 어느 쪽도 아닌 예외 케이스는 기존과 동일하게 AI_INVALID_RESPONSE 로 폴백한다.
     */
    private BusinessException mapResponseStatusFailure(RestClientResponseException e) {
        HttpStatusCode status = e.getStatusCode();
        if (status.is4xxClientError()) {
            log.warn("AI 서버 요청 거부(4xx): {}", ErrorCode.AI_REQUEST_REJECTED, e);
            return new BusinessException(ErrorCode.AI_REQUEST_REJECTED);
        }
        if (status.is5xxServerError()) {
            log.warn("AI 서버 오류(5xx): {}", ErrorCode.AI_SERVER_ERROR, e);
            return new BusinessException(ErrorCode.AI_SERVER_ERROR);
        }
        log.warn("AI 서버 응답 처리 실패: {}", ErrorCode.AI_INVALID_RESPONSE, e);
        return new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
    }

    private void attachInternalKeyIfPresent(HttpHeaders headers) {
        if (StringUtils.hasText(aiServerProperties.getInternalKey())) {
            headers.set(INTERNAL_KEY_HEADER, aiServerProperties.getInternalKey());
        }
    }
}
