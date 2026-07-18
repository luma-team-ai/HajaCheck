import { useEffect } from 'react';

// 새 비밀번호 설정 화면 전용 — 토큰이 URL 쿼리(?token=)에 실리므로, 외부 리소스 요청 시 Referer
// 헤더로 토큰이 새는 것을 막는다(계약 "프론트 보안 규약" 고정 요건: Referrer-Policy: no-referrer).
// SPA라 nginx가 모든 경로에 동일 index.html을 서빙해 서버 응답 헤더로 페이지별 정책을 걸 수
// 없으므로, 마운트 시 <meta name="referrer"> 태그를 주입하고 언마운트 시 제거해 페이지 스코프
// Referrer-Policy를 흉내낸다.
export function useNoReferrer(): void {
  useEffect(() => {
    const meta = document.createElement('meta');
    meta.name = 'referrer';
    meta.content = 'no-referrer';
    document.head.appendChild(meta);

    return () => {
      document.head.removeChild(meta);
    };
  }, []);
}
