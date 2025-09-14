package com.loom.exchange.demo;

import com.loom.exchange.core.Order;
import com.loom.exchange.engine.MatchingEngine;
import com.loom.exchange.risk.RiskConfig;

import java.math.BigDecimal;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JDK 21特性演示
 * 展示虚拟线程和ZGC分代在高频交易场景中的应用
 */
public class JDK21FeaturesDemo {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== JDK 21高频交易特性演示 ===");
        System.out.println("JDK版本: " + System.getProperty("java.version"));
        System.out.println("可用处理器: " + Runtime.getRuntime().availableProcessors());
        System.out.println("最大内存: " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + "MB");
        System.out.println();
        
        // 演示1: 虚拟线程大规模并发
        demonstrateVirtualThreads();
        
        // 演示2: 高频撮合性能
        demonstrateHighFrequencyMatching();
        
        // 演示3: 内存和GC性能
        demonstrateMemoryPerformance();
        
        System.out.println("演示完成！");
    }
    
    /**
     * 演示虚拟线程的大规模并发能力
     */
    private static void demonstrateVirtualThreads() throws Exception {
        System.out.println("=== 虚拟线程大规模并发演示 ===");
        
        int virtualThreadCount = 100_000;
        var latch = new CountDownLatch(virtualThreadCount);
        var completedTasks = new AtomicLong();
        
        System.out.println("创建 " + virtualThreadCount + " 个虚拟线程...");
        
        long startTime = System.currentTimeMillis();
        
        // 使用JDK 21虚拟线程执行器
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            
            for (int i = 0; i < virtualThreadCount; i++) {
                final int taskId = i;
                
                executor.submit(() -> {
                    try {
                        // 模拟订单处理工作
                        Thread.sleep(10); // 模拟IO等待
                        
                        // 模拟一些计算工作
                        var result = Math.sin(taskId) * Math.cos(taskId);
                        
                        completedTasks.incrementAndGet();
                        
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            // 等待所有任务完成
            latch.await();
        }
        
        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;
        
        System.out.println("完成任务数: " + completedTasks.get());
        System.out.println("总耗时: " + totalTime + "ms");
        System.out.println("平均每个任务: " + (totalTime / (double) virtualThreadCount) + "ms");
        System.out.println("任务吞吐量: " + (virtualThreadCount * 1000L / totalTime) + " 任务/秒");
        System.out.println();
    }
    
    /**
     * 演示高频撮合性能
     */
    private static void demonstrateHighFrequencyMatching() throws Exception {
        System.out.println("=== 高频撮合性能演示 ===");
        
        // 使用宽松风控配置进行演示
        var matchingEngine = new MatchingEngine(RiskConfig.lenientConfig());
        
        int orderCount = 50_000;
        var basePrice = new BigDecimal("50000");
        var orderIdGenerator = new AtomicLong(1);
        var tradeCount = new AtomicLong();
        
        // 监听交易事件
        matchingEngine.addTradeListener(trade -> tradeCount.incrementAndGet());
        
        System.out.println("提交 " + orderCount + " 个订单进行撮合...");
        
        long startTime = System.nanoTime();
        var futures = new CompletableFuture[orderCount];
        
        // 创建匹配的买卖单
        for (int i = 0; i < orderCount; i++) {
            if (i % 2 == 0) {
                // 买单
                var buyOrder = Order.create(
                    "BUY_" + orderIdGenerator.getAndIncrement(),
                    "BTCUSDT",
                    Order.OrderSide.BUY,
                    Order.OrderType.LIMIT,
                    basePrice,
                    new BigDecimal("0.1"),
                    "buyer_" + i
                );
                futures[i] = matchingEngine.submitOrder(buyOrder);
            } else {
                // 卖单 (相同价格，立即撮合)
                var sellOrder = Order.create(
                    "SELL_" + orderIdGenerator.getAndIncrement(),
                    "BTCUSDT",
                    Order.OrderSide.SELL,
                    Order.OrderType.LIMIT,
                    basePrice,
                    new BigDecimal("0.1"),
                    "seller_" + i
                );
                futures[i] = matchingEngine.submitOrder(sellOrder);
            }
        }
        
        // 等待所有订单处理完成
        CompletableFuture.allOf(futures).join();
        
        long endTime = System.nanoTime();
        long totalTimeNanos = endTime - startTime;
        double totalTimeMs = totalTimeNanos / 1_000_000.0;
        
        System.out.println("处理订单数: " + orderCount);
        System.out.println("成交笔数: " + tradeCount.get());
        System.out.println("总耗时: " + String.format("%.2f", totalTimeMs) + "ms");
        System.out.println("订单处理TPS: " + String.format("%.0f", orderCount * 1000.0 / totalTimeMs));
        System.out.println("撮合TPS: " + String.format("%.0f", tradeCount.get() * 1000.0 / totalTimeMs));
        
        // 显示撮合统计
        var stats = matchingEngine.getStats("BTCUSDT");
        if (stats != null) {
            System.out.println("平均延迟: " + String.format("%.2f", stats.getAverageLatencyMicros()) + "μs");
            System.out.println("最小延迟: " + String.format("%.2f", stats.getMinLatencyMicros()) + "μs");
            System.out.println("最大延迟: " + String.format("%.2f", stats.getMaxLatencyMicros()) + "μs");
        }
        
        // 显示风控统计
        var riskStats = matchingEngine.getRiskStats();
        System.out.println("风控统计: " + riskStats);
        
        matchingEngine.shutdown();
        System.out.println();
    }
    
    /**
     * 演示内存和GC性能
     */
    private static void demonstrateMemoryPerformance() throws Exception {
        System.out.println("=== 内存和GC性能演示 ===");
        
        var runtime = Runtime.getRuntime();
        var gcBefore = getGCTime();
        var memoryBefore = runtime.totalMemory() - runtime.freeMemory();
        
        System.out.println("开始大量对象创建和处理...");
        
        // 创建大量短生命周期对象 (模拟订单和交易)
        var objectCount = 1_000_000;
        var orders = new Order[objectCount];
        
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < objectCount; i++) {
            orders[i] = Order.create(
                "ORDER_" + i,
                "BTCUSDT",
                i % 2 == 0 ? Order.OrderSide.BUY : Order.OrderSide.SELL,
                Order.OrderType.LIMIT,
                new BigDecimal("50000").add(new BigDecimal(Math.random() * 1000)),
                new BigDecimal("0.1"),
                "user_" + (i % 1000)
            );
            
            // 模拟对象处理
            if (i % 10000 == 0) {
                // 定期释放一些对象引用
                for (int j = Math.max(0, i - 5000); j < i; j++) {
                    orders[j] = null;
                }
            }
        }
        
        long endTime = System.currentTimeMillis();
        var gcAfter = getGCTime();
        var memoryAfter = runtime.totalMemory() - runtime.freeMemory();
        
        System.out.println("对象创建数: " + objectCount);
        System.out.println("耗时: " + (endTime - startTime) + "ms");
        System.out.println("内存变化: " + (memoryAfter - memoryBefore) / 1024 / 1024 + "MB");
        System.out.println("GC时间变化: " + (gcAfter - gcBefore) + "ms");
        
        // 强制GC并观察效果
        System.out.println("执行垃圾回收...");
        var gcBeforeForced = getGCTime();
        System.gc();
        Thread.sleep(100); // 等待GC完成
        var gcAfterForced = getGCTime();
        var memoryAfterGC = runtime.totalMemory() - runtime.freeMemory();
        
        System.out.println("GC后内存: " + memoryAfterGC / 1024 / 1024 + "MB");
        System.out.println("GC耗时: " + (gcAfterForced - gcBeforeForced) + "ms");
        System.out.println("内存回收: " + (memoryAfter - memoryAfterGC) / 1024 / 1024 + "MB");
        
        System.out.println();
    }
    
    /**
     * 获取GC时间 (简化版)
     */
    private static long getGCTime() {
        return java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
                .stream()
                .mapToLong(gcBean -> gcBean.getCollectionTime())
                .sum();
    }
}
