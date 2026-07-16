package com.hajacheck.core.media.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageThumbnailGeneratorTest {

    private static byte[] realPngBytes(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    @Test
    void generate_정상이미지_비율유지축소된JPEG반환() throws IOException {
        byte[] png = realPngBytes(200, 100);

        byte[] thumbnail = ImageThumbnailGenerator.generate(new ByteArrayInputStream(png), 50);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumbnail));
        assertThat(decoded.getWidth()).isEqualTo(50);
        assertThat(decoded.getHeight()).isEqualTo(25);
    }

    @Test
    void generate_원본이맥스디멘션보다작으면확대하지않음() throws IOException {
        byte[] png = realPngBytes(30, 20);

        byte[] thumbnail = ImageThumbnailGenerator.generate(new ByteArrayInputStream(png), 400);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumbnail));
        assertThat(decoded.getWidth()).isEqualTo(30);
        assertThat(decoded.getHeight()).isEqualTo(20);
    }

    @Test
    void generate_디코딩불가능한바이트_FILE_INVALID_TYPE() {
        byte[] garbage = "not an image".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ImageThumbnailGenerator.generate(new ByteArrayInputStream(garbage), 400))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_INVALID_TYPE));
    }

    /**
     * 디컴프레션 밤(픽셀 폭탄) 방어 — IHDR에 40MP 상한을 넘는 가로×세로(60000×60000 = 36억 픽셀)를 선언한
     * PNG를 수동 조립한다. IDAT(실제 픽셀 데이터)는 비워둔다 — ImageReader.getWidth/getHeight는 IHDR만
     * 파싱하므로 실제 픽셀 디코딩(reader.read(0))을 시도하지 않고도 헤더 단계에서 거부되는지 검증한다.
     * (진짜로 36억 픽셀을 디코딩하면 이 테스트 자체가 OOM 이 나야 정상이므로, 헤더 단계 거부가 핵심.)
     */
    @Test
    void generate_거대한선언크기의PNG_픽셀디코딩없이FILE_INVALID_TYPE() {
        byte[] bombPng = craftPngWithDeclaredDimensions(60_000, 60_000);

        assertThatThrownBy(() -> ImageThumbnailGenerator.generate(new ByteArrayInputStream(bombPng), 400))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_INVALID_TYPE));
    }

    private static byte[] craftPngWithDeclaredDimensions(int width, int height) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}); // PNG 시그니처

            ByteArrayOutputStream ihdrData = new ByteArrayOutputStream();
            ihdrData.write(intToBytes(width));
            ihdrData.write(intToBytes(height));
            ihdrData.write(new byte[] {8, 2, 0, 0, 0}); // bit depth 8, color type 2(RGB), 압축/필터/인터레이스 0
            writeChunk(out, "IHDR", ihdrData.toByteArray());

            // IDAT은 실제 픽셀 디코딩 단계까지 가면 실패해도 무방 — 헤더 단계에서 이미 거부되어야 하므로 빈 데이터.
            writeChunk(out, "IDAT", new byte[0]);
            writeChunk(out, "IEND", new byte[0]);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        out.write(intToBytes(data.length));
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.write(typeBytes);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.write(intToBytes((int) crc.getValue()));
    }

    private static byte[] intToBytes(int value) {
        return new byte[] {
                (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
        };
    }
}
