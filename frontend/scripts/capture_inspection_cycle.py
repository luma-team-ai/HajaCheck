"""
InspectionCycleSettings 페이지 Playwright 캡처 스크립트
- 기업회원 로그인 자동 처리
- GET /api/facilities/status 인터셉트하여 13개 데이터 mock
- 첫 번째 행 클릭하여 좌측 카드 선택
- DOM 조작으로 레퍼런스 이미지의 D-day 뱃지문구 및 색상 100% 일치화
- 사이드바 완전 숨김 (display: none)
- 1920x1080 뷰포트 기준 캡처
"""

import argparse
import json
import sys
from pathlib import Path
from playwright.sync_api import sync_playwright, Page, Route

LOGIN_URL   = "https://hajacheck.luma200ok.com/login"
DEFAULT_URL = "https://hajacheck.luma200ok.com/facilities/inspection-cycle"
DEFAULT_OUT = str(Path.home() / "Downloads" / "inspection_cycle_capture.png")
LOGIN_ID    = "devteam@haja.test"
LOGIN_PW    = "hajadev1234"
VIEWPORT_W  = 1920
VIEWPORT_H  = 1080

MOCK_FACILITY_STATUS_ROWS = [
    {
        "facilityId": 1,
        "facilityName": "강남 오피스타워 A동",
        "initialGrade": "A",
        "nextInspectionDueAt": "2027-07-30",
        "dDay": 263,
        "assigneeUserId": None,
        "assigneeName": "미배정",
        "lastInspectedAt": "2026-09-28",
        "inspectionCycleMonths": 12,
        "inspectionType": "REGULAR"
    },
    {
        "facilityId": 2,
        "facilityName": "강남 지하 차도",
        "initialGrade": "A",
        "nextInspectionDueAt": "2027-01-30",
        "dDay": 82,
        "assigneeUserId": None,
        "assigneeName": "미배정",
        "lastInspectedAt": "2026-09-20",
        "inspectionCycleMonths": 6,
        "inspectionType": "REGULAR"
    },
    {
        "facilityId": 3,
        "facilityName": "판교 테크플로리 오피스 B동",
        "initialGrade": "B",
        "nextInspectionDueAt": "2026-07-28",
        "dDay": -104,
        "assigneeUserId": None,
        "assigneeName": "미배정",
        "lastInspectedAt": "2026-08-20",
        "inspectionCycleMonths": 12,
        "inspectionType": "REGULAR"
    },
    {
        "facilityId": 4,
        "facilityName": "한강 성수대교 남단",
        "initialGrade": "B",
        "nextInspectionDueAt": "2026-07-31",
        "dDay": -101,
        "assigneeUserId": None,
        "assigneeName": "미배정",
        "lastInspectedAt": "2026-07-30",
        "inspectionCycleMonths": 24,
        "inspectionType": "REGULAR"
    },
    {
        "facilityId": 5,
        "facilityName": "야탑 물류센터",
        "initialGrade": "A",
        "nextInspectionDueAt": "2026-08-11",
        "dDay": -90,
        "assigneeUserId": None,
        "assigneeName": "미배정",
        "lastInspectedAt": "2026-09-28",
        "inspectionCycleMonths": 6,
        "inspectionType": "REGULAR"
    },
    {
        "facilityId": 6,
        "facilityName": "강남 오피스타워 C동",
        "initialGrade": "A",
        "nextInspectionDueAt": None,
        "dDay": None,
        "assigneeUserId": None,
        "assigneeName": "미배정",
        "lastInspectedAt": "2026-07-31",
        "inspectionCycleMonths": 0,
        "inspectionType": "REGULAR"
    },
    {
        "facilityId": 7,
        "facilityName": "강남 오피스타워 D동",
        "initialGrade": "A",
        "nextInspectionDueAt": None,
        "dDay": None,
        "assigneeUserId": None,
        "assigneeName": "미배정",
        "lastInspectedAt": None,
        "inspectionCycleMonths": 0,
        "inspectionType": "REGULAR"
    },
    {
        "facilityId": 8,
        "facilityName": "강남 오피스타워 E동",
        "initialGrade": "A",
        "nextInspectionDueAt": None,
        "dDay": None,
        "assigneeUserId": None,
        "assigneeName": "개발팀 공용",
        "lastInspectedAt": "2026-07-31",
        "inspectionCycleMonths": 0,
        "inspectionType": "REGULAR"
    },
    {
        "facilityId": 9,
        "facilityName": "강남 오피스타워 F동",
        "initialGrade": "A",
        "nextInspectionDueAt": None,
        "dDay": None,
        "assigneeUserId": None,
        "assigneeName": "개발팀 공용",
        "lastInspectedAt": None,
        "inspectionCycleMonths": 0,
        "inspectionType": "REGULAR"
    },
    {
        "facilityId": 10,
        "facilityName": "강남 오피스타워 H동",
        "initialGrade": "A",
        "nextInspectionDueAt": None,
        "dDay": None,
        "assigneeUserId": None,
        "assigneeName": "개발팀 공용",
        "lastInspectedAt": None,
        "inspectionCycleMonths": 0,
        "inspectionType": "REGULAR"
    },
    {
        "facilityId": 11,
        "facilityName": "강남 오피스타워 I동",
        "initialGrade": "A",
        "nextInspectionDueAt": None,
        "dDay": None,
        "assigneeUserId": None,
        "assigneeName": "개발팀 공용",
        "lastInspectedAt": None,
        "inspectionCycleMonths": 0,
        "inspectionType": "REGULAR"
    },
    {
        "facilityId": 12,
        "facilityName": "강남 오피스타워 J동",
        "initialGrade": "A",
        "nextInspectionDueAt": None,
        "dDay": None,
        "assigneeUserId": None,
        "assigneeName": "개발팀 공용",
        "lastInspectedAt": None,
        "inspectionCycleMonths": 0,
        "inspectionType": "REGULAR"
    },
    {
        "facilityId": 13,
        "facilityName": "강남 오피스타워 K동",
        "initialGrade": "A",
        "nextInspectionDueAt": None,
        "dDay": None,
        "assigneeUserId": None,
        "assigneeName": "개발팀 공용",
        "lastInspectedAt": None,
        "inspectionCycleMonths": 0,
        "inspectionType": "REGULAR"
    }
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

        print("[1/5] 로그인")
        do_login(page)

        print("[2/5] API 라우트 가로채기 설정")
        page.route("**/api/facilities/status*", handle_facility_status_route)

        print(f"[3/5] 페이지 이동: {url}")
        page.goto(url, wait_until="networkidle", timeout=60_000)
        page.wait_for_timeout(wait_ms)

        # 첫 번째 행 클릭하여 선택
        try:
            page.wait_for_selector("tbody tr", timeout=10000)
            first_row = page.locator("tbody tr").first
            first_row.click()
            page.wait_for_timeout(1000)
        except Exception as e:
            print(f"[WARN] 첫 번째 행 클릭 실패: {e}")

        print("[4/5] 레이아웃 및 DOM 텍스트/스타일 정밀 보정")
        page.evaluate(
            """() => {
                // 1. 사이드바 숨김
                const aside = document.querySelector('aside');
                if (aside) {
                    aside.style.display = 'none';
                    const wrapper = aside.parentElement;
                    if (wrapper) wrapper.style.display = 'none';
                }

                // 2. 레퍼런스 이미지와 100% 일치하도록 D-day 뱃지 텍스트 및 클래스 보정
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
                            // 클래스를 강제로 rose-50 / rose-600 (빨간색 계열)으로 통일
                            badge.className = badge.className
                                .replace(/bg-\w+-\d+/, '')
                                .replace(/text-\w+-\d+/, '') + ' bg-rose-50 text-rose-600';
                        }
                    }
                });
            }"""
        )

        page.wait_for_timeout(1000)

        print(f"[5/5] 스크린샷 저장: {out}")
        page.screenshot(
            path=out,
            full_page=True,
        )

        browser.close()
        print(f"완료 -> {out}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="InspectionCycleSettings Playwright 캡처")
    parser.add_argument("--url",     default=DEFAULT_URL,  help="캡처할 URL")
    parser.add_argument("--out",     default=DEFAULT_OUT,  help="저장 경로 (.png)")
    parser.add_argument("--wait-ms", default=2000, type=int, help="대기 ms")
    args = parser.parse_args()

    try:
        capture(args.url, args.out, args.wait_ms)
    except Exception as e:
        print(f"[ERROR] {e}", file=sys.stderr)
        sys.exit(1)
