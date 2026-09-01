package com.shanshui.apmserver.web;

import com.shanshui.apmserver.service.PayloadTooLargeException;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

public class LimitedInputStream extends FilterInputStream {

    private final long maxBytes;
    private final String limitMessage;
    private long bytesRead;

    public LimitedInputStream(InputStream inputStream, long maxBytes) {
        this(inputStream, maxBytes, "请求解压后超过大小上限");
    }

    public LimitedInputStream(InputStream inputStream, long maxBytes, String limitMessage) {
        super(inputStream);
        this.maxBytes = maxBytes;
        this.limitMessage = limitMessage;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value >= 0) {
            count(1);
        }
        return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        int count = super.read(bytes, offset, length);
        if (count > 0) {
            count(count);
        }
        return count;
    }

    private void count(int count) {
        bytesRead += count;
        if (bytesRead > maxBytes) {
            throw new PayloadTooLargeException(limitMessage);
        }
    }
}
