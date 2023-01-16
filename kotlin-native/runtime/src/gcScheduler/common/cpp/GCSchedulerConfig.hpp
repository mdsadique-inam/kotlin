/*
 * Copyright 2010-2023 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#pragma once

#include <chrono>

#include "std_support/Optional.hpp"

namespace kotlin::gcScheduler {

// NOTE: When changing default values, reflect them in GC.kt as well.
struct GCSchedulerConfig {
    bool autoTune = true;
    // The target interval between collections when Kotlin code is idle. GC will be triggered
    // by timer no sooner than this value and no later than twice this value since the previous collection.
    int64_t regularGcIntervalMicroseconds = 10 * 1000 * 1000;
    // How many object bytes must be in the heap to trigger collection. Autotunes when autoTune is true.
    int64_t targetHeapBytes = 1024 * 1024;
    // The rate at which targetHeapBytes changes when autoTune = true. Concretely: if after the collection
    // N object bytes remain in the heap, the next targetHeapBytes will be N / targetHeapUtilization capped
    // between minHeapBytes and maxHeapBytes.
    double targetHeapUtilization = 0.5;
    // The minimum value of targetHeapBytes for autoTune = true
    int64_t minHeapBytes = 1024 * 1024;
    // The maximum value of targetHeapBytes for autoTune = true
    int64_t maxHeapBytes = std::numeric_limits<int64_t>::max();

    std::chrono::microseconds regularGcInterval() const { return std::chrono::microseconds(regularGcIntervalMicroseconds); }

    bool operator==(const GCSchedulerConfig& rhs) const noexcept {
        // TODO: Consider using memcpy. But careful around padding bytes.
        return
            this->autoTune == rhs.autoTune &&
            this->regularGcIntervalMicroseconds == rhs.regularGcIntervalMicroseconds &&
            this->targetHeapBytes == rhs.targetHeapBytes &&
            this->targetHeapUtilization == rhs.targetHeapUtilization &&
            this->minHeapBytes == rhs.minHeapBytes &&
            this->maxHeapBytes == rhs.maxHeapBytes;
    }

    bool operator!=(const GCSchedulerConfig& rhs) const noexcept {
        return !(*this == rhs);
    }

    void mergeAutotunedConfig(const GCSchedulerConfig& autoTunedConfig) noexcept {
        if (autoTune) {
            targetHeapBytes = autoTunedConfig.targetHeapBytes;
        }
    }
};

static_assert(std::is_trivially_copyable_v<GCSchedulerConfig>, "GCSchedulerConfig should be a simple struct.");

} // namespace kotlin::gcScheduler
