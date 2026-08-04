package com.hajacheck.core.media.support;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 파일의 <b>실제</b> 타입을 매직바이트(파일 시그니처)로 판별한다 — 이 레포가 업로드로 다루는 3종
 * (JPEG·PNG·PDF)만 안다.
 *
 * <p><b>왜 필요한가</b>: 클라이언트가 보내는 {@code Content-Type} 은 멀티파트 파트 헤더 한 줄이라
 * curl 로 자유롭게 바꿀 수 있다. 선언 타입만 믿고 분기하면 "훔친 등록증 JPEG 를 그대로 두고 헤더만
 * {@code application/pdf} 로 바꿔" 이미지 전용 검사를 통째로 건너뛰는 우회가 성립한다(#1469 리뷰 P1).
 * 따라서 <b>분기의 근거는 언제나 실제 바이트</b>여야 한다.
 *
 * <p>{@link ImageSignatureValidator} 와의 차이: 그쪽은 "선언 타입과 실제 바이트가 일치하는가"를
 * <b>검증</b>(불일치면 예외)하고 이미지 2종만 다룬다. 이 클래스는 "실제 타입이 무엇인가"를 <b>판별</b>해
 * 돌려줄 뿐이라 호출부가 타입별로 다른 처리(PDF 래스터화 등)를 고를 수 있고, PDF 도 안다.
 */
public enum DetectedFileType {

    JPEG("image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
    PNG("image/png", new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}),
    /** {@code %PDF-} — PDF 1.x/2.x 공통 헤더. */
    PDF("application/pdf", new byte[] {0x25, 0x50, 0x44, 0x46, 0x2D});

    /** 판별에 필요한 최대 헤더 길이(PNG 시그니처 8바이트가 최장). */
    public static final int HEADER_LENGTH = 1024;

    private final String mimeType;
    private final byte[] magic;

    DetectedFileType(String mimeType, byte[] magic) {
        this.mimeType = mimeType;
        this.magic = magic;
    }

    public String mimeType() {
        return mimeType;
    }

    /**
     * 헤더 바이트로 실제 타입을 판별한다. 아는 3종이 아니면 {@link Optional#empty()}.
     *
     * <p>래스터 이미지 2종은 <b>오프셋 0 고정</b>으로 대조한다(선행 바이트를 허용하면 위조 여지만 커진다).
     * PDF 만 {@link #HEADER_LENGTH} 창 안에서 {@code %PDF-} 를 <b>탐색</b>한다 — 사양상 헤더 앞에 선행
     * 바이트·BOM 이 올 수 있고 PDFBox 도 앞부분을 스캔해 파싱하므로, 선두 고정으로 대조하면 "PDFBox 는
     * 읽는데 우리 저장은 거부"하는 불일치가 생겨 정상 PDF 업로드가 400 이 된다(2026-08-04 리뷰 P2).
     */
    public static Optional<DetectedFileType> detect(byte[] header) {
        if (header == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(type -> type == PDF ? contains(header, type.magic) : startsWith(header, type.magic))
                .findFirst();
    }

    /**
     * 파일 앞부분만 읽어 실제 타입을 판별한다. 스트림을 열어 최대 {@link #HEADER_LENGTH} 바이트만 읽고
     * 즉시 닫으므로 전체 디코딩·전체 적재가 없다.
     *
     * <p>읽기 자체가 실패하면 "판별 불가"({@link Optional#empty()})로 돌려준다 — 판별기가 IO 예외를
     * 던져 호출부의 흐름 제어를 대신 결정하지 않는다(호출부마다 실패 처리 정책이 다르다).
     */
    public static Optional<DetectedFileType> detect(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Optional.empty();
        }
        try (InputStream in = file.getInputStream()) {
            return detect(in.readNBytes(HEADER_LENGTH));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** {@code data} 안 어디에든 {@code needle} 이 있는지(선행 바이트 허용 — PDF 전용). */
    private static boolean contains(byte[] data, byte[] needle) {
        for (int offset = 0; offset + needle.length <= data.length; offset++) {
            boolean matched = true;
            for (int i = 0; i < needle.length; i++) {
                if (data[offset + i] != needle[i]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
