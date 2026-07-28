"""테스트 전역 설정.

main.py의 load_dotenv()가 실제 .env(LangSmith 키 포함)를 그대로 읽어들이는데, pytest가
report_chain/rag_chat_chain의 컴파일된 그래프를 실제로 invoke()하는 테스트가 많아 LLM 호출을
mock해도 그래프 실행 자체는 트레이싱된다 — 테스트 노이즈가 실제 운영 트레이스와 섞이는 것을
막기 위해 테스트 프로세스에서는 LangSmith 트레이싱을 강제로 끈다.
"""
import os

os.environ["LANGCHAIN_TRACING_V2"] = "false"
