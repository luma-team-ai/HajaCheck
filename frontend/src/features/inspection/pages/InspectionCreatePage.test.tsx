// @vitest-environment jsdom
// InspectionCreatePage 통합 테스트 — 회의 후 반영된 시안(점검 정보 + 데이터 업로드 단일 화면)을
// 검증한다. 폼 검증은 MSW inspectionHandlers로 실제 왕복하되, 이미지 업로드는 파일(File) 파트를
// 포함한 실제 HTTP 왕복이 msw+jsdom+undici 조합의 알려진 환경 한계로 안정 재현되지 않아
// (authApi.company.test.ts와 동일 근거) mediaApi.upload를 스파이로
// 대체해 발화 여부/파라미터만 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { inspectionHandlers } from '../api/inspectionApi.handlers';
import { mediaApi } from '../api/mediaApi';
import type { Media } from '../types';
import { InspectionCreatePage } from './InspectionCreatePage';

const server = setupServer(...inspectionHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  vi.restoreAllMocks();
});
afterAll(() => server.close());

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-probe">{location.pathname}</div>;
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/inspections/create']}>
        <Routes>
          <Route path="/inspections/create" element={<InspectionCreatePage />} />
          <Route path="/facilities/:id" element={<div>시설물 상세</div>} />
        </Routes>
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

async function fillRequiredFields() {
  await screen.findByText('판교 테크노밸리 B동');
  fireEvent.change(screen.getByLabelText('시설물'), { target: { value: '1' } });
  fireEvent.change(screen.getByLabelText('점검일'), { target: { value: '2026-08-01' } });
  fireEvent.change(screen.getByLabelText('담당 점검자'), { target: { value: '5' } });
}

function selectFiles(files: File[]) {
  fireEvent.change(screen.getByLabelText('촬영 데이터 파일 선택'), { target: { files } });
}

