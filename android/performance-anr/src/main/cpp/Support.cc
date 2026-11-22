/*
 * Tencent is pleased to support the open source community by making wechat-matrix available.
 * Copyright (C) 2021 THL A29 Limited, a Tencent company. All rights reserved.
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Author: leafjia@tencent.com
//
// Support.cpp

#include "Support.h"
#include <unistd.h>
#include <fcntl.h>

namespace MatrixTracer {

/** 按 Matrix 原实现关闭文件描述符。 */
void ScopedFileDescriptor::close_(int fd) {
    close(fd);
}

/** 按 Matrix 原实现读取下一行。 */
bool LineReader::getNextLine(const char **line, size_t *len) {
    for (;;) {
        if (mBufUsed == 0 && mHitEof)
            return false;

        for (unsigned i = 0; i < mBufUsed; ++i) {
            if (mBuf[i] == '\n' || mBuf[i] == 0) {
                mBuf[i] = 0;
                *len = i;
                *line = mBuf;
                return true;
            }
        }

        if (mBufUsed == sizeof(mBuf)) {
            // we scanned the whole buffer and didn't find an end-of-line marker.
            // This line is too long to process.
            return false;
        }

        // We didn't find any end-of-line terminators in the buffer. However, if
        // this is the last line in the file it might not have one:
        if (mHitEof) {
            assert(mBufUsed);
            // There's room for the NUL because of the mBufUsed == sizeof(mBuf)
            // check above.
            mBuf[mBufUsed] = 0;
            *len = mBufUsed;
            mBufUsed += 1;  // since we appended the NUL.
            *line = mBuf;
            return true;
        }

        // Otherwise, we should pull in more data from the file
        /** 本次文件读取的字节数。 */
        const ssize_t n = read(mFD, mBuf + mBufUsed,
                                   sizeof(mBuf) - mBufUsed);
        if (n < 0) {
            return false;
        } else if (n == 0) {
            mHitEof = true;
        } else {
            mBufUsed += n;
        }

        // At this point, we have either set the mHitEof flag, or we have more
        // data to process...
    }
}

/** 按 Matrix 原实现移除已处理行。 */
void LineReader::popLine(size_t len) {
    // len doesn't include the NUL byte at the end.

    assert(mBufUsed >= len + 1);
    mBufUsed -= len + 1;
    memmove(mBuf, mBuf + len + 1, mBufUsed);
}


namespace Support {

/** 按 Matrix 原实现处理被中断的读取。 */
ssize_t robustRead(const ScopedFileDescriptor &fd, void *buffer, size_t size) {
    /** Matrix 原实现使用的字节缓冲区。 */
    auto buf = static_cast<uint8_t *>(buffer);
    /** 已读取的字节总数。 */
    size_t total = 0;
    while (total < size) {
        /** 本次底层读取结果。 */
        ssize_t ret = read(fd.get(), buf + total, size - total);
        if (ret == 0) return total;
        if (ret == -1) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        total += ret;
    }
    return total;
}


/** 按 Matrix 原实现读取进程文件文本。 */
ssize_t readFileAsString(const char *filePath, char *buffer, size_t bufSize) {
    /** 进程文件描述符包装。 */
    ScopedFileDescriptor fd(open(filePath, O_RDONLY, 0));
    if (!fd.valid())
        return -1;

    /** Matrix 文件读取结果。 */
    ssize_t ret = Support::robustRead(fd, buffer, bufSize - 1);
    if (ret < 0)
        return ret;

    for (int i = 0; i < ret; i++) {
        if (buffer[i] == '\0')
            buffer[i] = ' ';
    }
    buffer[ret] = '\0';
    return ret;
}
}   // namespace Support
}   // namespace MatrixTracer
