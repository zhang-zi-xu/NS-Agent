package com.nongxin.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.domain.knowledge.KnowledgeRelation;
import com.nongxin.service.KnowledgeLibrary;

import org.springframework.core.io.Resource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fail closed on stale, invented or unverified graph evidence. Does not extract facts with an LLM.
 */
public final class KnowledgeRelationLoader {
    private static final Set<String> PREDICATES =
            Set.of("防治时机", "监测指标", "管理措施", "作业条件", "注意事项", "适用阶段");

    private KnowledgeRelationLoader() {}

    public record Bundle(int version, List<KnowledgeRelation> relations) {}

    public static List<KnowledgeRelation> load(
            Resource resource, ObjectMapper json, KnowledgeLibrary library) {
        try (var input = resource.getInputStream()) {
            Bundle bundle = json.readValue(input, Bundle.class);
            if (bundle.version() != 1 || bundle.relations() == null)
                throw new IllegalArgumentException();
            Set<String> ids = new HashSet<>();
            for (KnowledgeRelation relation : bundle.relations()) {
                if (blank(relation.id())
                        || !ids.add(relation.id())
                        || !("水稻".equals(relation.crop()) || "小麦".equals(relation.crop()))
                        || blank(relation.subject())
                        || !PREDICATES.contains(relation.predicate())
                        || blank(relation.object())
                        || blank(relation.sourceId())
                        || blank(relation.evidenceQuote())) throw new IllegalArgumentException();
                var hits = library.resolve(List.of(relation.sourceId()));
                if (hits.size() != 1) throw new IllegalArgumentException();
                var hit = hits.getFirst();
                if (hit.document() == null
                        || !hit.document().verified()
                        || blank(hit.document().url())
                        || !KnowledgeApplicability.supports(
                                hit.chunk(), hit.document(), relation.crop())
                        || !compact(hit.chunk().text()).contains(compact(relation.evidenceQuote()))
                        || !compact(relation.evidenceQuote()).contains(compact(relation.object()))
                        || !compact(hit.chunk().heading() + hit.chunk().text())
                                .contains(compact(relation.subject())))
                    throw new IllegalArgumentException();
            }
            return List.copyOf(bundle.relations());
        } catch (Exception e) {
            // No question, source content or secret-bearing exception is logged.
            throw new IllegalStateException("农业知识关系校验失败，请核对关系与已核验原文", e);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String compact(String value) {
        return value.replaceAll("\\s+", "");
    }
}
