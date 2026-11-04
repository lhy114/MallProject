# mall-admin 功能说明书

本文只说明 `mall-admin`，不包含 `mall-portal`、`mall-search` 和微服务版本。

## 1. 系统定位

`mall-admin` 是电商平台的运营管理后台，主要服务于平台内部人员。

使用对象包括：

```text
系统管理员
商品运营
订单运营
营销运营
内容运营
客服或售后人员
```

系统目标：

```text
安全登录后台
管理后台用户、角色、菜单和接口资源
维护商品、分类、品牌、属性和库存
查询和处理订单、发货和售后
配置优惠券、秒杀和首页推荐
维护专题和优选区域等基础内容
上传商品图片和运营素材
```

不属于 `mall-admin` 的功能：

```text
会员注册和登录
购物车
会员下单
会员支付
会员浏览历史
商品搜索
```

这些功能分别属于 `mall-portal` 和 `mall-search`。

## 2. 当前实现规模

当前 `mall-admin` 包含：

```text
31 个 Controller
160 个接口方法
31 个 Service 接口
31 个 Service 实现类
```

数据库中的表按业务前缀分类：

```text
pms_*   18 张表，商品管理
oms_*    8 张表，订单管理
sms_*   13 张表，营销管理
ums_*   25 张表，用户、权限和会员管理
cms_*   12 张表，内容管理
```

注意：

```text
数据库有表，不代表 mall-admin 已经提供了对应接口。
mall-admin 只实现了平台运营需要的部分功能。
```

## 3. 代码分层

`mall-admin` 采用典型分层结构：

```text
Controller
-> Service
-> ServiceImpl
-> Mapper / Dao
-> MySQL
```

### Controller

职责：

```text
接收 HTTP 请求
解析路径、查询参数和请求体
执行参数校验
调用 Service
使用 CommonResult 返回结果
```

### Service

职责：

```text
定义业务能力
声明事务边界
组织多个 Mapper 和 Dao
保证业务规则
```

### Mapper

职责：

```text
MyBatis Generator 自动生成
负责单表或基础条件查询
对应 mall-mbg 中的接口和 XML
```

### Dao

职责：

```text
手写复杂查询
处理多表关联
处理聚合和业务专用查询
```

### Model

职责：

```text
数据库表对应的实体对象
主要位于 mall-mbg
```

### DTO 和 Param

职责：

```text
接收前端请求参数
避免 Controller 直接接收数据库实体
支持参数校验
```

### BO

职责：

```text
服务内部使用
例如登录用户、权限资源等
```

## 4. 系统权限域

权限域是整个后台的入口，决定谁可以登录、能看到哪些菜单、能访问哪些接口。

### 4.1 后台用户管理

Controller：

```text
UmsAdminController
```

基础路径：

```text
/admin
```

主要接口：

| 接口 | 功能 |
|---|---|
| `POST /admin/register` | 注册后台管理员 |
| `POST /admin/login` | 管理员登录并返回 JWT |
| `GET /admin/refreshToken` | 刷新 Token |
| `GET /admin/info` | 获取当前登录用户信息 |
| `POST /admin/logout` | 退出登录 |
| `GET /admin/list` | 分页查询管理员 |
| `GET /admin/{id}` | 查询指定管理员 |
| `POST /admin/update/{id}` | 修改管理员 |
| `POST /admin/updatePassword` | 修改密码 |
| `POST /admin/delete/{id}` | 删除管理员 |
| `POST /admin/updateStatus/{id}` | 修改账号状态 |
| `POST /admin/role/update` | 给管理员分配角色 |
| `GET /admin/role/{adminId}` | 查询管理员角色 |

主要业务规则：

```text
密码使用 BCrypt 加密存储
登录成功后生成 JWT
账号禁用后不能登录
管理员通过角色获得权限
退出时清理用户相关缓存
```

涉及数据表：

```text
ums_admin
ums_admin_login_log
ums_admin_role_relation
```

### 4.2 角色管理

Controller：

```text
UmsRoleController
```

基础路径：

```text
/role
```

主要接口：

| 接口 | 功能 |
|---|---|
| `POST /role/create` | 添加角色 |
| `POST /role/update/{id}` | 修改角色 |
| `POST /role/delete` | 批量删除角色 |
| `GET /role/listAll` | 查询全部角色 |
| `GET /role/list` | 分页查询角色 |
| `POST /role/updateStatus/{id}` | 启用或禁用角色 |
| `GET /role/listMenu/{roleId}` | 查询角色拥有的菜单 |
| `GET /role/listResource/{roleId}` | 查询角色拥有的资源 |
| `POST /role/allocMenu` | 给角色分配菜单 |
| `POST /role/allocResource` | 给角色分配接口资源 |

