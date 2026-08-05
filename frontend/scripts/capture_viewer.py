"""
ResultViewer 페이지 Playwright 캡처 스크립트
- 기업회원 로그인 자동 처리
- 사이드바 완전 숨김 (display: none — 접힘 아님)
- 이미지 높이를 동적으로 계산해 스크롤 없이 1920×1080 뷰포트에 fit
- bbox 위치는 이미지 기준 % 좌표이므로 이미지 크기 변화와 무관하게 정합 유지
"""

import argparse
import sys
from pathlib import Path
from playwright.sync_api import sync_playwright, Page

# ── 기본값 ────────────────────────────────────────────────────────────────────
LOGIN_URL   = "https://hajacheck.luma200ok.com/login"
DEFAULT_URL = "https://hajacheck.luma200ok.com/inspections/27/viewer"
DEFAULT_OUT = str(Path.home() / "Downloads" / "viewer_capture.png")
LOGIN_ID    = "devteam@haja.test"
LOGIN_PW    = "hajadev1234"
VIEWPORT_W  = 1920
VIEWPORT_H  = 1080


def do_login(page: Page) -> None:
    """로그인 페이지에서 기업회원 탭으로 전환 후 로그인 처리."""
    print(f"  > 로그인 페이지 이동: {LOGIN_URL}")
    page.goto(LOGIN_URL, wait_until="networkidle", timeout=60_000)

    # 기업회원 탭 클릭 (role="tab", 텍스트 "기업회원")
    page.get_by_role("tab", name="기업회원").click()

    # 아이디/비밀번호 입력
    page.locator("#company-login-id").fill(LOGIN_ID)
    page.locator("#company-login-password").fill(LOGIN_PW)

    # 로그인 버튼 클릭 후 네트워크 idle 대기
    with page.expect_navigation(wait_until="networkidle", timeout=30_000):
        page.get_by_role("button", name="로그인").click()

    print(f"  > 로그인 완료, 현재 URL: {page.url}")


