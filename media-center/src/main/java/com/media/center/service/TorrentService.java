package com.media.center.service;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.media.center.service.torrent.MagnetLink;
import com.media.center.service.torrent.TorrentDownloader;

public class TorrentService {
    private final ObservableList<DownloadSession> downloads = FXCollections.observableArrayList();
    private final DatabaseService databaseService;
    private final Map<DownloadSession, TorrentDownloader> activeDownloaders = new HashMap<>();
    private final Map<DownloadSession, Thread> downloadThreads = new HashMap<>();
    private String downloadDir;

    public TorrentService() {
        this.databaseService = new DatabaseService();
        this.downloads.addAll(databaseService.loadSessions());

        for (DownloadSession session : this.downloads) {
            hookSessionListeners(session);
        }
    }

    public void setDownloadDir(String downloadDir) {
        this.downloadDir = downloadDir;
    }

    public void setTempDir(String tempDir) {
        // Deprecated
    }

    public List<TorrentFile> getPreviewFiles(String magnetLinkStr, List<String> extraTrackers,
            java.util.function.BooleanSupplier isCancelled) {
        try {
            MagnetLink magnet = new MagnetLink(magnetLinkStr);
            System.out.println("Parsed magnet: " + magnet.getDisplayName());

            // Use our custom MetadataFetcher
            return com.media.center.service.torrent.MetadataFetcher.fetchFiles(magnet, extraTrackers, isCancelled);

        } catch (Exception e) {
            System.err.println("Invalid magnet link or fetch failed: " + e.getMessage());
            e.printStackTrace();
            return new java.util.ArrayList<>();
        }
    }

    private void hookSessionListeners(DownloadSession session) {
        session.statusProperty().addListener((obs, old, newVal) -> {
            if (!session.isDeleted())
                databaseService.saveSession(session);
        });
        session.progressProperty().addListener((obs, old, newVal) -> {
            if (!session.isDeleted())
                databaseService.saveSession(session);
        });
    }

    public ObservableList<DownloadSession> getDownloads() {
        return downloads;
    }

    public void addDownload(String magnetLink, List<String> extraTrackers, List<TorrentFile> selectedFiles) {
        for (DownloadSession s : downloads) {
            if (s.getMagnetLink().equals(magnetLink))
                return;
        }

        DownloadSession session = new DownloadSession(magnetLink);
        session.nameProperty().set("Download_" + Math.abs(magnetLink.hashCode()));
        session.setExtraTrackers(extraTrackers);

        if (selectedFiles != null) {
            session.getFiles().addAll(selectedFiles);
        }

        downloads.add(session);
        hookSessionListeners(session);
        databaseService.saveSession(session);

        // Start download
        startDownload(session, magnetLink, extraTrackers, selectedFiles);
    }

    public void addDownload(String magnetLink, List<String> extraTrackers) {
        addDownload(magnetLink, extraTrackers, null);
    }

    private void startDownload(DownloadSession session, String magnetLink,
            List<String> extraTrackers, List<TorrentFile> selectedFiles) {
        if (downloadDir == null || downloadDir.isEmpty()) {
            session.statusProperty().set("Error: No download directory set");
            return;
        }

        TorrentDownloader downloader = new TorrentDownloader(
                session, magnetLink, extraTrackers, downloadDir, selectedFiles);
        activeDownloaders.put(session, downloader);

        Thread thread = new Thread(downloader, "Downloader-" + session.getName());
        thread.setDaemon(true);
        downloadThreads.put(session, thread);
        thread.start();
    }

    public void pauseDownload(DownloadSession session) {
        TorrentDownloader d = activeDownloaders.get(session);
        if (d != null)
            d.pause();
        session.statusProperty().set("Paused");
        session.dlSpeedProperty().set("0 KB/s");
    }

    public void resumeDownload(DownloadSession session) {
        TorrentDownloader d = activeDownloaders.get(session);
        if (d != null) {
            d.resume();
            session.statusProperty().set("Downloading");
        } else {
            // Restart download from scratch
            startDownload(session, session.getMagnetLink(),
                    session.getExtraTrackers(), new java.util.ArrayList<>(session.getFiles()));
        }
    }

    public void openFile(DownloadSession session) {
        if (downloadDir != null) {
            java.io.File sessionDir = new java.io.File(downloadDir, session.getName());
            if (sessionDir.exists()) {
                try {
                    java.awt.Desktop.getDesktop().open(sessionDir);
                } catch (java.io.IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void restartDownload(DownloadSession session) {
        // Stop existing downloader
        TorrentDownloader d = activeDownloaders.get(session);
        if (d != null)
            d.stop();

        session.progressProperty().set(0.0);
        startDownload(session, session.getMagnetLink(),
                session.getExtraTrackers(), new java.util.ArrayList<>(session.getFiles()));
    }

    public void deleteDownload(DownloadSession session) {
        // Mark as deleted FIRST — prevents listeners from re-saving to DB
        session.setDeleted(true);

        TorrentDownloader d = activeDownloaders.remove(session);
        if (d != null)
            d.stop();
        Thread t = downloadThreads.remove(session);
        if (t != null)
            t.interrupt();

        // Delete download folder
        if (downloadDir != null) {
            java.io.File sessionDir = new java.io.File(downloadDir, session.getName());
            if (sessionDir.exists()) {
                deleteRecursive(sessionDir);
            }
        }

        downloads.remove(session);
        databaseService.deleteSession(session);
    }

    private void deleteRecursive(java.io.File file) {
        if (file.isDirectory()) {
            java.io.File[] children = file.listFiles();
            if (children != null) {
                for (java.io.File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    public void shutdown() {
        for (Map.Entry<DownloadSession, TorrentDownloader> entry : activeDownloaders.entrySet()) {
            entry.getValue().stop();
        }
        for (Thread t : downloadThreads.values()) {
            t.interrupt();
        }
        activeDownloaders.clear();
        downloadThreads.clear();

        // Only save non-deleted sessions
        for (DownloadSession session : downloads) {
            if (!session.isDeleted()) {
                databaseService.saveSession(session);
            }
        }
    }
}
