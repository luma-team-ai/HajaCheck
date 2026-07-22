package com.hajacheck.core.rag.repository;

import com.hajacheck.core.rag.entity.RagDocument;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * RAG 문서(rag_documents) 저장소 — 플랫폼 관리자 콘솔(#22/HAJA-35). company 스코핑 없음(법규·지침 문서는
 * 회사 소유 리소스가 아니라 전체 플랫폼이 공유하는 지식베이스 원본이라 FR-8-B 공통 원칙대로 전체 목록을 다룬다).
 */
public interface RagDocumentRepository extends JpaRepository<RagDocument, Long> {

    List<RagDocument> findAllByOrderByCreatedAtDesc();
}
