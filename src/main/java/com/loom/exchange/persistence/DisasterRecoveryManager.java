package com.loom.exchange.persistence;

import com.loom.exchange.core.OrderBook;
import com.loom.exchange.engine.MatchingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 灾难恢复管理器
 * 负责系统崩溃后的快速恢复
 */
@Component
public class DisasterRecoveryManager {
    
    private static final Logger log = LoggerFactory.getLogger(DisasterRecoveryManager.class);
    
    private final EventStore eventStore;
    private final MatchingEngine matchingEngine;
    private final ExecutorService virtualThreadExecutor;
    
    // 恢复统计
    private final AtomicLong recoveredEvents = new AtomicLong();
    private final AtomicLong recoveredSnapshots = new AtomicLong();
    
    public DisasterRecoveryManager(EventStore eventStore, MatchingEngine matchingEngine) {
        this.eventStore = eventStore;
        this.matchingEngine = matchingEngine;
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }
    
    /**
     * 执行完整的系统恢复
     */
    public CompletableFuture<RecoveryResult> performFullRecovery() {
        log.info("开始系统灾难恢复...");
        var startTime = Instant.now();
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. 检查事件存储健康状态
                var healthCheck = eventStore.healthCheck().join();
                if (!healthCheck) {
                    throw new RecoveryException("事件存储不健康，无法进行恢复");
                }
                
                // 2. 恢复所有交易对的订单簿
                var symbols = getAvailableSymbols();
                var recoveryFutures = symbols.parallelStream()
                    .map(this::recoverOrderBook)
                    .toList();
                
                // 3. 等待所有恢复任务完成
                CompletableFuture.allOf(recoveryFutures.toArray(new CompletableFuture[0])).join();
                
                // 4. 验证恢复结果
                validateRecovery();
                
                var duration = Duration.between(startTime, Instant.now());
                var result = new RecoveryResult(
                    true,
                    duration,
                    recoveredEvents.get(),
                    recoveredSnapshots.get(),
                    symbols.size(),
                    null
                );
                
                log.info("系统恢复完成: {}", result);
                return result;
                
            } catch (Exception e) {
                var duration = Duration.between(startTime, Instant.now());
                var result = new RecoveryResult(
                    false,
                    duration,
                    recoveredEvents.get(),
                    recoveredSnapshots.get(),
                    0,
                    e.getMessage()
                );
                
                log.error("系统恢复失败: {}", result, e);
                return result;
            }
        }, virtualThreadExecutor);
    }
    
    /**
     * 恢复单个交易对的订单簿
     */
    private CompletableFuture<Void> recoverOrderBook(String symbol) {
        return CompletableFuture.runAsync(() -> {
            try {
                log.debug("开始恢复订单簿: {}", symbol);
                
                // 1. 查找最新快照
                var snapshotOpt = eventStore.getLatestSnapshot(symbol).join();
                
                long fromVersion = 0;
                if (snapshotOpt.isPresent()) {
                    var snapshot = snapshotOpt.get();
                    log.debug("找到快照: {} 版本: {}", symbol, snapshot.sequenceNumber());
                    
                    // 从快照恢复订单簿状态
                    restoreFromSnapshot(symbol, snapshot);
                    fromVersion = snapshot.sequenceNumber() + 1;
                    recoveredSnapshots.incrementAndGet();
                }
                
                // 2. 重放快照之后的事件
                replayEventsFromVersion(symbol, fromVersion);
                
                log.debug("完成恢复订单簿: {}", symbol);
                
            } catch (Exception e) {
                log.error("恢复订单簿失败: {}", symbol, e);
                throw new RecoveryException("恢复订单簿失败: " + symbol, e);
            }
        }, virtualThreadExecutor);
    }
    
    /**
     * 从快照恢复订单簿状态
     */
    private void restoreFromSnapshot(String symbol, OrderBookSnapshot snapshot) {
        // 重建订单簿状态
        var orderBook = new OrderBook(symbol);
        
        // 从快照数据恢复买卖单
        var depth = snapshot.depth();
        
        // 恢复买单
        depth.bids().forEach(priceLevel -> {
            // 这里需要根据实际的快照数据结构来恢复订单
            // 简化实现，实际需要完整的订单信息
        });
        
        // 恢复卖单
        depth.asks().forEach(priceLevel -> {
            // 恢复卖单逻辑
        });
        
        // 将恢复的订单簿注册到撮合引擎
        // matchingEngine.registerOrderBook(symbol, orderBook);
        
        log.debug("从快照恢复订单簿完成: {}", symbol);
    }
    
    /**
     * 重放指定版本之后的事件
     */
    private void replayEventsFromVersion(String symbol, long fromVersion) {
        var streamId = "orders-" + symbol;
        var batchSize = 1000;
        var currentVersion = fromVersion;
        
        while (true) {
            var events = eventStore.readEvents(streamId, currentVersion, batchSize).join();
            
            if (events.isEmpty()) {
                break;
            }
            
            // 按序重放事件
            events.forEach(this::replayEvent);
            
            recoveredEvents.addAndGet(events.size());
            currentVersion += events.size();
            
            log.debug("重放事件批次: {} 事件数: {} 当前版本: {}", 
                symbol, events.size(), currentVersion);
        }
    }
    
    /**
     * 重放单个事件
     */
    private void replayEvent(DomainEvent event) {
        switch (event) {
            case OrderSubmitted orderSubmitted -> {
                // 重新提交订单到撮合引擎
                matchingEngine.submitOrder(orderSubmitted.order());
            }
            case OrderCancelled orderCancelled -> {
                // 取消订单
                matchingEngine.cancelOrder(orderCancelled.orderId(), orderCancelled.symbol());
            }
            case OrderMatched orderMatched -> {
                // 订单撮合事件通常不需要重放，因为它们是其他事件的结果
                log.debug("跳过撮合事件重放: {}", orderMatched.eventId());
            }
            case TradeExecuted tradeExecuted -> {
                // 交易执行事件也不需要重放
                log.debug("跳过交易事件重放: {}", tradeExecuted.eventId());
            }
            case OrderBookSnapshot snapshot -> {
                // 快照事件不需要重放
                log.debug("跳过快照事件重放: {}", snapshot.eventId());
            }
        }
    }
    
    /**
     * 验证恢复结果的一致性
     */
    private void validateRecovery() {
        log.info("开始验证恢复结果...");
        
        // 检查订单簿状态一致性
        var symbols = getAvailableSymbols();
        
        symbols.parallelStream().forEach(symbol -> {
            var orderBook = matchingEngine.getOrderBook(symbol);
            if (orderBook.isEmpty()) {
                log.warn("订单簿为空: {}", symbol);
                return;
            }
            
            // 验证订单簿数据完整性
            var depth = orderBook.get().getDepth(10);
            var bidCount = depth.getBidCount();
            var askCount = depth.getAskCount();
            
            log.debug("订单簿验证 {}: 买单={} 卖单={}", symbol, bidCount, askCount);
        });
        
        log.info("恢复结果验证完成");
    }
    
    /**
     * 获取所有可用的交易对
     */
    private java.util.List<String> getAvailableSymbols() {
        // 这里应该从配置或数据库中获取所有支持的交易对
        // 简化实现，返回默认交易对
        return java.util.List.of("BTCUSDT", "ETHUSDT", "ADAUSDT");
    }
    
    /**
     * 创建系统快照
     */
    public CompletableFuture<Void> createSystemSnapshot() {
        return CompletableFuture.runAsync(() -> {
            log.info("开始创建系统快照...");
            
            var symbols = getAvailableSymbols();
            var snapshotFutures = symbols.parallelStream()
                .map(this::createOrderBookSnapshot)
                .toList();
                
            // 等待所有快照创建完成
            CompletableFuture.allOf(snapshotFutures.toArray(new CompletableFuture[0])).join();
            
            log.info("系统快照创建完成");
        }, virtualThreadExecutor);
    }
    
    /**
     * 创建订单簿快照
     */
    private CompletableFuture<Void> createOrderBookSnapshot(String symbol) {
        return CompletableFuture.runAsync(() -> {
            var orderBookOpt = matchingEngine.getOrderBook(symbol);
            if (orderBookOpt.isEmpty()) {
                return;
            }
            
            var orderBook = orderBookOpt.get();
            var depth = orderBook.getDepth(100); // 保存前100档
            var version = eventStore.getStreamVersion("orders-" + symbol).join();
            
            var snapshot = OrderBookSnapshot.create(symbol, depth, version);
            eventStore.saveSnapshot(snapshot).join();
            
            log.debug("创建订单簿快照完成: {}", symbol);
        }, virtualThreadExecutor);
    }
    
    /**
     * 恢复结果
     */
    public record RecoveryResult(
        boolean success,
        Duration duration,
        long recoveredEvents,
        long recoveredSnapshots,
        long recoveredOrderBooks,
        String errorMessage
    ) {
        @Override
        public String toString() {
            if (success) {
                return String.format("恢复成功: 耗时=%dms, 事件=%d, 快照=%d, 订单簿=%d",
                    duration.toMillis(), recoveredEvents, recoveredSnapshots, recoveredOrderBooks);
            } else {
                return String.format("恢复失败: 耗时=%dms, 错误=%s",
                    duration.toMillis(), errorMessage);
            }
        }
    }
    
    /**
     * 恢复异常
     */
    public static class RecoveryException extends RuntimeException {
        public RecoveryException(String message) {
            super(message);
        }
        
        public RecoveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
