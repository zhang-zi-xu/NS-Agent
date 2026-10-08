package com.nongxin.service.knowledge;

import com.nongxin.domain.knowledge.KnowledgeChunk;
import com.nongxin.domain.knowledge.KnowledgeDocument;

/** Shared crop boundary for lexical, graph and vector candidates. */
public final class KnowledgeApplicability {
    private KnowledgeApplicability() {}

    public static boolean supports(KnowledgeChunk chunk, KnowledgeDocument document, String crop) {
        if (crop == null || crop.isBlank()) return true;
        if (chunk.crop() != null && !chunk.crop().isBlank() && !"通用".equals(chunk.crop())) {
            return crop.equals(chunk.crop());
        }
        // Missing slice-level crop metadata in a mixed-crop guide is not universal advice.
        // Keep single-crop and genuinely general documents; ambiguous composite slices must be
        // reviewed/split instead of borrowing the whole document's crop list.
        return document == null
                || document.crops() == null
                || document.crops().isEmpty()
                || document.crops().size() == 1
                        && (document.crops().contains("通用") || document.crops().contains(crop));
    }
}
