package com.hajacheck.global.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * naive(TZ 정보 없는) {@link LocalDateTime} 엔티티 필드 ↔ {@code timestamp with time zone} 컬럼 사이의
 * 변환을 서버 배포 환경의 JVM 기본 TZ 설정과 완전히 무관하게 "KST(Asia/Seoul)"로 고정한다.
 *
 * <p>엔티티 필드 타입이 {@code LocalDateTime}인 채로 timestamptz 컬럼에 그대로 매핑되면, JDBC 드라이버가
 * write/read 시 JVM 기본 TZ로 절대시각을 계산해 저장 계층에서 TZ 의존이 재유입된다(운영은
 * {@code -Duser.timezone=Asia/Seoul}로 고정돼 있지만, 테스트/로컬 JVM은 그 보장이 없다). 이 컨버터는
 * 값을 "KST"로 명시 고정해 {@link OffsetDateTime}(오프셋을 값 자체에 담음 — 드라이버가 JVM 기본값을
 * 참조할 필요가 없음)으로 변환한 뒤 저장하고, 조회 시 같은 고정 존으로 되돌려 원문 벽시계 숫자를 그대로
 * 복원한다.
 *
 * <p>원래 {@code Media.capturedAt}(EXIF 카메라 현지시각) 전용 {@code CapturedAtConverter}였으나(#788),
 * {@code Inspection.performedAt}(#1667, EXIF captured_at 또는 SchedulingConfig의 KST 고정 Clock 기준
 * 업로드 시각)도 동일한 "KST 고정 해석" 전제를 공유해 공용 컨버터로 일반화했다 — 두 필드 모두 최종적으로
 * 같은 기준(KST)의 값을 저장/비교해야 원자적 UPDATE(InspectionRepository.applyPerformedAtIfEarlier)의
 * 파라미터 바인딩과 DB에 이미 저장된 값의 대소 비교가 어긋나지 않는다.
 */
@Converter
public class KstFixedLocalDateTimeConverter implements AttributeConverter<LocalDateTime, OffsetDateTime> {

    private static final ZoneId FIXED_ZONE = ZoneId.of("Asia/Seoul");

    @Override
    public OffsetDateTime convertToDatabaseColumn(LocalDateTime attribute) {
        return attribute == null ? null : attribute.atZone(FIXED_ZONE).toOffsetDateTime();
    }

    @Override
    public LocalDateTime convertToEntityAttribute(OffsetDateTime dbData) {
        return dbData == null ? null : dbData.atZoneSameInstant(FIXED_ZONE).toLocalDateTime();
    }
}
