package com.hajacheck.core.rag.service;

import com.hajacheck.core.rag.dto.RagDocumentUploadRequest;
import com.hajacheck.core.rag.entity.RagDocument;
import com.hajacheck.core.rag.repository.RagDocumentRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
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
        findOrThrow(id).startEmbedding();
    }

    @Transactional
    public void completeEmbedding(Long id, int chunkCount) {
        findOrThrow(id).completeEmbedding(chunkCount);
    }

    @Transactional
    public void failEmbedding(Long id) {
        findOrThrow(id).failEmbedding();
    }

    @Transactional
    public void resetForReEmbed(Long id) {
        findOrThrow(id).resetForReEmbed();
    }

    private RagDocument findOrThrow(Long id) {
        return ragDocumentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RAG_DOCUMENT_NOT_FOUND));
    }
}
