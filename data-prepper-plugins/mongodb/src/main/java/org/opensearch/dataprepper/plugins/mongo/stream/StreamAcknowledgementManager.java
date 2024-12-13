package org.opensearch.dataprepper.plugins.mongo.stream;

import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.Counter;
import org.opensearch.dataprepper.common.concurrent.BackgroundThreadFactory;
import org.opensearch.dataprepper.metrics.PluginMetrics;
import org.opensearch.dataprepper.model.acknowledgements.AcknowledgementSet;
import org.opensearch.dataprepper.model.acknowledgements.AcknowledgementSetManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.opensearch.dataprepper.logging.DataPrepperMarkers.NOISY;


public class StreamAcknowledgementManager {
    private static final Logger LOG = LoggerFactory.getLogger(StreamAcknowledgementManager.class);
    private static final int CHECKPOINT_RECORD_INTERVAL = 50;
    private final ConcurrentLinkedQueue<CheckpointStatus> checkpoints = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, CheckpointStatus> ackStatus = new ConcurrentHashMap<>();
    private final AcknowledgementSetManager acknowledgementSetManager;
    private final DataStreamPartitionCheckpoint partitionCheckpoint;

    private final Duration partitionAcknowledgmentTimeout;
    private final int acknowledgementMonitorWaitTimeInMs;
    private final int checkPointIntervalInMs;
    private final ExecutorService executorService;

    private boolean enableAcknowledgement = false;

    private final Counter positiveAcknowledgementSets;
    private final Counter negativeAcknowledgementSets;
    private final Counter recordsCheckpointed;
    private final Counter noDataExtendLeaseCount;
    private final Counter giveupPartitionCount;
    public static final String POSITIVE_ACKNOWLEDGEMENT_SET_METRIC_NAME = "positiveAcknowledgementSets";
    public static final String NEGATIVE_ACKNOWLEDGEMENT_SET_METRIC_NAME = "negativeAcknowledgementSets";
    public static final String RECORDS_CHECKPOINTED = "recordsCheckpointed";
    public static final String NO_DATA_EXTEND_LEASE_COUNT = "noDataExtendLeaseCount";
    public static final String GIVE_UP_PARTITION_COUNT = "giveupPartitionCount";


    public StreamAcknowledgementManager(final AcknowledgementSetManager acknowledgementSetManager,
                                        final DataStreamPartitionCheckpoint partitionCheckpoint,
                                        final Duration partitionAcknowledgmentTimeout,
                                        final int acknowledgementMonitorWaitTimeInMs,
                                        final int checkPointIntervalInMs,
                                        final PluginMetrics pluginMetrics) {
        this.acknowledgementSetManager = acknowledgementSetManager;
        this.partitionCheckpoint = partitionCheckpoint;
        this.partitionAcknowledgmentTimeout = partitionAcknowledgmentTimeout;
        this.acknowledgementMonitorWaitTimeInMs = acknowledgementMonitorWaitTimeInMs;
        this.checkPointIntervalInMs = checkPointIntervalInMs;
        this.executorService = Executors.newSingleThreadExecutor(BackgroundThreadFactory.defaultExecutorThreadFactory("mongodb-stream-ack-monitor"));
        this.positiveAcknowledgementSets = pluginMetrics.counter(POSITIVE_ACKNOWLEDGEMENT_SET_METRIC_NAME);
        this.negativeAcknowledgementSets = pluginMetrics.counter(NEGATIVE_ACKNOWLEDGEMENT_SET_METRIC_NAME);
        this.recordsCheckpointed = pluginMetrics.counter(RECORDS_CHECKPOINTED);
        this.noDataExtendLeaseCount = pluginMetrics.counter(NO_DATA_EXTEND_LEASE_COUNT);
        this.giveupPartitionCount = pluginMetrics.counter(GIVE_UP_PARTITION_COUNT);
    }

    void init(final Consumer<Void> stopWorkerConsumer) {
        enableAcknowledgement = true;
        executorService.submit(() -> monitorAcknowledgment(executorService, stopWorkerConsumer));
    }

