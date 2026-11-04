package com.macro.mall.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.macro.mall.common.exception.Asserts;
import com.macro.mall.dao.AdminRoleRelationPlusMapper;
import com.macro.mall.dao.UmsRoleDao;
import com.macro.mall.dao.UmsRoleMenuRelationPlusMapper;
import com.macro.mall.dao.UmsRolePermissionRelationPlusMapper;
import com.macro.mall.dao.UmsRolePlusMapper;
import com.macro.mall.dao.UmsRoleResourceRelationPlusMapper;
import com.macro.mall.mapper.UmsMenuMapper;
import com.macro.mall.mapper.UmsResourceMapper;
import com.macro.mall.mapper.UmsRoleMapper;
import com.macro.mall.model.*;
import com.macro.mall.service.UmsAdminCacheService;
import com.macro.mall.service.UmsRoleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.macro.mall.common.constant.UmsConstants.SUPER_ADMIN_ROLE_ID;

/**
 * 后台角色管理Service实现类
 * Created by macro on 2018/9/30.
 */
@Service
public class UmsRoleServiceImpl implements UmsRoleService {
    @Autowired
    private UmsRoleMapper roleMapper;
    @Autowired
    private UmsMenuMapper menuMapper;
    @Autowired
    private UmsResourceMapper resourceMapper;
    @Autowired
    private UmsRoleDao roleDao;
    @Autowired
    private UmsAdminCacheService adminCacheService;
    @Autowired
    private UmsRolePlusMapper rolePlusMapper;
    @Autowired
    private AdminRoleRelationPlusMapper adminRoleRelationPlusMapper;
    @Autowired
    private UmsRoleMenuRelationPlusMapper roleMenuRelationPlusMapper;
    @Autowired
    private UmsRoleResourceRelationPlusMapper roleResourceRelationPlusMapper;
    @Autowired
    private UmsRolePermissionRelationPlusMapper rolePermissionRelationPlusMapper;

    @Override
    public int create(UmsRole role) {
        role.setCreateTime(new Date());
        role.setAdminCount(0);
        role.setSort(0);
        if (role.getStatus() == null) {
            role.setStatus(1);
        }
        return roleMapper.insert(role);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int update(Long id, UmsRole role) {
        if (id == null) {
            Asserts.fail("角色ID不能为空");
        }
        role.setId(id);
        int count = roleMapper.updateByPrimaryKeySelective(role);
        if (count > 0) {
            List<Long> affectedAdminIds =
                getAdminIdsByRoleIds(Collections.singletonList(id));
            clearResourceCacheAfterCommit(affectedAdminIds);
        }
        return count;
    }

    @Override
    @Transactional
    public int delete(List<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return 0;
        }

        // 1. 保护内置角色
        if (ids.contains(SUPER_ADMIN_ROLE_ID)) {
            Asserts.fail("系统内置角色不能删除");
        }

        // 2. 删除关系前记录受影响的用户，事务提交后再清缓存
        List<Long> affectedAdminIds = getAdminIdsByRoleIds(ids);

        // 3. 删除管理员和角色关系
        adminRoleRelationPlusMapper.delete(
            Wrappers.<UmsAdminRoleRelation>lambdaQuery()
                .in(UmsAdminRoleRelation::getRoleId, ids)
        );

        // 4. 删除角色和菜单关系
        roleMenuRelationPlusMapper.delete(
            Wrappers.<UmsRoleMenuRelation>lambdaQuery()
                .in(UmsRoleMenuRelation::getRoleId, ids)
        );

        // 5. 删除角色和资源关系
        roleResourceRelationPlusMapper.delete(
            Wrappers.<UmsRoleResourceRelation>lambdaQuery()
                .in(UmsRoleResourceRelation::getRoleId, ids)
        );

        // 6. 删除角色和旧权限模型关系
        rolePermissionRelationPlusMapper.delete(
            Wrappers.<UmsRolePermissionRelation>lambdaQuery()
                .in(UmsRolePermissionRelation::getRoleId, ids)
        );

        // 7. 最后删除角色主表
        int count = rolePlusMapper.deleteByIds(ids);
        clearResourceCacheAfterCommit(affectedAdminIds);
        return count;
    }

    @Override
    public List<UmsRole> list() {
        return roleMapper.selectByExample(new UmsRoleExample());
    }

