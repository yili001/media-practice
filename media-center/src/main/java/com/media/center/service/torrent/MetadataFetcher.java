package com.media.center.service.torrent;

import com.media.center.service.TorrentFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MetadataFetcher {

    public static List<TorrentFile> fetchFiles(MagnetLink magnet, List<String> extraTrackers,
            java.util.function.BooleanSupplier isCancelled) {
        // Collect all unique trackers
        List<String> allTrackers = new ArrayList<>();
        if (magnet.getTrackers() != null) {
            allTrackers.addAll(magnet.getTrackers());
        }
        if (extraTrackers != null) {
            for (String tr : extraTrackers) {
                if (tr != null && !tr.isEmpty() && !allTrackers.contains(tr)) {
                    allTrackers.add(tr);
                }
            }
        }

        // Add some default trackers if not present
        String[] defaults = new String[] {
                "http://nyaa.tracker.wf:7777/announce",
                "http://tracker.opentrackr.org:1337/announce",
                "http://explodie.org:6969/announce",
                "http://repo.arteslas.com:6969/announce",
                "http://p4p.arenabg.com:1337/announce",
                "http://tracker.internetwarriors.net:1337/announce"
        };
        for (String def : defaults) {
            if (!allTrackers.contains(def)) {
                allTrackers.add(def);
            }
        }

        System.out.println("Querying " + allTrackers.size() + " trackers in parallel...");

        // 1. Get Peers in Parallel
        List<TrackerClient.Peer> allPeers = java.util.Collections.synchronizedList(new ArrayList<>());
        // Thread pool of 30 as requested, remaining trackers will queue
        ExecutorService trackerExecutor = Executors.newFixedThreadPool(Math.min(allTrackers.size(), 30));
        List<Future<?>> trackerFutures = new ArrayList<>();

        for (String tr : allTrackers) {
            if (isCancelled.getAsBoolean()) {
                trackerExecutor.shutdownNow();
                return null;
            }

            // Skip unsupported protocols (wss is browser-only)
            if (tr.startsWith("wss://"))
                continue;

            trackerFutures.add(trackerExecutor.submit(() -> {
                if (isCancelled.getAsBoolean())
                    return;

                List<TrackerClient.Peer> peers;
                try {
                    if (tr.startsWith("udp://")) {
                        peers = UdpTrackerClient.getPeers(tr, magnet.getInfoHash());
                    } else {
                        peers = TrackerClient.getPeers(tr, magnet.getInfoHash());
                    }
                } catch (Exception e) {
                    System.err.println("Tracker error: " + tr + " - " + e.getMessage());
                    return;
                }

                if (peers != null && !peers.isEmpty()) {
                    System.out.println("Tracker " + tr + " returned " + peers.size() + " peers.");
                    synchronized (allPeers) {
                        for (TrackerClient.Peer p : peers) {
                            boolean exists = false;
                            for (TrackerClient.Peer existing : allPeers) {
                                if (existing.ip.equals(p.ip) && existing.port == p.port) {
                                    exists = true;
                                    break;
                                }
                            }
                            if (!exists)
                                allPeers.add(p);
                        }
                    }
                }
            }));
        }

        // Wait for trackers (smart wait)
        trackerExecutor.shutdown();
        try {
            // Loop until cancelled, enough peers, or all done
            while (!trackerExecutor.isTerminated()) {
                if (isCancelled.getAsBoolean()) {
                    System.out.println("Metadata fetch cancelled by user.");
                    trackerExecutor.shutdownNow();
                    return null;
                }

                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            // Ignore
        }

        System.out.println("Found " + allPeers.size() + " unique peers.");
        if (allPeers.isEmpty())
            return new ArrayList<>();

        if (isCancelled.getAsBoolean())
            return null;

        // 2. Connect and Fetch Metadata (Parallel)
        ExecutorService peerExecutor = Executors.newFixedThreadPool(10);
        List<Future<byte[]>> futures = new ArrayList<>();

        // Shuffle peers to avoid hitting same ones if we improved logic later
        java.util.Collections.shuffle(allPeers);

        for (TrackerClient.Peer peer : allPeers) {
            if (isCancelled.getAsBoolean())
                break;
            futures.add(peerExecutor.submit(() -> {
                if (isCancelled.getAsBoolean())
                    return null;
                try (PeerConnection conn = new PeerConnection(peer.ip, peer.port, magnet.getInfoHash(),
                        TrackerClient.getPeerId())) {
                    conn.connect();
                    return conn.fetchMetadata();
                } catch (Exception e) {
                    return null;
                }
            }));
        }

        peerExecutor.shutdown();

        byte[] metadata = null;
        try {
            // Wait for first result
            while (!peerExecutor.isTerminated()) {
                if (isCancelled.getAsBoolean()) {
                    System.out.println("Metadata fetch cancelled by user.");
                    peerExecutor.shutdownNow();
                    return null;
                }

                boolean anyDone = false;
                for (Future<byte[]> f : futures) {
                    if (f.isDone()) {
                        anyDone = true;
                        try {
                            byte[] res = f.get();
                            if (res != null) {
                                metadata = res;
                                break;
                            }
                        } catch (Exception e) {
                        }
                    }
                }
                if (metadata != null)
                    break;
                if (anyDone && futures.stream().allMatch(Future::isDone))
                    break;
                Thread.sleep(500);
            }
            peerExecutor.shutdownNow();
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (isCancelled.getAsBoolean())
            return null;

        // 3. Parse Metadata and Return Files
        if (metadata != null) {
            System.out.println("Metadata fetched successfully (" + metadata.length + " bytes).");
            return parseInfoDictionary(metadata);
        }

        System.err.println("Failed to fetch metadata from " + allPeers.size() + " peers.");
        return new ArrayList<>();
    }

    private static List<TorrentFile> parseInfoDictionary(byte[] data) {
        List<TorrentFile> files = new ArrayList<>();
        try {
            Object decoded = BencodeParser.decode(data);
            if (decoded instanceof Map) {
                Map<String, Object> info = (Map<String, Object>) decoded;

                // Single file mode
                if (info.containsKey("length")) {
                    String name = (String) info.get("name");
                    Long length = (Long) info.get("length");
                    files.add(new TorrentFile(name, length));
                }
                // Multi file mode
                else if (info.containsKey("files")) {
                    List<Object> fileList = (List<Object>) info.get("files");
                    for (Object obj : fileList) {
                        Map<String, Object> fileMap = (Map<String, Object>) obj;
                        Long length = (Long) fileMap.get("length");
                        List<Object> pathList = (List<Object>) fileMap.get("path");

                        String fullPath = "";
                        for (Object p : pathList) {
                            if (!fullPath.isEmpty())
                                fullPath += "/";
                            fullPath += String.valueOf(p);
                        }

                        files.add(new TorrentFile(fullPath, length));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return files;
    }
}
