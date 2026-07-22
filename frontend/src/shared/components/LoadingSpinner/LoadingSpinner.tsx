interface LoadingSpinnerProps {
  message?: string;
  className?: string;
}

// 공통 로딩 표시 — 각 화면이 제각각 마크업으로 "불러오는 중..." 텍스트만 그리던 것을 통일한다(#499).
// 여러 섹션이 동시에 로딩되는 화면(대시보드 등)에서도 아이콘+텍스트 조합으로 일관되게 보이도록 한다.
export function LoadingSpinner({ message = '불러오는 중...', className = '' }: LoadingSpinnerProps) {
  return (
    <div
      className={`flex items-center justify-center gap-2 py-6 text-sm text-text-muted ${className}`}
      role="status"
      aria-live="polite"
    >
      <span
        className="h-4 w-4 animate-spin rounded-full border-2 border-border border-t-primary"
        aria-hidden="true"
      />
      <span>{message}</span>
    </div>
  );
}
