package com.hajacheck.core.rag.dto;

import com.hajacheck.core.rag.entity.RagDocumentSourceType;
import com.hajacheck.core.rag.entity.RagTargetCollection;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * RAG 문서 업로드 요청(multipart/form-data, {@code @ModelAttribute} 바인딩) — #22/HAJA-35.
 * multipart 파일 자체는 컨트롤러에서 별도 {@code @RequestParam("file")}로 받는다(이 레코드에는
 * 메타데이터만 담는다). effectiveDate/publisher/authoredAt은 문서 종류(LAW/GUIDELINE, regulations/
 * defect_kb)에 따라 선택적으로 채워진다 — rag_chroma_schema.md §2/§4/§5 참고.
 */
public record RagDocumentUploadRequest(
        @NotBlank(message = "제목은 필수입니다.")
        @Size(max = 300, message = "제목은 300자 이하여야 합니다.")
        String title,

        @NotNull(message = "출처 유형은 필수입니다.")
        RagDocumentSourceType sourceType,

        @NotNull(message = "임베딩 대상 컬렉션은 필수입니다.")
        RagTargetCollection targetCollection,

        LocalDate effectiveDate,

        @Size(max = 200, message = "발행 기관명은 200자 이하여야 합니다.")
        String publisher,

        LocalDate authoredAt) {
}
