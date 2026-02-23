package com.media.center.service.torrent;

import com.media.center.service.DownloadSession;
import com.media.center.service.TorrentFile;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;

/**
 * BitTorrent piece download engine.
 * Connects to peers via persistent connections, requests pieces via the
 * standard BT wire protocol, verifies SHA1 hashes, and writes data to disk.
 */
public class TorrentDownloader implements Runnable {

    private final DownloadSession session;
    private final MagnetLink magnet;
    private final List<String> extraTrackers;
    private final String downloadDir;
    private final List<TorrentFile> selectedFiles;

    // Parsed from info dictionary
    private int pieceLength;
    private byte[][] pieceHashes; // SHA1 hash for each piece
    private long totalSize;
    private String torrentName;

    // File layout: offset -> (path, length)
    private List<FileEntry> fileEntries = new ArrayList<>();

    // Download state
    private volatile boolean paused = false;
    private volatile boolean stopped = false;
    private boolean[] completedPieces;
    private boolean[] neededPieces; // only pieces covering selected files
    private long downloadedBytes = 0;
    private long lastSpeedCheckBytes = 0;
    private long lastSpeedCheckTime = 0;
    private double smoothedSpeed = 0;
    private final Set<String> activeSeedCount = Collections.synchronizedSet(new HashSet<>());

    // Peer tracking: skip dead peers, prefer working ones
    private final Set<String> badPeers = Collections.synchronizedSet(new HashSet<>());
    private final ConcurrentLinkedQueue<TrackerClient.Peer> goodPeers = new ConcurrentLinkedQueue<>();
    private final Set<String> activeConnections = ConcurrentHashMap.newKeySet();

    // Upload state
    private long uploadedBytes = 0;
    private long lastUlSpeedCheckBytes = 0;
    private long lastUlSpeedCheckTime = 0;
    private double smoothedUlSpeed = 0;
    private volatile ServerSocket peerServer;
    private static volatile int listenPort = 6881;

    /** Set the peer listen port (called from settings). */
    public static void setListenPort(int port) {
        if (port > 0 && port <= 65535)
            listenPort = port;
    }

    public static int getListenPort() {
        return listenPort;
    }

    // Disk I/O pool — don't block network threads waiting for disk
    private final ExecutorService diskPool = Executors.newFixedThreadPool(2);

    public TorrentDownloader(DownloadSession session, String magnetLink,
            List<String> extraTrackers, String downloadDir,
            List<TorrentFile> selectedFiles) {
        this.session = session;
        this.magnet = new MagnetLink(magnetLink);
        this.extraTrackers = extraTrackers;
        this.downloadDir = downloadDir;
        this.selectedFiles = selectedFiles;
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
    }

    public void stop() {
        stopped = true;
        diskPool.shutdownNow();
    }

    @Override
    public void run() {
        try {
            updateStatus("Connecting to trackers...");

            // 1. Get peers from trackers
            List<TrackerClient.Peer> peers = getPeers();
            if (peers.isEmpty() || stopped) {
                updateStatus("No peers found");
                return;
            }

            updateStatus("Fetching metadata...");

            // 2. Fetch full info dictionary
            byte[] infoDict = fetchInfoDict(peers);
            if (infoDict == null || stopped) {
                updateStatus("Failed to get metadata");
                return;
            }

            // 3. Parse info dictionary for download parameters
            if (!parseInfoDict(infoDict)) {
                updateStatus("Failed to parse metadata");
                return;
            }

            // 4. Create files on disk
            createFiles();

            // 4b. Compute which pieces are needed for selected files
            computeNeededPieces();

            // 5. Start peer server for incoming upload connections
            lastUlSpeedCheckTime = System.currentTimeMillis();
            startPeerServer();

            // 6. Download pieces
            updateStatus("Downloading");
            downloadPieces(peers);

            if (stopped) {
                updateStatus("Stopped");
            } else if (isComplete()) {
                // Rename .temp files
                finishDownload();
                updateStatus("Completed");
                Platform.runLater(() -> session.progressProperty().set(1.0));
            }

        } catch (Exception e) {
            System.err.println("Download error: " + e.getMessage());
            e.printStackTrace();
            updateStatus("Error: " + e.getMessage());
        } finally {
            // Shutdown peer server
            if (peerServer != null)
                try {
                    peerServer.close();
                } catch (IOException ignored) {
                }
        }
    }

