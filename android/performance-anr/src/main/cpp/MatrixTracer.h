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
// MatrixTracer.h

#ifndef LAGDETECTOR_LAG_DETECTOR_MAIN_CPP_MatrixTracer_H_
#define LAGDETECTOR_LAG_DETECTOR_MAIN_CPP_MatrixTracer_H_

#include <cstdint>
#include <string>

std::string anrDumpCallback();
bool anrDumpTraceCallback(const std::string& captureId, const std::string& path, bool truncated);
bool nativeBacktraceDumpCallback();
bool printTraceCallback(const std::string& path, bool truncated);
uint64_t beginTraceCapture();
bool takePrintTraceRequest();
void hookAnrTraceWrite(bool isSigUser, uint64_t ticket, const std::string& captureId);
void onTouchEventLag(int fd);
void onTouchEventLagDumpTrace(int fd);
#endif  // LAGDETECTOR_LAG_DETECTOR_MAIN_CPP_MatrixTracer_H_
