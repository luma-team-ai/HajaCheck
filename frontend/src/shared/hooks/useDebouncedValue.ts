// 값이 delayMs 동안 변경 없이 안정될 때만 갱신되는 디바운스 훅 (외부 라이브러리 없이 최소 구현)
import { useEffect, useState } from 'react';

/**
 * 입력값이 delayMs 동안 추가 변경 없이 유지되면 그 값을 반환합니다.
 * 검색어처럼 타이핑마다 바뀌는 값을 무거운 재계산(예: 지도 마커 재생성) 의존성에
 * 직접 걸지 않고, 디바운스된 값을 대신 사용하기 위한 용도입니다.
 * @param value - 디바운스할 원본 값
 * @param delayMs - 안정 판단 대기 시간(ms)
 * @returns delayMs 동안 값이 바뀌지 않았을 때의 최신 값
 */
export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debouncedValue, setDebouncedValue] = useState(value);

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedValue(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);

  return debouncedValue;
}
