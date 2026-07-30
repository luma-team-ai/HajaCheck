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

// 하자 변화 목록 테이블 — 유형, 회차별 등급, 변화 배지, 비고(#489 스펙).
//
// #1344 — 원래 "위치/유형" 한 컬럼에 `${location} / ${defectType}`을 합쳐 찍었으나, 백엔드가 내려주는
// Defect.location이 비어 있는 하자가 많아 화면에 "null / 균열"이 그대로 노출됐다. 위치는 빼고 유형만
// 보여준다(2026-07-31 사용자 결정). 응답의 location 필드 자체는 API 계약대로 유지한다.
export function DefectChangeTable({ rows, beforeCycle, afterCycle }: Props) {
  const columns: TableColumn<DefectChangeRow>[] = [
    {
      key: 'defectType',
      header: '유형',
      render: (row) => row.defectType,
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