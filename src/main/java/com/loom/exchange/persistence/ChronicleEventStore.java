package com.loom.exchange.persistence;

import net.openhft.chronicle.map.ChronicleMap;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于Chronicle Map的高性能事件存储实现
 * 利用JDK 21虚拟线程实现异步操作
 */
@Component
public class ChronicleEventStore implements EventStore {
    
    private final ChronicleMap<String, EventStream> eventStreams;
    private final ChronicleMap<String, OrderBookSnapshot> snapshots;
    private final ExecutorService virtualThreadExecutor;
    private final AtomicLong globalPosition = new AtomicLong(0);
    
    // 事件流数据结构
    private static class EventStream {
        private final List<DomainEvent> events = new CopyOnWriteArrayList<>();
        private volatile long version = 0;
        
        public synchronized void appendEvents(List<DomainEvent> newEvents, long expectedVersion) {
            if (expectedVersion != -1 && expectedVersion != version) {
                throw new OptimisticLockException("Expected version " + expectedVersion + 
                    " but current version is " + version);
            }
            
            events.addAll(newEvents);
            version += newEvents.size();
        }
        
        public List<DomainEvent> getEvents(long fromVersion, int maxCount) {
            if (fromVersion >= events.size()) {
                return List.of();
            }
            
            int startIndex = (int) fromVersion;
            int endIndex = Math.min(startIndex + maxCount, events.size());
            
            return new ArrayList<>(events.subList(startIndex, endIndex));
        }
        
        public long getVersion() {
            return version;
        }
    }
    
    public ChronicleEventStore() {
        try {
            // 初始化虚拟线程执行器
            this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
            
            // 初始化Chronicle Map用于事件流存储
            this.eventStreams = ChronicleMap
                .of(String.class, EventStream.class)
                .name("event-streams")
                .entries(100_000)  // 支持10万个事件流
                .averageKeySize(50)
                .createPersistedTo(Paths.get("data/event-streams.dat").toFile());
            
            // 初始化Chronicle Map用于快照存储
            this.snapshots = ChronicleMap
                .of(String.class, OrderBookSnapshot.class)
                .name("snapshots")
                .entries(10_000)   // 支持1万个快照
                .averageKeySize(50)
                .createPersistedTo(Paths.get("data/snapshots.dat").toFile());
                
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize Chronicle Maps", e);
        }
    }
    
    @Override
    public CompletableFuture<Void> appendEvents(String streamId, List<DomainEvent> events, 
                                              long expectedVersion) {
        return CompletableFuture.runAsync(() -> {
            var stream = eventStreams.computeIfAbsent(streamId, k -> new EventStream());
            
            try {
                stream.appendEvents(events, expectedVersion);
                globalPosition.addAndGet(events.size());
                
                // 异步持久化到磁盘（Chronicle Map自动处理）
                // 这里可以添加额外的备份逻辑
                
            } catch (Exception e) {
                throw new RuntimeException("Failed to append events to stream: " + streamId, e);
            }
        }, virtualThreadExecutor);
    }
    
    @Override
    public CompletableFuture<List<DomainEvent>> readEvents(String streamId, long fromVersion, 
                                                         int maxCount) {
        return CompletableFuture.supplyAsync(() -> {
            var stream = eventStreams.get(streamId);
            if (stream == null) {
                return List.<DomainEvent>of();
            }
            
            return stream.getEvents(fromVersion, maxCount);
        }, virtualThreadExecutor);
    }
    
    @Override
    public CompletableFuture<List<DomainEvent>> readAllEvents(long fromGlobalPosition, 
                                                            int maxCount) {
        return CompletableFuture.supplyAsync(() -> {
            var allEvents = new ArrayList<DomainEvent>();
            long currentPosition = 0;
            
            for (var stream : eventStreams.values()) {
                var events = stream.getEvents(0, Integer.MAX_VALUE);
                
                for (var event : events) {
                    if (currentPosition >= fromGlobalPosition && allEvents.size() < maxCount) {
                        allEvents.add(event);
                    }
                    currentPosition++;
                    
                    if (allEvents.size() >= maxCount) {
                        break;
                    }
                }
                
                if (allEvents.size() >= maxCount) {
                    break;
                }
            }
            
            return allEvents;
        }, virtualThreadExecutor);
    }
    
    @Override
    public CompletableFuture<Optional<OrderBookSnapshot>> getLatestSnapshot(String streamId) {
        return CompletableFuture.supplyAsync(() -> {
            var snapshot = snapshots.get("snapshot-" + streamId);
            return Optional.ofNullable(snapshot);
        }, virtualThreadExecutor);
    }
    
    @Override
    public CompletableFuture<Void> saveSnapshot(OrderBookSnapshot snapshot) {
        return CompletableFuture.runAsync(() -> {
            snapshots.put("snapshot-" + snapshot.symbol(), snapshot);
        }, virtualThreadExecutor);
    }
    
    @Override
    public CompletableFuture<Long> getStreamVersion(String streamId) {
        return CompletableFuture.supplyAsync(() -> {
            var stream = eventStreams.get(streamId);
            return stream != null ? stream.getVersion() : 0L;
        }, virtualThreadExecutor);
    }
    
    @Override
    public CompletableFuture<Long> getGlobalPosition() {
        return CompletableFuture.completedFuture(globalPosition.get());
    }
    
    @Override
    public CompletableFuture<Boolean> healthCheck() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 检查Chronicle Map是否可用
                var testKey = "health-check-" + System.currentTimeMillis();
                var testStream = new EventStream();
                
                eventStreams.put(testKey, testStream);
                var retrieved = eventStreams.get(testKey);
                eventStreams.remove(testKey);
                
                return retrieved != null;
                
            } catch (Exception e) {
                return false;
            }
        }, virtualThreadExecutor);
    }
    
    @Override
    public CompletableFuture<Void> close() {
        return CompletableFuture.runAsync(() -> {
            try {
                eventStreams.close();
                snapshots.close();
                virtualThreadExecutor.shutdown();
                
                if (!virtualThreadExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    virtualThreadExecutor.shutdownNow();
                }
                
            } catch (Exception e) {
                throw new RuntimeException("Failed to close ChronicleEventStore", e);
            }
        });
    }
    
    /**
     * 获取存储统计信息
     */
    public StorageStats getStats() {
        var streamCount = eventStreams.size();
        var snapshotCount = snapshots.size();
        var totalEvents = eventStreams.values().stream()
            .mapToLong(EventStream::getVersion)
            .sum();
        
        return new StorageStats(streamCount, snapshotCount, totalEvents, globalPosition.get());
    }
    
    /**
     * 存储统计信息
     */
    public record StorageStats(
        long streamCount,
        long snapshotCount, 
        long totalEvents,
        long globalPosition
    ) {}
    
    /**
     * 乐观锁异常
     */
    public static class OptimisticLockException extends RuntimeException {
        public OptimisticLockException(String message) {
            super(message);
        }
    }
}
