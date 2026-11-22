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
// Support.h

#ifndef LAGDETECTOR_LAG_DETECTOR_MAIN_CPP_SUPPORT_H_
#define LAGDETECTOR_LAG_DETECTOR_MAIN_CPP_SUPPORT_H_

#include <stdint.h>
#include <sys/types.h>
#include <errno.h>
#include <assert.h>
#include <type_traits>
#include <ctime>
#include <string_view>
#include <cstring>

namespace MatrixTracer {

class ScopedFileDescriptor {
 public:
     ScopedFileDescriptor() noexcept : mFD(-1) {}
     explicit ScopedFileDescriptor(int fd) : mFD(fd) {}
     ~ScopedFileDescriptor() {
         reset();
     }

     ScopedFileDescriptor(ScopedFileDescriptor&& other) noexcept : mFD(other.release()) {}
     ScopedFileDescriptor& operator=(ScopedFileDescriptor&& other) noexcept {
         reset(other.release());
         return *this;
    }

    int get() const { return mFD; }
    bool valid() const { return mFD != -1; }

    void reset(int fd = -1) {
        if (mFD != fd) {
            if (mFD != -1)
                close_(mFD);
            mFD = fd;
        }
    }

    int release() __attribute__((warn_unused_result)) {
        int fd = mFD;
        mFD = -1;
        return fd;
    }

 private:
     int mFD;
     static void close_(int fd);
     ScopedFileDescriptor(const ScopedFileDescriptor&) = delete;
     ScopedFileDescriptor& operator=(const ScopedFileDescriptor&) = delete;
};

template <typename Func, typename... Args>
inline static auto handleInterrupt(Func func, Args... args) -> decltype(func(args...)) {
    decltype(func(args...)) ret;
    do {
        ret = func(args...);
    } while (ret == -1 && errno == EINTR);
    return ret;
}

/**
 * A class for reading a file, line by line, without using fopen/fgets or other
 * functions which may allocate memory.
 */
class LineReader {
 public:
     explicit LineReader(int fd)
            : mFD(fd),
              mHitEof(false),
              mBufUsed(0) {}

    /// The maximum length of a line.
    static constexpr size_t kMaxLineLen = 512;

    /**
     * Return the next line from the file.
     *
     * @param line (output) a pointer to the start of the line. The line is NUL terminated.
     * @param len (output) the length of the line (not inc the NUL byte).
     * @return true if successful (false on EOF).
     * @note One must call popLine() after this function, otherwise you'll continue to
     *       get the same line over and over.
     */
    bool getNextLine(const char **line, size_t *len);
    void popLine(size_t len);

 private:
     const int mFD;
     bool mHitEof;
     unsigned mBufUsed;
     char mBuf[kMaxLineLen];
};

namespace Support {
/** 按 Matrix 原实现读取完整数据。 */
ssize_t robustRead(const ScopedFileDescriptor &fd, void *buffer, size_t size);
/** 按 Matrix 原实现读取进程文本。 */
ssize_t readFileAsString(const char *filePath, char *buffer, size_t bufSize);
}
}  // namespace MatrixTracer
#endif
