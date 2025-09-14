package com.loom.exchange.engine;

import com.loom.exchange.core.*;
import com.loom.exchange.persistence.*;
import com.loom.exchange.risk.RiskConfig;
import com.loom.exchange.risk.RiskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 基于事件溯源的撮合引擎
 * 集成持久化和灾难恢复功能
 */
@Component
public class EventSourcingMatchingEngine {
    
    private static final Logger log = LoggerFactory.getLogger(EventSourcingMatchingEngine.class);
    
    private final Map<String, OrderBook> orderBooks;
    private final ExecutorService virtualThreadExecutor;
    private final AtomicLong tradeIdGenerator;
    private final RiskManager riskManager;
    private final EventStore eventStore;
    
    // 事件序列号生成器（每个流独立）
    private final Map<String, AtomicLong> streamSequenceNumbers;
    
    // 事件回调
    private final List<Consumer<Trade>> tradeListeners;
    private final List<Consumer<Order>> orderUpdateListeners;
    private final List<Consumer<DomainEvent>> eventListeners;
    
    // 性能统计
    private final Map<String, MatchingStats> stats;
    
    public EventSourcingMatchingEngine(EventStore eventStore) {
        this(eventStore, RiskConfig.defaultConfig());
    }
    
    public EventSourcingMatchingEngine(EventStore eventStore, RiskConfig riskConfig) {
        this.eventStore = eventStore;
        this.orderBooks = new ConcurrentHashMap<>();
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.tradeIdGenerator = new AtomicLong(1);
        this.riskManager = new RiskManager(riskConfig);
        this.streamSequenceNumbers = new ConcurrentHashMap<>();
        this.tradeListeners = new CopyOnWriteArrayList<>();
        this.orderUpdateListeners = new CopyOnWriteArrayList<>();
        this.eventListeners = new CopyOnWriteArrayList<>();
        this.stats = new ConcurrentHashMap<>();
    }
    
    /**
     * 提交订单进行撮合（带事件持久化）
     */
    public CompletableFuture<MatchResult> submitOrder(Order order) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.nanoTime();
            