    private List<TrackerClient.Peer> getPeers() {
        List<TrackerClient.Peer> allPeers = Collections.synchronizedList(new ArrayList<>());
        List<String> allTrackers = new ArrayList<>();
        if (magnet.getTrackers() != null)
            allTrackers.addAll(magnet.getTrackers());
        if (extraTrackers != null) {
            for (String tr : extraTrackers) {
                if (tr != null && !tr.isEmpty() && !allTrackers.contains(tr))
                    allTrackers.add(tr);
            }
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(allTrackers.size(), 40));
        for (String tr : allTrackers) {
            if (stopped)
                break;
            if (tr.startsWith("wss://"))
                continue;
            executor.submit(() -> {
                if (stopped)
                    return;
                try {
                    List<TrackerClient.Peer> peers;
                    if (tr.startsWith("udp://")) {
                        peers = UdpTrackerClient.getPeers(tr, magnet.getInfoHash());
                    } else {
                        peers = TrackerClient.getPeers(tr, magnet.getInfoHash());
                    }
                    if (peers != null) {
                        synchronized (allPeers) {
                            for (TrackerClient.Peer p : peers) {
                                boolean exists = allPeers.stream()
                                        .anyMatch(e -> e.ip.equals(p.ip) && e.port == p.port);
                                if (!exists)
                                    allPeers.add(p);
                            }
                        }
                    }
                } catch (Exception e) {
                    // Ignore tracker errors
                }
            });
        }
        executor.shutdown();
        try {
            executor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
        }
        executor.shutdownNow();

        System.out.println("Download: found " + allPeers.size() + " peers for download.");
        int peerCount = allPeers.size();
        Platform.runLater(() -> session.peersProperty().set(peerCount));
        return allPeers;
    }

