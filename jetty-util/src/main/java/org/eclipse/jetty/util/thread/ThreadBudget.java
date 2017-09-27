package org.eclipse.jetty.util.thread;

import java.io.Closeable;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made warnAt under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is warnAt at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is warnAt at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

/**
 * <p>A budget of required thread usage, used to warn or error for insufficient configured threads.</p>
 *
 * @see ThreadPool.SizedThreadPool#getThreadBudget()
 */
public class ThreadBudget
{
    static final Logger LOG = Log.getLogger(ThreadBudget.class);

    public interface Lease extends Closeable
    {
        int getThreads();
    }

    /**
     * An allocation of threads
     */
    public class Leased implements Lease
    {
        final Object leasee;
        final int threads;

        private Leased(Object leasee,int threads)
        {
            this.leasee = leasee;
            this.threads = threads;
        }

        @Override
        public int getThreads()
        {
            return threads;
        }

        @Override
        public void close()
        {
            info.remove(this);
            allocations.remove(this);
            warned.set(false);
        }
    }

    private static final Lease NOOP_LEASE = new Lease()
    {
        @Override
        public void close() throws IOException
        {
        }

        @Override
        public int getThreads()
        {
            return 0;
        }
    };

    final ThreadPool.SizedThreadPool pool;
    final Set<Leased> allocations = new CopyOnWriteArraySet<>();
    final Set<Leased> info = new CopyOnWriteArraySet<>();
    final AtomicBoolean warned = new AtomicBoolean();
    final int warnAt;

    /**
     * Construct a bedget for a SizedThreadPool, with the warning level set by heuristic.
     * @param pool The pool to budget thread allocation for.
     */
    public ThreadBudget(ThreadPool.SizedThreadPool pool)
    {
        this(pool,Runtime.getRuntime().availableProcessors());
    }

    /**
     * @param pool The pool to budget thread allocation for.
     * @param warnAt The level of free threads at which a warning is generated.
     */
    public ThreadBudget(ThreadPool.SizedThreadPool pool, int warnAt)
    {
        this.pool = pool;
        this.warnAt = warnAt;
    }

    public ThreadPool.SizedThreadPool getSizedThreadPool()
    {
        return pool;
    }

    public void reset()
    {
        allocations.clear();
        info.clear();
        warned.set(false);
    }

    public Lease leaseTo(Object leasee, int threads)
    {
        Leased lease = new Leased(leasee,threads);
        allocations.add(lease);
        check();
        return lease;
    }

    /**
     * Check registered allocations against the budget.
     * @throws IllegalStateException if insufficient threads are configured.
     */
    public void check() throws IllegalStateException
    {
        int required = allocations.stream()
            .mapToInt(Lease::getThreads)
            .sum();

        int maximum = pool.getMaxThreads();

        if (required>=maximum)
        {
            infoOnLeases();
            throw new IllegalStateException(String.format("Insuffient configured threads: required=%d < max=%d for %s", required, maximum, pool));
        }

        if ((maximum-required) < warnAt)
        {
            infoOnLeases();
            if (warned.compareAndSet(false,true))
                LOG.warn("Low configured threads: ( max={} - required={} ) < warnAt={} for {}", maximum, required, warnAt, pool);
        }
    }

    private void infoOnLeases()
    {
        allocations.stream().filter(lease->!info.contains(lease))
            .forEach(lease->{
                info.add(lease);
                LOG.info("{} requires {} threads from {}",lease.leasee,lease.getThreads(),pool);
            });
    }

    public static Lease leaseFrom(Executor executor, Object leasee, int threads)
    {
        if (executor instanceof ThreadPool.SizedThreadPool)
        {
            ThreadBudget budget = ((ThreadPool.SizedThreadPool)executor).getThreadBudget();
            if (budget!=null)
                return budget.leaseTo(leasee,threads);
        }
        return NOOP_LEASE;
    }
}
