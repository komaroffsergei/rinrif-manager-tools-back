package ru.reinform.rinrif.managertools.core;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class RepoQueueManagerTest {
    @Test
    public void serializesCompleteRepositoryReadPhases() throws Exception {
        RepoQueueManager manager = new RepoQueueManager(null);
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final AtomicBoolean secondStarted = new AtomicBoolean(false);

        RepoQueueManager.QueueTaskHandle first = manager.enqueue("repo", new RepoQueueManager.RunnableTask() {
            @Override
            public void run() {
                firstStarted.countDown();
                try {
                    releaseFirst.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(error);
                }
            }
        });
        Assert.assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
        RepoQueueManager.QueueTaskHandle second = manager.enqueue("repo", new RepoQueueManager.RunnableTask() {
            @Override
            public void run() {
                secondStarted.set(true);
            }
        });

        Thread.sleep(100L);
        Assert.assertFalse(secondStarted.get());
        releaseFirst.countDown();
        first.completion.get(2, TimeUnit.SECONDS);
        second.completion.get(2, TimeUnit.SECONDS);
        Assert.assertTrue(secondStarted.get());
    }

    @Test
    public void errorCompletesFutureAndAdvancesRepositoryQueue() throws Exception {
        RepoQueueManager manager = new RepoQueueManager(null);
        final CountDownLatch failingStarted = new CountDownLatch(1);
        final CountDownLatch releaseFailure = new CountDownLatch(1);
        final AtomicBoolean nextRan = new AtomicBoolean(false);

        RepoQueueManager.QueueTaskHandle failing = manager.enqueue("repo-error", new RepoQueueManager.RunnableTask() {
            @Override
            public void run() {
                failingStarted.countDown();
                try {
                    releaseFailure.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
                throw new AssertionError("simulated linkage error");
            }
        });
        Assert.assertTrue(failingStarted.await(2, TimeUnit.SECONDS));
        RepoQueueManager.QueueTaskHandle next = manager.enqueue("repo-error", new RepoQueueManager.RunnableTask() {
            @Override
            public void run() {
                nextRan.set(true);
            }
        });

        releaseFailure.countDown();
        try {
            failing.completion.get(2, TimeUnit.SECONDS);
            Assert.fail("Error must complete the future exceptionally");
        } catch (ExecutionException expected) {
            Assert.assertTrue(expected.getCause() instanceof AssertionError);
        }
        next.completion.get(2, TimeUnit.SECONDS);
        Assert.assertTrue(nextRan.get());
    }
}
