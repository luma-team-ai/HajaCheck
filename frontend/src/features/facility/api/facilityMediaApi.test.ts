// @vitest-environment node
// 이 파일만 node 환경을 쓴다(#1712) — 원래는 axios baseURL='/api'(상대경로)를 XHR 어댑터로
// resolve하려고 jsdom을 썼지만(defectApi.test.ts와 동일 이유), 실제 파일(File) 업로드를
// 검증하는 이 파일에선 jsdom이 오히려 문제였다: jsdom이 만드는 File은 jsdom 자체 구현체라
// msw(Node 내장 undici)가 요청을 파싱할 때 undici의 File 브랜드 체크(`webidl.is.File()`)를
// 통과하지 못해 항상 크래시했다(Node 22+에서 undici가 이 체크를 엄격화하며 드러남 — 상세는
// 이슈 #1712 코멘트 참고). node 환경에선 File/FormData/fetch가 전부 Node(undici) 자신의
// 것이라 이 realm 불일치 자체가 없다 — 이 파일이 "업로드 요청이 크래시 없이 도달하고,
// 파일이 문자열로 강등되지 않은 파일 엔트리로 정확한 개수만큼 전달되는지"를 검증하는
// 자리이므로(facilityMediaApi.handlers.ts는 `typeof entry !== 'string'`으로 개수만 판별하고,
// 바이트 내용·파일명은 이 레포 어디에서도 검증하지 않는다 — 그 점은 이 전환으로도 바뀌지
// 않는다), realm을 우회하는 잔재주 대신 애초에 크래시하지 않는 환경을 쓰는 쪽을 택했다.
// 대신 jsdom이 "공짜로" 제공해 주던 것들을 이 테스트에서만 최소한으로 메꿔준다(다른 테스트
// 파일에는 영향 없음 — vitest는 테스트 파일마다 모듈 레지스트리를 격리한다):
//   ① 공용 axios 인스턴스의 baseURL('/api', 상대경로)을 절대 URL로 덮어쓴다 — node엔
//      window.location이 없어 상대경로를 resolve할 기준이 없다.
//   ② msw는 handlers.ts의 상대경로 패턴(`/api/...`)도 `location.href` 기준으로 절대경로화해
//      매칭한다(getAbsoluteUrl) — jsdom 환경에선 기본 URL(`http://localhost:3000`)이 이미
//      있어 "공짜"였지만 node엔 그 자체가 없어 상대 패턴이 매칭되지 않는다. ①과 동일한
//      origin으로 최소 `location` 스텁을 채워 다른 테스트 파일과 매칭 방식을 그대로 유지한다.
//   ③ 공용 axios 인스턴스의 응답 에러 인터셉터(shared/api/axios.ts)가 상태코드와 무관하게
//      항상 `window.location.pathname`을 읽는다(401 리다이렉트 대상 계산용) — 최소 스텁을
//      채워준다.
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { api } from '../../../shared/api/axios';
import { facilityMediaApi } from './facilityMediaApi';
import { facilityMediaHandlers, resetFacilityMediaMockStore } from './facilityMediaApi.handlers';

const server = setupServer(...facilityMediaHandlers);
const TEST_ORIGIN = 'http://localhost:3000';

beforeAll(() => {
  api.defaults.baseURL = `${TEST_ORIGIN}/api`;
  (globalThis as { location?: unknown }).location = { href: `${TEST_ORIGIN}/` };
  (globalThis as { window?: unknown }).window = { location: { pathname: '/' } };
  server.listen({ onUnhandledRequest: 'error' });
});
afterEach(() => {
  server.resetHandlers();
  resetFacilityMediaMockStore();
});
afterAll(() => {
  server.close();
  delete (globalThis as { location?: unknown }).location;
  delete (globalThis as { window?: unknown }).window;
});

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
