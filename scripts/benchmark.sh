#!/bin/bash

# 性能基准测试脚本
# 专门用于测试JDK 21在高频交易场景下的性能表现

echo "Loom Exchange 性能基准测试"
echo "=========================="
echo "测试JDK 21 ZGC分代 + 虚拟线程性能"
echo ""

# 创建结果目录
mkdir -p benchmark-results
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RESULT_DIR="benchmark-results/$TIMESTAMP"
mkdir -p $RESULT_DIR

# 基准测试JVM参数
BENCHMARK_OPTS=""
BENCHMARK_OPTS="$BENCHMARK_OPTS -XX:+UnlockExperimentalVMOptions"
BENCHMARK_OPTS="$BENCHMARK_OPTS -XX:+UseZGC"
BENCHMARK_OPTS="$BENCHMARK_OPTS -XX:+UseGenerationalZGC"
BENCHMARK_OPTS="$BENCHMARK_OPTS -Xmx8g -Xms8g"
BENCHMARK_OPTS="$BENCHMARK_OPTS -XX:+UseLargePages"
BENCHMARK_OPTS="$BENCHMARK_OPTS --enable-preview"

# 性能监控参数
BENCHMARK_OPTS="$BENCHMARK_OPTS -XX:+FlightRecorder"
BENCHMARK_OPTS="$BENCHMARK_OPTS -XX:StartFlightRecording=duration=300s,filename=$RESULT_DIR/benchmark.jfr"
BENCHMARK_OPTS="$BENCHMARK_OPTS -Xlog:gc*:$RESULT_DIR/gc.log:time,tags"

echo "开始基准测试..."
echo "结果将保存到: $RESULT_DIR"
echo ""

# 编译项目
echo "编译项目..."
mvn clean compile test-compile -q

if [ $? -ne 0 ]; then
    echo "编译失败!"
    exit 1
fi

echo "编译完成"
echo ""

# 运行基准测试
echo "执行性能基准测试..."
java $BENCHMARK_OPTS \
    -cp "target/classes:target/test-classes:$(mvn dependency:build-classpath -q -Dmdep.outputFile=/dev/stdout)" \
    org.junit.platform.console.ConsoleLauncher \
    --select-class=com.loom.exchange.benchmark.MatchingEngineBenchmark \
    --reports-dir=$RESULT_DIR \
    2>&1 | tee $RESULT_DIR/benchmark.log

echo ""
echo "基准测试完成!"
echo ""

# 生成性能报告
echo "生成性能报告..."

cat > $RESULT_DIR/performance-summary.md << EOF
# Loom Exchange 性能基准测试报告

**测试时间**: $(date)
**JDK版本**: $(java -version 2>&1 | head -n 1)
**测试环境**: $(uname -a)

## 测试配置

### JVM参数
\`\`\`
$BENCHMARK_OPTS
\`\`\`

### 硬件信息
- **CPU**: $(lscpu | grep "Model name" | cut -d: -f2 | xargs)
- **核心数**: $(nproc)
- **内存**: $(free -h | grep Mem | awk '{print $2}')

## 测试结果

详细测试日志请查看 \`benchmark.log\` 文件。

## 关键指标

### 延迟性能
- **目标**: P99延迟 < 100微秒
- **实际**: 请查看测试日志

### 吞吐量性能  
- **目标**: > 100万订单/秒
- **实际**: 请查看测试日志

### 虚拟线程扩展性
- **目标**: 支持10万+并发虚拟线程
- **实际**: 请查看测试日志

## ZGC分代性能
GC日志分析请查看 \`gc.log\` 文件。

## JFR分析
使用以下命令分析JFR文件:
\`\`\`bash
jfr print benchmark.jfr
\`\`\`

EOF

echo "性能报告已生成: $RESULT_DIR/performance-summary.md"
echo ""

# 简单的GC分析
if [ -f "$RESULT_DIR/gc.log" ]; then
    echo "GC统计信息:"
    echo "============"
    
    # 统计GC次数和时间
    YOUNG_GC_COUNT=$(grep -c "GC(.*) Pause Young" $RESULT_DIR/gc.log || echo "0")
    OLD_GC_COUNT=$(grep -c "GC(.*) Pause Old" $RESULT_DIR/gc.log || echo "0")
    
    echo "年轻代GC次数: $YOUNG_GC_COUNT"
    echo "老年代GC次数: $OLD_GC_COUNT"
    
    # 提取最大暂停时间
    MAX_PAUSE=$(grep -o "Pause.*[0-9]\+\.[0-9]\+ms" $RESULT_DIR/gc.log | \
               grep -o "[0-9]\+\.[0-9]\+ms" | \
               sed 's/ms//' | \
               sort -n | \
               tail -1)
    
    if [ ! -z "$MAX_PAUSE" ]; then
        echo "最大GC暂停时间: ${MAX_PAUSE}ms"
    fi
    
    echo ""
fi

echo "所有测试文件位于: $RESULT_DIR"
echo "测试完成!"
