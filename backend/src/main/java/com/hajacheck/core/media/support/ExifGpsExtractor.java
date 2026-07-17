package com.hajacheck.core.media.support;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * EXIF 촬영시각·GPS 좌표 추출(PRD FR-2 "메타데이터 추출"). PNG·스크린샷 등 EXIF가 없는 이미지는
 * 흔한 정상 케이스이므로 예외를 던지지 않고 빈 값(모두 null)을 반환한다.
 *
 * <p>InputStream을 직접 받는다 — byte[] 전체를 앱 힙에 먼저 올리지 않고 metadata-extractor 가
 * 내부적으로 필요한 만큼만 읽도록 위임한다.
 */
public final class ExifGpsExtractor {

    // EXIF DateTimeOriginal 은 타임존 정보가 없는 카메라 현지시각 문자열("yyyy:MM:dd HH:mm:ss")이다.
    // metadata-extractor 의 getDateOriginal()(Date 반환)을 거치면 내부적으로 어떤 TimeZone 을
    // 가정해 Instant 로 변환하는지가 라이브러리 버전에 암묵적으로 묶이고, 이를 다시 LocalDateTime 으로
    // 되돌릴 때 서버의 ZoneId.systemDefault() 를 쓰면 두 변환에 쓰인 존이 어긋나는 순간 저장값이
    // 배포 환경(서버 TZ 설정)에 따라 달라진다(리뷰 P2). 원문 문자열을 직접 파싱해 Date/Instant/
    // ZoneId 왕복 자체를 없애면 이 문제가 구조적으로 발생할 수 없다.
    private static final DateTimeFormatter EXIF_DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");

    private ExifGpsExtractor() {
    }

    public static ExifData extract(InputStream original) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(original);
            return new ExifData(extractCapturedAt(metadata), extractGpsLat(metadata), extractGpsLng(metadata));
        } catch (ImageProcessingException | IOException | RuntimeException e) {
            // 매직바이트 8바이트만 통과하면 그 뒤는 임의 바이트여도 이 경로에 도달한다(리뷰 P2).
            // metadata-extractor 는 조작·손상된 세그먼트에서 체크 예외(ImageProcessingException/
            // IOException) 외에도 NumberFormatException 등 unchecked 예외를 던질 수 있는데, 이
            // 메서드의 계약은 "EXIF 파싱 실패는 항상 정상 케이스로 흡수"이므로(클래스 상단 문서 참고)
            // RuntimeException 도 동일하게 EMPTY 로 흡수해야 조작된 파일 하나로 500이 나는 것을 막는다.
            return ExifData.EMPTY;
        }
    }

    private static LocalDateTime extractCapturedAt(Metadata metadata) {
        ExifSubIFDDirectory exifDir = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        if (exifDir == null) {
            return null;
        }
        return parseCapturedAt(exifDir.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL));
    }

    // 서버 TZ와 무관하게 EXIF 원문 로컬시각 숫자를 그대로 보존하는지가 검증 대상이므로 문자열 파싱만
    // 분리해 단위 테스트한다(실제 EXIF 바이트를 가진 이미지 없이도 회귀를 잡을 수 있게).
    static LocalDateTime parseCapturedAt(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim(), EXIF_DATETIME_FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static GeoLocation geoLocation(Metadata metadata) {
        GpsDirectory gpsDir = metadata.getFirstDirectoryOfType(GpsDirectory.class);
        if (gpsDir == null) {
            return null;
        }
        GeoLocation location = gpsDir.getGeoLocation();
        return (location == null || location.isZero()) ? null : location;
    }

    private static BigDecimal extractGpsLat(Metadata metadata) {
        GeoLocation location = geoLocation(metadata);
        return location == null ? null : BigDecimal.valueOf(location.getLatitude()).setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal extractGpsLng(Metadata metadata) {
        GeoLocation location = geoLocation(metadata);
        return location == null ? null : BigDecimal.valueOf(location.getLongitude()).setScale(6, RoundingMode.HALF_UP);
    }

    public record ExifData(LocalDateTime capturedAt, BigDecimal gpsLat, BigDecimal gpsLng) {
        public static final ExifData EMPTY = new ExifData(null, null, null);
    }
}
