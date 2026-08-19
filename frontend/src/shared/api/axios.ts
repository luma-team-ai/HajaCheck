// 공통 axios 인스턴스 — 컴포넌트에서 axios 직접 import 금지 (React_코드_컨벤션.md §3)
// 백엔드 envelope({ success, data, error })은 인터셉터에서 해제 — 컴포넌트/훅은 data만 다룬다
import axios from 'axios';
import {
  COUNSELOR_LOGIN_PATH,
  COUNSELOR_PATH_PREFIX,
  LOGIN_PATH,
  PLATFORM_ADMIN_LOGIN_PATH,
  PLATFORM_ADMIN_PATH_PREFIX,
} from '../constants/authPaths';
import { clearPreviousUserLocalState } from '../utils/clearPreviousUserLocalState';
import { API_TIMEOUT_MS } from './timeouts';
import type { ApiError, ApiResponse } from './types';

// 세션 탐지용 요청(getMe 등)은 401을 "미로그인"이라는 정상 신호로 받으므로 전역 하드 리다이렉트에서
// 제외한다 — 이 플래그를 request config에 실으면 아래 인터셉터가 401이어도 /login으로 튕기지 않는다.
// (공개 랜딩 '/'에서 AuthGate의 getMe 401이 랜딩을 못 보게 강제 이동시키던 회귀 방지, #276)
declare module 'axios' {
  export interface AxiosRequestConfig {
    skipAuthRedirect?: boolean;
  }
}

export const api = axios.create({
  baseURL: '/api',
  withCredentials: true, // 세션 쿠키
  // 무한 대기 차단(#1598) — axios 기본값 0은 "상한 없음"이라, 응답이 삼켜지면 이 인스턴스를 쓰는
  // 모든 호출부의 catch/finally가 영원히 실행되지 않고 로딩 상태가 고착된다. 값의 역산 근거는
  // timeouts.ts 참고. 정상적으로 이보다 오래 걸리는 경로(AI·업로드)는 요청 단위로 override 한다.
  timeout: API_TIMEOUT_MS,
});

api.interceptors.response.use(
  (response) => {
    // blob 응답(파일 다운로드, 예: 상담 대화 내보내기)은 envelope({success,data,error}) 형태가
    // 아니라 파일 본문 자체이므로 unwrap을 건너뛴다 — body.success/body.data 접근 시 Blob에 그런
    // 필드가 없어 response.data가 undefined로 뭉개지는 회귀를 방지한다(카운슬 대화 내보내기 신설).
    if (response.config.responseType === 'blob') {
      return response;
    }
    const body = response.data as ApiResponse<unknown>;
    if (body && body.success === false) {
      const apiError: ApiError = {
        ...(body.error ?? { code: 'UNKNOWN_ERROR', message: '알 수 없는 오류가 발생했습니다.' }),
        status: response.status,
      };
      return Promise.reject(apiError);
    }
    response.data = body.data;
    return response;
  },
  (error) => {
    const status = error.response?.status;
    // 세션 탐지 요청(skipAuthRedirect)은 401이어도 하드 리다이렉트하지 않는다 — 공개 라우트에서
    // getMe 401은 '미로그인'일 뿐이고, 보호 라우트 가드는 ProtectedRoute가 담당한다(#276).
    const skipAuthRedirect = error.config?.skipAuthRedirect === true;
    // 플랫폼 관리자 콘솔(#535)·상담원 콘솔은 각각 별도 로그인 경로를 쓴다 — 해당 경로 하위에서
    // 401이 나면 기업회원 로그인(LOGIN_PATH)이 아니라 각 전용 로그인으로 리다이렉트한다.
    // *_PATH_PREFIX는 BASE_URL을 반영한 경로라 basename 배포에서도 정확히 매칭된다
    // (raw prefix 비교는 basename 배포에서 항상 false — PR머신 리뷰 P2, #558).
    const redirectTarget = window.location.pathname.startsWith(PLATFORM_ADMIN_PATH_PREFIX)
      ? PLATFORM_ADMIN_LOGIN_PATH
      : window.location.pathname.startsWith(COUNSELOR_PATH_PREFIX)
        ? COUNSELOR_LOGIN_PATH
        : LOGIN_PATH;
    // 이미 로그인 경로면 리다이렉트 스킵 — 로그인 화면 세션체크·로그인 실패 401이 무한 리로드로 이어지는 것 방지
    // redirectTarget이 basename까지 반영된 정확한 경로라 정확 일치로 비교(과매칭 방지 — 예: '/company/login')
    if (status === 401 && !skipAuthRedirect && window.location.pathname !== redirectTarget) {
      // 로그인 화면으로 하드 리다이렉트하기 전에 이전 세션의 로컬 잔여 상태를 지운다 —
      // activeInspectionId/activeReportId(#1194)·RAG 세션(#1590)·점검 생성 폼 임시저장(#1703) 등이
      // 모두 localStorage/IndexedDB에 영속화되므로, 안 지우면 공용 PC에서 세션 만료된 사용자의
      // 데이터가 브라우저에 남아 다음 로그인 사용자에게 노출된다(PR머신 리뷰 P1). 이 401 강제
      // 로그아웃은 로그인 3진입점·useLogout과 동일 계약을 지키는 6번째 지점이라
      // clearPreviousUserLocalState 하나로 함께 관리한다(PR #1708 2차 P1).
      clearPreviousUserLocalState();
      window.location.href = redirectTarget; // 401 일괄 처리
    }
    // timeout(#1598) 초과도 여기로 온다 — 응답 자체가 없어(error.response undefined) status는
    // undefined이고 코드는 아래 NETWORK_ERROR로 떨어진다. 이는 의도된 선택이다: 전용 코드를 새로
    // 만들면 NETWORK_ERROR를 "서버 미기동" 신호로 삼아 목 데이터로 폴백하는 dev 경로
    // (shared/utils/hybridFetchFallback.ts, mypage/utils/fetchWithFallback.ts)가 timeout에서만
    // 폴백하지 않게 되어 동작이 갈라진다. timeout은 실제로 응답 없는 네트워크 실패가 맞다.
    const apiError: ApiError = {
      ...(error.response?.data?.error ?? {
        code: 'NETWORK_ERROR',
        message: '네트워크 오류가 발생했습니다.',
      }),
      status,
    };
    return Promise.reject(apiError);
  },
);
