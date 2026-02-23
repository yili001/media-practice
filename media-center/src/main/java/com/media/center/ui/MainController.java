package com.media.center.ui;

import com.media.center.service.ContentServer;
import com.media.center.service.DeviceModel;
import com.media.center.service.DlnaService;
import com.media.center.service.DownloadSession;
import com.media.center.service.TorrentService;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class MainController {
    @FXML
    private TextField magnetField;
    @FXML
    private TextArea trackersArea;
    @FXML
    private TableView<DownloadSession> downloadTable;
    @FXML
    private TableColumn<DownloadSession, String> nameCol;
    @FXML
    private TableColumn<DownloadSession, Double> progressCol;
    @FXML
    private TableColumn<DownloadSession, String> statusCol;

    @FXML
    private ListView<DeviceModel> deviceList;
    @FXML
    private Label selectedFileLabel;
    @FXML
    private TextField videoUrlField;
    @FXML
    private Label statusLabel;
    @FXML
    private Label networkInfoLabel;
    @FXML
    private javafx.scene.control.ComboBox<String> networkAdapterCombo;

    private TorrentService torrentService;
    private DlnaService dlnaService;
    private ContentServer contentServer;
    private File fileToCast;
    private List<NetworkInterface> availableAdapters = new ArrayList<>();

    public void initialize() {
        torrentService = new TorrentService();
        downloadTable.setItems(torrentService.getDownloads());
        nameCol.setCellValueFactory(cell -> cell.getValue().nameProperty());
        progressCol.setCellValueFactory(cell -> cell.getValue().progressProperty().asObject());
        statusCol.setCellValueFactory(cell -> cell.getValue().statusProperty());

        TableColumn<DownloadSession, String> dlSpeedCol = new TableColumn<>("DL Speed");
        dlSpeedCol.setCellValueFactory(cellData -> cellData.getValue().dlSpeedProperty());

        TableColumn<DownloadSession, String> ulSpeedCol = new TableColumn<>("UL Speed");
        ulSpeedCol.setCellValueFactory(cellData -> cellData.getValue().ulSpeedProperty());

        TableColumn<DownloadSession, Number> seedsCol = new TableColumn<>("Seeds");
        seedsCol.setCellValueFactory(cellData -> cellData.getValue().seedsProperty());
        seedsCol.setPrefWidth(60);

        downloadTable.getColumns().addAll(dlSpeedCol, ulSpeedCol, seedsCol);

        progressCol.setCellFactory(column -> new TableCell<DownloadSession, Double>() {
            private final ProgressBar progressBar = new ProgressBar();
            private final javafx.scene.layout.StackPane stackPane = new javafx.scene.layout.StackPane();
            private final Label label = new Label();

            {
                progressBar.setMaxWidth(Double.MAX_VALUE);
                stackPane.getChildren().addAll(progressBar, label);
            }

            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    progressBar.setProgress(item);
                    label.setText(String.format("%.0f%%", item * 100));
                    setGraphic(stackPane);
                }
            }
        });

        // Context Menu
        downloadTable.setRowFactory(tv -> {
            javafx.scene.control.TableRow<DownloadSession> row = new javafx.scene.control.TableRow<>();
            javafx.scene.control.ContextMenu contextMenu = new javafx.scene.control.ContextMenu();

            javafx.scene.control.MenuItem pauseItem = new javafx.scene.control.MenuItem("Pause");
            pauseItem.setOnAction(event -> torrentService.pauseDownload(row.getItem()));

            javafx.scene.control.MenuItem resumeItem = new javafx.scene.control.MenuItem("Resume");
            resumeItem.setOnAction(event -> torrentService.resumeDownload(row.getItem()));

            javafx.scene.control.MenuItem restartItem = new javafx.scene.control.MenuItem("Restart");
            restartItem.setOnAction(event -> torrentService.restartDownload(row.getItem()));

            javafx.scene.control.MenuItem deleteItem = new javafx.scene.control.MenuItem("Delete");
            deleteItem.setOnAction(event -> torrentService.deleteDownload(row.getItem()));

            javafx.scene.control.MenuItem openFileItem = new javafx.scene.control.MenuItem("Go to File");
            openFileItem.setOnAction(event -> torrentService.openFile(row.getItem()));

            contextMenu.getItems().addAll(pauseItem, resumeItem, restartItem, deleteItem, openFileItem);

            row.contextMenuProperty().bind(
                    javafx.beans.binding.Bindings.when(row.emptyProperty())
                            .then((javafx.scene.control.ContextMenu) null)
                            .otherwise(contextMenu));
            return row;
        });

        try {
            contentServer = new ContentServer();
            contentServer.start();
        } catch (IOException e) {
            e.printStackTrace();
            statusLabel.setText("Failed to start content server");
        }

        dlnaService = new DlnaService();
        deviceList.setItems(dlnaService.getDevices());

        // Populate network adapters
        try {
            javafx.collections.ObservableList<String> adapterNames = javafx.collections.FXCollections
                    .observableArrayList();
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp())
                    continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof java.net.Inet4Address) {
                        availableAdapters.add(ni);
                        adapterNames.add(ni.getDisplayName() + " (" + addr.getHostAddress() + ")");
                        break;
                    }
                }
            }
            networkAdapterCombo.setItems(adapterNames);
            if (!adapterNames.isEmpty()) {
                networkAdapterCombo.getSelectionModel().select(0);
                updateNetworkInfo(0);
            }
            networkAdapterCombo.getSelectionModel().selectedIndexProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null && newVal.intValue() >= 0) {
                    updateNetworkInfo(newVal.intValue());
                }
            });
        } catch (Exception e) {
            networkInfoLabel.setText("Network: error enumerating adapters");
        }

        // Initial SSDP discovery on default adapter
        dlnaService.start();

        // Initialize Settings
        databaseService = new com.media.center.service.DatabaseService();
        String downloadDir = databaseService.getConfig("download_dir");
        if (downloadDir == null || downloadDir.isEmpty()) {
            downloadDir = System.getProperty("user.home") + File.separator + "Downloads";
        }
        downloadFolderField.setText(downloadDir);

        String defaultTrackers = databaseService.getConfig("default_trackers");
        if (defaultTrackers != null) {
            defaultTrackersArea.setText(defaultTrackers);
        }

        // Proxy Init
        proxyTypeCombo.setItems(javafx.collections.FXCollections.observableArrayList("None", "HTTP", "SOCKS"));
        String pHost = databaseService.getConfig("proxy_host");
        String pPort = databaseService.getConfig("proxy_port");
        String pType = databaseService.getConfig("proxy_type");

        if (pHost != null)
            proxyHostField.setText(pHost);
        if (pPort != null)
            proxyPortField.setText(pPort);
        if (pType != null) {
            proxyTypeCombo.setValue(pType);
        } else {
            proxyTypeCombo.setValue("None");
        }

        // Apply initial proxy settings
        com.media.center.service.torrent.ProxyConfig.setProxy(pHost, pPort, pType);

        // Peer port init
        String peerPort = databaseService.getConfig("peer_port");
        if (peerPort != null && !peerPort.isEmpty()) {
            peerPortField.setText(peerPort);
            try {
                com.media.center.service.torrent.TorrentDownloader.setListenPort(Integer.parseInt(peerPort));
            } catch (NumberFormatException ignored) {
            }
        } else {
            peerPortField.setText("6881");
        }

        torrentService.setDownloadDir(downloadDir);

        // Populate readonly port reference fields
        streamPortField.setText("TCP " + contentServer.getPort());
        peerPortReadonlyField.setText("TCP " + com.media.center.service.torrent.TorrentDownloader.getListenPort());

        // Keep peer port readonly field in sync when editable field changes
        peerPortField.textProperty().addListener((obs, old, val) -> {
            if (val != null && !val.isEmpty()) {
                peerPortReadonlyField.setText("TCP " + val);
            }
        });

    }

    @FXML
    private javafx.scene.control.Button addDownloadButton;
    @FXML
    private javafx.scene.control.Button cancelButton;

    private boolean isFetching = false;
    private volatile boolean cancelFlag = false;

    @FXML
    private void handleCancel() {
        if (isFetching) {
            cancelFlag = true;
            statusLabel.setText("Cancelling...");
        }
    }

    @FXML
    private void addDownload() {
        if (isFetching)
            return;

        String magnet = magnetField.getText();
        if (magnet != null && !magnet.isEmpty()) {

            // UI State
            isFetching = true;
            cancelFlag = false;
            addDownloadButton.setDisable(true);
            cancelButton.setDisable(false);
            statusLabel.setText("Fetching metadata...");

            // Background Task
            new Thread(() -> {
                try {
                    String trackersText = trackersArea.getText();
                    List<String> trackers = new java.util.ArrayList<>();
                    if (trackersText != null && !trackersText.isEmpty()) {
                        trackers.addAll(Arrays.stream(trackersText.split("\\n"))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .collect(Collectors.toList()));
                    }

                    // Add Default Trackers
                    String defaultTrackersText = defaultTrackersArea.getText();
                    if (defaultTrackersText != null && !defaultTrackersText.isEmpty()) {
                        trackers.addAll(Arrays.stream(defaultTrackersText.split("\\n"))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .collect(Collectors.toList()));
                    }

                    // 1. Get Preview Files (Blocking, but in background thread)
                    List<com.media.center.service.TorrentFile> previewFiles = torrentService.getPreviewFiles(magnet,
                            trackers, () -> cancelFlag);

                    if (cancelFlag) {
                        javafx.application.Platform.runLater(() -> statusLabel.setText("Fetch cancelled."));
                        return; // Just exit
                    }

                    javafx.application.Platform.runLater(() -> {
                        processFetchResult(magnet, trackers, previewFiles);
                    });

                } catch (Exception e) {
                    javafx.application.Platform.runLater(() -> {
                        statusLabel.setText("Error: " + e.getMessage());
                        e.printStackTrace();
                    });
                } finally {
                    javafx.application.Platform.runLater(() -> {
                        isFetching = false;
                        addDownloadButton.setDisable(false);
                        cancelButton.setDisable(true);
                        if (!statusLabel.getText().startsWith("Error")
                                && !statusLabel.getText().startsWith("Fetch cancelled")) {
                            statusLabel.setText("Ready");
                        }
                    });
                }
            }).start();
        }
    }

    private void processFetchResult(String magnet, List<String> trackers,
            List<com.media.center.service.TorrentFile> previewFiles) {
        if (previewFiles == null || previewFiles.isEmpty()) {
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.WARNING);
            alert.setTitle("No Files Found");
            alert.setHeaderText("Could not fetch metadata");
            alert.setContentText(
                    "Unable to retrieve file list from magnet link.\n\nPossible reasons:\n- Trackers are unreachable (try configuring a Proxy)\n- Torrent has no active peers\n- Timeout awaiting metadata");
            alert.showAndWait();
            return;
        }

        // 2. Show Selection Dialog
        javafx.scene.control.Dialog<List<com.media.center.service.TorrentFile>> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Select Files to Download");
        dialog.setHeaderText("Uncheck files you do not want to download.");

        javafx.scene.control.DialogPane dialogPane = dialog.getDialogPane();
        dialogPane.getButtonTypes().addAll(javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL);

        javafx.collections.ObservableList<com.media.center.service.TorrentFile> fileList = javafx.collections.FXCollections
                .observableArrayList(previewFiles);
        ListView<com.media.center.service.TorrentFile> listView = new ListView<>();
        listView.setItems(fileList);

        listView.setCellFactory(param -> new javafx.scene.control.ListCell<>() {
            private final javafx.scene.control.CheckBox checkBox = new javafx.scene.control.CheckBox();

            @Override
            protected void updateItem(com.media.center.service.TorrentFile item, boolean empty) {
                super.updateItem(item, empty);
                // Remove old handler to prevent cross-item coupling
                checkBox.setOnAction(null);

                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    checkBox.setSelected(item.isSelected());
                    checkBox.setText(item.toString());
                    checkBox.setOnAction(e -> item.setSelected(checkBox.isSelected()));
                    setGraphic(checkBox);
                    setText(null);
                }
            }
        });

        // Select All / Deselect All buttons
        javafx.scene.control.Button selectAllBtn = new javafx.scene.control.Button("Select All");
        selectAllBtn.setOnAction(e -> {
            for (com.media.center.service.TorrentFile f : fileList)
                f.setSelected(true);
            listView.refresh();
        });
        javafx.scene.control.Button deselectAllBtn = new javafx.scene.control.Button("Deselect All");
        deselectAllBtn.setOnAction(e -> {
            for (com.media.center.service.TorrentFile f : fileList)
                f.setSelected(false);
            listView.refresh();
        });
        javafx.scene.layout.HBox buttonBar = new javafx.scene.layout.HBox(8, selectAllBtn, deselectAllBtn);
        buttonBar.setPadding(new javafx.geometry.Insets(0, 0, 8, 0));

        listView.setPrefHeight(200);
        javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(buttonBar, listView);
        dialogPane.setContent(content);

        dialog.setResultConverter(buttonType -> {
            if (buttonType == javafx.scene.control.ButtonType.OK) {
                return new java.util.ArrayList<>(listView.getItems());
            }
            return null;
        });

        java.util.Optional<List<com.media.center.service.TorrentFile>> result = dialog.showAndWait();

        // 3. Start Download if confirmed
        result.ifPresent(selectedFiles -> {
            torrentService.addDownload(magnet, trackers, selectedFiles);
            magnetField.clear();
            trackersArea.clear();
        });
    }

    @FXML
    private void browseFile() {
        FileChooser chooser = new FileChooser();
        File file = chooser.showOpenDialog(selectedFileLabel.getScene().getWindow());
        if (file != null) {
            fileToCast = file;
            selectedFileLabel.setText(file.getName());
            contentServer.setFileToServe(file);
            videoUrlField.setText(contentServer.getServeUrl());
        }
    }

    @FXML
    private void handleRefreshDevices() {
        if (dlnaService != null) {
            InetAddress bindAddr = getSelectedAdapterAddress();
            dlnaService.search(bindAddr);
        }
    }

    @FXML
    private void play() {
        DeviceModel device = deviceList.getSelectionModel().getSelectedItem();
        if (device != null && fileToCast != null) {
            statusLabel.setText("Casting to " + device.toString());
            String url = contentServer.getServeUrl();
            dlnaService.play(device, url, fileToCast.getName());
        } else {
            statusLabel.setText("Select a device and a file first.");
        }
    }

    @FXML
    private void stop() {
        DeviceModel device = deviceList.getSelectionModel().getSelectedItem();
        if (device != null) {
            dlnaService.stop(device);
            statusLabel.setText("Stopped.");
        }
    }

    private void updateNetworkInfo(int index) {
        if (index < 0 || index >= availableAdapters.size())
            return;
        NetworkInterface ni = availableAdapters.get(index);
        for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
            if (addr instanceof java.net.Inet4Address) {
                int port = contentServer != null ? contentServer.getPort() : 8192;
                networkInfoLabel.setText("IP: " + addr.getHostAddress() + "  |  Stream port: " + port);

                if (contentServer != null) {
                    contentServer.setHostname(addr.getHostAddress());
                }

                // Update the video URL field if a file is already selected, as the IP may have
                // changed
                if (contentServer != null && fileToCast != null) {
                    javafx.application.Platform.runLater(() -> {
                        // Restart server binding to new IP if needed, or at least update the displayed
                        // URL
                        // (Assuming ContentServer handles binding to all interfaces or the new IP
                        // correctly)
                        videoUrlField.setText(contentServer.getServeUrl());
                    });
                }
                return;
            }
        }
    }

    private InetAddress getSelectedAdapterAddress() {
        int idx = networkAdapterCombo.getSelectionModel().getSelectedIndex();
        if (idx >= 0 && idx < availableAdapters.size()) {
            NetworkInterface ni = availableAdapters.get(idx);
            for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                if (addr instanceof java.net.Inet4Address)
                    return addr;
            }
        }
        return null;
    }

    // Settings Tab
    @FXML
    private TextField downloadFolderField;

    @FXML
    private TextArea defaultTrackersArea;

    @FXML
    private TextField proxyHostField;
    @FXML
    private TextField proxyPortField;
    @FXML
    private javafx.scene.control.ComboBox<String> proxyTypeCombo;

    @FXML
    private TextField peerPortField;

    @FXML
    private TextField streamPortField;
    @FXML
    private TextField peerPortReadonlyField;

    private com.media.center.service.DatabaseService databaseService;

    @FXML
    private void handleBrowseDownloadFolder() {
        javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
        File selectedDirectory = chooser.showDialog(downloadFolderField.getScene().getWindow());
        if (selectedDirectory != null) {
            downloadFolderField.setText(selectedDirectory.getAbsolutePath());
        }
    }

    @FXML
    private void handleSaveSettings() {
        String folder = downloadFolderField.getText();
        String trackers = defaultTrackersArea.getText();
        String proxyHost = proxyHostField.getText();
        String proxyPort = proxyPortField.getText();
        String proxyType = proxyTypeCombo.getValue();

        if (databaseService != null) {
            databaseService.saveConfig("download_dir", folder);
            databaseService.saveConfig("default_trackers", trackers);
            databaseService.saveConfig("proxy_host", proxyHost);
            databaseService.saveConfig("proxy_port", proxyPort);
            databaseService.saveConfig("proxy_type", proxyType);
        }

        if (torrentService != null) {
            if (folder != null)
                torrentService.setDownloadDir(folder);
        }

        // Update global proxy config
        com.media.center.service.torrent.ProxyConfig.setProxy(proxyHost, proxyPort, proxyType);

        // Update peer listen port
        String peerPort = peerPortField.getText();
        if (databaseService != null) {
            databaseService.saveConfig("peer_port", peerPort);
        }
        if (peerPort != null && !peerPort.isEmpty()) {
            try {
                com.media.center.service.torrent.TorrentDownloader.setListenPort(Integer.parseInt(peerPort));
            } catch (NumberFormatException ignored) {
            }
        }

        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.INFORMATION);
        alert.setTitle("Settings Saved");
        alert.setHeaderText(null);
        alert.setContentText("Settings have been saved successfully.");
        alert.showAndWait();
    }

    public void shutdown() {
        if (torrentService != null)
            torrentService.shutdown();
        if (dlnaService != null)
            dlnaService.shutdown();
        if (contentServer != null)
            contentServer.stop();
    }

}
