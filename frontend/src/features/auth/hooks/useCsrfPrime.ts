import { useEffect } from 'react';
import { authApi } from '../api/authApi';

// CSRF 쿠키 프라이밍용 더미 이메일 — 실제 이메일 중복확인이 아니라 GET 호출 자체가 목적(결과 미사용)
const CSRF_PRIME_EMAIL = 'csrf-prime@hajacheck.internal';

// 계약(contract.md) 공통 규약: 비로그인 POST도 XSRF-TOKEN 쿠키를 먼저 받아야 통과 →
// 회원가입 외 인증 폼(아이디/비밀번호 찾기 등) 마운트 시 GET 1회로 쿠키를 미리 확보한다.
// 응답/에러는 사용하지 않음 — 프라이밍 실패해도 폼은 그대로 노출하고, 실제 제출 시 에러를 그대로 노출한다.
export function useCsrfPrime(): void {
  useEffect(() => {
    authApi.checkEmailAvailability(CSRF_PRIME_EMAIL).catch(() => {
      // 무시 — 프라이밍 실패는 치명적이지 않음
    });
  }, []);
}
