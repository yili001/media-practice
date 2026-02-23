package com.media.center.service;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class DownloadSession {
    private final String magnetLink;
    private final StringProperty name = new SimpleStringProperty("Initializing...");
    private final DoubleProperty progress = new SimpleDoubleProperty(0.0);
    private final StringProperty status = new SimpleStringProperty("Stopped");
    private final StringProperty dlSpeed = new SimpleStringProperty("0 KB/s");
    private final StringProperty ulSpeed = new SimpleStringProperty("0 KB/s");
    private final IntegerProperty seeds = new SimpleIntegerProperty(0);
    private final IntegerProperty peers = new SimpleIntegerProperty(0);
    private java.util.List<String> extraTrackers;
    private long totalSize;
    private volatile boolean deleted = false;

    public DownloadSession(String magnetLink) {
        this.magnetLink = magnetLink;
    }

    public String getMagnetLink() {
        return magnetLink;
    }

    public StringProperty nameProperty() {
        return name;
    }

    public DoubleProperty progressProperty() {
        return progress;
    }

    public StringProperty statusProperty() {
        return status;
    }

    public StringProperty dlSpeedProperty() {
        return dlSpeed;
    }

    public StringProperty ulSpeedProperty() {
        return ulSpeed;
    }

    public String getName() {
        return name.get();
    }

    public IntegerProperty seedsProperty() {
        return seeds;
    }

    public IntegerProperty peersProperty() {
        return peers;
    }

    public java.util.List<String> getExtraTrackers() {
        return extraTrackers;
    }

    public void setExtraTrackers(java.util.List<String> extraTrackers) {
        this.extraTrackers = extraTrackers;
    }

    public long getTotalSize() {
        return totalSize;
    }

    public void setTotalSize(long totalSize) {
        this.totalSize = totalSize;
    }

    private final javafx.collections.ObservableList<TorrentFile> files = javafx.collections.FXCollections
            .observableArrayList();

    public javafx.collections.ObservableList<TorrentFile> getFiles() {
        return files;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }
}
