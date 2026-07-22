package com.hajacheck.core.defect.service;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.core.ai.config.AiServerProperties;
import com.hajacheck.core.defect.dto.NlSearchAiEnvelope;
import com.hajacheck.core.defect.dto.NlSearchResult;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 하자 자연어 검색 공개 게이트웨이(HAJA-120/179~183) — 세션 인증(컨트롤러 @AuthenticationPrincipal)을
 * 전제로, 이 서비스는 (1) 질의 검증 (2) has_ai_addon 플랜 게이트를 통과한 요청만 내부 FastAPI
 * {@code POST /ai/nl-search}를 호출한다. 게이트 실패 시 FastAPI 호출 자체가 발생하지 않는다
 * (docs/design/ai/nl_search_filter_schema.md §4).
 *
 * <p>플랜 게이트는 X-Internal-Service-Token(AiProxyService의 X-Internal-Key와 별개, contract.md
 * InternalServiceToken)으로 FastAPI에 별도 신뢰 경계를 둔다.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class NlSearchService {

    private static final int QUERY_MAX_LENGTH = 500;
    private static final String NL_SEARCH_PATH = "/ai/nl-search";
    private static final String INTERNAL_SERVICE_TOKEN_HEADER = "X-Internal-Service-Token";

    private final RestClient aiServerRestClient;
    private final AiServerProperties aiServerProperties;
    private final UserRepository userRepository;
    private final UserPlanRepository userPlanRepository;
    private final PlanRepository planRepository;
    private final CompanyMembershipRepository companyMembershipRepository;

    public ApiResponse<NlSearchResult> search(Long userId, String rawQuery) {
        String query = validateQuery(rawQuery);
        requireAiAddon(userId);

        NlSearchAiEnvelope envelope = callAiServer(query);
        if (envelope == null) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        if (!envelope.success()) {
            NlSearchAiEnvelope.ErrorBody error = envelope.error();
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

    private String validateQuery(String rawQuery) {
        String trimmed = rawQuery == null ? "" : rawQuery.trim();
        if (trimmed.isEmpty() || trimmed.length() > QUERY_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return trimmed;
    }

    /**
     * has_ai_addon=true인 활성(ACTIVE) 플랜 게이트. 회사 소속(companyId != null)이면 users.company_id
     * 만으로 상속하지 않고 company_memberships의 유효한 승인 멤버십(APPROVED+미회수+미만료, 회사
     * 자체도 APPROVED+VERIFIED)까지 확인한 뒤에만 회사 플랜을 조회한다(§4). 실패 시 예외로 즉시 중단 —
     * 호출부(search)가 이 뒤로 진행하지 않으므로 FastAPI 호출 자체가 발생하지 않는다.
     */
    private void requireAiAddon(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        Long companyId = user.getCompanyId();

        Optional<UserPlan> userPlan;
        if (companyId != null) {
            if (!companyMembershipRepository.existsEffectiveApprovedMembership(companyId, userId, Instant.now())) {
                throw new BusinessException(ErrorCode.AI_ADDON_REQUIRED);
            }
            userPlan = userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(
                    companyId, UserPlanStatus.ACTIVE);
        } else {
            userPlan = userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
                    userId, UserPlanStatus.ACTIVE);
        }

        Plan plan = userPlan
                .flatMap(up -> planRepository.findById(up.getPlanId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.AI_ADDON_REQUIRED));
        if (!plan.isHasAiAddon()) {
            throw new BusinessException(ErrorCode.AI_ADDON_REQUIRED);
        }
    }

    private NlSearchAiEnvelope callAiServer(String query) {
        try {
            return aiServerRestClient.post()
                    .uri(NL_SEARCH_PATH)
                    .headers(this::attachInternalServiceTokenIfPresent)
                    .body(new QueryBody(query))
                    .retrieve()
                    .body(NlSearchAiEnvelope.class);
        } catch (ResourceAccessException e) {
            throw mapConnectionFailure(e);
        } catch (RestClientResponseException e) {
            throw mapResponseStatusFailure(e);
        } catch (RestClientException e) {
            log.warn("AI 서버 응답 처리 실패: {}", ErrorCode.AI_INVALID_RESPONSE, e);
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
    }

    private record QueryBody(String query) {
    }

    /** AiProxyService.mapConnectionFailure와 동일 분류 기준(JdkClientHttpRequestFactory 타임아웃 유형). */
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

    /** AiProxyService.mapResponseStatusFailure와 동일 4xx/5xx 분류 기준. */
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

    private void attachInternalServiceTokenIfPresent(HttpHeaders headers) {
        if (StringUtils.hasText(aiServerProperties.getInternalServiceToken())) {
            headers.set(INTERNAL_SERVICE_TOKEN_HEADER, aiServerProperties.getInternalServiceToken());
        }
    }
}