def capture(url: str, out: str, wait_ms: int = 2000) -> None:
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(
            viewport={"width": VIEWPORT_W, "height": VIEWPORT_H},
            device_scale_factor=1,
        )
        page = ctx.new_page()

        # ── 1. 로그인 ──────────────────────────────────────────────────────────
        print("[1/5] 로그인")
        do_login(page)

        # ── 2. 뷰어 페이지 이동 ────────────────────────────────────────────────
        print(f"[2/5] 뷰어 페이지 이동: {url}")
        page.goto(url, wait_until="networkidle", timeout=60_000)

        # 점검 이미지가 DOM에 나타날 때까지 대기
        page.wait_for_selector('img[alt="점검 이미지"]', timeout=30_000)

        # 이미지가 실제로 로드(onLoad)될 때까지 추가 대기
        page.wait_for_function(
            """() => {
                const img = document.querySelector('img[alt="점검 이미지"]');
                return img && img.complete && img.naturalHeight > 0;
            }""",
            timeout=30_000,
        )

        # ── 3. 레이아웃 조정 ───────────────────────────────────────────────────
        print("[3/5] 레이아웃 조정 (사이드바 숨김 + 이미지 높이 조정)")
        page.evaluate(
            """() => {
                // ── 사이드바 완전히 숨기기 ─────────────────────────────────
                // aside 자체 + 그것을 감싸는 wrapper div (flex-shrink-0)
                const aside = document.querySelector('aside');
                if (aside) {
                    aside.style.display = 'none';
                    const wrapper = aside.parentElement;
                    if (wrapper) wrapper.style.display = 'none';
                }

                // ── 이미지 최대 높이 동적 계산 ────────────────────────────
                // 뷰포트에서 이미지 위아래 모든 UI 요소의 실제 높이를 뺀다.
                const imgEl = document.querySelector('img[alt="점검 이미지"]');
                if (!imgEl) return;

                // DefectOverlay 내부: 이미지를 포함하는 div (w-fit relative)
                // → 그 부모인 "flex-1 flex-col items-center justify-center" div
                const imgWrapper = imgEl.closest('.flex-1.flex-col.items-center');
                if (!imgWrapper) {
                    imgEl.style.maxHeight = '500px';
                    return;
                }

                // 스크롤 컨테이너(overflow-y-auto) = 왼쪽 패널 전체
                const scrollPane = imgWrapper.closest('[class*="overflow-y-auto"]');
                if (!scrollPane) {
                    imgEl.style.maxHeight = '500px';
                    return;
                }

                // 스크롤 패널의 실제 표시 가능 높이
                const paneH = scrollPane.getBoundingClientRect().height;

                // imgWrapper 이외 형제 요소들의 높이 합산 (네비게이터, 진행률바, 버튼 등)
                let siblingsH = 0;
                for (const child of Array.from(scrollPane.children)) {
                    if (!child.contains(imgWrapper)) {
                        siblingsH += child.getBoundingClientRect().height;
                    }
                }

                // imgWrapper 내부의 이미지 외 요소 (범례, 텍스트 등)
                let inWrapperOthersH = 0;
                for (const child of Array.from(imgWrapper.children)) {
                    if (!child.contains(imgEl) && child !== imgEl) {
                        inWrapperOthersH += child.getBoundingClientRect().height;
                    }
                }

                // 패딩·gap 여유분 (보수적으로 80px)
                const margin = 80;
                const maxH = Math.max(paneH - siblingsH - inWrapperOthersH - margin, 200);

                imgEl.style.maxHeight = `${maxH}px`;
                console.log(
                    `[capture] paneH=${paneH}, siblingsH=${siblingsH}, ` +
                    `inWrapperOthersH=${inWrapperOthersH} → maxH=${maxH}px`
                );
            }"""
        )

        # 레이아웃 재계산 대기
        page.wait_for_timeout(wait_ms)

        # ── 4. 스크롤 잔존 여부 확인 ──────────────────────────────────────────
        scroll_info = page.evaluate(
            """() => ({
                overflowPanes: Array.from(document.querySelectorAll('*')).filter(el => {
                    const s = window.getComputedStyle(el);
                    return (s.overflowY === 'auto' || s.overflowY === 'scroll')
                        && el.scrollHeight > el.clientHeight + 2;
                }).map(el => ({
                    tag: el.tagName,
                    cls: el.className.slice(0, 80),
                    scrollHeight: el.scrollHeight,
                    clientHeight: el.clientHeight,
                }))
            })"""
        )
        if scroll_info["overflowPanes"]:
            print("[!] 스크롤 잔존 요소:")
            for pane in scroll_info["overflowPanes"]:
                print(
                    f"    <{pane['tag']}> scrollH={pane['scrollHeight']} "
                    f"clientH={pane['clientHeight']}  cls={pane['cls']}"
                )
        else:
            print("[OK] 스크롤 없음 -- 뷰포트 fit 확인")

        # ── 5. 스크린샷 ────────────────────────────────────────────────────────
        print(f"[4/5] 스크린샷 저장: {out}")
        page.screenshot(
            path=out,
            clip={"x": 0, "y": 0, "width": VIEWPORT_W, "height": VIEWPORT_H},
            full_page=False,
        )

        browser.close()
        print(f"[5/5] 완료 -> {out}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="ResultViewer Playwright 캡처")
    parser.add_argument("--url",     default=DEFAULT_URL,  help="캡처할 URL")
    parser.add_argument("--out",     default=DEFAULT_OUT,  help="저장 경로 (.png)")
    parser.add_argument("--wait-ms", default=2000, type=int, help="레이아웃 안정 대기 ms")
    args = parser.parse_args()

    try:
        capture(args.url, args.out, args.wait_ms)
    except Exception as e:
        print(f"[ERROR] {e}", file=sys.stderr)
        sys.exit(1)
