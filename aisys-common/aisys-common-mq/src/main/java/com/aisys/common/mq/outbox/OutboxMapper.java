package com.aisys.common.mq.outbox;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Outbox 表 Mapper（共享 event_outbox 表）。使用 FOR UPDATE SKIP LOCKED 实现多实例安全轮询。
 */
@Mapper
public interface OutboxMapper {

    void insert(OutboxRecord record);

    List<OutboxRecord> selectPendingForUpdate(@Param("producer") String producer, @Param("limit") int limit);

    void markSent(@Param("id") Long id);

    void markFailed(@Param("id") Long id);
}
