// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { Table, type TableColumn } from './Table';

afterEach(cleanup);

interface DefectRow {
  id: number;
  type: string;
  grade: string;
}

const columns: TableColumn<DefectRow>[] = [
  { key: 'type', header: '유형' },
  { key: 'grade', header: '등급', render: (row) => `${row.grade}등급` },
];

describe('Table', () => {
  it('columns/data에 맞춰 셀을 렌더링한다', () => {
    const data: DefectRow[] = [
      { id: 1, type: '균열', grade: 'A' },
      { id: 2, type: '누수', grade: 'B' },
    ];

    render(<Table columns={columns} data={data} />);

    expect(screen.getByText('균열')).not.toBeNull();
    expect(screen.getByText('A등급')).not.toBeNull();
    expect(screen.getByText('누수')).not.toBeNull();
    expect(screen.getByText('B등급')).not.toBeNull();
  });

  it('data가 빈 배열이면 emptyMessage를 표시한다', () => {
    render(<Table columns={columns} data={[]} emptyMessage="결과 없음" />);

    expect(screen.getByText('결과 없음')).not.toBeNull();
  });
});