主要业务规则：

```text
角色是管理员和权限之间的中间层
角色可以绑定多个菜单
角色可以绑定多个后台资源
禁用角色后，该角色的权限不应该继续生效
```

涉及数据表：

```text
ums_role
ums_role_menu_relation
ums_role_resource_relation
ums_role_permission_relation
```

### 4.3 菜单管理

Controller：

```text
UmsMenuController
```

基础路径：

```text
/menu
```

主要接口：

| 接口 | 功能 |
|---|---|
| `POST /menu/create` | 添加菜单 |
| `POST /menu/update/{id}` | 修改菜单 |
| `GET /menu/{id}` | 查询菜单详情 |
| `POST /menu/delete/{id}` | 删除菜单 |
| `GET /menu/list/{parentId}` | 分页查询子菜单 |
| `GET /menu/treeList` | 返回完整菜单树 |
| `POST /menu/updateHidden/{id}` | 控制菜单显示或隐藏 |

菜单负责：

```text
前端后台导航展示
父子菜单层级
菜单排序
菜单显示状态
```

涉及数据表：

```text
ums_menu
ums_role_menu_relation
```

### 4.4 后台资源管理

Controller：

```text
UmsResourceController
```

基础路径：

```text
/resource
```

主要接口：

| 接口 | 功能 |
|---|---|
| `POST /resource/create` | 添加资源 |
| `POST /resource/update/{id}` | 修改资源 |
| `GET /resource/{id}` | 获取资源详情 |
| `POST /resource/delete/{id}` | 删除资源 |
| `GET /resource/list` | 分页查询资源 |
| `GET /resource/listAll` | 查询全部资源 |

资源代表后端接口权限。

例如：

```text
资源名称：brand:list
资源 URL：/brand/list
HTTP 方法：GET
```

涉及数据表：

```text
ums_resource
ums_role_resource_relation
```

### 4.5 资源分类管理

Controller：

```text
UmsResourceCategoryController
```

基础路径：

```text
/resourceCategory
```

主要接口：

```text
GET  /resourceCategory/listAll
POST /resourceCategory/create
POST /resourceCategory/update/{id}
POST /resourceCategory/delete/{id}
```

作用：

```text
把大量后台接口资源按业务分类
方便角色分配资源
```

涉及数据表：

```text
ums_resource_category
```

### 4.6 会员等级

Controller：

```text
UmsMemberLevelController
```

基础路径：

```text
/memberLevel
```

当前只提供：

```text
GET /memberLevel/list
```

目前是只读功能，不包含完整会员等级 CRUD。

涉及数据表：

```text
ums_member_level
```

## 5. 商品中心域

商品中心负责维护商品主数据、分类、品牌、属性、规格和库存。

### 5.1 商品管理

Controller：

```text
PmsProductController
```

基础路径：

```text
/product
```

主要接口：

| 接口 | 功能 |
|---|---|
| `POST /product/create` | 创建商品 |
| `GET /product/updateInfo/{id}` | 获取编辑商品数据 |
| `POST /product/update/{id}` | 更新商品 |
| `GET /product/list` | 分页查询商品 |
| `GET /product/simpleList` | 按名称或货号模糊查询 |
| `POST /product/update/verifyStatus` | 批量审核 |
| `POST /product/update/publishStatus` | 批量上下架 |
| `POST /product/update/recommendStatus` | 批量推荐 |
| `POST /product/update/newStatus` | 批量设为新品 |
| `POST /product/update/deleteStatus` | 批量逻辑删除 |

商品不是一个单表对象，创建和更新通常会同时处理：

```text
商品主表
商品属性值
SKU 库存
会员价格
阶梯价格
满减规则
```

涉及数据表：

```text
pms_product
pms_product_attribute_value
pms_sku_stock
pms_member_price
pms_product_ladder
pms_product_full_reduction
pms_product_vertify_record
```

### 5.2 商品分类

Controller：

```text
PmsProductCategoryController
```

基础路径：

```text
/productCategory
```

主要功能：

