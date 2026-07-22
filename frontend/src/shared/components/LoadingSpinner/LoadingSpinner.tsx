interface LoadingSpinnerProps {
  message?: string;
  /**
   * 레이아웃(flex/justify/padding 등)을 통째로 책임지는 클래스 — 기본값과 부분적으로만 덧붙이면
   * 같은 속성(예: justify-center vs justify-start, py-6 vs py-0)의 유틸리티가 동시에 존재하게 돼
   * Tailwind가 생성한 스타일시트 순서에 따라 엉뚱한 쪽이 이겨버리는 문제가 있었다(#499 코드 리뷰
   * 지적). 그래서 "부분 override"가 아니라 이 prop이 레이아웃 클래스 전체를 완전히 대체하는
   * 방식으로 설계한다 — 커스텀 레이아웃이 필요하면 DEFAULT_LAYOUT_CLASS를 참고해 전체를 새로 적는다.
   */
  className?: string;
}

const DEFAULT_LAYOUT_CLASS = 'flex items-center justify-center gap-2 py-6';

// 공통 로딩 표시 — 각 화면이 제각각 마크업으로 "불러오는 중..." 텍스트만 그리던 것을 통일한다(#499).
// 여러 섹션이 동시에 로딩되는 화면(대시보드 등)에서도 아이콘+텍스트 조합으로 일관되게 보이도록 한다.
export function LoadingSpinner({
  message = '불러오는 중...',
  className = DEFAULT_LAYOUT_CLASS,
}: LoadingSpinnerProps) {
  return (
    <div className={`${className} text-sm text-text-muted`} role="status" aria-live="polite">
      <span
        className="h-4 w-4 shrink-0 animate-spin rounded-full border-2 border-border border-t-primary"
        aria-hidden="true"
      />
      <span>{message}</span>
    </div>
  );
}