    private byte[] fetchInfoDict(List<TrackerClient.Peer> peers) {
        Collections.shuffle(peers);
        // Cap attempts — no point trying hundreds of peers if none respond
        int maxAttempts = Math.min(peers.size(), 100);
        int batchSize = 20;
        int threads = Math.min(batchSize, 10);

        System.out.println(
                "Fetching metadata: trying up to " + maxAttempts + " peers in batches of " + batchSize + "...");

        for (int batchStart = 0; batchStart < maxAttempts && !stopped; batchStart += batchSize) {
            int batchEnd = Math.min(batchStart + batchSize, maxAttempts);
            List<TrackerClient.Peer> batch = peers.subList(batchStart, batchEnd);

            System.out.println("  Metadata batch " + (batchStart / batchSize + 1) +
                    ": peers " + (batchStart + 1) + "-" + batchEnd + " of " + maxAttempts);

            ExecutorService executor = Executors.newFixedThreadPool(Math.min(batch.size(), threads));
            List<Future<byte[]>> futures = new ArrayList<>();

            for (TrackerClient.Peer peer : batch) {
                if (stopped)
                    break;
                futures.add(executor.submit(() -> {
                    if (stopped)
                        return null;
                    try (PeerConnection conn = new PeerConnection(peer.ip, peer.port,
                            magnet.getInfoHash(), TrackerClient.getPeerId())) {
                        conn.connect();
                        return conn.fetchMetadata();
                    } catch (Exception e) {
                        return null;
                    }
                }));
            }
            executor.shutdown();

            // Wait for this batch, checking for success
            byte[] metadata = null;
            try {
                while (!executor.isTerminated() && !stopped) {
                    for (Future<byte[]> f : futures) {
                        if (f.isDone()) {
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
                    if (futures.stream().allMatch(Future::isDone))
                        break;
                    Thread.sleep(200);
                }
            } catch (Exception e) {
            }
            executor.shutdownNow();

            if (metadata != null) {
                System.out.println("Metadata fetched successfully (" + metadata.length + " bytes).");
                return metadata;
            }
        }

        System.out.println("Failed to fetch metadata from " + maxAttempts + " peers.");
        return null;
    }

    @SuppressWarnings("unchecked")
    private boolean parseInfoDict(byte[] data) {
        try {
            Object decoded = BencodeParser.decode(data);
            if (!(decoded instanceof Map))
                return false;
            Map<String, Object> info = (Map<String, Object>) decoded;

            // Piece length
            if (info.containsKey("piece length")) {
                pieceLength = ((Long) info.get("piece length")).intValue();
            } else {
                System.err.println("No piece length in info dict");
                return false;
            }

            // Pieces (concatenated SHA1 hashes, 20 bytes each)
            if (info.containsKey("pieces")) {
                String piecesStr = (String) info.get("pieces");
                byte[] piecesRaw = piecesStr.getBytes(StandardCharsets.ISO_8859_1);
                int numPieces = piecesRaw.length / 20;
                pieceHashes = new byte[numPieces][];
                for (int i = 0; i < numPieces; i++) {
                    pieceHashes[i] = new byte[20];
                    System.arraycopy(piecesRaw, i * 20, pieceHashes[i], 0, 20);
                }
            } else {
                System.err.println("No pieces in info dict");
                return false;
            }

            // Torrent name
            torrentName = info.containsKey("name") ? (String) info.get("name") : "Unknown";

            // File layout
            totalSize = 0;
            fileEntries.clear();

            if (info.containsKey("length")) {
                // Single file mode
                long length = (Long) info.get("length");
                totalSize = length;
                fileEntries.add(new FileEntry(torrentName, length, 0));
            } else if (info.containsKey("files")) {
                // Multi-file mode
                List<Object> fileList = (List<Object>) info.get("files");
                long offset = 0;
                for (Object obj : fileList) {
                    Map<String, Object> fileMap = (Map<String, Object>) obj;
                    long length = (Long) fileMap.get("length");
                    List<Object> pathList = (List<Object>) fileMap.get("path");

                    StringBuilder pathSb = new StringBuilder();
                    for (int i = 0; i < pathList.size(); i++) {
                        if (i > 0)
                            pathSb.append(File.separator);
                        pathSb.append(String.valueOf(pathList.get(i)));
                    }

                    fileEntries.add(new FileEntry(pathSb.toString(), length, offset));
                    offset += length;
                }
                totalSize = offset;
            }

            completedPieces = new boolean[pieceHashes.length];

            Platform.runLater(() -> {
                session.nameProperty().set(torrentName);
                session.setTotalSize(totalSize);
            });

            System.out.println("Parsed info: " + torrentName + ", " + pieceHashes.length +
                    " pieces, piece length=" + pieceLength + ", total=" + totalSize);
            return true;
        } catch (Exception e) {
            System.err.println("Error parsing info dict: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void createFiles() throws IOException {
        File baseDir = new File(downloadDir, torrentName);
        baseDir.mkdirs();

        Set<String> selectedNames = new HashSet<>();
        if (selectedFiles != null) {
            for (TorrentFile tf : selectedFiles) {
                if (tf.isSelected())
                    selectedNames.add(tf.getName());
            }
        }

        for (FileEntry entry : fileEntries) {
            // Filter by selection
            if (!selectedNames.isEmpty() && !selectedNames.contains(entry.path.replace(File.separator, "/"))) {
                entry.skip = true;
                continue;
            }

            File f = new File(baseDir, entry.path + ".temp");
            f.getParentFile().mkdirs();
            if (!f.exists()) {
                f.createNewFile();
            }
        }
    }

    /** Mark which pieces overlap with at least one selected (non-skipped) file. */
    private void computeNeededPieces() {
        neededPieces = new boolean[pieceHashes.length];
        for (FileEntry entry : fileEntries) {
            if (entry.skip)
                continue;
            int firstPiece = (int) (entry.offset / pieceLength);
            int lastPiece = (int) ((entry.offset + entry.length - 1) / pieceLength);
            for (int i = firstPiece; i <= lastPiece; i++) {
                neededPieces[i] = true;
            }
        }
        int count = 0;
        for (boolean b : neededPieces)
            if (b)
                count++;
        System.out.println("Needed pieces: " + count + " / " + pieceHashes.length);
    }

    // =====================================================================
    // PERSISTENT CONNECTION DOWNLOAD ENGINE
    // Each worker connects to ONE peer, stays connected, and downloads
    // many pieces through that single TCP session — just like qBittorrent.
    // =====================================================================

    private void downloadPieces(List<TrackerClient.Peer> peers) {
        int totalPieces = pieceHashes.length;
        lastSpeedCheckTime = System.currentTimeMillis();
        lastSpeedCheckBytes = 0;

        // Use a mutable, thread-safe peer list so re-announce can add more peers
        List<TrackerClient.Peer> livePeers = new java.util.concurrent.CopyOnWriteArrayList<>(peers);

        // Decouple worker pool size from initial peer count, hardcode to 60 for better
        // performance.
        int maxConnections = 60;
        ExecutorService peerPool = Executors.newFixedThreadPool(maxConnections);
        BlockingQueue<Integer> pieceQueue = new LinkedBlockingQueue<>();
        for (int i = 0; i < totalPieces; i++) {
            if (neededPieces[i] && !completedPieces[i]) {
                pieceQueue.add(i);
            }
        }

        Collections.shuffle(livePeers);
        List<Future<?>> workerFutures = new ArrayList<>();
        System.out.println("Starting download: " + pieceQueue.size() + " pieces, "
                + livePeers.size() + " peers, " + maxConnections + " workers");

        // Each worker gets a PERSISTENT connection to a peer
        for (int w = 0; w < maxConnections && w < livePeers.size(); w++) {
            final int workerIdx = w;
            workerFutures.add(peerPool.submit(() -> {
                int peerIdx = workerIdx;
                int consecutiveFailures = 0;
                Random rng = new Random();

                // Stagger worker starts to avoid thundering herd
                try {
                    Thread.sleep(workerIdx * 100L);
                } catch (InterruptedException e) {
                    return;
                }

                while (!stopped && !pieceQueue.isEmpty()) {
                    // Handle pause
                    while (paused && !stopped) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    if (stopped)
                        return;

                    // === Exponential backoff on consecutive failures ===
                    if (consecutiveFailures > 3) {
                        int delay = Math.min(1000 * (1 << Math.min(consecutiveFailures - 3, 5)), 30000);
                        delay += rng.nextInt(1000); // jitter
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }

                    // === Smart peer selection ===
                    TrackerClient.Peer peer = null;
                    while (true) {
                        peer = goodPeers.poll();
                        if (peer != null) {
                            String key = peer.ip + ":" + peer.port;
                            if (activeConnections.add(key)) {
                                break;
                            }
                        } else {
                            break;
                        }
                    }

                    if (peer == null) {
                        int tried = 0;
                        int listSize = livePeers.size();
                        while (tried < listSize) {
                            TrackerClient.Peer candidate = livePeers.get(peerIdx % listSize);
                            peerIdx++;
                            String key = candidate.ip + ":" + candidate.port;
                            if (!badPeers.contains(key) && activeConnections.add(key)) {
                                peer = candidate;
                                break;
                            }
                            tried++;
                        }
                        if (peer == null) {
                            badPeers.clear();
                            System.out.println("Worker " + workerIdx + " cleared bad peer list, retrying...");
                            try {
                                Thread.sleep(5000 + rng.nextInt(3000));
                            } catch (InterruptedException e) {
                                return;
                            }
                            continue;
                        }
                    }

                    final TrackerClient.Peer currentPeer = peer;
                    boolean wasUseful = false;

                    // Establish ONE persistent connection to this peer
                    try (Socket socket = new Socket(ProxyConfig.getPeerProxy())) {
                        socket.connect(new InetSocketAddress(currentPeer.ip, currentPeer.port), 10000);
                        socket.setSoTimeout(30000); // Increased from 15s to 30s to tolerate slow peers
                        socket.setTcpNoDelay(true);
                        socket.setReceiveBufferSize(256 * 1024);
                        socket.setSendBufferSize(64 * 1024);

                        DataInputStream in = new DataInputStream(
                                new BufferedInputStream(socket.getInputStream(), 128 * 1024));
                        DataOutputStream out = new DataOutputStream(
                                new BufferedOutputStream(socket.getOutputStream()));

                        // === Handshake (once per connection) ===
                        byte[] protocol = "BitTorrent protocol".getBytes(StandardCharsets.US_ASCII);
                        out.writeByte(19);
                        out.write(protocol);
                        out.write(new byte[8]); // reserved
                        out.write(magnet.getInfoHash());
                        out.write(TrackerClient.getPeerId().getBytes(StandardCharsets.US_ASCII));
                        out.flush();

                        // Read handshake response
                        int pstrlen = in.readUnsignedByte();
                        if (pstrlen != 19) {
                            badPeers.add(currentPeer.ip + ":" + currentPeer.port);
                            continue;
                        }
                        in.readNBytes(19); // protocol string
                        in.readNBytes(8); // reserved
                        byte[] peerInfoHash = new byte[20];
                        in.readFully(peerInfoHash);
                        if (!Arrays.equals(magnet.getInfoHash(), peerInfoHash)) {
                            badPeers.add(currentPeer.ip + ":" + currentPeer.port);
                            continue;
                        }
                        in.readNBytes(20); // peerId

                        // === Send interested + bitfield + unchoke ===
                        out.writeInt(1);
                        out.writeByte(2); // interested
                        sendBitfieldAndUnchoke(out);

                        // === Wait for unchoke (once per connection) ===
                        boolean unchoked = false;
                        long startTime = System.currentTimeMillis();
                        boolean[] peerHasPiece = new boolean[totalPieces];
                        while (System.currentTimeMillis() - startTime < 15000 && !unchoked && !stopped) {
                            int len = in.readInt();
                            if (len == 0)
                                continue;
                            byte msgId = in.readByte();
                            if (msgId == 1) {
                                unchoked = true;
                            } else if (msgId == 4) {
                                int pIdx = in.readInt();
                                if (pIdx >= 0 && pIdx < totalPieces) {
                                    peerHasPiece[pIdx] = true;
                                }
                            } else if (msgId == 5) {
                                byte[] bitfield = new byte[len - 1];
                                in.readFully(bitfield);
                                for (int i = 0; i < totalPieces; i++) {
                                    if ((bitfield[i / 8] & (1 << (7 - (i % 8)))) != 0) {
                                        peerHasPiece[i] = true;
                                    }
                                }
                            } else {
                                skipBytes(in, len - 1);
                            }
                        }
                        if (!unchoked)
                            continue;

                        System.out.println("Worker " + workerIdx + " connected to " +
                                currentPeer.ip + ":" + currentPeer.port + " - unchoked, downloading...");
                        activeSeedCount.add(currentPeer.ip + ":" + currentPeer.port);
                        updateSeedCount();

                        // === Download MANY pieces through this ONE connection ===
                        while (!stopped && !pieceQueue.isEmpty()) {
                            while (paused && !stopped) {
                                try {
                                    out.writeInt(0);
                                    out.flush();
                                    Thread.sleep(5000);
                                } catch (Exception e) {
                                    break;
                                }
                            }
                            if (stopped)
                                return;

                            Integer pieceIndex = null;
                            for (Integer p : pieceQueue) {
                                if (peerHasPiece[p]) {
                                    if (pieceQueue.remove(p)) {
                                        pieceIndex = p;
                                        break;
                                    }
                                }
                            }

                            if (pieceIndex == null) {
                                break;
                            }

                            try {
                                byte[] pieceData = requestPiece(in, out, pieceIndex, peerHasPiece);
                                if (pieceData != null && verifyPiece(pieceIndex, pieceData)) {
                                    // Write to disk asynchronously — don't block the network
                                    final int idx = pieceIndex;
                                    final byte[] data = pieceData;
                                    completedPieces[pieceIndex] = true;
                                    synchronized (this) {
                                        downloadedBytes += pieceData.length;
                                    }
                                    diskPool.submit(() -> {
                                        try {
                                            writePieceToDisk(idx, data);
                                        } catch (IOException ex) {
                                            System.err.println("Disk write error: " + ex.getMessage());
                                        }
                                    });
                                    updateProgress();
                                    consecutiveFailures = 0;
                                    wasUseful = true;
                                } else {
                                    pieceQueue.add(pieceIndex);
                                    System.err.println("Piece " + pieceIndex + " from " +
                                            currentPeer.ip + ":" + currentPeer.port +
                                            (pieceData == null ? " - no data" : " - hash mismatch"));
                                    break;
                                }
                            } catch (IOException e) {
                                pieceQueue.add(pieceIndex);
                                System.err.println("Piece " + pieceIndex + " connection lost: " + e.getMessage());
                                break;
                            }
                        }

                        // Peer was useful — put it back in the good list for reconnection
                        if (wasUseful) {
                            goodPeers.add(currentPeer);
                        }
                        consecutiveFailures = 0;

                    } catch (java.net.SocketTimeoutException | java.net.ConnectException e) {
                        // Timeout/refused — don't permanently mark as bad, just increment failures
                        consecutiveFailures++;
                        System.err.println("Worker " + workerIdx + " timeout " +
                                currentPeer.ip + ":" + currentPeer.port + " - " + e.getMessage());
                    } catch (Exception e) {
                        consecutiveFailures++;
                        // Protocol errors — mark peer as bad
                        badPeers.add(currentPeer.ip + ":" + currentPeer.port);
                        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                        System.err.println("Worker " + workerIdx + " failed " +
                                currentPeer.ip + ":" + currentPeer.port + " - " + msg);
                    } finally {
                        activeConnections.remove(currentPeer.ip + ":" + currentPeer.port);
                    }
                }
            }));
        }

        // Monitor progress + periodic re-announce for fresh peers
        long lastReannounce = System.currentTimeMillis();
        while (!stopped) {
            boolean allDone = workerFutures.stream().allMatch(Future::isDone);
            updateSpeed();
            updateUploadSpeed();
            if (allDone || (pieceQueue.isEmpty() && isComplete()))
                break;

            // Re-announce every 60 seconds to get fresh peers
            if (System.currentTimeMillis() - lastReannounce > 60_000) {
                lastReannounce = System.currentTimeMillis();
                new Thread(() -> {
                    try {
                        List<TrackerClient.Peer> fresh = getPeers();
                        int added = 0;
                        for (TrackerClient.Peer p : fresh) {
                            boolean exists = livePeers.stream()
                                    .anyMatch(e -> e.ip.equals(p.ip) && e.port == p.port);
                            if (!exists) {
                                livePeers.add(p);
                                added++;
                            }
                        }
                        if (added > 0) {
                            System.out.println(
                                    "Re-announce: added " + added + " new peers (total: " + livePeers.size() + ")");
                        }
                    } catch (Exception ignored) {
                    }
                }, "Re-announce").start();
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }

        peerPool.shutdownNow();
        diskPool.shutdown();
        try {
            diskPool.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
        }
    }

    /**
     * Request a single piece over an already-established connection.
     * Sends all block requests (pipelining) then reads responses.
     */
    private byte[] requestPiece(DataInputStream in, DataOutputStream out, int pieceIndex, boolean[] peerHasPiece)
            throws IOException {
        int expectedPieceSize = getPieceSize(pieceIndex);
        int blockSize = 16384; // 16KB
        int numBlocks = (expectedPieceSize + blockSize - 1) / blockSize;
        byte[] pieceData = new byte[expectedPieceSize];
        boolean[] receivedBlocks = new boolean[numBlocks];
        int receivedCount = 0;
        int requestedCount = 0;
        int maxPendingRequests = 10;
        int nextBlockToRequest = 0;

        // Pipeline blocks with a sliding window
        long lastActivityTime = System.currentTimeMillis();
        long pieceTimeoutMs = 30000; // 30 seconds idle timeout
        while (receivedCount < numBlocks && System.currentTimeMillis() - lastActivityTime < pieceTimeoutMs) {

            // Fill pipeline up to maxPendingRequests
            while (requestedCount - receivedCount < maxPendingRequests && nextBlockToRequest < numBlocks) {
                int offset = nextBlockToRequest * blockSize;
                int length = Math.min(blockSize, expectedPieceSize - offset);
                out.writeInt(13); // message length
                out.writeByte(6); // request
                out.writeInt(pieceIndex);
                out.writeInt(offset);
                out.writeInt(length);
                nextBlockToRequest++;
                requestedCount++;
            }
            out.flush();

            int len = in.readInt();
            if (len == 0)
                continue; // keep-alive
            byte msgId = in.readByte();

            if (msgId == 7) { // piece
                lastActivityTime = System.currentTimeMillis();
                int index = in.readInt();
                int begin = in.readInt();
                int dataLen = len - 9;
                byte[] blockData = new byte[dataLen];
                in.readFully(blockData);

                if (index == pieceIndex && begin < expectedPieceSize) {
                    System.arraycopy(blockData, 0, pieceData, begin,
                            Math.min(dataLen, expectedPieceSize - begin));
                    int blockIdx = begin / blockSize;
                    if (blockIdx < numBlocks && !receivedBlocks[blockIdx]) {
                        receivedBlocks[blockIdx] = true;
                        receivedCount++;
                    }
                }
            } else if (msgId == 0) { // choke
                return null;
            } else if (msgId == 4) { // HAVE
                int pIdx = in.readInt();
                if (pIdx >= 0 && pIdx < peerHasPiece.length) {
                    peerHasPiece[pIdx] = true;
                }
            } else if (msgId == 6) { // REQUEST from peer — serve upload
                int reqIdx = in.readInt();
                int reqBegin = in.readInt();
                int reqLen = in.readInt();
                handleRequest(in, out, reqIdx, reqBegin, reqLen);
            } else {
                skipBytes(in, len - 1);
            }
        }

        return (receivedCount == numBlocks) ? pieceData : null;
    }

    /** Efficiently skip bytes from input stream. */
    private void skipBytes(DataInputStream in, int count) throws IOException {
        if (count <= 0)
            return;
        byte[] buf = new byte[Math.min(count, 8192)];
        int remaining = count;
        while (remaining > 0) {
            int toRead = Math.min(remaining, buf.length);
            in.readFully(buf, 0, toRead);
            remaining -= toRead;
        }
    }

    private int getPieceSize(int pieceIndex) {
        if (pieceIndex == pieceHashes.length - 1) {
            int remainder = (int) (totalSize % pieceLength);
            return remainder == 0 ? pieceLength : remainder;
        }
        return pieceLength;
    }

    private boolean verifyPiece(int pieceIndex, byte[] data) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(data);
            return Arrays.equals(hash, pieceHashes[pieceIndex]);
        } catch (Exception e) {
            return false;
        }
    }

    private void writePieceToDisk(int pieceIndex, byte[] data) throws IOException {
        long pieceStart = (long) pieceIndex * pieceLength;
        long pieceEnd = pieceStart + data.length;
        File baseDir = new File(downloadDir, torrentName);

        for (FileEntry entry : fileEntries) {
            if (entry.skip)
                continue;

            long fileStart = entry.offset;
            long fileEnd = entry.offset + entry.length;

            if (pieceEnd <= fileStart || pieceStart >= fileEnd)
                continue;

            long overlapStart = Math.max(pieceStart, fileStart);
            long overlapEnd = Math.min(pieceEnd, fileEnd);

            int dataOffset = (int) (overlapStart - pieceStart);
            long fileOffset = overlapStart - fileStart;
            int writeLen = (int) (overlapEnd - overlapStart);

            File f = new File(baseDir, entry.path + ".temp");
            try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                raf.seek(fileOffset);
                raf.write(data, dataOffset, writeLen);
            }

            // Check if this file is now fully downloaded and rename it
            if (!entry.renamed && isFileComplete(entry)) {
                File target = new File(baseDir, entry.path);
                if (f.exists() && f.renameTo(target)) {
                    entry.renamed = true;
                    System.out.println("File complete, renamed: " + entry.path);
                }
            }
        }
    }

    /** Check if all pieces that cover this file entry have been downloaded. */
    private boolean isFileComplete(FileEntry entry) {
        long fileStart = entry.offset;
        long fileEnd = entry.offset + entry.length;

        int firstPiece = (int) (fileStart / pieceLength);
        int lastPiece = (int) ((fileEnd - 1) / pieceLength);

        for (int i = firstPiece; i <= lastPiece; i++) {
            if (!completedPieces[i])
                return false;
        }
        return true;
    }

    private void updateProgress() {
        if (session.isDeleted())
            return;
        int completed = 0;
        int needed = 0;
        for (int i = 0; i < completedPieces.length; i++) {
            if (neededPieces[i]) {
                needed++;
                if (completedPieces[i])
                    completed++;
            }
        }
        double progress = needed > 0 ? (double) completed / needed : 0;
        Platform.runLater(() -> session.progressProperty().set(progress));
    }

    private void updateSpeed() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastSpeedCheckTime;
        if (elapsed < 1000)
            return;

        long currentBytes;
        synchronized (this) {
            currentBytes = downloadedBytes;
        }
        long bytesInInterval = currentBytes - lastSpeedCheckBytes;
        double instantSpeed = (double) bytesInInterval / (elapsed / 1000.0);

        lastSpeedCheckBytes = currentBytes;
        lastSpeedCheckTime = now;

        // Exponential moving average (alpha=0.3) to smooth speed display
        // Prevents flashing between xxx KB/s and 0 KB/s during peer reconnections
        smoothedSpeed = 0.3 * instantSpeed + 0.7 * smoothedSpeed;

        String speedStr;
        if (smoothedSpeed >= 1024 * 1024) {
            speedStr = String.format("%.1f MB/s", smoothedSpeed / (1024 * 1024));
        } else if (smoothedSpeed >= 1024) {
            speedStr = String.format("%.1f KB/s", smoothedSpeed / 1024);
        } else {
            speedStr = String.format("%.0f B/s", smoothedSpeed);
        }

        Platform.runLater(() -> session.dlSpeedProperty().set(speedStr));
    }

    private boolean isComplete() {
        for (int i = 0; i < completedPieces.length; i++) {
            if (neededPieces[i] && !completedPieces[i])
                return false;
        }
        return true;
    }

    private void finishDownload() {
        File baseDir = new File(downloadDir, torrentName);
        for (FileEntry entry : fileEntries) {
            if (entry.skip)
                continue;
            File temp = new File(baseDir, entry.path + ".temp");
            File target = new File(baseDir, entry.path);
            if (temp.exists()) {
                temp.renameTo(target);
            }
        }
    }

    private void updateStatus(String status) {
        if (session.isDeleted())
            return;
        Platform.runLater(() -> session.statusProperty().set(status));
    }

    private void updateSeedCount() {
        int count = activeSeedCount.size();
        Platform.runLater(() -> session.seedsProperty().set(count));
    }

    // =====================================================================
    // UPLOAD SUPPORT
    // =====================================================================

    /** Build a BITFIELD message showing which pieces we have. */
    private byte[] buildBitfield() {
        int numBytes = (completedPieces.length + 7) / 8;
        byte[] bitfield = new byte[numBytes];
        for (int i = 0; i < completedPieces.length; i++) {
            if (completedPieces[i]) {
                bitfield[i / 8] |= (byte) (0x80 >> (i % 8));
            }
        }
        return bitfield;
    }

    /** Send BITFIELD + UNCHOKE so the peer knows our pieces and can request. */
    private void sendBitfieldAndUnchoke(DataOutputStream out) throws IOException {
        byte[] bitfield = buildBitfield();
        boolean hasPieces = false;
        for (byte b : bitfield)
            if (b != 0) {
                hasPieces = true;
                break;
            }

        if (hasPieces) {
            // BITFIELD: length=1+bitfield.length, id=5
            out.writeInt(1 + bitfield.length);
            out.writeByte(5);
            out.write(bitfield);
        }

        // UNCHOKE: length=1, id=1
        out.writeInt(1);
        out.writeByte(1);
        out.flush();
    }

    /**
     * Handle an incoming REQUEST message (msgId=6).
     * Reads: pieceIndex(4) + begin(4) + length(4) = 12 bytes already consumed.
     * Responds with a PIECE message if we have the data.
     */
    private void handleRequest(DataInputStream in, DataOutputStream out,
            int pieceIndex, int begin, int length) throws IOException {
        if (pieceIndex < 0 || pieceIndex >= completedPieces.length || !completedPieces[pieceIndex]) {
            return; // Don't have this piece
        }

        // Cap block size at 16KB (BT spec)
        if (length > 16384)
            length = 16384;

        byte[] pieceData = readPieceFromDisk(pieceIndex);
        if (pieceData == null || begin + length > pieceData.length) {
            return;
        }

        // PIECE message: length=9+blockLen, id=7, index, begin, block
        out.writeInt(9 + length);
        out.writeByte(7);
        out.writeInt(pieceIndex);
        out.writeInt(begin);
        out.write(pieceData, begin, length);
        out.flush();

        synchronized (this) {
            uploadedBytes += length;
        }
    }

    /** Read a complete piece from disk by reassembling from file entries. */
    private byte[] readPieceFromDisk(int pieceIndex) {
        try {
            int size = getPieceSize(pieceIndex);
            byte[] data = new byte[size];
            long pieceStart = (long) pieceIndex * pieceLength;
            long pieceEnd = pieceStart + size;
            File baseDir = new File(downloadDir, torrentName);

            for (FileEntry entry : fileEntries) {
                if (entry.skip)
                    continue;
                long fileStart = entry.offset;
                long fileEnd = entry.offset + entry.length;
                if (pieceEnd <= fileStart || pieceStart >= fileEnd)
                    continue;

                long overlapStart = Math.max(pieceStart, fileStart);
                long overlapEnd = Math.min(pieceEnd, fileEnd);
                int dataOffset = (int) (overlapStart - pieceStart);
                long fileOffset = overlapStart - fileStart;
                int readLen = (int) (overlapEnd - overlapStart);

                // Try final file first, then .temp
                File f = new File(baseDir, entry.path);
                if (!f.exists())
                    f = new File(baseDir, entry.path + ".temp");
                if (!f.exists())
                    return null;

                try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                    raf.seek(fileOffset);
                    raf.readFully(data, dataOffset, readLen);
                }
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    /** Update upload speed display. */
    private void updateUploadSpeed() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastUlSpeedCheckTime;
        if (elapsed < 1000)
            return;

        long currentBytes;
        synchronized (this) {
            currentBytes = uploadedBytes;
        }
        long bytesInInterval = currentBytes - lastUlSpeedCheckBytes;
        double instantSpeed = (double) bytesInInterval / (elapsed / 1000.0);

        lastUlSpeedCheckBytes = currentBytes;
        lastUlSpeedCheckTime = now;

        smoothedUlSpeed = 0.3 * instantSpeed + 0.7 * smoothedUlSpeed;

        String speedStr;
        if (smoothedUlSpeed >= 1024 * 1024) {
            speedStr = String.format("%.1f MB/s", smoothedUlSpeed / (1024 * 1024));
        } else if (smoothedUlSpeed >= 1024) {
            speedStr = String.format("%.1f KB/s", smoothedUlSpeed / 1024);
        } else {
            speedStr = String.format("%.0f B/s", smoothedUlSpeed);
        }

        Platform.runLater(() -> session.ulSpeedProperty().set(speedStr));
    }

    /** Start a server socket to accept incoming peer connections for uploading. */
    private void startPeerServer() {
        new Thread(() -> {
            try {
                peerServer = new ServerSocket(listenPort, 50);
                peerServer.setSoTimeout(1000); // check stopped every 1s
                System.out.println("Peer server listening on port " + listenPort);

                while (!stopped) {
                    try {
                        Socket client = peerServer.accept();
                        // Handle incoming peer in the thread pool
                        new Thread(() -> handleIncomingPeer(client), "InPeer-" + client.getRemoteSocketAddress())
                                .start();
                    } catch (SocketTimeoutException ignored) {
                        // Just check stopped flag
                    }
                }
            } catch (BindException e) {
                System.err.println("Peer server: port " + listenPort + " in use, incoming connections disabled");
            } catch (IOException e) {
                if (!stopped)
                    System.err.println("Peer server error: " + e.getMessage());
            } finally {
                if (peerServer != null)
                    try {
                        peerServer.close();
                    } catch (IOException ignored) {
                    }
            }
        }, "PeerServer").start();
    }

    /** Handle a single incoming peer connection (upload only). */
    private void handleIncomingPeer(Socket client) {
        try {
            client.setSoTimeout(30000);
            client.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(new BufferedInputStream(client.getInputStream(), 64 * 1024));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(client.getOutputStream()));

            // Read incoming handshake
            int pstrlen = in.readUnsignedByte();
            if (pstrlen != 19)
                return;
            in.readNBytes(19);
            in.readNBytes(8);
            byte[] peerInfoHash = new byte[20];
            in.readFully(peerInfoHash);
            if (!Arrays.equals(magnet.getInfoHash(), peerInfoHash))
                return;
            in.readNBytes(20); // peer id

            // Send our handshake
            byte[] protocol = "BitTorrent protocol".getBytes(StandardCharsets.US_ASCII);
            out.writeByte(19);
            out.write(protocol);
            out.write(new byte[8]);
            out.write(magnet.getInfoHash());
            out.write(TrackerClient.getPeerId().getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Send bitfield + unchoke
            sendBitfieldAndUnchoke(out);

            // Serve requests
            while (!stopped) {
                int len = in.readInt();
                if (len == 0)
                    continue; // keep-alive
                byte msgId = in.readByte();

                if (msgId == 6) { // REQUEST
                    int idx = in.readInt();
                    int begin = in.readInt();
                    int blockLen = in.readInt();
                    handleRequest(in, out, idx, begin, blockLen);
                } else if (msgId == 2) { // INTERESTED
                    // Already unchoked, no action needed
                } else {
                    skipBytes(in, len - 1);
                }
            }
        } catch (Exception ignored) {
            // Connection closed or error
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Internal file info */
    private static class FileEntry {
        final String path;
        final long length;
        final long offset;
        boolean skip = false;
        boolean renamed = false;

        FileEntry(String path, long length, long offset) {
            this.path = path;
            this.length = length;
            this.offset = offset;
        }
    }
}
