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

    /**
     * 서브샘플링 디코딩 도입(리뷰 P2) 후에도 결과가 정확한지 실제 대형 PNG(25MP)로 검증한다.
     * 풀 해상도 디코딩 없이도 비율 유지 축소가 정확히 맞춰지는지 확인.
     */
    @Test
    void generate_대형이미지_서브샘플링디코딩으로도_정확한크기의썸네일반환() throws IOException {
        byte[] png = realPngBytes(5000, 5000);

        byte[] thumbnail = ImageThumbnailGenerator.generate(new ByteArrayInputStream(png), 400);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumbnail));
        assertThat(decoded.getWidth()).isEqualTo(400);
        assertThat(decoded.getHeight()).isEqualTo(400);
    }

    /**
     * 최신 플래그십 폰 카메라는 48MP·108MP·200MP 원본을 흔히 촬영한다(리뷰 P2). 이전 40MP 상한은
     * 이런 정상 사진을 "허용되지 않는 파일 형식"으로 거부했다 — 8000×6000(48MP)이 거부되지 않고
     * 정확한 비율로 축소되는지 고정한다(MAX_PIXELS를 250MP로 올린 결정의 회귀 테스트).
     */
    @Test
    void generate_48MP정상사진_거부되지않고정확한크기의썸네일반환() throws IOException {
        byte[] png = realPngBytes(8000, 6000);

        byte[] thumbnail = ImageThumbnailGenerator.generate(new ByteArrayInputStream(png), 400);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumbnail));
        assertThat(decoded.getWidth()).isEqualTo(400);
        assertThat(decoded.getHeight()).isEqualTo(300);
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

    /**
     * ImageSignatureValidator는 앞 8바이트(PNG 시그니처)만 대조하므로, "시그니처+헤더(IHDR)는 유효하나
     * 실제 픽셀 데이터(IDAT)가 조작/손상된" 입력이 이 검증을 통과해 디코딩 단계까지 도달할 수 있다
     * (리뷰 P2). getWidth/getHeight(헤더 단계)는 통과하지만 reader.read(픽셀 디코딩 단계)에서 실패하는
     * 입력이므로, 헤더만 조작한 픽셀폭탄 테스트와 달리 실제 디코딩 경로의 예외 흡수를 검증한다. JDK
     * PNG 리더가 이런 입력에 체크 예외(IOException)를 던지는지 unchecked 예외를 던지는지는 버전마다
     * 다를 수 있으므로, 결과가 "어떤 BusinessException(400)"인지만 고정한다 — raw 500이 아니면 된다
     * (ExifGpsExtractorTest.extract_유효매직바이트지만내용이잘리거나손상됨_예외없이EMPTY반환과 대응).
     */
    @Test
    void generate_시그니처와헤더는유효하나픽셀데이터가손상됨_예외전파없이BusinessException으로매핑() {
        byte[] corruptedPixelData = craftPngWithCorruptedIdat(100, 100);

        assertThatThrownBy(() -> ImageThumbnailGenerator.generate(new ByteArrayInputStream(corruptedPixelData), 400))
                .isInstanceOf(BusinessException.class);
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

    private static byte[] craftPngWithCorruptedIdat(int width, int height) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});

            ByteArrayOutputStream ihdrData = new ByteArrayOutputStream();
            ihdrData.write(intToBytes(width));
            ihdrData.write(intToBytes(height));
            ihdrData.write(new byte[] {8, 2, 0, 0, 0});
            writeChunk(out, "IHDR", ihdrData.toByteArray());

            // 유효한 zlib/deflate 스트림이 아닌 임의 바이트 — 헤더 파싱은 통과하지만 픽셀 압축해제에서 실패해야 한다.
            byte[] garbageIdat = new byte[64];
            new java.util.Random(7).nextBytes(garbageIdat);
            writeChunk(out, "IDAT", garbageIdat);
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
