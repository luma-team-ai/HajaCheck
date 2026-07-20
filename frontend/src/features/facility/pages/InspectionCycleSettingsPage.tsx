import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Button } from '../../../shared/components/Button';
import { InspectionCycleSettingsCard } from '../components/InspectionCycleSettingsCard';
import { InspectionCycleStatusTable } from '../components/InspectionCycleStatusTable';
import { useInspectionCycleStatusRows } from '../hooks/useInspectionCycleStatusRows';
import { useSetInspectionSchedule } from '../hooks/useSetInspectionSchedule';
import type { InspectionCycleStatusRow, InspectionCycleType } from '../types';

// 라우트에 시설물 컨텍스트가 없어(handoff §5) 쿼리파라미터(?facilityId=)로 대상 지정,
// 미지정 시 목 대표 행(강남 오피스타워 A동, id=3 — 화면 breadcrumb과 이름이 일치)을 기본값으로 사용.
// 실연동 시 시설물 상세 화면에서 진입하는 경로로 교체 예정(주석 유지).
const DEFAULT_FACILITY_ID = 3;

function findRow(rows: InspectionCycleStatusRow[], id: number): InspectionCycleStatusRow {
  return rows.find((row) => row.id === id) ?? rows[0];
}

export function InspectionCycleSettingsPage() {
  const [searchParams] = useSearchParams();
  const initialFacilityId = Number(searchParams.get('facilityId')) || DEFAULT_FACILITY_ID;

  // 현황 목데이터를 이 훅 하나로만 조회한다(mock 모듈 직접 import 금지 — react-reviewer P1) —
  // 로딩/에러 상태를 먼저 처리하고, 데이터가 준비된 뒤에만 상호작용 상태(개월수 등)를 갖는
  // 하위 컴포넌트를 마운트해 최초 선택행 값으로 안전하게 초기화한다.
  const { data: rows, isLoading, isError } = useInspectionCycleStatusRows();

  if (isLoading) {
    return (
      <div className="mx-auto max-w-6xl px-6 py-8 text-sm text-text-muted">불러오는 중...</div>
    );
  }
  if (isError || !rows || rows.length === 0) {
    return (
      <div className="mx-auto max-w-6xl px-6 py-8 text-sm text-text-muted">
        점검 주기 정보를 불러오지 못했습니다.
      </div>
    );
  }

  return (
    <InspectionCycleSettingsPageContent rows={rows} initialFacilityId={initialFacilityId} />
  );
}

type ContentProps = {
  rows: InspectionCycleStatusRow[];
  initialFacilityId: number;
};

function InspectionCycleSettingsPageContent({ rows, initialFacilityId }: ContentProps) {
  const [selectedRow, setSelectedRow] = useState<InspectionCycleStatusRow>(() =>
    findRow(rows, initialFacilityId),
  );
  const [cycleType, setCycleType] = useState<InspectionCycleType>('정기');
  const [months, setMonths] = useState(selectedRow.cycleMonths);
  const [nextDueAt, setNextDueAt] = useState<string | null>(selectedRow.nextInspectionDueAt);
  const [notifyBeforeEnabled, setNotifyBeforeEnabled] = useState(true);
  const [warnOnOverdueEnabled, setWarnOnOverdueEnabled] = useState(false);

  const { setSchedule, isPending, error, resetError } = useSetInspectionSchedule();

  const handleSelectRow = (row: InspectionCycleStatusRow) => {
    setSelectedRow(row);
    setMonths(row.cycleMonths);
    setNextDueAt(row.nextInspectionDueAt);
    // 다른 시설물로 선택을 옮기면 이전 시설물 저장 실패 메시지가 그대로 남아있으면 안 된다(react-reviewer P2).
    resetError();
  };

  const handleSave = async () => {
    const result = await setSchedule({
      facilityId: selectedRow.id,
      body: { inspectionCycleMonths: months },
    });
    setNextDueAt(result.nextInspectionDueAt);
  };

  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-6 px-6 py-8">
      <div className="flex items-start justify-between gap-4">
        <div className="flex flex-col gap-1">
          <h1 className="m-0 text-xl font-bold text-heading">점검 주기 설정</h1>
          <p className="m-0 text-sm text-text-muted">
            시설물별 점검 주기를 설정하면 다음 점검일을 자동 계산해 대시보드·알림에 표시합니다
          </p>
        </div>
        <Button variant="primary" size="sm" onClick={handleSave} disabled={isPending}>
          {isPending ? '저장 중...' : '저장'}
        </Button>
      </div>

      {error && (
        <p role="alert" className="m-0 text-sm text-danger">
          {error.message ?? '점검 주기 저장에 실패했습니다.'}
        </p>
      )}

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <InspectionCycleSettingsCard
          cycleType={cycleType}
          onCycleTypeChange={setCycleType}
          months={months}
          onMonthsChange={setMonths}
          lastInspectedAt={selectedRow.lastInspectedAt}
          nextInspectionDueAt={nextDueAt}
          notifyBeforeEnabled={notifyBeforeEnabled}
          onNotifyBeforeChange={setNotifyBeforeEnabled}
          warnOnOverdueEnabled={warnOnOverdueEnabled}
          onWarnOnOverdueChange={setWarnOnOverdueEnabled}
        />
        <InspectionCycleStatusTable selectedId={selectedRow.id} onSelectRow={handleSelectRow} />
      </div>
    </div>
  );
}
