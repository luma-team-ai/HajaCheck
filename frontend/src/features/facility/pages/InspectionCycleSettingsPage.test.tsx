// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { InspectionCycleSettingsPage } from './InspectionCycleSettingsPage';
import { resetInspectionCycleStatusMockStore } from '../mocks/inspectionCycle.mock';

const mockSetSchedule = vi.fn();
vi.mock('../hooks/useSetInspectionSchedule', () => ({
  useSetInspectionSchedule: () => ({
    setSchedule: mockSetSchedule,
    isPending: false,
    error: null,
    resetError: vi.fn(),
  }),
}));

// 점검 알림 설정(#540 ③) — useInspectionCycleStatusRows/useSetInspectionSchedule과 동일하게 전부
// 목으로 대체한다(이 파일은 QueryClientProvider 없이 렌더하므로 실 useQuery/useMutation을 그대로
// 쓰면 "No QueryClient set" 에러가 난다).
const mockSaveNotificationSettings = vi.fn();
// 구현 함수 자체는 인자를 쓰지 않지만(고정 응답), 타입은 실 훅 시그니처와 맞춰 vi.fn<T>()로
// 명시한다 — 이름 있는 미사용 매개변수를 두면 no-unused-vars에 걸린다(이 프로젝트는
// argsIgnorePattern 미설정이라 `_` 접두사로 면제되지 않는다). Vitest는 구현 함수의 자체 인자
// 선언과 무관하게 실제 호출 인자를 mock.calls에 기록하므로 toHaveBeenCalledWith 검증엔 영향 없다.
const mockUseInspectionNotificationSettings = vi.fn<(facilityId: number) => { data: unknown }>(() => ({
  // warnOnOverdueEnabled 기본값은 true다(HAJA-498/V21).
  data: { notifyBeforeEnabled: true, notifyBeforeDays: 7, warnOnOverdueEnabled: true },
}));
vi.mock('../hooks/useInspectionNotificationSettings', () => ({
  useInspectionNotificationSettings: (facilityId: number) =>
    mockUseInspectionNotificationSettings(facilityId),
}));
vi.mock('../hooks/useSaveInspectionNotificationSettings', () => ({
  useSaveInspectionNotificationSettings: () => ({
    saveNotificationSettings: mockSaveNotificationSettings,
    isPending: false,
    error: null,
    resetError: vi.fn(),
  }),
}));

vi.mock('../hooks/useInspectionCycleStatusRows', () => ({
  useInspectionCycleStatusRows: () => ({
    data: [
      {
        id: 3,
        name: '오피스타워',
        type: '정기',
        cycleMonths: 6,
        lastInspectedAt: '2026-06-21',
        nextInspectionDueAt: '2026-12-21',
        assigneeName: '박책임',
      },
      {
        id: 4,
        name: '정밀동',
        type: '정밀',
        cycleMonths: 3,
        lastInspectedAt: '2026-05-01',
        nextInspectionDueAt: '2026-12-01',
        assigneeName: '이정밀',
      },
    ],
    isLoading: false,
    isError: false,
  }),
}));

function renderPage(initialEntry = '/facilities/inspection-cycle') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <InspectionCycleSettingsPage />
    </MemoryRouter>,
  );
}

// 기존 테스트 대부분은 "오피스타워(id=3)"를 이미 선택한 상태를 전제로 저장/토글 동작을 검증한다 —
// #1129 이후에는 자동 기본 선택이 없으므로, 각 테스트가 필요로 하는 화면 상태를 이 헬퍼로 만든다.
function selectOfficeTowerRow() {
  fireEvent.click(screen.getByText('오피스타워').closest('tr') as HTMLElement);
}

beforeEach(() => {
  vi.stubEnv('VITE_ENABLE_MSW', 'true');
  mockSaveNotificationSettings.mockResolvedValue({
    notifyBeforeEnabled: true,
    notifyBeforeDays: 7,
    warnOnOverdueEnabled: true,
  });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.unstubAllEnvs();
  resetInspectionCycleStatusMockStore();
});

