// Copyright (c) 2019-present, iQIYI, Inc. All rights reserved.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
//

// Created by caikelun on 2019-03-07.

#ifndef XCD_FRAMES_H
#define XCD_FRAMES_H 1

#include <stdint.h>
#include <sys/types.h>
#include "xcd_regs.h"
#include "xcd_maps.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct xcd_frames xcd_frames_t;

/** 仅暴露回溯所需的相对地址与模块，不暴露原始栈或内存映射内容。 */
typedef struct {
    /** 帧在本线程中的顺序。 */ size_t index;
    /** 可能为空的帧所属模块。 */ xcd_map_t *map;
    /** 已归一到模块的相对程序计数器。 */ uintptr_t relative_pc;
    /** ELF 已有的可选函数名。 */ const char *function_name;
    /** 相对于函数的偏移。 */ size_t function_offset;
} xcd_frame_view_t;

/** 回调只在栈帧和模块对象的生命周期内使用视图。 */
typedef int (*xcd_frame_visitor_t)(const xcd_frame_view_t *frame, void *context);

int xcd_frames_create(xcd_frames_t **self, xcd_regs_t *regs, xcd_maps_t *maps, pid_t pid);
void xcd_frames_destroy(xcd_frames_t **self);

/** 按顺序访问最多 limit 帧，返回已观测的全部帧数。 */
size_t xcd_frames_visit(xcd_frames_t *self, size_t limit, xcd_frame_visitor_t visitor, void *context);

int xcd_frames_record_backtrace(xcd_frames_t *self, int log_fd);
int xcd_frames_record_buildid(xcd_frames_t *self, int log_fd, int dump_elf_hash, uintptr_t fault_addr);

#ifdef __cplusplus
}
#endif

#endif
