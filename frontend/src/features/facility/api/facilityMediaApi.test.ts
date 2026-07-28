// @vitest-environment jsdom
// api(axios) baseURL='/api'(상대경로)를 XHR 어댑터로 resolve하려면 jsdom 환경이 필요(defectApi.test.ts와 동일 이유)
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { facilityMediaApi } from './facilityMediaApi';
import { facilityMediaHandlers, resetFacilityMediaMockStore } from './facilityMediaApi.handlers';

const server = setupServer(...facilityMediaHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  resetFacilityMediaMockStore();
});
afterAll(() => server.close());

function makeImageFile(name: string): File {
  return new File(['fake-image-bytes'], name, { type: 'image/png' });
}

describe('facilityMediaApi.upload', () => {
  it('파일을 업로드하면 생성된 사진 목록을 반환한다', async () => {
    const res = await facilityMediaApi.upload(1, [makeImageFile('a.png'), makeImageFile('b.png')]);

    expect(res.status).toBe(201);
    expect(res.data).toHaveLength(2);
    expect(res.data[0]).toMatchObject({ fileType: 'IMAGE', inspectionId: null });
  });

  it('진행률 콜백(onUploadProgress)이 함수로 전달돼도 에러 없이 업로드된다', async () => {
    const progressUpdates: number[] = [];

    const res = await facilityMediaApi.upload(1, [makeImageFile('a.png')], (percent) => {
      progressUpdates.push(percent);
    });

    expect(res.data).toHaveLength(1);
  });

  it('파일이 없으면 FILE_REQUIRED 에러로 reject된다', async () => {
    await expect(facilityMediaApi.upload(1, [])).rejects.toMatchObject({
      code: 'FILE_REQUIRED',
    });
  });

  // 4장 누적 제한(#652 핸드오프 — 신규 등록 폼은 항상 0장에서 시작하므로 이번 배치가 4장을
  // 넘는지만 확인하면 된다) — 백엔드 FACILITY_PHOTO_COUNT_EXCEEDED와 코드 정합.
  it('한 번에 4장을 초과해 업로드하면 FACILITY_PHOTO_COUNT_EXCEEDED 에러로 reject된다', async () => {
    const files = Array.from({ length: 5 }, (_, i) => makeImageFile(`photo-${i}.png`));

    await expect(facilityMediaApi.upload(1, files)).rejects.toMatchObject({
      code: 'FACILITY_PHOTO_COUNT_EXCEEDED',
    });
  });

  it('보유 장수 + 이번 업로드 합이 4장을 초과하면(누적 기준) 전체 배치가 거부된다', async () => {
    await facilityMediaApi.upload(2, [makeImageFile('a.png'), makeImageFile('b.png'), makeImageFile('c.png')]);

    await expect(
      facilityMediaApi.upload(2, [makeImageFile('d.png'), makeImageFile('e.png')]),
    ).rejects.toMatchObject({ code: 'FACILITY_PHOTO_COUNT_EXCEEDED' });
  });
});

describe('facilityMediaApi.list', () => {
  it('사진을 업로드한 적 없는 시설물은 빈 배열을 반환한다', async () => {
    const res = await facilityMediaApi.list(999);

    expect(res.data).toEqual([]);
  });

  it('업로드한 사진 목록을 반환한다', async () => {
    await facilityMediaApi.upload(3, [makeImageFile('a.png')]);

    const res = await facilityMediaApi.list(3);

    expect(res.data).toHaveLength(1);
    expect(res.data[0]).toMatchObject({ mimeType: 'image/png' });
  });
});
