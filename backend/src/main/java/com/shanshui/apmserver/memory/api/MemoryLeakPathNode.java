package com.shanshui.apmserver.memory.api;

public record MemoryLeakPathNode(String reference, String referenceType, String declaredClass) {
}
