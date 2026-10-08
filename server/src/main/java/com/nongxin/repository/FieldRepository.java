package com.nongxin.repository;

import com.nongxin.domain.field.FieldProfile;

import java.util.List;

public interface FieldRepository {
    List<FieldProfile> list(String userId);

    FieldProfile find(String userId, String id);

    void create(String userId, String id, FieldProfile field);

    int update(String userId, String id, FieldProfile field);

    boolean delete(String userId, String id);

    void addRecord(String userId, String fieldId, String date, String note);
}
