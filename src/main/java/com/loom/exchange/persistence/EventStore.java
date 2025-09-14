package com.loom.exchange.persistence;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 事件存储接口
 * 支持高并发的事件读写操作
 */
public interface EventStore {
    
    /**
     * 追加事件到指定流
     * @param streamId 流ID
     * @param events 事件列表
     * @param expectedVersion 期望的版本号，用于乐观锁
     * @return 异步操作结果
     */
    CompletableFuture<Void> appendEvents(String streamId, List<DomainEvent> events, 
                                        long expectedVersion);
    
    /**
     * 读取指定流的事件
     * @param streamId 流ID
     * @param fromVersion 起始版本号
     * @param maxCount 最大读取数量
     * @return 事件列表
     */
    CompletableFuture<List<DomainEvent>> readEvents(String streamId, long fromVersion, 
                                                   int maxCount);
    
    /**
     * 读取所有事件（用于重建投影）
     * @param fromGlobalPosition 全局位置
     * @param maxCount 最大数量
     * @return 事件列表
     */
    CompletableFuture<List<DomainEvent>> readAllEvents(long fromGlobalPosition, 
                                                      int maxCount);
    
    /**
     * 获取流的最新快照
     * @param streamId 流ID
     * @return 快照事件
     */
    CompletableFuture<Optional<OrderBookSnapshot>> getLatestSnapshot(String streamId);
    
    /**
     * 保存快照
     * @param snapshot 快照事件
     * @return 异步操作结果
     */
    CompletableFuture<Void> saveSnapshot(OrderBookSnapshot snapshot);
    
    /**
     * 获取流的当前版本号
     * @param streamId 流ID
     * @return 版本号
     */
    CompletableFuture<Long> getStreamVersion(String streamId);
    
    /**
     * 获取全局事件位置
     * @return 全局位置
     */
    CompletableFuture<Long> getGlobalPosition();
    
    /**
     * 健康检查
     * @return 是否健康
     */
    CompletableFuture<Boolean> healthCheck();
    
    /**
     * 关闭事件存储
     */
    CompletableFuture<Void> close();
}
