package com.hajacheck.core.media.support;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * {@link com.hajacheck.core.media.entity.Media#getCapturedAt()} (naive 카메라 현지시각) ↔
 * {@code captured_at} 컬럼(timestamp with time zone) 사이의 변환을 서버 배포 환경의 TZ 설정과
 * 완전히 무관하게 고정한다(리뷰 P2).
 *
 * <p>{@link ExifGpsExtractor}는 EXIF 원문 로컬시각 문자열을 Date/Instant 왕복 없이 직접
 * LocalDateTime 으로 파싱해 서버 TZ 의존을 없앴다. 그런데 엔티티 필드 타입이 LocalDateTime 인 채로
 * timestamptz 컬럼에 그대로 매핑되면, JDBC 드라이버가 write/read 시 JVM 기본 TZ로 절대시각을
 * 계산해 저장 계층에서 TZ 의존이 재유입된다. 이 컨버터는 카메라 현지시각을 "KST(Asia/Seoul)"로
 * 명시 고정해 OffsetDateTime(오프셋을 값 자체에 담음 — 드라이버가 JVM 기본값을 참조할 필요가 없음)으로
 * 변환한 뒤 저장하고, 조회 시 같은 고정 존으로 되돌려 원문 벽시계 숫자를 그대로 복원한다.
 */
@Converter
public class CapturedAtConverter implements AttributeConverter<LocalDateTime, OffsetDateTime> {

    private static final ZoneId ASSUMED_CAPTURE_ZONE = ZoneId.of("Asia/Seoul");

    @Override
    public OffsetDateTime convertToDatabaseColumn(LocalDateTime attribute) {
        return attribute == null ? null : attribute.atZone(ASSUMED_CAPTURE_ZONE).toOffsetDateTime();
    }

    @Override
    public LocalDateTime convertToEntityAttribute(OffsetDateTime dbData) {
        return dbData == null ? null : dbData.atZoneSameInstant(ASSUMED_CAPTURE_ZONE).toLocalDateTime();
    }
}
