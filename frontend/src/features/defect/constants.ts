// 하자 상세 모달 "조치 후 사진 업로드" 제약 — contract.md §"조치 결과 등록" 필드 표(JPG/PNG, 최대 10MB).
// inspection feature의 MEDIA_*(허용 20MB) 상수와 값이 달라 feature 간 직접 import 금지 컨벤션에 따라
// 별도로 정의한다(React_코드_컨벤션.md §1).
export const DEFECT_ACTION_PHOTO_ALLOWED_TYPES = ['image/jpeg', 'image/png'];
export const DEFECT_ACTION_PHOTO_MAX_SIZE_BYTES = 10 * 1024 * 1024;
