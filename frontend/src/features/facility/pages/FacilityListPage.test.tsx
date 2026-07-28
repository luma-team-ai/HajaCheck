// @vitest-environment jsdom
// FacilityListPage 통합 테스트 — 실제 useFacilities/useCreateFacility 훅 + MSW facilityHandlers를 통해
// "등록 성공 시 목록 반영(invalidateQueries)"과 "등록 실패 시 모달 유지·폼 값 보존"을 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { facilityHandlers, resetFacilityMockStore } from '../api/facilityApi.handlers';
import { facilityMediaHandlers, resetFacilityMediaMockStore } from '../api/facilityMediaApi.handlers';
import { FacilityListPage } from './FacilityListPage';

const server = setupServer(...facilityHandlers, ...facilityMediaHandlers);

// jsdom은 URL.createObjectURL/revokeObjectURL을 구현하지 않으므로 대표 사진 선택을 시뮬레이션하는
// 테스트를 위해 스텁한다(FacilityPhotoUploadField.test.tsx와 동일 이유).
beforeEach(() => {
  let counter = 0;
  URL.createObjectURL = vi.fn(() => `blob:mock-${counter++}`) as unknown as typeof URL.createObjectURL;
  URL.revokeObjectURL = vi.fn() as unknown as typeof URL.revokeObjectURL;
});

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  // 모듈 스코프 목 저장소(facilities/nextId, facility 사진)는 resetHandlers()로 초기화되지
  // 않으므로, 한 테스트에서 등록한 데이터가 다음 테스트로 새지 않도록 명시적으로 리셋한다.
  resetFacilityMockStore();
  resetFacilityMediaMockStore();
  cleanup();
  vi.restoreAllMocks();
});
afterAll(() => server.close());

function makeImageFile(name: string): File {
  return new File(['fake-image-bytes'], name, { type: 'image/png' });
}

