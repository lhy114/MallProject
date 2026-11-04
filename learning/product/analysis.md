# 商品模块数据库查询性能：初始分析存档

分析日期：2026-11-04。本文保留最初只读分析时的事实和待验证候选，不代表后续修复状态；索引实验、JOIN 对照、删除策略与已实施代码统一见 [复习总结](review.md)。

初始阶段范围：当前工作区源码及现有本机 Docker 数据库。本阶段只阅读与编写本文件，没有修改业务代码、DDL、索引或数据，没有运行导入、写入测试及压力测试。

## 1. 结论与证据边界

- **已确认**：本机容器 `mall-local-mysql-1` 的 MySQL 为 8.0.46。在 `mall` schema 的商品及下文列出的关联表中，information_schema.STATISTICS 仅返回各表 `PRIMARY(id)`，没有查询到二级索引。仓库初始化脚本也仅定义这些表的主键。此结论仅适用于本机该 schema，不代表其他环境。
- **已确认**：多表一对多同时 JOIN、前导通配符 LIKE、未分页商品选择、整批 ES 导入等查询形态存在。
- **尚未确认**：哪条 SQL 是实际瓶颈、实际扫描行数、执行计划、请求耗时与并发影响。本次没有执行 EXPLAIN 或 EXPLAIN ANALYZE，不编造计划和耗时。
- 当前本机只是部署时初始化的学习数据库，不是线上真实负载；IDEA 是否确实连接本库需检查运行参数。仓库 application-dev.yml 不能独立证明运行时连接目标。

证据分级：E1=静态代码可确认；E2=本机数据库只读元数据确认；E3=实际计划/压测/流量证据。本报告具有 E1/E2，没有 E3。潜在性能影响均附条件，不能以风险直接判定慢查询。

## 2. 模块结构与持久化链路

