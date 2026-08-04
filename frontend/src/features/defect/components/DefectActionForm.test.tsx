// @vitest-environment jsdom
// 조치 결과 등록 폼 — 업로드 드롭존 미리보기(#969) 단위 테스트. QueryClientProvider + MSW로
// useDefectAssignableUsers/useUploadDefectActionPhoto/useSubmitDefectAction의 실제 요청 경로를
// DefectActionBoard.test.tsx와 동일하게 재사용한다(defectApi.handlers.ts 단일 소스).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { defectHandlers } from '../api/defectApi.handlers';
import { defectMediaApi } from '../api/defectMediaApi';
import { mockDefects } from '../mocks/defect.mock';
import { DefectActionForm } from './DefectActionForm';
import type { Defect, DefectStatus } from '../types';

const server = setupServer(...defectHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

// jsdom은 URL.createObjectURL/revokeObjectURL을 구현하지 않으므로 직접 스텁한다
// (FacilityPhotoUploadField.test.tsx:6-18과 동일 패턴).
let createObjectURLMock: ReturnType<typeof vi.fn>;
let revokeObjectURLMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  let counter = 0;
  createObjectURLMock = vi.fn(() => `blob:mock-${counter++}`);
  revokeObjectURLMock = vi.fn();
  URL.createObjectURL = createObjectURLMock as unknown as typeof URL.createObjectURL;
  URL.revokeObjectURL = revokeObjectURLMock as unknown as typeof URL.revokeObjectURL;
});

function makeImageFile(name: string): File {
  return new File(['fake-image-bytes'], name, { type: 'image/png' });
}

function makeDefect(status: DefectStatus, overrides: Partial<Defect> = {}): Defect {
  return { ...mockDefects[0], id: 1, inspectionId: 101, status, actionResult: null, ...overrides };
}

function renderForm(status: DefectStatus = 'CONFIRMED') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <DefectActionForm defect={makeDefect(status)} actionResult={null} />
    </QueryClientProvider>,
  );
}

describe('DefectActionForm — 업로드 드롭존 미리보기', () => {
  it('파일을 선택하면 미리보기 이미지와 파일명 칩이 렌더된다', () => {
    renderForm();

    const input = screen.getByLabelText('조치 후 사진 업로드');
    fireEvent.change(input, { target: { files: [makeImageFile('after.png')] } });

    const preview = screen.getByAltText('조치 후 사진 미리보기') as HTMLImageElement;
    expect(preview).not.toBeNull();
    expect(preview.src).toContain('blob:mock-0');
    expect(screen.getByText('after.png')).not.toBeNull();
    expect(createObjectURLMock).toHaveBeenCalledTimes(1);
    // 안내 아이콘/문구는 미리보기 상태에서 사라져야 한다.
    expect(screen.queryByText('파일을 드래그하거나 클릭하여 업로드')).toBeNull();
  });

  it('제거(X) 버튼 클릭 시 미리보기가 사라지고 원래 안내 문구로 돌아오며, 드롭존의 파일선택창 재오픈은 발생하지 않는다', () => {
    renderForm();

    const input = screen.getByLabelText('조치 후 사진 업로드') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [makeImageFile('after.png')] } });
    expect(screen.getByAltText('조치 후 사진 미리보기')).not.toBeNull();

    const clickSpy = vi.spyOn(HTMLInputElement.prototype, 'click');

    fireEvent.click(screen.getByRole('button', { name: '선택한 사진 제거' }));

    expect(screen.queryByAltText('조치 후 사진 미리보기')).toBeNull();
    expect(screen.getByText('파일을 드래그하거나 클릭하여 업로드')).not.toBeNull();
    // 드롭존 div의 onClick(fileInputRef.current?.click())이 재발화하면 안 된다(stopPropagation 검증).
    expect(clickSpy).not.toHaveBeenCalled();
    // 같은 파일을 다시 선택해도 onChange가 재발화하도록 input value가 비워져야 한다.
    expect(input.value).toBe('');

    clickSpy.mockRestore();
  });

  it('언마운트 시 생성된 objectURL을 revoke한다', () => {
    const { unmount } = render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <DefectActionForm defect={makeDefect('CONFIRMED')} actionResult={null} />
      </QueryClientProvider>,
    );

    const input = screen.getByLabelText('조치 후 사진 업로드');
    fireEvent.change(input, { target: { files: [makeImageFile('after.png')] } });
    expect(revokeObjectURLMock).not.toHaveBeenCalled();

    unmount();

    expect(revokeObjectURLMock).toHaveBeenCalledWith('blob:mock-0');
  });
});

