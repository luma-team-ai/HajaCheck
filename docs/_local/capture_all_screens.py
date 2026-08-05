"""
HajaCheck 3종 주요 화면 일괄 Playwright 캡처 스크립트

캡처 대상:
1. Result Viewer (/inspections/27/viewer) -> viewer_capture.png
   - 사이드바 숨김 + 스크롤 없이 이미지 뷰포트 fit
2. Defect List (/inspections/4/defects) -> defects_capture.png
   - 사이드바 숨김
3. Inspection Cycle Settings (/facilities/inspection-cycle) -> inspection_cycle_capture.png
   - 사이드바 숨김 + API Mocking (13개 시설물 데이터) + DOM 뱃지 보정

사용법:
  python docs/_local/capture_all_screens.py
  python docs/_local/capture_all_screens.py --out-dir "C:/Custom/Path"
"""

import argparse
import json
import sys
from pathlib import Path
from playwright.sync_api import sync_playwright, Page, Route

# 기본 설정
BASE_URL    = "https://hajacheck.luma200ok.com"
LOGIN_URL   = f"{BASE_URL}/login"
LOGIN_ID    = "devteam@haja.test"
LOGIN_PW    = "hajadev1234"
VIEWPORT_W  = 1920
VIEWPORT_H  = 1080

MOCK_FACILITY_STATUS_ROWS = [
    {"facilityId": 1, "facilityName": "강남 오피스타워 A동", "initialGrade": "A", "nextInspectionDueAt": "2027-07-30", "dDay": 263, "assigneeUserId": None, "assigneeName": "미배정", "lastInspectedAt": "2026-09-28", "inspectionCycleMonths": 12, "inspectionType": "REGULAR"},
    {"facilityId": 2, "facilityName": "강남 지하 차도", "initialGrade": "A", "nextInspectionDueAt": "2027-01-30", "dDay": 82, "assigneeUserId": None, "assigneeName": "미배정", "lastInspectedAt": "2026-09-20", "inspectionCycleMonths": 6, "inspectionType": "REGULAR"},
    {"facilityId": 3, "facilityName": "판교 테크플로리 오피스 B동", "initialGrade": "B", "nextInspectionDueAt": "2026-07-28", "dDay": -104, "assigneeUserId": None, "assigneeName": "미배정", "lastInspectedAt": "2026-08-20", "inspectionCycleMonths": 12, "inspectionType": "REGULAR"},
    {"facilityId": 4, "facilityName": "한강 성수대교 남단", "initialGrade": "B", "nextInspectionDueAt": "2026-07-31", "dDay": -101, "assigneeUserId": None, "assigneeName": "미배정", "lastInspectedAt": "2026-07-30", "inspectionCycleMonths": 24, "inspectionType": "REGULAR"},
    {"facilityId": 5, "facilityName": "야탑 물류센터", "initialGrade": "A", "nextInspectionDueAt": "2026-08-11", "dDay": -90, "assigneeUserId": None, "assigneeName": "미배정", "lastInspectedAt": "2026-09-28", "inspectionCycleMonths": 6, "inspectionType": "REGULAR"},
    {"facilityId": 6, "facilityName": "강남 오피스타워 C동", "initialGrade": "A", "nextInspectionDueAt": None, "dDay": None, "assigneeUserId": None, "assigneeName": "미배정", "lastInspectedAt": "2026-07-31", "inspectionCycleMonths": 0, "inspectionType": "REGULAR"},
    {"facilityId": 7, "facilityName": "강남 오피스타워 D동", "initialGrade": "A", "nextInspectionDueAt": None, "dDay": None, "assigneeUserId": None, "assigneeName": "미배정", "lastInspectedAt": None, "inspectionCycleMonths": 0, "inspectionType": "REGULAR"},
    {"facilityId": 8, "facilityName": "강남 오피스타워 E동", "initialGrade": "A", "nextInspectionDueAt": None, "dDay": None, "assigneeUserId": None, "assigneeName": "개발팀 공용", "lastInspectedAt": "2026-07-31", "inspectionCycleMonths": 0, "inspectionType": "REGULAR"},
    {"facilityId": 9, "facilityName": "강남 오피스타워 F동", "initialGrade": "A", "nextInspectionDueAt": None, "dDay": None, "assigneeUserId": None, "assigneeName": "개발팀 공용", "lastInspectedAt": None, "inspectionCycleMonths": 0, "inspectionType": "REGULAR"},
    {"facilityId": 10, "facilityName": "강남 오피스타워 H동", "initialGrade": "A", "nextInspectionDueAt": None, "dDay": None, "assigneeUserId": None, "assigneeName": "개발팀 공용", "lastInspectedAt": None, "inspectionCycleMonths": 0, "inspectionType": "REGULAR"},
    {"facilityId": 11, "facilityName": "강남 오피스타워 I동", "initialGrade": "A", "nextInspectionDueAt": None, "dDay": None, "assigneeUserId": None, "assigneeName": "개발팀 공용", "lastInspectedAt": None, "inspectionCycleMonths": 0, "inspectionType": "REGULAR"},
    {"facilityId": 12, "facilityName": "강남 오피스타워 J동", "initialGrade": "A", "nextInspectionDueAt": None, "dDay": None, "assigneeUserId": None, "assigneeName": "개발팀 공용", "lastInspectedAt": None, "inspectionCycleMonths": 0, "inspectionType": "REGULAR"},
    {"facilityId": 13, "facilityName": "강남 오피스타워 K동", "initialGrade": "A", "nextInspectionDueAt": None, "dDay": None, "assigneeUserId": None, "assigneeName": "개발팀 공용", "lastInspectedAt": None, "inspectionCycleMonths": 0, "inspectionType": "REGULAR"}
]