    private void monitorAcknowledgment(final ExecutorService executorService, final Consumer<Void> stopWorkerConsumer) {
        long lastCheckpointTime = System.currentTimeMillis();
        CheckpointStatus lastCheckpointStatus = null;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                CheckpointStatus checkpointStatus = checkpoints.peek();
                if (checkpointStatus != null) {
                    if (checkpointStatus.isPositiveAcknowledgement()) {
                        if (System.currentTimeMillis() - lastCheckpointTime >= checkPointIntervalInMs) {
                            long ackCount = 0;
                            do {
                                lastCheckpointStatus = checkpoints.poll();
                                ackStatus.remove(checkpointStatus.getResumeToken());
                                checkpointStatus = checkpoints.peek();
                                ackCount++;
                                // at high TPS each ack contains 100 records. This should checkpoint every 100*50 = 5000 records.
                                if (ackCount % CHECKPOINT_RECORD_INTERVAL == 0) {
                                    checkpoint(lastCheckpointStatus.getResumeToken(), lastCheckpointStatus.getRecordCount());
                                }
                            } while (checkpointStatus != null && checkpointStatus.isPositiveAcknowledgement());
                            checkpoint(lastCheckpointStatus.getResumeToken(), lastCheckpointStatus.getRecordCount());
                            lastCheckpointTime = System.currentTimeMillis();
                        }
                    } else {
                        LOG.debug("Checkpoint not complete for resume token {}", checkpointStatus.getResumeToken());
                        final Duration ackWaitDuration = Duration.between(Instant.ofEpochMilli(checkpointStatus.getCreateTimestamp()), Instant.now());
                        if (checkpointStatus.isNegativeAcknowledgement()) {
                            // Give up partition and should interrupt parent thread to stop processing stream
                            if (lastCheckpointStatus != null && lastCheckpointStatus.isPositiveAcknowledgement()) {
                                checkpoint(lastCheckpointStatus.getResumeToken(), lastCheckpointStatus.getRecordCount());
                            }
                            LOG.warn("Acknowledgement not received for the checkpoint {} past wait time. Giving up partition.", checkpointStatus.getResumeToken());
                            partitionCheckpoint.giveUpPartition();
                            this.giveupPartitionCount.increment();
                            break;
                        }
                    }
                } else {
                    if (System.currentTimeMillis() - lastCheckpointTime >= checkPointIntervalInMs) {
                        LOG.info(NOISY, "No records processed. Extend the lease of the partition worker.");
                        partitionCheckpoint.extendLease();
                        this.noDataExtendLeaseCount.increment();
                        lastCheckpointTime = System.currentTimeMillis();
                    }
                }
            } catch (Exception e) {
                LOG.warn("Exception monitoring acknowledgments. The stream record processing will start from previous checkpoint.", e);
                break;
            }

            try {
                Thread.sleep(acknowledgementMonitorWaitTimeInMs);
            } catch (InterruptedException ex) {
                break;
            }
        }
        stopWorkerConsumer.accept(null);
        executorService.shutdown();
    }

    private void checkpoint(final String resumeToken, final long recordCount) {
        LOG.debug("Perform regular checkpointing for resume token {} at record count {}", resumeToken, recordCount);
        partitionCheckpoint.checkpoint(resumeToken, recordCount);
        this.recordsCheckpointed.increment(recordCount);
    }

    Optional<AcknowledgementSet> createAcknowledgementSet(final String resumeToken, final long recordNumber) {
        if (!enableAcknowledgement) {
            return Optional.empty();
        }

        final CheckpointStatus checkpointStatus = new CheckpointStatus(resumeToken, recordNumber, Instant.now().toEpochMilli());
        checkpoints.add(checkpointStatus);
        ackStatus.put(resumeToken, checkpointStatus);
        LOG.debug("Creating acknowledgment for resumeToken {}", checkpointStatus.getResumeToken());
        return Optional.of(acknowledgementSetManager.create((result) -> {
            final CheckpointStatus ackCheckpointStatus = ackStatus.get(resumeToken);
            ackCheckpointStatus.setAcknowledgedTimestamp(Instant.now().toEpochMilli());
            if (result) {
                this.positiveAcknowledgementSets.increment();
                ackCheckpointStatus.setAcknowledged(CheckpointStatus.AcknowledgmentStatus.POSITIVE_ACK);
                LOG.debug("Received acknowledgment of completion from sink for checkpoint {}", resumeToken);
            } else {
                this.negativeAcknowledgementSets.increment();
                ackCheckpointStatus.setAcknowledged(CheckpointStatus.AcknowledgmentStatus.NEGATIVE_ACK);
                LOG.warn("Negative acknowledgment received for checkpoint {}, resetting checkpoint", resumeToken);
                // default CheckpointStatus acknowledged value is false. The monitorCheckpoints method will time out
                // and reprocess stream from last successful checkpoint in the order.
            }
        }, partitionAcknowledgmentTimeout));
    }

    void shutdown() {
        executorService.shutdown();
    }

    @VisibleForTesting
    ConcurrentHashMap<String, CheckpointStatus> getAcknowledgementStatus() {
        return ackStatus;
    }

    @VisibleForTesting
    ConcurrentLinkedQueue<CheckpointStatus> getCheckpoints() {
        return checkpoints;
    }
}
