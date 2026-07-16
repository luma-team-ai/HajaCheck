package com.hajacheck.core.media.support;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * EXIF 촬영시각·GPS 좌표 추출(PRD FR-2 "메타데이터 추출"). PNG·스크린샷 등 EXIF가 없는 이미지는
 * 흔한 정상 케이스이므로 예외를 던지지 않고 빈 값(모두 null)을 반환한다.
 */
public final class ExifGpsExtractor {

    private ExifGpsExtractor() {
    }

    public static ExifData extract(byte[] originalBytes) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(originalBytes));
            return new ExifData(extractCapturedAt(metadata), extractGpsLat(metadata), extractGpsLng(metadata));
        } catch (ImageProcessingException | IOException e) {
            return ExifData.EMPTY;
        }
    }

    private static LocalDateTime extractCapturedAt(Metadata metadata) {
        ExifSubIFDDirectory exifDir = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        if (exifDir == null) {
            return null;
        }
        Date dateOriginal = exifDir.getDateOriginal();
        return dateOriginal == null ? null
                : LocalDateTime.ofInstant(dateOriginal.toInstant(), ZoneId.systemDefault());
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
