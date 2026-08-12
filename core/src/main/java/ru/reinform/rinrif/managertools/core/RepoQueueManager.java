package ru.reinform.rinrif.managertools.core;

import ru.reinform.rinrif.managertools.model.ApiModels.JobStatus;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class RepoQueueManager {
    private final JobsStore jobsStore;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, RepoQueue> queues = new ConcurrentHashMap<String, RepoQueue>();

    RepoQueueManager(JobsStore jobsStore) {
        this.jobsStore = jobsStore;
    }

    QueueTaskHandle enqueue(String repoId, String jobId, RunnableTask task) {
        RepoQueue queue = getOrCreateQueue(repoId);
        CompletableFuture<Void> completion = new CompletableFuture<Void>();
        QueueItem item = new QueueItem(jobId, task, completion);
        int queuePosition;
        synchronized (queue) {
            if (queue.active != null) {
                queue.pending.add(item);
                queuePosition = queue.pending.size();
                jobsStore.updateJob(jobId, JobStatus.queued, "Operation queued", queuePosition);
            } else {
                queue.active = item;
                queuePosition = 0;
                jobsStore.updateJob(jobId, JobStatus.running, "Operation is running", 0);
                runActive(repoId, queue);
            }
            refreshPendingPositions(queue);
        }
        return new QueueTaskHandle(queuePosition, completion);
    }

    QueueTaskHandle enqueue(String repoId, RunnableTask task) {
        RepoQueue queue = getOrCreateQueue(repoId);
        CompletableFuture<Void> completion = new CompletableFuture<Void>();
        QueueItem item = new QueueItem(null, task, completion);
        int queuePosition;
        synchronized (queue) {
            if (queue.active != null) {
                queue.pending.add(item);
                queuePosition = queue.pending.size();
            } else {
                queue.active = item;
                queuePosition = 0;
                runActive(repoId, queue);
            }
            refreshPendingPositions(queue);
        }
        return new QueueTaskHandle(queuePosition, completion);
    }

    private RepoQueue getOrCreateQueue(String repoId) {
        RepoQueue existing = queues.get(repoId);
        if (existing != null) {
            return existing;
        }
        RepoQueue created = new RepoQueue();
        RepoQueue previous = queues.putIfAbsent(repoId, created);
        return previous == null ? created : previous;
    }

    private void runActive(final String repoId, final RepoQueue queue) {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                QueueItem active;
                synchronized (queue) {
                    active = queue.active;
                }
                if (active == null) {
                    return;
                }
                try {
                    active.task.run();
                    active.completion.complete(null);
                } catch (Throwable error) {
                    // A linkage error (for example, an API mismatch in a runtime dependency)
                    // must never leave callers waiting forever. Complete first so the queue can
                    // advance even when the task failed outside the RuntimeException hierarchy.
                    active.completion.completeExceptionally(error);
                } finally {
                    synchronized (queue) {
                        queue.active = queue.pending.poll();
                        refreshPendingPositions(queue);
                        if (queue.active != null) {
                            if (queue.active.jobId != null) {
                                jobsStore.updateJob(queue.active.jobId, JobStatus.running, "Operation is running", 0);
                            }
                            runActive(repoId, queue);
                        } else {
                            queues.remove(repoId);
                        }
                    }
                }
            }
        });
    }

    private void refreshPendingPositions(RepoQueue queue) {
        int index = 1;
        for (QueueItem item : queue.pending) {
            if (item.jobId != null) {
                jobsStore.updateJob(item.jobId, JobStatus.queued, "Operation queued", index);
            }
            index++;
        }
    }

    interface RunnableTask {
        void run();
    }

    static class QueueTaskHandle {
        final int queuePosition;
        final CompletableFuture<Void> completion;

        QueueTaskHandle(int queuePosition, CompletableFuture<Void> completion) {
            this.queuePosition = queuePosition;
            this.completion = completion;
        }
    }

    private static class RepoQueue {
        QueueItem active;
        Queue<QueueItem> pending = new ArrayDeque<QueueItem>();
    }

    private static class QueueItem {
        final String jobId;
        final RunnableTask task;
        final CompletableFuture<Void> completion;

        QueueItem(String jobId, RunnableTask task, CompletableFuture<Void> completion) {
            this.jobId = jobId;
            this.task = task;
            this.completion = completion;
        }
    }
}
