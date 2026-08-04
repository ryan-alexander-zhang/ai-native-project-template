package com.example.samples.s24.coupons.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;

/** The coupon row, in this context's own table. */
@TableName("s24_coupons_coupon")
class CouponRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String code;

  private String kind;
  private Long valueMinor;
  private Integer percentOff;
  private String currency;
  private Instant validFrom;
  private Instant validUntil;
  private Integer maxRedemptions;
  private Integer redemptions;

  @Version private Long version;

  String getCode() {
    return code;
  }

  void setCode(String code) {
    this.code = code;
  }

  String getKind() {
    return kind;
  }

  void setKind(String kind) {
    this.kind = kind;
  }

  Long getValueMinor() {
    return valueMinor;
  }

  void setValueMinor(Long valueMinor) {
    this.valueMinor = valueMinor;
  }

  Integer getPercentOff() {
    return percentOff;
  }

  void setPercentOff(Integer percentOff) {
    this.percentOff = percentOff;
  }

  String getCurrency() {
    return currency;
  }

  void setCurrency(String currency) {
    this.currency = currency;
  }

  Instant getValidFrom() {
    return validFrom;
  }

  void setValidFrom(Instant validFrom) {
    this.validFrom = validFrom;
  }

  Instant getValidUntil() {
    return validUntil;
  }

  void setValidUntil(Instant validUntil) {
    this.validUntil = validUntil;
  }

  Integer getMaxRedemptions() {
    return maxRedemptions;
  }

  void setMaxRedemptions(Integer maxRedemptions) {
    this.maxRedemptions = maxRedemptions;
  }

  Integer getRedemptions() {
    return redemptions;
  }

  void setRedemptions(Integer redemptions) {
    this.redemptions = redemptions;
  }

  @Override
  public Long getVersion() {
    return version;
  }

  @Override
  public void setVersion(Long version) {
    this.version = version;
  }
}
