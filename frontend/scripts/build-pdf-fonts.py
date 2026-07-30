#!/usr/bin/env python3
"""보고서 PDF 내보내기용 한글 폰트 서브셋 생성 (src/assets/fonts/*.subset.ttf).

## 왜 이 스크립트가 필요한가

보고서 PDF는 관공서 정밀안전진단 표준서식(한컴오피스 산출물)의 조판을 따른다. 원본 서식은
맑은 고딕(Malgun Gothic)을 쓰지만 이 폰트는 Microsoft 소유로 .ttf 재배포가 불가하므로,
디자인 계열이 가장 가까운 자유 폰트인 Noto Sans KR(본고딕, OFL)로 대체한다.

그런데 npm 배포본(`@fontsource/noto-sans-kr`)은 **woff2만** 제공하고, jsPDF의 `addFont`는
TTF/OTF만 받는다. 그래서 Google Fonts 원본(가변 폰트, glyf 기반)을 받아
  1) `wght` 축을 400/700으로 정적 인스턴스화하고,
  2) 보고서에 실제로 쓰이는 문자 집합으로 서브셋한다.
결과는 weight당 약 2.5MB로, 이전에 쓰던 Pretendard TTF(약 2.7MB)보다 오히려 작다.

## 실행

    pip install "fonttools[woff]" brotli
    python frontend/scripts/build-pdf-fonts.py

산출물(`src/assets/fonts/NotoSansKR-{Regular,Bold}.subset.ttf`)은 레포에 커밋한다 —
빌드 시점에 네트워크를 타지 않게 하려는 의도적 결정이다. 폰트를 갱신할 때만 이 스크립트를 다시 돌린다.
소비처는 `src/features/report/utils/exportReportToPdf.ts` (Vite `?url` import).
"""
from __future__ import annotations

import sys
import urllib.request
from pathlib import Path
from tempfile import TemporaryDirectory

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

# Google Fonts 공식 저장소의 Noto Sans KR 가변 폰트(glyf outline — jsPDF가 읽을 수 있음).
# OTF/CFF 판본은 jsPDF에서 지원이 불완전하므로 반드시 TTF 판본을 쓴다.
SOURCE_URL = "https://github.com/google/fonts/raw/main/ofl/notosanskr/NotoSansKR%5Bwght%5D.ttf"

OUTPUT_DIR = Path(__file__).resolve().parent.parent / "src" / "assets" / "fonts"

# 정적 인스턴스로 뽑을 weight — 표준서식 원본이 Regular/Bold 2종만 쓰므로 그 이상은 만들지 않는다.
WEIGHTS = {"Regular": 400, "Bold": 700}

# 서브셋 문자 집합. 한글은 음절 전체(11,172자)를 넣는다 — 시설물명·하자 설명이 사용자 입력이라
# 상용 2,350자로 줄이면 희귀 음절에서 두부(tofu)가 날 수 있다. 그 대가로 늘어나는 용량보다
# 렌더 실패 리스크가 크다는 판단이다.
UNICODES = ",".join(
    [
        "U+0020-007E",  # ASCII
        "U+00B0,U+00B7,U+00D7",  # ° · ×
        "U+2013,U+2014",  # – —
        "U+2018-201D",  # ‘ ’ “ ”
        "U+2022,U+2026",  # • …
        "U+2103,U+2109",  # ℃ ℉
        "U+2192,U+223C",  # → ∼
        "U+203B",  # ※
        "U+3001,U+3002",  # 、 。
        "U+3008-3011",  # 〈〉《》「」『』
        "U+3131-318E",  # 한글 호환 자모 (ㆍ U+318D 포함)
        "U+3399-33A5",  # 단위 기호 (㎜ U+339C, ㎡ U+33A1 등)
        "U+AC00-D7A3",  # 한글 음절 전체
    ]
)


def build() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory() as tmp:
        source = Path(tmp) / "NotoSansKR-VF.ttf"
        print(f"· 원본 다운로드 → {SOURCE_URL}")
        urllib.request.urlretrieve(SOURCE_URL, source)  # noqa: S310 — 고정된 신뢰 URL

        for name, weight in WEIGHTS.items():
            static = Path(tmp) / f"NotoSansKR-{name}.ttf"
            font = instantiateVariableFont(
                TTFont(source), {"wght": weight}, inplace=True, updateFontNames=True
            )
            font.save(static)

            output = OUTPUT_DIR / f"NotoSansKR-{name}.subset.ttf"
            subset.main(
                [
                    str(static),
                    f"--unicodes={UNICODES}",
                    "--layout-features=*",
                    "--no-hinting",
                    "--desubroutinize",
                    f"--output-file={output}",
                ]
            )
            print(f"· {output.name} — {output.stat().st_size / 1024 / 1024:.2f}MB")


if __name__ == "__main__":
    try:
        build()
    except Exception as error:  # noqa: BLE001 — CLI 스크립트라 사용자에게 원인만 알리고 종료
        print(f"폰트 생성 실패: {error}", file=sys.stderr)
        sys.exit(1)
