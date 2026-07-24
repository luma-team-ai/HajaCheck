// 공통 API 타입 — SpringBoot_코드_컨벤션.md §4 envelope와 1:1
export interface ApiError {
  code: string;
  message: string;
  status?: number; // HTTP 상태 코드 — 401(미인증)과 5xx/네트워크 오류를 호출부에서 구분하기 위함
}

// axios 인터셉터(axios.ts)가 실패 시 던지는 값이 ApiError 형태(평범한 객체, message: string)인지
// 판별한다(코드 리뷰 P3) — ApiError는 클래스가 아니라 인터페이스라 `instanceof Error`로는 절대 못
// 잡는다. catch(error) { error instanceof Error ? error.message : fallback } 패턴은 axios 실패에
// 항상 false가 되어 서버가 준 구체 사유(예: '업로드된 이미지가 없습니다.')가 대체 문구로 가려진다.
function isApiError(error: unknown): error is ApiError {
  return typeof error === 'object' && error !== null && typeof (error as ApiError).message === 'string';
}

/** catch(error)에서 사용자에게 보여줄 메시지를 뽑는다 — ApiError면 서버 메시지, 아니면 fallback. */
export function getApiErrorMessage(error: unknown, fallback: string): string {
  return isApiError(error) ? error.message : fallback;
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
