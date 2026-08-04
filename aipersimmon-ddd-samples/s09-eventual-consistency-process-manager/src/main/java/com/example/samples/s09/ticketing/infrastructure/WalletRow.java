package com.example.samples.s09.ticketing.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/** The wallet root row: just the balance. The movements are the child rows. */
@TableName("s09_wallet")
class WalletRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String customerId;

  private Long balanceMinor;

  @Version private Long version;

  String getCustomerId() {
    return customerId;
  }

  void setCustomerId(String customerId) {
    this.customerId = customerId;
  }

  Long getBalanceMinor() {
    return balanceMinor;
  }

  void setBalanceMinor(Long balanceMinor) {
    this.balanceMinor = balanceMinor;
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
