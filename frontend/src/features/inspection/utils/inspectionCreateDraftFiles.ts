// 점검(회차) 생성 폼의 첨부 파일 임시저장 — File(Blob)은 JSON 직렬화가 안 되고 실제 사진 용량도
// sessionStorage 할당량(수 MB)을 훌쩍 넘기 쉬워(이미지 1장당 최대 20MB — constants.ts, 개수 제한 없음)
// inspectionCreateDraft.ts(텍스트 필드)와 달리 IndexedDB에 Blob 그대로 저장한다.
// 접근 실패(프라이빗 모드 등)는 조용히 무시 — 첨부 파일 복원만 안 될 뿐 폼 작성 자체엔 영향 없음.
const DB_NAME = 'hajacheckInspectionCreateDraft';
const DB_VERSION = 1;
const STORE_NAME = 'mediaFiles';
const RECORD_KEY = 'draft';

interface StoredDraftFile {
  name: string;
  type: string;
  blob: Blob;
}

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      request.result.createObjectStore(STORE_NAME);
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

export async function saveDraftMediaFiles(files: File[]): Promise<void> {
  try {
    const db = await openDb();
    const stored: StoredDraftFile[] = files.map((file) => ({
      name: file.name,
      type: file.type,
      blob: file,
    }));
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readwrite');
      tx.objectStore(STORE_NAME).put(stored, RECORD_KEY);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
    db.close();
  } catch {
    // 저장 실패 무시
  }
}

export async function loadDraftMediaFiles(): Promise<File[]> {
  try {
    const db = await openDb();
    const stored = await new Promise<StoredDraftFile[] | undefined>((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readonly');
      const request = tx.objectStore(STORE_NAME).get(RECORD_KEY);
      request.onsuccess = () => resolve(request.result as StoredDraftFile[] | undefined);
      request.onerror = () => reject(request.error);
    });
    db.close();
    if (!stored) return [];
    return stored.map((entry) => new File([entry.blob], entry.name, { type: entry.type }));
  } catch {
    return [];
  }
}

export async function clearDraftMediaFiles(): Promise<void> {
  try {
    const db = await openDb();
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readwrite');
      tx.objectStore(STORE_NAME).delete(RECORD_KEY);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
    db.close();
  } catch {
    // 삭제 실패 무시
  }
}