    @Override
    public List<UmsRole> list(String keyword, Integer pageSize, Integer pageNum) {
        PageHelper.startPage(pageNum, pageSize);
        UmsRoleExample example = new UmsRoleExample();
        if (!StrUtil.isEmpty(keyword)) {
            example.createCriteria().andNameLike("%" + keyword + "%");
        }
        return roleMapper.selectByExample(example);
    }

    @Override
    public List<UmsMenu> getMenuList(Long adminId) {
        return roleDao.getMenuList(adminId);
    }

    @Override
    public List<UmsMenu> listMenu(Long roleId) {
        return roleDao.getMenuListByRoleId(roleId);
    }

    @Override
    public List<UmsResource> listResource(Long roleId) {
        return roleDao.getResourceListByRoleId(roleId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int allocMenu(Long roleId, List<Long> menuIds) {
        validateRole(roleId);

        // 空集合表示清空该角色的全部菜单
        List<Long> distinctMenuIds = normalizeIds(menuIds);
        validateMenusExist(distinctMenuIds);

        roleMenuRelationPlusMapper.delete(
            Wrappers.<UmsRoleMenuRelation>lambdaQuery()
                .eq(UmsRoleMenuRelation::getRoleId, roleId)
        );

        int count = 0;
        for (Long menuId : distinctMenuIds) {
            UmsRoleMenuRelation relation = new UmsRoleMenuRelation();
            relation.setRoleId(roleId);
            relation.setMenuId(menuId);
            count += roleMenuRelationPlusMapper.insert(relation);
        }
        return count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int allocResource(Long roleId, List<Long> resourceIds) {
        validateRole(roleId);

        // 空集合表示清空该角色的全部资源
        List<Long> distinctResourceIds = normalizeIds(resourceIds);
        validateResourcesExist(distinctResourceIds);

        List<Long> affectedAdminIds =
            getAdminIdsByRoleIds(Collections.singletonList(roleId));

        roleResourceRelationPlusMapper.delete(
            Wrappers.<UmsRoleResourceRelation>lambdaQuery()
                .eq(UmsRoleResourceRelation::getRoleId, roleId)
        );

        int count = 0;
        for (Long resourceId : distinctResourceIds) {
            UmsRoleResourceRelation relation = new UmsRoleResourceRelation();
            relation.setRoleId(roleId);
            relation.setResourceId(resourceId);
            count += roleResourceRelationPlusMapper.insert(relation);
        }

        clearResourceCacheAfterCommit(affectedAdminIds);
        return count;
    }

    private void validateRole(Long roleId) {
        if (roleId == null) {
            Asserts.fail("角色ID不能为空");
        }
        if (rolePlusMapper.selectById(roleId) == null) {
            Asserts.fail("角色不存在");
        }
    }

    private List<Long> normalizeIds(List<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return ids.stream()
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());
    }

    private void validateMenusExist(List<Long> menuIds) {
        if (CollUtil.isEmpty(menuIds)) {
            return;
        }
        UmsMenuExample example = new UmsMenuExample();
        example.createCriteria().andIdIn(menuIds);
        if (menuMapper.countByExample(example) != menuIds.size()) {
            Asserts.fail("包含不存在的菜单");
        }
    }

    private void validateResourcesExist(List<Long> resourceIds) {
        if (CollUtil.isEmpty(resourceIds)) {
            return;
        }
        UmsResourceExample example = new UmsResourceExample();
        example.createCriteria().andIdIn(resourceIds);
        if (resourceMapper.countByExample(example) != resourceIds.size()) {
            Asserts.fail("包含不存在的资源");
        }
    }

    private List<Long> getAdminIdsByRoleIds(List<Long> roleIds) {
        if (CollUtil.isEmpty(roleIds)) {
            return Collections.emptyList();
        }
        return adminRoleRelationPlusMapper.selectList(
                Wrappers.<UmsAdminRoleRelation>lambdaQuery()
                    .select(UmsAdminRoleRelation::getAdminId)
                    .in(UmsAdminRoleRelation::getRoleId, roleIds)
            ).stream()
            .map(UmsAdminRoleRelation::getAdminId)
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());
    }

    private void clearResourceCacheAfterCommit(List<Long> adminIds) {
        if (CollUtil.isEmpty(adminIds)) {
            return;
        }
        Runnable clearCache = () ->
            adminIds.forEach(adminCacheService::delResourceList);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        clearCache.run();
                    }
                }
            );
            return;
        }
        clearCache.run();
    }
}
