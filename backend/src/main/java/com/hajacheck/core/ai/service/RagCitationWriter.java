package com.hajacheck.core.ai.service;

import com.hajacheck.core.ai.dto.RagChatResponse;
import com.hajacheck.core.rag.entity.ChatMessageCitation;
import com.hajacheck.core.rag.repository.ChatMessageCitationRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * RAG 답변의 출처(citation)만 저장하는 writer(#1593).
 *
 * <p>알려진 저장 실패 경로가 모두 여기 몰려 있어({@code chat_message_citations} 의 FK·unique·NOT NULL·
 * 길이 제약) 대화 메시지 저장과 물리 트랜잭션을 분리했다. 이 트랜잭션이 통째로 롤백돼도 질문·답변은
 * 이미 커밋돼 있어 사용자는 이력을 잃지 않는다(출처 칩만 사라진다).
 *
 * <p><b>호출 순서 제약(중요)</b>: 반드시 {@link RagChatMessageWriter#saveMessages} 트랜잭션이
 * <b>커밋된 뒤</b>에 호출해야 한다. {@code chat_message_citations.message_id} 는 {@code chat_messages}
 * 를 참조하는 FK이므로, 메시지 트랜잭션 <i>안에서</i> 이 {@code REQUIRES_NEW} 를 호출하면 별도 물리
 * 트랜잭션이 아직 커밋 안 된 봇 메시지 행을 볼 수 없어 정상 경로에서도 FK 위반이 난다(중첩이 아니라
 * 순차 실행이어야 하는 이유).
 *
 * <p>전파를 {@code REQUIRES_NEW} 로 명시하는 이유는 {@link RagChatMessageWriter} javadoc 참고 —
 * 바깥 트랜잭션이 생기더라도 rollback-only 오염이 번지지 않게 구조로 못박는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagCitationWriter {

    /** {@code chat_message_citations.chunk_ref} 컬럼 길이(V1 baseline) — 초과 시 22001 로 터진다. */
    private static final int CHUNK_REF_MAX_LENGTH = 100;

    private final ChatMessageCitationRepository chatMessageCitationRepository;

    /**
     * 봇 메시지에 출처를 매핑해 저장한다. 저장 불가능한 항목(식별자 파싱 실패·필수 필드 결측·길이 초과·
     * 중복)은 건너뛰고 나머지는 그대로 저장한다 — 한 건 때문에 나머지 출처까지 잃지 않기 위함.
     *
     * <p>이 메서드는 예외를 삼키지 않는다. 흡수 책임은 호출부
     * ({@link RagConversationPersistenceService#saveConversation})에 있다 — 여기서 잡으면 이
     * {@code @Transactional} 이 이미 rollback-only 로 마킹된 뒤라 커밋 시점에
     * {@code UnexpectedRollbackException} 으로 다시 터진다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveCitations(Long botMessageId, List<RagChatResponse.SourceCitation> sources) {
        Set<String> seenChunks = new HashSet<>();
        int duplicateCount = 0;

        for (RagChatResponse.SourceCitation source : sources) {
            Long documentId = parseDocumentId(source.docId());
            if (documentId == null || !isStorable(documentId, source)) {
                continue;
            }
            // unique(message_id, document_id, chunk_ref) 위반 선제 차단 — 리랭킹 결과에 같은 청크가
            // 중복 등장하는 것은 흔한 정상 경로다. messageId 는 이 루프 안에서 고정이라 나머지 두
            // 컬럼만으로 중복을 판정하면 되고, documentId 가 숫자라 ':' 앞뒤 값이 섞일 여지도 없다.
            if (!seenChunks.add(documentId + ":" + source.chunkRef())) {
                duplicateCount++;
                continue;
            }
            chatMessageCitationRepository.save(ChatMessageCitation.create(
                    botMessageId, documentId, source.chunkRef(), source.locator(), source.snippet()));
        }

        if (duplicateCount > 0) {
            // 정상 경로에서도 상시 발생할 수 있어 요청당 1건으로 요약한다(WARN 이면 로그 노이즈).
            log.debug("RAG 출처 중복 인용 {}건 생략 — botMessageId={}", duplicateCount, botMessageId);
        }
    }

    /**
     * NOT NULL·길이 제약(V1 baseline: {@code chunk_ref varchar(100) NOT NULL}, {@code locator text
     * NOT NULL}, {@code snippet text NOT NULL})을 저장 전에 거른다 — ai-server 가 {@code min_length=1}
     * 로 막고 있지만, 계약이 느슨해지면 23502/22001 로 출처 저장이 통째로 실패한다. docId 파싱 실패를
     * skip+warn 으로 처리하는 것과 같은 계열의 방어다.
     */
    private boolean isStorable(Long documentId, RagChatResponse.SourceCitation source) {
        if (!StringUtils.hasText(source.chunkRef())
                || source.chunkRef().length() > CHUNK_REF_MAX_LENGTH
                || !StringUtils.hasText(source.locator())
                || !StringUtils.hasText(source.snippet())) {
            log.warn("RAG 출처 필드 결측/길이 초과 — citation 저장 생략: documentId={}, chunkRef={}",
                    documentId, source.chunkRef());
            return false;
        }
        return true;
    }

    private Long parseDocumentId(String docId) {
        try {
            return Long.parseLong(docId);
        } catch (NumberFormatException e) {
            log.warn("RAG 출처 doc_id 파싱 실패 — citation 저장 생략: {}", docId);
            return null;
        }
    }
}
