import { createPortal } from 'react-dom';
import { useOutsideDismiss } from '../../../shared/hooks/useOutsideDismiss';

type Props = {
  onOpenHistory: () => void;
  onClone: () => void;
  onSubmit: () => void;
  onClose: () => void;
  anchor: { top: number; left: number };
  canSubmit: boolean;
  isClonePending?: boolean;
  isSubmitPending?: boolean;
  actionError?: string | null;
};

export function ReportRowMenu({
  onOpenHistory,
  onClone,
  onSubmit,
  onClose,
  anchor,
  canSubmit,
  isClonePending = false,
  isSubmitPending = false,
  actionError = null,
}: Props) {
  const rootRef = useOutsideDismiss<HTMLDivElement>(onClose);
  const isBusy = isClonePending || isSubmitPending;

  return createPortal(
    <div
      ref={rootRef}
      role="menu"
      aria-label="보고서 작업 메뉴"
      className="fixed z-50 w-32 rounded-[20px] border border-zinc-200 bg-white/90 py-1 shadow-lg backdrop-blur-[6px]"
      style={{ top: anchor.top, left: anchor.left }}
    >
      <button
        type="button"
        role="menuitem"
        className="w-full cursor-pointer border-none bg-none px-3 py-1.5 text-left text-sm font-medium text-zinc-900"
        onClick={() => {
          onOpenHistory();
          onClose();
        }}
      >
        버전 이력
      </button>
      <button
        type="button"
        role="menuitem"
        disabled={isBusy}
        className="w-full cursor-pointer border-none bg-none px-3 py-1.5 text-left text-sm font-medium text-zinc-900 disabled:cursor-not-allowed disabled:text-zinc-400"
        onClick={onClone}
      >
        {isClonePending ? '복제 중' : '복제'}
      </button>
      <button
        type="button"
        role="menuitem"
        disabled={!canSubmit || isBusy}
        title={!canSubmit ? 'DRAFT 보고서만 제출할 수 있습니다' : undefined}
        className="w-full cursor-pointer border-none bg-none px-3 py-1.5 text-left text-sm font-medium text-zinc-900 disabled:cursor-not-allowed disabled:text-zinc-400"
        onClick={onSubmit}
      >
        {isSubmitPending ? '제출 중' : '제출 처리'}
      </button>
      {actionError && (
        <p role="alert" className="m-0 px-3 py-1 text-xs leading-4 text-red-600">
          {actionError}
        </p>
      )}
      <div className="my-1 h-px bg-zinc-200" />
      <button
        type="button"
        role="menuitem"
        disabled
        title="삭제는 지원하지 않습니다"
        className="w-full cursor-not-allowed border-none bg-none px-3 py-1.5 text-left text-sm text-red-300"
      >
        삭제
      </button>
    </div>,
    document.body,
  );
}
