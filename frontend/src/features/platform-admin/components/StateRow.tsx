import type { ReactNode } from 'react';

interface StateRowProps {
  colSpan: number;
  children: ReactNode;
  /** 지정 시 데이터 유무와 무관하게 표 높이를 고정하는 용도(px) — ErrorLogTable 참고. */
  minHeightPx?: number;
}

// AdminUserTable의 로딩·에러·빈 목록 상태를 표 구조(colSpan) 그대로 유지하며 보여주는 행.
export function StateRow({ colSpan, children, minHeightPx }: StateRowProps) {
  return (
    <tr>
      <td
        className="px-4 py-12 text-center text-sm"
        colSpan={colSpan}
        style={minHeightPx ? { height: minHeightPx } : undefined}
      >
        {children}
      </td>
    </tr>
  );
}