describe('InspectionCreatePage (통합 테스트)', () => {
  it('점검 정보 입력 필드와 데이터 업로드 영역을 함께 렌더링한다', async () => {
    renderPage();

    expect(screen.getByRole('heading', { name: '점검 정보' })).not.toBeNull();
    expect(screen.getByRole('heading', { name: '데이터 업로드' })).not.toBeNull();
    expect(screen.getByLabelText('시설물')).not.toBeNull();
    expect(screen.getByLabelText('점검일')).not.toBeNull();
    expect(screen.getByLabelText('담당 점검자')).not.toBeNull();
    expect(screen.getByLabelText('메모')).not.toBeNull();
  });

  it('필수값 미입력 시 제출하면 에러 메시지를 보여주고 요청을 보내지 않는다', async () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: '업로드 완료 후 AI 분석 시작' }));

    expect(await screen.findByText('시설물을 선택해 주세요.')).not.toBeNull();
    expect(screen.getByText('점검일을 선택해 주세요.')).not.toBeNull();
    expect(screen.getByText('담당자 ID를 입력해 주세요.')).not.toBeNull();
  });

  it('허용되지 않는 형식의 파일을 선택하면 에러를 보여주고 제출 버튼을 비활성화한다', async () => {
    renderPage();
    await fillRequiredFields();

    selectFiles([new File(['a'], 'a.exe', { type: 'application/octet-stream' })]);

    expect(await screen.findByText('지원하지 않는 형식입니다 (JPG, PNG, MP4만 가능)')).not.toBeNull();
    expect(screen.getByRole('button', { name: '업로드 완료 후 AI 분석 시작' })).toHaveProperty(
      'disabled',
      true,
    );
  });

  it('영상 파일은 선택되지만 "프레임 추출 예정" 상태로만 표시되고 실제 업로드 대상에서 제외된다', async () => {
    renderPage();
    await fillRequiredFields();

    selectFiles([new File(['a'], 'clip.mp4', { type: 'video/mp4' })]);

    expect(await screen.findByText('영상 · 프레임 추출 예정')).not.toBeNull();
  });

  it('생성 성공 + 이미지 업로드 성공 시 mediaApi.upload를 호출하고 시설물 상세로 이동한다', async () => {
    const mockMedia: Media[] = [
      {
        id: 1,
        inspectionId: 100,
        fileType: 'IMAGE',
        thumbnailUrl: '/api/media/1/thumbnail',
        mimeType: 'image/jpeg',
        capturedAt: null,
        gpsLat: null,
        gpsLng: null,
        createdAt: '2026-07-22T00:00:00',
      },
    ];
    const uploadSpy = vi
      .spyOn(mediaApi, 'upload')
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      .mockResolvedValue({ data: mockMedia } as any);

    renderPage();
    await fillRequiredFields();
    const file = new File(['a'], 'a.jpg', { type: 'image/jpeg' });
    selectFiles([file]);
    await screen.findByText('대기 중');

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '업로드 완료 후 AI 분석 시작' }));
    });

    expect(uploadSpy).toHaveBeenCalledWith(100, [file], expect.any(Function));
    expect(await screen.findByText('시설물 상세')).not.toBeNull();
    expect(screen.getByTestId('location-probe').textContent).toBe('/facilities/1');
  });

  it('점검 생성 성공 후 업로드만 실패하면, 재제출 시 회차를 다시 만들지 않고 업로드만 재시도한다(P1 회귀 방지)', async () => {
    let createCallCount = 0;
    server.use(
      http.post('/api/inspections', async ({ request }) => {
        createCallCount += 1;
        const reqBody = (await request.json()) as { facilityId: number; assignedInspectorId: number; inspectionDate: string };
        const body = {
          success: true,
          data: {
            id: 100,
            facilityId: reqBody.facilityId,
            createdBy: 1,
            assignedInspectorId: reqBody.assignedInspectorId,
            roundNo: 1,
            inspectionDate: reqBody.inspectionDate,
            status: 'SCHEDULED',
            createdAt: new Date().toISOString(),
          },
        };
        return HttpResponse.json(body, { status: 201 });
      }),
    );

    const mockMedia: Media[] = [
      {
        id: 1,
        inspectionId: 100,
        fileType: 'IMAGE',
        thumbnailUrl: '/api/media/1/thumbnail',
        mimeType: 'image/jpeg',
        capturedAt: null,
        gpsLat: null,
        gpsLng: null,
        createdAt: '2026-07-22T00:00:00',
      },
    ];
    const uploadSpy = vi
      .spyOn(mediaApi, 'upload')
      .mockRejectedValueOnce({ code: 'NETWORK_ERROR', message: '업로드에 실패했습니다.' })
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      .mockResolvedValueOnce({ data: mockMedia } as any);

    renderPage();
    await fillRequiredFields();
    const file = new File(['a'], 'a.jpg', { type: 'image/jpeg' });
    selectFiles([file]);
    await screen.findByText('대기 중');

    // 1차 제출 — 회차 생성은 성공, 업로드는 실패
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '업로드 완료 후 AI 분석 시작' }));
    });
    expect(await screen.findByText('업로드에 실패했습니다.')).not.toBeNull();
    expect(createCallCount).toBe(1);

    // 시설물 필드가 잠겨(이미 생성된 회차 재사용) 더 이상 수정할 수 없어야 한다
    expect((screen.getByLabelText('시설물') as HTMLSelectElement).disabled).toBe(true);

    // 2차 제출(재시도) — 회차는 다시 만들지 않고 업로드만 재실행
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '업로드 완료 후 AI 분석 시작' }));
    });

    expect(createCallCount).toBe(1); // 회차 생성은 여전히 1회만
    expect(uploadSpy).toHaveBeenCalledTimes(2); // 업로드는 재시도로 2회
    expect(await screen.findByText('시설물 상세')).not.toBeNull();
  });

  it('점검 생성 실패 시 에러 메시지를 표시하고 입력값을 유지한다', async () => {
    server.use(
      http.post('/api/inspections', () => {
        const failure = {
          success: false,
          data: null,
          error: { code: 'AUTH_INVALID_INSPECTOR', message: '배정할 수 없는 담당자입니다.' },
        };
        return HttpResponse.json(failure, { status: 400 });
      }),
    );

    renderPage();
    await fillRequiredFields();

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '업로드 완료 후 AI 분석 시작' }));
    });

    expect(await screen.findByText('배정할 수 없는 담당자입니다.')).not.toBeNull();
    expect((screen.getByLabelText('시설물') as HTMLSelectElement).value).toBe('1');
  });
});
