package com.swingfrog.summer.db.repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public interface Repository<T, K> {

    boolean add(T obj);
    boolean remove(T obj);
    boolean removeByPrimaryKey(K primaryKey);
    boolean save(T obj);
    void save(Collection<T> objs);
    T get(K primaryKey);
    T getOrCreate(K primaryKey, Supplier<T> supplier);
    List<T> list(String field, Object value);
    List<T> list(Map<String, Object> optional);
    List<T> list();

}
