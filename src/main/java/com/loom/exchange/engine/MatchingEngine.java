package com.loom.exchange.engine;

import com.loom.exchange.core.*;
import com.loom.exchange.risk.RiskConfig;
import com.loom.exchange.risk.RiskManager;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 高频交易撮合引擎
 * 利用JDK 21的虚拟线程实现高并发订单处理
 * 每个交易对使用独立的虚拟线程进行撮合，避免锁竞争
 */
public class MatchingEngine {
    
    private final Map<String, OrderBook> orderBooks;
    private final ExecutorService virtualThreadExecutor;
    private final AtomicLong tradeIdGenerator;
    private final RiskManager riskManager;
    
    // 事件回调
    private final List<Consumer<Trade>> tradeListeners;
    private final List<Consumer<Order>> orderUpdateListeners;
    
    // 性能统计
    private final Map<String, MatchingStats> stats;
    
    public MatchingEngine() {
        this(RiskConfig.defaultConfig());
    }
    
    public MatchingEngine(RiskConfig riskConfig) {
        this.orderBooks = new ConcurrentHashMap<>();
        // 使用JDK 21的虚拟线程执行器
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.tradeIdGenerator = new AtomicLong(1);
        this.riskManager = new RiskManager(riskConfig);
        this.tradeListeners = new CopyOnWriteArrayList<>();
        this.orderUpdateListeners = new CopyOnWriteArrayList<>();
        this.stats = new ConcurrentHashMap<>();
    }
    
    /**
     * 提交订单进行撮合
     * 使用虚拟线程异步处理，不阻塞调用者
     */
    public CompletableFuture<MatchResult> submitOrder(Order order) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.nanoTime();
            
