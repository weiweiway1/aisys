package com.aisys.training.mapper;

import com.aisys.training.entity.Checkpoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CheckpointMapper {

    int insert(Checkpoint checkpoint);

    /** 清除某任务下所有 active 标记。 */
    int clearActiveByTaskId(@Param("taskId") Long taskId);

    /** 将指定 checkpoint 设为 active。 */
    int setActive(@Param("id") Long id);

    Checkpoint selectById(@Param("id") Long id);

    List<Checkpoint> listByTaskId(@Param("taskId") Long taskId);

    Checkpoint selectActiveByTaskId(@Param("taskId") Long taskId);
}
