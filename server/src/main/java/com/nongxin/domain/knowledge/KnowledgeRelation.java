package com.nongxin.domain.knowledge;

/** A source-backed agricultural relation, never a diagnosis of the user's field. */
public record KnowledgeRelation(
        String id,
        String crop,
        String subject,
        String predicate,
        String object,
        String sourceId,
        String evidenceQuote) {}
