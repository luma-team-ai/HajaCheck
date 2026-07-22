package com.hajacheck.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * 픽셀폭탄(디컴프레션 폭탄) 테스트용 PNG 조립 헬퍼 — IHDR에 임의의 가로×세로를 선언하고 IDAT은 비워둔
 * "헤더만 유효한" PNG를 만든다(ImageThumbnailGeneratorTest.craftPngWithDeclaredDimensions와 동일 목적,
 * BusinessLicenseOcrServiceTest/ImageDimensionValidatorTest 양쪽에서 재사용하기 위해 공용 test-support로
 * 분리). ImageReader.getWidth/getHeight는 IHDR만 파싱하므로 실제 픽셀 디코딩 없이 헤더 단계 거부를
 * 검증할 수 있다 — 진짜로 선언한 픽셀 수만큼 디코딩하면 이 테스트 자체가 OOM 나야 정상이기 때문.
 */
public final class PngTestFixtures {

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private PngTestFixtures() {
    }

    public static byte[] craftPngWithDeclaredDimensions(int width, int height) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(PNG_SIGNATURE);

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
