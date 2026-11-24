package com.notes.notes.forms.storage;

import java.util.ArrayList;
import java.util.List;

/**
 * query()/count() input: filters are AND-ed together. {@code ownerId} implements
 * OWN-scope enforcement (PermissionGuard already decided the caller may only see
 * their own rows; the store does the actual filtering at the SQL level so it
 * scales instead of filtering in Java after fetching everything).
 */
public class QuerySpec {
    private final List<FilterCondition> filters = new ArrayList<>();
    private Long ownerId;
    private String sortFieldKey;
    private boolean sortDescending = true;
    private int page = 0;
    private int size = 20;

    public QuerySpec addFilter(FilterCondition condition) {
        filters.add(condition);
        return this;
    }

    public QuerySpec ownerId(Long ownerId) {
        this.ownerId = ownerId;
        return this;
    }

    public QuerySpec sortBy(String fieldKey, boolean descending) {
        this.sortFieldKey = fieldKey;
        this.sortDescending = descending;
        return this;
    }

    public QuerySpec page(int page, int size) {
        this.page = page;
        this.size = size;
        return this;
    }

    public List<FilterCondition> getFilters() {
        return filters;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getSortFieldKey() {
        return sortFieldKey;
    }

    public boolean isSortDescending() {
        return sortDescending;
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public int getOffset() {
        return page * size;
    }
}
