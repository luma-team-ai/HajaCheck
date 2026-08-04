import type { ReactNode } from 'react';
import { useEffect, useRef, useState } from 'react';

interface ImageWithFallbackProps {
  src: string | null | undefined;
  alt: string;
  className?: string;
  fallback: ReactNode;
}

// #1494 — 인증 필요·캐시 금지 썸네일(예: /api/media/{id}/thumbnail)은 목록 진입 시 카드 여러 장이
// 동시에 마운트되며 인증된 요청이 한꺼번에 나간다. 브라우저 호스트당 동시 연결 수 제한 + 서버
// 재인코딩 지연이 겹치면 첫 시도가 종종 실패한다(새로고침하면 보이는 게 그 증거 — 재요청 시
// 타이밍이 달라져 성공). 실패 즉시 fallback으로 영구 고정하지 않고, 짧은 지연 후 1회만 재시도한다.
const RETRY_DELAY_MS = 500;

// #1571 — 500ms 재시도(#1494)만으로는 시설물이 많은 목록(페이지네이션 없이 최대 500건, #1571
// 이슈 참고)에서 화면 밖 카드까지 전부 즉시 요청을 쏘는 근본 원인을 해결하지 못한다.
// loading="lazy"로 뷰포트 밖 이미지의 요청 자체를 미뤄 동시 요청 폭주를 줄인다.

export function ImageWithFallback({
  src,
  alt,
  className,
  fallback,
}: ImageWithFallbackProps) {
  const [failedSrc, setFailedSrc] = useState<string | null>(null);
  const [retryKey, setRetryKey] = useState(0);
  const hasRetriedRef = useRef(false);
  const retryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    setFailedSrc(null);
    setRetryKey(0);
    hasRetriedRef.current = false;
    return () => {
      if (retryTimerRef.current) {
        clearTimeout(retryTimerRef.current);
      }
    };
  }, [src]);

  const handleError = () => {
    if (!hasRetriedRef.current) {
      hasRetriedRef.current = true;
      // key를 바꿔 <img>를 완전히 새로 마운트해야 브라우저가 동일 src를 다시 요청한다
      // (React가 속성값이 그대로면 DOM에 손대지 않아, src를 그대로 재대입해도 재요청이 안 된다).
      retryTimerRef.current = setTimeout(() => setRetryKey((key) => key + 1), RETRY_DELAY_MS);
      return;
    }
    // code-reviewer LOW — 재시도 타이머가 아직 안 끝난 상태에서 동일 <img>에 두 번째 에러가 오면
    // (테스트 하네스의 동기 이중 발화 경로 등) 대기 중이던 타이머를 취소해, 이미 fallback으로 넘어간
    // 뒤에 뒤늦게 retryKey만 바뀌는 불필요한 리렌더를 막는다.
    if (retryTimerRef.current) {
      clearTimeout(retryTimerRef.current);
      retryTimerRef.current = null;
    }
    setFailedSrc(src ?? null);
  };

  if (!src || failedSrc === src) {
    return <>{fallback}</>;
  }

  return (
    <img
      key={retryKey}
      src={src}
      alt={alt}
      className={className}
      loading="lazy"
      onError={handleError}
    />
  );
}