describe('InspectionCycleSettingsPage', () => {
  // #1129 회귀 고정 — facilityId 쿼리파라미터 없이 진입해도(사이드메뉴 클릭과 동일 상황) 에러 없이
  // 렌더되고, 시설물이 선택되기 전까지는 어떤 facilityId로도 알림설정 조회 훅이 호출되지 않아야 한다
  // (이전에는 하드코딩된 DEFAULT_FACILITY_ID=3으로 즉시 실 백엔드 GET이 나가 FACILITY_NOT_FOUND가 났다).
  it('facilityId 없이 진입하면 에러 없이 선택 안내만 보이고 알림설정 조회는 호출되지 않는다', () => {
    renderPage();

    expect(screen.getByText('점검 주기를 설정할 시설물을 선택해주세요')).toBeTruthy();
    expect(screen.queryByText('이 시설물 주기 설정')).toBeNull();
    expect(screen.queryByRole('button', { name: '저장' })).toBeNull();
    expect(mockUseInspectionNotificationSettings).not.toHaveBeenCalled();
  });

  it('현황 테이블에서 시설물을 선택하면 그 시설물 id로 알림설정을 조회하고 설정 카드가 나타난다', () => {
    renderPage();

    selectOfficeTowerRow();

    expect(mockUseInspectionNotificationSettings).toHaveBeenCalledWith(3);
    expect(screen.getByText('이 시설물 주기 설정')).toBeTruthy();
    expect(screen.getByRole('button', { name: '저장' })).toBeTruthy();
  });

  it('유효하지 않은 facilityId로 진입해도(현재 목록에 없는 id) 에러 없이 선택 안내가 보인다', () => {
    renderPage('/facilities/inspection-cycle?facilityId=999');

    expect(screen.getByText('점검 주기를 설정할 시설물을 선택해주세요')).toBeTruthy();
    expect(mockUseInspectionNotificationSettings).not.toHaveBeenCalled();
  });

  it('facilityId 쿼리파라미터가 목록에 존재하면 그 시설물이 바로 선택된 채로 진입한다', () => {
    renderPage('/facilities/inspection-cycle?facilityId=4');

    expect(screen.getByText('이 시설물 주기 설정')).toBeTruthy();
    expect(mockUseInspectionNotificationSettings).toHaveBeenCalledWith(4);
  });

  it('저장 버튼을 누르면 선택된 시설물 id와 현재 개월 수로 저장 뮤테이션을 호출한다', async () => {
    mockSetSchedule.mockResolvedValue({ nextInspectionDueAt: '2027-01-20' });
    renderPage();
    selectOfficeTowerRow();

    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => {
      expect(mockSetSchedule).toHaveBeenCalledWith({
        facilityId: 3,
        body: { inspectionCycleMonths: 6 },
      });
    });
  });

  it('저장 성공 시 응답의 다음 점검일로 화면 표시가 갱신된다', async () => {
    mockSetSchedule.mockResolvedValue({ nextInspectionDueAt: '2027-01-20' });
    renderPage();
    selectOfficeTowerRow();

    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    expect(await screen.findByText('2027-01-20')).toBeTruthy();
  });

  it('데모 기준일(2026-11-09) 기준으로 선택한 시설물(다음점검 2026-12-21)의 뱃지를 "D-42"로 표시한다', () => {
    // 실제 오늘이 아니라 고정 데모 기준일로 상태를 파생하므로, 154일 뒤라 "여유"로만 보이던 문제 없이
    // Figma 디자인과 동일하게 D-42가 카드·현황 테이블 양쪽에 표시된다.
    renderPage();
    selectOfficeTowerRow();

    expect(screen.getAllByText('D-42').length).toBeGreaterThanOrEqual(1);
    expect(screen.queryByText('여유')).toBeNull();
  });

  it('+ 스테퍼로 개월 수를 올린 뒤 저장하면 변경된 개월 수로 호출된다', async () => {
    mockSetSchedule.mockResolvedValue({ nextInspectionDueAt: '2027-02-21' });
    renderPage();
    selectOfficeTowerRow();

    fireEvent.click(screen.getByLabelText('주기 1개월 증가'));
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => {
      expect(mockSetSchedule).toHaveBeenCalledWith({
        facilityId: 3,
        body: { inspectionCycleMonths: 7 },
      });
    });
  });

  it('다른 시설물(정밀) 행을 선택하면 세그먼트 토글이 그 행의 점검유형으로 동기화된다', async () => {
    // #462 P2: cycleType이 항상 '정기'로 초기화되던 문제 — 행 선택 시 실제 type과 맞춰져야 한다.
    renderPage();
    selectOfficeTowerRow();

    // 초기 선택행(id=3)은 '정기' — 세그먼트의 '정기' 버튼이 눌린 상태여야 한다.
    expect(screen.getByRole('button', { name: '정기' }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByRole('button', { name: '정밀' }).getAttribute('aria-pressed')).toBe('false');

    const targetRow = screen.getByText('정밀동').closest('tr');
    expect(targetRow).not.toBeNull();
    fireEvent.click(targetRow as HTMLElement);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '정밀' }).getAttribute('aria-pressed')).toBe('true');
    });
    expect(screen.getByRole('button', { name: '정기' }).getAttribute('aria-pressed')).toBe('false');
  });

  it('다른 시설물 행을 선택하면 개월수·다음점검일도 그 행의 값으로 리셋된다(key 리마운트)', async () => {
    // react-reviewer P3 — key={selectedRow.id} 리마운트가 cycleType뿐 아니라 months/nextDueAt도
    // 새 행 값으로 초기화하는지까지 함께 고정한다(오피스타워 6개월/2026-12-21 → 정밀동 3개월/2026-12-01).
    renderPage();
    selectOfficeTowerRow();
    expect(screen.getAllByText('2026-12-21').length).toBeGreaterThanOrEqual(1);

    fireEvent.click(screen.getByText('정밀동').closest('tr') as HTMLElement);

    await waitFor(() => {
      expect(screen.getAllByText('2026-12-01').length).toBeGreaterThanOrEqual(1);
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => {
      expect(mockSetSchedule).toHaveBeenCalledWith({
        facilityId: 4,
        body: { inspectionCycleMonths: 3 },
      });
    });
  });

  it('저장 뮤테이션이 실패해도 unhandled rejection 없이 클릭이 정상 처리된다', async () => {
    // #462 P2: handleSave에 try/catch가 없으면 reject 시 onClick 내부에서 unhandled rejection이 발생한다.
    mockSetSchedule.mockRejectedValue(new Error('저장 실패'));
    renderPage();
    selectOfficeTowerRow();

    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => {
      expect(mockSetSchedule).toHaveBeenCalledWith({
        facilityId: 3,
        body: { inspectionCycleMonths: 6 },
      });
    });
    // 여기까지 예외 없이 도달하면(reject가 테스트를 깨지 않으면) 통과.
  });
});
