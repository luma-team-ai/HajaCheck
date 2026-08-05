"""
InspectionDefects 페이지 Playwright 캡처 스크립트
- 기업회원 로그인 자동 처리
- 사이드바 완전 숨김 (display: none)
- 1920x1080 뷰포트 기준 캡처
"""

import argparse
import sys
from pathlib import Path
from playwright.sync_api import sync_playwright, Page

LOGIN_URL   = "https://hajacheck.luma200ok.com/login"
DEFAULT_URL = "https://hajacheck.luma200ok.com/inspections/4/defects"
DEFAULT_OUT = str(Path.home() / "Downloads" / "defects_capture.png")
LOGIN_ID    = "devteam@haja.test"
LOGIN_PW    = "hajadev1234"
VIEWPORT_W  = 1920
VIEWPORT_H  = 1080


def do_login(page: Page) -> None:
    print(f"  > 로그인 페이지 이동: {LOGIN_URL}")
    page.goto(LOGIN_URL, wait_until="networkidle", timeout=60_000)
    page.get_by_role("tab", name="기업회원").click()
    page.locator("#company-login-id").fill(LOGIN_ID)
    page.locator("#company-login-password").fill(LOGIN_PW)

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

        print("[1/4] 로그인")
        do_login(page)

        print(f"[2/4] 페이지 이동: {url}")
        page.goto(url, wait_until="networkidle", timeout=60_000)
        page.wait_for_timeout(wait_ms)

        print("[3/4] 레이아웃 조정 (사이드바 숨김)")
        page.evaluate(
            """() => {
                const aside = document.querySelector('aside');
                if (aside) {
                    aside.style.display = 'none';
                    const wrapper = aside.parentElement;
                    if (wrapper) wrapper.style.display = 'none';
                }
            }"""
        )

        page.wait_for_timeout(1000)

        print(f"[4/4] 스크린샷 저장: {out}")
        page.screenshot(
            path=out,
            full_page=True,
        )

        browser.close()
        print(f"완료 -> {out}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="InspectionDefects Playwright 캡처")
    parser.add_argument("--url",     default=DEFAULT_URL,  help="캡처할 URL")
    parser.add_argument("--out",     default=DEFAULT_OUT,  help="저장 경로 (.png)")
    parser.add_argument("--wait-ms", default=2000, type=int, help="대기 ms")
    args = parser.parse_args()

    try:
        capture(args.url, args.out, args.wait_ms)
    except Exception as e:
        print(f"[ERROR] {e}", file=sys.stderr)
        sys.exit(1)
