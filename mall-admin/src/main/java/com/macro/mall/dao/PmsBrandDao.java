package com.macro.mall.dao;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 品牌管理自定义Dao
 */
public interface PmsBrandDao {
    /**
     * 查询指定品牌中仍有未删除商品的品牌名称
     */
    List<String> selectBrandNamesWithValidProduct(@Param("ids") List<Long> ids);
}
