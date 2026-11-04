package com.macro.mall.service.impl;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.macro.mall.common.api.BusinessErrorCode;
import com.macro.mall.common.exception.Asserts;
import com.macro.mall.dao.UmsMenuPlusMapper;
import com.macro.mall.dao.UmsRoleMenuRelationPlusMapper;
import com.macro.mall.dao.UmsRolePlusMapper;
import com.macro.mall.dto.UmsMenuNode;
import com.macro.mall.mapper.UmsMenuMapper;
import com.macro.mall.model.*;
import com.macro.mall.service.UmsMenuService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 后台菜单管理Service实现类
 * Created by macro on 2020/2/2.
 */
@Service
public class UmsMenuServiceImpl implements UmsMenuService {
    @Autowired
    private UmsMenuMapper menuMapper;
    @Autowired
    private UmsRoleMenuRelationPlusMapper umsRoleMenuRelationPlusMapper;
    @Autowired
    private UmsMenuPlusMapper umsMenuPlusMapper;

    @Override
    public int create(UmsMenu umsMenu) {
        umsMenu.setCreateTime(new Date());
        updateLevel(umsMenu);
        return menuMapper.insert(umsMenu);
    }

    /**
     * 修改菜单层级
     */
    private void updateLevel(UmsMenu umsMenu) {
        if (umsMenu.getParentId() == 0) {
            //没有父菜单时为一级菜单
            umsMenu.setLevel(0);
        } else {
            //有父菜单时选择根据父菜单level设置
            UmsMenu parentMenu = menuMapper.selectByPrimaryKey(umsMenu.getParentId());
            if (parentMenu != null) {
                umsMenu.setLevel(parentMenu.getLevel() + 1);
            } else {
                umsMenu.setLevel(0);
            }
        }
    }

    @Override
    public int update(Long id, UmsMenu umsMenu) {
        umsMenu.setId(id);
        updateLevel(umsMenu);
        return menuMapper.updateByPrimaryKeySelective(umsMenu);
    }

    @Override
    public UmsMenu getItem(Long id) {
        UmsMenu menu = menuMapper.selectByPrimaryKey(id);
        if (menu == null) {
            Asserts.fail(BusinessErrorCode.NOT_FOUND, Map.of("field", "id", "value", id));
        }
        return menu;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int delete(Long id) {
//        return menuMapper.deleteByPrimaryKey(id);

        if (id == null) {
            Asserts.fail("菜单ID不能为空");
        }

        UmsMenu umsMenu = menuMapper.selectByPrimaryKey(id);
        if (umsMenu == null){
            Asserts.fail("菜单不存在");
        }
        //1. 检查当前菜单是否有Role和子菜单进行引用
        //1.1 如果有子菜单引用，抛出api异常，并告诉前端，不能删除该菜单
        Long childCount = umsMenuPlusMapper.selectCount(Wrappers.<UmsMenu>lambdaQuery()
            .eq(UmsMenu::getParentId, id));
        if (childCount!=null && childCount > 0 ){
            Asserts.fail("必须要将所有角色的菜单迁移之后才能进行");
        }


        //1.2 如果有Role引用，抛出api异常，并告诉前端，必须要将所有角色的菜单迁移之后才能进行
        Long roleCount = umsRoleMenuRelationPlusMapper.selectCount(Wrappers.<UmsRoleMenuRelation>lambdaQuery()
            .eq(UmsRoleMenuRelation::getMenuId, id));

        if (roleCount!=null && roleCount>0) {
            Asserts.fail("必须要将所有角色的菜单迁移之后才能进行");
        }

        //2. 如果没有引用，删除对应的role表
        return menuMapper.deleteByPrimaryKey(id);
    }


    @Override
    public List<UmsMenu> list(Long parentId, Integer pageSize, Integer pageNum) {
        PageHelper.startPage(pageNum, pageSize);
        UmsMenuExample example = new UmsMenuExample();
        example.setOrderByClause("sort desc");
        example.createCriteria().andParentIdEqualTo(parentId);
        return menuMapper.selectByExample(example);
    }

    @Override
    public List<UmsMenuNode> treeList() {
        List<UmsMenu> menuList = menuMapper.selectByExample(new UmsMenuExample());
        List<UmsMenuNode> result = menuList.stream()
                .filter(menu -> menu.getParentId().equals(0L))
                .map(menu -> covertMenuNode(menu, menuList))
                .collect(Collectors.toList());
        return result;
    }

    @Override
    public int updateHidden(Long id, Integer hidden) {
        UmsMenu umsMenu = new UmsMenu();
        umsMenu.setId(id);
        umsMenu.setHidden(hidden);
        return menuMapper.updateByPrimaryKeySelective(umsMenu);
    }

    /**
     * 将UmsMenu转化为UmsMenuNode并设置children属性
     */
    private UmsMenuNode covertMenuNode(UmsMenu menu, List<UmsMenu> menuList) {
        UmsMenuNode node = new UmsMenuNode();
        BeanUtils.copyProperties(menu, node);
        List<UmsMenuNode> children = menuList.stream()
                .filter(subMenu -> subMenu.getParentId().equals(menu.getId()))
                .map(subMenu -> covertMenuNode(subMenu, menuList)).collect(Collectors.toList());
        node.setChildren(children);
        return node;
    }
}
