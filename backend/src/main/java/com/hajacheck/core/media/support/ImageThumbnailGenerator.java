package com.hajacheck.core.media.support;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * 업로드 원본을 비율 유지 축소 후 JPEG로 재인코딩한 썸네일 바이트를 생성한다(PRD FR-2
 * "업로드 완료 시 썸네일 생성"). 표준 javax.imageio 만 사용 — 별도 라이브러리 의존성 불필요.
 * 원본이 무엇이든(JPEG/PNG) 썸네일은 항상 JPEG 로 통일해, 서빙 시 Content-Type 분기가 필요 없게 한다.
 *
 * <p>InputStream을 직접 받는다(byte[] 전체를 앱 힙에 먼저 올리지 않음) — ImageIO가 내부적으로
 * ImageInputStream(필요 시 디스크 캐시 백엔드)으로 버퍼링해 대용량 파일의 메모리 압박을 줄인다.
 */
public final class ImageThumbnailGenerator {

    // 디컴프레션 밤(픽셀 폭탄) 방어 — 파일 용량이 작아도 헤더에 거대한 가로×세로를 선언하면
    // 전체 디코딩 시 픽셀 버퍼가 수 GB에 달해 OOM을 유발할 수 있다. 실제 픽셀을 읽기 전에
    // ImageReader로 크기(헤더)만 먼저 확인해 상한을 넘으면 디코딩 자체를 하지 않고 거부한다.
    private static final long MAX_PIXELS = 40_000_000L; // 40MP — 폰 카메라 사진 대부분을 커버하는 여유 상한

    private ImageThumbnailGenerator() {
    }

    public static byte[] generate(InputStream original, int maxDimension) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(original)) {
            if (iis == null) {
                throw new BusinessException(ErrorCode.FILE_INVALID_TYPE);
            }
            ImageReader reader = firstReader(iis);
            try {
                reader.setInput(iis);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * height;
                if (pixels > MAX_PIXELS) {
                    throw new BusinessException(ErrorCode.FILE_INVALID_TYPE);
                }
                BufferedImage image = reader.read(0, subsamplingParam(reader, width, height, maxDimension));
                return resizeToJpeg(image, maxDimension);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }
    }

    // 썸네일은 어차피 maxDimension 이하로 축소되므로, 디코딩 단계부터 서브샘플링해 원본 풀 해상도
    // 버퍼(40MP·TYPE_INT_RGB 기준 약 160MB)를 힙에 올리지 않는다(리뷰 P2 — 요청당 최대 10개 파일을
    // 순차 처리하고 동시 요청도 가능해 힙 압박이 누적될 수 있음). 서브샘플링은 스트라이드 단위라
    // 정확한 목표 크기를 만들지 못하므로, 이어지는 resizeToJpeg 의 비율 유지 축소가 정확히 맞춘다.
    private static ImageReadParam subsamplingParam(ImageReader reader, int width, int height, int maxDimension) {
        ImageReadParam param = reader.getDefaultReadParam();
        int longerSide = Math.max(width, height);
        int subsampling = Math.max(1, longerSide / Math.max(1, maxDimension));
        param.setSourceSubsampling(subsampling, subsampling, 0, 0);
        return param;
    }

    private static ImageReader firstReader(ImageInputStream iis) {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
        if (!readers.hasNext()) {
            throw new BusinessException(ErrorCode.FILE_INVALID_TYPE);
        }
        return readers.next();
    }

    private static byte[] resizeToJpeg(BufferedImage original, int maxDimension) throws IOException {
        int width = original.getWidth();
        int height = original.getHeight();
        double scale = Math.min(1.0, (double) maxDimension / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));

        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        try {
            // PNG 투명 배경을 흰색으로 채워 재인코딩(JPEG는 알파 채널 미지원 — 채우지 않으면 검게 렌더링됨).
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, targetWidth, targetHeight);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(original, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(resized, "jpg", out);
        return out.toByteArray();
    }
}