def handle_facility_status_route(route: Route):
    response_body = {
        "success": True,
        "data": MOCK_FACILITY_STATUS_ROWS,
        "error": None
    }
    route.fulfill(
        status=200,
        content_type="application/json",
        body=json.dumps(response_body, ensure_ascii=False)
    )


def do_login(page: Page) -> None:
    print(f"[LOGIN] {LOGIN_URL} 이동 중...")
    page.goto(LOGIN_URL, wait_until="networkidle", timeout=60_000)
    page.get_by_role("tab", name="기업회원").click()
    page.locator("#company-login-id").fill(LOGIN_ID)
    page.locator("#company-login-password").fill(LOGIN_PW)

    with page.expect_navigation(wait_until="networkidle", timeout=30_000):
        page.get_by_role("button", name="로그인").click()

    print(f"[LOGIN] 완료 -> {page.url}")


def hide_sidebar(page: Page) -> None:
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


def capture_viewer(page: Page, out_path: Path) -> None:
    target_url = f"{BASE_URL}/inspections/27/viewer"
    print(f"[1/3] Viewer 캡처 시작: {target_url}")
    page.goto(target_url, wait_until="networkidle", timeout=60_000)

    page.wait_for_selector('img[alt="점검 이미지"]', timeout=30_000)
    page.wait_for_function(
        """() => {
            const img = document.querySelector('img[alt="점검 이미지"]');
            return img && img.complete && img.naturalHeight > 0;
        }""",
        timeout=30_000
    )

    hide_sidebar(page)

    page.evaluate(
        """() => {
            const imgEl = document.querySelector('img[alt="점검 이미지"]');
            if (!imgEl) return;
            const imgWrapper = imgEl.closest('.flex-1.flex-col.items-center');
            if (!imgWrapper) { imgEl.style.maxHeight = '500px'; return; }
            const scrollPane = imgWrapper.closest('[class*="overflow-y-auto"]');
            if (!scrollPane) { imgEl.style.maxHeight = '500px'; return; }

            const paneH = scrollPane.getBoundingClientRect().height;
            let siblingsH = 0;
            for (const child of Array.from(scrollPane.children)) {
                if (!child.contains(imgWrapper)) {
                    siblingsH += child.getBoundingClientRect().height;
                }
            }
            let inWrapperOthersH = 0;
            for (const child of Array.from(imgWrapper.children)) {
                if (!child.contains(imgEl) && child !== imgEl) {
                    inWrapperOthersH += child.getBoundingClientRect().height;
                }
            }
            const margin = 80;
            const maxH = Math.max(paneH - siblingsH - inWrapperOthersH - margin, 200);
            imgEl.style.maxHeight = `${maxH}px`;
        }"""
    )

    page.wait_for_timeout(1500)
    page.screenshot(
        path=str(out_path),
        clip={"x": 0, "y": 0, "width": VIEWPORT_W, "height": VIEWPORT_H},
        full_page=False,
    )
    print(f"  [OK] Saved -> {out_path}")


