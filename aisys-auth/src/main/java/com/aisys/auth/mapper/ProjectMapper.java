package com.aisys.auth.mapper;

import com.aisys.auth.entity.Project;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ProjectMapper {

    Project selectById(@Param("id") Long id);

    long count(@Param("keyword") String keyword);

    List<Project> page(@Param("keyword") String keyword, @Param("offset") int offset, @Param("size") int size);

    int insert(Project project);
}
