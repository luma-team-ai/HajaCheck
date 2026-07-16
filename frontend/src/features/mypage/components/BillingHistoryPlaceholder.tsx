// 결제 이력 — 데이터 소스 없음(PG 실결제 Out-of-scope, contract.md). 후속 이슈에서 billing 스키마와 함께 설계.
export function BillingHistoryPlaceholder() {
  return (
    <section className="flex flex-col gap-2 py-6 first:pt-0 last:pb-0">
      <h3 className="text-xl font-semibold text-heading">결제 이력</h3>
      <p className="text-sm text-text-muted">결제 이력 조회는 준비 중입니다.</p>
    </section>
  );
}
