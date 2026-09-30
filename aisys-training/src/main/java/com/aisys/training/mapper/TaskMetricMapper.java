package com.aisys.training.mapper;

import com.aisys.training.entity.TaskMetric;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface TaskMetricMapper {

    int insert(TaskMetric metric);

    long countByTaskIdAndRange(@Param("taskId") Long taskId,
                               @Param("from") Instant from,
                               @Param("to") Instant to);

    List<TaskMetric> pageByTaskIdAndRange(@Param("taskId") Long taskId,
                                          @Param("from") Instant from,
                                          @Param("to") Instant to,
                                          @Param("offset") int offset,
                                          @Param("size") int size);
}
