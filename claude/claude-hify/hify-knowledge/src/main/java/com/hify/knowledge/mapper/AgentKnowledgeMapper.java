package com.hify.knowledge.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.knowledge.entity.AgentKnowledge;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Agent-知识库绑定 Mapper。绑定是纯关系数据，解绑走物理删除
 * （BaseMapper 的 deleteById 会被 @TableLogic 转成逻辑删除，占住唯一键导致重复绑定冲突）。
 */
public interface AgentKnowledgeMapper extends BaseMapper<AgentKnowledge> {

    @Delete("DELETE FROM agent_knowledge WHERE agent_id = #{agentId}")
    int physicalDeleteByAgent(@Param("agentId") Long agentId);

    @Delete("DELETE FROM agent_knowledge WHERE knowledge_base_id = #{knowledgeBaseId}")
    int physicalDeleteByKnowledgeBase(@Param("knowledgeBaseId") Long knowledgeBaseId);

    /** 按知识库反查绑定（知识库删除前清理用） */
    @Select("SELECT agent_id FROM agent_knowledge WHERE knowledge_base_id = #{knowledgeBaseId} AND deleted = 0")
    List<Long> selectAgentIdsByKnowledgeBase(@Param("knowledgeBaseId") Long knowledgeBaseId);
}
