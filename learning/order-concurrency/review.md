# Mall 订单并发安全与事务边界审查

日期：2026-11-09。对象：真实源码 `/Users/liuhongyu/IdeaProjects/mallProject/`。未提交 Git，未修改部署快照、正式库或正式 Redis。开始时 DAO、Service、XML 三个文件已有上一轮未提交修复，本次保留并补充。

## 1. 原有调用链与风险

- `POST /order/generateOrder` → `OmsPortalOrderController.generateOrder` → Spring 代理 `OmsPortalOrderService.generateOrder` → `OmsPortalOrderServiceImpl.generateOrder`。
- 接口 `OmsPortalOrderService.java:25` 的 `@Transactional` 使用默认 `REQUIRED`、默认数据库隔离级别、运行时异常回滚。Controller 无事务；事务覆盖购物车读取、可售校验、促销计算、库存预占、订单/明细插入、优惠券/积分更新、购物车逻辑删除。Redis 订单序号及 RabbitMQ 消息不属于 MySQL 本地事务。
- 创建订单只增加 `lock_stock`，不扣 `stock`；可购买量为 `stock - lock_stock`。支付成功同时扣实际库存和预占，取消只释放预占。支付失败没有独立释放入口：仍待付款，后续取消释放；支付 SQL 异常应整体回滚。
- 当前下单链路没有 `synchronized`、Redisson、Redis 锁，也没有手工 `unlock`。Redis 仅参与订单号原子递增等功能。因此不存在用户示例中的分布式锁提前释放问题，Watchdog、租期、分布式锁获取失败均不适用，无需引入 Redisson 或拆分新的锁门面 Service。
- 上一轮已有 `reserveSkuStock` 条件原子更新：`lock_stock=COALESCE(lock_stock,0)+quantity WHERE stock-COALESCE(lock_stock,0)>=quantity AND quantity>0`。影响行数不为 1 抛运行时业务异常。该写操作持有 SKU 行锁直到事务结束，不能仅以早先的 `hasStock` 快照查询判断库存。
- 上一轮已有支付/取消 `status=0` 条件迁移：只有成功迁移者扣减或释放库存。定时取消、RabbitMQ 消费取消及用户取消共享这一约束。

| 风险 | 源码位置及触发条件 | 结论 |
| --- | --- | --- |
| 重复下单 | `generateOrder:100` 原购物车普通查询；同一批有效购物车行被两个事务同时读到，库存足够时分别创建订单，后续逻辑删除不阻止重复 | 已复现并修复。去掉新购物车行锁后，16 并发实际成功 16 次，订单 16 张，库存 100/预占 16 |
| 快照校验不保证库存 | `hasStock`、`lockStock:768`；其他事务在查询后预占 | 保留原子条件 UPDATE；16 用户争最后 1 件只有 1 单成功 |
| 同 SKU 多条明细少扣/少释放 | `PortalOrderDao.xml` 的 `CASE id WHEN skuId` 只命中一条数量；例如同 SKU 数量 2、3 预占 5，只按第一条处理 | 本次按 SKU 合并为 5 后调用支付/取消 SQL |
| 多 SKU 锁顺序 | `lockStock` 原按购物车顺序更新；两个订单顺序相反 | 按 SKU 主键排序预占，降低创建间死锁风险；不承诺消除所有跨业务死锁 |
| 事务内消息及跨资源一致性 | `generateOrder` 末尾发送 RabbitMQ 消息、`generateOrderSn` Redis INCR | 消息可能早于提交，Redis 序号回滚后有空洞；本次未扩大为 Outbox 改造 |

`paySuccessByOrderSn:461` 内部直接调用 `paySuccess`，内部调用不会另开事务，但外部入口本身有 `@Transactional`，正常代理调用下已有外层事务。因此不能仅看到内部调用就认定事务失效。

## 2. 最小修改

