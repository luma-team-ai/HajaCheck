interface Props {
  title: string;
}

// 플랫폼 관리자 콘솔 7개 메뉴의 placeholder(#535) — 실제 기능은 후속 이슈 범위.
// 기존 BillingHistoryPlaceholder(features/mypage)와 동일한 "제목 + 준비 중 안내" 패턴을 따른다.
export function PlatformAdminPlaceholderPage({ title }: Props) {
  return (
    <section className="flex flex-col gap-2 p-8">
      <h1 className="text-xl font-semibold text-heading">{title}</h1>
      <p className="text-sm text-text-muted">{title} 기능은 준비 중입니다.</p>
    </section>
  );
}
