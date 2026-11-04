package com.macro.mall.portal.service.impl;

import com.macro.mall.common.api.BusinessErrorCode;
import com.macro.mall.common.exception.Asserts;
import com.macro.mall.mapper.PmsProductMapper;
import com.macro.mall.mapper.PmsSkuStockMapper;
import com.macro.mall.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 购物车和订单共用的商品可售校验。批量读取，避免逐商品查询。
 */
@Component
public class ProductSaleValidator {
    @Autowired
    private PmsProductMapper productMapper;
    @Autowired
    private PmsSkuStockMapper skuStockMapper;

    public void validate(List<OmsCartItem> cartItems) {
        if (cartItems == null || cartItems.isEmpty()) {
            Asserts.fail(BusinessErrorCode.PARAM_INVALID, Map.of("reason", "请选择至少一件商品"));
        }
        for (OmsCartItem item : cartItems) {
            if (item == null || item.getProductId() == null || item.getProductSkuId() == null) {
                Asserts.fail(BusinessErrorCode.PARAM_INVALID, Map.of("reason", "商品和SKU编号不能为空"));
            }
        }
        PmsProductExample productExample = new PmsProductExample();
        productExample.createCriteria().andIdIn(cartItems.stream()
                .map(OmsCartItem::getProductId).distinct().collect(Collectors.toList()));
        Map<Long, PmsProduct> products = productMapper.selectByExample(productExample).stream()
                .collect(Collectors.toMap(PmsProduct::getId, item -> item));
        PmsSkuStockExample skuExample = new PmsSkuStockExample();
        skuExample.createCriteria().andIdIn(cartItems.stream()
                .map(OmsCartItem::getProductSkuId).distinct().collect(Collectors.toList()));
        Map<Long, PmsSkuStock> skus = skuStockMapper.selectByExample(skuExample).stream()
                .collect(Collectors.toMap(PmsSkuStock::getId, item -> item));
        for (OmsCartItem item : cartItems) {
            Long productId = item.getProductId();
            PmsProduct product = products.get(productId);
            if (product == null) {
                Asserts.fail(BusinessErrorCode.NOT_FOUND,
                        Map.of("field", "productId", "value", productId, "reason", "商品不存在"));
            }
            if (!Integer.valueOf(0).equals(product.getDeleteStatus())) {
                Asserts.fail(BusinessErrorCode.DATA_CONFLICT,
                        Map.of("field", "productId", "value", productId, "reason", "商品已删除或不可用"));
            }
            if (!Integer.valueOf(1).equals(product.getPublishStatus())) {
                Asserts.fail(BusinessErrorCode.DATA_CONFLICT,
                        Map.of("field", "productId", "value", productId, "reason", "商品已下架或不可用"));
            }
            Long skuId = item.getProductSkuId();
            PmsSkuStock sku = skus.get(skuId);
            if (sku == null) {
                Asserts.fail(BusinessErrorCode.NOT_FOUND,
                        Map.of("field", "productSkuId", "value", skuId, "reason", "SKU不存在"));
            }
            if (!productId.equals(sku.getProductId())) {
                Asserts.fail(BusinessErrorCode.DATA_CONFLICT,
                        Map.of("field", "productSkuId", "value", skuId, "reason", "SKU不属于当前商品"));
            }
        }
    }
}
