package com.payflow.api.merkle;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.merkle")
public class MerkleProperties {

    private BatchBuilder batchBuilder = new BatchBuilder();

    public BatchBuilder getBatchBuilder() {
        return batchBuilder;
    }

    public void setBatchBuilder(BatchBuilder batchBuilder) {
        this.batchBuilder = batchBuilder;
    }

    public static class BatchBuilder {
        private boolean enabled = true;
        private long intervalMs = 60000;
        private int batchSize = 500;
        private int lagSeconds = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getLagSeconds() {
            return lagSeconds;
        }

        public void setLagSeconds(int lagSeconds) {
            this.lagSeconds = lagSeconds;
        }
    }
}
