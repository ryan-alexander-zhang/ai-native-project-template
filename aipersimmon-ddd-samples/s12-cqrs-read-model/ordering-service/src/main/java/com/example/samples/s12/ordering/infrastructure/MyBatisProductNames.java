package com.example.samples.s12.ordering.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.samples.s12.ordering.application.ProductNames;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** The replica, stored. */
@Repository
class MyBatisProductNames implements ProductNames {

  private final ProductNameMapper mapper;

  MyBatisProductNames(ProductNameMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Map<String, String> namesOf(List<String> skus) {
    if (skus.isEmpty()) {
      return Map.of();
    }
    Map<String, String> byName = new LinkedHashMap<>();
    mapper
        .selectList(new LambdaQueryWrapper<ProductNameRow>().in(ProductNameRow::getSku, skus))
        .forEach(row -> byName.put(row.getSku(), row.getName()));
    return byName;
  }

  @Override
  public Optional<String> nameOf(String sku) {
    ProductNameRow row = mapper.selectById(sku);
    return Optional.ofNullable(row).map(ProductNameRow::getName);
  }

  /**
   * Upsert by hand.
   *
   * <p>Not a blind insert: the catalogue can rename the same product twice, and a redelivered event must not
   * raise a duplicate key. Not a delete-then-insert either — the row is the thing other queries read, and
   * making it briefly absent would make an unrelated projection rebuild show a sku instead of a name.
   */
  @Override
  public void record(String sku, String name, Instant at) {
    ProductNameRow row = new ProductNameRow();
    row.setSku(sku);
    row.setName(name);
    row.setUpdatedAt(at);
    if (mapper.selectById(sku) == null) {
      mapper.insert(row);
    } else {
      mapper.updateById(row);
    }
  }
}
