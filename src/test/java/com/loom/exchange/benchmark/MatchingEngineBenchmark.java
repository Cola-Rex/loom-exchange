package com.loom.exchange.benchmark;

import com.loom.exchange.core.Order;
import com.loom.exchange.engine.MatchingEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

/**
 * 撮合引擎性能基准测试
 * 验证JDK 21虚拟线程在高并发场景下的性能表现
 */
public class MatchingEngineBenchmark {
    
    private MatchingEngine matchingEngine;
    private final String SYMBOL = "BTCUSDT";
    private final AtomicLong orderIdGenerator = new AtomicLong(1);
    
    @BeforeEach
    void setUp() {
        matchingEngine = new MatchingEngine();
    }
    
    /**
     * 高并发订单提交测试
     * 模拟真实交易所的高频场景
     */
    @Test
    void benchmarkHighConcurrentOrders() throws InterruptedException {
        int totalOrders = 100_000;
        int concurrentUsers = 1_000;
        
        System.out.println("=== 高并发订单提交基准测试 ===");
        System.out.println("总订单数: " + totalOrders);
        System.out.println("并发用户数: " + concurrentUsers);
        System.out.println("使用JDK 21虚拟线程...\n");
        
        var startTime = System.nanoTime();
        var latch = new CountDownLatch(concurrentUsers);
        var ordersPerUser = totalOrders / concurrentUsers;
        
        // 创建虚拟线程池来模拟高并发用户
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            
            // 每个用户提交订单
            for (int userId = 0; userId < concurrentUsers; userId++) {
                final int finalUserId = userId;
                
                executor.submit(() -> {
                    try {
                        var futures = new ArrayList<CompletableFuture<?>>();
                        
                        // 每个用户提交多个订单
                        for (int i = 0; i < ordersPerUser; i++) {
                            var order = createRandomOrder(finalUserId);
                            var future = matchingEngine.submitOrder(order);
                            futures.add(future);
                        }
                        
                        // 等待所有订单处理完成
                        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                                        .join();
                        
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            // 等待所有用户完成
            latch.await();
        }
        
        var endTime = System.nanoTime();
        var totalTimeMs = (endTime - startTime) / 1_000_000;
        var throughput = (totalOrders * 1000L) / totalTimeMs;
        
        System.out.println("总耗时: " + totalTimeMs + "ms");
        System.out.println("吞吐量: " + throughput + " 订单/秒");
        
        // 输出撮合统计
        var stats = matchingEngine.getStats(SYMBOL);
        if (stats != null) {
            System.out.println("撮合统计: " + stats);
        }
    }
    
    /**
     * 延迟测试 - 单个订单从提交到撮合完成的延迟
     */
    @Test
    void benchmarkLatency() throws Exception {
        int warmupOrders = 10_000;
        int testOrders = 50_000;
        
        System.out.println("=== 延迟基准测试 ===");
        System.out.println("预热订单数: " + warmupOrders);
        System.out.println("测试订单数: " + testOrders);
        
        // 预热阶段
        System.out.println("预热中...");
        for (int i = 0; i < warmupOrders; i++) {
            var order = createRandomOrder(i);
            matchingEngine.submitOrder(order).join();
        }
        
        // 测试阶段
        System.out.println("开始延迟测试...");
        var latencies = new ArrayList<Long>();
        
        for (int i = 0; i < testOrders; i++) {
            var order = createRandomOrder(i);
            
            var startTime = System.nanoTime();
            matchingEngine.submitOrder(order).join();
            var endTime = System.nanoTime();
            
            latencies.add(endTime - startTime);
        }
        
        // 计算延迟统计
        Collections.sort(latencies);
        
        var avgLatency = latencies.stream()
                                 .mapToLong(Long::longValue)
                                 .average()
                                 .orElse(0) / 1000; // 转换为微秒
        
        var p50 = latencies.get(testOrders / 2) / 1000.0;
        var p95 = latencies.get((int) (testOrders * 0.95)) / 1000.0;
        var p99 = latencies.get((int) (testOrders * 0.99)) / 1000.0;
        var p999 = latencies.get((int) (testOrders * 0.999)) / 1000.0;
        
        System.out.println("\\n延迟统计 (微秒):");
        System.out.printf("平均延迟: %.2fμs\\n", avgLatency);
        System.out.printf("P50: %.2fμs\\n", p50);
        System.out.printf("P95: %.2fμs\\n", p95);
        System.out.printf("P99: %.2fμs\\n", p99);
        System.out.printf("P99.9: %.2fμs\\n", p999);
    }
    
    /**
     * 撮合效率测试 - 测试买卖单撮合的速度
     */
    @Test
    void benchmarkMatchingEfficiency() throws Exception {
        int orderPairs = 50_000;
        
        System.out.println("=== 撮合效率基准测试 ===");
        System.out.println("订单对数: " + orderPairs);
        
        var tradeCount = new AtomicLong();
        matchingEngine.addTradeListener(trade -> tradeCount.incrementAndGet());
        
        var startTime = System.nanoTime();
        
        // 创建匹配的买卖单对
        var futures = new ArrayList<CompletableFuture<?>>();
        var basePrice = new BigDecimal("50000");
        
        for (int i = 0; i < orderPairs; i++) {
            // 创建买单
            var buyOrder = Order.create(
                "BUY_" + i,
                SYMBOL,
                Order.OrderSide.BUY,
                Order.OrderType.LIMIT,
                basePrice,
                new BigDecimal("0.1"),
                "buyer_" + i
            );
            
            // 创建卖单（相同价格，应该立即撮合）
            var sellOrder = Order.create(
                "SELL_" + i,
                SYMBOL,
                Order.OrderSide.SELL,
                Order.OrderType.LIMIT,
                basePrice,
                new BigDecimal("0.1"),
                "seller_" + i
            );
            
            futures.add(matchingEngine.submitOrder(buyOrder));
            futures.add(matchingEngine.submitOrder(sellOrder));
        }
        
        // 等待所有订单处理完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        var endTime = System.nanoTime();
        var totalTimeMs = (endTime - startTime) / 1_000_000;
        var matchingThroughput = (tradeCount.get() * 1000L) / totalTimeMs;
        
        System.out.println("\\n撮合结果:");
        System.out.println("总耗时: " + totalTimeMs + "ms");
        System.out.println("成交笔数: " + tradeCount.get());
        System.out.println("撮合吞吐量: " + matchingThroughput + " 笔/秒");
    }
    
    /**
     * 虚拟线程扩展性测试
     */
    @Test
    void benchmarkVirtualThreadScalability() throws InterruptedException {
        int[] threadCounts = {100, 1_000, 10_000, 100_000};
        
        System.out.println("=== 虚拟线程扩展性测试 ===");
        
        for (int threadCount : threadCounts) {
            System.out.println("\\n测试 " + threadCount + " 个虚拟线程...");
            
            var latch = new CountDownLatch(threadCount);
            var startTime = System.nanoTime();
            
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < threadCount; i++) {
                    final int orderId = i;
                    
                    executor.submit(() -> {
                        try {
                            // 模拟订单处理
                            var order = createRandomOrder(orderId);
                            matchingEngine.submitOrder(order).join();
                            
                            // 模拟一些计算工作
                            Thread.sleep(1);
                            
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                
                latch.await();
            }
            
            var endTime = System.nanoTime();
            var totalTimeMs = (endTime - startTime) / 1_000_000;
            
            System.out.println("  耗时: " + totalTimeMs + "ms");
            System.out.println("  平均每线程: " + (totalTimeMs / (double) threadCount) + "ms");
        }
    }
    
    /**
     * 创建随机订单
     */
    private Order createRandomOrder(int userId) {
        var random = new Random(userId); // 使用userId作为种子确保可重复性
        var orderId = "ORDER_" + orderIdGenerator.getAndIncrement();
        var side = random.nextBoolean() ? Order.OrderSide.BUY : Order.OrderSide.SELL;
        
        // 价格在49000-51000之间波动
        var basePrice = 50000;
        var priceVariation = random.nextInt(2000) - 1000; // -1000 到 +1000
        var price = new BigDecimal(basePrice + priceVariation);
        
        // 数量在0.01-1之间
        var quantity = new BigDecimal(String.format("%.3f", 0.01 + random.nextDouble() * 0.99));
        
        return Order.create(orderId, SYMBOL, side, Order.OrderType.LIMIT, 
                          price, quantity, "user_" + userId);
    }
}
