package com.hajacheck.core.media.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class ImageSignatureValidatorTest {

    private static byte[] realPngBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static byte[] realJpegBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }

    @Test
    void validate_실제PNG_통과() throws IOException {
        MultipartFile file = new MockMultipartFile("files", "a.png", "image/png", realPngBytes());

        assertThatCode(() -> ImageSignatureValidator.validate(file)).doesNotThrowAnyException();
    }

    @Test
    void validate_실제JPEG_통과() throws IOException {
        MultipartFile file = new MockMultipartFile("files", "a.jpg", "image/jpeg", realJpegBytes());

        assertThatCode(() -> ImageSignatureValidator.validate(file)).doesNotThrowAnyException();
    }

    @Test
    void validate_PNG바이트인데JPEG로선언_FILE_INVALID_TYPE() throws IOException {
        // 확장자·Content-Type 위조 방어 — 실제 바이트 시그니처가 선언된 타입과 달라야 한다.
        MultipartFile file = new MockMultipartFile("files", "a.jpg", "image/jpeg", realPngBytes());

        assertThatThrownBy(() -> ImageSignatureValidator.validate(file))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_INVALID_TYPE));
    }

    @Test
    void validate_허용되지않는contentType_FILE_INVALID_TYPE() {
        MultipartFile file = new MockMultipartFile("files", "a.txt", "text/plain", "hello world".getBytes());

        assertThatThrownBy(() -> ImageSignatureValidator.validate(file))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_INVALID_TYPE));
    }

    @Test
    void validate_시그니처보다짧은바이트_FILE_INVALID_TYPE() {
        MultipartFile file = new MockMultipartFile("files", "a.png", "image/png", new byte[] {1, 2, 3});

        assertThatThrownBy(() -> ImageSignatureValidator.validate(file))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_INVALID_TYPE));
    }
}
