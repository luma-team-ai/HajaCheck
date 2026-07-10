"""공통 MLflow 트래킹 헬퍼 — AI-DL 학습 실험 기록용.

로컬/Colab 어디서든 동일하게 사용. 트래킹 서버 없이 로컬 파일(ai-dl/mlruns, 실행 위치와 무관하게 고정)에 기록 —
필요해지면(07/24 AI 운영 서버 세팅 때) MLFLOW_TRACKING_URI 환경변수만 바꾸면 원격 서버로 전환.

사용 예 (이 파일을 학습 스크립트와 같은 폴더에 두고):
    from tracking import start_run

    with start_run(dataset_version="v1", notes="1차 학습"):
        for epoch in range(epochs):
            ...
            log_epoch_metrics(epoch, mAP=0.71, loss=0.32)
"""
import os
from contextlib import contextmanager
from datetime import date
from pathlib import Path

import mlflow

# MLflow에서 실험(experiment)은 "같은 목적의 실행(run)들을 모아두는 폴더" 개념.
# 이 이름으로 통일해야 팀원이 각자 실행한 run이 한 실험 안에 모여서 서로 비교가 됨
# (이름을 다르게 하면 MLflow가 별개 실험으로 취급해서 UI에서 안 보임).
EXPERIMENT_NAME = "hajacheck-defect-detection"

# 실행 위치(cwd)에 상관없이 항상 이 파일 기준 위치에 기록 — 어디서 스크립트를 돌려도 실험이 한곳에 모이게.
# (주의: cwd 기준 상대경로("./mlruns")로 하면 스크립트를 다른 폴더에서 실행할 때마다
#  기록 위치가 달라져서 실험이 흩어지는 버그가 실제로 있었음 — 그래서 파일 위치 기준으로 고정)
DEFAULT_TRACKING_DIR = Path(__file__).resolve().parent / "mlruns"


def _run_name(dataset_version: str) -> str:
    # PRD §6.2 모델 버전 관리 규칙과 동일: {날짜}_{데이터셋버전}
    # 예: "20260710_v1" — run 목록에서 언제·어떤 데이터셋으로 돌렸는지 이름만 보고 알 수 있게
    return f"{date.today():%Y%m%d}_{dataset_version}"


@contextmanager
def start_run(dataset_version: str, notes: str = ""):
    """학습 실행(run) 하나를 시작 — with 블록 안의 학습 코드 전체가 이 run 하나로 기록됨.

    - MLFLOW_TRACKING_URI 환경변수가 있으면 그쪽(원격 서버 등)에 기록, 없으면 로컬 파일 기본값 사용
      → Colab에서 Google Drive에 남기고 싶으면 이 함수 호출 전에
        os.environ["MLFLOW_TRACKING_URI"] = "/content/drive/MyDrive/hajacheck-mlruns" 로 지정하면 됨
    - dataset_version, notes는 태그로 저장돼서 나중에 MLflow UI에서 run 목록 볼 때 구분 기준이 됨
    - with 블록을 벗어나면(정상 종료든 예외든) run이 자동으로 마감 처리됨 — 수동으로 끝낼 필요 없음
    """
    mlflow.set_tracking_uri(os.getenv("MLFLOW_TRACKING_URI", DEFAULT_TRACKING_DIR.as_uri()))
    mlflow.set_experiment(EXPERIMENT_NAME)
    with mlflow.start_run(run_name=_run_name(dataset_version)) as run:
        mlflow.set_tag("dataset_version", dataset_version)
        if notes:
            mlflow.set_tag("notes", notes)
        yield run


def log_epoch_metrics(epoch: int, mAP: float, loss: float, **extra) -> None:
    """매 epoch(또는 원하는 주기)마다 호출 — mAP/loss가 epoch별로 어떻게 변했는지 곡선으로 남음.

    step=epoch로 저장하기 때문에 MLflow UI에서 x축이 epoch인 그래프가 자동으로 그려짐.
    mAP·loss 말고 다른 지표도 같이 남기고 싶으면 키워드 인자로 추가하면 됨:
        log_epoch_metrics(epoch, mAP=0.7, loss=0.3, precision=0.8, recall=0.75)
    """
    mlflow.log_metric("mAP", mAP, step=epoch)
    mlflow.log_metric("loss", loss, step=epoch)
    for key, value in extra.items():
        mlflow.log_metric(key, value, step=epoch)


def log_hyperparams(**params) -> None:
    """학습 시작 전 딱 한 번 호출 — 이 run에 어떤 설정으로 학습했는지 기록.

    예: log_hyperparams(model="yolov8s-seg", epochs=50, lr=0.001, batch_size=16)
    나중에 여러 run을 MLflow UI에서 비교할 때 "어떤 하이퍼파라미터 조합이 mAP가 더 높았는지"
    표로 바로 볼 수 있음 — 이게 실험 추적의 핵심 목적.
    """
    mlflow.log_params(params)


def log_artifact(path: str) -> None:
    """모델 체크포인트·config 등 파일을 실험에 첨부 (예: log_artifact("best.pt")).

    주의: 파일을 통째로 복사하는 작업이라 매 epoch마다 부르면 느려짐.
    학습 다 끝난 뒤 best/final 체크포인트 한 번만 부르는 걸 권장.
    """
    mlflow.log_artifact(path)
