import { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';

interface BusinessLicenseImageLightboxProps {
  // 사업자등록증 업로드 이미지 확대 보기(#767) — BusinessLicenseUpload가 이미 갖고 있는
  // previewUrl(objectURL)을 그대로 전달받아 재사용한다. 여기서 별도 objectURL을 생성하지 않는다.
  previewUrl: string;
  onClose: () => void;
}

// Modal.tsx와 동일한 focusable 판정 기준(WAI-ARIA Dialog 포커스 트랩용).
const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

function getFocusableElements(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR));
}

// 썸네일 클릭 시 원본을 크게 보는 라이트박스. 닫기 3경로(닫기 버튼/배경 클릭/Esc)를 지원하고,
// 이미지 자체 클릭으로는 닫히지 않는다(이미지는 onClick이 없어 배경 버튼과 겹치지 않음).
export function BusinessLicenseImageLightbox({
  previewUrl,
  onClose,
}: BusinessLicenseImageLightboxProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    closeButtonRef.current?.focus();

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onCloseRef.current();
        return;
      }

      // 포커스 트랩(P2 픽스) — aria-modal="true"는 스크린리더 힌트일 뿐 실제 Tab 이동을
      // 막지 않으므로, 배경(가려진 회원가입 폼)으로 포커스가 빠져나가지 않게 순환시킨다.
      if (event.key !== 'Tab') return;
      const container = containerRef.current;
      if (!container) return;

      const focusable = getFocusableElements(container);
      if (focusable.length === 0) {
        event.preventDefault();
        return;
      }

      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = document.activeElement;

      if (event.shiftKey) {
        if (active === first || !container.contains(active)) {
          event.preventDefault();
          last.focus();
        }
      } else if (active === last || !container.contains(active)) {
        event.preventDefault();
        first.focus();
      }
    }

    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, []);

  return createPortal(
    <div
      ref={containerRef}
      className="fixed inset-0 z-[1000] flex items-center justify-center bg-overlay p-4"
      role="dialog"
      aria-modal="true"
      aria-label="사업자등록증 이미지 크게 보기"
    >
      <button
        type="button"
        className="absolute inset-0 cursor-default border-none bg-transparent p-0"
        aria-label="배경 클릭하여 닫기"
        onClick={onClose}
      />
      <img
        src={previewUrl}
        alt="사업자등록증 원본 이미지"
        className="relative z-10 max-h-full max-w-full rounded-lg object-contain shadow-2xl"
      />
      <button
        ref={closeButtonRef}
        type="button"
        aria-label="닫기"
        className="absolute right-4 top-4 z-20 flex h-9 w-9 items-center justify-center rounded-full border-none bg-surface text-lg text-text-default hover:bg-surface-muted"
        onClick={onClose}
      >
        ✕
      </button>
    </div>,
    document.body,
  );
}
