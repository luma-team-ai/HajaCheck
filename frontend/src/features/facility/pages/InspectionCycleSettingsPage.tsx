import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Button } from '../../../shared/components/Button';
import { shouldEnableMocking } from '../../../shared/utils/shouldEnableMocking';
import { InspectionCycleSettingsCard } from '../components/InspectionCycleSettingsCard';
import { InspectionCycleStatusTable } from '../components/InspectionCycleStatusTable';
import { useInspectionCycleStatusRows } from '../hooks/useInspectionCycleStatusRows';
import { useInspectionNotificationSettings } from '../hooks/useInspectionNotificationSettings';
import { useSaveInspectionNotificationSettings } from '../hooks/useSaveInspectionNotificationSettings';
import { useSetInspectionSchedule } from '../hooks/useSetInspectionSchedule';
import { INSPECTION_CYCLE_DEMO_TODAY } from '../utils/inspectionCycleDemo';
import type { InspectionCycleStatusRow, InspectionCycleType } from '../types';

// 알림설정 GET이 저장한 적 없는 시설물에 대해 서버 기본값을 돌려주지만, 조회가 끝나기 전(로딩 중)
// 잠깐 보여줄 화면 초기값도 서버 컬럼 기본값과 동일하게 맞춘다(InspectionNotificationSettingResponse
// .defaults()와 정합 — 두 곳이 어긋나면 로딩 중/직후 토글 값이 깜빡여 보인다).
// warnOnOverdueEnabled 기본값은 true다(HAJA-498/V21) — false로 시작했다가 연체 시설물에 알림이 더 이상
// 발행되지 않는 회귀가 발견돼 Polalise 승인(옵션1)으로 되돌렸다.
const DEFAULT_NOTIFY_BEFORE_ENABLED = true;
const DEFAULT_NOTIFY_BEFORE_DAYS = 7;
const DEFAULT_WARN_ON_OVERDUE_ENABLED = true;

// #1129 회귀 픽스 — 이전에는 facilityId 쿼리파라미터 미지정 시 하드코딩된 DEFAULT_FACILITY_ID(=3, 목
// 대표 행)로 폴백했다. 이 목 행 id는 실 회사 소유 시설물과 무관한 합성값이라, 폴백이 걸리는 즉시
// useInspectionNotificationSettings가 그 id로 실 백엔드 GET을 쏴 회사 스코프 조회(findByIdAndCompanyId,
// IDOR 방지)가 못 찾아 FACILITY_NOT_FOUND가 났다(id를 rows[0]으로 바꿔도 마찬가지 — 이 훅이 반환하는
// 목록 자체가 백엔드 미연동 로컬 mock이라 어떤 고정 id를 골라도 동일한 문제가 재현된다).
// 근본 해결은 시설물을 명시적으로 "고른 뒤"에만 알림설정 조회를 쏘는 것 — id 미지정/미매칭 시 null을
// 유지해 InspectionCycleSettingsForm(알림설정 훅 포함)을 아예 마운트하지 않는다(조건부 훅 호출 대신
// 조건부 자식 마운트 — rules/react/hooks.md 패턴).
function findRow(rows: InspectionCycleStatusRow[], id: number | null): InspectionCycleStatusRow | null {
  if (id == null) {
    return null;
  }
  return rows.find((row) => row.id === id) ?? null;
}

export function InspectionCycleSettingsPage() {
  const [searchParams] = useSearchParams();
  const facilityIdParam = searchParams.get('facilityId');
  const initialFacilityId = facilityIdParam ? Number(facilityIdParam) : null;

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
    // key로 facilityId 쿼리파라미터가 바뀌면(같은 라우트에 머문 채로) 콘텐츠를 리마운트한다 —
    // selectedRow는 최초 마운트 시 한 번만 평가되는 lazy useState 초기값이라, key 없이는 이후
    // facilityId 변경이 선택 상태에 반영되지 않는다(react-reviewer P2).
    <InspectionCycleSettingsPageContent
      key={initialFacilityId ?? 'none'}
      rows={rows}
      initialFacilityId={initialFacilityId}
    />
  );
}

type ContentProps = {
  rows: InspectionCycleStatusRow[];
  initialFacilityId: number | null;
};