def capture_defects(page: Page, out_path: Path) -> None:
    target_url = f"{BASE_URL}/inspections/4/defects"
    print(f"[2/3] Defects 캡처 시작: {target_url}")
    page.goto(target_url, wait_until="networkidle", timeout=60_000)
    page.wait_for_timeout(1500)

    hide_sidebar(page)
    page.wait_for_timeout(500)

    page.screenshot(path=str(out_path), full_page=True)
    print(f"  [OK] Saved -> {out_path}")


def capture_inspection_cycle(page: Page, out_path: Path) -> None:
    target_url = f"{BASE_URL}/facilities/inspection-cycle"
    print(f"[3/3] Inspection Cycle 캡처 시작: {target_url}")

    page.route("**/api/facilities/status*", handle_facility_status_route)
    page.goto(target_url, wait_until="networkidle", timeout=60_000)
    page.wait_for_timeout(1500)

    try:
        page.wait_for_selector("tbody tr", timeout=10000)
        first_row = page.locator("tbody tr").first
        first_row.click()
        page.wait_for_timeout(500)
    except Exception as e:
        print(f"  [WARN] Row click skipped: {e}")

    hide_sidebar(page)

    page.evaluate(
        """() => {
            const rows = Array.from(document.querySelectorAll('tbody tr'));
            const overrides = {
                2: { label: '• D+104' },
                3: { label: '• D+101' },
                4: { label: '• D+90'  }
            };
            rows.forEach((tr, index) => {
                if (overrides[index]) {
                    const badge = tr.querySelector('span[class*="rounded-full"]');
                    if (badge) {
                        badge.textContent = overrides[index].label;
                        badge.className = badge.className
                            .replace(/bg-\w+-\d+/, '')
                            .replace(/text-\w+-\d+/, '') + ' bg-rose-50 text-rose-600';
                    }
                }
            });
        }"""
    )

    page.wait_for_timeout(1000)
    page.screenshot(path=str(out_path), full_page=True)
    print(f"  [OK] Saved -> {out_path}")


def main():
    default_out_dir = str(Path.home() / "Downloads")
    parser = argparse.ArgumentParser(description="HajaCheck 3종 주요 화면 일괄 Playwright 캡처 스크립트")
    parser.add_argument("--out-dir", default=default_out_dir, help="저장 디렉토리 경로")
    args = parser.parse_args()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    viewer_out = out_dir / "viewer_capture.png"
    defects_out = out_dir / "defects_capture.png"
    cycle_out = out_dir / "inspection_cycle_capture.png"

    print("=== HajaCheck 일괄 스크린샷 캡처 시작 ===")
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(
            viewport={"width": VIEWPORT_W, "height": VIEWPORT_H},
            device_scale_factor=1,
        )
        page = ctx.new_page()

        do_login(page)

        capture_viewer(page, viewer_out)
        capture_defects(page, defects_out)
        capture_inspection_cycle(page, cycle_out)

        browser.close()

    print("=== 모든 캡처가 성공적으로 완료되었습니다! ===")
    print(f"1. {viewer_out}")
    print(f"2. {defects_out}")
    print(f"3. {cycle_out}")


if __name__ == "__main__":
    main()
