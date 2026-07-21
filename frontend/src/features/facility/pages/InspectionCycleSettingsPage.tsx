import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Button } from '../../../shared/components/Button';
import { shouldEnableMocking } from '../../../shared/utils/shouldEnableMocking';
import { InspectionCycleSettingsCard } from '../components/InspectionCycleSettingsCard';
import { InspectionCycleStatusTable } from '../components/InspectionCycleStatusTable';
import { useInspectionCycleStatusRows } from '../hooks/useInspectionCycleStatusRows';
import { useSetInspectionSchedule } from '../hooks/useSetInspectionSchedule';
import { INSPECTION_CYCLE_DEMO_TODAY } from '../utils/inspectionCycleDemo';
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
  // selectedRow·cycleType 둘 다 최초 선택행에서 파생 — cycleType만 항상 '정기'로 고정되면
  // 세그먼트 토글이 실제 선택 행의 점검유형과 어긋난다(#462 P2).
  const [selectedRow, setSelectedRow] = useState<InspectionCycleStatusRow>(() =>
    findRow(rows, initialFacilityId),
  );
  const [cycleType, setCycleType] = useState<InspectionCycleType>(
    () => findRow(rows, initialFacilityId).type,
  );
  const [months, setMonths] = useState(selectedRow.cycleMonths);
  const [nextDueAt, setNextDueAt] = useState<string | null>(selectedRow.nextInspectionDueAt);
  const [notifyBeforeEnabled, setNotifyBeforeEnabled] = useState(true);
  const [warnOnOverdueEnabled, setWarnOnOverdueEnabled] = useState(false);

  const { setSchedule, isPending, error, resetError } = useSetInspectionSchedule();

  // 데모 기준일은 MSW 목이 실제로 켜져 있을 때만 주입한다 — 프로덕션(실 백엔드) 빌드에서
  // 고정 과거/미래 날짜가 새어 들어가 D-day가 잘못 계산되는 침묵 버그를 막는다(react-reviewer P2).
  const demoToday = shouldEnableMocking(import.meta.env) ? INSPECTION_CYCLE_DEMO_TODAY : undefined;

  const handleSelectRow = (row: InspectionCycleStatusRow) => {
    setSelectedRow(row);
    setCycleType(row.type);
    setMonths(row.cycleMonths);
    setNextDueAt(row.nextInspectionDueAt);
    // 다른 시설물로 선택을 옮기면 이전 시설물 저장 실패 메시지가 그대로 남아있으면 안 된다(react-reviewer P2).
    resetError();
  };

  const handleSave = async () => {
    try {
      const result = await setSchedule({
        facilityId: selectedRow.id,
        body: { inspectionCycleMonths: months },
      });
      setNextDueAt(result.nextInspectionDueAt);
    } catch {
      // 에러는 useSetInspectionSchedule.error → 상단 배너로 이미 노출됨. 여기선 unhandled rejection만 방지.
    }
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

      {/* 카드(폭 제한) 위 → 현황 테이블 전체 너비 아래로 스택. Figma 'Table Content'처럼 7컬럼이
          가로 스크롤 없이 모두 보이도록 테이블을 컨테이너 전체 폭으로 배치한다. */}
      <div className="flex flex-col gap-6">
        <div className="w-full lg:max-w-2xl">
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
            today={demoToday}
          />
        </div>
        <InspectionCycleStatusTable
          selectedId={selectedRow.id}
          onSelectRow={handleSelectRow}
          today={demoToday}
        />
      </div>
    </div>
  );
}
