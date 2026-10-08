package com.nongxin.service;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Attachment lifecycle; provider payloads and saved conversations never contain persisted base64.
 */
public interface UploadService {
    record Stored(
            String id,
            String mime,
            String ext,
            long bytes,
            int width,
            int height,
            String sha256,
            String createdAt,
            String referencedAt,
            String fieldId,
            String observedAt,
            String note,
            String taskId) {}

    final class SaveUnavailable extends RuntimeException {
        public SaveUnavailable() {
            super("图片保存暂时无法确认，请刷新影像列表核对后再重试");
        }
    }

    final class DeleteUnavailable extends RuntimeException {
        public DeleteUnavailable() {
            super("图片删除暂时无法确认，请刷新影像列表核对后再重试");
        }
    }

    final class ArchiveChanged extends RuntimeException {
        public ArchiveChanged() {
            super("图片归档状态已发生变化，可能已被删除或清理，请刷新影像列表后重试");
        }
    }

    final class ArchiveUnavailable extends RuntimeException {
        public ArchiveUnavailable() {
            super("图片归档保存暂时无法确认，请保留修改内容，刷新影像列表核对后再重试");
        }
    }

    Stored store(
            byte[] raw,
            String declaredContentType,
            String fieldId,
            String observedAt,
            String note,
            String taskId);

    List<Stored> byField(String fieldId, int limit);

    Map<String, Object> usage(String fieldId);

    List<Stored> latestForField(String fieldId, int limit);

    Stored get(String id);

    List<Stored> find(Collection<String> ids);

    byte[] read(String id);

    Path fileFor(String id, String ext);

    int markReferenced(Collection<String> ids);

    boolean confirmConversationReferences(Collection<String> ids);

    boolean delete(String id);

    Stored updateArchive(String id, String note, String observedAt, String fieldId);

    int cleanupUnreferenced();

    List<Stored> list();
}
