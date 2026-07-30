package com.hajacheck.core.rag.support;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * 업로드된 PDF에서 본문 텍스트를 추출한다(#22/HAJA-35) — Spring↔FastAPI 컨테이너 간 파일 공유 볼륨이
 * 없어, Spring이 PDF를 직접 파싱해 텍스트 payload로 AI 서버에 전달하는 아키텍처를 택했다(handoff §아키텍처
 * 결정). 스캔 이미지만 있는 PDF 등 텍스트 레이어가 없는 문서는 추출 결과가 비어 RAG_TEXT_EXTRACTION_FAILED
 * 로 거부한다(OCR은 이번 범위 밖).
 */
@Component
public class PdfTextExtractor {

    public String extractText(byte[] pdfBytes) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            String text = new PDFTextStripper().getText(document);
            if (text == null || text.isBlank()) {
                throw new BusinessException(ErrorCode.RAG_TEXT_EXTRACTION_FAILED);
            }
            return text;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.RAG_TEXT_EXTRACTION_FAILED);
        }
    }
}
