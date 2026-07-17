package com.hajacheck.core.media.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.Random;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ExifGpsExtractorTest {

    private final TimeZone originalDefault = TimeZone.getDefault();

    @AfterEach
    void restoreDefaultTimeZone() {
        TimeZone.setDefault(originalDefault);
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