1. **`PortalOrderDao.java` / `PortalOrderDao.xml:4`**：新增 `lockCheckoutCartItems`，按有效购物车主键及当前会员查询，`ORDER BY id FOR UPDATE`。强制主键访问；空集合使用 `AND 1=0`，避免锁定扫描全表。数据库锁在物理事务提交/回滚后自动释放，没有业务方法 `finally` 提前解锁窗口。
2. **`OmsPortalOrderServiceImpl.generateOrder:100`**：用锁定当前读替换普通购物车读取，保留会员归属、选中行完整性及商品可售校验。同一批购物车行只可消费一次：后到事务等待，前者提交后看到已逻辑删除的行，明确失败；前者回滚后可正常创建。未传/空 `cartIds` 保持“结算当前购物车”语义：先发现候选主键，再锁定这些主键，避免无主键条件扫描。
3. **`lockStock:768`**：复制列表并按 SKU 主键排序后执行已有原子 UPDATE，不改变订单明细展示顺序。
4. **`aggregateStockItems:782`**：按 SKU 汇总明细数量，再供 `paySuccess:281`、`cancelOrder:331`、`cancelTimeOutOrder:300` 使用。`Math.addExact` 遇到非法溢出会失败回滚，而非静默写错库存。
5. 新增隔离 JUnit 测试、空表结构、容器复现脚本及本报告；无新增依赖、无数据库迁移、无生产唯一约束变更。商品可售校验及已有 Redis afterCommit 缓存失效代码未改动。

锁粒度是本次实际消费的购物车行和实际更新的 SKU，不是用户或全局锁。同一用户不同购物车订单可以成功。预占同一 SKU 必然存在必要行锁竞争；不同 SKU 无额外全局锁。带显式 cartIds 的路径用锁定查询替换原普通查询；省略 cartIds 的兼容路径多一次候选主键发现。排序与汇总开销随订单行数增长。事务较长会延长数据库行锁持有时间，需避免给外层事务增加慢远程调用。

## 3. 隔离测试与结果

测试文件：`mall-portal/src/test/java/com/macro/mall/portal/order/OrderConcurrencyTest.java`。

独立 Docker MySQL 8.0（localhost:23306，`mall_order_tx_audit`）与 Redis 7（localhost:23379），无业务卷。只从仓库 SQL 抽取六张空表，JUnit 创建虚构会员/商品/购物车数据；没有读取正式库。真实 Controller → Spring 事务代理 → 实现类 → MyBatis → MySQL，订单号使用真实隔离 Redis。测试替身仅用于身份、地址、促销计算及消息发送，订单/明细/购物车/库存 SQL 与可售校验使用真实实现。消息替身用于注入事务提交前暂停及异常。

最终修复版：**17 tests, 0 failures, 0 errors, 0 skipped；Java 17 Maven BUILD SUCCESS**。状态均由独立连接读取提交结果，并有断言；提交暂停测试由 CountDownLatch 控制而非只观察线程耗时。

| 测试方法/场景 | 数据与并发 | 预期及实际结果 |
| --- | --- | --- |
| lastUnitAcross16Users | 库存 1，16 会员各买 1 | 成功 1 单、1 明细，stock/lock_stock=1/1；15 个失败购物车仍有效 |
| sameCartSubmitted16TimesCreatesOneOrder | 库存 100，同购物车 16 并发，再顺序重放 | 仅 1 单、预占 1；其余失败，顺序重放也失败 |
| sameUserDifferentOrdersBothSucceed | 同会员两组购物车/两个 SKU，2 并发 | 2 单均成功，各 SKU 预占 1 |
| differentSkuIsNotBlockedByUncommittedOrder（2 参数） | 首单停在 beforeCommit；第二单分别为同会员/不同会员、不同 SKU | 第二单在首单未提交时 1.5 秒内提交；非吞吐量压测 |
| cartLockRemainsHeldDuringSpringCommit | 同购物车；首单停在 beforeCommit | 外部查不到首单；第二单 400ms 内无法返回，释放后首单成功、第二单失败 |
| outerRequiredTransactionKeepsLocksUntilRollback | 外层 TransactionTemplate 调用创建，方法返回后暂停，最终外层回滚 | 第二单仍被阻塞；外层回滚后第二单成功，只有 1 张提交订单 |
| failureAfterOrderItemsAndCartWriteRollsEverythingBack | 库存 100，数量 2；在消息调用处抛异常 | 订单/明细 0，stock/lock_stock=100/0，购物车有效；重试后预占 2 |
| failureOnSecondSkuRollsBackEarlierReservation | 首 SKU 已预占后，独立竞争事务令第二 SKU 库存变为 0 | 第二次原子预占影响 0 行，创建失败，首 SKU 恢复 100/0，无订单明细，购物车仍有效 |
| rowLockTimeoutDoesNotCreatePhantomOrder | 首单持锁；第二单 MySQL 行锁等待超时 2 秒 | 明确抛数据库锁异常，无假成功/第二张订单；首单最终正常提交 |
| duplicateSkuLinesPayAndCancelExactlyOnce | 同 SKU 两行数量 2+3；16 次支付、16 次取消 | 支付仅一次生效：100/5→95/0；另一订单取消：95/5→95/0；取消后支付不生效 |
| paymentAndCancelRaceHasOneConsistentWinner | 数量 2，支付和取消 2 并发 | 仅已支付或已取消终态；库存分别 98/0 或100/0 |
| duplicateSkuTotalAboveStockRollsBack | 库存 10，同 SKU 两行各 6 | 失败，无订单/明细，库存 10/0，购物车两行有效 |
| paymentFailureRollsBackStatusAndStock | 支付库存 SQL 执行后注入异常 | 状态仍待付，库存100/2；重试后支付成功98/0 |
| scheduledAndSingleCancellationShareOneStateTransition | 超时订单，定时及单笔取消2并发 | 只释放一次100/0、状态4；重复定时取消影响0 |
| reversedMultiSkuOrdersDoNotOversell | 两会员分别按1→2、2→1提交 | 2单4明细成功，各SKU预占2 |
| omittedCartIdsKeepCheckoutAllSemantics | 两会员各一条购物车，省略选中ID | 只消费当前会员购物车；重放失败，不动其他会员购物车 |

