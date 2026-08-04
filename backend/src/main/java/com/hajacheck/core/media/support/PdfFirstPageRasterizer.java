package com.hajacheck.core.media.support;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

/**
 * PDF 첫 페이지만 이미지로 래스터화한다(#1469) — 사업자등록증을 PDF 로 올린 사용자도 OCR 대조 게이트를
 * 통과하게 하기 위한 어댑터. RapidOCR 은 래스터 이미지만 다루므로, PDF 를 "대조 불가"로 흘려보내면
 * 파일을 PDF 로 만드는 것만으로 게이트를 우회할 수 있다. PDFBox 는 이미 RAG 문서 텍스트 추출
 * ({@code PdfTextExtractor}, #22)로 들어와 있는 의존성이라 신규 도입이 아니다.
 *
 * <p><b>⚠️ 이 클래스의 존재 이유의 절반은 "래스터화를 안전하게 하는 것"이다</b>(2026-08-04 리뷰 P1).
 * PDF 렌더는 <b>무인증 회원가입 제출</b> 요청 안에서 동기로 실행되므로, 상한이 없으면 요청 1건으로
 * JVM 힙을 통째로 날릴 수 있다. 아래 5중 상한을 모두 통과해야 렌더가 일어난다:
 * <ol>
 *   <li><b>렌더 픽셀 상한</b>({@link #DEFAULT_MAX_RENDER_PIXELS}) — AI 페이로드 상한(50MP)과 <b>분리</b>한다.
 *       50MP 를 그대로 쓰면 픽셀당 4바이트 기준 단일 BufferedImage 가 약 200MB 다. PDF 사양상 페이지
 *       상자는 14400pt 까지 합법이라 <b>수 KB 파일로도</b> 그 픽셀 수를 선언할 수 있다.</li>
 *   <li><b>{@link ImageType#GRAY} 렌더</b> — 픽셀당 4바이트({@code TYPE_INT_RGB}) → 1바이트로 메모리 1/4.
 *       이진화가 전제인 OCR 입력으로는 오히려 유리하다.</li>
 *   <li><b>{@link PDFRenderer#setSubsamplingAllowed(boolean) 서브샘플링}</b> — 이게 없으면
 *       {@code PageDrawer} 가 임베디드 이미지를 {@code PDImage.getImage()} 로 <b>원본 해상도 전량
 *       디코드</b>한다. 페이지 캔버스가 A4 로 작아도 그 안에 100000×100000 XObject 를 넣으면(균일색이면
 *       압축 후 수백 KB) 수 GB 를 할당할 수 있다 — <b>캔버스 크기 추정으로는 절대 못 막는 벡터</b>다.</li>
 *   <li><b>동시성 상한</b>({@link Semaphore}) — rate-limit 캡은 "분당 N회"라 <b>동시 실행 수를 제한하지
 *       않는다</b>. 같은 순간 여러 건이 겹치면 각자 상한을 통과한 렌더들끼리 힙·CPU 를 합산해 먹는다.</li>
 *   <li><b>시간 상한</b>(별도 스레드 + {@code Future.get(timeout)}) — AI 호출 타임아웃은 HTTP 구간만
 *       덮는다. 패턴·셰이딩·투명그룹이 중첩된 PDF 는 렌더만으로 수십 초 CPU 를 태워, 제출 경로를 26s 로
 *       묶어둔 예산(#889 P1)을 이 경로로 되돌린다. 별도 스레드 실행은 <b>{@link OutOfMemoryError} 같은
 *       {@link Error} 까지 {@link ExecutionException} 으로 감싸 돌려준다</b>는 이점도 있다 — 호출부의
 *       {@code catch (RuntimeException)} 은 Error 를 못 잡아 fail-open 계약이 뚫린다.</li>
 * </ol>
 *
 * <p>검사(페이지 수·캔버스 픽셀)와 렌더는 <b>문서를 한 번만 열어</b> 함께 수행한다. 호출부는 이 메서드를
 * <b>rate-limit 캡을 획득한 뒤에</b> 불러야 한다 — PDF 에는 "싼 사전 검사"라는 게 없다(페이지 수를
 * 알려면 이미 객체그래프를 파싱해야 한다).
 */
@Slf4j
@Component
public class PdfFirstPageRasterizer {

