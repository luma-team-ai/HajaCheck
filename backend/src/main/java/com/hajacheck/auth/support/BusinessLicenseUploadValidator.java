package com.hajacheck.auth.support;

import com.hajacheck.core.media.support.DetectedFileType;
import com.hajacheck.core.media.support.ImageDimensionValidator;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.util.Optional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 기업 가입 업로드(사업자등록증) 저장 전 검증(#1469 2차 리뷰) — <b>대조 게이트보다 먼저</b> 실행한다.
 *
 * <p><b>왜 대조보다 먼저인가</b>: 대조는 의도적으로 <b>선언 {@code Content-Type} 을 보지 않고 실제
 * 바이트로만</b> 분기한다. 반면 저장({@code LocalFileStorage})은 선언 타입 기준으로 확장자를 정하고
 * 시그니처를 대조한다. 이 둘이 어긋난 채로 두면 다음 루프가 성립했다:
 * <ol>
 *   <li>공격자가 <b>자기 회사의 진짜 등록증 PNG</b>를 {@code application/pdf} 로 선언해 제출</li>
 *   <li>대조는 실제 PNG 를 읽어 {@code MATCH} → 통과 (전역 캡만 1 소모)</li>
 *   <li>저장은 선언(pdf) ↔ 실제(png) 불일치로 {@code FILE_INVALID_TYPE} 400 → <b>회사 행이 안 생김</b></li>
 *   <li>→ 1번으로 무한 반복. 전역 캡만 계속 태운다.</li>
 * </ol>
 * 선언↔실제 일치를 <b>대조 앞</b>에서 강제하면 "대조는 통과했는데 저장에서 400" 이라는 어긋남 자체가
 * 사라진다(AI 호출 비용도 아낀다).
 *
 * <p>추가로 <b>디코딩 가능성</b>까지 본다. {@code FF D8 FF} 3바이트 뒤가 쓰레기인 파일은 시그니처
 * 검증만으로는 통과해 "열리지 않는 이미지"가 증빙으로 남는다 — 나중에 사람이 등록증을 열람할 때
 * 신뢰할 수 없는 상태가 된다. 이미지는 헤더를 파싱해 크기를 읽을 수 있어야만 저장을 허용한다.
 *
 * <p>파일 부재·용량 초과는 여기서 판정하지 않는다 — 그건 {@code FileStorageService.store} 의 기존
 * 계약({@code FILE_REQUIRED}/{@code FILE_TOO_LARGE})이고, 두 곳에서 같은 판정을 하면 어긋날 여지만 는다.
 */
public final class BusinessLicenseUploadValidator {

    /**
     * 디코딩 가능성 확인용 픽셀 상한 — 저장 자체를 거부하는 기준이므로 대조용 상한(50MP)과 같은 값을 쓴다.
     * 이 검사의 목적은 "픽셀 폭탄 차단"이 아니라 "헤더를 읽을 수 있는 진짜 이미지인가"이지만,
     * 상한을 함께 적용해도 정상 등록증에는 아무 영향이 없다.
     */
    private static final long MAX_PIXELS = 50_000_000L;

    private BusinessLicenseUploadValidator() {
    }

    /**
     * @throws BusinessException 선언 타입과 실제 바이트가 다르거나, 아는 형식이 아니거나,
     *                           이미지인데 디코딩할 수 없으면 {@code FILE_INVALID_TYPE}
     */
    public static void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return; // 파일 부재 판정은 저장 계층의 계약(FILE_REQUIRED)에 맡긴다.
        }
        Optional<DetectedFileType> detected = DetectedFileType.detect(file);
        if (detected.isEmpty() || !detected.get().mimeType().equals(file.getContentType())) {
            // 선언과 실제가 다르면 즉시 거부 — 위 클래스 주석의 "대조 통과 → 저장 400" 루프 차단.
            throw new BusinessException(ErrorCode.FILE_INVALID_TYPE);
        }
        if (detected.get() != DetectedFileType.PDF) {
            // 헤더조차 파싱되지 않는 이미지는 증빙으로 쓸 수 없다 → 저장 자체를 거부한다.
            ImageDimensionValidator.ensureWithinPixelLimit(file, MAX_PIXELS);
        }
    }
}
