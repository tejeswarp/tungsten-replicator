/**
 * Tungsten Scale-Out Stack
 * Copyright (C) 2010-2011 Continuent Inc.
 * Contact: tungsten@continuent.org
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of version 2 of the GNU General Public License as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA
 *
 * Initial developer(s): Robert Hodges
 * Contributor(s):
 */

package com.continuent.tungsten.replicator.thl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.log4j.Logger;

import com.continuent.tungsten.commons.config.TungstenProperties;
import com.continuent.tungsten.replicator.ReplicatorException;
import com.continuent.tungsten.replicator.event.DBMSEmptyEvent;
import com.continuent.tungsten.replicator.event.DBMSEvent;
import com.continuent.tungsten.replicator.event.ReplControlEvent;
import com.continuent.tungsten.replicator.event.ReplDBMSEvent;
import com.continuent.tungsten.replicator.event.ReplDBMSHeader;
import com.continuent.tungsten.replicator.event.ReplEvent;
import com.continuent.tungsten.replicator.event.ReplOptionParams;
import com.continuent.tungsten.replicator.plugin.PluginContext;
import com.continuent.tungsten.replicator.storage.ParallelStore;
import com.continuent.tungsten.replicator.storage.parallel.Partitioner;
import com.continuent.tungsten.replicator.storage.parallel.PartitionerResponse;
import com.continuent.tungsten.replicator.storage.parallel.SimplePartitioner;
import com.continuent.tungsten.replicator.util.AtomicCounter;
import com.continuent.tungsten.replicator.util.WatchPredicate;

/**
 * Implements an parallel event store. This queue has no memory beyond its
 * current contents.
 * 
 * @author <a href="mailto:robert.hodges@continuent.com">Robert Hodges</a>
 * @version 1.0
 */
public class THLParallelQueue implements ParallelStore
{
    private static Logger             logger              = Logger.getLogger(THLParallelQueue.class);

    // Queue parameters.
    private String                    name;
    private int                       maxSize             = 100;
    private int                       maxControlEvents    = 1000;
    private int                       partitions          = 1;
    private boolean                   syncEnabled         = true;
    private int                       syncInterval        = 2000;
    private int                       maxCriticalSections = 1000;
    private String                    thlStoreName        = "thl";

    // THL for which we are implementing a parallel queue.
    private THL                       thl;

    // Read task control information.
    private List<THLParallelReadTask> readTasks;
    private ReplDBMSHeader[]          lastHeaders;
    private ReplDBMSEvent             lastInsertedEvent;

    // Counter of head sequence number.
    private AtomicCounter             headSeqnoCounter    = new AtomicCounter(0);

    // Class to describe critical sections.
    class CriticalSection
    {
        int  partition;
        long startSeqno;
        long endSeqno;

        CriticalSection(int partition, long startSeqno, long endSeqno)
        {
            this.partition = partition;
            this.startSeqno = startSeqno;
            this.endSeqno = endSeqno;
        }
    }

    // Queue of pending critical sections.
    private BlockingQueue<CriticalSection>                      criticalSections;
    CriticalSection                                             pendingCriticalSection;

    // Partitioner configuration variables.
    private Partitioner                                         partitioner;
    private String                                              partitionerClass   = SimplePartitioner.class
                                                                                           .getName();
    private long                                                transactionCount   = 0;
    private long                                                serializationCount = 0;
    private long                                                discardCount       = 0;

    // Queue for predicates belonging to pending wait synchronization requests.
    private LinkedBlockingQueue<WatchPredicate<ReplDBMSHeader>> watchPredicates;

    // Flag to insert stop synchronization event at next transaction boundary.
    private boolean                                             stopRequested      = false;

    // Counter to force synchronization events at intervals so all queues remain
    // up-to-date.
    private int                                                 syncCounter        = 1;

    // Control information for event serialization to support shard processing.
    private int                                                 criticalPartition  = -1;

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    /** Maximum size of individual queues. */
    public int getMaxSize()
    {
        return maxSize;
    }

    public void setMaxSize(int size)
    {
        this.maxSize = size;
    }

    /** Sets the number of queue partitions. */
    public void setPartitions(int partitions)
    {
        this.partitions = partitions;
    }

