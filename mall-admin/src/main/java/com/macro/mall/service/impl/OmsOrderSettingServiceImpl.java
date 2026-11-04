package com.macro.mall.service.impl;

import com.macro.mall.common.api.BusinessErrorCode;
import com.macro.mall.common.exception.Asserts;
import com.macro.mall.mapper.OmsOrderSettingMapper;
import com.macro.mall.model.OmsOrderSetting;
import com.macro.mall.service.OmsOrderSettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 订单设置管理Service实现类
 * Created by macro on 2018/10/16.
 */
@Service
public class OmsOrderSettingServiceImpl implements OmsOrderSettingService {
    @Autowired
    private OmsOrderSettingMapper orderSettingMapper;

    @Override
    public OmsOrderSetting getItem(Long id) {
        OmsOrderSetting orderSetting = orderSettingMapper.selectByPrimaryKey(id);
        if (orderSetting == null) {
            Asserts.fail(BusinessErrorCode.NOT_FOUND, Map.of("field", "id", "value", id));
        }
        return orderSetting;
    }

    @Override
    public int update(Long id, OmsOrderSetting orderSetting) {
        orderSetting.setId(id);
        return orderSettingMapper.updateByPrimaryKey(orderSetting);
    }
}
