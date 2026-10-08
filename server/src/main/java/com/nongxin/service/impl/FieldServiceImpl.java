package com.nongxin.service.impl;

import com.nongxin.domain.field.FieldProfile;
import com.nongxin.repository.FieldRepository;
import com.nongxin.security.CurrentUser;
import com.nongxin.service.FieldService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class FieldServiceImpl implements FieldService {
    private final FieldRepository repository;
    private final CurrentUser currentUser;

    public FieldServiceImpl(FieldRepository repository, CurrentUser currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    @Override
    public List<FieldProfile> list() {
        return repository.list(currentUser.id());
    }

    @Override
    public FieldProfile get(String id) {
        return repository.find(currentUser.id(), id);
    }

    @Override
    public FieldProfile create(FieldProfile field) {
        String id =
                field.id() == null || field.id().isBlank() ? "f-" + UUID.randomUUID() : field.id();
        repository.create(currentUser.id(), id, field);
        return get(id);
    }

    @Override
    public FieldProfile update(String id, FieldProfile field) {
        return repository.update(currentUser.id(), id, field) == 0 ? null : get(id);
    }

    @Override
    public boolean delete(String id) {
        if (get(id) == null) return false;
        return repository.delete(currentUser.id(), id);
    }

    @Override
    public void addRecord(String fieldId, String date, String note) {
        if (get(fieldId) == null) throw new java.util.NoSuchElementException("田块不存在或不属于当前用户");
        repository.addRecord(currentUser.id(), fieldId, date, note);
    }
}