    /** Returns the number of partitions for events. */
    public int getPartitions()
    {
        return partitions;
    }

    public Partitioner getPartitioner()
    {
        return partitioner;
    }

    public void setPartitioner(Partitioner partitioner)
    {
        this.partitioner = partitioner;
    }

    public String getPartitionerClass()
    {
        return partitionerClass;
    }

    public void setPartitionerClass(String partitionerClass)
    {
        this.partitionerClass = partitionerClass;
    }

    /** Returns the number of events between sync intervals. */
    public int getSyncInterval()
    {
        return syncInterval;
    }

    /**
     * Sets the number of events to process before generating an automatic
     * control event if sync is enabled.
     */
    public void setSyncInterval(int syncInterval)
    {
        this.syncInterval = syncInterval;
    }

    /**
     * Returns true if automatic control events for synchronization are enabled.
     */
    public boolean isSyncEnabled()
    {
        return syncEnabled;
    }

    /**
     * Enables/disables automatic generation of control events to ensure queue
     * consumers have up-to-date positions in the log.
     * 
     * @param syncEnabled If true sync control events are generated
     */
    public void setSyncEnabled(boolean syncEnabled)
    {
        this.syncEnabled = syncEnabled;
    }

    /** Sets the last header processed. This is required for restart. */
    public void setLastHeader(int taskId, ReplDBMSHeader header)
            throws ReplicatorException
    {
        assertTaskIdWithinRange(taskId);
        lastHeaders[taskId] = header;
    }

