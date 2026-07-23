import { useCallback } from 'react';
import { loadFacilityPostcodeScript } from '../utils/loadFacilityPostcodeScript';

// 다음(카카오) 우편번호 검색 팝업 — auth/hooks/useDaumPostcodeSearch.ts와 동일 로직.
// auth 폴더는 다른 팀원 소유라 직접 import하지 않고 facility 전용으로 복제한다(#629).
// 컴포넌트에서 window.daum 직접 접근 금지, 이 훅으로만 캡슐화.
export function useFacilityPostcodeSearch() {
  const openPostcodeSearch = useCallback(
    (onComplete: (address: string) => void, onError?: () => void) => {
      loadFacilityPostcodeScript()
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
