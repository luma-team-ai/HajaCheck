"""ai.core.yolo_client.predict 단위 테스트(코드 리뷰 P2) — 공유 YOLO 인스턴스(get_yolo_model,
@lru_cache)에 대한 동시 predict 호출을 락으로 상호 배제하는지 직접 고정한다. 체인 레벨 통합
시나리오는 test_defect_detection_chain.py에도 있다 — 이 파일은 yolo_client.predict() 자체의
동시성 계약만 좁게 검증한다.
"""
import threading
import time

from ai.core.yolo_client import predict


class _FakeModel:
    def __init__(self):
        self.active = 0
        self.max_active = 0
        self._counter_lock = threading.Lock()

    def predict(self, **_kwargs):
        with self._counter_lock:
            self.active += 1
            self.max_active = max(self.max_active, self.active)
        time.sleep(0.05)  # 겹칠 기회를 실제로 만들기 위해 predict 구간을 늘린다
        with self._counter_lock:
            self.active -= 1
        return "result"


def test_predict_직렬화되어_동시진입이없다():
    model = _FakeModel()
    threads = [threading.Thread(target=predict, args=(model,)) for _ in range(6)]

    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert model.max_active == 1


def test_predict_결과를_그대로반환한다():
    model = _FakeModel()

    assert predict(model) == "result"