function InspectionCycleSettingsPageContent({ rows, initialFacilityId }: ContentProps) {
  const [selectedRow, setSelectedRow] = useState<InspectionCycleStatusRow | null>(() =>
    findRow(rows, initialFacilityId),
  );

  // 데모 기준일은 MSW 목이 실제로 켜져 있을 때만 주입한다 — 프로덕션(실 백엔드) 빌드에서
  // 고정 과거/미래 날짜가 새어 들어가 D-day가 잘못 계산되는 침묵 버그를 막는다(react-reviewer P2).
  const demoToday = shouldEnableMocking(import.meta.env) ? INSPECTION_CYCLE_DEMO_TODAY : undefined;

  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-6 px-6 py-8">
      <div className="flex flex-col gap-1">
        <h1 className="m-0 text-xl font-bold text-heading">점검 주기 설정</h1>
        <p className="m-0 text-sm text-text-muted">
          시설물별 점검 주기를 설정하면 다음 점검일을 자동 계산해 대시보드·알림에 표시합니다
        </p>
      </div>

      {/* 카드(폭 제한) 위 → 현황 테이블 전체 너비 아래로 스택. Figma 'Table Content'처럼 7컬럼이
          가로 스크롤 없이 모두 보이도록 테이블을 컨테이너 전체 폭으로 배치한다. */}
      <div className="flex flex-col gap-6">
        <div className="w-full lg:max-w-2xl">
          {selectedRow ? (
            // key로 행 전환 시 폼 전체를 리마운트한다 — cycleType/months/알림토글/에러 상태가 훅
            // 초기값으로 자동 리셋되어, 이전 시설물 상태가 새 시설물로 새는 것을 수동 리셋 코드 없이 방지한다.
            <InspectionCycleSettingsForm key={selectedRow.id} row={selectedRow} today={demoToday} />
          ) : (
            <div className="flex flex-col items-center justify-center gap-1 rounded-2xl border border-dashed border-border bg-surface p-10 text-center">
              <p className="m-0 text-sm font-medium text-text-default">
                점검 주기를 설정할 시설물을 선택해주세요
              </p>
              <p className="m-0 text-sm text-text-muted">
                아래 목록에서 시설물을 클릭하면 주기 설정 카드가 나타납니다
              </p>
            </div>
          )}
        </div>
        <InspectionCycleStatusTable
          selectedId={selectedRow?.id ?? null}
          onSelectRow={setSelectedRow}
          today={demoToday}
        />
      </div>
    </div>
  );
}

type FormProps = {
  row: InspectionCycleStatusRow;
  today?: Date;
};

// 시설물이 실제로 선택된 뒤에만 마운트된다 — useInspectionNotificationSettings(실 백엔드 GET)를
// 포함한 모든 시설물 스코프 훅이 이 컴포넌트 안에 있어, 선택 전에는 어떤 facilityId로도 백엔드를
// 호출하지 않는다(#1129).
function InspectionCycleSettingsForm({ row, today }: FormProps) {
  const [cycleType, setCycleType] = useState<InspectionCycleType>(row.type);
  const [months, setMonths] = useState(row.cycleMonths);
  const [nextDueAt, setNextDueAt] = useState<string | null>(row.nextInspectionDueAt);
  const [notifyBeforeEnabled, setNotifyBeforeEnabled] = useState(DEFAULT_NOTIFY_BEFORE_ENABLED);
  // notifyBeforeDays는 화면에 입력 UI가 없다(#540 ③ 범위 — 토글만 노출) — 조회된 값을 그대로
  // 보존했다가 저장 시 되돌려 보내, 다른 경로로 커스텀된 값이 있어도 저장 시 7일로 덮어쓰지 않는다.
  const [notifyBeforeDays, setNotifyBeforeDays] = useState(DEFAULT_NOTIFY_BEFORE_DAYS);
  const [warnOnOverdueEnabled, setWarnOnOverdueEnabled] = useState(DEFAULT_WARN_ON_OVERDUE_ENABLED);

  const { setSchedule, isPending, error } = useSetInspectionSchedule();
  const { data: notificationSettings } = useInspectionNotificationSettings(row.id);
  const {
    saveNotificationSettings,
    isPending: isNotificationSettingsSaving,
    error: notificationSettingsError,
  } = useSaveInspectionNotificationSettings();

  // 선택된 시설물의 알림설정 조회가 끝나면 토글 상태를 그 값으로 동기화한다(폼 초기화 — 비동기로
  // 가져온 값을 편집 가능한 로컬 상태의 시작값으로 삼는 통상적인 패턴).
  useEffect(() => {
    if (notificationSettings) {
      setNotifyBeforeEnabled(notificationSettings.notifyBeforeEnabled);
      setNotifyBeforeDays(notificationSettings.notifyBeforeDays);
      setWarnOnOverdueEnabled(notificationSettings.warnOnOverdueEnabled);
    }
  }, [notificationSettings]);

  const handleSave = async () => {
    try {
      const [scheduleResult] = await Promise.all([
        setSchedule({
          facilityId: row.id,
          body: { inspectionCycleMonths: months },
        }),
        saveNotificationSettings({
          facilityId: row.id,
          body: { notifyBeforeEnabled, notifyBeforeDays, warnOnOverdueEnabled },
        }),
      ]);
      setNextDueAt(scheduleResult.nextInspectionDueAt);
    } catch {
      // 에러는 각 훅의 error → 상단 배너로 이미 노출됨. 여기선 unhandled rejection만 방지.
    }
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between gap-4">
        <span className="text-sm font-medium text-text-default">{row.name}</span>
        <Button
          variant="primary"
          size="sm"
          onClick={handleSave}
          disabled={isPending || isNotificationSettingsSaving}
        >
          {isPending || isNotificationSettingsSaving ? '저장 중...' : '저장'}
        </Button>
      </div>

      {(error || notificationSettingsError) && (
        <p role="alert" className="m-0 text-sm text-danger">
          {error?.message ?? notificationSettingsError?.message ?? '점검 주기 저장에 실패했습니다.'}
        </p>
      )}

      <InspectionCycleSettingsCard
        cycleType={cycleType}
        onCycleTypeChange={setCycleType}
        months={months}
        onMonthsChange={setMonths}
        lastInspectedAt={row.lastInspectedAt}
        nextInspectionDueAt={nextDueAt}
        notifyBeforeEnabled={notifyBeforeEnabled}
        onNotifyBeforeChange={setNotifyBeforeEnabled}
        warnOnOverdueEnabled={warnOnOverdueEnabled}
        onWarnOnOverdueChange={setWarnOnOverdueEnabled}
        today={today}
      />
    </div>
  );
}
