// @vitest-environment jsdom
// InspectionCreatePage 통합 테스트 — 회의 후 반영된 시안(점검 정보 + 데이터 업로드 단일 화면)을
// 검증한다. 폼 검증은 MSW inspectionHandlers로 실제 왕복하되, 이미지 업로드는 파일(File) 파트를
// 포함한 실제 HTTP 왕복이 msw+jsdom+undici 조합의 알려진 환경 한계로 안정 재현되지 않아
// (authApi.company.test.ts와 동일 근거) mediaApi.upload를 스파이로
// 대체해 발화 여부/파라미터만 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { createMemoryRouter, Link, RouterProvider } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../auth/store/authStore';
import { inspectionHandlers } from '../api/inspectionApi.handlers';
import { mediaApi } from '../api/mediaApi';
import type { Media } from '../types';
import { todayDateString } from '../utils/validateInspectionCreateForm';
import {
  clearDraftMediaFiles,
  saveDraftMediaFiles,
} from '../utils/inspectionCreateDraftFiles';
import { saveInspectionCreateDraft } from '../utils/inspectionCreateDraft';
import { InspectionCreatePage } from './InspectionCreatePage';

const DRAFT_KEY = 'hajacheckInspectionCreateDraft';

// jsdom엔 기본적으로 indexedDB가 없어(fake-indexeddb 전역 폴리필 미설정) 실제 구현을 그대로 쓰면
// openDb()가 조용히 실패해(자체 try/catch로 삼킴) 호출 여부를 관찰할 수 없다. PR머신 리뷰 P2 —
// 언마운트 flush 회귀 테스트를 위해 이 모듈만 스파이 가능한 목으로 교체한다.
vi.mock('../utils/inspectionCreateDraftFiles', () => ({
  saveDraftMediaFiles: vi.fn().mockResolvedValue(undefined),
  loadDraftMediaFiles: vi.fn().mockResolvedValue([]),
  clearDraftMediaFiles: vi.fn().mockResolvedValue(undefined),
}));

const server = setupServer(...inspectionHandlers);

// 담당 점검자는 더 이상 폼 입력이 아니라 로그인한 본인(useAuthStore)으로 자동 배정된다 —
// 페이지가 currentUser.id를 읽으므로 렌더 전에 스토어를 채워둬야 한다.
const MOCK_CURRENT_USER = {
  id: 5,
  email: 'inspector@example.com',
  name: '테스트 점검자',
  role: 'INSPECTOR' as const,
  companyId: 1,
  profileImageUrl: null,
  createdAt: '2026-07-01T00:00:00',
  companyName: '테스트회사',
  status: 'ACTIVE' as const,
};

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
beforeEach(() => {
  useAuthStore.setState({ user: MOCK_CURRENT_USER });
});
afterEach(() => {
  server.resetHandlers();
  cleanup();
  vi.restoreAllMocks();
  useAuthStore.setState({ user: null });
  localStorage.clear();
});
afterAll(() => server.close());

// useBlocker(react-router-dom)는 data router(createMemoryRouter/RouterProvider) 컨텍스트
// 안에서만 동작한다 — MemoryRouter+Routes(비-data router)로 렌더하면 렌더 시점에 크래시한다
// (같은 레포의 AppShellRoute.test.tsx/authFlow.logout.test.tsx와 동일 패턴으로 교체).
// "/dashboard-link"는 실제 사이드바 Link 클릭을 대역하는 테스트 전용 라우트 — useBlocker는
// 어떤 컴포넌트가 이동을 시작했는지 상관하지 않고 라우터 내부 이동 자체를 가로채므로,
// 실제 SideNavBar Link 클릭과 동등하게 검증할 수 있다.
function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });

  const router = createMemoryRouter(
    [
      {
        path: '/inspections/create',
        element: (
          <>
            <Link to="/dashboard">대시보드로 이동(사이드바 대역)</Link>
            <InspectionCreatePage />
          </>
        ),
      },
      { path: '/inspections/:id/analysis', element: <div>AI 분석 실행/상태</div> },
      { path: '/dashboard', element: <div>대시보드 페이지</div> },
    ],
    { initialEntries: ['/inspections/create'] },
  );

  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );

  return router;
}

