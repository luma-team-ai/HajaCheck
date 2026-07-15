// 결제 이력 — 데이터 소스 없음(PG 실결제 Out-of-scope, contract.md). 후속 이슈에서 billing 스키마와 함께 설계.
export function BillingHistoryPlaceholder() {
  return (
    <section className="dashboard-card mypage-billing-card">
      <h3 className="dashboard-card-title">결제 이력</h3>
      <p className="dashboard-card-status">결제 이력 조회는 준비 중입니다.</p>
    </section>
  );
}
