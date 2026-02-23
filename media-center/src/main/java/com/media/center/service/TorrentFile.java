package com.media.center.service;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class TorrentFile {
    private final StringProperty name;
    private final long size;
    private final BooleanProperty selected;

    public TorrentFile(String name, long size) {
        this.name = new SimpleStringProperty(name);
        this.size = size;
        this.selected = new SimpleBooleanProperty(true); // Default to selected
    }

    public String getName() {
        return name.get();
    }

    public StringProperty nameProperty() {
        return name;
    }

    public long getSize() {
        return size;
    }

    public boolean isSelected() {
        return selected.get();
    }

    public void setSelected(boolean selected) {
        this.selected.set(selected);
    }

    public BooleanProperty selectedProperty() {
        return selected;
    }

    // Helper to format size
    public String getSizeFormatted() {
        if (size < 1024)
            return size + " B";
        int z = (63 - Long.numberOfLeadingZeros(size)) / 10;
        return String.format("%.1f %sB", (double) size / (1L << (z * 10)), " KMGTPE".charAt(z));
    }

    @Override
    public String toString() {
        return getName() + " (" + getSizeFormatted() + ")";
    }
}
