// 공통 API 타입 — SpringBoot_코드_컨벤션.md §4 envelope와 1:1
export interface ApiError {
  code: string;
  message: string;
  status?: number; // HTTP 상태 코드 — 401(미인증)과 5xx/네트워크 오류를 호출부에서 구분하기 위함
}

export interface ApiResponse<T> {
  success: boolean;
  data: T;
  error?: ApiError;
}

// 목록 페이징 구조 통일
export interface PageResponse<T> {
  content: T[];
  page: number;
  totalElements: number;
}