            try {
                var orderBook = getOrCreateOrderBook(order.symbol());
                var result = processOrder(orderBook, order);
                
                // 更新性能统计
                updateStats(order.symbol(), System.nanoTime() - startTime);
                
                return result;
            } catch (Exception e) {
                return MatchResult.error(order, e.getMessage());
            }
        }, virtualThreadExecutor);
    }
    
    /**
     * 取消订单
     */
    public CompletableFuture<Boolean> cancelOrder(String orderId, String symbol) {
        return CompletableFuture.supplyAsync(() -> {
            var orderBook = orderBooks.get(symbol);
            if (orderBook == null) {
                return false;
            }
            
            return orderBook.removeOrder(orderId);
        }, virtualThreadExecutor);
    }
    
    /**
     * 处理单个订单的撮合逻辑
     */
    private MatchResult processOrder(OrderBook orderBook, Order order) {
        // 风控检查
        var riskCheck = riskManager.checkOrder(order);
        if (riskCheck.rejected()) {
            return MatchResult.error(order, "风控拒绝: " + riskCheck.reason());
        }
        
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
     * 处理市价单
     */
    private Order processMarketOrder(OrderBook orderBook, Order order, List<Trade> trades) {
        var currentOrder = order;
        
        while (!currentOrder.isFullyFilled()) {
            var counterSide = order.side() == Order.OrderSide.BUY ? 
                             Order.OrderSide.SELL : Order.OrderSide.BUY;
            
            var bestLevel = getBestPriceLevel(orderBook, counterSide);
            if (bestLevel.isEmpty()) {
                // 没有对手盘，市价单无法成交
                break;
            }
            
            var matchResult = matchOrders(currentOrder, bestLevel.get(), trades);
            currentOrder = matchResult.takerOrder();
            
            // 如果对手盘价格档位空了，从订单簿移除
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
        
        // 持续撮合直到无法匹配或订单完全成交
        while (!currentOrder.isFullyFilled()) {
            var bestLevel = getBestPriceLevel(orderBook, counterSide);
            if (bestLevel.isEmpty()) {
                break;
            }
            
            // 检查价格是否匹配
            if (!isPriceMatch(currentOrder, bestLevel.get().getPrice())) {
                break;
            }
            
            var matchResult = matchOrders(currentOrder, bestLevel.get(), trades);
            currentOrder = matchResult.takerOrder();
            
            // 如果对手盘价格档位空了，从订单簿移除
            if (bestLevel.get().isEmpty()) {
                removeEmptyPriceLevel(orderBook, counterSide, bestLevel.get().getPrice());
            }
        }
        
        return currentOrder;
    }
    
    /**
     * 撮合两个订单
     */
    private MatchResult matchOrders(Order takerOrder, PriceLevel makerLevel, List<Trade> trades) {
        var makerOrder = makerLevel.peekFirst();
        if (makerOrder == null) {
            return new MatchResult(takerOrder, null, List.of());
        }
        
        // 计算成交数量（取较小值）
        var tradeQuantity = takerOrder.getRemainingQuantity()
                          .min(makerOrder.getRemainingQuantity());
        
        // 成交价格使用挂单价格（价格优先原则）
        var tradePrice = makerOrder.price();
        
        // 生成交易记录
        var trade = createTrade(takerOrder, makerOrder, tradePrice, tradeQuantity);
        trades.add(trade);
        
        // 更新订单状态
        var updatedTaker = takerOrder.withPartialFill(tradeQuantity);
        var updatedMaker = makerOrder.withPartialFill(tradeQuantity);
        
        // 如果挂单完全成交，从价格档位移除
        if (updatedMaker.isFullyFilled()) {
            makerLevel.pollFirst();
        }
        
        // 通知订单更新
        notifyOrderUpdateListeners(updatedMaker);
        
        return new MatchResult(updatedTaker, updatedMaker, List.of(trade));
    }
    
    /**
     * 创建交易记录
     */
    private Trade createTrade(Order takerOrder, Order makerOrder, 
                            BigDecimal price, BigDecimal quantity) {
        var tradeId = String.valueOf(tradeIdGenerator.getAndIncrement());
        
        // 确定买卖方向
        var buyOrder = takerOrder.side() == Order.OrderSide.BUY ? takerOrder : makerOrder;
        var sellOrder = takerOrder.side() == Order.OrderSide.SELL ? takerOrder : makerOrder;
        
        return Trade.create(tradeId, takerOrder.symbol(), buyOrder, sellOrder, price, quantity);
    }
    
    /**
     * 检查价格是否匹配
     */
    private boolean isPriceMatch(Order order, BigDecimal counterPrice) {
        return switch (order.side()) {
            case BUY -> order.price().compareTo(counterPrice) >= 0;  // 买单价格 >= 卖单价格
            case SELL -> order.price().compareTo(counterPrice) <= 0; // 卖单价格 <= 买单价格
        };
    }
    
    /**
     * 获取最佳价格档位
     */
    private Optional<PriceLevel> getBestPriceLevel(OrderBook orderBook, Order.OrderSide side) {
        return switch (side) {
            case BUY -> orderBook.getBestBid().flatMap(orderBook::getBuyLevel);
            case SELL -> orderBook.getBestAsk().flatMap(orderBook::getSellLevel);
        };
    }
    
    /**
     * 移除空的价格档位
     */
    private void removeEmptyPriceLevel(OrderBook orderBook, Order.OrderSide side, BigDecimal price) {
        // 这个逻辑在OrderBook.removeOrder中已经处理
    }
    
    /**
     * 获取或创建订单簿
     */
    private OrderBook getOrCreateOrderBook(String symbol) {
        return orderBooks.computeIfAbsent(symbol, OrderBook::new);
    }
    
    /**
     * 更新性能统计
     */
    private void updateStats(String symbol, long latencyNanos) {
        stats.computeIfAbsent(symbol, k -> new MatchingStats())
             .recordLatency(latencyNanos);
    }
    
    /**
     * 通知交易监听器
     */
    private void notifyTradeListeners(Trade trade) {
        // 使用虚拟线程异步通知，避免阻塞撮合流程
        virtualThreadExecutor.execute(() -> {
            tradeListeners.forEach(listener -> {
                try {
                    listener.accept(trade);
                } catch (Exception e) {
                    // 记录日志但不影响撮合
                    System.err.println("Trade listener error: " + e.getMessage());
                }
            });
        });
    }
    
    /**
     * 通知订单更新监听器
     */
    private void notifyOrderUpdateListeners(Order order) {
        virtualThreadExecutor.execute(() -> {
            orderUpdateListeners.forEach(listener -> {
                try {
                    listener.accept(order);
                } catch (Exception e) {
                    System.err.println("Order update listener error: " + e.getMessage());
                }
            });
        });
    }
    
    // 公共API方法
    
    public void addTradeListener(Consumer<Trade> listener) {
        tradeListeners.add(listener);
    }
    
    public void addOrderUpdateListener(Consumer<Order> listener) {
        orderUpdateListeners.add(listener);
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
    private record MatchResult(Order takerOrder, Order makerOrder, List<Trade> trades) {}
}
