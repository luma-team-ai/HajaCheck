import { useEffect, useRef } from 'react';
import type { RefObject } from 'react';

/**
 * 컨테이너 바깥 클릭(mousedown) 또는 Escape 키 입력 시 onDismiss를 호출하는 훅.
 * NotificationDropdown/FloatingPopup/AdminUserRowMenu에 각각 따로 구현돼 있던 동일 패턴을
 * 하나로 추출했다(#401). onDismiss는 ref로 캐시해 리스너를 값 변경마다 재등록하지 않는다.
 * enabled=false면 리스너를 등록하지 않는다(메뉴가 닫혀 있을 때는 감지가 불필요한 경우 등).
 */
export function useOutsideDismiss<T extends HTMLElement>(
  onDismiss: (() => void) | undefined,
  enabled = true,
): RefObject<T | null> {
  const containerRef = useRef<T>(null);
  const onDismissRef = useRef(onDismiss);
  onDismissRef.current = onDismiss;

  useEffect(() => {
    if (!enabled || !onDismissRef.current) {
      return;
    }

    function handlePointerDown(event: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        onDismissRef.current?.();
      }
    }

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onDismissRef.current?.();
      }
    }

    document.addEventListener('mousedown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('mousedown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [enabled]);

  return containerRef;
}
