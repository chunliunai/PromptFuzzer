package com.promptfuzzer.dto;

import lombok.Data;

import java.util.List;

@Data
public class PageResponse<T> {

    private long total;
    private int page;
    private int size;
    private List<T> items;

    public static <T> PageResponse<T> of(long total, int page, int size, List<T> items) {
        PageResponse<T> r = new PageResponse<>();
        r.setTotal(total);
        r.setPage(page);
        r.setSize(size);
        r.setItems(items);
        return r;
    }
}
