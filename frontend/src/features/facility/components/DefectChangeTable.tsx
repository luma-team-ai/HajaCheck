import { Table } from '../../../shared/components/Table';
import type { TableColumn } from '../../../shared/components/Table';
import { DefectChangeBadge } from './DefectChangeBadge';
import { FacilityGradeBadge } from './FacilityGradeBadge';
import type { DefectChangeRow } from '../types';

type Props = {
  rows: DefectChangeRow[];
  beforeCycle: number;
  afterCycle: number;
};

// 하자 변화 목록 테이블 — 위치/유형, 회차별 등급, 변화 배지, 비고(#489 스펙).
export function DefectChangeTable({ rows, beforeCycle, afterCycle }: Props) {
  const columns: TableColumn<DefectChangeRow>[] = [
    {
      key: 'location',
      header: '위치/유형',
      render: (row) => `${row.location} / ${row.defectType}`,
    },
    {
      key: 'gradeBefore',
      header: `${beforeCycle}회차 등급`,
      render: (row) => <FacilityGradeBadge grade={row.gradeBefore} />,
    },
    {
      key: 'gradeAfter',
      header: `${afterCycle}회차 등급`,
      render: (row) => <FacilityGradeBadge grade={row.gradeAfter} />,
    },
    {
      key: 'changeType',
      header: '변화',
      render: (row) => <DefectChangeBadge changeType={row.changeType} />,
    },
    {
      key: 'note',
      header: '비고',
      render: (row) => row.note,
    },
  ];

  return <Table columns={columns} data={rows} emptyMessage="변화가 감지된 하자가 없습니다." />;
}