package com.aisys.resource.mapper;

import com.aisys.resource.entity.NodeGroup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface NodeGroupMapper {

    List<NodeGroup> selectAll();

    NodeGroup selectById(@Param("id") Long id);

    int insert(NodeGroup nodeGroup);

    int update(NodeGroup nodeGroup);

    int deleteById(@Param("id") Long id);

    /** 统计某分组下的计算节点数 */
    int countNodesInGroup(@Param("groupId") Long groupId);
}