- 后台：PmsProductController → PmsProductServiceImpl → PmsProductMapper（MBG Example 动态条件）/PmsProductDao（自定义 XML）；品牌、分类、属性、SKU 有各自 Controller/Service/Mapper。
- 商城：PmsPortalProductController → PmsPortalProductServiceImpl；HomeServiceImpl 首页推荐；PmsPortalBrandServiceImpl 品牌商品。PortalProductDao 聚合购物车规格、促销与优惠券。
- 搜索：EsProductController → EsProductServiceImpl → ElasticsearchTemplate/EsProductRepository；ES 导入、单商品创建及相似推荐的源商品读取仍通过 EsProductDao 查询 MySQL。
- 生成 SQL：mall-mbg/src/main/resources/com/macro/mall/mapper/*Mapper.xml；selectByExample 展开 Base_Column_List + Example_Where_Clause，分页由 PageHelper 拦截追加，XML 本身不含 LIMIT。
- 普通商品列表 Base_Column_List 含大量基础列，但**不含** description/detail_desc/detail_html/detail_mobile_html 四个文本列；主键详情查询带这些文本列（M1）。后台编辑 JOIN 使用 SELECT *，不是同一投影。
- 商品列表主链路使用 MBG Mapper，不应因为项目引入 MyBatis-Plus 就误判其分页由 MP 提供。

## 3. 业务查询场景与 SQL 清单

以下 B 表示 M1 中完整基础列；`:参数` 为阅读用占位符，不是可直接执行语法。LIMIT/count 为分页插件预期形态，最终执行 SQL、count 是否发生及分页零值行为需日志确认。可选条件只在参数存在时生成。

|编号/业务|SQL 形态与往返|证据|
|---|---|---|
|Q1 后台商品列表 GET /product/list|`SELECT B FROM pms_product WHERE delete_status=0 [AND publish_status=?] [AND verify_status=?] [AND name LIKE ?] [AND product_sn=?] [AND brand_id=?] [AND product_category_id=?] LIMIT offset,size`；没有显式 ORDER BY；PageHelper 通常另发同条件 COUNT|A1/A2/M1|
|Q2 后台商品选择 GET /product/simpleList|无关键词：`SELECT B FROM pms_product WHERE delete_status=0`；有关键词：`WHERE (delete_status=0 AND name LIKE ?) OR (delete_status=0 AND product_sn LIKE ?)`；LIKE 参数均为 `%keyword%`，无分页/排序|A3|
|Q3 后台编辑详情 GET /product/updateInfo/{id}|`p LEFT JOIN category pc ON pc.id=p.product_category_id LEFT JOIN ladder l ON l.product_id=p.id LEFT JOIN full_reduction pf ON pf.product_id=p.id LEFT JOIN member_price m ON m.product_id=p.id LEFT JOIN sku s ON s.product_id=p.id LEFT JOIN attribute_value a ON a.product_id=p.id WHERE p.id=?`；再嵌套读取专题/优选两个关系表 `WHERE product_id=?`|A4|
|Q4 商城搜索 GET /product/search|`SELECT B FROM pms_product WHERE delete_status=0 AND publish_status=1 [AND name LIKE '%keyword%'] [AND brand_id=?] [AND product_category_id=?] [ORDER BY id DESC / sale DESC / price ASC / price DESC] LIMIT offset,size`；sort=0 不设置排序；通常另有 count|P1|
|Q5 商城详情 GET /product/detail/{id}|商品 `WHERE id=?`（含文本）；品牌 `WHERE id=?`；属性 `WHERE product_attribute_category_id=?`；有属性时属性值 `WHERE product_id=? AND product_attribute_id IN (...)`；SKU `WHERE product_id=?`；promotion_type=3 时阶梯价、=4 时满减 `WHERE product_id=?`；最后优惠券 Q6|P2|
|Q6 详情可用优惠券|`SELECT * FROM sms_coupon WHERE use_type=0 AND start_time<NOW() AND end_time>NOW() UNION SELECT c.* FROM category_relation r LEFT JOIN sms_coupon c ON r.coupon_id=c.id WHERE c.use_type=1 AND 时间条件 AND r.product_category_id=? UNION SELECT c.* FROM product_relation r LEFT JOIN sms_coupon c ON r.coupon_id=c.id WHERE c.use_type=2 AND 时间条件 AND r.product_id=?`|P4|
|Q7 首页新品/热门|`SELECT p.* FROM sms_home_new_product / sms_home_recommend_product hp LEFT JOIN pms_product p ON hp.product_id=p.id WHERE hp.recommend_status=1 AND p.publish_status=1 ORDER BY hp.sort DESC LIMIT offset,size`；首页 content 同时查询多个内容块|H1/H2|
|Q8 首页推荐/品牌商品页|`SELECT B FROM pms_product WHERE delete_status=0 AND publish_status=1 [AND brand_id=?] LIMIT offset,size`，无显式排序，PageHelper|H2/B1|
|Q9 秒杀商品|`SELECT pr.促销字段,p.* FROM sms_flash_promotion_product_relation pr LEFT JOIN pms_product p ON pr.product_id=p.id WHERE pr.flash_promotion_id=? AND pr.flash_promotion_session_id=?`，无分页|HomeDao.xml getFlashProductList|
|Q10 SKU 查询|`SELECT SKU基础列 FROM pms_sku_stock WHERE product_id=? [AND sku_code LIKE '%keyword%']`，无分页|C4|
|Q11 购物车编辑规格|商品主键 JOIN 属性（按属性分类关联）JOIN SKU（product_id），`WHERE p.id=? AND pa.type=0 ORDER BY pa.sort DESC`|P5|
|Q12 购物车/结算促销商品|`SELECT p.部分列,sku.部分列,ladder.部分列,full_re.部分列 FROM pms_product p LEFT JOIN sku ON product_id=p.id LEFT JOIN ladder ON product_id=p.id LEFT JOIN full_re ON product_id=p.id WHERE p.id IN (...)`；批量查询，不是逐商品查库|P3；OmsPromotionServiceImpl.getPromotionProductList|
|Q13 ES 导入/单商品创建/推荐源读取|商品 LEFT JOIN 属性值 LEFT JOIN 属性定义；`WHERE p.delete_status=0 AND p.publish_status=1 [AND p.id=?]`；importAll 无 id、无 LIMIT，一次载入再 saveAll；create/recommend 传 id|S1/S2/S3|
|Q14 ES 普通/综合搜索、相关信息|普通关键字查 repository；综合查询含品牌/分类过滤与排序，相关信息为 ES 聚合。不是 MySQL 商品 LIKE 查询，MySQL EXPLAIN 不能诊断这些请求|EsProductServiceImpl.search/searchRelatedInfo|
|Q15 分类列表/树|后台列表 `WHERE parent_id=?` + PageHelper；后台 listWithChildren 自连接 `c1.id=c2.parent_id WHERE c1.parent_id=0`；商城 categoryTreeList 一次读全表、内存递归；首页分类 `WHERE show_status=1 AND parent_id=? ORDER BY sort DESC`|C1/PmsProductCategoryServiceImpl/P1 同文件 categoryTreeList/HomeServiceImpl|
|Q16 属性/属性分类|属性分页 `WHERE product_attribute_category_id=? AND type=? ORDER BY sort DESC`；分类全表分页；分类附属性一次 LEFT JOIN，条件 pa.type=1；分类关联属性查询由 pcar.product_category_id=? 开始，JOIN 属性/属性分类主键|C2/C3/PmsProductAttributeServiceImpl|
|Q17 品牌管理|品牌全列表或 `name LIKE '%keyword%'` + 可选 show_status，按 sort DESC 分页；删除前 `SELECT DISTINCT b.name FROM pms_brand b INNER JOIN pms_product p ON p.brand_id=b.id WHERE p.delete_status=0 AND b.id IN (...)`|PmsBrandServiceImpl/C5|

商品 Controller 默认后台 pageNum=1、pageSize=5；商城 search 默认 pageNum=0、pageSize=5，与常见 PageHelper 一基页号不一致。不能直接假设默认页扫描全表，需结合有效配置和 SQL 日志复现。控制器相关参数未见明确的最大 pageSize 限制（需与全局绑定/网关策略共同核对）。

## 4. 表结构与现有索引

### 4.1 数据库证据

本次只读执行 `SELECT VERSION()`、information_schema.STATISTICS（按表/索引汇总列）、information_schema.TABLES。STATISTICS 覆盖全部 pms 表以及下列券/促销/专题关联表。

|表组|相关连接/过滤列|本机实际索引|
|---|---|---|
|pms_product|brand_id,product_category_id,product_attribute_category_id,delete_status,publish_status,verify_status,product_sn,name,sale,price|PRIMARY(id)|
|pms_sku_stock|product_id,sku_code|PRIMARY(id)|
|pms_product_attribute_value|product_id,product_attribute_id|PRIMARY(id)|
|pms_product_attribute|product_attribute_category_id,type,sort|PRIMARY(id)|
|pms_product_ladder/full_reduction、pms_member_price|product_id|各 PRIMARY(id)|
|pms_product_category|parent_id,show_status,sort|PRIMARY(id)|
|pms_product_category_attribute_relation|product_category_id,product_attribute_id|PRIMARY(id)|
|pms_brand、pms_product_attribute_category|name/show_status/sort 或分类主键|各 PRIMARY(id)|
|cms_subject_product_relation、cms_prefrence_area_product_relation|product_id|各 PRIMARY(id)|
|sms_coupon|use_type,start_time,end_time|PRIMARY(id)|
|sms_coupon_product_relation/category_relation|product_id/product_category_id,coupon_id|各 PRIMARY(id)|
|sms_home_new_product、sms_home_recommend_product|recommend_status,sort,product_id|各 PRIMARY(id)|
|sms_flash_promotion_product_relation|flash_promotion_id,flash_promotion_session_id,product_id|PRIMARY(id)|

其他 pms 表（album、album_pic、comment、comment_replay、feight_template、product_operate_log、product_vertify_record）同样仅 PRIMARY(id)，但不是本次优先查询路径。

information_schema.TABLES 的 TABLE_ROWS 是估算值：product=38、sku_stock=96、attribute_value=130、attribute=53、category=40、member_price=80、ladder=35、full_reduction=36、brand=12。未执行 COUNT(*) 校正，不视为精确数据量或生产规模。

### 4.2 仓库结构事实

D1 商品表含 bigint 主键、品牌/分类关联列、多状态 int 列、price decimal(10,2)、多个 text 描述列；品牌名/分类名冗余在商品表。关系表通过 bigint 逻辑关联；本脚本相关 CREATE TABLE 未声明外键或关联列二级索引。不应把“有 product_id 列”误认为“有 product_id 索引”。脚本仅是初始化来源，最终状态以上面的本机元数据为准。

### 4.3 SQL 与索引匹配判断

- Q3/Q5 主商品和品牌的 id 等值可利用主键；Q12 的商品 id IN 可具备主键访问条件，但实际连接顺序/方式仍需计划证明。
- Q1/Q2/Q4/Q8 的非主键过滤缺少匹配二级索引（本机已确认）；是否出现 ALL、索引扫描、提前 LIMIT 或排序取决于实际参数和优化器。
- Q3/Q5/Q10/Q11/Q12/Q13 的子表 product_id、属性分类关联目前无索引支持，存在扫描/连接放大风险；MySQL 也可能选择 hash join，不能将其描述成已发生的逐行嵌套全扫描。
- Q4 `ORDER BY id DESC` 与按 sale/price 排序不同，主键有序访问可能避免单独排序；不能一概说所有排序必然 filesort。
- 回表：当前没有这些表的二级索引，不能宣称已经发生“二级索引回表”。未来使用非覆盖二级索引读取宽行时才需要评估聚簇主键访问成本。id 主键访问不是二级索引回表。

## 5. 潜在问题与证据强弱

|风险|已确认事实|尚需验证的影响|
|---|---|---|
|多个一对多 JOIN 行数膨胀（高优先）|A4 同时 JOIN 阶梯价、满减、会员价、SKU、属性值；返回映射去重不等于数据库减少传输|单商品结果行近似各子表 max(1,行数) 的乘积，重复传输商品文本；实际基数与字节数未测|
|促销/购物车 JOIN 膨胀|P3 的 SKU×阶梯×满减；P5 属性×SKU；都是 E1|购物车商品数、每商品规格基数增长后的数据库/网络/映射耗时|
|子表关联列无二级索引|E2 确认，涵盖多数详情子表|扫描成本在当前小表可能很低，大表及高 QPS 下可能显著|
|前导通配符及 OR|A2/A3/P1/C4 中均存在 `%keyword%`|普通 B-tree 对包含匹配难以形成有效前缀范围；其他等值条件可能先缩小候选集，不能说必然整个表全扫|
|排序、COUNT 与深分页|PageHelper 查询和首页显式 offset；sale/price 无索引；Q1/Q8/Q2 无稳定排序|大 offset、低选择性过滤及 count 的额外成本，需分别测；无 ORDER BY 页间结果不稳定是确定性风险|
|未分页/宽投影|Q2 无 LIMIT；S2 全量列表加载；Q7 p.*；M1 列表仍较宽|Q2 空关键词可能返回所有未删除商品；ES 导入内存峰值、响应体和网络字节需测|
|详情往返多|P2 正常非空商品基础 5 条（商品、品牌、属性、SKU、优惠券）+有属性时 1 条+类型3或4再1条，即通常5–7条|顺序往返成本、品牌/属性跨请求重复率；本方法未见缓存层，不等于整个应用不存在数据库缓存|
|优惠券 UNION|P4 三分支 UNION（去重语义）及时间范围，关联无二级索引|临时表/去重/时间过滤成本及实际行数；不能直接改 UNION ALL，必须证明业务与重复语义允许|
|树结构|categoryTreeList 一次读全表后反复筛 allList|不是数据库 N+1；是潜在内存 O(n²) 级处理问题，需分类规模与 CPU 证据|

### N+1 和重复查询的准确界定

A4 的 resultMap 两个 collection 使用 select 嵌套查询。当前接口取一个商品，因此通常是主查询+两条固定关系查询；不是已经确认的“列表 N 个商品各查一次”。需要 SQL 日志核对 MyBatis 缓存和嵌套加载实际触发次数。若以后复用于多商品才会形成随 N 增长的风险。

P2 多条固定详情查询不等于 N+1；商品列表未见遍历每行再调用详情。OmsPromotionServiceImpl 先批量 getPromotionProductList，后续遍历主要在内存计算，不能仅看到 for 就判定 N+1。首页多个不同内容块查询不是同 SQL 重复。跨请求品牌、属性、优惠券重复读取可能值得缓存评估，但尚无命中率/新鲜度要求；库存与促销不能盲目缓存。SKU 更新路径存在逐 SKU 更新，但属于写路径，不作为本次查询性能结论。

## 6. 现有测试与迁移机制

- T1 为 @SpringBootTest：批量会员价插入测试带事务回滚；编辑详情测试仅查询 id=7 并打印 JSON，未验证行放大、查询次数或时延。该文件在 `src/test/com/...`，不是标准 `src/test/java`；本次查看模块 POM 未发现对应 testSourceDirectory 配置，需 Maven testCompile/测试发现报告核实是否被执行。
- T2 在标准测试路径，@SpringBootTest 查询固定四个商品，断言结果数量为4。它依赖预置数据库，属于集成测试，未验证扫描行、重复行和性能。
- search 有应用上下文测试入口，未见商品搜索基准、EXPLAIN 回归、查询预算或数据规模分层测试。
- 根 pom.xml 默认 `skipTests=true`；不能把普通 Maven 构建成功说成商品测试通过。后续需显式 `-DskipTests=false` 并核对实际测试报告，先核对连接到隔离测试库。
- 本仓库发现 `document/sql/mall.sql` 整库初始化脚本、MBG `generatorConfig.xml`；未发现 Flyway/Liquibase 依赖或版本化 migration 目录。MBG 生成模型不是数据库迁移机制。项目外 Docker 的首次初始化也不构成增量迁移。本阶段未新建迁移文件。
- 不执行现有测试，避免插入测试/上下文启动超出只读分析范围；不通过生成数据伪造生产规模。

## 7. 下一阶段验证方案（本次均未执行）

### 7.1 获取正确环境与实际 SQL

确认 IDEA 有效 datasource URL/schema、MySQL 版本、存储引擎、排序规则、SQL mode、连接池及数据库网络 RTT。收集应用实际参数和绑定值的脱敏样本，以及 PageHelper 发出的数据 SQL 和 COUNT SQL。不要仅把 XML 粘到 EXPLAIN。

只读索引核对示例：

```sql
SELECT VERSION(), DATABASE();
SHOW CREATE TABLE pms_product;
SHOW INDEX FROM pms_product;
SHOW INDEX FROM pms_sku_stock;
SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 'pms_%'
ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;
```

### 7.2 EXPLAIN 案例

在目标 schema 将具体参数替换为代表性取值。下列为验证模板，**不代表已经得到任何计划**；实际投影应换为捕获 SQL 的完整列。

```sql
-- Q1：先无关键词、再货号/品牌/分类组合，各自检查数据查询与 COUNT
EXPLAIN FORMAT=JSON
SELECT id,name,price FROM pms_product
WHERE delete_status=0 AND brand_id=1 AND product_category_id=19 LIMIT 0,20;
EXPLAIN FORMAT=JSON
SELECT COUNT(*) FROM pms_product
WHERE delete_status=0 AND brand_id=1 AND product_category_id=19;
-- Q4：包含搜索与价格排序；还需分别验证 sale DESC、id DESC 和无 keyword
EXPLAIN FORMAT=JSON
SELECT id,name,price FROM pms_product
WHERE delete_status=0 AND publish_status=1 AND name LIKE '%手机%'
ORDER BY price ASC LIMIT 0,20;
-- Q5/Q10：子表查询
EXPLAIN FORMAT=JSON SELECT * FROM pms_sku_stock WHERE product_id=26;
EXPLAIN FORMAT=JSON SELECT * FROM pms_product_attribute_value
WHERE product_id=26 AND product_attribute_id IN (1,2);
-- Q7：推荐连接及排序
EXPLAIN FORMAT=JSON
SELECT p.* FROM sms_home_new_product hp LEFT JOIN pms_product p ON hp.product_id=p.id
WHERE hp.recommend_status=1 AND p.publish_status=1
ORDER BY hp.sort DESC LIMIT 0,4;
```

另外必须展开 A4 的完整 SELECT 和全部 JOIN 对 id=26 或实际高 SKU 商品做 EXPLAIN；P3 对 1/10/100 个商品 ID；P4 对三分支完整 UNION；S1 对 id 非空/空的两种形态；C1 自连接、C5 DISTINCT 品牌关联分别检查。不能只验证主表 id 查询就推断详情整体很快。

普通 EXPLAIN 关注 access_type/type、possible_keys/key、估算 rows/filtered、连接顺序、filesort、临时表及成本。EXPLAIN ANALYZE 会真实执行 SELECT；虽不写数据也可能造成负载，应在隔离或受控环境有超时预算下执行：

```sql
EXPLAIN ANALYZE SELECT * FROM pms_sku_stock WHERE product_id=26;
```

对实际完整 SQL 同样采样 actual time、rows、loops、排序输出；对比估算/实际差距。不将 filesort 等同磁盘排序，不将 ALL 等同必需优化，小表扫描可能合理。本机 MySQL 8.0.46 支持后续此项验证，其他环境先核对版本。

### 7.3 负载、数据与基准矩阵

- 数据：商品精确数量、有效/上架比例、品牌/分类分布与倾斜、关键词命中率、每商品 SKU/属性/会员价/阶梯/满减的 p50/p95/max、文本长度、券数量及有效时间分布。
- 请求：各接口 QPS、p50/p95/p99、慢请求样本、热商品比例、页码/页大小分布、组合筛选使用比例、峰值并发。
- 数据库：只读获取已有慢日志或 performance_schema digest 的次数、总耗时、rows_examined/rows_sent、排序/临时表统计；不得在本阶段改全局日志设置。
- 分页：pageSize=20/100，offset=0/1000/10000/100000（目标规模允许时），分开记录 count 与数据查询。明确 pageNum=0、超大 pageSize 的真实 SQL 与响应。
- JOIN：低/中/高子表基数商品，比较数据库原始行数、MyBatis 最终集合大小、传输字节、JVM 映射耗时。
- 冷/热缓存分开采样，多轮固定参数；记录环境、数据量、连接池、并发、吞吐、错误率及资源使用。不为获得冷缓存而在共享环境重启数据库。
- ES：用 ES profile/慢查询信息单独分析，记录导入时 MySQL 时间、ES 写入时间和 JVM 峰值；不要将 ES 搜索延迟归因于 MySQL 索引。

## 8. 优化候选排序（不构成变更授权）

|顺序|候选|预期影响与前提|证据/进入实施前门槛|
|---|---|---|---|
|1|详情子表 product_id、属性分类等连接访问路径|详情/购物车高频且子表增长时可能高影响|E1+E2；先获取完整计划和基数，再评估索引列序，不能直接给所有字段建索引|
|2|后台编辑、促销查询的一对多聚合方式|高 SKU/属性商品可能显著减少中间行和文本传输|E1；先测行数乘积、往返代价和结果一致性，比较拆分批量查询而非机械合并|
|3|列表过滤+排序索引与稳定分页策略|商品大表/高 QPS 下可能高影响；当前几十行不能证明收益|E1+E2；先确定热门条件和选择性，再评估如状态+品牌或分类+排序的不同候选，避免一条超宽万能索引|
|4|无分页 simpleList、宽投影、ES 全量导入边界|大数据量时响应体/内存风险明显|E1；明确前端数据契约及导入一致性后再设计限额、精简列或分批导入|
|5|包含搜索的检索路径|高频宽匹配可能高影响|E1；确认搜索语义、中文分词和数据同步，再比较现有 ES 与 SQL，不能直接把 LIKE 改成前缀匹配|
|6|COUNT、深分页和稳定排序|大量翻页/统计成本占比高时收益明显|E1；确定总数与任意跳页是否必要，未来才考虑游标/延迟关联/减少 count|
|7|优惠券 UNION/时间与关系索引|券多且详情热点时中等至高影响|E1+E2；先获取计划及重复语义，评估而不直接更换 UNION ALL|
|8|品牌/分类/属性缓存或树构建优化|读多变更少且查询占比高时可能受益|E1；确认一致性、失效机制和命中率；小表与低 QPS 时优先级低|

以上排序综合潜在覆盖范围与现有证据，并非测得收益排序。当前最需要补齐的是目标环境的真实数据规模、SQL 日志、执行计划和接口基线，而不是立即增加索引。

## 9. 代码证据索引

路径相对于项目根目录；行号为本次读取时位置。

- **A1**：[mall-admin/src/main/java/com/macro/mall/controller/PmsProductController.java:66](../../mall-admin/src/main/java/com/macro/mall/controller/PmsProductController.java#L66)
- **A2**：[mall-admin/src/main/java/com/macro/mall/service/impl/PmsProductServiceImpl.java:220](../../mall-admin/src/main/java/com/macro/mall/service/impl/PmsProductServiceImpl.java#L220)
- **A3**：[mall-admin/src/main/java/com/macro/mall/service/impl/PmsProductServiceImpl.java:325](../../mall-admin/src/main/java/com/macro/mall/service/impl/PmsProductServiceImpl.java#L325)
- **A4**：[mall-admin/src/main/resources/dao/PmsProductDao.xml:21](../../mall-admin/src/main/resources/dao/PmsProductDao.xml#L21)
- **P1**：[mall-portal/src/main/java/com/macro/mall/portal/service/impl/PmsPortalProductServiceImpl.java:48](../../mall-portal/src/main/java/com/macro/mall/portal/service/impl/PmsPortalProductServiceImpl.java#L48)
- **P2**：[mall-portal/src/main/java/com/macro/mall/portal/service/impl/PmsPortalProductServiceImpl.java:88](../../mall-portal/src/main/java/com/macro/mall/portal/service/impl/PmsPortalProductServiceImpl.java#L88)
- **P3**：[mall-portal/src/main/resources/dao/PortalProductDao.xml:45](../../mall-portal/src/main/resources/dao/PortalProductDao.xml#L45)
- **P4**：[mall-portal/src/main/resources/dao/PortalProductDao.xml:75](../../mall-portal/src/main/resources/dao/PortalProductDao.xml#L75)
- **P5**：[mall-portal/src/main/resources/dao/PortalProductDao.xml:20](../../mall-portal/src/main/resources/dao/PortalProductDao.xml#L20)
- **H1**：[mall-portal/src/main/resources/dao/HomeDao.xml:38](../../mall-portal/src/main/resources/dao/HomeDao.xml#L38)
- **H2**：[mall-portal/src/main/java/com/macro/mall/portal/service/impl/HomeServiceImpl.java:41](../../mall-portal/src/main/java/com/macro/mall/portal/service/impl/HomeServiceImpl.java#L41)
- **B1**：[mall-portal/src/main/java/com/macro/mall/portal/service/impl/PmsPortalBrandServiceImpl.java:49](../../mall-portal/src/main/java/com/macro/mall/portal/service/impl/PmsPortalBrandServiceImpl.java#L49)
- **S1**：[mall-search/src/main/resources/dao/EsProductDao.xml:14](../../mall-search/src/main/resources/dao/EsProductDao.xml#L14)
- **S2**：[mall-search/src/main/java/com/macro/mall/search/service/impl/EsProductServiceImpl.java:45](../../mall-search/src/main/java/com/macro/mall/search/service/impl/EsProductServiceImpl.java#L45)
- **S3**：[mall-search/src/main/java/com/macro/mall/search/service/impl/EsProductServiceImpl.java:161](../../mall-search/src/main/java/com/macro/mall/search/service/impl/EsProductServiceImpl.java#L161)
- **M1**：[mall-mbg/src/main/resources/com/macro/mall/mapper/PmsProductMapper.xml:108](../../mall-mbg/src/main/resources/com/macro/mall/mapper/PmsProductMapper.xml#L108)
- **T1**：[mall-admin/src/test/com/macro/mall/PmsDaoTests.java:24](../../mall-admin/src/test/com/macro/mall/PmsDaoTests.java#L24)
- **T2**：[mall-portal/src/test/java/com/macro/mall/portal/PortalProductDaoTests.java:19](../../mall-portal/src/test/java/com/macro/mall/portal/PortalProductDaoTests.java#L19)
- **D1**：[document/sql/mall.sql:1085](../../document/sql/mall.sql#L1085)
- **C1**：[mall-admin/src/main/resources/dao/PmsProductCategoryDao.xml:9](../../mall-admin/src/main/resources/dao/PmsProductCategoryDao.xml#L9)
- **C2**：[mall-admin/src/main/resources/dao/PmsProductAttributeCategoryDao.xml:8](../../mall-admin/src/main/resources/dao/PmsProductAttributeCategoryDao.xml#L8)
- **C3**：[mall-admin/src/main/resources/dao/PmsProductAttributeDao.xml:4](../../mall-admin/src/main/resources/dao/PmsProductAttributeDao.xml#L4)
- **C4**：[mall-admin/src/main/java/com/macro/mall/service/impl/PmsSkuStockServiceImpl.java:27](../../mall-admin/src/main/java/com/macro/mall/service/impl/PmsSkuStockServiceImpl.java#L27)
- **C5**：[mall-admin/src/main/resources/dao/PmsBrandDao.xml:4](../../mall-admin/src/main/resources/dao/PmsBrandDao.xml#L4)

后续结论统一维护在 [review.md](review.md)，本文不重复保存性能结果和实施记录。
