package com.macro.mall.service.impl;

import cn.hutool.core.util.StrUtil;
import com.github.pagehelper.PageHelper;
import com.macro.mall.common.api.BusinessErrorCode;
import com.macro.mall.common.exception.Asserts;
import com.macro.mall.dao.PmsBrandDao;
import com.macro.mall.dto.PmsBrandParam;
import com.macro.mall.mapper.PmsBrandMapper;
import com.macro.mall.mapper.PmsProductMapper;
import com.macro.mall.model.PmsBrand;
import com.macro.mall.model.PmsBrandExample;
import com.macro.mall.model.PmsProduct;
import com.macro.mall.model.PmsProductExample;
import com.macro.mall.service.PmsBrandService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 商品品牌管理Service实现类
 * Created by macro on 2018/4/26.
 */
@Service
public class PmsBrandServiceImpl implements PmsBrandService {
    @Autowired
    private PmsBrandMapper brandMapper;
    @Autowired
    private PmsProductMapper productMapper;
    @Autowired
    private PmsBrandDao brandDao;

    @Override
    public List<PmsBrand> listAllBrand() {
        return brandMapper.selectByExample(new PmsBrandExample());
    }

    @Override
    public int createBrand(PmsBrandParam pmsBrandParam) {
        PmsBrand pmsBrand = new PmsBrand();
        BeanUtils.copyProperties(pmsBrandParam, pmsBrand);
        //如果创建时首字母为空，取名称的第一个为首字母
        if (StrUtil.isEmpty(pmsBrand.getFirstLetter())) {
            pmsBrand.setFirstLetter(pmsBrand.getName().substring(0, 1));
        }
        return brandMapper.insertSelective(pmsBrand);
    }

//    @Override
//    public int updateBrand(Long id, PmsBrandParam pmsBrandParam) {
//        /***
//         * 有很多问题：
//         * 第一个productMapper.updateByExampleSelective(product,example)，失败了，任然会更新品牌名，这是严重的错误
//         * 第二个数据库设计的问题，由于pms_product只有一个主键，brand_id上没有索引，所以全局扫描的时候会出现慢的问题，建议这里要加上索引
//         *
//         */
//        PmsBrand pmsBrand = new PmsBrand();
//        BeanUtils.copyProperties(pmsBrandParam, pmsBrand);
//        pmsBrand.setId(id);
//        //如果创建时首字母为空，取名称的第一个为首字母
//        if (StrUtil.isEmpty(pmsBrand.getFirstLetter())) {
//            pmsBrand.setFirstLetter(pmsBrand.getName().substring(0, 1));
//        }
//        //更新品牌时要更新商品中的品牌名称
//        PmsProduct product = new PmsProduct();
//        product.setBrandName(pmsBrand.getName());
//        PmsProductExample example = new PmsProductExample();
//        example.createCriteria().andBrandIdEqualTo(id);
//        productMapper.updateByExampleSelective(product,example);
//        return brandMapper.updateByPrimaryKeySelective(pmsBrand);
//    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateBrand(Long id, PmsBrandParam pmsBrandParam) {
        PmsBrand oldBrand = brandMapper.selectByPrimaryKey(id);
        if (oldBrand == null) {
            return 0;
        }
        PmsBrand pmsBrand = new PmsBrand();
        BeanUtils.copyProperties(pmsBrandParam, pmsBrand);
        pmsBrand.setId(id);
        if (StrUtil.isEmpty(pmsBrand.getFirstLetter())) {
            pmsBrand.setFirstLetter(pmsBrand.getName().substring(0, 1));
        }
        // 只有名称真正变化时才同步商品冗余字段
        if (!StrUtil.equals(oldBrand.getName(), pmsBrand.getName())) {
            PmsProduct product = new PmsProduct();
            product.setBrandName(pmsBrand.getName());
            PmsProductExample example = new PmsProductExample();
            example.createCriteria().andBrandIdEqualTo(id);
            productMapper.updateByExampleSelective(product, example);
        }
        return brandMapper.updateByPrimaryKeySelective(pmsBrand);
    }

//    @Override
//    public int deleteBrand(Long id) {
//        /**
//         * 物理删除，且对应的旗下的product没有进行检验，这里我们改成了如果brand下面还有products，就禁止删除
//         */
//        return brandMapper.deleteByPrimaryKey(id);
//    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteBrand(Long id) {
        return deleteBrand(Collections.singletonList(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteBrand(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        //先查出这批品牌中仍有未删除商品的，有则整体拒绝，避免产生悬空的 brand_id
        List<String> blockedBrandNames = brandDao.selectBrandNamesWithValidProduct(ids);
        if (blockedBrandNames != null && !blockedBrandNames.isEmpty()) {
            Asserts.fail("以下品牌下仍有商品，无法删除：" + String.join("、", blockedBrandNames));
        }
        PmsBrandExample pmsBrandExample = new PmsBrandExample();
        pmsBrandExample.createCriteria().andIdIn(ids);
        return brandMapper.deleteByExample(pmsBrandExample);
    }



    @Override
    public List<PmsBrand> listBrand(String keyword, Integer showStatus, int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        PmsBrandExample pmsBrandExample = new PmsBrandExample();
        pmsBrandExample.setOrderByClause("sort desc");
        PmsBrandExample.Criteria criteria = pmsBrandExample.createCriteria();
        if (!StrUtil.isEmpty(keyword)) {
            criteria.andNameLike("%" + keyword + "%");
        }
        if(showStatus!=null){
            criteria.andShowStatusEqualTo(showStatus);
        }
        return brandMapper.selectByExample(pmsBrandExample);
    }

    @Override
    public PmsBrand getBrand(Long id) {
        PmsBrand brand = brandMapper.selectByPrimaryKey(id);
        if (brand == null) {
            Asserts.fail(BusinessErrorCode.NOT_FOUND, Map.of("field", "id", "value", id));
        }
        return brand;
    }

    @Override
    public int updateShowStatus(List<Long> ids, Integer showStatus) {
        if (ids == null || ids.isEmpty()) {
            Asserts.fail("请至少选择一个品牌");
        }
        if (showStatus == null) {
            Asserts.fail("显示状态不能为空");
        }
        PmsBrand pmsBrand = new PmsBrand();
        pmsBrand.setShowStatus(showStatus);
        PmsBrandExample pmsBrandExample = new PmsBrandExample();
        pmsBrandExample.createCriteria().andIdIn(ids);
        return brandMapper.updateByExampleSelective(pmsBrand, pmsBrandExample);
    }

    @Override
    public int updateFactoryStatus(List<Long> ids, Integer factoryStatus) {
        if (ids == null || ids.isEmpty()) {
            Asserts.fail("请至少选择一个品牌");
        }
        if (factoryStatus == null) {
            Asserts.fail("厂家状态不能为空");
        }
        PmsBrand pmsBrand = new PmsBrand();
        pmsBrand.setFactoryStatus(factoryStatus);
        PmsBrandExample pmsBrandExample = new PmsBrandExample();
        pmsBrandExample.createCriteria().andIdIn(ids);
        return brandMapper.updateByExampleSelective(pmsBrand, pmsBrandExample);
    }
}
