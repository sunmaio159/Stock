package com.aquila.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MasterDataLimitsTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(MasterDataLimits.class);

    @Test
    void acceptsFrozenDefaults() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsRaisedWatchlistCapacity() {
        runner.withPropertyValues("aquila.limits.watchlist-capacity=21")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsRaisedHotlistCapacity() {
        runner.withPropertyValues("aquila.limits.hotlist-capacity=300")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsRaisedGroupCount() {
        runner.withPropertyValues("aquila.limits.max-groups=999")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsRaisedUploadSize() {
        runner.withPropertyValues("aquila.limits.import-upload-bytes=1073741824")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsRaisedImportAndExportRows() {
        runner.withPropertyValues("aquila.limits.import-rows=5000")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("aquila.limits.export-rows=5000")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("aquila.limits.replay-rows=5000")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsNonPositiveLimits() {
        runner.withPropertyValues("aquila.limits.export-rows=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
