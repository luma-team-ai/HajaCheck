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
 *
 * <p>⚠️ 클래스 레벨 {@code @Transactional} 금지 — requireAiAddon()의 단순 조회는 Spring Data
 * 리포지토리 메서드 자체가 이미 개별 트랜잭션을 갖는다(RepositoryFactorySupport 기본 동작).
 * 여기에 감싸는 트랜잭션을 씌우면 뒤이은 callAiServer()의 외부 HTTP 호출(최대
 * ai.server.read-timeout-ms=150s)까지 같은 트랜잭션 경계에 묶여 그 시간만큼 HikariCP 커넥션을
 * 점유하게 된다 — 동시 요청 시 커넥션 풀 고갈로 이어질 수 있다(AiProxyService가 DB 접근이 없어도
 * @Transactional을 붙이지 않는 것과 같은 이유, 리뷰 P1).
 */
@Slf4j
@Service
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

        if (userPlan.isEmpty()) {
            throw new BusinessException(ErrorCode.AI_ADDON_REQUIRED);
        }
        // 활성 플랜은 있는데 참조 Plan 행이 없으면(FK 정합성 깨짐) "플랜 없음"이 아니라 서버측
        // 데이터 오류다 — MembershipService.findPlan과 동일 기준으로 구분(리뷰 P3).
        Plan plan = planRepository.findById(userPlan.get().getPlanId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));
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
