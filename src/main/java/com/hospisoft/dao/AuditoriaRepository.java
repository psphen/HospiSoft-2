package com.hospisoft.dao;

import com.hospisoft.model.Auditoria;
import com.hospisoft.model.Page;

public interface AuditoriaRepository {
    void insert(Auditoria entry);

    Page<Auditoria> findPage(AuditoriaQuery query, int page, int pageSize);
}
