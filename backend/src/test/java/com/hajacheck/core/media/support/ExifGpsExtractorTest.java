package com.hajacheck.core.media.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.util.Random;
import java.util.TimeZone;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ExifGpsExtractorTest {

    private final TimeZone originalDefault = TimeZone.getDefault();

    @AfterEach
    void restoreDefaultTimeZone() {
        TimeZone.setDefault(originalDefault);
    }

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

    /**
     * 진짜 JPEG(ImageIO 생성) 맨 앞에 우리가 손으로 만든 EXIF APP1 세그먼트를 끼워 넣는다 — SOI
     * 직후 APP1을 두는 건 실제 카메라·exiftool이 EXIF를 넣는 방식과 동일해서, 나머지 마커·스캔
     * 데이터는 100% 유효한 JPEG로 남는다(조작된 파일이 아니라 "정상 EXIF가 있는 정상 이미지"를
     * 재현하는 게 목적 — extract_유효매직바이트지만내용이잘리거나손상됨_예외없이EMPTY반환처럼
     * 일부러 깨뜨린 케이스와는 반대).
     */
    private static byte[] jpegWithExif(byte[] tiff) throws IOException {
        byte[] realJpeg = realJpegBytes();
        byte[] exifPayload = new byte[6 + tiff.length];
        exifPayload[0] = 'E';
        exifPayload[1] = 'x';
        exifPayload[2] = 'i';
        exifPayload[3] = 'f';
        exifPayload[4] = 0;
        exifPayload[5] = 0;
        System.arraycopy(tiff, 0, exifPayload, 6, tiff.length);

        int segmentLength = exifPayload.length + 2; // 길이 필드 자신(2바이트) 포함, 마커는 미포함
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8); // SOI
        out.write(0xFF);
        out.write(0xE1); // APP1
        out.write((segmentLength >> 8) & 0xFF);
        out.write(segmentLength & 0xFF);
        out.write(exifPayload);
        out.write(realJpeg, 2, realJpeg.length - 2); // 진짜 JPEG의 나머지(SOI 제외)
        return out.toByteArray();
    }

    /** GPSLatitude=37°30'0"N, GPSLongitude=127°0'0"E → 십진 37.5 / 127.0. */
    private static byte[] gpsExifTiff() {
        ByteBuffer buf = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        // TIFF 헤더
        buf.put((byte) 'I').put((byte) 'I');
        buf.putShort((short) 42);
        buf.putInt(8); // IFD0 오프셋

        // IFD0(offset 8) — GPSInfoIFDPointer 엔트리 1개
        buf.putShort((short) 1);
        buf.putShort((short) 0x8825); // GPSInfoIFDPointer
        buf.putShort((short) 4); // LONG
        buf.putInt(1);
        buf.putInt(26); // GPS IFD 오프셋
        buf.putInt(0); // 다음 IFD 없음

        // GPS IFD(offset 26) — 4개 엔트리
        buf.putShort((short) 4);
        buf.putShort((short) 1); // GPSLatitudeRef
        buf.putShort((short) 2); // ASCII
        buf.putInt(2);
        buf.put((byte) 'N').put((byte) 0).put((byte) 0).put((byte) 0);
        buf.putShort((short) 2); // GPSLatitude
        buf.putShort((short) 5); // RATIONAL
        buf.putInt(3);
        buf.putInt(80); // 위도 유리수 3개 오프셋
        buf.putShort((short) 3); // GPSLongitudeRef
        buf.putShort((short) 2);
        buf.putInt(2);
        buf.put((byte) 'E').put((byte) 0).put((byte) 0).put((byte) 0);
        buf.putShort((short) 4); // GPSLongitude
        buf.putShort((short) 5);
        buf.putInt(3);
        buf.putInt(104); // 경도 유리수 3개 오프셋
        buf.putInt(0); // 다음 IFD 없음

        // 값 영역(offset 80) — 위도 37°30'0"
        buf.putInt(37).putInt(1);
        buf.putInt(30).putInt(1);
        buf.putInt(0).putInt(1);
        // 값 영역(offset 104) — 경도 127°0'0"
        buf.putInt(127).putInt(1);
        buf.putInt(0).putInt(1);
        buf.putInt(0).putInt(1);

        return buf.array();
    }

    private static byte[] orientationExifTiff(int orientationValue) {
        ByteBuffer buf = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 'I').put((byte) 'I');
        buf.putShort((short) 42);
        buf.putInt(8);
        buf.putShort((short) 1); // 1개 엔트리
        buf.putShort((short) 0x0112); // Orientation
        buf.putShort((short) 3); // SHORT
        buf.putInt(1);
        buf.putShort((short) orientationValue);
        buf.putShort((short) 0); // 4바이트 값 영역 패딩
        buf.putInt(0); // 다음 IFD 없음
        return buf.array();
    }

    @Test
    void extract_GPS태그있는JPEG_위경도추출() throws IOException {
        byte[] jpeg = jpegWithExif(gpsExifTiff());

        ExifGpsExtractor.ExifData result = ExifGpsExtractor.extract(new ByteArrayInputStream(jpeg));

        assertThat(result.gpsLat()).isEqualByComparingTo("37.500000");
        assertThat(result.gpsLng()).isEqualByComparingTo("127.000000");
    }

    @Test
    void extract_EXIF없는정상PNG_EMPTY반환() throws IOException {
        ExifGpsExtractor.ExifData result = ExifGpsExtractor.extract(new ByteArrayInputStream(realPngBytes()));

        assertThat(result).isEqualTo(ExifGpsExtractor.ExifData.EMPTY);
    }

    @Test
    void extract_Orientation범위밖값_기본값1로대체() throws IOException {
        byte[] jpeg = jpegWithExif(orientationExifTiff(99));

        ExifGpsExtractor.ExifData result = ExifGpsExtractor.extract(new ByteArrayInputStream(jpeg));

        assertThat(result.orientation()).isEqualTo(1);
    }

    /**
     * EXIF DateTimeOriginal은 타임존 정보가 없는 카메라 현지시각 문자열이다. 서버의 기본 타임존이
     * 무엇이든(리뷰 P2: 배포 환경 TZ 설정에 따라 값이 달라지면 안 됨) 원문 숫자 그대로
     * LocalDateTime으로 보존되어야 한다.
     */
    @Test
    void parseCapturedAt_서버기본타임존과무관하게원문시각그대로보존() {
        LocalDateTime expected = LocalDateTime.of(2024, 3, 15, 14, 30, 0);

        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        LocalDateTime parsedInUtc = ExifGpsExtractor.parseCapturedAt("2024:03:15 14:30:00");

        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
        LocalDateTime parsedInLa = ExifGpsExtractor.parseCapturedAt("2024:03:15 14:30:00");

        assertThat(parsedInUtc).isEqualTo(expected);
        assertThat(parsedInLa).isEqualTo(expected);
    }

    @Test
    void parseCapturedAt_null이면null반환() {
        assertThat(ExifGpsExtractor.parseCapturedAt(null)).isNull();
    }

    @Test
    void parseCapturedAt_형식이맞지않으면null반환() {
        assertThat(ExifGpsExtractor.parseCapturedAt("not-a-date")).isNull();
    }

    /**
     * 매직바이트만 유효하고 그 뒤가 잘리거나 조작된 입력(리뷰 P2) — metadata-extractor 가
     * 체크 예외가 아닌 unchecked 예외를 던지더라도 extract()가 이를 전파하지 않고 EMPTY로
     * 흡수해야 한다(그렇지 않으면 조작 파일 하나로 업로드 요청 전체가 raw 500이 된다).
     */
    @Test
    void extract_유효매직바이트지만내용이잘리거나손상됨_예외없이EMPTY반환() {
        byte[] truncatedJpegWithExifHeader = {
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x00, 0x10,
                'E', 'x', 'i', 'f', 0x00, 0x00, 'M', 'M', 0x00
        };

        ExifGpsExtractor.ExifData result =
                ExifGpsExtractor.extract(new ByteArrayInputStream(truncatedJpegWithExifHeader));

        assertThat(result).isEqualTo(ExifGpsExtractor.ExifData.EMPTY);
    }

    @Test
    void extract_무작위가비지바이트_예외없이EMPTY반환() {
        byte[] garbage = new byte[256];
        new Random(42).nextBytes(garbage);

        ExifGpsExtractor.ExifData result = ExifGpsExtractor.extract(new ByteArrayInputStream(garbage));

        assertThat(result).isEqualTo(ExifGpsExtractor.ExifData.EMPTY);
    }

    /**
     * GPS 좌표는 항상 |위도|<=90, |경도|<=180 이다(리뷰 P2). media.gps_lat/gps_lng 컬럼은
     * numeric(9,6)이라 |값|<1000 까지는 물리적으로 저장되지만, 조작된 EXIF가 산출하는 좌표는
     * 실제 위경도 범위를 벗어날 수 있어 별도 가드가 필요하다. 실제 EXIF GPS 바이트를 조작해
     * 범위초과 값을 재현하기보다, 범위 클램프 로직 자체를 직접 단위 테스트한다
     * (parseCapturedAt과 동일한 테스트 전략).
     */
    @Test
    void boundedCoordinate_범위이내값_그대로반환() {
        assertThat(ExifGpsExtractor.boundedCoordinate(37.5, 90))
                .isEqualByComparingTo("37.500000");
        assertThat(ExifGpsExtractor.boundedCoordinate(-179.999999, 180))
                .isEqualByComparingTo("-179.999999");
    }

    @Test
    void boundedCoordinate_경계값_포함() {
        assertThat(ExifGpsExtractor.boundedCoordinate(90.0, 90)).isEqualByComparingTo("90.000000");
        assertThat(ExifGpsExtractor.boundedCoordinate(-180.0, 180)).isEqualByComparingTo("-180.000000");
    }

    @Test
    void boundedCoordinate_범위초과값_null반환() {
        // 위도 91, 경도 1000처럼 numeric(9,6) 컬럼(|값|<1000)에는 담기지만 실제 위경도 범위를
        // 벗어나는 값 — INSERT는 통과할 수 있어도 좌표로서는 무의미하므로 null 처리해야 한다.
        assertThat(ExifGpsExtractor.boundedCoordinate(91.0, 90)).isNull();
        assertThat(ExifGpsExtractor.boundedCoordinate(-91.0, 90)).isNull();
        assertThat(ExifGpsExtractor.boundedCoordinate(1000.0, 180)).isNull();
        assertThat(ExifGpsExtractor.boundedCoordinate(181.0, 180)).isNull();
    }

    @Test
    void boundedCoordinate_NaN이나무한대_null반환() {
        assertThat(ExifGpsExtractor.boundedCoordinate(Double.NaN, 90)).isNull();
        assertThat(ExifGpsExtractor.boundedCoordinate(Double.POSITIVE_INFINITY, 180)).isNull();
    }
}
