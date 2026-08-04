package com.hajacheck.support;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

/**
 * 업로드 파일 테스트 픽스처 모음.
 *
 * <ul>
 *   <li>{@link #craftPngWithDeclaredDimensions} — 픽셀폭탄(디컴프레션 폭탄) 테스트용. IHDR에 임의의
 *       가로×세로를 선언하고 IDAT은 비워둔 "헤더만 유효한" PNG. ImageReader.getWidth/getHeight는
 *       IHDR만 파싱하므로 실제 픽셀 디코딩 없이 헤더 단계 거부를 검증할 수 있다(진짜로 선언한 픽셀
 *       수만큼 디코딩하면 이 테스트 자체가 OOM 나야 정상이기 때문).</li>
 *   <li>{@link #realPng}/{@link #realJpeg}/{@link #realPdf} — <b>매직바이트가 유효한 진짜 파일</b>.
 *       {@code LocalFileStorage}가 저장 시 실제 시그니처를 검증하게 되면서(#1488) 더미 문자열
 *       바이트(예: {@code "PNGDATA".getBytes()})는 저장 자체가 거부된다 — "정상 업로드" 시나리오는
 *       반드시 이 헬퍼를 쓸 것. 거부 동작을 검증하는 <b>음성 테스트</b>는 계속 더미 바이트를 쓴다.</li>
 * </ul>
 */
public final class PngTestFixtures {

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private PngTestFixtures() {
    }

    /** 4×4 진짜 PNG(매직바이트·IHDR·IDAT 모두 유효). */
    public static byte[] realPng() {
        return encode("png");
    }

    /** 4×4 진짜 JPEG. */
    public static byte[] realJpeg() {
        return encode("jpg");
    }

    /** A4 1페이지 빈 PDF(%PDF- 헤더 포함, PDFBox로 열 수 있는 유효 문서). */
    public static byte[] realPdf() {
        return realPdf(1, PDRectangle.A4);
    }

    /** 페이지 수·페이지 크기를 지정한 유효 PDF. */
    public static byte[] realPdf(int pageCount, PDRectangle pageSize) {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                document.addPage(new PDPage(pageSize));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] encode(String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
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
