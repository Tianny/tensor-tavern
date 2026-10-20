package com.hify.app;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hify.common.entity.BaseEntity;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 BaseEntity 的公共字段注解被 MyBatis-Plus 正确解析：
 * 主键自增、驼峰转下划线、时间字段自动填充、逻辑删除。
 * 纯元数据测试，不依赖数据库和 Spring 上下文。
 */
class BaseEntityMappingTest {

    /** 模拟业务实体：只声明业务字段，公共字段全部来自 BaseEntity */
    static class TestEntity extends BaseEntity {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    private TableInfo initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        return TableInfoHelper.initTableInfo(assistant, TestEntity.class);
    }

    private TableFieldInfo fieldInfo(TableInfo info, String property) {
        return info.getFieldList().stream()
                .filter(f -> f.getProperty().equals(property))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void 主键自增且驼峰转下划线() {
        TableInfo info = initTableInfo();
        assertThat(info.getIdType()).isEqualTo(IdType.AUTO);
        assertThat(info.getKeyProperty()).isEqualTo("id");
        assertThat(info.getKeyColumn()).isEqualTo("id");
        assertThat(fieldInfo(info, "createdAt").getColumn()).isEqualTo("created_at");
        assertThat(fieldInfo(info, "updatedAt").getColumn()).isEqualTo("updated_at");
    }

    @Test
    void 时间字段自动填充() {
        TableInfo info = initTableInfo();
        assertThat(fieldInfo(info, "createdAt").getFieldFill()).isEqualTo(FieldFill.INSERT);
        assertThat(fieldInfo(info, "createdAt").isWithInsertFill()).isTrue();
        assertThat(fieldInfo(info, "createdAt").isWithUpdateFill()).isFalse();
        assertThat(fieldInfo(info, "updatedAt").getFieldFill()).isEqualTo(FieldFill.INSERT_UPDATE);
        assertThat(fieldInfo(info, "updatedAt").isWithInsertFill()).isTrue();
        assertThat(fieldInfo(info, "updatedAt").isWithUpdateFill()).isTrue();
    }

    @Test
    void deleted识别为逻辑删除字段() {
        TableInfo info = initTableInfo();
        assertThat(fieldInfo(info, "deleted").isLogicDelete()).isTrue();
        assertThat(info.isWithLogicDelete()).isTrue();
        assertThat(info.getLogicDeleteFieldInfo().getColumn()).isEqualTo("deleted");
    }
}