describe('DefectActionForm — actionResult 등록 완료 상태(회귀 확인)', () => {
  it('RESOLVED이고 actionResult가 있으면 읽기 전용 요약을 렌더링하고 드롭존은 렌더링하지 않는다', () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <DefectActionForm
          defect={makeDefect('RESOLVED')}
          actionResult={{
            actionContent: '에폭시 주입 처리',
            actionDate: '2026-07-20',
            assigneeId: 1,
            assigneeName: '홍길동',
            afterPhotoUrl: '/api/media/999/thumbnail',
          }}
        />
      </QueryClientProvider>,
    );

    expect(screen.getByText('에폭시 주입 처리')).not.toBeNull();
    expect(screen.queryByLabelText('조치 후 사진 업로드')).toBeNull();
  });

  // HAJA-26 3차(#1556) — RESOLVED도 더 이상 이탈 불가한 종료 상태가 아니다. 조치 필드는 읽기
  // 전용으로 유지하되, "다른 상태로 변경" 통합으로 진행상태 select만 남겨 사유와 함께 되돌릴 수
  // 있게 한다(DefectStatusChangeControl이 담당하던 것을 흡수).
  it('RESOLVED에서도 진행상태 select로 IN_PROGRESS로 되돌릴 수 있고, 선택 즉시 사유 모달이 열린다', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <DefectActionForm
          defect={makeDefect('RESOLVED')}
          actionResult={{
            actionContent: '에폭시 주입 처리',
            actionDate: '2026-07-20',
            assigneeId: 1,
            assigneeName: '홍길동',
            afterPhotoUrl: '/api/media/999/thumbnail',
          }}
        />
      </QueryClientProvider>,
    );

    const select = screen.getByLabelText('진행상태 *') as HTMLSelectElement;
    expect(select.value).toBe('RESOLVED');
    expect(Array.from(select.options).map((option) => option.textContent)).toEqual([
      '조치완료',
      '조치중(으)로 되돌리기',
    ]);

    fireEvent.change(select, { target: { value: 'IN_PROGRESS' } });

    expect(await screen.findByRole('dialog', { name: '상태 변경 사유 입력' })).not.toBeNull();
  });

  // #1128 회귀 방지 — CONFIRMED→IN_PROGRESS 등록 직후에도 actionResult가 채워지지만, IN_PROGRESS는
  // 아직 종료 상태가 아니므로(RESOLVED로 한 번 더 전이 필요) 읽기 전용 요약이 아니라 폼이 계속
  // 보여야 한다.
  it('IN_PROGRESS이고 actionResult가 있어도 폼을 계속 렌더링한다(종료 상태 아님)', () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <DefectActionForm
          defect={makeDefect('IN_PROGRESS')}
          actionResult={{
            actionContent: '균열 부위 실측 완료',
            actionDate: '2026-07-24',
            assigneeId: 1,
            assigneeName: '홍길동',
            afterPhotoUrl: '/api/media/998/thumbnail',
          }}
        />
      </QueryClientProvider>,
    );

    expect(screen.getByLabelText('조치 후 사진 업로드')).not.toBeNull();
    expect(screen.queryByText('에폭시 주입 처리')).toBeNull();
  });
});

