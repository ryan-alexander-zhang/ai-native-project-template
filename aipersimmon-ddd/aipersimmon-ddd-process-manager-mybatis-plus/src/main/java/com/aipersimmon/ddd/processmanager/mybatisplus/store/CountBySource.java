package com.aipersimmon.ddd.processmanager.mybatisplus.store;

/** A {@code (suspension source, count)} pair from the suspended-by-source aggregate. */
public class CountBySource {
  private String src;
  private long cnt;

  public String getSrc() {
    return src;
  }

  public void setSrc(String v) {
    this.src = v;
  }

  public long getCnt() {
    return cnt;
  }

  public void setCnt(long v) {
    this.cnt = v;
  }
}
