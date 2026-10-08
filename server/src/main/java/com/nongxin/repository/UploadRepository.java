package com.nongxin.repository;

import com.nongxin.service.UploadService.Stored;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owner-scoped operations and separately named maintenance-only operations. */
public interface UploadRepository extends TransactionalRepository {
    Integer countByIdForPublication(String id);

    int insert(String userId, Stored photo);

    List<Stored> byField(String userId, String fieldId, int limit);

    Map<String, Object> usage(String userId, String fieldId);

    Stored find(String userId, String id);

    int markReferenced(String userId, String id, String now);

    int pinReference(String userId, String id, String now);

    int delete(String userId, String id);

    int updateArchive(String userId, String id, String note, String observedAt, String fieldId);

    List<Stored> list(String userId);

    Integer ownedFieldCount(String userId, String fieldId);

    Integer ownedTaskCount(String userId, String taskId);

    List<Stored> staleCandidates(String cutoff);

    List<Stored> findForMaintenance(String id);

    int deleteForMaintenance(String id);

    Set<String> referencedByConversations(Set<String> candidates);
}
