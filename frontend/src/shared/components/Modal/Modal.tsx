import type { MouseEvent, ReactNode } from 'react';
import { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import './Modal.css';

interface ModalProps {
  open: boolean;
  onClose: () => void;
  title?: string;
  children: ReactNode;
}

const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

function getFocusableElements(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR));
}

export function Modal({ open, onClose, title, children }: ModalProps) {
  const contentRef = useRef<HTMLDivElement>(null);
  const previousActiveElementRef = useRef<HTMLElement | null>(null);
  // onClose가 부모 렌더마다 새로 생성돼도 effect가 재실행되지 않도록 ref로 최신값만 참조
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!open) {
      return;
    }

    // 열리기 직전 포커스를 기억해뒀다가, 닫힐 때 트리거 요소로 복귀시킨다.
    previousActiveElementRef.current = document.activeElement as HTMLElement | null;

    const content = contentRef.current;
    if (content) {
      const [firstFocusable] = getFocusableElements(content);
      (firstFocusable ?? content).focus();
    }

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onCloseRef.current();
        return;
      }

      if (event.key === 'Tab' && content) {
        const focusable = getFocusableElements(content);
        if (focusable.length === 0) {
          event.preventDefault();
          return;
        }

        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        const active = document.activeElement;

        if (event.shiftKey) {
          if (active === first || !content.contains(active)) {
            event.preventDefault();
            last.focus();
          }
        } else if (active === last || !content.contains(active)) {
          event.preventDefault();
          first.focus();
        }
      }
    }

    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      previousActiveElementRef.current?.focus();
    };
  }, [open]);

  if (!open) {
    return null;
  }

  function handleOverlayClick() {
    onClose();
  }

  function handleContentClick(event: MouseEvent<HTMLDivElement>) {
    event.stopPropagation();
  }

  return createPortal(
    <div className="modal-overlay" onClick={handleOverlayClick} role="presentation">
      <div
        ref={contentRef}
        className="modal-content"
        role="dialog"
        aria-modal="true"
        tabIndex={-1}
        onClick={handleContentClick}
      >
        {title && <h2 className="modal-title">{title}</h2>}
        {children}
      </div>
    </div>,
    document.body,
  );
}
