package com.ppms.common.dto;

import org.springframework.data.domain.PageRequest;

public final class PageRequests {

    public static final int MAX_PAGE_SIZE = 200;

    private PageRequests() {
    }

    public static PageRequest of(int page, int size) {
        return PageRequest.of(page, cappedSize(size));
    }

    public static int cappedSize(int size) {
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
