interface AILoadingIndicatorProps {
  message?: string;
}

// prop 이름은 dev의 기존 사용부(AiBriefingCard: <AILoadingIndicator message="..." />)와
// 호환되도록 message로 맞춤(과거 flat 버전과 동일 API)
export function AILoadingIndicator({ message = 'AI 분석 중입니다...' }: AILoadingIndicatorProps) {
  return (
    <div className="flex flex-col items-center gap-3 px-4 py-6" role="status" aria-live="polite">
      <span
        className="h-6 w-6 animate-spin rounded-full border-[3px] border-neutral-100 border-t-accent"
        aria-hidden="true"
      />
      <div className="flex w-full max-w-60 flex-col gap-2">
        <span className="h-2.5 animate-pulse rounded bg-neutral-100" />
        <span className="h-2.5 w-3/5 animate-pulse rounded bg-neutral-100" />
      </div>
      <p className="m-0 text-[13px] text-text-muted">{message}</p>
    </div>
  );
}
