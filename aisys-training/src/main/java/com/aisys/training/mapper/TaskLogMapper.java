package com.aisys.training.mapper;

import com.aisys.training.entity.TaskLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TaskLogMapper {

    /** 批量插入日志行。 */
    int batchInsert(@Param("items") List<TaskLog> items);

    long countByTaskId(@Param("taskId") Long taskId);

    List<TaskLog> pageByTaskId(@Param("taskId") Long taskId,
                               @Param("offset") int offset,
                               @Param("size") int size);
}
