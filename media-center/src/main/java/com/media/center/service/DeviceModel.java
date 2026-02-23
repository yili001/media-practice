package com.media.center.service;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class DeviceModel {
    private final StringProperty name;
    private final StringProperty udn;
    private final String controlUrl;

    public DeviceModel(String name, String udn, String controlUrl) {
        this.name = new SimpleStringProperty(name);
        this.udn = new SimpleStringProperty(udn);
        this.controlUrl = controlUrl;
    }

    public StringProperty nameProperty() {
        return name;
    }

    public String getUdn() {
        return udn.get();
    }

    public StringProperty udnProperty() {
        return udn;
    }

    public String getControlUrl() {
        return controlUrl;
    }

    @Override
    public String toString() {
        return name.get();
    }
}
