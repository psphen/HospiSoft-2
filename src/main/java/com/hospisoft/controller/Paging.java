package com.hospisoft.controller;

import com.hospisoft.bridge.BadRequestException;
import com.hospisoft.bridge.Payload;
import java.util.Map;

final class Paging {

    static final int DEFAULT_PAGE_SIZE = 10;
    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_PAGE = 1_000_000;

    private Paging() {
    }

    static int page(Map<String, Object> body) {
        int page = Payload.optInt(body, "page", 1);
        if (page < 1 || page > MAX_PAGE) {
            throw new BadRequestException("page must be between 1 and " + MAX_PAGE);
        }
        return page;
    }

    static int pageSize(Map<String, Object> body) {
        int size = Payload.optInt(body, "pageSize", DEFAULT_PAGE_SIZE);
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
        return size;
    }
}
