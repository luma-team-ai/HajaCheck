import type { MouseEvent, ReactNode } from 'react';
import { useEffect } from 'react';
import { createPortal } from 'react-dom';
import './Modal.css';

interface ModalProps {
  open: boolean;
  onClose: () => void;
  title?: string;
  children: ReactNode;
}

export function Modal({ open, onClose, title, children }: ModalProps) {
  useEffect(() => {
    if (!open) {
      return;
    }

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onClose();
      }
    }

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [open, onClose]);

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
      <div className="modal-content" role="dialog" aria-modal="true" onClick={handleContentClick}>
        {title && <h2 className="modal-title">{title}</h2>}
        {children}
      </div>
    </div>,
    document.body,
  );
}