```text
添加分类
修改分类
分页查询分类
查询分类详情
删除分类
修改导航栏显示状态
修改分类显示状态
查询一级分类和子分类树
```

涉及数据表：

```text
pms_product_category
pms_product_category_attribute_relation
```

### 5.3 商品属性

Controller：

```text
PmsProductAttributeController
PmsProductAttributeCategoryController
```

主要功能：

```text
商品属性分类 CRUD
商品属性或参数 CRUD
根据分类查询属性
根据商品分类查询属性和属性分类
批量删除属性
```

属性分为：

```text
规格
参数
```

涉及数据表：

```text
pms_product_attribute
pms_product_attribute_category
pms_product_attribute_value
pms_product_category_attribute_relation
```

### 5.4 品牌管理

Controller：

```text
PmsBrandController
```

基础路径：

```text
/brand
```

主要功能：

```text
查询全部品牌
分页查询品牌
添加品牌
修改品牌
删除品牌
批量删除品牌
修改显示状态
修改厂家制造商状态
```

业务规则：

```text
品牌名不能重复
首字母为空时自动取品牌名第一个字
更新品牌名称时同步更新商品表中的品牌名称
删除品牌前可能需要检查是否被商品使用
```

涉及数据表：

```text
pms_brand
pms_product
```

### 5.5 SKU 库存

Controller：

```text
PmsSkuStockController
```

基础路径：

```text
/sku
```

主要功能：

```text
根据商品 ID 查询 SKU
根据 SKU 编码模糊查询
批量更新 SKU 库存
```

涉及数据表：

```text
pms_sku_stock
```

## 6. 订单中心域

订单中心负责订单查询、履约、关闭、售后和订单设置。

### 6.1 订单管理

Controller：

```text
OmsOrderController
```

基础路径：

```text
/order
```

主要接口：

| 接口 | 功能 |
|---|---|
| `GET /order/list` | 分页查询订单 |
| `POST /order/update/delivery` | 批量发货 |
| `POST /order/update/close` | 批量关闭 |
| `POST /order/delete` | 批量删除 |
| `GET /order/{id}` | 查询订单详情 |
| `POST /order/update/receiverInfo` | 修改收货人信息 |
| `POST /order/update/moneyInfo` | 修改订单费用 |
| `POST /order/update/note` | 修改订单备注 |

订单详情通常包括：

```text
订单主信息
订单商品
收货信息
支付信息
物流信息
操作历史
```

涉及数据表：

```text
oms_order
oms_order_item
oms_order_operate_history
```

### 6.2 退货申请

Controller：

```text
OmsOrderReturnApplyController
```

基础路径：

```text
/returnApply
```

主要功能：

```text
分页查询退货申请
批量删除
查询详情
修改申请处理状态
```

涉及数据表：

```text
oms_order_return_apply
```

### 6.3 退货原因

Controller：

```text
OmsOrderReturnReasonController
```

基础路径：

```text
/returnReason
```

主要功能：

```text
添加退货原因
修改退货原因
删除退货原因
分页查询
查询详情
修改启用状态
```

涉及数据表：

```text
oms_order_return_reason
```

### 6.4 订单设置

Controller：

```text
OmsOrderSettingController
```

基础路径：

```text
/orderSetting
```

主要功能：

```text
查询订单设置
修改订单设置
```

可能涉及：

```text
未支付订单超时时间
发货后自动确认收货时间
完成订单后自动关闭时间
```

涉及数据表：

```text
oms_order_setting
```

### 6.5 公司收货地址

Controller：

```text
OmsCompanyAddressController
```

基础路径：

```text
/companyAddress
```

当前提供：

```text
GET /companyAddress/list
```

涉及数据表：

```text
oms_company_address
```

## 7. 营销中心域

营销中心负责优惠券、秒杀和首页推荐。

### 7.1 优惠券

Controller：

```text
SmsCouponController
SmsCouponHistoryController
```

主要功能：

```text
优惠券创建、修改和删除
优惠券分页查询
优惠券详情
优惠券领取记录查询
优惠券适用范围配置
```

优惠券通常支持：

```text
全场通用
指定分类
指定商品
固定金额
百分比折扣
领取数量限制
使用数量限制
有效期控制
```

涉及数据表：

```text
sms_coupon
sms_coupon_history
sms_coupon_product_relation
sms_coupon_product_category_relation
```

### 7.2 秒杀活动

Controller：

```text
SmsFlashPromotionController
SmsFlashPromotionSessionController
SmsFlashPromotionProductRelationController
```

