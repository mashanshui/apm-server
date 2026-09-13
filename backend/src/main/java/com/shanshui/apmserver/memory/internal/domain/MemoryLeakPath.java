package com.shanshui.apmserver.memory.internal.domain;

import com.shanshui.apmserver.memory.api.MemoryLeakPathNode;
import java.util.List;

public record MemoryLeakPath(String signature, String gcRoot, String leakReason,
                             long instanceCount, List<MemoryLeakPathNode> path,
                             String contentHash) {
}
