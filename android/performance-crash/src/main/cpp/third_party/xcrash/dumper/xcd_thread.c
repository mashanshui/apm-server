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

#include <inttypes.h>
#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
#include <fcntl.h>
#include <time.h>
#include <ucontext.h>
#include <dirent.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <linux/elf.h>
#include "xcc_errno.h"
#include "xcc_util.h"
#include "xcd_thread.h"
#include "xcd_frames.h"
#include "xcd_regs.h"
#include "xcd_util.h"
#include "xcd_log.h"

void xcd_thread_init(xcd_thread_t *self, pid_t pid, pid_t tid)
{
    self->status = XCD_THREAD_STATUS_OK;
    self->pid    = pid;
    self->tid    = tid;
    self->tname  = NULL;
    self->frames = NULL;
    memset(&(self->regs), 0, sizeof(self->regs));
}

void xcd_thread_suspend(xcd_thread_t *self)
{
    if(0 != ptrace(PTRACE_ATTACH, self->tid, NULL, NULL))
    {
#if XCD_THREAD_DEBUG
        XCD_LOG_WARN("THREAD: ptrace ATTACH failed, errno=%d", errno);
#endif
        self->status = XCD_THREAD_STATUS_ATTACH;
        return;
    }

    errno = 0;
    while(waitpid(self->tid, NULL, __WALL) < 0)
    {
        if(EINTR != errno)
        {
            ptrace(PTRACE_DETACH, self->tid, NULL, NULL);
#if XCD_THREAD_DEBUG
            XCD_LOG_ERROR("THREAD: waitpid for ptrace ATTACH failed, errno=%d", errno);
#endif
            self->status = XCD_THREAD_STATUS_ATTACH_WAIT;
            return;
        }
        errno = 0;
    }
}

void xcd_thread_resume(xcd_thread_t *self)
{
    ptrace(PTRACE_DETACH, self->tid, NULL, NULL);
}

void xcd_thread_load_info(xcd_thread_t *self)
{
    char buf[64] = "\0";
    
    xcc_util_get_thread_name(self->tid, buf, sizeof(buf));
    if(NULL == (self->tname = strdup(buf))) self->tname = "unknown";
}

void xcd_thread_load_regs(xcd_thread_t *self)
{
    uintptr_t regs[64]; //big enough for all architectures
    size_t    regs_len;
    
#ifdef PTRACE_GETREGS
    if(0 != ptrace(PTRACE_GETREGS, self->tid, NULL, &regs))
    {
        XCD_LOG_ERROR("THREAD: ptrace GETREGS failed, errno=%d", errno);
        self->status = XCD_THREAD_STATUS_REGS;
        return;
    }
    regs_len = XCD_REGS_USER_NUM;
#else
    struct iovec iovec;
    iovec.iov_base = &regs;
    iovec.iov_len = sizeof(regs);
    if(0 != ptrace(PTRACE_GETREGSET, self->tid, (void *)NT_PRSTATUS, &iovec))
    {
        XCD_LOG_ERROR("THREAD: ptrace GETREGSET failed, errno=%d", errno);
        self->status = XCD_THREAD_STATUS_REGS;
        return;
    }
    regs_len = iovec.iov_len / sizeof(uintptr_t);
#endif
    xcd_regs_load_from_ptregs(&(self->regs), regs, regs_len);
}

void xcd_thread_load_regs_from_ucontext(xcd_thread_t *self, ucontext_t *uc)
{
    xcd_regs_load_from_ucontext(&(self->regs), uc);
}

int xcd_thread_load_frames(xcd_thread_t *self, xcd_maps_t *maps)
{
#if XCD_THREAD_DEBUG
    XCD_LOG_DEBUG("THREAD: load frames, tid=%d, tname=%s", self->tid, self->tname);
#endif

    if(XCD_THREAD_STATUS_OK != self->status) return XCC_ERRNO_STATE; //do NOT ignore

    return xcd_frames_create(&(self->frames), &(self->regs), maps, self->pid);
}

int xcd_thread_record_info(xcd_thread_t *self, int log_fd, const char *pname)
{
    return xcc_util_write_format(log_fd, "pid: %d, tid: %d, name: %s  >>> %s <<<\n",
                                 self->pid, self->tid, self->tname, pname);
}

int xcd_thread_record_regs(xcd_thread_t *self, int log_fd)
{
    if(XCD_THREAD_STATUS_OK != self->status) return 0; //ignore
    
    return xcd_regs_record(&(self->regs), log_fd);
}

int xcd_thread_record_backtrace(xcd_thread_t *self, int log_fd)
{
    if(XCD_THREAD_STATUS_OK != self->status) return 0; //ignore

    return xcd_frames_record_backtrace(self->frames, log_fd);
}

int xcd_thread_record_buildid(xcd_thread_t *self, int log_fd, int dump_elf_hash, uintptr_t fault_addr)
{
    if(XCD_THREAD_STATUS_OK != self->status) return 0; //ignore

    return xcd_frames_record_buildid(self->frames, log_fd, dump_elf_hash, fault_addr);
}
