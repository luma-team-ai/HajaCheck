package com.hajacheck.core.media.support;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.web.multipart.MultipartFile;

/**
 * 디컴프레션 폭탄(픽셀 폭탄) 방어 — 파일 용량이 작아도 헤더에 거대한 가로×세로를 선언하면 실제
 * 디코딩 시 픽셀 버퍼가 수 GB에 달해 CPU/메모리를 고갈시킬 수 있다(PR머신 2차 검수 P1, #557/HAJA-324).
 * {@link ImageReader}로 헤더(가로×세로)만 먼저 읽어(전체 디코딩 없음) 상한을 넘으면 거부한다.
 *
 * <p>이 검사 자체는 {@link ImageThumbnailGenerator}(#517 이전부터의 방어)가 먼저 갖고 있던 로직이다.
 * 그쪽은 검사 통과 후 <b>같은 ImageReader 인스턴스</b>로 이어서 서브샘플링 디코딩을 하고(스트림을
 * 두 번 열 수 없는 단발성 InputStream API), 픽셀폭탄 발견 시 이미 {@code FILE_INVALID_TYPE}로 응답하는
 * 계약이 자체 테스트({@code ImageThumbnailGeneratorTest}) 로 고정돼 있어, 이번 P1 픽스에서 그 내부를
 * 이 클래스로 강제 이관하지 않았다(동작 변경 회귀 위험 — 리뷰어도 "과하면 인라인 허용"을 명시).
 * 대신 <b>새 소비처(OCR 프록시)가 재사용할 수 있는 자기완결형(open→check→close) 버전</b>을 여기
 * 별도로 둔다 — MultipartFile을 받아 스트림을 직접 열고 검사만 하고 닫는다(디코딩 결과를 돌려주지
 * 않는 호출부, 즉 픽셀 자체가 필요 없는 OCR 같은 소비처에 적합).
 */
public final class ImageDimensionValidator {

    private ImageDimensionValidator() {
    }

    /**
     * {@code file}의 헤더만 읽어 픽셀 수(width×height)가 {@code maxPixels}를 넘으면 거부한다.
     * 실제 픽셀 디코딩은 하지 않는다({@link ImageReader#getWidth}/{@link ImageReader#getHeight}는
     * 포맷 헤더만 파싱). 초과 시 {@link ErrorCode#FILE_TOO_LARGE}("용량이 너무 크다"는 의미를
     * "픽셀 수가 너무 크다"로 자연스럽게 재사용 — 신규 ErrorCode를 만들지 않는다).
     */
    public static void ensureWithinPixelLimit(MultipartFile file, long maxPixels) {
        try (InputStream in = file.getInputStream();
             ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            if (iis == null) {
                throw new BusinessException(ErrorCode.FILE_INVALID_TYPE);
            }
            ImageReader reader = firstReader(iis);
            try {
                reader.setInput(iis);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > maxPixels) {
                    throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
                }
            } catch (BusinessException e) {
                throw e;
            } catch (RuntimeException e) {
                // ImageSignatureValidator는 앞 8바이트(PNG)/3바이트(JPEG)만 대조하므로 "매직바이트는
                // 유효하나 그 뒤(IHDR 등)가 조작/손상된 바이트"가 여기까지 도달할 수 있다
                // (ImageThumbnailGenerator의 동일 위협에 대한 방어와 대칭을 맞춘다).
                throw new BusinessException(ErrorCode.FILE_INVALID_TYPE);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }
    }

    private static ImageReader firstReader(ImageInputStream iis) {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
        if (!readers.hasNext()) {
            throw new BusinessException(ErrorCode.FILE_INVALID_TYPE);
        }
        return readers.next();
    }
}
