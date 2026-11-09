package com.macro.mall.portal.order;

import com.macro.mall.common.exception.ApiException;
import com.macro.mall.common.service.RedisService;
import com.macro.mall.mapper.*;
import com.macro.mall.model.*;
import com.macro.mall.portal.component.CancelOrderSender;
import com.macro.mall.portal.controller.OmsPortalOrderController;
import com.macro.mall.portal.dao.*;
import com.macro.mall.portal.domain.*;
import com.macro.mall.portal.service.*;
import com.macro.mall.portal.service.impl.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in integration tests: dedicated throwaway containers only; never loads application.yml. */
@EnabledIfEnvironmentVariable(named = "MALL_ORDER_TX_AUDIT", matches = "isolated-containers-only")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderConcurrencyTest {
    private final ThreadLocal<Long> member = ThreadLocal.withInitial(() -> 1L);
    private final AtomicLong ids = new AtomicLong(100);
    private final AtomicReference<LongConsumer> sendHook = new AtomicReference<>(id -> {});
    private final AtomicBoolean exhaustSecondSku = new AtomicBoolean();
    private final AtomicBoolean failPayment = new AtomicBoolean();
    private JdbcTemplate jdbc;
    private SqlSessionTemplate db;
    private DataSourceTransactionManager tx;
    private OmsPortalOrderService service;
    private OmsPortalOrderController controller;
    private LettuceConnectionFactory redisFactory;
    private StringRedisTemplate redis;
    private ExecutorService pool;

    private static void inject(Object bean, String field, Object value) {
        ReflectionTestUtils.setField(bean, field, value);
    }

    @BeforeAll void configure() throws Exception {
        // Hardcoded isolated ports/schema: cannot be redirected to the normal 13306/16379 services.
        var ds = new UnpooledDataSource("com.mysql.cj.jdbc.Driver",
                "jdbc:mysql://127.0.0.1:23306/mall_order_tx_audit?useSSL=false&allowPublicKeyRetrieval=true&sessionVariables=innodb_lock_wait_timeout=2", "root", "");
        jdbc = new JdbcTemplate(ds);
        var config = new Configuration(new Environment("order-tx-audit", new SpringManagedTransactionFactory(), ds));
        config.setCacheEnabled(false);
        for (String name : List.of("PmsProduct", "PmsSkuStock", "OmsCartItem", "OmsOrder", "OmsOrderItem", "OmsOrderSetting")) {
            parse(config, "com/macro/mall/mapper/" + name + "Mapper.xml");
        }
        parse(config, "dao/PortalOrderDao.xml");
        parse(config, "dao/PortalOrderItemDao.xml");
        db = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        tx = new DataSourceTransactionManager(ds);
        redisFactory = new LettuceConnectionFactory("127.0.0.1", 23379);
        redisFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(redisFactory);
        redis.afterPropertiesSet();
        assertEquals("PONG", redis.getConnectionFactory().getConnection().ping());
        var members = mock(UmsMemberService.class);
        when(members.getCurrentMember()).thenAnswer(call -> {
            var m = new UmsMember(); m.setId(member.get()); m.setUsername("audit" + member.get()); m.setIntegration(0); return m;
        });
        var addresses = mock(UmsMemberReceiveAddressService.class);
        when(addresses.getItem(anyLong())).thenAnswer(call -> {
            var a = new UmsMemberReceiveAddress(); a.setName("audit"); a.setPhoneNumber("13000000000"); return a;
        });
        var validator = new ProductSaleValidator();
        inject(validator, "productMapper", db.getMapper(PmsProductMapper.class));
        inject(validator, "skuStockMapper", db.getMapper(PmsSkuStockMapper.class));
        // Pricing is outside these tests. Stock reads, validation, all order/stock/cart writes are real.
        var promotion = mock(OmsPromotionService.class);
        when(promotion.calcCartPromotion(anyList())).thenAnswer(call -> {
            List<OmsCartItem> cart = call.getArgument(0);
            List<CartPromotionItem> result = new ArrayList<>();
            for (var item : cart) {
                var p = new CartPromotionItem(); BeanUtils.copyProperties(item, p);
                p.setReduceAmount(BigDecimal.ZERO); p.setIntegration(0); p.setGrowth(0);
                p.setRealStock(jdbc.queryForObject("SELECT stock-COALESCE(lock_stock,0) FROM pms_sku_stock WHERE id=?", Integer.class, item.getProductSkuId()));
                result.add(p);
            }
            return result;
        });
        var cart = new OmsCartItemServiceImpl();
        inject(cart, "cartItemMapper", db.getMapper(OmsCartItemMapper.class));
        var redisService = mock(RedisService.class);
        when(redisService.incr(anyString(), anyLong())).thenAnswer(call -> redis.opsForValue().increment(call.getArgument(0), (Long) call.getArgument(1)));
        var sender = mock(CancelOrderSender.class);
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            sendHook.get().accept(call.getArgument(0)); return null;
        }).when(sender).sendMessage(anyLong(), anyLong());
        var target = new OmsPortalOrderServiceImpl();
        inject(target, "memberService", members); inject(target, "memberReceiveAddressService", addresses);
        inject(target, "cartItemService", cart); inject(target, "promotionService", promotion);
        inject(target, "productSaleValidator", validator); inject(target, "redisService", redisService);
        inject(target, "REDIS_DATABASE", "order-tx-audit"); inject(target, "REDIS_KEY_ORDER_ID", "sequence:");
        inject(target, "cancelOrderSender", sender);
        inject(target, "orderMapper", db.getMapper(OmsOrderMapper.class));
        inject(target, "orderItemMapper", db.getMapper(OmsOrderItemMapper.class));
        inject(target, "orderSettingMapper", db.getMapper(OmsOrderSettingMapper.class));
        inject(target, "orderItemDao", db.getMapper(PortalOrderItemDao.class));
        var realOrderDao = db.getMapper(PortalOrderDao.class);
        var orderDao = mock(PortalOrderDao.class, org.mockito.AdditionalAnswers.delegatesTo(realOrderDao));
        doAnswer(call -> {
            if (exhaustSecondSku.get()) {
                // Independent committed competitor after snapshot checks and SKU 1 reservation.
                try (var connection = ds.getConnection(); var statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE pms_sku_stock SET stock=0 WHERE id=2");
                }
            }
            return realOrderDao.reserveSkuStock(2L, call.getArgument(1));
        }).when(orderDao).reserveSkuStock(eq(2L), anyInt());
        doAnswer(call -> {
            int count = realOrderDao.updateSkuStock(call.getArgument(0));
            if (failPayment.get()) throw new IllegalStateException("injected payment failure after stock update");
            return count;
        }).when(orderDao).updateSkuStock(anyList());
        inject(target, "portalOrderDao", orderDao);
        var interceptor = new TransactionInterceptor(); interceptor.setTransactionManager(tx);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(target); proxy.setInterfaces(OmsPortalOrderService.class); proxy.addAdvice(interceptor);
        service = (OmsPortalOrderService) proxy.getProxy();
        controller = new OmsPortalOrderController(); inject(controller, "portalOrderService", service);
    }

    private void parse(Configuration c, String path) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path);
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Reproduction only: remove the new locking read while keeping the real service/SQL.
            if ("true".equals(System.getenv("MALL_AUDIT_BASELINE")) && path.equals("dao/PortalOrderDao.xml")) {
                xml = xml.replace("FOR UPDATE", "");
            }
            new XMLMapperBuilder(new StringReader(xml), c, path, c.getSqlFragments()).parse();
        }
    }

    @BeforeEach void fixtures() {
        pool = Executors.newFixedThreadPool(16);
        sendHook.set(id -> {}); exhaustSecondSku.set(false); failPayment.set(false);
        for (String table : List.of("oms_order_item", "oms_order", "oms_cart_item", "pms_sku_stock", "pms_product", "oms_order_setting")) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO pms_product(id,name,product_sn,delete_status,publish_status) VALUES(1,'audit','AUDIT',0,1)");
        jdbc.update("INSERT INTO pms_sku_stock(id,product_id,sku_code,stock,lock_stock) VALUES(1,1,'AUDIT-1',100,0),(2,1,'AUDIT-2',100,0)");
        jdbc.update("INSERT INTO oms_order_setting(id,normal_order_overtime,confirm_overtime) VALUES(1,30,7)");
    }
    @AfterEach void stopThreads() throws Exception { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); member.remove(); }
    @AfterAll void close() { if (redisFactory != null) redisFactory.destroy(); }

    private long cart(long user, long sku, int qty) {
        long id = ids.incrementAndGet();
        jdbc.update("INSERT INTO oms_cart_item(id,member_id,product_id,product_sku_id,quantity,price,delete_status) VALUES(?,?,1,?,?,10,0)", id, user, sku, qty);
        return id;
    }
    private OmsOrder create(long user, Long... carts) {
        member.set(user);
        var p = new OrderParam(); p.setMemberReceiveAddressId(user); p.setCartIds(Arrays.asList(carts)); p.setPayType(0);
        var response = controller.generateOrder(p);
        assertEquals(200, response.getCode());
        return (OmsOrder) ((Map<?, ?>) response.getData()).get("order");
    }
    private int scalar(String sql) { return jdbc.queryForObject(sql, Integer.class); }
    private void state(int orders, int items, int stock, int locked) {
        assertEquals(orders, scalar("SELECT COUNT(*) FROM oms_order"));
        assertEquals(items, scalar("SELECT COUNT(*) FROM oms_order_item"));
        assertEquals(stock, scalar("SELECT stock FROM pms_sku_stock WHERE id=1"));
        assertEquals(locked, scalar("SELECT lock_stock FROM pms_sku_stock WHERE id=1"));
    }
    private List<Boolean> race(List<Callable<Boolean>> tasks) throws Exception {
        var start = new CountDownLatch(1); var ready = new CountDownLatch(tasks.size());
        List<Future<Boolean>> futures = new ArrayList<>();
        for (var action : tasks) futures.add(pool.submit(() -> {ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); return action.call();}));
        assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
        List<Boolean> results = new ArrayList<>();
        for (var f : futures) results.add(f.get(20, TimeUnit.SECONDS));
        return results;
    }
    private boolean attempt(long user, long cart) {
        try { create(user, cart); return true; } catch (ApiException expected) { return false; }
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    private void holdBeforeCommit(CountDownLatch reached, CountDownLatch release) {
        sendHook.set(id -> TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) { reached.countDown(); await(release); }
        }));
    }

    @Test void lastUnitAcross16Users() throws Exception {
        jdbc.update("UPDATE pms_sku_stock SET stock=1 WHERE id=1");
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (long u=1; u<=16; u++) { long user=u, c=cart(u,1,1); tasks.add(() -> attempt(user,c)); }
        assertEquals(1, Collections.frequency(race(tasks), true)); state(1,1,1,1);
        assertEquals(15, scalar("SELECT COUNT(*) FROM oms_cart_item WHERE delete_status=0"));
    }
    @Test void sameCartSubmitted16TimesCreatesOneOrder() throws Exception {
        long c=cart(1,1,1);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for(int i=0;i<16;i++) tasks.add(() -> attempt(1,c));
        assertEquals(1, Collections.frequency(race(tasks), true)); state(1,1,100,1);
        assertFalse(attempt(1,c)); state(1,1,100,1);
    }
    @Test void sameUserDifferentOrdersBothSucceed() throws Exception {
        long a=cart(1,1,1), b=cart(1,2,1);
        assertEquals(List.of(true,true),race(List.of(() -> attempt(1,a), () -> attempt(1,b))));
        state(2,2,100,1); assertEquals(1,scalar("SELECT lock_stock FROM pms_sku_stock WHERE id=2"));
    }
    @ParameterizedTest
    @ValueSource(longs = {1, 2})
    void differentSkuIsNotBlockedByUncommittedOrder(long otherUser) throws Exception {
        long a=cart(1,1,1), b=cart(otherUser,2,1);
        var reached=new CountDownLatch(1); var release=new CountDownLatch(1);
        holdBeforeCommit(reached,release);
        var first=pool.submit(() -> create(1,a));
        try {
            await(reached); sendHook.set(id -> {});
            var second=pool.submit(() -> create(otherUser,b)); second.get(1500,TimeUnit.MILLISECONDS);
            assertEquals(1,scalar("SELECT COUNT(*) FROM oms_order"));
        } finally { release.countDown(); }
        first.get(10,TimeUnit.SECONDS); state(2,2,100,1);
    }
    @Test void cartLockRemainsHeldDuringSpringCommit() throws Exception {
        long c=cart(1,1,1);
        var reached=new CountDownLatch(1); var release=new CountDownLatch(1);
        holdBeforeCommit(reached,release);
        var first=pool.submit(() -> create(1,c));
        Future<Boolean> second=null;
        try {
            await(reached); assertEquals(0,scalar("SELECT COUNT(*) FROM oms_order"));
            second=pool.submit(() -> attempt(1,c));
            var waiting=second;
            assertThrows(TimeoutException.class, () -> waiting.get(400,TimeUnit.MILLISECONDS));
        } finally { release.countDown(); }
        first.get(10,TimeUnit.SECONDS); assertFalse(second.get(10,TimeUnit.SECONDS)); state(1,1,100,1);
    }
    @Test void outerRequiredTransactionKeepsLocksUntilRollback() throws Exception {
        long c=cart(1,1,1); var reached=new CountDownLatch(1); var release=new CountDownLatch(1);
        var first=pool.submit(() -> new TransactionTemplate(tx).execute(status -> {
            create(1,c); reached.countDown(); await(release); status.setRollbackOnly(); return null;
        }));
        Future<Boolean> second=null;
        try {
            await(reached); assertEquals(0,scalar("SELECT COUNT(*) FROM oms_order"));
            second=pool.submit(() -> attempt(1,c)); var waiting=second;
            assertThrows(TimeoutException.class, () -> waiting.get(400,TimeUnit.MILLISECONDS));
        } finally { release.countDown(); }
        first.get(10,TimeUnit.SECONDS); assertTrue(second.get(10,TimeUnit.SECONDS)); state(1,1,100,1);
    }
    @Test void failureAfterOrderItemsAndCartWriteRollsEverythingBack() {
        long c=cart(1,1,2);
        sendHook.set(id -> { assertEquals(1,scalar("SELECT COUNT(*) FROM oms_order")); assertEquals(1,scalar("SELECT COUNT(*) FROM oms_order_item")); throw new IllegalStateException("injected failure after writes"); });
        assertThrows(IllegalStateException.class, () -> create(1,c)); state(0,0,100,0);
        assertEquals(0,scalar("SELECT delete_status FROM oms_cart_item WHERE id="+c));
        sendHook.set(id -> {}); create(1,c); state(1,1,100,2);
    }
    @Test void failureOnSecondSkuRollsBackEarlierReservation() {
        long a=cart(1,1,1), b=cart(1,2,1);
        exhaustSecondSku.set(true);
        assertThrows(ApiException.class, () -> create(1,a,b)); state(0,0,100,0);
        assertEquals(2,scalar("SELECT COUNT(*) FROM oms_cart_item WHERE delete_status=0"));
    }
    @Test void rowLockTimeoutDoesNotCreatePhantomOrder() throws Exception {
        long c=cart(1,1,1); var reached=new CountDownLatch(1); var release=new CountDownLatch(1);
        holdBeforeCommit(reached,release); var first=pool.submit(() -> create(1,c));
        try {
            await(reached); var second=pool.submit(() -> create(1,c));
            var error=assertThrows(ExecutionException.class, () -> second.get(5,TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof org.springframework.dao.PessimisticLockingFailureException, error.toString());
            assertEquals(0,scalar("SELECT COUNT(*) FROM oms_order"));
        } finally { release.countDown(); }
        first.get(10,TimeUnit.SECONDS); state(1,1,100,1);
    }
    @Test void duplicateSkuLinesPayAndCancelExactlyOnce() throws Exception {
        var paid=create(1,cart(1,1,2),cart(1,1,3)); state(1,2,100,5);
        List<Callable<Boolean>> payments=new ArrayList<>();
        for(int i=0;i<16;i++) payments.add(() -> service.paySuccess(paid.getId(),1)>0);
        assertEquals(1,Collections.frequency(race(payments),true)); state(1,2,95,0);
        var canceled=create(2,cart(2,1,2),cart(2,1,3)); state(2,4,95,5);
        List<Callable<Boolean>> cancels=new ArrayList<>();
        for(int i=0;i<16;i++) cancels.add(() -> { service.cancelOrder(canceled.getId()); return true; });
        race(cancels); state(2,4,95,0);
        assertEquals(0,service.paySuccess(canceled.getId(),1)); state(2,4,95,0);
    }
    @Test void paymentAndCancelRaceHasOneConsistentWinner() throws Exception {
        var order=create(1,cart(1,1,2));
        race(List.of(() -> {service.cancelOrder(order.getId());return true;}, () -> {service.paySuccess(order.getId(),1);return true;}));
        int status=scalar("SELECT status FROM oms_order WHERE id="+order.getId());
        assertTrue(status==1 || status==4); state(1,1,status==1?98:100,0);
    }
    @Test void duplicateSkuTotalAboveStockRollsBack() {
        jdbc.update("UPDATE pms_sku_stock SET stock=10 WHERE id=1");
        long a=cart(1,1,6), b=cart(1,1,6);
        assertThrows(ApiException.class, () -> create(1,a,b)); state(0,0,10,0);
        assertEquals(2,scalar("SELECT COUNT(*) FROM oms_cart_item WHERE delete_status=0"));
    }
    @Test void paymentFailureRollsBackStatusAndStock() {
        var order=create(1,cart(1,1,2)); failPayment.set(true);
        assertThrows(IllegalStateException.class, () -> service.paySuccess(order.getId(),1));
        state(1,1,100,2); assertEquals(0,scalar("SELECT status FROM oms_order WHERE id="+order.getId()));
        failPayment.set(false); assertEquals(1,service.paySuccess(order.getId(),1)); state(1,1,98,0);
    }
    @Test void scheduledAndSingleCancellationShareOneStateTransition() throws Exception {
        var order=create(1,cart(1,1,2));
        jdbc.update("UPDATE oms_order SET create_time=DATE_SUB(NOW(),INTERVAL 1 DAY) WHERE id=?",order.getId());
        race(List.of(() -> {service.cancelTimeOutOrder();return true;}, () -> {service.cancelOrder(order.getId());return true;}));
        state(1,1,100,0); assertEquals(4,scalar("SELECT status FROM oms_order WHERE id="+order.getId()));
        assertEquals(0,service.cancelTimeOutOrder());
    }
    @Test void omittedCartIdsKeepCheckoutAllSemantics() {
        cart(1,1,1); cart(2,2,1);
        create(1); state(1,1,100,1);
        assertEquals(1,scalar("SELECT COUNT(*) FROM oms_cart_item WHERE member_id=2 AND delete_status=0"));
        assertThrows(ApiException.class, () -> create(1)); state(1,1,100,1);
    }
    @Test void reversedMultiSkuOrdersDoNotOversell() throws Exception {
        var a=cart(1,1,1); var b=cart(1,2,1); var c=cart(2,2,1); var d=cart(2,1,1);
        assertEquals(List.of(true,true),race(List.of(() -> {create(1,a,b);return true;}, () -> {create(2,c,d);return true;})));
        state(2,4,100,2); assertEquals(2,scalar("SELECT lock_stock FROM pms_sku_stock WHERE id=2"));
    }
}
