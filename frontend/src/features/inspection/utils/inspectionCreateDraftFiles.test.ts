// @vitest-environment node
// jsdom엔 IndexedDB 구현이 없어(장기 미지원 갭) fake-indexeddb로 폴리필한다. jsdom 환경 대신
// node 환경을 쓰는 이유 — jsdom의 structuredClone이 Blob을 온전히 복제하지 못하는 알려진 문제가
// 있어(jsdom/jsdom#3363, fake-indexeddb README "jsdom" 항목 참고) File.text()로 읽으면
// "[object Object]"로 깨진다. 이 파일은 DOM이 필요 없고 Node 18+ 내장 File/Blob과 정상 동작하는
// structuredClone만 있으면 되므로 node 환경으로 그 문제를 우회한다.
import 'fake-indexeddb/auto';
import { afterEach, describe, expect, it } from 'vitest';
import {
  clearDraftMediaFiles,
  loadDraftMediaFiles,
  saveDraftMediaFiles,
} from './inspectionCreateDraftFiles';

const DB_NAME = 'hajacheckInspectionCreateDraft';

// 테스트 간 격리 — 매 테스트 뒤 DB를 통째로 지워 이전 테스트의 저장값이 남지 않게 한다.
async function resetDb() {
  await new Promise<void>((resolve, reject) => {
    const request = indexedDB.deleteDatabase(DB_NAME);
    request.onsuccess = () => resolve();
    request.onerror = () => reject(request.error);
    request.onblocked = () => resolve();
  });
}

describe('inspectionCreateDraftFiles', () => {
  afterEach(async () => {
    await resetDb();
  });

  it('저장된 파일이 없으면 빈 배열을 반환한다', async () => {
    expect(await loadDraftMediaFiles()).toEqual([]);
  });

  it('저장하면 그대로 조회할 수 있다(라운드트립) — 파일명·타입·내용 보존', async () => {
    const file = new File(['crack-photo-bytes'], 'crack.jpg', { type: 'image/jpeg' });

    await saveDraftMediaFiles([file]);
    const restored = await loadDraftMediaFiles();

    expect(restored).toHaveLength(1);
    expect(restored[0].name).toBe('crack.jpg');
    expect(restored[0].type).toBe('image/jpeg');
    expect(await restored[0].text()).toBe('crack-photo-bytes');
  });

  it('여러 파일을 저장 순서대로 복원한다', async () => {
    const files = [
      new File(['a'], 'a.jpg', { type: 'image/jpeg' }),
      new File(['b'], 'b.png', { type: 'image/png' }),
    ];

    await saveDraftMediaFiles(files);
    const restored = await loadDraftMediaFiles();

    expect(restored.map((entry) => entry.name)).toEqual(['a.jpg', 'b.png']);
  });

  it('clear 후에는 조회 시 빈 배열을 반환한다', async () => {
    await saveDraftMediaFiles([new File(['a'], 'a.jpg', { type: 'image/jpeg' })]);
    await clearDraftMediaFiles();

    expect(await loadDraftMediaFiles()).toEqual([]);
  });

  it('빈 배열로 다시 저장하면 이후 조회는 빈 배열을 반환한다(하이드레이션 완료 후 삭제 시나리오)', async () => {
    await saveDraftMediaFiles([new File(['a'], 'a.jpg', { type: 'image/jpeg' })]);
    await saveDraftMediaFiles([]);

    expect(await loadDraftMediaFiles()).toEqual([]);
  });
});
