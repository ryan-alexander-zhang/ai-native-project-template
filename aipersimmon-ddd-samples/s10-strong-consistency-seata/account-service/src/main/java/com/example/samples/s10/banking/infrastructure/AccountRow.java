package com.example.samples.s10.banking.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/** The account row. */
@TableName("s10_account")
class AccountRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String id;

  private Long balanceMinor;
  private String lastNote;

  @Version private Long version;

  String getId() {
    return id;
  }

  void setId(String id) {
    this.id = id;
  }

  Long getBalanceMinor() {
    return balanceMinor;
  }

  void setBalanceMinor(Long balanceMinor) {
    this.balanceMinor = balanceMinor;
  }

  String getLastNote() {
    return lastNote;
  }

  void setLastNote(String lastNote) {
    this.lastNote = lastNote;
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
