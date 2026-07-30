// admin/components/icons/DownloadIcon.tsx와 동일한 형태 — cross-feature import 대신
// 이 feature 안에 로컬로 둔다(다른 feature는 자기 아이콘을 자기 폴더에 둔다, admin 패턴 참고).
export function DownloadIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden>
      <path
        d="M7 1.8v7m0 0L4.4 6.2M7 8.8l2.6-2.6M2 11.2h10"
        stroke="currentColor"
        strokeWidth="1.3"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
