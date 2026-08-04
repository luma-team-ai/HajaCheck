package com.hajacheck.core.media.support;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * PDF 첫 페이지만 PNG 로 래스터화한다(#1469) — 사업자등록증을 PDF 로 올린 사용자도 OCR 대조 게이트를
 * 통과하게 하기 위한 어댑터. RapidOCR 은 래스터 이미지만 다루므로, PDF 를 "대조 불가"로 흘려보내면
 * <b>파트 헤더를 {@code application/pdf} 로 바꾸는 것만으로 게이트를 우회</b>할 수 있다.
 *
 * <p>PDFBox 는 이미 RAG 문서 텍스트 추출({@code PdfTextExtractor}, #22)로 들어와 있는 의존성이라
 * 신규 도입이 아니다.
 *
 * <p><b>비용 통제</b>: 래스터화는 CPU·메모리를 쓰는 작업이라 무방비로 열면 안 된다. 이 클래스는
 * ①검사({@link #inspect})와 ②실제 렌더({@link #renderFirstPageAsPng})를 <b>분리</b>해, 호출부가
 * "싼 검사 → rate-limit 획득 → 비싼 렌더" 순서를 지킬 수 있게 한다. 렌더는 <b>첫 페이지 한 장</b>만
 * 수행한다(페이지 수가 몇이든 대조에 필요한 건 등록증 본문 1장뿐).
 */
public final class PdfFirstPageRasterizer {

    /**
     * 렌더 DPI. <b>200 산정 근거</b>: 문서 OCR 권장 해상도는 통상 200~300 DPI 이고, 200 DPI 면 A4
     * (8.27×11.69 inch) 기준 약 1654×2339px(≈3.9MP)로 사업자등록번호 같은 인쇄 숫자를 인식하기에
     * 충분하다. 300 DPI 로 올리면 픽셀 수가 2.25배(≈8.7MP)가 되어 래스터화 CPU·힙과 base64 페이로드
     * (AI 서버로 그대로 전송된다)가 함께 커지는데, 이 경로는 <b>무인증 가입 제출</b> 안에서 동기로
     * 돌아가므로 그 비용을 감수할 이유가 없다. 인식률이 문제가 되면 그때 올린다.
     */
    public static final int RENDER_DPI = 200;

    private static final float POINTS_PER_INCH = 72f;

    private PdfFirstPageRasterizer() {
    }

    /**
     * 래스터화 전 사전 검사 결과 — 페이지 수와 "이 DPI 로 렌더하면 몇 픽셀이 되는지"의 추정치.
     * 실제 렌더 없이 페이지 상자(MediaBox)만 읽어 계산하므로 픽셀 폭탄을 렌더 전에 거를 수 있다.
     */
    public record Inspection(int pageCount, long firstPageEstimatedPixels) {

        public boolean hasFirstPage() {
            return pageCount >= 1;
        }
    }

    /**
     * 문서를 열어 페이지 수와 첫 페이지의 렌더 예상 픽셀 수를 확인한다(렌더는 하지 않는다).
     *
     * <p>암호화 PDF·손상 PDF 는 {@link IOException}(PDFBox 의 {@code InvalidPasswordException} 포함)으로
     * 표면화된다 — 호출부가 fail-open 으로 흡수할지 거부할지 결정한다.
     */
    public static Inspection inspect(byte[] pdfBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int pageCount = document.getNumberOfPages();
            if (pageCount < 1) {
                return new Inspection(pageCount, 0L);
            }
            PDPage firstPage = document.getPage(0);
            PDRectangle box = firstPage.getMediaBox();
            long width = (long) Math.ceil(box.getWidth() / POINTS_PER_INCH * RENDER_DPI);
            long height = (long) Math.ceil(box.getHeight() / POINTS_PER_INCH * RENDER_DPI);
            return new Inspection(pageCount, width * height);
        }
    }

    /**
     * 첫 페이지를 {@link #RENDER_DPI} 로 렌더해 PNG 바이트로 돌려준다. 손실 없는 PNG 를 쓰는 이유는
     * OCR 대상이 얇은 인쇄 획이라 JPEG 압축 아티팩트가 인식률을 떨어뜨리기 때문이다.
     */
    public static byte[] renderFirstPageAsPng(byte[] pdfBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            if (document.getNumberOfPages() < 1) {
                throw new IOException("PDF에 페이지가 없습니다.");
            }
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(0, RENDER_DPI, ImageType.RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", out)) {
                throw new IOException("PNG 인코더를 찾지 못했습니다.");
            }
            return out.toByteArray();
        }
    }
}
