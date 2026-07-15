import { useCallback } from 'react';
import { loadDaumPostcodeScript } from '../utils/loadDaumPostcodeScript';

// 다음(카카오) 우편번호 검색 팝업 — 컴포넌트에서 window.daum 직접 접근 금지, 이 훅으로만 캡슐화
export function useDaumPostcodeSearch() {
  const openPostcodeSearch = useCallback(
    (onComplete: (address: string) => void, onError?: () => void) => {
      loadDaumPostcodeScript()
        .then(() => {
          if (!window.daum?.Postcode) {
            onError?.();
            return;
          }
          new window.daum.Postcode({
            oncomplete: (data) => {
              onComplete(data.roadAddress || data.jibunAddress);
            },
          }).open();
        })
        .catch(() => onError?.());
    },
    [],
  );

  return { openPostcodeSearch };
}
