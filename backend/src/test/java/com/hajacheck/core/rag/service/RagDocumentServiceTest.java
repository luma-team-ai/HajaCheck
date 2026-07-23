package com.hajacheck.core.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.support.FileStorageService;
import com.hajacheck.auth.support.FileStorageService.StoredFile;
import com.hajacheck.core.ai.dto.RagEmbedResponse;
import com.hajacheck.core.ai.service.AiProxyService;
import com.hajacheck.core.rag.dto.RagDocumentResponse;
import com.hajacheck.core.rag.dto.RagDocumentUploadRequest;
import com.hajacheck.core.rag.entity.RagDocument;
import com.hajacheck.core.rag.entity.RagDocumentSourceType;
import com.hajacheck.core.rag.entity.RagEmbeddingStatus;
import com.hajacheck.core.rag.entity.RagTargetCollection;
import com.hajacheck.core.rag.repository.RagDocumentRepository;
import com.hajacheck.core.rag.support.PdfTextExtractor;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.multipart.MultipartFile;

/**
 * RagDocumentService 단위 테스트(#22/HAJA-35) — RagDocumentWriter(DB 원자쓰기)·AiProxyService(AI 서버
 * 프록시)는 목으로 대체한다. RagDocumentWriter의 각 상태전이 메서드는 실제 프로덕션에서 fresh 조회한
 * RagDocument에 동일 엔티티 메서드를 호출하는 얇은 위임이므로, 테스트에서는 공유 인스턴스에 같은 엔티티
 * 메서드를 호출하는 doAnswer로 그 효과를 재현해 서비스가 반환하는 최종 상태를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagDocumentServiceTest {

    @Mock
    private RagDocumentRepository ragDocumentRepository;
    @Mock
    private RagDocumentWriter ragDocumentWriter;
    @Mock
    private FileStorageService fileStorage;
    @Mock
    private PdfTextExtractor pdfTextExtractor;
    @Mock
    private AiProxyService aiProxyService;
    @Mock
    private MultipartFile file;

    @InjectMocks
    private RagDocumentService ragDocumentService;

    private RagDocument document;

    private static final RagDocumentUploadRequest REQUEST = new RagDocumentUploadRequest(
            "시설물 안전법", RagDocumentSourceType.LAW, RagTargetCollection.REGULATIONS,
            null, "국토교통부", null);

    @BeforeEach
    void setUp() throws IOException {
        document = RagDocument.upload(
                REQUEST.title(), REQUEST.sourceType(), REQUEST.targetCollection(),
                REQUEST.effectiveDate(), REQUEST.publisher(), REQUEST.authoredAt(),
                null, "rag-documents/stub.pdf");

        when(file.isEmpty()).thenReturn(false);
        when(file.getBytes()).thenReturn("dummy-pdf-bytes".getBytes());
        when(pdfTextExtractor.extractText(any())).thenReturn("추출된 본문");
        when(fileStorage.store(any(), any(), any(), anyLong()))
                .thenReturn(new StoredFile("https://files.example/rag-documents/stub.pdf", "rag-documents/stub.pdf"));
        when(ragDocumentWriter.create(any(), any())).thenReturn(document);
        // findByIdOrThrow는 RagDocumentRepository의 default 메서드다 — Mockito @Mock은 default
        // 메서드도 스텁 없이는 null을 반환한다(실제 구현으로 자동 위임되지 않는다). findById만
        // 스텁해두면 findByIdOrThrow(내부에서 findById 호출)가 아니라 이 메서드 자체가 직접
        // 목이라 반드시 따로 스텁해야 한다(code-review 회귀 — 재사용 리팩터 시 놓쳤던 부분).
        when(ragDocumentRepository.findByIdOrThrow(any())).thenReturn(document);

        // RagDocumentWriter의 상태전이 메서드가 실제로는 fresh 조회한 엔티티에 위임하는 효과를 재현.
        doAnswer(inv -> {
            document.startEmbedding();
            return null;
        }).when(ragDocumentWriter).markEmbeddingStarted(any());
        doAnswer(inv -> {
            document.completeEmbedding(inv.getArgument(1));
            return null;
        }).when(ragDocumentWriter).completeEmbedding(any(), anyInt());
        doAnswer(inv -> {
            document.failEmbedding();
            return null;
        }).when(ragDocumentWriter).failEmbedding(any());
        doAnswer(inv -> {
            document.restartEmbedding();
            return null;
        }).when(ragDocumentWriter).markReEmbeddingStarted(any());
    }

    @Test
    void upload_AI서버성공_완료상태로전환() {
        when(aiProxyService.embedRagDocument(any())).thenReturn(ApiResponse.ok(new RagEmbedResponse(12)));

        RagDocumentResponse response = ragDocumentService.upload(file, REQUEST);

        assertThat(response.embeddingStatus()).isEqualTo(RagEmbeddingStatus.DONE);
        assertThat(response.chunkCount()).isEqualTo(12);
        verify(ragDocumentWriter).markEmbeddingStarted(any());
        verify(ragDocumentWriter).completeEmbedding(any(), anyInt());
        verify(ragDocumentWriter, never()).failEmbedding(any());
    }

    @Test
    void upload_AI서버가업무실패응답_예외전파없이FAILED로전환() {
        // envelope.success()=false(예: AI 서버가 VALIDATION_ERROR로 거부) — 업로드 자체는 실패시키지 않는다.
        when(aiProxyService.embedRagDocument(any())).thenReturn(ApiResponse.fail("VALIDATION_ERROR", "청크 분할 실패"));

        RagDocumentResponse response = ragDocumentService.upload(file, REQUEST);

        assertThat(response.embeddingStatus()).isEqualTo(RagEmbeddingStatus.FAILED);
        verify(ragDocumentWriter).failEmbedding(any());
    }

    @Test
    void upload_AI서버연결예외_예외전파없이FAILED로전환() {
        when(aiProxyService.embedRagDocument(any()))
                .thenThrow(new BusinessException(ErrorCode.AI_SERVER_UNREACHABLE));

        RagDocumentResponse response = ragDocumentService.upload(file, REQUEST);

        assertThat(response.embeddingStatus()).isEqualTo(RagEmbeddingStatus.FAILED);
        verify(ragDocumentWriter).failEmbedding(any());
    }

    @Test
    void upload_텍스트추출실패_파일저장을시도하지않고예외전파() {
        when(pdfTextExtractor.extractText(any()))
                .thenThrow(new BusinessException(ErrorCode.RAG_TEXT_EXTRACTION_FAILED));

        assertThatThrownBy(() -> ragDocumentService.upload(file, REQUEST))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.RAG_TEXT_EXTRACTION_FAILED));
        verify(fileStorage, never()).store(any(), any(), any(), anyLong());
    }

    @Test
    void upload_파일이비어있으면_FILE_REQUIRED예외() {
        when(file.isEmpty()).thenReturn(true);

        assertThatThrownBy(() -> ragDocumentService.upload(file, REQUEST))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_REQUIRED));
    }

    @Test
    void upload_DB저장실패_저장한파일을보상삭제하고예외전파() {
        when(ragDocumentWriter.create(any(), any())).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> ragDocumentService.upload(file, REQUEST))
                .isInstanceOf(RuntimeException.class);
        verify(fileStorage).delete("rag-documents/stub.pdf");
    }

    @Test
    void reEmbed_완료문서를재추출하여다시완료로전환() {
        document.startEmbedding();
        document.completeEmbedding(5);
        when(fileStorage.read("rag-documents/stub.pdf")).thenReturn("dummy-pdf-bytes".getBytes());
        when(aiProxyService.embedRagDocument(any())).thenReturn(ApiResponse.ok(new RagEmbedResponse(9)));

        RagDocumentResponse response = ragDocumentService.reEmbed(document.getId());

        assertThat(response.embeddingStatus()).isEqualTo(RagEmbeddingStatus.DONE);
        assertThat(response.chunkCount()).isEqualTo(9);
        verify(ragDocumentWriter).markReEmbeddingStarted(any());
    }

    @Test
    void reEmbed_문서없음_RAG_DOCUMENT_NOT_FOUND예외() {
        when(ragDocumentRepository.findByIdOrThrow(any()))
                .thenThrow(new BusinessException(ErrorCode.RAG_DOCUMENT_NOT_FOUND));

        assertThatThrownBy(() -> ragDocumentService.reEmbed(999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.RAG_DOCUMENT_NOT_FOUND));
    }
}
