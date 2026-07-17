package com.hajacheck.core.media.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
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
}
