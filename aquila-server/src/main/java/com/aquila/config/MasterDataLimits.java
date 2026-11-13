package com.aquila.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "aquila.limits")
public class MasterDataLimits implements InitializingBean {
    private static final long FROZEN_CAPACITY = 20;
    private static final long FROZEN_GROUP_COUNT = 20;
    private static final long FROZEN_UPLOAD_BYTES = 2L * 1024 * 1024;
    private static final long FROZEN_IMPORT_ROWS = 1000;
    private static final long FROZEN_EXPORT_ROWS = 100;
    private static final long FROZEN_REPLAY_ROWS = 1000;

    private int watchlistCapacity = 20;
    private int hotlistCapacity = 20;
    private int maxGroups = 20;
    private long importUploadBytes = 2L * 1024 * 1024;
    private int importRows = 1000;
    private int exportRows = 100;
    private int replayRows = 1000;

    @Override
    public void afterPropertiesSet() {
        requireCeiling("watchlist-capacity", watchlistCapacity, FROZEN_CAPACITY);
        requireCeiling("hotlist-capacity", hotlistCapacity, FROZEN_CAPACITY);
        requireCeiling("max-groups", maxGroups, FROZEN_GROUP_COUNT);
        requireCeiling("import-upload-bytes", importUploadBytes, FROZEN_UPLOAD_BYTES);
        requireCeiling("import-rows", importRows, FROZEN_IMPORT_ROWS);
        requireCeiling("export-rows", exportRows, FROZEN_EXPORT_ROWS);
        requireCeiling("replay-rows", replayRows, FROZEN_REPLAY_ROWS);
    }

    private static void requireCeiling(String name, long value, long ceiling) {
        if (value < 1 || value > ceiling) {
            throw new IllegalStateException(
                    "Frozen Stage B limit " + name + " must be in [1," + ceiling
                            + "]; raising it requires G03/G02 evidence and owner approval");
        }
    }

    public int getWatchlistCapacity() {
        return watchlistCapacity;
    }

    public void setWatchlistCapacity(int watchlistCapacity) {
        this.watchlistCapacity = watchlistCapacity;
    }

    public int getHotlistCapacity() {
        return hotlistCapacity;
    }

    public void setHotlistCapacity(int hotlistCapacity) {
        this.hotlistCapacity = hotlistCapacity;
    }

    public int getMaxGroups() {
        return maxGroups;
    }

    public void setMaxGroups(int maxGroups) {
        this.maxGroups = maxGroups;
    }

    public long getImportUploadBytes() {
        return importUploadBytes;
    }

    public void setImportUploadBytes(long importUploadBytes) {
        this.importUploadBytes = importUploadBytes;
    }

    public int getImportRows() {
        return importRows;
    }

    public void setImportRows(int importRows) {
        this.importRows = importRows;
    }

    public int getExportRows() {
        return exportRows;
    }

    public void setExportRows(int exportRows) {
        this.exportRows = exportRows;
    }

    public int getReplayRows() {
        return replayRows;
    }

    public void setReplayRows(int replayRows) {
        this.replayRows = replayRows;
    }
}
