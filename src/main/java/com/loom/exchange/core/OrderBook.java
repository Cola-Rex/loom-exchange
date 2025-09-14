package com.loom.exchange.core;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 高性能订单簿实现
 * 使用跳表(SkipList)实现价格优先，时间优先的排序
 * 针对高频交易场景优化，支持微秒级延迟
 */
public class OrderBook {
    
    private final String symbol;
    
    // 买单：价格从高到低排序 (降序)
    private final NavigableMap<BigDecimal, PriceLevel> buyOrders;
    
    // 卖单：价格从低到高排序 (升序)  
    private final NavigableMap<BigDecimal, PriceLevel> sellOrders;
    
    // 订单ID到订单的快速查找映射
    private final Map<String, Order> orderIndex;
    
    // 读写锁，支持多读单写
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    
    public OrderBook(String symbol) {
        this.symbol = symbol;
        // 买单使用降序比较器
        this.buyOrders = new ConcurrentSkipListMap<>(Collections.reverseOrder());
        // 卖单使用自然排序（升序）
        this.sellOrders = new ConcurrentSkipListMap<>();
        this.orderIndex = new ConcurrentHashMap<>();
    }
    
    /**
     * 添加订单到订单簿
     */
    public void addOrder(Order order) {
        lock.writeLock().lock();
        try {
            var priceLevel = getPriceLevel(order.side(), order.price());
            priceLevel.addOrder(order);
            orderIndex.put(order.orderId(), order);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 移除订单
     */
    public boolean removeOrder(String orderId) {
        lock.writeLock().lock();
        try {
            var order = orderIndex.remove(orderId);
            if (order == null) {
                return false;
            }
            
            var priceLevel = getPriceLevel(order.side(), order.price());
            priceLevel.removeOrder(order);
            
            // 如果价格档位为空，则移除
            if (priceLevel.isEmpty()) {
                getOrderMap(order.side()).remove(order.price());
            }
            
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 获取最佳买价
     */
    public Optional<BigDecimal> getBestBid() {
        lock.readLock().lock();
        try {
            return buyOrders.isEmpty() ? Optional.empty() : 
                   Optional.of(buyOrders.firstKey());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 获取最佳卖价
     */
    public Optional<BigDecimal> getBestAsk() {
        lock.readLock().lock();
        try {
            return sellOrders.isEmpty() ? Optional.empty() : 
                   Optional.of(sellOrders.firstKey());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 获取买卖价差
     */
    public Optional<BigDecimal> getSpread() {
        var bestBid = getBestBid();
        var bestAsk = getBestAsk();
        
        if (bestBid.isPresent() && bestAsk.isPresent()) {
            return Optional.of(bestAsk.get().subtract(bestBid.get()));
        }
        return Optional.empty();
    }
    
    /**
     * 获取指定价格的买单队列
     */
    public Optional<PriceLevel> getBuyLevel(BigDecimal price) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(buyOrders.get(price));
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 获取指定价格的卖单队列
     */
    public Optional<PriceLevel> getSellLevel(BigDecimal price) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(sellOrders.get(price));
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 获取订单簿深度（前N档）
     */
    public OrderBookDepth getDepth(int levels) {
        lock.readLock().lock();
        try {
            var bids = new ArrayList<PriceLevel>();
            var asks = new ArrayList<PriceLevel>();
            
            // 获取买单前N档
            buyOrders.values().stream()
                .limit(levels)
                .forEach(bids::add);
                
            // 获取卖单前N档
            sellOrders.values().stream()
                .limit(levels)
                .forEach(asks::add);
                
            return new OrderBookDepth(symbol, bids, asks);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    private PriceLevel getPriceLevel(Order.OrderSide side, BigDecimal price) {
        var orderMap = getOrderMap(side);
        return orderMap.computeIfAbsent(price, p -> new PriceLevel(price));
    }
    
    private NavigableMap<BigDecimal, PriceLevel> getOrderMap(Order.OrderSide side) {
        return side == Order.OrderSide.BUY ? buyOrders : sellOrders;
    }
    
    public String getSymbol() {
        return symbol;
    }
    
    /**
     * 获取订单总数
     */
    public int getOrderCount() {
        lock.readLock().lock();
        try {
            return orderIndex.size();
        } finally {
            lock.readLock().unlock();
        }
    }
}
