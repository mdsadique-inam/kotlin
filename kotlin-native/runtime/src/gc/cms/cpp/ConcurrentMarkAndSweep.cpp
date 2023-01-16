/*
 * Copyright 2010-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include "ConcurrentMarkAndSweep.hpp"

#include <cinttypes>

#include "CompilerConstants.hpp"
#include "GlobalData.hpp"
#include "GCImpl.hpp"
#include "Logging.hpp"
#include "MarkAndSweepUtils.hpp"
#include "Memory.h"
#include "RootSet.hpp"
#include "Runtime.h"
#include "ThreadData.hpp"
#include "ThreadRegistry.hpp"
#include "ThreadSuspension.hpp"
#include "GCState.hpp"
#include "FinalizerProcessor.hpp"
#include "GCStatistics.hpp"

#ifdef CUSTOM_ALLOCATOR
#include "Heap.hpp"
#endif

using namespace kotlin;

namespace {
    [[clang::no_destroy]] std::mutex markingMutex;
    [[clang::no_destroy]] std::condition_variable markingCondVar;
    [[clang::no_destroy]] std::atomic<bool> markingRequested = false;
    [[clang::no_destroy]] std::atomic<uint64_t> markingEpoch = 0;

struct SweepTraits {
    using ObjectFactory = mm::ObjectFactory<gc::ConcurrentMarkAndSweep>;
    using ExtraObjectsFactory = mm::ExtraObjectDataFactory;

    static bool IsMarkedByExtraObject(mm::ExtraObjectData &object) noexcept {
        auto *baseObject = object.GetBaseObject();
        if (!baseObject->heap()) return true;
        auto& objectData = mm::ObjectFactory<gc::ConcurrentMarkAndSweep>::NodeRef::From(baseObject).ObjectData();
        return objectData.marked();
    }

    static bool TryResetMark(ObjectFactory::NodeRef node) noexcept {
        auto& objectData = node.ObjectData();
        return objectData.tryResetMark();
    }
};

} // namespace

NO_EXTERNAL_CALLS_CHECK void gc::ConcurrentMarkAndSweep::ThreadData::OnSuspendForGC() noexcept {
    std::unique_lock lock(markingMutex);
    if (!markingRequested.load()) return;
    AutoReset scopedAssignMarking(&marking_, true);
    threadData_.Publish();
    markingCondVar.wait(lock, []() { return !markingRequested.load(); });
    // // Unlock while marking to allow mutliple threads to mark in parallel.
    lock.unlock();
    uint64_t epoch = markingEpoch.load();
    GCLogDebug(epoch, "Parallel marking in thread %d", konan::currentThreadId());
    MarkQueue markQueue;
    auto handle = GCHandle::getByEpoch(epoch);
    gc::collectRootSetForThread<internal::MarkTraits>(handle, markQueue, threadData_);
    gc::Mark<internal::MarkTraits>(handle, markQueue);
}

gc::ConcurrentMarkAndSweep::ConcurrentMarkAndSweep(
        mm::ObjectFactory<ConcurrentMarkAndSweep>& objectFactory) noexcept :
#ifndef CUSTOM_ALLOCATOR
    objectFactory_(objectFactory),
#endif
    finalizerProcessor_([](int64_t epoch) noexcept {
        GCHandle::getByEpoch(epoch).finalizersDone();
    }) {
    markingBehavior_ = kotlin::compiler::gcMarkSingleThreaded() ? MarkingBehavior::kDoNotMark : MarkingBehavior::kMarkOwnStack;
    RuntimeLogDebug({kTagGC}, "Concurrent Mark & Sweep GC initialized");
}

void gc::ConcurrentMarkAndSweep::StartFinalizerThreadIfNeeded() noexcept {
    NativeOrUnregisteredThreadGuard guard(true);
    finalizerProcessor_.StartFinalizerThreadIfNone();
    finalizerProcessor_.WaitFinalizerThreadInitialized();
}

void gc::ConcurrentMarkAndSweep::StopFinalizerThreadIfRunning() noexcept {
    NativeOrUnregisteredThreadGuard guard(true);
    finalizerProcessor_.StopFinalizerThread();
}

bool gc::ConcurrentMarkAndSweep::FinalizersThreadIsRunning() noexcept {
    return finalizerProcessor_.IsRunning();
}

void gc::ConcurrentMarkAndSweep::SetMarkingBehaviorForTests(MarkingBehavior markingBehavior) noexcept {
    markingBehavior_ = markingBehavior;
}

void gc::ConcurrentMarkAndSweep::RunGC(GCHandle& gcHandle) noexcept {
    SetMarkingRequested(gcHandle.getEpoch());
    bool didSuspend = mm::RequestThreadsSuspension();
    RuntimeAssert(didSuspend, "Only GC thread can request suspension");
    gcHandle.suspensionRequested();

    RuntimeAssert(!kotlin::mm::IsCurrentThreadRegistered(), "Concurrent GC must run on unregistered thread");
    WaitForThreadsReadyToMark();
    gcHandle.threadsAreSuspended();

#ifdef CUSTOM_ALLOCATOR
    heap_.PrepareForGC();
#endif

    gcHandle.started();

    CollectRootSetAndStartMarking(gcHandle);

    // Can be unsafe, because we've stopped the world.
    gc::Mark<internal::MarkTraits>(gcHandle, markQueue_);

    mm::WaitForThreadsSuspension();
    mm::ExtraObjectDataFactory& extraObjectDataFactory = mm::GlobalData::Instance().extraObjectDataFactory();

    gc::SweepExtraObjects<SweepTraits>(gcHandle, extraObjectDataFactory);

#ifndef CUSTOM_ALLOCATOR
    auto objectFactoryIterable = objectFactory_.LockForIter();
    mm::ResumeThreads();
    gcHandle.threadsAreResumed();
    auto finalizerQueue = gc::Sweep<SweepTraits>(gcHandle, objectFactoryIterable);
#else
    mm::ResumeThreads();
    gcHandle.threadsAreResumed();
    SweepTraits::ObjectFactory::FinalizerQueue finalizerQueue;
    heap_.Sweep();
#endif
    kotlin::compactObjectPoolInMainThread();
    gcHandle.finalizersScheduled(finalizerQueue.size());
    gcHandle.finished();
    finalizerProcessor_.ScheduleTasks(std::move(finalizerQueue), gcHandle.getEpoch());
}

namespace {
    bool isSuspendedOrNative(kotlin::mm::ThreadData& thread) noexcept {
        auto& suspensionData = thread.suspensionData();
        return suspensionData.suspended() || suspensionData.state() == kotlin::ThreadState::kNative;
    }

    template <typename F>
    bool allThreads(F predicate) noexcept {
        auto& threadRegistry = kotlin::mm::ThreadRegistry::Instance();
        auto* currentThread = (threadRegistry.IsCurrentThreadRegistered()) ? threadRegistry.CurrentThreadData() : nullptr;
        kotlin::mm::ThreadRegistry::Iterable threads = kotlin::mm::ThreadRegistry::Instance().LockForIter();
        for (auto& thread : threads) {
            // Handle if suspension was initiated by the mutator thread.
            if (&thread == currentThread) continue;
            if (!predicate(thread)) {
                return false;
            }
        }
        return true;
    }

    void yield() noexcept {
        std::this_thread::yield();
    }
} // namespace

void gc::ConcurrentMarkAndSweep::SetMarkingRequested(uint64_t epoch) noexcept {
    markingRequested = markingBehavior_ == MarkingBehavior::kMarkOwnStack;
    markingEpoch = epoch;
}

void gc::ConcurrentMarkAndSweep::WaitForThreadsReadyToMark() noexcept {
    while(!allThreads([](kotlin::mm::ThreadData& thread) { return isSuspendedOrNative(thread) || thread.gc().impl().gc().marking_.load(); })) {
        yield();
    }
}

NO_EXTERNAL_CALLS_CHECK void gc::ConcurrentMarkAndSweep::CollectRootSetAndStartMarking(GCHandle gcHandle) noexcept {
        std::unique_lock lock(markingMutex);
        markingRequested = false;
        gc::collectRootSet<internal::MarkTraits>(
                gcHandle,
                markQueue_,
                [](mm::ThreadData& thread) {
                    return !thread.gc().impl().gc().marking_.load();
                }
            );
        RuntimeLogDebug({kTagGC}, "Requesting marking in threads");
        markingCondVar.notify_all();
}
