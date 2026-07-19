import type { ReactNode } from 'react';

interface StateRowProps {
  colSpan: number;
  children: ReactNode;
}

// AdminUserTable의 로딩·에러·빈 목록 상태를 표 구조(colSpan) 그대로 유지하며 보여주는 행.
export function StateRow({ colSpan, children }: StateRowProps) {
  return (
    <tr>
      <td className="px-4 py-12 text-center text-sm" colSpan={colSpan}>
        {children}
      </td>
    </tr>
  );
}
