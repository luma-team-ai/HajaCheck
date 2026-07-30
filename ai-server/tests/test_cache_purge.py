"""LLM 캐시 주기 purge 검증 (#623 — main.py FastAPI lifespan).

- `_purge_llm_cache_once()`: `ai:cache:*`만 scan_iter+delete로 삭제, `ai:usage:*`는 보존
- 삭제 대상이 없으면 0건 반환(에러 아님)
- redis 오류는 `_purge_llm_cache_once()` 자체는 그대로 전파(호출부 책임 분리) —
  단, 루프(`_purge_llm_cache_loop`)는 그 예외를 삼키고 다음 주기로 넘어간다(앱이 죽지 않음)
- lifespan이 백그라운드 태스크를 시작하고, 앱 종료 시 정상적으로 취소·정리한다
"""
import asyncio
from unittest.mock import MagicMock, patch

import pytest
import redis
from fastapi.testclient import TestClient

import main


class _FakeRedis:
    """scan_iter/delete만 지원하는 최소 인메모리 페이크(테스트 전용)."""

    def __init__(self, keys):
        self._keys = set(keys)
        self.deleted: list[str] = []

    def scan_iter(self, match=None):
        prefix = match.rstrip("*") if match else ""
        for k in list(self._keys):
            if k.startswith(prefix):
                yield k

    def delete(self, *keys):
        self.deleted.extend(keys)
        for k in keys:
            self._keys.discard(k)


@patch("ai.core.llm_client._redis")
def test_purge_llm_cache_once_deletes_only_cache_keys_preserves_usage(mock_redis_factory):
    fake = _FakeRedis(
        {
            "ai:cache:hf:model:0.1:abc123",
            "ai:cache:ocr:BusinessLicenseOcrExtract:def456",
            "ai:usage:20260723",
        }
    )
    mock_redis_factory.return_value = fake

    deleted_count = main._purge_llm_cache_once()

    assert deleted_count == 2
    assert fake._keys == {"ai:usage:20260723"}  # 사용량 집계 키는 보존


@patch("ai.core.llm_client._redis")
def test_purge_llm_cache_once_returns_zero_when_no_cache_keys(mock_redis_factory):
    fake = _FakeRedis({"ai:usage:20260723"})
    mock_redis_factory.return_value = fake

    deleted_count = main._purge_llm_cache_once()

    assert deleted_count == 0
    assert fake._keys == {"ai:usage:20260723"}
    assert fake.deleted == []  # delete() 자체를 호출하지 않음(빈 keys로 호출 안 함)


@patch("ai.core.llm_client._redis")
def test_purge_llm_cache_once_propagates_redis_error_to_caller(mock_redis_factory):
    """함수 자체는 redis 오류를 삼키지 않고 그대로 전파한다 — 삼키는 책임은 루프 쪽에 있다."""
    fake = MagicMock()
    fake.scan_iter.side_effect = redis.RedisError("연결 실패")
    mock_redis_factory.return_value = fake

    with pytest.raises(redis.RedisError):
        main._purge_llm_cache_once()


def test_purge_loop_swallows_purge_errors_and_continues_to_next_cycle():
    """purge 실행 중 예외가 나도 루프는 죽지 않고 다음 sleep 주기로 넘어간다(redis 장애로 앱이 안 죽음)."""
    sleep_calls = {"n": 0}

    async def fake_sleep(_seconds):
        sleep_calls["n"] += 1
        if sleep_calls["n"] >= 2:
            raise asyncio.CancelledError()

    with patch("main.asyncio.sleep", side_effect=fake_sleep), patch(
        "main._purge_llm_cache_once", side_effect=RuntimeError("redis 다운")
    ) as mock_purge_once:

        async def run():
            with pytest.raises(asyncio.CancelledError):
                await main._purge_llm_cache_loop()

        asyncio.run(run())

        # 1번째 주기(sleep 1회차 이후)에서 purge 예외가 나도, 2번째 sleep까지 루프가 정상 진행됐다.
        assert mock_purge_once.call_count == 1


def test_purge_loop_runs_purge_on_each_cycle_when_no_errors():
    sleep_calls = {"n": 0}

    async def fake_sleep(_seconds):
        sleep_calls["n"] += 1
        if sleep_calls["n"] >= 3:
            raise asyncio.CancelledError()

    with patch("main.asyncio.sleep", side_effect=fake_sleep), patch(
        "main._purge_llm_cache_once", return_value=0
    ) as mock_purge_once:

        async def run():
            with pytest.raises(asyncio.CancelledError):
                await main._purge_llm_cache_loop()

        asyncio.run(run())

        assert mock_purge_once.call_count == 2  # 3번째 sleep에서 취소되기 전까지 2회 실행


def test_lifespan_starts_background_task_and_cancels_cleanly_on_shutdown():
    """앱 기동 시 백그라운드 purge 루프가 시작되고, 종료 시 task.cancel()로 정리돼 앱이 정상 종료된다.

    실제 24h 대기를 태우지 않도록 `_purge_llm_cache_loop`를 무한 대기(Event().wait())로 대체해
    "취소 없이는 절대 스스로 끝나지 않는 태스크"를 흉내내고, `with TestClient(...)`를 빠져나올 때
    (lifespan의 finally에서 cancel + await) 예외 없이 정리되는지만 확인한다.
    """

    async def fake_loop():
        await asyncio.Event().wait()  # cancel 전까지 끝나지 않음

    with patch("main._purge_llm_cache_loop", side_effect=fake_loop):
        with TestClient(main.app) as client:
            res = client.get("/health")
            assert res.status_code == 200
        # with 블록 종료 시 lifespan 정리가 예외 없이 끝나면 성공(행이나 에러 없이 여기 도달)
