package com.acme.samples.s2.ordering.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("s2_ordering.outbox")
public class OutboxPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String topic;
    private String msgKey;
    private String payload;
    private Boolean sent;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getMsgKey() { return msgKey; }
    public void setMsgKey(String msgKey) { this.msgKey = msgKey; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public Boolean getSent() { return sent; }
    public void setSent(Boolean sent) { this.sent = sent; }
}
