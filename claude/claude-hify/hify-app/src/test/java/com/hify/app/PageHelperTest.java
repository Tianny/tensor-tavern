package com.hify.app;

import java.util.List;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hify.common.dto.PageResult;
import com.hify.common.util.PageHelper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** PageHelper 契约测试：page 从 1 开始，pageSize 默认 20 上限 100 */
class PageHelperTest {

    @Test
    void 正常参数原样透传() {
        Page<String> page = PageHelper.toPage(3, 50);
        assertThat(page.getCurrent()).isEqualTo(3);
        assertThat(page.getSize()).isEqualTo(50);
    }

    @Test
    void null参数走默认值() {
        Page<String> page = PageHelper.toPage(null, null);
        assertThat(page.getCurrent()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(20);
    }

    @Test
    void 非法参数归位() {
        Page<String> page = PageHelper.toPage(0, 200);
        assertThat(page.getCurrent()).isEqualTo(1);      // page < 1 归到 1
        assertThat(page.getSize()).isEqualTo(100);       // pageSize 超上限截到 100

        Page<String> page2 = PageHelper.toPage(-5, 0);
        assertThat(page2.getCurrent()).isEqualTo(1);
        assertThat(page2.getSize()).isEqualTo(20);       // pageSize < 1 走默认值
    }

    @Test
    void 查询结果转分页数据体() {
        Page<String> page = PageHelper.toPage(2, 20);
        page.setTotal(45);
        page.setRecords(List.of("a", "b"));

        PageResult<String> result = PageHelper.toPageResult(page);

        assertThat(result.getList()).containsExactly("a", "b");
        assertThat(result.getTotal()).isEqualTo(45);
        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(20);
    }

    @Test
    void 空结果返回空列表不返回null() {
        Page<String> page = PageHelper.toPage(1, 20);
        page.setTotal(0);

        PageResult<String> result = PageHelper.toPageResult(page);

        assertThat(result.getList()).isNotNull().isEmpty();
        assertThat(result.getTotal()).isZero();
    }
}
