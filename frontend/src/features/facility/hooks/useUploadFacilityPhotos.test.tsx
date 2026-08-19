// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { setupServer } from 'msw/node';
import type { ReactNode } from 'react';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { installMswFileRealmCompat } from '../../../shared/testing/mswFileRealmCompat';
import { facilityHandlers, resetFacilityMockStore } from '../api/facilityApi.handlers';
import { facilityMediaHandlers, resetFacilityMediaMockStore } from '../api/facilityMediaApi.handlers';
import { useFacilities } from './useFacilities';
import { useUploadFacilityPhotos } from './useUploadFacilityPhotos';

// 등록 직후 사진이 목록에 반영 안 되는 회귀 방지 — 원인은 useUploadFacilityPhotos가 업로드 성공 후
// 목록 쿼리(facilityKeys.list)를 무효화하지 않아, 시설물 생성(useCreateFacility)이 먼저 무효화해 받아온
// "사진 없음" 스냅샷이 캐시에 그대로 남던 것이었다. 업로드 성공 시 목록이 실제로 재조회되는지를
// GET /api/facilities 호출 횟수로 검증한다(useSetInspectionSchedule.test.tsx와 동일한 통합 하네스 패턴).
const server = setupServer(...facilityHandlers, ...facilityMediaHandlers);
// jsdom File과 msw(Node 내장 undici)의 realm 불일치로 실제 파일 업로드 요청이 크래시하는 문제
// 회피(#1712) — 이 테스트는 업로드 성공/실패에 따른 훅의 무효화 동작만 검증하고 바이트
// 내용·파일명은 애초에 보지 않으므로(이 레포 어디에도 그 검증은 없다) 이 유틸로 충분하다.
const restoreFileRealm = installMswFileRealmCompat(server);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  resetFacilityMockStore();
  resetFacilityMediaMockStore();
  restoreFileRealm();
});
afterAll(() => server.close());

function useTestHarness() {
  const list = useFacilities();
  const upload = useUploadFacilityPhotos();
  return { list, upload };
}

function renderHarness() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return renderHook(() => useTestHarness(), { wrapper });
}

function makeImageFile(name = 'photo.png'): File {
  return new File(['fake-image-bytes'], name, { type: 'image/png' });
}

describe('useUploadFacilityPhotos', () => {
  it('사진 업로드 성공 시 시설물 목록 쿼리를 무효화해 재조회한다(등록 직후 "사진 없음" 고정 표시 회귀 방지)', async () => {
    let getFacilitiesListCallCount = 0;
    const captureRequest = ({ request }: { request: Request }) => {
      if (request.method === 'GET' && new URL(request.url).pathname === '/api/facilities') {
        getFacilitiesListCallCount += 1;
      }
    };
    server.events.on('request:match', captureRequest);

    try {
      const { result } = renderHarness();
      await waitFor(() => expect(result.current.list.isSuccess).toBe(true));
      const callCountAfterInitialLoad = getFacilitiesListCallCount;
      expect(callCountAfterInitialLoad).toBeGreaterThan(0);

      await result.current.upload.uploadPhotos(1, [makeImageFile()]);

      await waitFor(() => {
        expect(getFacilitiesListCallCount).toBeGreaterThan(callCountAfterInitialLoad);
      });
    } finally {
      server.events.removeListener('request:match', captureRequest);
    }
  });

  it('사진 업로드 실패 시에는 목록 쿼리를 무효화하지 않는다', async () => {
    let getFacilitiesListCallCount = 0;
    const captureRequest = ({ request }: { request: Request }) => {
      if (request.method === 'GET' && new URL(request.url).pathname === '/api/facilities') {
        getFacilitiesListCallCount += 1;
      }
    };
    server.events.on('request:match', captureRequest);

    try {
      const { result } = renderHarness();
      await waitFor(() => expect(result.current.list.isSuccess).toBe(true));
      const callCountAfterInitialLoad = getFacilitiesListCallCount;

      // 빈 files 배열은 목 핸들러(facilityMediaApi.handlers.ts)가 FILE_REQUIRED(400)로 거부한다.
      await expect(result.current.upload.uploadPhotos(1, [])).rejects.toMatchObject({
        code: 'FILE_REQUIRED',
      });

      // 짧게 대기해도 추가 GET 호출이 없어야 한다(무효화가 실패 시에는 발동하지 않음을 확인).
      await new Promise((resolve) => setTimeout(resolve, 50));
      expect(getFacilitiesListCallCount).toBe(callCountAfterInitialLoad);
    } finally {
      server.events.removeListener('request:match', captureRequest);
    }
  });
});