describe('DefectActionForm — 진행상태 select(#1128, "다른 상태로 변경" 통합 #1556)', () => {
  it('CONFIRMED 상태에서는 정방향(조치중) 옵션이 기본 선택되고, 역행 옵션(신규/조치완료로 되돌리기)도 함께 노출된다', () => {
    renderForm('CONFIRMED');

    const select = screen.getByLabelText('진행상태 *') as HTMLSelectElement;
    expect(select.value).toBe('IN_PROGRESS');
    // 역행 옵션이 함께 있으므로 더 이상 disabled 고정 select가 아니다(#1556).
    expect(select.disabled).toBe(false);
    const optionLabels = Array.from(select.options).map((option) => option.textContent);
    expect(optionLabels).toEqual(['조치중', '신규(으)로 되돌리기', '조치완료(으)로 되돌리기']);
  });

  // #1193/HAJA-569 — IN_PROGRESS 단계에서 조치중 사진을 시간차를 두고 여러 번 등록할 수 있어야
  // 하므로, 더 이상 "조치완료"로 고정되지 않고 두 옵션 중 사용자가 고른다. 기본값은 실수로 바로
  // 완료 처리되지 않도록 "조치중"(유지)이다.
  it('IN_PROGRESS 상태에서는 정방향(조치중/조치완료) 옵션과 역행 옵션(신규/검수확정으로 되돌리기)이 함께 노출되며 기본값은 "조치중"이다', () => {
    renderForm('IN_PROGRESS');

    const select = screen.getByLabelText('진행상태 *') as HTMLSelectElement;
    expect(select.disabled).toBe(false);
    expect(select.value).toBe('IN_PROGRESS');
    const optionLabels = Array.from(select.options).map((option) => option.textContent);
    expect(optionLabels).toEqual([
      '조치중',
      '조치완료',
      '신규(으)로 되돌리기',
      '검수확정(으)로 되돌리기',
    ]);
  });

  it('역행 옵션을 선택하면 사유 입력 모달이 열리고, 사진/내용 등 조치 필드는 그대로 유지된다', async () => {
    renderForm('CONFIRMED');

    fireEvent.change(screen.getByLabelText('조치 내용'), { target: { value: '유지될 조치 내용' } });
    fireEvent.change(screen.getByLabelText('진행상태 *'), { target: { value: 'DETECTED' } });

    expect(await screen.findByRole('dialog', { name: '상태 변경 사유 입력' })).not.toBeNull();
    expect((screen.getByLabelText('조치 내용') as HTMLTextAreaElement).value).toBe('유지될 조치 내용');
  });

  it('사유를 제출하면 PATCH /status가 호출되고 모달이 닫힌다', async () => {
    let capturedBody: Record<string, unknown> | null = null;
    server.use(
      http.patch('/api/defects/:id/status', async ({ request }) => {
        capturedBody = (await request.clone().json()) as Record<string, unknown>;
        return HttpResponse.json({
          success: true,
          data: { ...makeDefect('CONFIRMED'), status: 'DETECTED' },
        });
      }),
    );

    renderForm('CONFIRMED');
    fireEvent.change(screen.getByLabelText('진행상태 *'), { target: { value: 'DETECTED' } });
    await screen.findByRole('dialog', { name: '상태 변경 사유 입력' });

    fireEvent.change(screen.getByLabelText('사유'), { target: { value: '오탐 재확인 필요' } });
    fireEvent.click(screen.getByRole('button', { name: '확인' }));

    await waitFor(() => expect(capturedBody).not.toBeNull());
    expect((capturedBody as unknown as { status: string; reason: string }).status).toBe('DETECTED');
    expect((capturedBody as unknown as { status: string; reason: string }).reason).toBe('오탐 재확인 필요');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '상태 변경 사유 입력' })).toBeNull());
  });

  it('IN_PROGRESS 상태에서 "조치완료"를 선택해 제출하면 요청 바디의 targetStatus가 RESOLVED가 된다', async () => {
    const uploadSpy = vi
      .spyOn(defectMediaApi, 'uploadActionPhoto')
      .mockResolvedValue({ data: [{ id: 9002, thumbnailUrl: '/api/media/9002/thumbnail' }] } as Awaited<
        ReturnType<typeof defectMediaApi.uploadActionPhoto>
      >);

    let capturedBody: Record<string, unknown> | null = null;
    const listener = async ({ request }: { request: Request }) => {
      if (request.method === 'PATCH' && request.url.includes('/action')) {
        capturedBody = (await request.clone().json()) as Record<string, unknown>;
      }
    };
    server.events.on('request:start', listener);

    renderForm('IN_PROGRESS');
    fireEvent.change(screen.getByLabelText('진행상태 *'), { target: { value: 'RESOLVED' } });
    fireEvent.change(screen.getByLabelText('조치 후 사진 업로드'), {
      target: { files: [makeImageFile('resolved.png')] },
    });
    fireEvent.change(screen.getByLabelText('조치 내용'), { target: { value: '조치 완료 처리' } });
    fireEvent.change(screen.getByLabelText('조치일'), { target: { value: '2026-07-29' } });
    await screen.findByText('김도현 검사자');
    fireEvent.change(screen.getByLabelText('담당자'), { target: { value: '101' } });

    fireEvent.click(screen.getByRole('button', { name: '상태 저장' }));

    await waitFor(() => expect(capturedBody).not.toBeNull());
    expect((capturedBody as unknown as Record<string, unknown>).targetStatus).toBe('RESOLVED');

    server.events.removeListener('request:start', listener);
    uploadSpy.mockRestore();
  });

  // DETECTED(신규, 검수 전)는 조치 등록 대상이 아니므로 패널 자체가 렌더링되지 않아야 한다(방어적 가드).
  it('DETECTED 상태에서는 폼 자체를 렌더링하지 않는다', () => {
    const { container } = renderForm('DETECTED');

    expect(container.firstChild).toBeNull();
  });

  it('제출 시 현재 상태에서 유효한 targetStatus가 요청 바디에 포함된다', async () => {
    // 실 axios→MSW 네트워크 경로로 File/FormData를 태우면 jsdom File과 Node undici의 multipart
    // 파서가 호환되지 않아 테스트가 깨진다(InspectionDefectsPage.test.tsx와 동일 패턴) — 업로드
    // API 자체는 spy로 우회하고, 이 테스트는 PATCH /action 요청 바디의 targetStatus에 집중한다.
    const uploadSpy = vi
      .spyOn(defectMediaApi, 'uploadActionPhoto')
      .mockResolvedValue({ data: [{ id: 9001, thumbnailUrl: '/api/media/9001/thumbnail' }] } as Awaited<
        ReturnType<typeof defectMediaApi.uploadActionPhoto>
      >);

    let capturedBody: Record<string, unknown> | null = null;
    const listener = async ({ request }: { request: Request }) => {
      if (request.method === 'PATCH' && request.url.includes('/action')) {
        capturedBody = (await request.clone().json()) as Record<string, unknown>;
      }
    };
    server.events.on('request:start', listener);

    renderForm('CONFIRMED');
    fireEvent.change(screen.getByLabelText('조치 후 사진 업로드'), {
      target: { files: [makeImageFile('after.png')] },
    });
    fireEvent.change(screen.getByLabelText('조치 내용'), { target: { value: '조치중 실측 완료' } });
    fireEvent.change(screen.getByLabelText('조치일'), { target: { value: '2026-07-28' } });
    await screen.findByText('김도현 검사자');
    fireEvent.change(screen.getByLabelText('담당자'), { target: { value: '101' } });

    fireEvent.click(screen.getByRole('button', { name: '상태 저장' }));

    await waitFor(() => expect(capturedBody).not.toBeNull());
    expect((capturedBody as unknown as Record<string, unknown>).targetStatus).toBe('IN_PROGRESS');

    server.events.removeListener('request:start', listener);
    uploadSpy.mockRestore();
  });
});