async function fillRequiredFields() {
  await screen.findByText('판교 테크노밸리 B동');
  fireEvent.change(screen.getByLabelText('시설물'), { target: { value: '1' } });
  fireEvent.change(screen.getByLabelText('점검일'), { target: { value: todayDateString() } });
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
    expect(screen.getByLabelText('메모')).not.toBeNull();
  });

  it('시설물·점검일·업로드 이미지 중 하나라도 비어 있으면 제출 버튼이 비활성 상태를 유지한다', async () => {
    renderPage();
    const submitButton = screen.getByRole('button', { name: '업로드 완료 후 AI 분석 시작' });

    // 아무 것도 입력하지 않은 초기 상태
    expect(submitButton).toHaveProperty('disabled', true);

    // 시설물·점검일만 채운 상태(파일 없음)
    await fillRequiredFields();
    expect(submitButton).toHaveProperty('disabled', true);

    // 영상 파일만 첨부 — 업로드 대상(이미지)이 아니므로 여전히 비활성이어야 한다(PR 리뷰 P2 회귀 방지)
    selectFiles([new File(['a'], 'clip.mp4', { type: 'video/mp4' })]);
    expect(submitButton).toHaveProperty('disabled', true);

    // 업로드 대상 이미지를 추가하면 비로소 활성화된다
    selectFiles([new File(['a'], 'a.jpg', { type: 'image/jpeg' })]);
    expect(submitButton).toHaveProperty('disabled', false);
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

  it('생성 성공 + 이미지 업로드 성공 시 mediaApi.upload를 호출하고 AI 분석 실행/상태로 이동한다', async () => {
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

    const router = renderPage();
    await fillRequiredFields();
    const file = new File(['a'], 'a.jpg', { type: 'image/jpeg' });
    selectFiles([file]);
    await screen.findByText('대기 중');

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '업로드 완료 후 AI 분석 시작' }));
    });

    expect(uploadSpy).toHaveBeenCalledWith(100, [file], expect.any(Function));
    expect(await screen.findByText('AI 분석 실행/상태')).not.toBeNull();
    expect(router.state.location.pathname).toBe('/inspections/100/analysis');
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
    expect(await screen.findByText('AI 분석 실행/상태')).not.toBeNull();
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
    selectFiles([new File(['a'], 'a.jpg', { type: 'image/jpeg' })]);

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '업로드 완료 후 AI 분석 시작' }));
    });

    expect(await screen.findByText('배정할 수 없는 담당자입니다.')).not.toBeNull();
    expect((screen.getByLabelText('시설물') as HTMLSelectElement).value).toBe('1');
  });

  it('같은 시설물에 이미 진행 중인 회차가 있으면 확인창을 띄우고, 취소하면 회차를 생성하지 않는다', async () => {
    let createCallCount = 0;
    server.use(
      http.get('/api/inspections', () => {
        const body = {
          success: true,
          // ANALYZED — 아직 검수도 안 끝난 회차라야 "진행 중" 경고 대상이다. REVIEWED는 페이즈8부터
          // "점검 요약" 진입 시 이미 확정되므로 더 이상 이 경고 대상이 아니다(InspectionCreatePage
          // findActiveRound).
          data: { content: [{ id: 900, roundNo: 2, status: 'ANALYZED' }], page: 0, totalElements: 1 },
        };
        return HttpResponse.json(body);
      }),
      http.post('/api/inspections', () => {
        createCallCount += 1;
        return HttpResponse.json({ success: true, data: null });
      }),
    );

    renderPage();
    await fillRequiredFields();
    selectFiles([new File(['a'], 'a.jpg', { type: 'image/jpeg' })]);

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '업로드 완료 후 AI 분석 시작' }));
    });

    expect(
      await screen.findByText('이미 진행 중인 2회차가 있습니다. 이어서 진행하시겠습니까, 새 회차를 만드시겠습니까?'),
    ).not.toBeNull();
    expect(createCallCount).toBe(0);

    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    await waitFor(() =>
      expect(
        screen.queryByText('이미 진행 중인 2회차가 있습니다. 이어서 진행하시겠습니까, 새 회차를 만드시겠습니까?'),
      ).toBeNull(),
    );
    expect(createCallCount).toBe(0);
    // 취소 후에도 계속 편집 가능해야 한다(회차를 만들지 않았으므로 잠기지 않음)
    expect((screen.getByLabelText('시설물') as HTMLSelectElement).disabled).toBe(false);
  });

  // 페이즈8 회귀 가드 — REVIEWED는 "점검 요약" 진입 시 이미 확정된 회차라, 최종 보고서(REPORTED)
  // 전이 전이라도 더 이상 "진행 중" 경고 대상이면 안 된다.
  it('기존 회차가 REVIEWED면 중복 회차 경고 없이 바로 생성한다', async () => {
    let createCallCount = 0;
    server.use(
      http.get('/api/inspections', () => {
        const body = {
          success: true,
          data: { content: [{ id: 900, roundNo: 2, status: 'REVIEWED' }], page: 0, totalElements: 1 },
        };
        return HttpResponse.json(body);
      }),
      http.post('/api/inspections', () => {
        createCallCount += 1;
        return HttpResponse.json({ success: true, data: null });
      }),
    );

    renderPage();
    await fillRequiredFields();
    selectFiles([new File(['a'], 'a.jpg', { type: 'image/jpeg' })]);

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '업로드 완료 후 AI 분석 시작' }));
    });

    await waitFor(() => expect(createCallCount).toBe(1));
    expect(
      screen.queryByText('이미 진행 중인 2회차가 있습니다. 이어서 진행하시겠습니까, 새 회차를 만드시겠습니까?'),
    ).toBeNull();
  });

  it('중복 회차 확인창에서 "계속 생성"을 누르면 정상적으로 점검을 생성한다', async () => {
    server.use(
      http.get('/api/inspections', () => {
        const body = {
          success: true,
          data: { content: [{ id: 900, roundNo: 2, status: 'ANALYZING' }], page: 0, totalElements: 1 },
        };
        return HttpResponse.json(body);
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
    vi.spyOn(mediaApi, 'upload')
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      .mockResolvedValue({ data: mockMedia } as any);

    const router = renderPage();
    await fillRequiredFields();
    selectFiles([new File(['a'], 'a.jpg', { type: 'image/jpeg' })]);

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '업로드 완료 후 AI 분석 시작' }));
    });
    await screen.findByText('이미 진행 중인 2회차가 있습니다. 이어서 진행하시겠습니까, 새 회차를 만드시겠습니까?');

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '계속 생성' }));
    });

    expect(await screen.findByText('AI 분석 실행/상태')).not.toBeNull();
    // 이 파일의 다른 테스트가 목 핸들러의 공유 nextInspectionId 카운터를 먼저 소비할 수 있어
    // (server.use로 POST를 override하지 않는 한 공용) 정확한 id 대신 경로 패턴만 확인한다.
    expect(router.state.location.pathname).toMatch(/^\/inspections\/\d+\/analysis$/);
  });

  it('중복 회차 확인창에서 "이어서 하기"를 누르면 새로 만들지 않고 기존 회차의 분석 화면으로 바로 이동한다', async () => {
    let createCallCount = 0;
    server.use(
      http.get('/api/inspections', () => {
        const body = {
          success: true,
          data: { content: [{ id: 900, roundNo: 2, status: 'ANALYZING' }], page: 0, totalElements: 1 },
        };
        return HttpResponse.json(body);
      }),
      http.post('/api/inspections', () => {
        createCallCount += 1;
        return HttpResponse.json({ success: true, data: null });
      }),
    );

    const router = renderPage();
    await fillRequiredFields();
    selectFiles([new File(['a'], 'a.jpg', { type: 'image/jpeg' })]);

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '업로드 완료 후 AI 분석 시작' }));
    });
    await screen.findByText('이미 진행 중인 2회차가 있습니다. 이어서 진행하시겠습니까, 새 회차를 만드시겠습니까?');

    fireEvent.click(screen.getByRole('button', { name: '이어서 하기' }));

    expect(await screen.findByText('AI 분석 실행/상태')).not.toBeNull();
    expect(router.state.location.pathname).toBe('/inspections/900/analysis');
    // 새 회차를 만들지 않는다 — 기존 회차로 그대로 이동만 한다.
    expect(createCallCount).toBe(0);
  });

  it('currentUser=null이면 제출 시 안내 메시지가 뜨고 createInspection이 호출되지 않는다', async () => {
    // 코드 리뷰 P3 — 로그인 사용자 정보가 아직 로드되지 않은 순간의 제출을 조용히 무시하던 것을
    // 고쳤다(무피드백 오동작). currentUser가 null이면 안내 문구를 보여주고 회차 생성 요청 자체를
    // 보내지 않는다.
    useAuthStore.setState({ user: null });
    let createCallCount = 0;
    server.use(
      http.post('/api/inspections', () => {
        createCallCount += 1;
        return HttpResponse.json({ success: true, data: null });
      }),
    );

    renderPage();
    await fillRequiredFields();
    selectFiles([new File(['a'], 'a.jpg', { type: 'image/jpeg' })]);

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '업로드 완료 후 AI 분석 시작' }));
    });

    expect(
      await screen.findByText('사용자 정보를 불러오는 중입니다. 잠시 후 다시 시도해 주세요.'),
    ).not.toBeNull();
    expect(createCallCount).toBe(0);
  });

  it('작성 중 다른 라우트로 이동 시 확인창을 띄우고, 취소하면 머무르고 나가기를 누르면 이동한다', async () => {
    const router = renderPage();
    await fillRequiredFields();

    fireEvent.click(screen.getByRole('link', { name: '대시보드로 이동(사이드바 대역)' }));
    expect(
      await screen.findByText(/작성을 취소하시겠습니까\?/),
    ).not.toBeNull();
    expect(router.state.location.pathname).toBe('/inspections/create');

    // 취소 — 확인창이 닫히고 현재 페이지에 머무른다
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    await waitFor(() =>
      expect(screen.queryByText(/작성을 취소하시겠습니까\?/)).toBeNull(),
    );
    expect(router.state.location.pathname).toBe('/inspections/create');

    // 다시 시도 후 나가기 — 클릭했던 목적지로 이동한다
    fireEvent.click(screen.getByRole('link', { name: '대시보드로 이동(사이드바 대역)' }));
    await screen.findByText(/작성을 취소하시겠습니까\?/);
    fireEvent.click(screen.getByRole('button', { name: '나가기' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/dashboard'));
  });

  // PR머신 리뷰 P2 — 임시저장 디바운스(400ms) 대기 중 언마운트되면 최신 mediaFiles를 즉시
  // flush해야 한다(안 그러면 파일 추가 직후 사이드바 이탈 시 그 변경이 조용히 유실된다).
  // selectFiles 직후 곧바로 unmount하면 실제 경과 시간이 400ms에 한참 못 미쳐 자연스럽게
  // "디바운스 타이머 발화 전 언마운트" 상황이 재현된다 — 가짜 타이머는 findByText 등 RTL의
  // 내부 폴링(waitFor)과 충돌해 오히려 불필요한 복잡도를 더한다.
  it('파일 추가 직후(디바운스 대기 중) 언마운트되면 최신 mediaFiles를 즉시 flush한다', async () => {
    vi.mocked(saveDraftMediaFiles).mockClear();

    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const router = createMemoryRouter(
      [{ path: '/inspections/create', element: <InspectionCreatePage /> }],
      { initialEntries: ['/inspections/create'] },
    );
    const { unmount } = render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await fillRequiredFields();
    const file = new File(['a'], 'a.jpg', { type: 'image/jpeg' });
    selectFiles([file]);

    unmount();

    expect(saveDraftMediaFiles).toHaveBeenCalledWith([file]);
  });

  // #1703 — 텍스트 초안을 localStorage로 옮기고 TTL(7일)을 도입한 회귀 방지 테스트.
  it('TTL 7일 이내의 임시저장은 폼에 복원되고 IndexedDB 사진 초안은 지우지 않는다', async () => {
    vi.mocked(clearDraftMediaFiles).mockClear();
    saveInspectionCreateDraft({
      facilityId: '1',
      inspectionDate: todayDateString(),
      inspectionType: 'DETAILED',
      memo: '균열 확인 필요',
    });

    renderPage();
    await screen.findByText('판교 테크노밸리 B동');

    expect((screen.getByLabelText('시설물') as HTMLSelectElement).value).toBe('1');
    expect((screen.getByLabelText('메모') as HTMLTextAreaElement).value).toBe('균열 확인 필요');
    expect(clearDraftMediaFiles).not.toHaveBeenCalled();
  });

  it('TTL 7일이 지난 임시저장은 폼에 복원하지 않고, IndexedDB 사진 초안도 함께 정리한다', async () => {
    vi.mocked(clearDraftMediaFiles).mockClear();
    const eightDaysAgo = Date.now() - 8 * 24 * 60 * 60 * 1000;
    localStorage.setItem(
      DRAFT_KEY,
      JSON.stringify({
        facilityId: '1',
        inspectionDate: todayDateString(),
        inspectionType: 'DETAILED',
        memo: '만료된 초안',
        savedAt: eightDaysAgo,
      }),
    );

    renderPage();
    await screen.findByText('판교 테크노밸리 B동');

    expect((screen.getByLabelText('시설물') as HTMLSelectElement).value).toBe('');
    expect((screen.getByLabelText('메모') as HTMLTextAreaElement).value).toBe('');
    expect(clearDraftMediaFiles).toHaveBeenCalled();
    // 만료 감지 시 localStorage 레코드 자체도 정리해 다음 로드마다 매번 다시 만료 판정을
    // 반복하지 않는다.
    expect(localStorage.getItem(DRAFT_KEY)).toBeNull();
  });
});
