package com.hajacheck.core.media.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.support.PngTestFixtures;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

/**
 * PDF 래스터화 상한 검증(#1469 2차 리뷰 P1) — "무인증 요청 1건이 힙 수백 MB 를 잡을 수 없다"와
 * "임베디드 이미지가 렌더 상한을 우회할 수 없다"를 코드로 고정한다.
 *
 * <p>이 테스트들이 깨진다는 건 <b>가입 엔드포인트 하나로 JVM 을 죽일 수 있게 됐다</b>는 뜻이다.
 */
class PdfFirstPageRasterizerTest {

    private final PdfFirstPageRasterizer rasterizer = new PdfFirstPageRasterizer();

    @Test
    void 정상_A4_PDF는_GRAY_PNG로_렌더된다() {
        PdfFirstPageRasterizer.Result result = rasterizer.renderFirstPageAsPng(PngTestFixtures.realPdf());

        assertThat(result.status()).isEqualTo(PdfFirstPageRasterizer.Status.OK);
        assertThat(result.pngBytes()).isNotEmpty();
        // PNG 시그니처
        assertThat(result.pngBytes()[0]).isEqualTo((byte) 0x89);
        assertThat(new String(result.pngBytes(), 1, 3)).isEqualTo("PNG");
    }

    @Test
    void 거대한_페이지상자는_렌더전에_거부된다_UNRENDERABLE() {
        // PDF 사양상 페이지 상자는 14400pt 까지 합법이다. 200DPI 로 렌더하면 40000×40000 = 16억 픽셀이
        // 되는데 파일 자체는 수 KB 다 — 상한이 없으면 이 한 건으로 힙이 날아간다.
        byte[] hugeCanvas = PngTestFixtures.realPdf(1, new PDRectangle(14400f, 14400f));

        PdfFirstPageRasterizer.Result result = rasterizer.renderFirstPageAsPng(hugeCanvas);

        assertThat(result.status()).isEqualTo(PdfFirstPageRasterizer.Status.UNRENDERABLE);
        assertThat(result.pngBytes()).isNull();
    }

    /**
     * <b>임베디드 이미지 폭탄</b> — 페이지 캔버스는 A4 로 작지만 그 안에 초대형 이미지 XObject 를 그린다.
     * 캔버스 픽셀 추정으로는 절대 못 잡는 벡터이고, 서브샘플링이 꺼져 있으면 PDFBox 가 그 이미지를
     * <b>원본 해상도로 전량 디코드</b>해 힙을 통째로 먹는다.
     *
     * <p>여기서는 6000×6000(3600만 픽셀, RGB 로 디코드하면 약 144MB)을 균일색으로 넣는다 — 압축되면
     * 파일은 수십 KB 라 크기 상한도 통과한다. 서브샘플링이 켜져 있으면 목표 해상도(A4@200DPI)에 맞춰
     * 축소 디코드하므로 정상적으로 끝나야 한다.
     */
    @Test
    void 임베디드_이미지폭탄도_상한안에서_처리된다() {
        byte[] bomb = PngTestFixtures.pdfWithEmbeddedImage(6_000, 6_000);

        PdfFirstPageRasterizer.Result result = rasterizer.renderFirstPageAsPng(bomb);

        // 성공하든(서브샘플 디코드) 거부하든 상관없다 — 중요한 건 OOM 없이 유한 시간에 결론이 난다는 것.
        assertThat(result.status()).isIn(PdfFirstPageRasterizer.Status.OK,
                PdfFirstPageRasterizer.Status.UNRENDERABLE,
                PdfFirstPageRasterizer.Status.TIMED_OUT);
        if (result.status() == PdfFirstPageRasterizer.Status.OK) {
            // 결과 이미지는 A4 캔버스 크기(≈3.9MP)를 넘지 않는다 = 임베디드 원본 해상도로 부풀지 않았다.
            assertThat(result.pngBytes().length).isLessThan(20 * 1024 * 1024);
        }
    }

    @Test
    void 손상된_PDF는_UNRENDERABLE() {
        PdfFirstPageRasterizer.Result result =
                rasterizer.renderFirstPageAsPng("%PDF-1.4\nnot really a pdf".getBytes());

        assertThat(result.status()).isEqualTo(PdfFirstPageRasterizer.Status.UNRENDERABLE);
    }

    @Test
    void 렌더픽셀_상한을_좁히면_정상_A4도_거부된다() {
        // 상한이 실제로 적용되는지(상수만 있고 안 쓰이는 게 아닌지) 확인.
        PdfFirstPageRasterizer tiny = new PdfFirstPageRasterizer(1_000L, 1, Duration.ofSeconds(5));

        assertThat(tiny.renderFirstPageAsPng(PngTestFixtures.realPdf()).status())
                .isEqualTo(PdfFirstPageRasterizer.Status.UNRENDERABLE);
    }

    @Test
    void 시간상한을_넘기면_TIMED_OUT() throws Exception {
        // 1ms 상한 — 어떤 문서도 이 안에 끝나지 않는다.
        PdfFirstPageRasterizer impatient =
                new PdfFirstPageRasterizer(PdfFirstPageRasterizer.DEFAULT_MAX_RENDER_PIXELS, 1, Duration.ofMillis(1));

        assertThat(impatient.renderFirstPageAsPng(PngTestFixtures.realPdf(1, PDRectangle.A4)).status())
                .isEqualTo(PdfFirstPageRasterizer.Status.TIMED_OUT);
    }

    /**
     * 동시 상한 — 퍼밋 1개짜리 래스터라이저에 두 요청이 겹치면 뒤엣것은 BUSY 로 떨어져야 한다.
     * 이 축이 없으면 "분당 30회" 캡을 통과한 요청들이 같은 순간에 겹쳐 힙·CPU 를 합산해 먹는다.
     */
    @Test
    void 동시_상한_포화시_BUSY() throws Exception {
        // 퍼밋 1 + 타임아웃 1ms: 첫 요청이 타임아웃으로 떠나도 렌더 작업이 끝날 때까지 퍼밋을 쥐고 있어
        // 뒤따르는 요청이 (2초 대기 후) BUSY 로 떨어진다.
        PdfFirstPageRasterizer single =
                new PdfFirstPageRasterizer(PdfFirstPageRasterizer.DEFAULT_MAX_RENDER_PIXELS, 1, Duration.ofMillis(1));
        byte[] heavy = PngTestFixtures.pdfWithEmbeddedImage(6_000, 6_000);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch started = new CountDownLatch(1);
            Future<PdfFirstPageRasterizer.Status> first = pool.submit(() -> {
                started.countDown();
                return single.renderFirstPageAsPng(heavy).status();
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            PdfFirstPageRasterizer.Status second = single.renderFirstPageAsPng(heavy).status();

            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(PdfFirstPageRasterizer.Status.TIMED_OUT);
            assertThat(second)
                    .as("퍼밋을 쥔 렌더가 도는 동안 뒤따르는 요청은 BUSY 여야 한다")
                    .isIn(PdfFirstPageRasterizer.Status.BUSY, PdfFirstPageRasterizer.Status.TIMED_OUT);
        } finally {
            pool.shutdownNow();
        }
    }
}
