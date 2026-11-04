package com.macro.mall.service.impl;

import cn.hutool.core.util.StrUtil;
import com.github.pagehelper.PageHelper;
import com.macro.mall.common.api.BusinessErrorCode;
import com.macro.mall.common.exception.Asserts;
import com.macro.mall.mapper.SmsFlashPromotionMapper;
import com.macro.mall.model.SmsFlashPromotion;
import com.macro.mall.model.SmsFlashPromotionExample;
import com.macro.mall.service.SmsFlashPromotionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 限时购活动管理Service实现类
 * Created by macro on 2018/11/16.
 */
@Service
public class SmsFlashPromotionServiceImpl implements SmsFlashPromotionService {
    @Autowired
    private SmsFlashPromotionMapper flashPromotionMapper;

    @Override
    public int create(SmsFlashPromotion flashPromotion) {
        flashPromotion.setCreateTime(new Date());
        return flashPromotionMapper.insert(flashPromotion);
    }

    @Override
    public int update(Long id, SmsFlashPromotion flashPromotion) {
        flashPromotion.setId(id);
        return flashPromotionMapper.updateByPrimaryKey(flashPromotion);
    }

    @Override
    public int delete(Long id) {
        // 这里还是有问题，就是删除的时候，log你进行处理吗，同时这里的promotion_production关系你也不进行操作
        return flashPromotionMapper.deleteByPrimaryKey(id);
    }

    @Override
    public int updateStatus(Long id, Integer status) {
        SmsFlashPromotion flashPromotion = new SmsFlashPromotion();
        flashPromotion.setId(id);
        flashPromotion.setStatus(status);
        return flashPromotionMapper.updateByPrimaryKeySelective(flashPromotion);
    }

    @Override
    public SmsFlashPromotion getItem(Long id) {
        SmsFlashPromotion flashPromotion = flashPromotionMapper.selectByPrimaryKey(id);
        if (flashPromotion == null) {
            Asserts.fail(BusinessErrorCode.NOT_FOUND, Map.of("field", "id", "value", id));
        }
        return flashPromotion;
    }

    @Override
    public List<SmsFlashPromotion> list(String keyword, Integer pageSize, Integer pageNum) {
        PageHelper.startPage(pageNum, pageSize);
        SmsFlashPromotionExample example = new SmsFlashPromotionExample();
        if (!StrUtil.isEmpty(keyword)) {
            example.createCriteria().andTitleLike("%" + keyword + "%");
        }
        return flashPromotionMapper.selectByExample(example);
    }
}
