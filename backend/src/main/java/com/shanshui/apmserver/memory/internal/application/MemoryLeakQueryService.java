package com.shanshui.apmserver.memory.internal.application;

import com.shanshui.apmserver.memory.api.*;
import com.shanshui.apmserver.memory.internal.domain.MemoryLeakQueryFilter;
import com.shanshui.apmserver.memory.internal.port.MemoryLeakReportRepository;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** 生产只接收聚合页和有界趋势桶，不加载全部报告正文。 */
@Service
public class MemoryLeakQueryService {
    /** 事实查询适配器负责数据库聚合。 */
    private final MemoryLeakReportRepository repository;
    /** 注入事实查询端口。 */
    public MemoryLeakQueryService(MemoryLeakReportRepository repository) { this.repository = repository; }
    /** 页码偏移由仓储按 long 处理，全范围分母与当前页一次返回。 */
    public MemoryLeakIssuesResponse issues(MemoryLeakQueryFilter filter,int page,int pageSize,String sort,String order) {
        return repository.issues(filter,page,pageSize,sort,order);
    }
    /** 在任何仓储访问前拒绝超过 2000 桶的范围，仅在内存补齐有界空桶。 */
    public MemoryLeakTrendResponse trend(MemoryLeakQueryFilter filter,String interval) {
        long seconds = switch(interval) { case "5m" -> 300; case "hour" -> 3600; case "day" -> 86400;
            default -> throw new MemoryLeakReportValidationException("interval 不受支持"); };
        Instant first = Instant.ofEpochSecond(Math.floorDiv(filter.from().getEpochSecond(),seconds)*seconds);
        Duration span = Duration.between(first,filter.to());
        long count = Math.floorDiv(span.getSeconds(),seconds) + (span.getSeconds()%seconds != 0 || span.getNano()!=0 ? 1 : 0);
        if (count > 2000) throw new MemoryLeakReportValidationException("趋势桶数量超过 2000");
        List<MemoryLeakTrendPoint> nonempty = repository.trend(filter,seconds);
        Map<Instant,MemoryLeakTrendPoint> indexed = new HashMap<>();
        nonempty.forEach(point -> indexed.put(point.bucketStart(),point));
        List<MemoryLeakTrendPoint> points = new ArrayList<>();
        for (Instant bucket=first;bucket.isBefore(filter.to());bucket=bucket.plusSeconds(seconds)) {
            points.add(indexed.getOrDefault(bucket,new MemoryLeakTrendPoint(bucket,0,0)));
        }
        return new MemoryLeakTrendResponse(filter.appId(),filter.from(),filter.to(),interval,List.copyOf(points),
                nonempty.isEmpty()?"no_data":"ok",repository.dataSource());
    }
}
