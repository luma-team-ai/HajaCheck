// 빨간 문서 아이콘 — 내 보고서 카드(HAJA-366, #668, Figma 시안). admin/components/icons의
// currentColor SVG 관례를 따르되, 색은 항상 danger(빨강) 고정이라 fill을 리터럴로 둔다.
export function ReportDocumentIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden>
      <path
        d="M5 2.5h6.5L15 6v11a.5.5 0 0 1-.5.5h-9a.5.5 0 0 1-.5-.5V3a.5.5 0 0 1 .5-.5Z"
        className="fill-danger"
      />
      <path d="M11.5 2.5V6H15" className="fill-danger-soft-bg" />
      <path
        d="M6.5 10h7M6.5 12.5h7M6.5 15h4.5"
        stroke="white"
        strokeWidth="1"
        strokeLinecap="round"
      />
    </svg>
  );
}