主要功能：

```text
秒杀活动 CRUD
活动上下线
秒杀场次 CRUD
场次启用状态
秒杀商品关联
修改秒杀价格和库存
```

涉及数据表：

```text
sms_flash_promotion
sms_flash_promotion_session
sms_flash_promotion_product_relation
sms_flash_promotion_log
```

### 7.3 首页营销

涉及：

```text
SmsHomeAdvertiseController
SmsHomeBrandController
SmsHomeNewProductController
SmsHomeRecommendProductController
SmsHomeRecommendSubjectController
```

主要功能：

```text
首页轮播广告管理
首页推荐品牌
首页新品
首页人气推荐
首页专题推荐
推荐排序
推荐状态
批量删除
分页查询
```

对应数据表：

```text
sms_home_advertise
sms_home_brand
sms_home_new_product
sms_home_recommend_product
sms_home_recommend_subject
```

## 8. 内容中心域

当前功能较少，主要是查询。

### 8.1 商品专题

Controller：

```text
CmsSubjectController
```

主要功能：

```text
查询全部商品专题
分页查询商品专题
```

涉及数据表：

```text
cms_subject
cms_subject_category
cms_subject_product_relation
cms_subject_comment
```

### 8.2 商品优选

Controller：

```text
CmsPrefrenceAreaController
```

主要功能：

```text
查询全部商品优选区域
```

涉及数据表：

```text
cms_prefrence_area
cms_prefrence_area_product_relation
```

## 9. 文件资源域

### 9.1 MinIO

Controller：

```text
MinioController
```

主要功能：

```text
上传文件
删除文件
创建 Bucket
设置 Bucket 只读策略
```

### 9.2 阿里云 OSS

Controller：

```text
OssController
```

主要功能：

```text
生成前端直传 OSS 的签名
接收 OSS 上传成功回调
```

## 10. 认证和授权设计

认证由：

```text
mall-admin
mall-security
```

共同实现。

登录流程：

```text
用户名和密码
-> UmsAdminServiceImpl.login
-> 查询 ums_admin
-> BCrypt 校验密码
-> 查询管理员角色和资源
-> JwtTokenUtil 生成 Token
-> 返回 token 和 tokenHead
```

后续请求：

```text
Authorization: Bearer <token>
-> JwtAuthenticationTokenFilter
-> 解析用户名
-> UserDetailsService 加载用户
-> 校验 Token
-> 写入 SecurityContext
-> 执行接口权限校验
```

菜单和资源的区别：

```text
菜单
    控制后台左侧导航显示什么。

资源
    控制后端接口能不能访问。

角色
    同时绑定菜单和资源。

管理员
    通过角色获得菜单和资源。
```

## 11. 数据库设计概览

### ums

负责：

```text
后台管理员
角色
菜单
资源
会员等级
会员相关表
```

### pms

负责：

```text
商品
分类
品牌
属性
SKU
价格规则
审核记录
```

### oms

负责：

```text
订单
订单商品
订单操作记录
退货
订单设置
公司地址
```

### sms

负责：

```text
优惠券
秒杀
首页推荐
营销关系表
```

### cms

负责：

```text
专题
优选区域
帮助
话题
评论
```

## 12. 当前功能边界

`mall-admin` 已经实现：

```text
后台用户和权限
商品管理
订单管理
营销管理
部分内容管理
文件上传
```

`mall-admin` 没有完整实现：

```text
会员注册和登录
会员信息管理
购物车
消费者下单
支付
物流轨迹
商品搜索
完整内容编辑
```

数据库里有 `ums_member` 等表，但 `mall-admin` 当前并没有提供完整会员 CRUD 接口。

数据库里有 `cms_help`、`cms_topic` 等表，但也不能据此认为这些内容管理功能已经完整实现。

## 13. 学习顺序

学习 `mall-admin` 时建议按下面顺序：

```text
1. 后台管理员登录和 JWT
2. 角色、菜单、资源动态权限
3. 品牌管理
4. 商品分类和属性
5. 商品创建和更新
6. SKU 库存
7. 订单查询和履约
8. 退货申请
9. 优惠券
10. 秒杀
11. 首页推荐
12. 内容查询
13. MinIO 和 OSS
```

不要一开始阅读全部 31 个 Controller。先完成“登录 -> 权限 -> 品牌 -> 商品 -> 订单”这条主业务链。

