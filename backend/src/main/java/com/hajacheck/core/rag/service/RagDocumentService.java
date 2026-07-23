package com.hajacheck.core.rag.service;

import com.hajacheck.auth.support.FileStorageService;
import com.hajacheck.auth.support.FileStorageService.StoredFile;
import com.hajacheck.core.ai.dto.RagEmbedRequest;
import com.hajacheck.core.ai.dto.RagEmbedResponse;
import com.hajacheck.core.ai.service.AiProxyService;
import com.hajacheck.core.rag.dto.RagDocumentResponse;
import com.hajacheck.core.rag.dto.RagDocumentUploadRequest;
import com.hajacheck.core.rag.entity.RagDocument;
import com.hajacheck.core.rag.entity.RagDocumentSourceType;
import com.hajacheck.core.rag.entity.RagDocumentVerificationStatus;
import com.hajacheck.core.rag.entity.RagTargetCollection;
import com.hajacheck.core.rag.repository.RagDocumentRepository;
import com.hajacheck.core.rag.support.PdfTextExtractor;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * RAG 문서 관리 — 플랫폼 관리자 콘솔(#22/HAJA-35, PRD FR-8-B). 법규·지침 PDF를 업로드하면 PDFBox로
 * 텍스트를 추출해 FastAPI에 JSON payload로 전달하고(Spring↔FastAPI 컨테이너 간 파일 공유 볼륨이 없어
 * 파일이 아니라 텍스트를 넘기는 아키텍처, handoff §아키텍처 결정), 결과에 따라 embedding_status를
 * PENDING→EMBEDDING→DONE/FAILED로 전이한다. 재임베딩은 관리자의 명시적 액션으로만 트리거되고(자동
 * 스케줄 없음 — PRD "재임베딩은 명시적 배치 잡으로 분리" 요건), AI 서버가 기존 청크를 삭제 후 재삽입하는
 * idempotent 설계라 여러 번 실행해도 결과가 수렴한다.
 *
 * <p>company 스코핑 없음 — 법규·지침 문서는 회사 소유 리소스가 아니라 플랫폼이 공유하는 지식베이스
 * 원본이다(rag_documents 엔티티에 company_id 자체가 없음, FR-8-B 공통 원칙).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RagDocumentService {

    private static final String FILE_CATEGORY = "rag-documents";
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("application/pdf");
    // PDF 전용이라 FileStorageService 기본 안내값(10MB)보다 크게 잡는다(handoff §Java 구현 상세).
    private static final long MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024;

    private final RagDocumentRepository ragDocumentRepository;
    private final RagDocumentWriter ragDocumentWriter;
    private final FileStorageService fileStorage;
    private final PdfTextExtractor pdfTextExtractor;
    private final AiProxyService aiProxyService;

    public List<RagDocumentResponse> list() {
        return ragDocumentRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(RagDocumentResponse::from)
                .toList();
    }

    /**
     * ① PDF 텍스트 추출(저장 전 — 텍스트가 없는 PDF는 파일 저장 자체를 하지 않는다) ② 파일 저장
     * ③ RagDocument(PENDING) 원자 저장(RagDocumentWriter, 별도 트랜잭션) ④ AI 서버 임베딩 호출(트랜잭션
     * 밖). ④가 실패해도 업로드 자체는 실패시키지 않는다 — FAILED로 남기고 관리자가 재임베딩으로 복구한다.
     *
     * <p>⚠️ NOT_SUPPORTED로 클래스 레벨 readOnly=true를 명시적으로 벗어난다 — 파일 IO·외부 HTTP 호출을
     * 읽기전용 트랜잭션을 연 채로 오래 들고 있지 않기 위함(MediaService.uploadMedia와 동일 패턴).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RagDocumentResponse upload(MultipartFile file, RagDocumentUploadRequest request) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_REQUIRED);
        }
        String text = extractText(file);

        StoredFile stored = fileStorage.store(file, FILE_CATEGORY, ALLOWED_CONTENT_TYPES, MAX_FILE_SIZE_BYTES);
        RagDocument document;
        try {
            document = ragDocumentWriter.create(request, stored.storageKey());
        } catch (RuntimeException e) {
            // DB 저장 실패 — 방금 저장한 파일을 보상삭제해 고아 파일 방지(MediaService와 동일 원칙).
            fileStorage.delete(stored.storageKey());
            throw e;
        }

        Long documentId = document.getId();
        embed(documentId, request.title(), request.sourceType(), request.targetCollection(), text,
                request.effectiveDate(), request.publisher(), request.authoredAt(), null, false);

        return RagDocumentResponse.from(ragDocumentRepository.findByIdOrThrow(documentId));
    }

    /**
     * 재임베딩(#22 handoff) — 기존 상태(PENDING/DONE/FAILED) 무관하게 명시적 관리자 액션으로만
     * 트리거한다. 원본 PDF를 저장소에서 다시 읽어 텍스트를 재추출하고, AI 서버가 동일 doc_id의 기존
     * Chroma 청크를 삭제 후 재삽입하므로 여러 번 실행해도 결과가 수렴한다(idempotent).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RagDocumentResponse reEmbed(Long id) {
        RagDocument document = ragDocumentRepository.findByIdOrThrow(id);
        byte[] pdfBytes = fileStorage.read(document.getFileUrl());
        String text = pdfTextExtractor.extractText(pdfBytes);

        embed(id, document.getTitle(), document.getSourceType(), document.getTargetCollection(), text,
                document.getEffectiveDate(), document.getPublisher(), document.getAuthoredAt(),
                document.getVerificationStatus(), true);

        return RagDocumentResponse.from(ragDocumentRepository.findByIdOrThrow(id));
    }

    private void embed(Long documentId, String title, RagDocumentSourceType sourceType,
                        RagTargetCollection targetCollection, String text, LocalDate effectiveDate,
                        String publisher, LocalDate authoredAt,
                        RagDocumentVerificationStatus verificationStatus, boolean isReEmbed) {
        // 최초 업로드(PENDING/FAILED만 허용)와 재임베딩(PENDING/DONE/FAILED 어디서든 허용)은 시작
        // 전이의 허용 선행 상태가 달라 엔티티 메서드가 분리돼 있다(RagDocument.startEmbedding() vs
        // restartEmbedding()) — 재임베딩을 resetForReEmbed()+startEmbedding() 2단계 트랜잭션으로
        // 나눴던 이전 구현은 그 사이 DONE 문서가 일시적으로 PENDING처럼 보이는 창을 만들었다
        // (code-review P2, RagDocument.restartEmbedding() 주석 참고).
        if (isReEmbed) {
            ragDocumentWriter.markReEmbeddingStarted(documentId);
        } else {
            ragDocumentWriter.markEmbeddingStarted(documentId);
        }

        // targetCollection.name()은 대문자(REGULATIONS/DEFECT_KB)지만 ai-server는 소문자 상수
        // (vectorstore.py COLLECTION_REGULATIONS/COLLECTION_DEFECT_KB)와 정확히 일치하는지만
        // 비교한다 — 그대로 보내면 매번 "unknown target_collection" ValueError로 임베딩이 실패한다
        // (code-review P1). toLowerCase()로 두 값(REGULATIONS→regulations, DEFECT_KB→defect_kb)
        // 모두 정확히 매핑된다.
        RagEmbedRequest aiRequest = new RagEmbedRequest(
                String.valueOf(documentId), title, sourceType.name(),
                targetCollection.name().toLowerCase(java.util.Locale.ROOT), text,
                effectiveDate == null ? null : effectiveDate.toString(),
                publisher,
                authoredAt == null ? null : authoredAt.toString(),
                verificationStatus == null ? null : verificationStatus.name());
        try {
            ApiResponse<RagEmbedResponse> response = aiProxyService.embedRagDocument(aiRequest);
            if (response.success() && response.data() != null) {
                ragDocumentWriter.completeEmbedding(documentId, response.data().chunkCount());
            } else {
                log.warn("RAG 문서 임베딩 실패(AI 서버 응답 실패) documentId={}", documentId);
                ragDocumentWriter.failEmbedding(documentId);
            }
        } catch (BusinessException e) {
            // AI 서버 연결/타임아웃/응답형식 실패 — 업로드 자체는 실패시키지 않고 FAILED로 남긴다
            // (관리자가 재임베딩으로 복구, handoff §Java 구현 상세).
            log.warn("RAG 문서 임베딩 실패(AI 서버 호출 예외) documentId={} errorCode={}",
                    documentId, e.getErrorCode(), e);
            ragDocumentWriter.failEmbedding(documentId);
        }
    }

    private String extractText(MultipartFile file) {
        try {
            return pdfTextExtractor.extractText(file.getBytes());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.RAG_TEXT_EXTRACTION_FAILED);
        }
    }

}