    /** Returns the last header processed. */
    public ReplDBMSHeader getLastHeader(int taskId) throws ReplicatorException
    {
        assertTaskIdWithinRange(taskId);
        return lastHeaders[taskId];
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.continuent.tungsten.replicator.storage.Store#getMaxStoredSeqno()
     */
    public long getMaxStoredSeqno()
    {
        return 0;
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.continuent.tungsten.replicator.storage.Store#getMinStoredSeqno()
     */
    public long getMinStoredSeqno()
    {
        return 0;
    }

    /**
     * Puts an event in the queue, blocking if it is full.)
     */
    public synchronized void put(int taskId, ReplDBMSEvent event)
            throws InterruptedException, ReplicatorException
    {
        boolean needsSync = false;
        if (logger.isDebugEnabled())
        {
            logger.debug("Received event: seqno=" + event.getSeqno()
                    + " fragno=" + event.getFragno() + " lastFrag="
                    + event.getLastFrag());
        }

        // Update transaction count at end.
        if (event.getLastFrag())
            transactionCount++;

        // Discard empty events.
        DBMSEvent dbmsEvent = event.getDBMSEvent();
        if (dbmsEvent == null | dbmsEvent instanceof DBMSEmptyEvent
                || dbmsEvent.getData().size() == 0)
        {
            discardCount++;
            return;
        }

        // Partition the event.
        PartitionerResponse response = partitioner.partition(event, taskId);
        if (response.isCritical())
        {
            serializationCount++;

            if (pendingCriticalSection == null)
            {
                // Case 1: A critical section is starting.
                pendingCriticalSection = new CriticalSection(
                        response.getPartition(), event.getSeqno(),
                        event.getSeqno());
            }
            else if (pendingCriticalSection.partition == response
                    .getPartition())
            {
                // Case 2: We are continuing in a critical section. Mark the
                // current seqno.
                pendingCriticalSection.endSeqno = event.getSeqno();
            }
            else
            {
                // Case 3: We are switching between critical sections. Enqueue
                // previous critical section and start a new one.
                criticalSections.put(pendingCriticalSection);
                pendingCriticalSection = new CriticalSection(
                        response.getPartition(), event.getSeqno(),
                        event.getSeqno());
            }
        }
        else
        {
            if (pendingCriticalSection == null)
            {
                // Case 4: Not in a critical section. Just advance the counter.
            }
            else
            {
                // Case 5: Critical section has ended and we are back to
                // non-critical processing. Enqueue critical section and advance
                // counter.
                criticalSections.put(pendingCriticalSection);
                pendingCriticalSection = null;
            }
        }

        // Advance the head seqno counter. This allows all eligible threads to
        // advance.
        if (logger.isDebugEnabled())
        {
            logger.debug("Updating head seqno counter: seqno="
                    + event.getSeqno());
        }
        headSeqnoCounter.setSeqno(event.getSeqno());

        // Record last event handled.
        lastInsertedEvent = event;

        // Fulfill stop request if we have one.
        if (event.getLastFrag() && stopRequested)
        {
            putControlEvent(ReplControlEvent.STOP, event);
            stopRequested = false;
            if (logger.isDebugEnabled())
            {
                logger.debug("Added stop control event after log event: seqno="
                        + event.getSeqno());
            }
        }

        // If we have pending predicate matches, try to fulfill them as well.
        if (event.getLastFrag() && watchPredicates.size() > 0)
        {
            // Scan for matches and add control events for each.
            List<WatchPredicate<ReplDBMSHeader>> removeList = new ArrayList<WatchPredicate<ReplDBMSHeader>>();
            for (WatchPredicate<ReplDBMSHeader> predicate : watchPredicates)
            {
                if (predicate.match(event))
                {
                    needsSync = true;
                    removeList.add(predicate);
                }
            }

            // Remove matching predicates.
            watchPredicates.removeAll(removeList);
        }

        // See if we need to send a sync event.
        if (syncEnabled && syncCounter >= syncInterval)
        {
            needsSync = true;
            syncCounter = 1;
        }
        else
            syncCounter++;

        // Even if we are not waiting for a heartbeat, these should always
        // generate a sync control event to ensure all tasks receive it.
        if (!needsSync
                && event.getDBMSEvent().getMetadataOptionValue(
                        ReplOptionParams.HEARTBEAT) != null)
        {
            needsSync = true;
        }

        // Now generate a sync event if we need one.
        if (needsSync)
        {
            putControlEvent(ReplControlEvent.SYNC, event);
            if (logger.isDebugEnabled())
            {
                logger.debug("Added sync control event after log event: seqno="
                        + event.getSeqno());
            }
        }
    }

    // Inserts a control event in all queues.
    private void putControlEvent(int type, ReplDBMSEvent event)
            throws InterruptedException
    {
        long ctrlSeqno;
        if (event == null)
            ctrlSeqno = this.headSeqnoCounter.getSeqno();
        else
            ctrlSeqno = event.getSeqno();
        ReplControlEvent ctrl = new ReplControlEvent(type, ctrlSeqno, event);

        if (logger.isDebugEnabled())
        {
            logger.debug("Inserting control event: type=" + type + " seqno="
                    + ctrlSeqno);
        }

        for (THLParallelReadTask readTask : this.readTasks)
        {
            readTask.putControlEvent(ctrl);
        }
    }

    /**
     * Removes and returns next event from the queue, blocking if empty.
     */
    public ReplEvent get(int taskId) throws InterruptedException,
            ReplicatorException
    {
        assertTaskIdWithinRange(taskId);
        return readTasks.get(taskId).get();
    }

    /**
     * Returns next event from the queue without removing it, returning null if
     * queue is empty.
     */
    public ReplEvent peek(int taskId) throws InterruptedException,
            ReplicatorException
    {
        assertTaskIdWithinRange(taskId);
        return readTasks.get(taskId).peek();
    }

    /**
     * Returns the current queue size.
     */
    public int size(int taskId)
    {
        return readTasks.get(taskId).size();
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.continuent.tungsten.replicator.plugin.ReplicatorPlugin#configure(com.continuent.tungsten.replicator.plugin.PluginContext)
     */
    public synchronized void configure(PluginContext context)
            throws ReplicatorException
    {

        // Instantiate partitioner class.
        if (partitioner == null)
        {
            try
            {
                partitioner = (Partitioner) Class.forName(partitionerClass)
                        .newInstance();
                partitioner.setPartitions(partitions);
            }
            catch (Exception e)
            {
                throw new ReplicatorException(
                        "Unable to instantiated partitioner: class="
                                + partitionerClass, e);
            }
        }

        // Allocate queue for watch predicates.
        // TODO: Get this to work.
        watchPredicates = new LinkedBlockingQueue<WatchPredicate<ReplDBMSHeader>>();

        // Allocate queue for critical sections.
        criticalSections = new LinkedBlockingQueue<CriticalSection>(
                maxCriticalSections);
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.continuent.tungsten.replicator.plugin.ReplicatorPlugin#prepare(com.continuent.tungsten.replicator.plugin.PluginContext)
     */
    public synchronized void prepare(PluginContext context)
            throws ReplicatorException, InterruptedException
    {
        // Find the THL from which we expect to feed.
        try
        {
            thl = (THL) context.getStore(thlStoreName);
        }
        catch (ClassCastException e)
        {
            throw new ReplicatorException(
                    "Invalid THL storage class; thlStoreName parameter may be in error: "
                            + context.getStore(thlStoreName).getClass()
                                    .getName());
        }
        if (thl == null)
            throw new ReplicatorException(
                    "Unknown storage name; thlStoreName may be in error: "
                            + thlStoreName);

        // Instantiate reader tasks, followed by array of last sequence numbers
        // to permit propagation of restart points from each output task.
        readTasks = new ArrayList<THLParallelReadTask>(partitions);
        for (int i = 0; i < partitions; i++)
        {
            THLParallelReadTask readTask = new THLParallelReadTask(i, thl,
                    partitioner, headSeqnoCounter, maxSize, maxControlEvents);
            readTasks.add(readTask);
            readTask.prepare(context);
        }
        lastHeaders = new ReplDBMSHeader[partitions];
    }

    /**
     * Release queue. {@inheritDoc}
     * 
     * @see com.continuent.tungsten.replicator.plugin.ReplicatorPlugin#release(com.continuent.tungsten.replicator.plugin.PluginContext)
     */
    public synchronized void release(PluginContext context)
            throws ReplicatorException
    {
        for (THLParallelReadTask readTask : readTasks)
        {
            // Stop the task thread again for good measure.
            readTask.stop();
            readTask.release();
        }
        readTasks = null;
        lastHeaders = null;
    }

    /**
     * Start the reader for a particular task.
     */
    public synchronized void start(int taskId)
    {
        this.readTasks.get(taskId).start();
    }

    /**
     * Stop the reader for a particular task.
     */
    public synchronized void stop(int taskId)
    {
        this.readTasks.get(taskId).stop();
    }

    // Validate that the taskId is in the accepted range of partitions.
    private void assertTaskIdWithinRange(int taskId) throws ReplicatorException
    {
        if (taskId >= partitions)
            throw new ReplicatorException(
                    "Task ID is out of range, must be less than partition size: taskId="
                            + taskId + " partitions=" + partitions);
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.continuent.tungsten.replicator.storage.ParallelStore#insertStopEvent()
     */
    public synchronized void insertStopEvent() throws InterruptedException
    {
        if (lastInsertedEvent == null || lastInsertedEvent.getLastFrag())
            putControlEvent(ReplControlEvent.STOP, lastInsertedEvent);
        else
            stopRequested = true;
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.continuent.tungsten.replicator.storage.ParallelStore#insertWatchSyncEvent(com.continuent.tungsten.replicator.util.WatchPredicate)
     */
    public void insertWatchSyncEvent(WatchPredicate<ReplDBMSHeader> predicate)
            throws InterruptedException
    {
        this.watchPredicates.add(predicate);
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.continuent.tungsten.replicator.storage.Store#status()
     */
    public TungstenProperties status()
    {
        TungstenProperties props = new TungstenProperties();
        props.setLong("headSeqno", headSeqnoCounter.getSeqno());
        props.setLong("maxSize", maxSize);
        props.setLong("eventCount", transactionCount);
        props.setLong("discardCount", discardCount);
        props.setInt("queues", partitions);
        props.setBoolean("syncEnabled", syncEnabled);
        props.setInt("syncInterval", syncInterval);
        props.setBoolean("serialized", this.criticalPartition >= 0);
        props.setLong("serializationCount", serializationCount);
        props.setBoolean("stopRequested", stopRequested);
        props.setInt("criticalPartition", criticalPartition);
        for (int i = 0; i < readTasks.size(); i++)
        {
            props.setString("store." + i, readTasks.get(i).toString());
        }
        return props;
    }

    @Override
    public long getMaxCommittedSeqno() throws ReplicatorException
    {
        // TODO Auto-generated method stub
        return -1;
    }
}