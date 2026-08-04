"""LangSmith 전송 페이로드 캡처 테스트 공통 헬퍼 (#1585 P3).

`test_langsmith_tracing_exclusion.py`(전송 억제 검증)와 `test_langsmith_pii_scrub.py`
(PII 부분 치환 검증)는 둘 다 "실제로 LangSmith로 나가려던 바이트"를 검사한다. 그 캡처
절차(zstd 해제·백그라운드 flush 대기·프로세스 단위 싱글턴 초기화)는 완전히 동일한데
한동안 두 파일에 복붙돼 있었다 — 한쪽만 고치면 동작이 갈리므로 여기로 추출했다.

`conftest.py`가 아니라 별도 모듈인 이유: 싱글턴 초기화는 **이 두 파일에서만** autouse여야
한다. conftest에 fixture를 두면 tests/ 전체에 적용돼 무관한 테스트의 실행 조건까지 바뀐다.
각 테스트 파일이 얇은 autouse fixture를 두고 `reset_langsmith_process_state()`를 호출한다.
"""
import io
import time

import zstandard
from langsmith import run_trees as ls_run_trees
from langsmith import utils as ls_utils

_ZSTD_MAGIC = b"\x28\xb5\x2f\xfd"


def extract_bytes(data) -> bytes:
    """LangSmith 전송 페이로드를 실제(압축 해제된) 바이트로 변환.

    소규모 배치는 bytes로 오지만, 대규모 트레이스는 zstd로 압축되고
    `_io.BytesIO`로 온다 — 압축 해제 없이는 민감 문자열을 검색할 수 없다.
    """
    if isinstance(data, bytes):
        raw = data
    elif hasattr(data, "read"):
        pos = data.tell() if hasattr(data, "tell") else None
        content = data.read()
        if pos is not None and hasattr(data, "seek"):
            data.seek(pos)
        raw = content if isinstance(content, bytes) else str(content).encode("utf-8")
    else:
        raw = str(data).encode("utf-8")

    if raw[:4] == _ZSTD_MAGIC:
        return zstandard.ZstdDecompressor().stream_reader(io.BytesIO(raw)).read()
    return raw


def wait_for_flush(captured: list, *, timeout: float = 8.0, settle: float = 0.4) -> None:
    """백그라운드 전송이 끝날 때까지 대기.

    captured 길이가 settle 초 동안 변하지 않으면 flush 완료로 본다.
    """
    deadline = time.time() + timeout
    last_len = -1
    settle_start = time.time()
    while time.time() < deadline:
        if len(captured) != last_len:
            last_len = len(captured)
            settle_start = time.time()
        elif captured and time.time() - settle_start >= settle:
            return
        time.sleep(0.05)


def reset_langsmith_process_state() -> None:
    """LangSmith 싱글턴 및 env 판정 LRU 캐시를 초기화.

    LangSmith 클라이언트와 env 판정이 프로세스 단위로 캐시되므로,
    한 테스트의 설정이 다음 테스트로 새어 들어오는 것을 방지한다.
    """
    ls_utils.get_env_var.cache_clear()
    ls_run_trees._CLIENT = None