// 이미지 단위 보수 작업 그룹 팬아웃(v0.2, #1456/#1457) — 백엔드가 groupSize>1로 응답하면 같은
// 이미지의 다른 하자들도 함께 갱신됐음을 성공 문구에 덧붙인다.
describe('DefectActionForm — 그룹 팬아웃 성공 안내', () => {
  it('응답 groupSize가 1보다 크면 성공 문구에 그룹 반영 건수를 덧붙인다', async () => {
    server.use(
      http.patch('/api/defects/:id/action', () =>
        HttpResponse.json({
          success: true,
          data: {
            id: 1,
            inspectionId: 101,
            status: 'IN_PROGRESS',
            groupSize: 3,
            groupStatus: 'IN_PROGRESS',
          },
        }),
      ),
    );
    const uploadSpy = vi
      .spyOn(defectMediaApi, 'uploadActionPhoto')
      .mockResolvedValue({ data: [{ id: 9003, thumbnailUrl: '/api/media/9003/thumbnail' }] } as Awaited<
        ReturnType<typeof defectMediaApi.uploadActionPhoto>
      >);

    renderForm('CONFIRMED');
    fireEvent.change(screen.getByLabelText('조치 후 사진 업로드'), {
      target: { files: [makeImageFile('after.png')] },
    });
    fireEvent.change(screen.getByLabelText('조치 내용'), { target: { value: '그룹 조치' } });
    fireEvent.change(screen.getByLabelText('조치일'), { target: { value: '2026-07-28' } });
    await screen.findByText('김도현 검사자');
    fireEvent.change(screen.getByLabelText('담당자'), { target: { value: '101' } });

    fireEvent.click(screen.getByRole('button', { name: '상태 저장' }));

    expect(await screen.findByText(/같은 이미지의 하자 3건에 함께 반영됨/)).not.toBeNull();

    uploadSpy.mockRestore();
  });

  it('응답 groupSize가 1(단독 하자)이면 그룹 반영 문구를 덧붙이지 않는다', async () => {
    const uploadSpy = vi
      .spyOn(defectMediaApi, 'uploadActionPhoto')
      .mockResolvedValue({ data: [{ id: 9004, thumbnailUrl: '/api/media/9004/thumbnail' }] } as Awaited<
        ReturnType<typeof defectMediaApi.uploadActionPhoto>
      >);

    renderForm('CONFIRMED');
    fireEvent.change(screen.getByLabelText('조치 후 사진 업로드'), {
      target: { files: [makeImageFile('after.png')] },
    });
    fireEvent.change(screen.getByLabelText('조치 내용'), { target: { value: '단독 조치' } });
    fireEvent.change(screen.getByLabelText('조치일'), { target: { value: '2026-07-28' } });
    await screen.findByText('김도현 검사자');
    fireEvent.change(screen.getByLabelText('담당자'), { target: { value: '101' } });

    fireEvent.click(screen.getByRole('button', { name: '상태 저장' }));

    await screen.findByText(/저장되었습니다/);
    expect(screen.queryByText(/함께 반영됨/)).toBeNull();

    uploadSpy.mockRestore();
  });
});

describe('DefectActionForm — 조치일 범위', () => {
  it('조치일은 로컬 날짜 기준 오늘까지만 선택할 수 있다', () => {
    renderForm();

    const today = new Date();
    const expectedMax = [
      today.getFullYear(),
      String(today.getMonth() + 1).padStart(2, '0'),
      String(today.getDate()).padStart(2, '0'),
    ].join('-');
    const actionDateInput = screen.getByLabelText('조치일') as HTMLInputElement;

    expect(actionDateInput.max).toBe(expectedMax);
  });

  it('미래 조치일은 직접 입력해도 입력값으로 반영되지 않는다', () => {
    renderForm();

    const future = new Date();
    future.setDate(future.getDate() + 1);
    const futureDate = [
      future.getFullYear(),
      String(future.getMonth() + 1).padStart(2, '0'),
      String(future.getDate()).padStart(2, '0'),
    ].join('-');

    const actionDateInput = screen.getByLabelText('조치일') as HTMLInputElement;
    fireEvent.change(actionDateInput, { target: { value: futureDate } });

    expect(actionDateInput.value).toBe('');
  });
});
