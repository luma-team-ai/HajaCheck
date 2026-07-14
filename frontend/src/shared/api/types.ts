// 공통 API 타입 — SpringBoot_코드_컨벤션.md §4 envelope와 1:1
export interface ApiError {
  code: string;
  message: string;
  status?: number;
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
