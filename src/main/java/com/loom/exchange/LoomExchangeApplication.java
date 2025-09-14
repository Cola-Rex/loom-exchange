package com.loom.exchange;

import com.loom.exchange.core.Order;
import com.loom.exchange.engine.MatchingEngine;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.math.BigDecimal;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Loom Exchange 主应用程序
 * 基于JDK 21虚拟线程和ZGC分代的高频交易系统
 */
@SpringBootApplication
public class LoomExchangeApplication {
    
    private final MatchingEngine matchingEngine;
    
    public LoomExchangeApplication() {
        this.matchingEngine = new MatchingEngine();
        setupEventListeners();
    }
    
    public static void main(String[] args) {
        System.out.println("启动 Loom Exchange - 高频交易撮合系统");
        System.out.println("JDK版本: " + System.getProperty("java.version"));
        // 检查虚拟线程支持
        boolean supportsVirtualThreads = false;
        try {
            Thread.class.getMethod("ofVirtual");
            supportsVirtualThreads = true;
        } catch (NoSuchMethodException e) {
            // 虚拟线程不支持
        }
        System.out.println("虚拟线程支持: " + (supportsVirtualThreads ? "是" : "否"));
        System.out.println("ZGC支持: 请检查JVM参数");
        System.out.println("=====================================");
        
        SpringApplication.run(LoomExchangeApplication.class, args);
    }
    
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        System.out.println("交易所系统已启动，开始模拟交易...");
        
        // 启动模拟交易
        startSimulatedTrading();
        
        // 启动性能监控
        startPerformanceMonitoring();
    }
    
    /**
     * 设置事件监听器
     */
    private void setupEventListeners() {
        // 交易监听器
        matchingEngine.addTradeListener(trade -> {
            System.out.printf("[交易] %s: %s@%s, 数量: %s%n",
                trade.symbol(), 
                trade.tradeId(),
                trade.price(),
                trade.quantity()
            );
        });
        
        // 订单更新监听器
        matchingEngine.addOrderUpdateListener(order -> {
            if (order.status() == Order.OrderStatus.FILLED) {
                System.out.printf("[订单完成] %s: %s %s@%s%n",
                    order.symbol(),
                    order.orderId(),
                    order.side(),
                    order.price()
                );
            }
        });
    }
    
    /**
     * 启动模拟交易
     */
    private void startSimulatedTrading() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            
            // 启动多个虚拟线程模拟不同的交易者
            for (int traderId = 1; traderId <= 10; traderId++) {
                final int id = traderId;
                
                executor.submit(() -> {
                    simulateTrader(id);
                });
            }
        }
    }
    
    /**
     * 模拟单个交易者
     */
    private void simulateTrader(int traderId) {
        var basePrice = new BigDecimal("50000");
        var orderCounter = 0;
        
        try {
            while (!Thread.currentThread().isInterrupted()) {
                // 创建随机订单
                var side = (orderCounter % 2 == 0) ? Order.OrderSide.BUY : Order.OrderSide.SELL;
                var price = basePrice.add(new BigDecimal(Math.random() * 1000 - 500));
                var quantity = new BigDecimal("0.1");
                
                var order = Order.create(
                    "T" + traderId + "_" + (++orderCounter),
                    "BTCUSDT",
                    side,
                    Order.OrderType.LIMIT,
                    price,
                    quantity,
                    "trader_" + traderId
                );
                
                // 提交订单
                matchingEngine.submitOrder(order);
                
                // 随机间隔
                Thread.sleep(100 + (int)(Math.random() * 400));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("交易者 " + traderId + " 出错: " + e.getMessage());
        }
    }
    
    /**
     * 启动性能监控
     */
    private void startPerformanceMonitoring() {
        try (var scheduler = Executors.newScheduledThreadPool(1)) {
            
            scheduler.scheduleAtFixedRate(() -> {
                var stats = matchingEngine.getStats("BTCUSDT");
                if (stats != null && stats.getTotalOrders() > 0) {
                    System.out.println("=== 性能统计 ===");
                    System.out.println(stats);
                    
                    // JVM内存信息
                    var runtime = Runtime.getRuntime();
                    var totalMemory = runtime.totalMemory() / 1024 / 1024;
                    var freeMemory = runtime.freeMemory() / 1024 / 1024;
                    var usedMemory = totalMemory - freeMemory;
                    
                    System.out.printf("内存使用: %dMB / %dMB (%.1f%%)%n", 
                        usedMemory, totalMemory, 
                        (usedMemory * 100.0) / totalMemory);
                    
                    // 虚拟线程信息
                    System.out.printf("活跃线程数: %d%n", Thread.activeCount());
                    System.out.println("================");
                }
            }, 10, 10, TimeUnit.SECONDS);
        }
    }
}
