package com.hajacheck.core.ai.service;

import com.hajacheck.core.ai.dto.RagChatResponse;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * RAG 챗봇 대화(질문/답변/출처) 저장 오케스트레이터(#1493/HAJA-657, PR #1510 P1 픽스, #1593).
 *
 * <p>{@link AiProxyService#ragChat} 이 저장까지 같은 메서드에서 {@code @Transactional} 로 묶으면,
 * FastAPI LLM 파이프라인 호출(캐시 미스 시 수 초 소요 가능)까지 트랜잭션 범위에 포함돼 그 동안 DB
 * 커넥션을 계속 점유한다 — 동시 RAG 요청이 몰리면 커넥션 풀 고갈로 다른 기능까지 영향받을 수 있다.
 * DB 쓰기만 별도 빈으로 분리해 트랜잭션 범위를 저장 구간으로 좁힌다(외부 호출은 트랜잭션 밖).
 * Spring self-invocation 문제(같은 클래스 내부 호출은 프록시 트랜잭션이 안 걸림) 때문에
 * {@link AiProxyService} 내부 private 메서드가 아니라 반드시 별도 빈이어야 한다.
 *
 * <p><b>이 클래스 자체에는 {@code @Transactional} 이 없다</b>(#1593) — 대화 메시지와 출처를 각각
 * 독립 물리 트랜잭션({@code REQUIRES_NEW})으로 커밋시키는 순차 오케스트레이터이기 때문이다.
 * 알려진 저장 실패 경로는 전부 출처 쪽이라, 하나로 묶으면 출처 한 건 때문에 질문·답변까지 롤백된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagConversationPersistenceService {

    private final RagChatMessageWriter ragChatMessageWriter;
    private final RagCitationWriter ragCitationWriter;

    /**
     * 사용자 질문 + 봇 답변을 저장하고, 이어서 답변의 출처를 봇 메시지에 인용으로 매핑해 저장한다.
     *
     * <p>두 단계는 <b>순차 실행</b>이다(중첩 아님) — 출처의 {@code message_id} FK 때문에 봇 메시지
     * 트랜잭션이 커밋된 뒤에야 출처를 쓸 수 있다({@link RagCitationWriter} javadoc 참고).
     *
     * <p>출처 저장 실패만 여기서 흡수한다(대화 이력은 이미 커밋됨). 메시지 저장 실패는 그대로 던져
     * 호출부({@link AiProxyService#ragChat})가 best-effort 로 흡수한다 — 저장 계층 어디에서도
     * 자기 {@code @Transactional} 안에서 예외를 잡지 않는다(잡으면 rollback-only 로 마킹된 트랜잭션이
     * 커밋 시점에 {@code UnexpectedRollbackException} 으로 다시 터진다).
     */
    public void saveConversation(Long sessionId, String query, RagChatResponse data) {
        Long botMessageId = ragChatMessageWriter.saveMessages(sessionId, query, data.answer());

        if (data.sources() == null || data.sources().isEmpty()) {
            return;
        }
        try {
            ragCitationWriter.saveCitations(botMessageId, data.sources());
        } catch (Exception e) {
            // 질문·답변은 이미 커밋됐다 — 사용자는 이력을 유지한 채 출처 칩만 잃는다.
            log.error("RAG 출처(citation) 저장 실패 — 질문·답변 이력은 보존됨. "
                            + "sessionId={}, botMessageId={}, docIds={}",
                    sessionId, botMessageId, docIds(data), e);
        }
    }

    /** 실패 원인 추적용 — 문서 PK 문자열이라 개인정보가 아니다(FK 위반이면 이 목록이 곧 용의자). */
    static String docIds(RagChatResponse data) {
        List<RagChatResponse.SourceCitation> sources = data.sources();
        if (sources == null || sources.isEmpty()) {
            return "[]";
        }
        return sources.stream()
                .map(RagChatResponse.SourceCitation::docId)
                .collect(Collectors.joining(",", "[", "]"));
    }
}
