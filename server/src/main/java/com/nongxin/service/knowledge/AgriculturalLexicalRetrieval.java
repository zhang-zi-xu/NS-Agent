package com.nongxin.service.knowledge;

import com.nongxin.domain.knowledge.KnowledgeChunk;
import com.nongxin.domain.knowledge.KnowledgeDocument;
import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.KnowledgeLibrary.SourcedHit;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Field-weighted BM25-style lexical recall; no paid API or claimed semantic model. */
public final class AgriculturalLexicalRetrieval {
    private final Map<String, KnowledgeDocument> documents;

    public AgriculturalLexicalRetrieval(Map<String, KnowledgeDocument> documents) {
        this.documents = documents;
    }

    public List<SourcedHit> rank(List<KnowledgeChunk> pool, String query, String region) {
        Set<String> terms = AgriculturalQuery.terms(query);
        if (terms.isEmpty() || pool.isEmpty()) return List.of();
        double averageLength = pool.stream().mapToInt(c -> c.text().length()).average().orElse(1);
        Map<String, Long> frequency = new java.util.LinkedHashMap<>();
        for (String term : terms)
            frequency.put(term, pool.stream().filter(c -> content(c).contains(term)).count());
        return pool.stream()
                .map(
                        chunk -> {
                            String heading = AgriculturalQuery.normalize(chunk.heading());
                            String keywords =
                                    AgriculturalQuery.normalize(
                                            String.join(
                                                    " ",
                                                    chunk.keywords() == null
                                                            ? List.of()
                                                            : chunk.keywords()));
                            String body = AgriculturalQuery.normalize(chunk.text());
                            // Broad harvested paragraphs may incidentally name unrelated crops. A
                            // body-only hit
                            // needs a phrase anchor; generic 2-gram overlaps alone are not usable
                            // evidence.
                            boolean anchored =
                                    terms.stream()
                                            .anyMatch(
                                                    t ->
                                                            heading.contains(t)
                                                                    || keywords.contains(t)
                                                                    || (AgriculturalQuery
                                                                                    .bodyAnchor(t)
                                                                            && body.contains(t)));
                            if (!anchored)
                                return new SourcedHit(chunk, documents.get(chunk.documentId()), 0);
                            double score = 0;
                            for (String term : terms) {
                                int tf = occurrences(body, term);
                                double field =
                                        heading.contains(term)
                                                ? 4
                                                : keywords.contains(term) ? 3 : 0;
                                if (tf == 0 && field == 0) continue;
                                double df = frequency.get(term);
                                double idf = Math.log(1 + (pool.size() - df + 0.5) / (df + 0.5));
                                double lengthNorm =
                                        1.2 * (0.25 + 0.75 * body.length() / averageLength);
                                score +=
                                        idf
                                                * ((tf + field) * 2.2 / (tf + field + lengthNorm)
                                                        + field);
                            }
                            if (score <= 0)
                                return new SourcedHit(chunk, documents.get(chunk.documentId()), 0);
                            KnowledgeDocument doc = documents.get(chunk.documentId());
                            if (doc != null && doc.verified()) score += 5;
                            if (region != null
                                    && KnowledgeLibrary.regionUsable(region, chunk.region()))
                                score += 2;
                            return new SourcedHit(
                                    chunk, doc, Math.max(1, (int) Math.round(score * 100)));
                        })
                .filter(hit -> hit.score() > 0)
                .sorted(
                        Comparator.comparingInt(SourcedHit::score)
                                .reversed()
                                .thenComparing(h -> h.chunk().id()))
                .toList();
    }

    private static String content(KnowledgeChunk chunk) {
        return AgriculturalQuery.normalize(
                chunk.heading()
                        + " "
                        + chunk.text()
                        + " "
                        + String.join(
                                " ", chunk.keywords() == null ? List.of() : chunk.keywords()));
    }

    private static int occurrences(String text, String term) {
        int count = 0;
        for (int index = text.indexOf(term);
                index >= 0;
                index = text.indexOf(term, index + term.length())) count++;
        return count;
    }
}