            try {
                // 1. 风控检查
                var riskCheck = riskManager.checkOrder(order);
                if (riskCheck.rejected()) {
                    return MatchResult.error(order, "风控拒绝: " + riskCheck.reason());
                }
                
                // 2. 创建订单提交事件
                var streamId = "orders-" + order.symbol();
                var sequenceNumber = getNextSequenceNumber(streamId);
                var orderSubmittedEvent = OrderSubmitted.create(order, sequenceNumber);
                
                // 3. 持久化事件
                var expectedVersion = getCurrentStreamVersion(streamId);
                eventStore.appendEvents(streamId, List.of(orderSubmittedEvent), expectedVersion).join();
                
                // 4. 处理订单撮合
                var orderBook = getOrCreateOrderBook(order.symbol());
                var result = processOrderMatching(orderBook, order);
                
                // 5. 持久化撮合结果事件
                if (result.hasTraded()) {
                    persistTradeEvents(order, result.trades());
                }
                
                // 6. 更新性能统计
                updateStats(order.symbol(), System.nanoTime() - startTime);
                
                // 7. 通知事件监听器
                notifyEventListeners(orderSubmittedEvent);
                
                return result;
                
            } catch (Exception e) {
                log.error("订单处理失败: {}", order.orderId(), e);
                return MatchResult.error(order, e.getMessage());
            }
        }, virtualThreadExecutor);
    }
    
    /**
     * 取消订单（带事件持久化）
     */
    public CompletableFuture<Boolean> cancelOrder(String orderId, String symbol, String reason) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. 从订单簿移除订单
                var orderBook = orderBooks.get(symbol);
                if (orderBook == null) {
                    return false;
                }
                
                var removed = orderBook.removeOrder(orderId);
                if (!removed) {
                    return false;
                }
                
                // 2. 创建取消事件
                var streamId = "orders-" + symbol;
                var sequenceNumber = getNextSequenceNumber(streamId);
                var cancelledEvent = OrderCancelled.create(orderId, symbol, reason, sequenceNumber);
                
                // 3. 持久化事件
                var expectedVersion = getCurrentStreamVersion(streamId);
                eventStore.appendEvents(streamId, List.of(cancelledEvent), expectedVersion).join();
                
                // 4. 通知监听器
                notifyEventListeners(cancelledEvent);
                
                return true;
                
            } catch (Exception e) {
                log.error("取消订单失败: {} - {}", orderId, symbol, e);
                return false;
            }
        }, virtualThreadExecutor);
    }
    
    /**
     * 处理订单撮合逻辑
     */
    private MatchResult processOrderMatching(OrderBook orderBook, Order order) {
        var trades = new ArrayList<Trade>();
        var currentOrder = order;
        
        // 市价单立即撮合
        if (order.type() == Order.OrderType.MARKET) {
            currentOrder = processMarketOrder(orderBook, currentOrder, trades);
        } else {
            // 限价单先尝试撮合，未成交部分加入订单簿
            currentOrder = processLimitOrder(orderBook, currentOrder, trades);
        }
        
        // 通知交易监听器
        trades.forEach(trade -> {
            notifyTradeListeners(trade);
            // 更新市场价格用于风控
            riskManager.updateMarketPrice(trade.symbol(), trade.price());
        });
        
        // 如果订单未完全成交且未取消，加入订单簿
        if (currentOrder != null && !currentOrder.isFullyFilled() && 
            currentOrder.status() == Order.OrderStatus.PENDING) {
            orderBook.addOrder(currentOrder);
            notifyOrderUpdateListeners(currentOrder);
        }
        
        return MatchResult.success(order, currentOrder, trades);
    }
    
    /**
     * 持久化交易事件
     */
    private void persistTradeEvents(Order originalOrder, List<Trade> trades) {
        virtualThreadExecutor.submit(() -> {
            try {
                var events = new ArrayList<DomainEvent>();
                
                for (var trade : trades) {
                    // 创建交易执行事件
                    var streamId = "trades-" + trade.symbol();
                    var sequenceNumber = getNextSequenceNumber(streamId);
                    var tradeEvent = TradeExecuted.create(trade, sequenceNumber);
                    events.add(tradeEvent);
                    
                    // 创建订单撮合事件
                    var orderStreamId = "orders-" + trade.symbol();
                    var orderSequenceNumber = getNextSequenceNumber(orderStreamId);
                    var matchEvent = OrderMatched.create(
                        trade, 
                        originalOrder.orderId(), 
                        trade.sellOrderId().equals(originalOrder.orderId()) ? 
                            trade.buyOrderId() : trade.sellOrderId(),
                        orderSequenceNumber
                    );
                    events.add(matchEvent);
                }
                
                // 批量持久化事件
                for (var event : events) {
                    var expectedVersion = getCurrentStreamVersion(event.streamId());
                    eventStore.appendEvents(event.streamId(), List.of(event), expectedVersion).join();
                    notifyEventListeners(event);
                }
                
            } catch (Exception e) {
                log.error("持久化交易事件失败", e);
            }
        });
    }
    
    /**
     * 处理市价单
     */
    private Order processMarketOrder(OrderBook orderBook, Order order, List<Trade> trades) {
        var currentOrder = order;
        
        while (!currentOrder.isFullyFilled()) {
            var counterSide = order.side() == Order.OrderSide.BUY ? 
                             Order.OrderSide.SELL : Order.OrderSide.BUY;
            
            var bestLevel = getBestPriceLevel(orderBook, counterSide);
            if (bestLevel.isEmpty()) {
                break;
            }
            
            var matchResult = matchOrders(currentOrder, bestLevel.get(), trades);
            currentOrder = matchResult.takerOrder();
            
            if (bestLevel.get().isEmpty()) {
                removeEmptyPriceLevel(orderBook, counterSide, bestLevel.get().getPrice());
            }
        }
        
        return currentOrder;
    }
    
    /**
     * 处理限价单
     */
    private Order processLimitOrder(OrderBook orderBook, Order order, List<Trade> trades) {
        var currentOrder = order;
        var counterSide = order.side() == Order.OrderSide.BUY ? 
                         Order.OrderSide.SELL : Order.OrderSide.BUY;
        
        while (!currentOrder.isFullyFilled()) {
            var bestLevel = getBestPriceLevel(orderBook, counterSide);
            if (bestLevel.isEmpty()) {
                break;
            }
            
            if (!isPriceMatch(currentOrder, bestLevel.get().getPrice())) {
                break;
            }
            
            var matchResult = matchOrders(currentOrder, bestLevel.get(), trades);
            currentOrder = matchResult.takerOrder();
            
            if (bestLevel.get().isEmpty()) {
                removeEmptyPriceLevel(orderBook, counterSide, bestLevel.get().getPrice());
            }
        }
        
        return currentOrder;
    }
    
    /**
     * 撮合两个订单
     */
    private MatchOrderResult matchOrders(Order takerOrder, PriceLevel makerLevel, List<Trade> trades) {
        var makerOrder = makerLevel.peekFirst();
        if (makerOrder == null) {
            return new MatchOrderResult(takerOrder, null);
        }
        
        var tradeQuantity = takerOrder.getRemainingQuantity()
                          .min(makerOrder.getRemainingQuantity());
        var tradePrice = makerOrder.price();
        
        var trade = createTrade(takerOrder, makerOrder, tradePrice, tradeQuantity);
        trades.add(trade);
        
        var updatedTaker = takerOrder.withPartialFill(tradeQuantity);
        var updatedMaker = makerOrder.withPartialFill(tradeQuantity);
        
        if (updatedMaker.isFullyFilled()) {
            makerLevel.pollFirst();
        }
        
        notifyOrderUpdateListeners(updatedMaker);
        
        return new MatchOrderResult(updatedTaker, updatedMaker);
    }
    
    /**
     * 创建交易记录
     */
    private Trade createTrade(Order takerOrder, Order makerOrder, 
                            java.math.BigDecimal price, java.math.BigDecimal quantity) {
        var tradeId = String.valueOf(tradeIdGenerator.getAndIncrement());
        var buyOrder = takerOrder.side() == Order.OrderSide.BUY ? takerOrder : makerOrder;
        var sellOrder = takerOrder.side() == Order.OrderSide.SELL ? takerOrder : makerOrder;
        
        return Trade.create(tradeId, takerOrder.symbol(), buyOrder, sellOrder, price, quantity);
    }
    
    /**
     * 获取流的下一个序列号
     */
    private long getNextSequenceNumber(String streamId) {
        return streamSequenceNumbers.computeIfAbsent(streamId, k -> new AtomicLong(0))
                                   .incrementAndGet();
    }
    
    /**
     * 获取流的当前版本
     */
    private long getCurrentStreamVersion(String streamId) {
        return streamSequenceNumbers.computeIfAbsent(streamId, k -> new AtomicLong(0))
                                   .get();
    }
    
    // 其他辅助方法...
    private Optional<PriceLevel> getBestPriceLevel(OrderBook orderBook, Order.OrderSide side) {
        return switch (side) {
            case BUY -> orderBook.getBestBid().flatMap(orderBook::getBuyLevel);
            case SELL -> orderBook.getBestAsk().flatMap(orderBook::getSellLevel);
        };
    }
    
    private boolean isPriceMatch(Order order, java.math.BigDecimal counterPrice) {
        return switch (order.side()) {
            case BUY -> order.price().compareTo(counterPrice) >= 0;
            case SELL -> order.price().compareTo(counterPrice) <= 0;
        };
    }
    
    private void removeEmptyPriceLevel(OrderBook orderBook, Order.OrderSide side, java.math.BigDecimal price) {
        // OrderBook内部处理
    }
    
    private OrderBook getOrCreateOrderBook(String symbol) {
        return orderBooks.computeIfAbsent(symbol, OrderBook::new);
    }
    
    private void updateStats(String symbol, long latencyNanos) {
        stats.computeIfAbsent(symbol, k -> new MatchingStats())
             .recordLatency(latencyNanos);
    }
    
    // 通知方法
    private void notifyTradeListeners(Trade trade) {
        virtualThreadExecutor.execute(() -> {
            tradeListeners.forEach(listener -> {
                try {
                    listener.accept(trade);
                } catch (Exception e) {
                    log.error("Trade listener error", e);
                }
            });
        });
    }
    
    private void notifyOrderUpdateListeners(Order order) {
        virtualThreadExecutor.execute(() -> {
            orderUpdateListeners.forEach(listener -> {
                try {
                    listener.accept(order);
                } catch (Exception e) {
                    log.error("Order update listener error", e);
                }
            });
        });
    }
    
    private void notifyEventListeners(DomainEvent event) {
        virtualThreadExecutor.execute(() -> {
            eventListeners.forEach(listener -> {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    log.error("Event listener error", e);
                }
            });
        });
    }
    
    // 公共API
    public void addTradeListener(Consumer<Trade> listener) {
        tradeListeners.add(listener);
    }
    
    public void addOrderUpdateListener(Consumer<Order> listener) {
        orderUpdateListeners.add(listener);
    }
    
    public void addEventListener(Consumer<DomainEvent> listener) {
        eventListeners.add(listener);
    }
    
    public Optional<OrderBook> getOrderBook(String symbol) {
        return Optional.ofNullable(orderBooks.get(symbol));
    }
    
    public MatchingStats getStats(String symbol) {
        return stats.get(symbol);
    }
    
    public com.loom.exchange.risk.RiskStats getRiskStats() {
        return riskManager.getStats();
    }
    
    public void shutdown() {
        virtualThreadExecutor.shutdown();
    }
    
    /**
     * 撮合结果内部记录
     */
    private record MatchOrderResult(Order takerOrder, Order makerOrder) {}
}
