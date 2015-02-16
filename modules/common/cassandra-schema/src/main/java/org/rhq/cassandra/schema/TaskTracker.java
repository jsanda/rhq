package org.rhq.cassandra.schema;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * @author John Sanda
 */
class TaskTracker {

    private volatile int remainingTasks;

    private volatile boolean schedulingFinished;

    private ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private CountDownLatch allTasksFinished = new CountDownLatch(1);

    private volatile boolean aborted;

    private String errorMessage;

    /**
     * Increases the count of remaining tasks.
     */
    public void addTask() {
        try {
            lock.writeLock().lock();
            remainingTasks++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * This method is intended primarily for debugging purposes to log the progress. While other methods in this class
     * obtain a read or write lock, this method intentionally does not. There is no need to impose the locking overhead
     * since this method only reads a single variable that is a volatile.
     *
     * @return The number of remaining or outstanding tasks to be completed
     */
    public int getRemainingTasks() {
        return remainingTasks;
    }

    /**
     * Should be called by the producer when it has finished scheduling tasks. Moreover the producer must invoke this
     * method before it invokes {@link #waitForTasksToFinish()}. Failure to do so will cause the producer to block
     * indefinitely.
     */
    public void finishedSchedulingTasks() {
        try {
            lock.writeLock().lock();
            schedulingFinished = true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Should be invoked by a consumer when it completes a task.
     */
    public void finishedTask() {
        try {
            lock.writeLock().lock();
            remainingTasks--;
            if (schedulingFinished && remainingTasks == 0) {
                allTasksFinished.countDown();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Should be invoked by the producer <strong>only</strong> after it has invoked {@link #finishedSchedulingTasks()}.
     * If this method gets invoked first, the producer will block indefinitely. This method will block until all tasks
     * have completed. If all tasks have already completed, this method returns immediately.
     *
     * @throws InterruptedException If the producer thread is interrupted while waiting
     * @throws AbortedException If task processing has been abort which is accomplished by calling
     * {@link #abort(String)}
     */
    public void waitForTasksToFinish() throws InterruptedException, AbortedException {
        try {
            lock.readLock().lock();
            if (aborted) {
                throw new AbortedException(errorMessage);
            }
            if (remainingTasks == 0) {
                return;
            }
        } finally {
            lock.readLock().unlock();
        }
        allTasksFinished.await();
        try {
            lock.readLock().lock();
            if (aborted) {
                throw new AbortedException(errorMessage);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Should be invoked by a consumer to abort processing of any future tasks.
     *
     * @param msg An error message that will be included in the {@link AbortedException} thrown by
     * {@link #waitForTasksToFinish()}
     */
    public void abort(String msg) {
        try {
            lock.writeLock().lock();
            errorMessage = msg;
            aborted = true;
            allTasksFinished.countDown();
        } finally {
            lock.writeLock().unlock();
        }
    }

}