function renderPage(): void {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/facilities/list']}>
        <Routes>
          <Route path="/facilities/list" element={<FacilityListPage />} />
          <Route path="/facilities/:id" element={<div>시설물 상세 화면</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function openCreateModal() {
  fireEvent.click(screen.getByRole('button', { name: '+ 시설물 등록' }));
}

function fillRequiredFields(name: string) {
  fireEvent.change(screen.getByLabelText(/시설물명/), { target: { value: name } });
  // #731 — 유형 옵션이 조합형 12종으로 확장돼 단순 '건물'은 더 이상 유효한 <option>이 아니다.
  fireEvent.change(screen.getByLabelText(/시설물 유형/), {
    target: { value: '건물-정기-4개월' },
  });
}

describe('FacilityListPage (통합 테스트)', () => {
  it('초기 목록: MSW 목 데이터를 불러와 카드 그리드에 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('강남 오피스타워 A동')).not.toBeNull();
  });

  it('등록 성공: 새 시설물이 목록에 즉시 반영되고 모달이 닫힌다', async () => {
    renderPage();
    await screen.findByText('강남 오피스타워 A동');

    openCreateModal();
    fillRequiredFields('테스트 신규 시설물');

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '등록하기' }));
    });

    expect(await screen.findByText('테스트 신규 시설물')).not.toBeNull();
    // 등록 성공 후 모달이 닫혀 더 이상 폼이 렌더링되지 않는다
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  // #652 — 시설물 생성 후 대표 사진이 선택돼 있으면 실제 업로드 API(POST .../media)가 호출되는지,
  // 선택하지 않았으면 호출되지 않는지를 MSW 요청 로그로 직접 검증한다(전체 등록+업로드 플로우).
  it('등록 성공 + 사진 선택: 시설물 생성 후 대표 사진 업로드 API가 호출된다(#652)', async () => {
    const requestedUrls: string[] = [];
    const captureRequest = ({ request }: { request: Request }) => {
      requestedUrls.push(new URL(request.url).pathname);
    };
    server.events.on('request:match', captureRequest);

    try {
      renderPage();
      await screen.findByText('강남 오피스타워 A동');

      openCreateModal();
      fillRequiredFields('사진 있는 시설물');
      fireEvent.change(screen.getByLabelText('대표 사진 업로드'), {
        target: { files: [makeImageFile('a.png')] },
      });

      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: '등록하기' }));
      });

      expect(await screen.findByText('사진 있는 시설물')).not.toBeNull();
      expect(screen.queryByRole('dialog')).toBeNull();
      expect(requestedUrls.some((path) => /^\/api\/facilities\/\d+\/media$/.test(path))).toBe(true);
    } finally {
      server.events.removeListener('request:match', captureRequest);
    }
  });

  it('등록 성공 + 사진 미선택: 대표 사진 업로드 API가 호출되지 않는다(#652)', async () => {
    const requestedUrls: string[] = [];
    const captureRequest = ({ request }: { request: Request }) => {
      requestedUrls.push(new URL(request.url).pathname);
    };
    server.events.on('request:match', captureRequest);

    try {
      renderPage();
      await screen.findByText('강남 오피스타워 A동');

      openCreateModal();
      fillRequiredFields('사진 없는 시설물');

      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: '등록하기' }));
      });

      expect(await screen.findByText('사진 없는 시설물')).not.toBeNull();
      expect(requestedUrls.some((path) => /^\/api\/facilities\/\d+\/media$/.test(path))).toBe(false);
    } finally {
      server.events.removeListener('request:match', captureRequest);
    }
  });

  // code-reviewer P1 회귀고정 — 시설물 생성은 성공했지만 사진 업로드만 실패하면, isPending/error를
  // useCreateFacility에서만 읽던 예전 코드는 (1) 생성 성공 즉시 isPending이 false로 풀려 업로드가
  // 진행 중인 동안 등록 버튼이 재활성화되고(중복 생성 위험), (2) uploadPhotos의 에러는 별개
  // 뮤테이션(error가 null)이라 배너가 전혀 뜨지 않아 사용자가 실패 사실을 전혀 알 수 없었다.
  // isCreating||isUploading, createError??uploadError로 합친 뒤에는 최소한 에러 배너가 반드시
  // 표시돼야 한다(모달이 열린 채 남아 사용자가 무언가 실패했음을 알 수 있어야 함).
  it('시설물 생성 후 대표 사진 업로드만 실패하면 에러 배너를 표시하고 모달을 유지한다(#652, code-reviewer P1)', async () => {
    server.use(
      http.post('/api/facilities/:facilityId/media', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'FACILITY_PHOTO_UPLOAD_FAILED', message: '대표 사진 업로드에 실패했습니다.' },
        };
        return HttpResponse.json(failure, { status: 500 });
      }),
    );

    renderPage();
    await screen.findByText('강남 오피스타워 A동');

    openCreateModal();
    fillRequiredFields('사진 업로드만 실패하는 시설물');
    fireEvent.change(screen.getByLabelText('대표 사진 업로드'), {
      target: { files: [makeImageFile('a.png')] },
    });

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '등록하기' }));
    });

    // 시설물 생성 자체는 이미 성공해 목록/목 저장소에 반영돼 있다(P1이 지적한 "이미 생성됐는데
    // 아무 표시도 없는" 상황 그 자체) — 그럼에도 사용자에게 실패 배너가 보여야 한다.
    expect(await screen.findByText('사진 업로드만 실패하는 시설물')).not.toBeNull();
    expect(await screen.findByText('대표 사진 업로드에 실패했습니다.')).not.toBeNull();
    // 모달은 자동으로 닫히지 않는다 — uploadPhotos의 rejection이 FacilityFormModal의 handleSubmit
    // catch로 전파돼 handleCloseModal이 호출되지 않는다.
    expect(screen.queryByRole('dialog')).not.toBeNull();
  });

  // #1098 회귀고정 — 생성 성공+업로드만 실패한 뒤 같은 폼으로 재제출하면, 시설물을 다시 생성하지
  // 않고(POST /api/facilities는 최초 1회만) 기억해둔 facility.id로 업로드만 재시도해야 한다.
  it('사진 업로드만 실패한 뒤 재제출하면 시설물을 다시 생성하지 않고 업로드만 재시도한다(#1098)', async () => {
    let mediaAttempt = 0;
    server.use(
      http.post('/api/facilities/:facilityId/media', () => {
        mediaAttempt += 1;
        if (mediaAttempt === 1) {
          const failure: ApiResponse<null> = {
            success: false,
            data: null,
            error: { code: 'FACILITY_PHOTO_UPLOAD_FAILED', message: '대표 사진 업로드에 실패했습니다.' },
          };
          return HttpResponse.json(failure, { status: 500 });
        }
        const success: ApiResponse<null> = { success: true, data: null };
        return HttpResponse.json(success);
      }),
    );

    const createFacilityRequests: string[] = [];
    const captureRequest = ({ request }: { request: Request }) => {
      const url = new URL(request.url);
      if (request.method === 'POST' && url.pathname === '/api/facilities') {
        createFacilityRequests.push(url.pathname);
      }
    };
    server.events.on('request:match', captureRequest);

    try {
      renderPage();
      await screen.findByText('강남 오피스타워 A동');

      openCreateModal();
      fillRequiredFields('재시도 시설물');
      fireEvent.change(screen.getByLabelText('대표 사진 업로드'), {
        target: { files: [makeImageFile('a.png')] },
      });

      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: '등록하기' }));
      });
      await screen.findByText('대표 사진 업로드에 실패했습니다.');
      expect(screen.queryByRole('dialog')).not.toBeNull();

      // 실패 시 폼 값·선택 사진이 그대로 유지되므로(FacilityFormModal), 같은 버튼을 다시 누르는
      // 것만으로 "동일 폼 재제출"을 재현할 수 있다.
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: '등록하기' }));
      });

      expect(screen.queryByRole('dialog')).toBeNull();
      expect(createFacilityRequests).toHaveLength(1);
      expect(mediaAttempt).toBe(2);
    } finally {
      server.events.removeListener('request:match', captureRequest);
    }
  });

  // #1098 P1 회귀고정(PR머신 재검수) — 업로드가 아직 응답을 받기 전(in-flight)에 Escape로 모달을
  // 닫으면, Modal의 키보드 핸들러는 isSubmitting을 모르고 즉시 onClose를 호출한다(취소 버튼의
  // disabled={isSubmitting}과 달리 보호되지 않음). 이후 뒤늦게 도착하는 업로드 실패 응답이
  // "이미 포기한" facilityId로 pendingFacilityId를 되살리면, 완전히 무관한 다음 등록이 재생성 없이
  // 그 옛 시설물에 사진을 붙이는 경쟁 조건이 생긴다 — submissionTokenRef가 이를 막아야 한다.
  it('업로드 진행 중 Escape로 모달을 닫아도 이후의 무관한 새 등록이 이전 시설물에 사진을 붙이지 않는다(#1098 P1)', async () => {
    let releaseMediaResponse: (() => void) | undefined;
    const mediaGate = new Promise<void>((resolve) => {
      releaseMediaResponse = resolve;
    });
    let mediaAttempt = 0;
    server.use(
      http.post('/api/facilities/:facilityId/media', async () => {
        mediaAttempt += 1;
        if (mediaAttempt === 1) {
          // 테스트가 releaseMediaResponse()를 호출할 때까지 응답하지 않는다 — 업로드가
          // in-flight인 동안 사용자가 Escape를 누르는 타이밍을 재현하기 위한 지연.
          await mediaGate;
          const failure: ApiResponse<null> = {
            success: false,
            data: null,
            error: { code: 'FACILITY_PHOTO_UPLOAD_FAILED', message: '대표 사진 업로드에 실패했습니다.' },
          };
          return HttpResponse.json(failure, { status: 500 });
        }
        const success: ApiResponse<null> = { success: true, data: null };
        return HttpResponse.json(success);
      }),
    );

    const createFacilityRequests: string[] = [];
    const captureRequest = ({ request }: { request: Request }) => {
      const url = new URL(request.url);
      if (request.method === 'POST' && url.pathname === '/api/facilities') {
        createFacilityRequests.push(url.pathname);
      }
    };
    server.events.on('request:match', captureRequest);

    try {
      renderPage();
      await screen.findByText('강남 오피스타워 A동');

      openCreateModal();
      fillRequiredFields('경쟁 조건 시설물');
      fireEvent.change(screen.getByLabelText('대표 사진 업로드'), {
        target: { files: [makeImageFile('a.png')] },
      });

      // 등록 클릭 — 시설물 생성은 성공하지만 업로드는 mediaGate가 풀릴 때까지 pending 상태로
      // 남는다. act로 감싸지 않고 진행 중인 상태를 그대로 유지한다.
      fireEvent.click(screen.getByRole('button', { name: '등록하기' }));
      await waitFor(() => expect(createFacilityRequests).toHaveLength(1));

      // 업로드가 in-flight인 동안 Escape로 모달을 닫는다 — 취소 버튼은 disabled라 눌리지 않지만
      // Modal의 Escape 리스너는 isSubmitting을 모르므로 즉시 닫힌다(이 PR이 고치는 P1의 재현 조건).
      fireEvent.keyDown(document, { key: 'Escape' });
      expect(screen.queryByRole('dialog')).toBeNull();

      // 이제 지연됐던 첫 업로드를 실패로 귀결시킨다 — 모달이 닫힌 뒤 도착하는 "뒤늦은 catch".
      await act(async () => {
        releaseMediaResponse?.();
        await new Promise((resolve) => setTimeout(resolve, 50));
      });

      // 완전히 새로운 등록을 시도한다 — pendingFacilityId가 되살아나 있다면 createFacility가
      // 다시 호출되지 않고 새로 선택한 사진이 옛(버려진) 시설물에 업로드될 것이다.
      openCreateModal();
      fillRequiredFields('전혀 다른 새 시설물');
      fireEvent.change(screen.getByLabelText('대표 사진 업로드'), {
        target: { files: [makeImageFile('b.png')] },
      });
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: '등록하기' }));
      });

      expect(await screen.findByText('전혀 다른 새 시설물')).not.toBeNull();
      // 두 번째 등록도 자기 자신의 시설물을 새로 생성해야 한다 — 1회(첫 시도)가 아니라 2회.
      expect(createFacilityRequests).toHaveLength(2);
    } finally {
      server.events.removeListener('request:match', captureRequest);
    }
  });

  it('등록 실패: 모달이 닫히지 않고 입력한 폼 값이 유지되며 에러 메시지가 표시된다', async () => {
    server.use(
      http.post('/api/facilities', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'FACILITY_CREATE_FAILED', message: '시설물 등록에 실패했습니다.' },
        };
        return HttpResponse.json(failure, { status: 400 });
      }),
    );

    renderPage();
    await screen.findByText('강남 오피스타워 A동');

    openCreateModal();
    fillRequiredFields('실패할 시설물');

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '등록하기' }));
    });

    expect(await screen.findByText('시설물 등록에 실패했습니다.')).not.toBeNull();
    // 모달은 여전히 열려 있고, 입력값도 초기화되지 않아야 한다
    expect(screen.queryByRole('dialog')).not.toBeNull();
    expect((screen.getByLabelText(/시설물명/) as HTMLInputElement).value).toBe('실패할 시설물');
  });

  it('시설물 이름 클릭 시 /facilities/:id(하자 정보 패널)로 이동한다(#489)', async () => {
    renderPage();
    await screen.findByText('강남 오피스타워 A동');

    fireEvent.click(screen.getByRole('button', { name: /강남 오피스타워 A동/ }));

    expect(await screen.findByText('시설물 상세 화면')).not.toBeNull();
  });

  // 검색+필터(#810) — MSW 목 데이터를 실제 useFacilities 경로로 불러온 뒤 FacilityFilterBar를
  // 통해 클라이언트 사이드로 좁혀지는지 확인한다(pure filterFacilities는 별도 단위 테스트 존재).
  it('검색창에 입력하면 일치하지 않는 시설물이 목록에서 숨겨진다', async () => {
    renderPage();
    await screen.findByText('강남 오피스타워 A동');
    await screen.findByText('한강대교 북단');

    fireEvent.change(screen.getByLabelText('시설물 이름 검색'), { target: { value: '오피스타워' } });

    expect(screen.getByText('강남 오피스타워 A동')).not.toBeNull();
    expect(screen.queryByText('한강대교 북단')).toBeNull();
  });

  it('검색 결과가 0건이면 필터 전용 빈 상태 안내 문구를 표시한다', async () => {
    renderPage();
    await screen.findByText('강남 오피스타워 A동');

    fireEvent.change(screen.getByLabelText('시설물 이름 검색'), {
      target: { value: '존재하지않는시설물이름' },
    });

    expect(await screen.findByText('검색/필터 조건에 맞는 시설물이 없습니다.')).not.toBeNull();
  });
});