    /**
     * 렌더 DPI. <b>200 산정 근거</b>: 문서 OCR 권장 해상도는 통상 200~300 DPI 이고, 200 DPI 면 A4
     * (8.27×11.69 inch) 기준 약 1654×2339px(≈3.9MP)로 사업자등록번호 같은 인쇄 숫자를 인식하기에
     * 충분하다. 300 DPI 로 올리면 픽셀 수가 2.25배(≈8.7MP)가 되어 래스터화 CPU·힙과 base64 페이로드가
     * 함께 커지는데, 이 경로는 무인증 가입 제출 안에서 동기로 돌아가므로 그 비용을 감수할 이유가 없다.
     */
    public static final int RENDER_DPI = 200;

    /**
     * 렌더 픽셀 상한 기본값 <b>12MP</b>. AI 페이로드 상한(50MP)과 반드시 분리한다 — 그건 "AI 로 보낼
     * 이미지의 상한"이고 이건 "이 프로세스가 한 번에 할당할 픽셀 버퍼의 상한"이다.
     *
     * <p><b>12MP 근거</b>: 정상 입력인 A4@200DPI 가 약 3.9MP 라 <b>3배 여유</b>다(A3·레터·여백 편차를
     * 모두 흡수한다). {@link ImageType#GRAY}(1바이트/픽셀)와 곱하면 단일 렌더 버퍼 최대 약 12MB,
     * 동시 상한(3)과 곱해도 약 36MB 라 arm1 급 힙(≈1GB)에서 안전하다.
     */
    public static final long DEFAULT_MAX_RENDER_PIXELS = 12_000_000L;

    /**
     * 동시 래스터화 허용 수 기본값 <b>3</b>. 힙은 위 계산대로 여유가 있으므로 이 값의 주 목적은 <b>CPU</b>
     * 보호다 — 렌더는 단일 스레드 CPU 바운드 작업이라, 소형 인스턴스(2 vCPU)에서 동시 렌더가 늘면 Tomcat
     * 워커 전체가 굶는다. 3 이면 정상 트래픽(가입 제출은 희박)에서 대기가 사실상 발생하지 않으면서
     * 폭주 시 상한이 걸린다.
     */
    public static final int DEFAULT_PERMITS = 3;

    /**
     * 단일 렌더 시간 상한 기본값 <b>5초</b>. 정상 등록증 1장 렌더는 수백 ms 수준이라 10배 이상 여유이고,
     * 제출 경로 예산(국세청 8s + AI 18s ≈ 26s)에 5s 를 더해도 상한이 유한하게 유지된다.
     */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    /** 동시 상한 대기 시간 — 순간적인 겹침은 잠깐 기다렸다 통과시키고, 지속 포화만 BUSY 로 돌린다. */
    private static final Duration ACQUIRE_WAIT = Duration.ofSeconds(2);

    private static final float POINTS_PER_INCH = 72f;

    /** 래스터화 결과 상태. */
    public enum Status {
        /** 렌더 성공. */
        OK,
        /** 동시 상한 포화 — 이 <b>파일</b>의 문제가 아니라 <b>용량</b> 문제다(호출부가 재시도를 유도한다). */
        BUSY,
        /** 시간 상한 초과 — 병적으로 무거운 문서. */
        TIMED_OUT,
        /** 열 수 없거나(암호화·손상) 페이지가 없거나 렌더 픽셀 상한을 넘는 문서. */
        UNRENDERABLE
    }

    /** {@link Status#OK} 일 때만 {@code pngBytes} 가 채워진다. */
    public record Result(Status status, byte[] pngBytes) {

        static Result ok(byte[] pngBytes) {
            return new Result(Status.OK, pngBytes);
        }

        static Result of(Status status) {
            return new Result(status, null);
        }
    }

    private final long maxRenderPixels;
    private final Duration timeout;
    private final int permits;
    private final Semaphore semaphore;
    private final ExecutorService executor;

    public PdfFirstPageRasterizer() {
        this(DEFAULT_MAX_RENDER_PIXELS, DEFAULT_PERMITS, DEFAULT_TIMEOUT);
    }

    /** 테스트에서 상한을 좁혀 검증하기 위한 생성자. */
    public PdfFirstPageRasterizer(long maxRenderPixels, int permits, Duration timeout) {
        this.maxRenderPixels = maxRenderPixels;
        this.permits = permits;
        this.timeout = timeout;
        this.semaphore = new Semaphore(permits);
        // ⚠️ 풀 크기 == 퍼밋 수 여야 한다. 그래야 "퍼밋을 얻은 작업은 즉시 실행될 스레드가 있다" =
        // 큐에 쌓이지 않는다. 큐에 쌓인 작업이 시작 전에 취소되면 finally 의 release 가 실행되지 않아
        // 퍼밋이 샌다. 퍼밋은 작업 스레드가 <b>실제로 끝날 때</b> 반납한다 — 타임아웃으로 호출부가 떠난
        // 뒤에도 작업이 돌고 있다면 그 자원은 아직 점유 중인 게 맞다.
        this.executor = Executors.newFixedThreadPool(permits, daemonThreadFactory());
    }

