@echo off
REM 性能基准测试批处理脚本 (Windows)
REM 专门用于测试JDK 21在高频交易场景下的性能表现

echo Loom Exchange 性能基准测试
echo ==========================
echo 测试JDK 21 ZGC分代 + 虚拟线程性能
echo.

REM 创建结果目录
if not exist benchmark-results mkdir benchmark-results
for /f "delims=" %%i in ('powershell -command "Get-Date -Format 'yyyyMMdd_HHmmss'"') do set TIMESTAMP=%%i
set RESULT_DIR=benchmark-results\%TIMESTAMP%
mkdir %RESULT_DIR%

REM 基准测试JVM参数
set BENCHMARK_OPTS=-XX:+UnlockExperimentalVMOptions
set BENCHMARK_OPTS=%BENCHMARK_OPTS% -XX:+UseZGC
set BENCHMARK_OPTS=%BENCHMARK_OPTS% -XX:+UseGenerationalZGC
set BENCHMARK_OPTS=%BENCHMARK_OPTS% -Xmx8g -Xms8g
set BENCHMARK_OPTS=%BENCHMARK_OPTS% --enable-preview

REM 性能监控参数
set BENCHMARK_OPTS=%BENCHMARK_OPTS% -XX:+FlightRecorder
set BENCHMARK_OPTS=%BENCHMARK_OPTS% -XX:StartFlightRecording=duration=300s,filename=%RESULT_DIR%\benchmark.jfr
set BENCHMARK_OPTS=%BENCHMARK_OPTS% -Xlog:gc*:%RESULT_DIR%\gc.log:time,tags

echo 开始基准测试...
echo 结果将保存到: %RESULT_DIR%
echo.

REM 检查Maven是否可用
where mvn >nul 2>nul
if errorlevel 1 (
    echo 警告: 未找到Maven，跳过编译步骤
    echo 请确保项目已编译完成
    echo.
) else (
    echo 编译项目...
    mvn clean compile test-compile -q
    if errorlevel 1 (
        echo 编译失败!
        pause
        exit /b 1
    )
    echo 编译完成
    echo.
)

echo 执行性能基准测试...

REM 如果有Maven，使用Maven运行测试
where mvn >nul 2>nul
if not errorlevel 1 (
    mvn test -Dtest=MatchingEngineBenchmark %BENCHMARK_OPTS% > %RESULT_DIR%\benchmark.log 2>&1
) else (
    echo 请手动运行基准测试或安装Maven
    echo java %BENCHMARK_OPTS% -cp "target\classes;target\test-classes" org.junit.platform.console.ConsoleLauncher --select-class=com.loom.exchange.benchmark.MatchingEngineBenchmark
)

echo.
echo 基准测试完成!
echo.

REM 生成性能报告
echo 生成性能报告...

echo # Loom Exchange 性能基准测试报告 > %RESULT_DIR%\performance-summary.md
echo. >> %RESULT_DIR%\performance-summary.md
for /f "delims=" %%i in ('date /t') do echo **测试时间**: %%i >> %RESULT_DIR%\performance-summary.md
for /f "delims=" %%i in ('java -version 2^>^&1 ^| findstr /C:"version"') do echo **JDK版本**: %%i >> %RESULT_DIR%\performance-summary.md
echo **测试环境**: Windows >> %RESULT_DIR%\performance-summary.md
echo. >> %RESULT_DIR%\performance-summary.md
echo ## 测试配置 >> %RESULT_DIR%\performance-summary.md
echo. >> %RESULT_DIR%\performance-summary.md
echo ### JVM参数 >> %RESULT_DIR%\performance-summary.md
echo ``` >> %RESULT_DIR%\performance-summary.md
echo %BENCHMARK_OPTS% >> %RESULT_DIR%\performance-summary.md
echo ``` >> %RESULT_DIR%\performance-summary.md
echo. >> %RESULT_DIR%\performance-summary.md
echo 详细测试日志请查看 benchmark.log 文件。 >> %RESULT_DIR%\performance-summary.md

echo 性能报告已生成: %RESULT_DIR%\performance-summary.md
echo.

REM 简单的GC分析
if exist %RESULT_DIR%\gc.log (
    echo GC统计信息:
    echo ============
    
    REM 统计GC次数
    for /f %%i in ('findstr /C:"Pause Young" %RESULT_DIR%\gc.log ^| find /C /V ""') do set YOUNG_GC_COUNT=%%i
    for /f %%i in ('findstr /C:"Pause Old" %RESULT_DIR%\gc.log ^| find /C /V ""') do set OLD_GC_COUNT=%%i
    
    echo 年轻代GC次数: %YOUNG_GC_COUNT%
    echo 老年代GC次数: %OLD_GC_COUNT%
    echo.
)

echo 所有测试文件位于: %RESULT_DIR%
echo 测试完成!
pause
