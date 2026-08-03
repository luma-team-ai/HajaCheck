// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { resizeImageToDataUrl } from './resizeImageToDataUrl';

/** jsdom은 이미지 디코딩·canvas 렌더링을 지원하지 않으므로 Image/canvas를 최소 스텁으로 대체한다. */
class StubImage {
  width = 4000;
  height = 2000;
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  private _src = '';
  set src(value: string) {
    this._src = value;
    queueMicrotask(() => this.onload?.());
  }
  get src() {
    return this._src;
  }
}

describe('resizeImageToDataUrl', () => {
  const drawImage = vi.fn();
  const toDataURL = vi.fn().mockReturnValue('data:image/jpeg;base64,resized');

  beforeEach(() => {
    vi.stubGlobal('Image', StubImage);
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:mock');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({
      drawImage,
    } as unknown as CanvasRenderingContext2D);
    vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockImplementation(toDataURL);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    drawImage.mockClear();
    toDataURL.mockClear();
  });

  it('긴 변이 1600을 넘으면 비율을 유지한 채 1600 이하로 줄여 JPEG data URL을 반환한다', async () => {
    const file = new File(['fake-bytes'], 'plan.png', { type: 'image/png' });

    const result = await resizeImageToDataUrl(file);

    // 4000x2000 → 긴 변(4000)을 1600으로 맞추면 2000*0.4=800.
    expect(drawImage).toHaveBeenCalledWith(expect.any(StubImage), 0, 0, 1600, 800);
    expect(toDataURL).toHaveBeenCalledWith('image/jpeg', 0.82);
    expect(result).toBe('data:image/jpeg;base64,resized');
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:mock');
  });

  it('원본이 이미 1600 이하이면 확대하지 않는다', async () => {
    class SmallStubImage extends StubImage {
      width = 800;
      height = 400;
    }
    vi.stubGlobal('Image', SmallStubImage);
    const file = new File(['fake-bytes'], 'small.jpg', { type: 'image/jpeg' });

    await resizeImageToDataUrl(file);

    expect(drawImage).toHaveBeenCalledWith(expect.any(SmallStubImage), 0, 0, 800, 400);
  });
});
