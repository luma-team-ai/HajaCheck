// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../assets/fonts/NotoSansKR-Regular.subset.ttf?url', () => ({
  default: 'https://example.test/NotoSansKR-Regular.subset.ttf',
}));
vi.mock('../../assets/fonts/NotoSansKR-Bold.subset.ttf?url', () => ({
  default: 'https://example.test/NotoSansKR-Bold.subset.ttf',
}));

describe('registerNotoSansKrFont', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) =>
        Promise.resolve({
          blob: () =>
            Promise.resolve(
              new Blob([url.includes('Bold') ? 'fake-bold-font-bytes' : 'fake-regular-font-bytes']),
            ),
        }),
      ),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('Regular/Bold 두 폰트를 fetch해 VFS에 등록하고 normal/bold로 addFont한다', async () => {
    const { registerNotoSansKrFont, PDF_FONT_NAME } = await import('./pdfFont');
    const mockAddFileToVFS = vi.fn();
    const mockAddFont = vi.fn();

    await registerNotoSansKrFont({ addFileToVFS: mockAddFileToVFS, addFont: mockAddFont });

    expect(mockAddFileToVFS).toHaveBeenCalledWith('NotoSansKR-Regular.ttf', expect.any(String));
    expect(mockAddFileToVFS).toHaveBeenCalledWith('NotoSansKR-Bold.ttf', expect.any(String));
    expect(mockAddFont).toHaveBeenCalledWith('NotoSansKR-Regular.ttf', PDF_FONT_NAME, 'normal');
    expect(mockAddFont).toHaveBeenCalledWith('NotoSansKR-Bold.ttf', PDF_FONT_NAME, 'bold');
  });

  it('Regular/Bold 두 URL을 각각 fetch한다(하나로 합치지 않음)', async () => {
    const { registerNotoSansKrFont } = await import('./pdfFont');
    const fetchSpy = fetch as unknown as ReturnType<typeof vi.fn>;

    await registerNotoSansKrFont({ addFileToVFS: vi.fn(), addFont: vi.fn() });

    const fetchedUrls = fetchSpy.mock.calls.map((call: unknown[]) => call[0] as string);
    expect(fetchedUrls).toContain('https://example.test/NotoSansKR-Regular.subset.ttf');
    expect(fetchedUrls).toContain('https://example.test/NotoSansKR-Bold.subset.ttf');
  });
});
