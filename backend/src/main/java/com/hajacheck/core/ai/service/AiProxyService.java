package com.hajacheck.core.ai.service;

import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSenderType;
import com.hajacheck.counsel.entity.ChatSessionType;
import com.hajacheck.counsel.repository.ChatMessageRepository;
import com.hajacheck.counsel.service.ChatSessionService;
import com.hajacheck.core.ai.config.AiServerProperties;
import com.hajacheck.core.ai.dto.BriefingAiEnvelope;
import com.hajacheck.core.ai.dto.BriefingResponse;
import com.hajacheck.core.ai.dto.BriefingStatsRequest;
import com.hajacheck.core.ai.dto.BusinessLicenseOcrAiEnvelope;
import com.hajacheck.core.ai.dto.BusinessLicenseOcrAiRequest;
import com.hajacheck.core.ai.dto.BusinessLicenseOcrResponse;
import com.hajacheck.core.ai.dto.DefectDetectionAiEnvelope;
import com.hajacheck.core.ai.dto.DefectDetectionAiRequest;
import com.hajacheck.core.ai.dto.DetectedDefectItem;
import com.hajacheck.core.ai.dto.DefectExplainAiEnvelope;
import com.hajacheck.core.ai.dto.DefectExplainRequest;
import com.hajacheck.core.ai.dto.DefectExplainResponse;
import com.hajacheck.core.ai.dto.HistoryTurnDto;
import com.hajacheck.core.ai.dto.RagChatAiEnvelope;
import com.hajacheck.core.ai.dto.RagChatAiRequest;
import com.hajacheck.core.ai.dto.RagChatRequest;
import com.hajacheck.core.ai.dto.RagChatResponse;
import com.hajacheck.core.ai.dto.RagDeleteAiEnvelope;
import com.hajacheck.core.ai.dto.RagEmbedAiEnvelope;
import com.hajacheck.core.ai.dto.RagEmbedRequest;
import com.hajacheck.core.ai.dto.RagEmbedResponse;
import com.hajacheck.core.ai.dto.RagEmbeddingStatusAiEnvelope;
import com.hajacheck.core.ai.dto.RagEmbeddingStatusResponse;
import com.hajacheck.core.ai.dto.ReportAiEnvelope;
import com.hajacheck.core.ai.dto.ReportRequest;
import com.hajacheck.core.ai.dto.ReportResponse;
import com.hajacheck.core.ai.support.AiProxyRateLimiter;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
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
    private static final String BUSINESS_LICENSE_OCR_PATH = "/ai/business-license-ocr";
    private static final String RAG_CHAT_PATH = "/ai/rag-chat";
    private static final String RAG_EMBED_PATH = "/ai/rag-documents/embed";
    private static final String RAG_EMBEDDING_STATUS_PATH = "/ai/rag-documents/{docId}/embedding-status";
    private static final String RAG_DELETE_PATH = "/ai/rag-documents/{docId}";
    private static final String DETECT_DEFECTS_PATH = "/ai/detect-defects";
    private static final String INTERNAL_KEY_HEADER = "X-Internal-Key";
    // 프롬프트에 실어 보낼 최근 이력 턴 수(질문+답변 페어) — 설계 §3, HF_MAX_TOKENS 여유 고려한 초안값.
    private static final int RECENT_HISTORY_TURN_LIMIT = 3;

    private final RestClient aiServerRestClient;
    private final AiServerProperties aiServerProperties;
    private final BriefingStatsService briefingStatsService;
    private final AiProxyRateLimiter aiProxyRateLimiter;
    private final RestClient aiServerEmbeddingStatusRestClient;
    // 세션 소유 검증 전용(#1467/HAJA-647). 인가 로직을 여기서 재구현하지 않고 counsel 도메인 서비스에
    // 위임해 /api/chat-sessions 와 동일한 규칙·동일한 403을 쓴다.
    private final ChatSessionService chatSessionService;
    // 세션 이력 조회 전용(#1493/HAJA-657). ChatSessionService.findMessages()가 쓰는 것과 동일한
    // 리포지토리를 재사용한다(조회 로직 중복 금지). 조회는 단건 repository 호출이라 별도
    // @Transactional 불필요 — 쓰기(저장)만 RagConversationPersistenceService로 분리한 이유는
    // 그 클래스 javadoc 참고.
    private final ChatMessageRepository chatMessageRepository;
    // 대화 저장(질문/답변/citation)은 별도 빈에 위임 — ragChat()이 FastAPI 호출까지 트랜잭션으로
    // 묶어 DB 커넥션을 오래 점유하는 것을 막기 위함(PR #1510 P1 픽스, RagConversationPersistenceService
    // javadoc 참고).
    private final RagConversationPersistenceService ragConversationPersistenceService;

    /**
     * @param userId 요청자 식별자 — 컨트롤러가 {@code @AuthenticationPrincipal}에서만 취득해 전달한다
     *               (요청 바디에서 받지 않는다). 사용자 축 rate-limit 키로만 사용한다.
     */
    public ApiResponse<DefectExplainResponse> explainDefect(Long userId, DefectExplainRequest request) {
        // 사용자 축 → 전역 축 순서로 rate-limit(초과 시 429·FastAPI 호출 없이 중단, AiProxyRateLimiter 참고).
        aiProxyRateLimiter.checkUser(userId);
        aiProxyRateLimiter.checkGlobal();
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

    /**
     * @param userId 요청자 식별자 — 호출부(컨트롤러/ReportService)가 {@code @AuthenticationPrincipal}
     *               에서 확보한 값을 전달한다(요청 바디에서 받지 않는다). 사용자 축 rate-limit 키로만 사용.
     */
    public ApiResponse<ReportResponse> generateReport(Long userId, ReportRequest request) {
        // 사용자 축 → 전역 축 순서로 rate-limit(초과 시 429·FastAPI 호출 없이 중단, AiProxyRateLimiter 참고).
        aiProxyRateLimiter.checkUser(userId);
        aiProxyRateLimiter.checkGlobal();
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
     * 로그인 사용자의 회사(companyId) 소유 범위 현황을 {@link BriefingStatsService} 로 집계해 FastAPI
     * {@code /ai/briefing} 을 호출한다(#248 / HAJA-197). userId와 companyId는 컨트롤러가
     * {@code @AuthenticationPrincipal} 에서만 취득해 각각 rate-limit과 회사 스코프에 사용한다.
     */
    public ApiResponse<BriefingResponse> briefing(Long userId, Long companyId) {
        BriefingStatsRequest stats = briefingStatsService.buildStats(userId, companyId);
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
     * 고객지원 RAG 챗봇 프록시(HAJA-32, #467) — 로그인 사용자(관리자 한정 아님)가 호출한다.
     * {@code userId} 는 컨트롤러가 {@code @AuthenticationPrincipal} 에서만 취득해 전달한다(요청 바디 금지).
     * 사용자 축 rate-limit 키로만 사용한다.
     *
     * <p>프론트 요청({@code query})을 FastAPI 계약({@code question})으로 변환해 전달한다 — 필드명이 달라
     * {@link RagChatRequest} 를 그대로 재사용할 수 없다({@link RagChatAiRequest} javadoc 참고).
     * <p>{@code sessionId}(#1467/HAJA-647)는 선택 값이다. 값이 있으면 <b>FastAPI를 호출하기 전에</b>
     * 세션 소유자({@code userId} 일치)와 {@code sessionType=RAG} 를 검증하고, 불일치·미존재면
     * {@link ErrorCode#CHAT_SESSION_FORBIDDEN}(403)으로 중단한다 — 타인 세션 식별자를 실어 보내는
     * cross-user 접근(IDOR) 차단이 목적이라, 검증은 반드시 외부 호출·과금보다 앞서야 한다.
     * 세션이 있으면 최근 3턴 이력을 FastAPI에 함께 전달하고, 응답 성공 시 질의/답변/출처를 저장한다
     * (#1493/HAJA-657). 세션이 없으면(단발 질의) 이력 전달·저장 둘 다 생략 — 기존 무상태 호출과 회귀 없음.
     *
     * <p>이 메서드 자체에는 {@code @Transactional} 을 붙이지 않는다(PR #1510 P1 픽스) — FastAPI 호출
     * ({@code callAiServer}, 캐시 미스 시 수 초 소요 가능)까지 트랜잭션으로 묶으면 그 동안 DB 커넥션을
     * 계속 점유해 동시 요청이 몰릴 때 커넥션 풀 고갈로 이어질 수 있다. 저장(DB 쓰기)만
     * {@link RagConversationPersistenceService} 로 분리해 그 빈에만 {@code @Transactional} 을 붙인다
     * (클래스 상단 주석 "이 서비스는 DB 접근이 없어 @Transactional 미부착" 그대로 유지 — ragChat()도
     * 자체적으로는 DB에 직접 쓰지 않는다, 조회는 read-only 단건 호출이라 별도 트랜잭션 불필요).
     */
    public ApiResponse<RagChatResponse> ragChat(Long userId, RagChatRequest request) {
        // 인가 먼저: rate-limit 소모나 AI 서버 호출보다 앞서 타인 세션 접근을 차단한다.
        if (request.sessionId() != null) {
            chatSessionService.getOwnedSession(userId, request.sessionId(), ChatSessionType.RAG);
        }
        // 사용자 축 → 전역 축 순서로 rate-limit(초과 시 429·FastAPI 호출 없이 중단, AiProxyRateLimiter 참고).
        aiProxyRateLimiter.checkUser(userId);
        aiProxyRateLimiter.checkGlobal();

        List<HistoryTurnDto> history = request.sessionId() != null
                ? buildRecentHistory(request.sessionId())
                : List.of();

        RagChatAiEnvelope envelope = callAiServer(new RagChatAiRequest(request.query(), history));
        if (envelope == null) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }

        if (!envelope.success()) {
            RagChatAiEnvelope.ErrorBody error = envelope.error();
            if (error == null) {
                throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
            }
            // RAG_NO_RESULT 등 AIErrorCode 를 가공 없이 그대로 전달 — 프론트는 error.code 로 분기한다
            // (useRagChat.ts: code==='RAG_NO_RESULT' 는 에러가 아니라 "근거 없음" 안내로 표시).
            return ApiResponse.fail(error.code(), error.message());
        }

        if (envelope.data() == null) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }

        if (request.sessionId() != null) {
            ragConversationPersistenceService.saveConversation(
                    request.sessionId(), request.query(), envelope.data());
        }
        return ApiResponse.ok(envelope.data());
    }

    /**
     * 세션의 (질문, 답변) 페어 중 최근 {@link #RECENT_HISTORY_TURN_LIMIT}개만 시간순으로 추린다.
     * citation은 제외하고 질문/답변 텍스트만 담는다(설계 §3).
     */
    private List<HistoryTurnDto> buildRecentHistory(Long sessionId) {
        List<ChatMessage> messages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<HistoryTurnDto> turns = new ArrayList<>();
        ChatMessage pendingQuestion = null;
        for (ChatMessage message : messages) {
            if (message.getSender() == ChatSenderType.USER) {
                pendingQuestion = message;
            } else if (message.getSender() == ChatSenderType.BOT && pendingQuestion != null) {
                turns.add(new HistoryTurnDto(pendingQuestion.getContent(), message.getContent()));
                pendingQuestion = null;
            }
        }
        int fromIndex = Math.max(0, turns.size() - RECENT_HISTORY_TURN_LIMIT);
        return turns.subList(fromIndex, turns.size());
    }

    private RagChatAiEnvelope callAiServer(RagChatAiRequest request) {
        try {
            return aiServerRestClient.post()
                    .uri(RAG_CHAT_PATH)
                    .headers(this::attachInternalKeyIfPresent)
                    .body(request)
                    .retrieve()
                    .body(RagChatAiEnvelope.class);
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
     * 사업자등록증 OCR 공개 프록시(#557 / HAJA-324) — 기업 가입 전(비로그인) 화면에서 호출된다.
     * 인가는 없지만 {@link #attachInternalKeyIfPresent}로 내부키를 부착해 AI 서버 강제 경유 원칙(#228)은
     * 그대로 유지한다. 호출부(BusinessLicenseOcrService)가 파일 검증·rate-limit·base64 인코딩을 마친
     * 문자열만 여기로 넘긴다 — 이 메서드는 순수 프록시 역할만 한다(다른 ocr/explain/report와 동일 스켈레톤).
     */
    public ApiResponse<BusinessLicenseOcrResponse> ocrBusinessLicense(String imageBase64) {
        BusinessLicenseOcrAiEnvelope envelope = callAiServer(new BusinessLicenseOcrAiRequest(imageBase64));
        if (envelope == null) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }

        if (!envelope.success()) {
            BusinessLicenseOcrAiEnvelope.ErrorBody error = envelope.error();
            if (error == null) {
                throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
            }
            return ApiResponse.fail(error.code(), error.message());
        }

        if (envelope.data() == null) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        return ApiResponse.ok(BusinessLicenseOcrResponse.from(envelope.data()));
    }

    private BusinessLicenseOcrAiEnvelope callAiServer(BusinessLicenseOcrAiRequest request) {
        try {
            return aiServerRestClient.post()
                    .uri(BUSINESS_LICENSE_OCR_PATH)
                    .headers(this::attachInternalKeyIfPresent)
                    .body(request)
                    .retrieve()
                    .body(BusinessLicenseOcrAiEnvelope.class);
        } catch (ResourceAccessException e) {
            throw mapConnectionFailure(e);
        } catch (RestClientResponseException e) {
            throw mapResponseStatusFailure(e);
        } catch (RestClientException e) {
            // OCR 원문/개인정보는 예외 메시지에 포함되지 않는다 — AI 서버가 그런 값을 던지지 않고,
            // 이 catch는 envelope 역직렬화 실패 등 형식 불량만 다룬다(다른 endpoint와 동일 패턴).
            log.warn("AI 서버 응답 처리 실패: {}", ErrorCode.AI_INVALID_RESPONSE, e);
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
    }

    /**
     * RAG 문서 임베딩 프록시(#22/HAJA-35) — RagDocumentService가 PDF에서 추출한 텍스트를 실어
     * FastAPI {@code /ai/rag-documents/embed}를 호출한다. 다른 프록시 메서드와 동일한
     * try/catch(ResourceAccessException/RestClientResponseException/RestClientException) 패턴.
     */
    public ApiResponse<RagEmbedResponse> embedRagDocument(RagEmbedRequest request) {
        RagEmbedAiEnvelope envelope = callAiServer(request);
        if (envelope == null) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }

        if (!envelope.success()) {
            RagEmbedAiEnvelope.ErrorBody error = envelope.error();
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

    private RagEmbedAiEnvelope callAiServer(RagEmbedRequest request) {
        try {
            return aiServerRestClient.post()
                    .uri(RAG_EMBED_PATH)
                    .headers(this::attachInternalKeyIfPresent)
                    .body(request)
                    .retrieve()
                    .body(RagEmbedAiEnvelope.class);
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
     * RAG 문서 백그라운드 임베딩 완료 여부 확인 프록시(#1328) — RagEmbeddingCompletionPoller가 짧은
     * 간격으로 재시도 폴링한다. read-only 조회라 rate-limit을 걸지 않는다(embedRagDocument와 동일하게
     * 관리자 콘솔 내부 호출 경로 — 사용자 축 rate-limit 대상이 아님).
     */
    public ApiResponse<RagEmbeddingStatusResponse> checkEmbeddingStatus(String docId, String collection) {
        RagEmbeddingStatusAiEnvelope envelope = callEmbeddingStatus(docId, collection);
        if (envelope == null) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }

        if (!envelope.success()) {
            RagEmbeddingStatusAiEnvelope.ErrorBody error = envelope.error();
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

    private RagEmbeddingStatusAiEnvelope callEmbeddingStatus(String docId, String collection) {
        try {
            return aiServerEmbeddingStatusRestClient.get()
                    .uri(RAG_EMBEDDING_STATUS_PATH + "?target_collection={collection}", docId, collection)
                    .headers(this::attachInternalKeyIfPresent)
                    .retrieve()
                    .body(RagEmbeddingStatusAiEnvelope.class);
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
     * RAG 문서 Chroma 청크 삭제 프록시(#1394) — RagDocumentService가 DB 로우·파일을 지우기 전에 먼저
     * 호출한다. FastAPI {@code delete_document()}는 chromadb {@code where} 삭제라 매치 0건이어도
     * 에러 없이 성공하므로(idempotent), 이 호출이 실패해도 DB/파일은 아직 건드리지 않은 상태이고
     * 재시도하면 그대로 수렴한다(RagDocumentService.delete() javadoc 참고).
     */
    public void deleteRagDocumentChunks(String docId, String targetCollection) {
        RagDeleteAiEnvelope envelope = callDeleteAiServer(docId, targetCollection);
        if (envelope == null) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        if (!envelope.success()) {
            RagDeleteAiEnvelope.ErrorBody error = envelope.error();
            String code = error != null ? error.code() : "UNKNOWN";
            String message = error != null ? error.message() : null;
            log.warn("AI 서버 RAG 문서 삭제 실패 code={} message={}", code, message);
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
    }

    private RagDeleteAiEnvelope callDeleteAiServer(String docId, String targetCollection) {
        try {
            return aiServerRestClient.delete()
                    .uri(uriBuilder -> uriBuilder.path(RAG_DELETE_PATH)
                            .queryParam("target_collection", targetCollection)
                            .build(docId))
                    .headers(this::attachInternalKeyIfPresent)
                    .retrieve()
                    .body(RagDeleteAiEnvelope.class);
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
     * 하자 탐지(dev-05-04) — InspectionAnalysisService가 회차의 이미지를 순회하며 1장씩 호출한다.
     * 다른 /ai/* 프록시와 달리 컨트롤러가 아니라 배치 잡(비동기 서비스)에서 호출되므로 ApiResponse가
     * 아니라 언랩된 결과를 바로 던지거나 반환한다 — 호출부가 이미지 1건 실패를 잡 전체 실패로 만들지
     * 않고 개별 격리 처리할 수 있도록 BusinessException을 그대로 전파한다(catch는 호출부 책임).
     */
    public List<DetectedDefectItem> detectDefects(String imageBase64) {
        DefectDetectionAiEnvelope envelope = callAiServer(new DefectDetectionAiRequest(imageBase64));
        if (envelope == null) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        if (!envelope.success()) {
            log.warn("AI 서버 하자 탐지 실패 응답: {}",
                    envelope.error() != null ? envelope.error().code() : "unknown");
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        if (envelope.data() == null || envelope.data().detections() == null) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        return envelope.data().detections();
    }

    private DefectDetectionAiEnvelope callAiServer(DefectDetectionAiRequest request) {
        try {
            return aiServerRestClient.post()
                    .uri(DETECT_DEFECTS_PATH)
                    .headers(this::attachInternalKeyIfPresent)
                    .body(request)
                    .retrieve()
                    .body(DefectDetectionAiEnvelope.class);
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
        // Spring DefaultResponseErrorHandler 규약상 RestClientResponseException 은 4xx/5xx 에서만 던져지므로
        // 이 분기는 이론상 도달 불가 — 향후 핸들러 구현 변경에 대비한 방어적 폴백이다(code-reviewer 지적).
        log.warn("AI 서버 응답 처리 실패: {}", ErrorCode.AI_INVALID_RESPONSE, e);
        return new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
    }

    private void attachInternalKeyIfPresent(HttpHeaders headers) {
        if (StringUtils.hasText(aiServerProperties.getInternalKey())) {
            headers.set(INTERNAL_KEY_HEADER, aiServerProperties.getInternalKey());
        }
    }
}