    /**
     * PDF 첫 페이지를 PNG 로 래스터화한다. 문서는 <b>한 번만</b> 열고, 그 안에서 페이지 수·캔버스 픽셀
     * 상한을 확인한 뒤 렌더까지 마친다.
     *
     * <p>이 메서드는 예외를 던지지 않는다 — 모든 실패를 {@link Status} 로 돌려주므로 호출부(fail-open
     * 계약을 가진 대조 게이트)가 흐름을 스스로 결정한다.
     */
    public Result renderFirstPageAsPng(byte[] pdfBytes) {
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(ACQUIRE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.of(Status.BUSY);
        }
        if (!acquired) {
            log.warn("PDF 래스터화 동시 상한 포화 — 렌더 생략. permits={}", permits);
            return Result.of(Status.BUSY);
        }

        Future<byte[]> future = executor.submit(() -> {
            try {
                return renderInternal(pdfBytes);
            } finally {
                semaphore.release();
            }
        });
        try {
            return Result.ok(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            // 인터럽트에 즉시 반응하지 않을 수 있으나, 퍼밋은 작업이 실제로 끝날 때 반납되므로
            // "취소했지만 아직 도는 렌더"가 새 렌더를 시작시키지는 못한다.
            future.cancel(true);
            log.warn("PDF 래스터화 시간 상한 초과 — 렌더 중단. timeout={}", timeout);
            return Result.of(Status.TIMED_OUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return Result.of(Status.TIMED_OUT);
        } catch (ExecutionException e) {
            // 별도 스레드 실행 덕분에 OutOfMemoryError 같은 Error 도 여기로 감싸져 들어온다.
            log.warn("PDF 래스터화 실패 — 렌더 생략. cause={}",
                    e.getCause() == null ? "UNKNOWN" : e.getCause().getClass().getSimpleName());
            return Result.of(Status.UNRENDERABLE);
        }
    }

    /**
     * 실제 렌더 — 문서를 한 번 열어 ①페이지 수 ②캔버스 픽셀 상한을 확인하고 첫 페이지만 그린다.
     *
     * <p>스트림 캐시를 <b>임시 파일</b>로 강제한다({@link IOUtils#createTempFileOnlyStreamCache}) —
     * PDFBox 3 기본값은 메모리 캐시라 압축 스트림이 그대로 힙에 올라온다.
     */
    private byte[] renderInternal(byte[] pdfBytes) throws IOException {
        try (RandomAccessReadBuffer source = new RandomAccessReadBuffer(pdfBytes);
             PDDocument document = Loader.loadPDF(source, IOUtils.createTempFileOnlyStreamCache())) {
            if (document.getNumberOfPages() < 1) {
                throw new IOException("PDF에 페이지가 없습니다.");
            }
            // renderImage 는 CropBox(= clipToMediaBox 적용) 기준이라 캔버스 상한으로 유효하다.
            // 다만 이건 "캔버스"일 뿐 "콘텐츠"는 못 막는다 — 임베디드 이미지 폭탄 방어는 서브샘플링이 한다.
            PDRectangle box = document.getPage(0).getCropBox();
            long width = (long) Math.ceil(box.getWidth() / POINTS_PER_INCH * RENDER_DPI);
            long height = (long) Math.ceil(box.getHeight() / POINTS_PER_INCH * RENDER_DPI);
            if (width * height > maxRenderPixels) {
                throw new IOException("렌더 픽셀 상한 초과");
            }

            PDFRenderer renderer = new PDFRenderer(document);
            // 임베디드 이미지를 목표 해상도에 맞춰 서브샘플 디코드한다(전량 디코드 방지).
            renderer.setSubsamplingAllowed(true);
            BufferedImage image = renderer.renderImageWithDPI(0, RENDER_DPI, ImageType.GRAY);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", out)) {
                throw new IOException("PNG 인코더를 찾지 못했습니다.");
            }
            return out.toByteArray();
        }
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "pdf-rasterizer-" + counter.incrementAndGet());
            // 데몬 — 병적인 문서 렌더가 끝나지 않아도 애플리케이션 종료를 막지 않는다.
            thread.setDaemon(true);
            return thread;
        };
    }
}
