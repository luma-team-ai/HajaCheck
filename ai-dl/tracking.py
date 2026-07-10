"""공통 MLflow 트래킹 헬퍼 — AI-DL 학습 실험 기록용.

로컬/Colab 어디서든 동일하게 사용. 트래킹 서버 없이 로컬 파일(ml/mlruns, 실행 위치와 무관하게 고정)에 기록 —
필요해지면(07/24 AI 운영 서버 세팅 때) MLFLOW_TRACKING_URI 환경변수만 바꾸면 원격 서버로 전환.

사용 예:
    from ml.tracking import start_run

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

EXPERIMENT_NAME = "hajacheck-defect-detection"
# 실행 위치(cwd)에 상관없이 항상 이 파일 기준 위치에 기록 — 어디서 스크립트를 돌려도 실험이 한곳에 모이게
DEFAULT_TRACKING_DIR = Path(__file__).resolve().parent / "mlruns"


def _run_name(dataset_version: str) -> str:
    # PRD §6.2 모델 버전 관리 규칙과 동일: {날짜}_{데이터셋버전}
    return f"{date.today():%Y%m%d}_{dataset_version}"


@contextmanager
def start_run(dataset_version: str, notes: str = ""):
    mlflow.set_tracking_uri(os.getenv("MLFLOW_TRACKING_URI", DEFAULT_TRACKING_DIR.as_uri()))
    mlflow.set_experiment(EXPERIMENT_NAME)
    with mlflow.start_run(run_name=_run_name(dataset_version)) as run:
        mlflow.set_tag("dataset_version", dataset_version)
        if notes:
            mlflow.set_tag("notes", notes)
        yield run


def log_epoch_metrics(epoch: int, mAP: float, loss: float, **extra) -> None:
    mlflow.log_metric("mAP", mAP, step=epoch)
    mlflow.log_metric("loss", loss, step=epoch)
    for key, value in extra.items():
        mlflow.log_metric(key, value, step=epoch)


def log_hyperparams(**params) -> None:
    mlflow.log_params(params)


def log_artifact(path: str) -> None:
    """모델 체크포인트·config 등 파일을 실험에 첨부 (예: log_artifact("best.pt"))"""
    mlflow.log_artifact(path)
