package com.hajacheck.core.media.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.support.PngTestFixtures;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * ImageDimensionValidator 단위테스트(PR머신 2차 검수 P1, #557/HAJA-324) — 디컴프레션 폭탄(픽셀 폭탄)
 * 방어를 ImageThumbnailGeneratorTest와 동일한 헤더 조립 기법(PngTestFixtures)으로 고정한다.
 */
class ImageDimensionValidatorTest {

    private static byte[] realPngBytes(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    @Test
    void ensureWithinPixelLimit_상한이내_통과() throws IOException {
        MultipartFile file = new MockMultipartFile("f", "a.png", "image/png", realPngBytes(100, 100));

        assertThatCode(() -> ImageDimensionValidator.ensureWithinPixelLimit(file, 50_000_000L))
                .doesNotThrowAnyException();
    }

    /**
     * 바이트 크기는 매우 작지만(수십 바이트) IHDR에 60000×60000(36억 픽셀)을 선언한 PNG — 실제 픽셀
     * 디코딩 없이 헤더 단계에서 거부되는지 검증한다(진짜 디코딩하면 이 테스트가 OOM 나야 정상).
     */
    @Test
    void ensureWithinPixelLimit_거대한선언크기_픽셀디코딩없이FILE_TOO_LARGE() {
        byte[] bombPng = PngTestFixtures.craftPngWithDeclaredDimensions(60_000, 60_000);
        MultipartFile file = new MockMultipartFile("f", "bomb.png", "image/png", bombPng);

        assertThatThrownBy(() -> ImageDimensionValidator.ensureWithinPixelLimit(file, 50_000_000L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_TOO_LARGE));
    }

    @Test
    void ensureWithinPixelLimit_경계값_상한보다1픽셀많으면거부() {
        // 7072*7072 = 50,013,184 > 50,000,000(OCR 상한) — 딱 한 축을 넘겼을 때도 걸리는지 확인.
        byte[] bombPng = PngTestFixtures.craftPngWithDeclaredDimensions(7072, 7072);
        MultipartFile file = new MockMultipartFile("f", "bomb.png", "image/png", bombPng);

        assertThatThrownBy(() -> ImageDimensionValidator.ensureWithinPixelLimit(file, 50_000_000L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_TOO_LARGE));
    }

    @Test
    void ensureWithinPixelLimit_디코딩불가능한바이트_FILE_INVALID_TYPE() {
        MultipartFile file = new MockMultipartFile("f", "a.png", "image/png", "not an image".getBytes());

        assertThatThrownBy(() -> ImageDimensionValidator.ensureWithinPixelLimit(file, 50_000_000L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_INVALID_TYPE));
    }
}
