package com.lumen.extension.outbox;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EventOutboxMapper extends BaseMapper<EventOutbox> {
    @Select("""
        SELECT * FROM t_event_outbox
        WHERE status = 'PENDING' AND next_retry_at <= NOW() AND deleted = 0
        ORDER BY id
        LIMIT #{batchSize}
        FOR UPDATE SKIP LOCKED
    """)
    List<EventOutbox> lockPendingBatch(@Param("batchSize") int batchSize);
}