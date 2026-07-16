package com.aisys.training.mapper;

import com.aisys.training.entity.TrainingTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TrainingTemplateMapper {

    int insert(TrainingTemplate template);

    int update(TrainingTemplate template);

    TrainingTemplate selectById(@Param("id") Long id);

    long count(@Param("tenantId") Long tenantId, @Param("keyword") String keyword);

    List<TrainingTemplate> page(@Param("tenantId") Long tenantId,
                                @Param("keyword") String keyword,
                                @Param("offset") int offset,
                                @Param("size") int size);
}
