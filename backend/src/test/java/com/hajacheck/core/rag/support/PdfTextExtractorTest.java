package com.hajacheck.core.rag.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

/** PdfTextExtractor 검증(#22/HAJA-35) — 실제 PDFBox로 조립한 최소 PDF를 사용한다. */
class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    @Test
    void extractText_텍스트있는PDF_본문을반환() throws IOException {
        // Standard14Fonts(Helvetica)는 WinAnsiEncoding만 지원해 한글을 직접 임베드할 수 없다 —
        // 추출 로직 자체(PDFTextStripper 연동) 검증이 목적이므로 ASCII 문장으로 대체한다.
        byte[] pdf = buildPdfWithText("Article 1 (Purpose) This guideline defines facility safety inspections.");

        String text = extractor.extractText(pdf);

        assertThat(text).contains("Article 1");
    }

    @Test
    void extractText_텍스트레이어없는PDF_추출실패예외() throws IOException {
        byte[] pdf = buildEmptyPagePdf();

        assertThatThrownBy(() -> extractor.extractText(pdf))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.RAG_TEXT_EXTRACTION_FAILED));
    }

    @Test
    void extractText_손상된바이트_추출실패예외() {
        byte[] garbage = "이건 PDF가 아닙니다".getBytes();

        assertThatThrownBy(() -> extractor.extractText(garbage))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.RAG_TEXT_EXTRACTION_FAILED));
    }

    private byte[] buildPdfWithText(String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(text);
                stream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private byte[] buildEmptyPagePdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
