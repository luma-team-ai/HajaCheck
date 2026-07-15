package com.hajacheck.core.ai.service;

import com.hajacheck.core.ai.config.AiServerProperties;
import com.hajacheck.core.ai.dto.DefectExplainAiEnvelope;
import com.hajacheck.core.ai.dto.DefectExplainRequest;
import com.hajacheck.core.ai.dto.DefectExplainResponse;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.net.SocketTimeoutException;
import lombok.RequiredArgsConstructor;
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
            if (e.getCause() instanceof SocketTimeoutException) {
                throw new BusinessException(ErrorCode.AI_SERVER_TIMEOUT);
            }
            throw new BusinessException(ErrorCode.AI_SERVER_UNREACHABLE);
        } catch (RestClientException e) {
            // 4xx/5xx 응답(기본 에러 핸들러가 던짐) 또는 envelope 역직렬화 실패 — 모두 형식 불량으로 취급.
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
    }

    private void attachInternalKeyIfPresent(HttpHeaders headers) {
        if (StringUtils.hasText(aiServerProperties.getInternalKey())) {
            headers.set(INTERNAL_KEY_HEADER, aiServerProperties.getInternalKey());
        }
    }
}
