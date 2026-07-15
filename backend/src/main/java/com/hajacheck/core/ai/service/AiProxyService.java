package com.hajacheck.core.ai.service;

import com.hajacheck.core.ai.config.AiServerProperties;
import com.hajacheck.core.ai.dto.DefectExplainAiEnvelope;
import com.hajacheck.core.ai.dto.DefectExplainRequest;
import com.hajacheck.core.ai.dto.DefectExplainResponse;
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
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 인증된 프록시로 FastAPI AI 서버를 호출한다(#228). 이 서비스는 DB 접근이 없어 @Transactional 을 붙이지 않는다
 * (handoff 미러 패턴 §서비스 참고).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiProxyService {

    private static final String DEFECT_EXPLAIN_PATH = "/ai/defect-explain";
    private static final String INTERNAL_KEY_HEADER = "X-Internal-Key";

    private final RestClient aiServerRestClient;
    private final AiServerProperties aiServerProperties;

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
        } catch (RestClientException e) {
            // 4xx/5xx 응답(기본 에러 핸들러가 던짐) 또는 envelope 역직렬화 실패 — 모두 형식 불량으로 취급.
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

    private void attachInternalKeyIfPresent(HttpHeaders headers) {
        if (StringUtils.hasText(aiServerProperties.getInternalKey())) {
            headers.set(INTERNAL_KEY_HEADER, aiServerProperties.getInternalKey());
        }
    }
}
