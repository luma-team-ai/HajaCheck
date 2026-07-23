// 촬영 데이터 업로드 제약 — 백엔드 MediaUploadProperties 기본값과 정합(dev-05-03).
// 이번 PR 범위는 이미지만 — 영상(MP4)은 MediaFileType.java 주석대로 후속 PR 범위.
export const MEDIA_ALLOWED_TYPES = ['image/jpeg', 'image/png'];
export const MEDIA_MAX_SIZE_BYTES = 20 * 1024 * 1024;
export const MEDIA_MAX_FILES_PER_REQUEST = 10;

// 영상은 회의 후 반영된 새 점검 생성 화면 시안(JPG·PNG·MP4 동시 첨부)에서 선택만 허용 —
// 백엔드 업로드 엔드포인트는 아직 이미지만 받으므로(MediaFileType.java) 실제 전송은 하지 않고
// "프레임 추출 예정" 상태로만 보여준다. 용량 상한은 시안 문구("최대 500MB") 기준.
export const MEDIA_ALLOWED_VIDEO_TYPES = ['video/mp4'];
export const MEDIA_VIDEO_MAX_SIZE_BYTES = 500 * 1024 * 1024;
