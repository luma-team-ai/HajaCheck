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
}