对照实验只在测试加载 XML 时去掉 `FOR UPDATE`，其余保持真实实现；重复提交断言预期失败 `expected 1 but was 16`。这证明购物车重复消费窗口，不冒充整个历史版本或 HTTP 压测。第一轮测试因虚构数据漏填 product_sn 等必填字段失败，修正测试数据后全部通过；无隐藏跳过。

分布式锁“提交前解锁”“获取失败”测试：**不适用，未标记通过**，因为本方案未使用分布式锁。以真实数据库提交前阻塞、外层事务回滚及行锁超时测试验证实际采用的机制。未运行真实 HTTP 登录、支付网关、RabbitMQ 联调及页面验收；未启动读取业务配置的原有 @SpringBootTest。

### 复现

在项目根目录使用 Java 17（macOS 可先设置 `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`）：

```bash
learning/order-concurrency/run.sh          # 最终修复版17项测试
learning/order-concurrency/run.sh baseline # 重复下单对照：断言失败被识别为复现成功
```

需要 Docker、Maven 及本地 mysql:8.0、redis:7-alpine 镜像；23306/23379 必须空闲。脚本创建独有容器名、临时内存数据盘，退出自动删除自己创建的容器和匿名卷，不清理任何原有容器。JUnit 有明确环境开关，默认不执行；脚本显式 `-DskipTests=false` 覆盖根 pom 默认跳过设置。日志在 `target/order-concurrency-audit/{fixed,baseline}.log`；简要证据见 `verification.txt`。对照运行的 Surefire 报告会显示预期失败，应区分 fixed.log 和 baseline.log。

## 4. 剩余风险及范围

- 本次幂等依据是**购物车行的单次消费**：重复提交同一组行明确拒绝，不返回旧订单。现有接口没有 requestId，不实现持久化“相同请求返回同一订单”的通用幂等协议。新购物车行代表新的合法购买；不能用会员ID或相同商品内容无限期去重。省略 cartIds 后购物车发生变化也不能视为同一请求。如需跨购物车重建的请求幂等，需另定请求标识及数据库唯一约束迁移，未擅自扩展。
- RabbitMQ 发送发生在提交前；外层回滚仍可能留下消息，消息过早消费可能读不到订单。Redis INCR 不回滚而产生订单号空洞；Redis 丢失序号可能影响唯一性。没有新增跨资源一致性保证。定时补偿类当前未启用 @Component，不能宣称已有定时兜底运行。
- 优惠券占用、积分扣减/返还仍有普通读改写及更新时机问题，本套测试不带优惠券/积分。`useIntegration` 在订单插入后才赋值、单笔详情查询未取该字段等既有问题未扩大修改。不能据此宣称整个财务链路并发安全。
- 支付回调库存副作用通过订单状态CAS去重，但真实回调验签、金额核对、接口授权、失败重试响应与取消后到账处理未覆盖本次验证。
- 只约束当前 portal 订单写入链路；其他模块直接改库存、历史负库存/错误预占数据未审计或修复。多业务交叉锁顺序、死锁重试、锁等待到 HTTP 的友好错误映射、长事务持锁仍需按实际流量评估。主键缺失可能存在 InnoDB 间隙锁，不等同承诺零锁竞争。
