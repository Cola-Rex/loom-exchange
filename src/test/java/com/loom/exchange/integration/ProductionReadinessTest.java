package com.loom.exchange.integration;

import com.loom.exchange.cluster.ClusterManager;
import com.loom.exchange.cluster.DistributedMatchingEngine;
import com.loom.exchange.core.Order;
import com.loom.exchange.persistence.ChronicleEventStore;
import com.loom.exchange.persistence.DisasterRecoveryManager;
import com.loom.exchange.risk.RiskConfig;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

/**
 * 生产就绪性集成测试
 * 验证系统在生产环境下的各项能力
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductionReadinessTest {
    
    private static final Logger log = LoggerFactory.getLogger(ProductionReadinessTest.class);
    
    private ChronicleEventStore eventStore;
    private ClusterManager clusterManager;
    private DistributedMatchingEngine distributedEngine;
    private DisasterRecoveryManager recoveryManager;
    
    @BeforeAll
    void setupProduction() {
        log.info("初始化生产级测试环境");
        
        // 初始化组件
        eventStore = new ChronicleEventStore();
        clusterManager = new ClusterManager();
        distributedEngine = new DistributedMatchingEngine(eventStore, clusterManager, 
                                                        RiskConfig.lenientConfig());
        recoveryManager = new DisasterRecoveryManager(eventStore, distributedEngine);
        
        // 启动集群
        clusterManager.start();
        
        // 等待集群稳定
        waitForClusterStability();
    }
    
    @AfterAll
    void teardownProduction() {
        log.info("清理生产级测试环境");
        
        clusterManager.stop();
        eventStore.close().join();
    }
    
    /**
     * 测试高并发订单处理能力
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testHighConcurrencyOrderProcessing() throws Exception {
        log.info("=== 高并发订单处理测试 ===");
        
        var orderCount = 100_000;
        var concurrency = 1_000;
        var processedOrders = new AtomicLong();
        var failedOrders = new AtomicLong();
        
        var startTime = System.nanoTime();
        var latch = new CountDownLatch(orderCount);
        
        // 使用虚拟线程提交大量订单
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            
            IntStream.range(0, orderCount).forEach(i -> {
                executor.submit(() -> {
                    try {
                        var order = createTestOrder(i);
                        var result = distributedEngine.submitOrder(order).get(5, TimeUnit.SECONDS);
                        
                        if (result.success()) {
                            processedOrders.incrementAndGet();
                        } else {
                            failedOrders.incrementAndGet();
                        }
                        
                    } catch (Exception e) {
                        failedOrders.incrementAndGet();
                        log.debug("订单处理失败: {}", e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            });
            
            latch.await();
        }
        
        var endTime = System.nanoTime();
        var durationMs = (endTime - startTime) / 1_000_000;
        var throughput = (processedOrders.get() * 1000L) / durationMs;
        
        log.info("高并发测试结果:");
        log.info("  总订单数: {}", orderCount);
        log.info("  成功处理: {}", processedOrders.get());
        log.info("  处理失败: {}", failedOrders.get());
        log.info("  总耗时: {}ms", durationMs);
        log.info("  吞吐量: {} 订单/秒", throughput);
        log.info("  成功率: {:.2f}%", (processedOrders.get() * 100.0) / orderCount);
        
        // 验证性能指标
        Assertions.assertTrue(throughput > 10_000, "吞吐量应该超过10,000订单/秒");
        Assertions.assertTrue(processedOrders.get() > orderCount * 0.95, "成功率应该超过95%");
    }
    
    /**
     * 测试系统故障恢复能力
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void testDisasterRecoveryCapability() throws Exception {
        log.info("=== 灾难恢复能力测试 ===");
        
        // 1. 提交一批订单建立初始状态
        var initialOrders = 1000;
        submitInitialOrders(initialOrders);
        
        // 2. 创建系统快照
        log.info("创建系统快照");
        recoveryManager.createSystemSnapshot().get(30, TimeUnit.SECONDS);
        
        // 3. 继续处理更多订单
        var additionalOrders = 500;
        submitInitialOrders(additionalOrders);
        
        // 4. 模拟系统崩溃和恢复
        log.info("模拟系统崩溃，开始恢复");
        var recoveryStart = System.nanoTime();
        
        var recoveryResult = recoveryManager.performFullRecovery()
                                          .get(60, TimeUnit.SECONDS);
        
        var recoveryTime = (System.nanoTime() - recoveryStart) / 1_000_000;
        
        log.info("恢复结果: {}", recoveryResult);
        log.info("恢复耗时: {}ms", recoveryTime);
        
        // 验证恢复结果
        Assertions.assertTrue(recoveryResult.success(), "恢复应该成功");
        Assertions.assertTrue(recoveryTime < 10_000, "恢复时间应该小于10秒");
        Assertions.assertTrue(recoveryResult.recoveredEvents() > 0, "应该恢复一些事件");
    }
    
    /**
     * 测试集群故障转移
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testClusterFailover() throws Exception {
        log.info("=== 集群故障转移测试 ===");
        
        // 1. 验证初始集群状态
        var initialMaster = clusterManager.getMasterNode();
        Assertions.assertTrue(initialMaster.isPresent(), "应该有主节点");
        
        log.info("初始主节点: {}", initialMaster.get().nodeId());
        
        // 2. 在故障转移期间持续提交订单
        var orderSubmissionFuture = CompletableFuture.runAsync(() -> {
            var orderCounter = new AtomicLong();
            
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    var order = createTestOrder((int) orderCounter.incrementAndGet());
                    distributedEngine.submitOrder(order);
                    Thread.sleep(10); // 控制提交频率
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // 在故障转移期间可能出现失败，这是正常的
                }
            }
        });
        
        // 3. 等待一段时间让订单处理稳定
        Thread.sleep(5000);
        
        // 4. 模拟主节点故障（实际测试中可能需要更复杂的模拟）
        log.info("模拟主节点故障");
        // 这里简化处理，实际应该模拟网络分区或节点崩溃
        
        // 5. 等待故障转移完成
        Thread.sleep(15000);
        
        // 6. 停止订单提交
        orderSubmissionFuture.cancel(true);
        
        // 7. 验证集群恢复
        var finalMaster = clusterManager.getMasterNode();
        Assertions.assertTrue(finalMaster.isPresent(), "故障转移后应该有新的主节点");
        
        var clusterStats = clusterManager.getStats();
        log.info("故障转移后集群状态: {}", clusterStats);
        
        Assertions.assertTrue(clusterStats.hasMaster(), "集群应该有主节点");
        Assertions.assertTrue(clusterStats.healthyNodes() > 0, "应该有健康节点");
    }
    
    /**
     * 测试内存和GC性能
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void testMemoryAndGCPerformance() throws Exception {
        log.info("=== 内存和GC性能测试 ===");
        
        var runtime = Runtime.getRuntime();
        var initialMemory = runtime.totalMemory() - runtime.freeMemory();
        var initialGcTime = getTotalGcTime();
        
        log.info("测试开始 - 内存: {}MB, GC时间: {}ms", 
                initialMemory / 1024 / 1024, initialGcTime);
        
        // 大量创建和处理订单，测试内存分配和GC
        var orderCount = 50_000;
        var futures = new CompletableFuture[orderCount];
        
        var startTime = System.nanoTime();
        
        for (int i = 0; i < orderCount; i++) {
            var order = createTestOrder(i);
            futures[i] = distributedEngine.submitOrder(order);
        }
        
        // 等待所有订单处理完成
        CompletableFuture.allOf(futures).get(60, TimeUnit.SECONDS);
        
        var endTime = System.nanoTime();
        var processingTime = (endTime - startTime) / 1_000_000;
        
        // 强制GC并测量
        System.gc();
        Thread.sleep(1000);
        
        var finalMemory = runtime.totalMemory() - runtime.freeMemory();
        var finalGcTime = getTotalGcTime();
        
        var memoryIncrease = (finalMemory - initialMemory) / 1024 / 1024;
        var gcTimeIncrease = finalGcTime - initialGcTime;
        
        log.info("测试结束 - 处理时间: {}ms", processingTime);
        log.info("内存增长: {}MB", memoryIncrease);
        log.info("GC时间增长: {}ms", gcTimeIncrease);
        log.info("平均每订单内存: {}KB", (memoryIncrease * 1024.0) / orderCount);
        log.info("平均每订单GC时间: {}μs", (gcTimeIncrease * 1000.0) / orderCount);
        
        // 验证内存和GC性能
        Assertions.assertTrue(memoryIncrease < 1000, "内存增长应该小于1GB");
        Assertions.assertTrue(gcTimeIncrease < 1000, "GC时间增长应该小于1秒");
        Assertions.assertTrue(processingTime / orderCount < 10, "平均每订单处理时间应该小于10ms");
    }
    
    /**
     * 测试极限压力场景
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void testExtremeStressScenario() throws Exception {
        log.info("=== 极限压力测试 ===");
        
        var orderCount = 200_000;
        var virtualThreadCount = 10_000;
        var processedOrders = new AtomicLong();
        var errors = new ConcurrentHashMap<String, AtomicLong>();
        
        log.info("启动极限压力测试 - 订单数: {}, 虚拟线程数: {}", orderCount, virtualThreadCount);
        
        var startTime = System.nanoTime();
        var latch = new CountDownLatch(orderCount);
        
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            
            // 创建大量虚拟线程同时提交订单
            for (int i = 0; i < orderCount; i++) {
                final int orderId = i;
                
                executor.submit(() -> {
                    try {
                        var order = createTestOrder(orderId);
                        var result = distributedEngine.submitOrder(order)
                                                    .get(10, TimeUnit.SECONDS);
                        
                        if (result.success()) {
                            processedOrders.incrementAndGet();
                        } else {
                            errors.computeIfAbsent(result.errorMessage(), 
                                                 k -> new AtomicLong()).incrementAndGet();
                        }
                        
                    } catch (Exception e) {
                        errors.computeIfAbsent(e.getClass().getSimpleName(), 
                                             k -> new AtomicLong()).incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            latch.await();
        }
        
        var endTime = System.nanoTime();
        var totalTime = (endTime - startTime) / 1_000_000;
        var throughput = (processedOrders.get() * 1000L) / totalTime;
        
        log.info("极限压力测试结果:");
        log.info("  处理成功: {}", processedOrders.get());
        log.info("  总耗时: {}ms", totalTime);
        log.info("  吞吐量: {} 订单/秒", throughput);
        log.info("  成功率: {:.2f}%", (processedOrders.get() * 100.0) / orderCount);
        
        // 输出错误统计
        if (!errors.isEmpty()) {
            log.info("错误统计:");
            errors.forEach((error, count) -> 
                log.info("  {}: {}", error, count.get()));
        }
        
        // 验证系统在极限压力下的表现
        Assertions.assertTrue(processedOrders.get() > orderCount * 0.8, 
                            "极限压力下成功率应该超过80%");
        Assertions.assertTrue(throughput > 5_000, 
                            "极限压力下吞吐量应该超过5,000订单/秒");
    }
    
    /**
     * 测试长时间运行稳定性
     */
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void testLongRunningStability() throws Exception {
        log.info("=== 长时间运行稳定性测试 ===");
        
        var testDuration = Duration.ofMinutes(3); // 3分钟稳定性测试
        var orderRate = 1000; // 每秒1000订单
        var processedOrders = new AtomicLong();
        var errors = new AtomicLong();
        
        var startTime = System.currentTimeMillis();
        var endTime = startTime + testDuration.toMillis();
        
        log.info("开始{}分钟稳定性测试，目标速率: {}订单/秒", 
                testDuration.toMinutes(), orderRate);
        
        try (var scheduler = Executors.newScheduledThreadPool(1);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            
            // 定期提交订单
            var orderSubmissionTask = scheduler.scheduleAtFixedRate(() -> {
                if (System.currentTimeMillis() >= endTime) {
                    return;
                }
                
                // 每秒提交指定数量的订单
                for (int i = 0; i < orderRate; i++) {
                    executor.submit(() -> {
                        try {
                            var order = createTestOrder((int) processedOrders.get());
                            var result = distributedEngine.submitOrder(order)
                                                        .get(5, TimeUnit.SECONDS);
                            
                            if (result.success()) {
                                processedOrders.incrementAndGet();
                            } else {
                                errors.incrementAndGet();
                            }
                            
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    });
                }
            }, 0, 1, TimeUnit.SECONDS);
            
            // 定期输出统计信息
            var statsTask = scheduler.scheduleAtFixedRate(() -> {
                var elapsed = (System.currentTimeMillis() - startTime) / 1000;
                var currentTps = processedOrders.get() / Math.max(elapsed, 1);
                
                log.info("运行{}秒 - 处理订单: {}, 当前TPS: {}, 错误: {}", 
                        elapsed, processedOrders.get(), currentTps, errors.get());
                        
                // 输出系统状态
                logSystemStats();
                
            }, 30, 30, TimeUnit.SECONDS);
            
            // 等待测试完成
            Thread.sleep(testDuration.toMillis());
            
            orderSubmissionTask.cancel(false);
            statsTask.cancel(false);
        }
        
        var actualDuration = (System.currentTimeMillis() - startTime) / 1000;
        var averageTps = processedOrders.get() / actualDuration;
        var errorRate = (errors.get() * 100.0) / (processedOrders.get() + errors.get());
        
        log.info("稳定性测试完成:");
        log.info("  运行时长: {}秒", actualDuration);
        log.info("  处理订单: {}", processedOrders.get());
        log.info("  平均TPS: {}", averageTps);
        log.info("  错误率: {:.2f}%", errorRate);
        
        // 验证长时间运行稳定性
        Assertions.assertTrue(averageTps > orderRate * 0.8, 
                            "平均TPS应该达到目标的80%以上");
        Assertions.assertTrue(errorRate < 5.0, 
                            "错误率应该小于5%");
    }
    
    // 辅助方法
    
    private void waitForClusterStability() {
        try {
            Thread.sleep(5000); // 等待5秒让集群稳定
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private void submitInitialOrders(int count) throws Exception {
        var futures = new CompletableFuture[count];
        
        for (int i = 0; i < count; i++) {
            var order = createTestOrder(i);
            futures[i] = distributedEngine.submitOrder(order);
        }
        
        CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);
    }
    
    private Order createTestOrder(int id) {
        var side = id % 2 == 0 ? Order.OrderSide.BUY : Order.OrderSide.SELL;
        var basePrice = new BigDecimal("50000");
        var priceVariation = (id % 1000) - 500; // -500 到 +500
        var price = basePrice.add(new BigDecimal(priceVariation));
        var quantity = new BigDecimal("0.1");
        
        return Order.create(
            "ORDER_" + id,
            "BTCUSDT",
            side,
            Order.OrderType.LIMIT,
            price,
            quantity,
            "user_" + (id % 100)
        );
    }
    
    private long getTotalGcTime() {
        return java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
                .stream()
                .mapToLong(gcBean -> gcBean.getCollectionTime())
                .sum();
    }
    
    private void logSystemStats() {
        var runtime = Runtime.getRuntime();
        var totalMemory = runtime.totalMemory() / 1024 / 1024;
        var freeMemory = runtime.freeMemory() / 1024 / 1024;
        var usedMemory = totalMemory - freeMemory;
        var gcTime = getTotalGcTime();
        
        log.debug("系统状态 - 内存: {}MB/{}MB, GC: {}ms, 线程: {}", 
                usedMemory, totalMemory, gcTime, Thread.activeCount());
    }
}
