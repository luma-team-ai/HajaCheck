package com.hajacheck.core.rag.service;

import com.hajacheck.core.rag.dto.RagDocumentUploadRequest;
import com.hajacheck.core.rag.entity.RagDocument;
import com.hajacheck.core.rag.repository.RagDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * RAG 문서 DB 원자쓰기 전담 빈(#22/HAJA-35) — MediaService/MediaWriter와 동일한 패턴으로,
 * RagDocumentService의 IO(PDF 파싱·파일 저장)·외부 HTTP 호출(AI 서버)을 트랜잭션 밖에서 수행하는
 * 동안 각 상태 전이만 짧은 독립 트랜잭션으로 원자 커밋한다(self-invocation 회피).
 */
@Component
@RequiredArgsConstructor
public class RagDocumentWriter {

    private final RagDocumentRepository ragDocumentRepository;

    @Transactional
    public RagDocument create(RagDocumentUploadRequest request, String storageKey) {
        RagDocument document = RagDocument.upload(
                request.title(), request.sourceType(), request.targetCollection(),
                request.effectiveDate(), request.publisher(), request.authoredAt(),
                null, storageKey);
        return ragDocumentRepository.save(document);
    }

    @Transactional
    public void markEmbeddingStarted(Long id) {
        ragDocumentRepository.findByIdOrThrow(id).startEmbedding();
    }

    @Transactional
    public void completeEmbedding(Long id, int chunkCount) {
        ragDocumentRepository.findByIdOrThrow(id).completeEmbedding(chunkCount);
    }

    @Transactional
    public void failEmbedding(Long id) {
        ragDocumentRepository.findByIdOrThrow(id).failEmbedding();
    }

    @Transactional
    public void markReEmbeddingStarted(Long id) {
        ragDocumentRepository.findByIdOrThrow(id).restartEmbedding();
    }

    /**
     * AI 서버가 임베딩 요청을 접수한 직후 이번 요청의 기대 청크 수·배치 식별자를 기록한다(#1393 리뷰
     * 2차 P2) — RagEmbeddingStaleReconciler가 폴러 상한 이후 완료 재확인에 쓴다.
     */
    @Transactional
    public void recordEmbedRequest(Long id, int expectedChunkCount, String embedBatchId) {
        ragDocumentRepository.findByIdOrThrow(id).recordEmbedRequest(expectedChunkCount, embedBatchId);
    }

    /**
     * DB 로우 삭제(#1394) — 파일(storageKey)을 먼저 읽어 반환해야, 트랜잭션 밖(RagDocumentService)에서
     * DB 커밋이 끝난 뒤에만 실제 파일을 지울 수 있다(순서가 바뀌면 DB 삭제가 실패했는데 파일만 먼저
     * 지워지는 반쪽 삭제가 생긴다 — create()의 보상삭제와 반대 방향의 동일한 원자성 원칙).
     */
    @Transactional
    public String delete(Long id) {
        RagDocument document = ragDocumentRepository.findByIdOrThrow(id);
        String storageKey = document.getFileUrl();
        ragDocumentRepository.delete(document);
        return storageKey;
    }
}
