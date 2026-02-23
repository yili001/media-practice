package com.media.center.service.torrent;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * UDP Tracker Client implementing BEP 15 (UDP Tracker Protocol).
 * https://www.bittorrent.org/beps/bep_0015.html
 */
public class UdpTrackerClient {

    private static final long CONNECT_MAGIC = 0x41727101980L;
    private static final int ACTION_CONNECT = 0;
    private static final int ACTION_ANNOUNCE = 1;
    private static final int TIMEOUT_MS = 10000;

    /**
     * Query a UDP tracker for peers.
     *
     * @param trackerUrl Full UDP tracker URL, e.g.
     *                   "udp://tracker.opentrackr.org:1337/announce"
     * @param infoHash   20-byte info hash
     * @return List of peers, empty if failed
     */
    public static List<TrackerClient.Peer> getPeers(String trackerUrl, byte[] infoHash) {
        List<TrackerClient.Peer> peers = new ArrayList<>();
        DatagramSocket socket = null;
        try {
            // Parse URL
            URI uri = new URI(trackerUrl);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || port <= 0) {
                System.err.println("UDP Tracker: invalid URL: " + trackerUrl);
                return peers;
            }

            InetAddress address = InetAddress.getByName(host);
            socket = new DatagramSocket();
            socket.setSoTimeout(TIMEOUT_MS);

            // Step 1: Connect
            Random random = new Random();
            int transactionId = random.nextInt();

            ByteBuffer connectReq = ByteBuffer.allocate(16);
            connectReq.putLong(CONNECT_MAGIC); // connection_id (magic)
            connectReq.putInt(ACTION_CONNECT); // action = connect
            connectReq.putInt(transactionId); // transaction_id

            byte[] connectData = connectReq.array();
            socket.send(new DatagramPacket(connectData, connectData.length, address, port));

            // Receive connect response (16 bytes)
            byte[] connectResp = new byte[16];
            DatagramPacket connectRespPacket = new DatagramPacket(connectResp, connectResp.length);
            socket.receive(connectRespPacket);

            ByteBuffer respBuf = ByteBuffer.wrap(connectResp);
            int respAction = respBuf.getInt();
            int respTransId = respBuf.getInt();
            long connectionId = respBuf.getLong();

            if (respAction != ACTION_CONNECT || respTransId != transactionId) {
                System.err.println("UDP Tracker: bad connect response from " + trackerUrl);
                return peers;
            }

            // Step 2: Announce
            int announceTransId = random.nextInt();
            byte[] peerId = TrackerClient.getPeerId().getBytes();

            ByteBuffer announceReq = ByteBuffer.allocate(98);
            announceReq.putLong(connectionId); // connection_id
            announceReq.putInt(ACTION_ANNOUNCE); // action = announce
            announceReq.putInt(announceTransId); // transaction_id
            announceReq.put(infoHash); // info_hash (20 bytes)
            announceReq.put(peerId, 0, 20); // peer_id (20 bytes)
            announceReq.putLong(0); // downloaded
            announceReq.putLong(0); // left
            announceReq.putLong(0); // uploaded
            announceReq.putInt(0); // event (0 = none)
            announceReq.putInt(0); // IP address (0 = default)
            announceReq.putInt(random.nextInt()); // key
            announceReq.putInt(-1); // num_want (-1 = default)
            announceReq.putShort((short) TorrentDownloader.getListenPort()); // port

            byte[] announceData = announceReq.array();
            socket.send(new DatagramPacket(announceData, announceData.length, address, port));

            // Receive announce response (20+ bytes: header + 6 bytes per peer)
            byte[] announceResp = new byte[4096];
            DatagramPacket announceRespPacket = new DatagramPacket(announceResp, announceResp.length);
            socket.receive(announceRespPacket);

            int receivedLen = announceRespPacket.getLength();
            if (receivedLen < 20) {
                System.err.println("UDP Tracker: short announce response from " + trackerUrl);
                return peers;
            }

            ByteBuffer annBuf = ByteBuffer.wrap(announceResp, 0, receivedLen);
            int annAction = annBuf.getInt();
            int annTransId = annBuf.getInt();

            if (annAction != ACTION_ANNOUNCE || annTransId != announceTransId) {
                System.err.println("UDP Tracker: bad announce response from " + trackerUrl);
                return peers;
            }

            int interval = annBuf.getInt(); // interval (unused for now)
            int leechers = annBuf.getInt(); // leechers
            int seeders = annBuf.getInt(); // seeders

            // Parse compact peers (6 bytes each: 4 IP + 2 port)
            int peerDataLen = receivedLen - 20;
            for (int i = 0; i < peerDataLen; i += 6) {
                if (i + 6 > peerDataLen)
                    break;

                int offset = 20 + i;
                String ip = String.format("%d.%d.%d.%d",
                        announceResp[offset] & 0xFF,
                        announceResp[offset + 1] & 0xFF,
                        announceResp[offset + 2] & 0xFF,
                        announceResp[offset + 3] & 0xFF);
                int peerPort = ((announceResp[offset + 4] & 0xFF) << 8) | (announceResp[offset + 5] & 0xFF);

                if (peerPort > 0 && peerPort < 65535 && !"0.0.0.0".equals(ip)) {
                    peers.add(new TrackerClient.Peer(ip, peerPort));
                }
            }

        } catch (Exception e) {
            System.err.println("UDP Tracker failed: " + trackerUrl + " - " + e.getMessage());
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
        return peers;
    }
}
