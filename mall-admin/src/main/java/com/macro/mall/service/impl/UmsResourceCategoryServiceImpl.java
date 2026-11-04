package com.macro.mall.service.impl;

import com.macro.mall.common.api.BusinessErrorCode;
import com.macro.mall.common.exception.Asserts;
import com.macro.mall.mapper.UmsResourceCategoryMapper;
import com.macro.mall.mapper.UmsResourceMapper;
import com.macro.mall.model.UmsResourceCategory;
import com.macro.mall.model.UmsResourceCategoryExample;
import com.macro.mall.model.UmsResourceExample;
import com.macro.mall.service.UmsResourceCategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台资源分类管理Service实现类
 * Created by macro on 2020/2/5.
 */
@Service
public class UmsResourceCategoryServiceImpl implements UmsResourceCategoryService {
    @Autowired
    private UmsResourceCategoryMapper resourceCategoryMapper;
    @Autowired
    private UmsResourceMapper resourceMapper;

    @Override
    public List<UmsResourceCategory> listAll() {
        UmsResourceCategoryExample example = new UmsResourceCategoryExample();
        example.setOrderByClause("sort desc");
        return resourceCategoryMapper.selectByExample(example);
    }

    @Override
    public int create(UmsResourceCategory umsResourceCategory) {
        umsResourceCategory.setCreateTime(new Date());
        return resourceCategoryMapper.insert(umsResourceCategory);
    }

    @Override
    public int update(Long id, UmsResourceCategory umsResourceCategory) {
        umsResourceCategory.setId(id);
        return resourceCategoryMapper.updateByPrimaryKeySelective(umsResourceCategory);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int delete(Long id) {
        if (id == null) {
            Asserts.fail(
                BusinessErrorCode.PARAM_INVALID,
                Map.of("field", id));
        }

        UmsResourceCategory category = resourceCategoryMapper.selectByPrimaryKey(id);

        if (category == null) {
            Asserts.fail(
                BusinessErrorCode.NOT_FOUND,
                Map.of("field", id)
            );
        }

        UmsResourceExample example = new UmsResourceExample();
        example.createCriteria().andCategoryIdEqualTo(id);

        long resourceCount = resourceMapper.countByExample(example);

        if (resourceCount > 0) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("categoryId", id);
            details.put("resourceCount", resourceCount);

            Asserts.fail(
                BusinessErrorCode.DATA_CONFLICT,
                details
            );
        }

        int count = resourceCategoryMapper.deleteByPrimaryKey(id);

        if (count == 0) {
            Asserts.fail(
                BusinessErrorCode.DATA_CONFLICT,
                Map.of("categoryId", id)
            );
        }


        return 0;
    }

//    @Override
//    public int delete(Long id) {
//        return resourceCategoryMapper.deleteByPrimaryKey(id);
//    }
}
