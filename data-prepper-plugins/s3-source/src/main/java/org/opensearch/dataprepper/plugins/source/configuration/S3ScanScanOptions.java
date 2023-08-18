/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.dataprepper.plugins.source.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.AssertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Class consists the scan options list bucket configuration properties.
 */
public class S3ScanScanOptions {

    @JsonProperty("range")
    private Duration range;

    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @JsonProperty("start_time")
    private LocalDateTime startTime;

    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @JsonProperty("end_time")
    private LocalDateTime endTime;

    @JsonProperty("buckets")
    @Valid
    private List<S3ScanBucketOptions> buckets;

    @JsonProperty("scheduling")
    @Valid
    private S3ScanSchedulingOptions schedulingOptions;

    @AssertTrue(message = "At most two options from start_time, end_time and range can be specified at the same time")
    public boolean hasValidTimeOptions() {
        return Stream.of(startTime, endTime, range).filter(Objects::nonNull).count() < 3;
    }

    @AssertFalse(message = "start_time or end_time cannot be used along with range")
    public boolean hasValidTimeAndRangeOptions() {
        return (startTime != null || endTime != null) && range != null;
    }

    @AssertTrue(message = "start_time, end_time, and range are not valid options when using scheduling with s3 scan")
    public boolean hasValidTimeOptionsWithScheduling() {
        return !Objects.nonNull(schedulingOptions) || Stream.of(startTime, endTime, range).noneMatch(Objects::nonNull);
    }

    public Duration getRange() {
        return range;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public LocalDateTime getEndTime() { return endTime; }

    public List<S3ScanBucketOptions> getBuckets() {
        return buckets;
    }

    public S3ScanSchedulingOptions getSchedulingOptions() {
        return schedulingOptions;
    }
}
