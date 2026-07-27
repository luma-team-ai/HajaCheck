import { createPortal } from 'react-dom';
import { useOutsideDismiss } from '../../../shared/hooks/useOutsideDismiss';

type Props = {
  onOpenHistory: () => void;
  onClose: () => void;
  anchor: { top: number; left: number };
};

const UNSUPPORTED_TITLE = '후속 지원 예정(BE 미구현)';

// 보고서 목록 행 "⋮" 컨텍스트 메뉴(Figma 시안) — 버전 이력만 실제로 동작한다. 복제/제출 처리/삭제는
// 백엔드에 대응 엔드포인트가 없어(ReportController 참고) disabled+안내 문구로 렌더한다
// (MyInspectionsTable "결과 보기" 버튼과 동일한 선례 — 가짜 성공을 만들지 않는다).
export function ReportRowMenu({ onOpenHistory, onClose, anchor }: Props) {
  const rootRef = useOutsideDismiss<HTMLDivElement>(onClose);

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
        disabled
        title={UNSUPPORTED_TITLE}
        className="w-full cursor-not-allowed border-none bg-none px-3 py-1.5 text-left text-sm text-zinc-400"
      >
        복제
      </button>
      <button
        type="button"
        role="menuitem"
        disabled
        title={UNSUPPORTED_TITLE}
        className="w-full cursor-not-allowed border-none bg-none px-3 py-1.5 text-left text-sm text-zinc-400"
      >
        제출 처리
      </button>
      <div className="my-1 h-px bg-zinc-200" />
      <button
        type="button"
        role="menuitem"
        disabled
        title={UNSUPPORTED_TITLE}
        className="w-full cursor-not-allowed border-none bg-none px-3 py-1.5 text-left text-sm text-red-300"
      >
        삭제
      </button>
    </div>,
    document.body,
  );
}
