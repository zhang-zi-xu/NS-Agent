package com.nongxin.service.knowledge;

import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.KnowledgeLibrary.SourcedHit;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explainable metadata reranking, not a learned reranker or diagnostic confidence score. */
public final class AgriculturalEvidenceRanker {
    private AgriculturalEvidenceRanker() {}

    public static List<SourcedHit> rank(
            List<SourcedHit> candidates,
            List<SourcedHit> lexical,
            String query,
            String region,
            int limit) {
        Map<String, Integer> lexicalScores = new LinkedHashMap<>();
        for (SourcedHit hit : lexical) lexicalScores.put(hit.chunk().id(), hit.score());
        int maxLexical = lexical.stream().mapToInt(SourcedHit::score).max().orElse(1);
        Set<String> terms = AgriculturalQuery.terms(query);
        String normalized = AgriculturalQuery.normalize(query);
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            SourcedHit hit = candidates.get(i);
            double score =
                    2.0 / (i + 1)
                            + 5.0
                                    * lexicalScores.getOrDefault(hit.chunk().id(), 0)
                                    / Math.max(maxLexical, 1);
            if (hit.document() != null && hit.document().verified()) score += 3;
            String heading = AgriculturalQuery.normalize(hit.chunk().heading());
            score += terms.stream().filter(t -> t.length() >= 3 && heading.contains(t)).count() * 2;
            if (region != null && KnowledgeLibrary.regionUsable(region, hit.chunk().region()))
                score += 1;
            String stage = hit.chunk().growthStage();
            if (stage != null
                    && AgriculturalQuery.STAGES.stream()
                            .anyMatch(s -> normalized.contains(s) && stage.contains(s)))
                score += 0.5;
            scores.put(hit.chunk().id(), score);
        }
        return candidates.stream()
                .sorted(
                        Comparator.<SourcedHit>comparingInt(
                                        h ->
                                                region == null
                                                                || KnowledgeLibrary.regionUsable(
                                                                        region, h.chunk().region())
                                                        ? 1
                                                        : 0)
                                .reversed()
                                .thenComparing(
                                        Comparator.<SourcedHit>comparingDouble(
                                                        h -> scores.get(h.chunk().id()))
                                                .reversed())
                                .thenComparing(h -> h.chunk().id()))
                .limit(limit)
                .toList();
    }
}
