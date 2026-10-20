package com.hify.app.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hify.app.dto.DemoItemCreateReq;
import com.hify.app.dto.DemoItemResp;
import com.hify.app.dto.DemoItemUpdateReq;
import com.hify.app.entity.DemoItem;
import com.hify.app.mapper.DemoItemMapper;
import com.hify.app.service.DemoItemService;

import com.hify.common.dto.PageResult;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonErrorCode;
import com.hify.common.exception.ErrorCode;
import com.hify.common.util.PageHelper;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * CRUD 流程演示 Service 实现。
 * 业务规则：名称在未删除记录中唯一；不存在按 NOT_FOUND 处理。
 */
@Service
public class DemoItemServiceImpl extends ServiceImpl<DemoItemMapper, DemoItem> implements DemoItemService {

    @Override
    public PageResult<DemoItemResp> list(Integer page, Integer pageSize, String name) {
        LambdaQueryWrapper<DemoItem> wrapper = new LambdaQueryWrapper<DemoItem>()
                .like(StringUtils.hasText(name), DemoItem::getName, name)
                .orderByDesc(DemoItem::getId);
        return PageHelper.toPageResult(
                page(PageHelper.toPage(page, pageSize), wrapper).convert(DemoItemResp::of));
    }

    @Override
    public DemoItemResp getById(Long id) {
        DemoItem item = super.getById(id);
        if (item == null) {
            throw notFound(id);
        }
        return DemoItemResp.of(item);
    }

    @Override
    public Long create(DemoItemCreateReq req) {
        checkNameDuplicate(req.getName(), null);

        DemoItem item = new DemoItem();
        item.setName(req.getName());
        item.setStatus(req.getStatus());
        save(item);   // createdAt/updatedAt 由 AutoFillMetaObjectHandler 填充
        return item.getId();
    }

    @Override
    public void update(Long id, DemoItemUpdateReq req) {
        DemoItem exist = super.getById(id);
        if (exist == null) {
            throw notFound(id);
        }
        checkNameDuplicate(req.getName(), id);

        DemoItem item = new DemoItem();
        item.setId(id);
        item.setName(req.getName());
        item.setStatus(req.getStatus());
        updateById(item);   // updatedAt 自动填充
    }

    @Override
    public void delete(Long id) {
        if (super.getById(id) == null) {
            throw notFound(id);
        }
        removeById(id);   // 逻辑删除：UPDATE demo_item SET deleted = 1
    }

    /** 名称唯一性：排除自身后查未删除记录中是否已存在同名 */
    private void checkNameDuplicate(String name, Long excludeId) {
        Long count = lambdaQuery()
                .eq(DemoItem::getName, name)
                .ne(excludeId != null, DemoItem::getId, excludeId)
                .count();
        if (count != null && count > 0) {
            throw new BizException(CommonErrorCode.PARAM_ERROR, "名称已存在：" + name);
        }
    }

    private BizException notFound(Long id) {
        return new BizException(CommonErrorCode.NOT_FOUND, "DemoItem 不存在，id=" + id);
    }
}
