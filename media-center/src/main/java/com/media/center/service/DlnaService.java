package com.media.center.service;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public class DlnaService {
    private final ObservableList<DeviceModel> devices = FXCollections.observableArrayList();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "DLNA-Worker");
        t.setDaemon(true);
        return t;
    });

    // SSDP constants
    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1";
    private static final String AV_TRANSPORT_TYPE = "urn:schemas-upnp-org:service:AVTransport:1";

    public DlnaService() {
    }

    public void start() {
        search(null);
    }

    /**
     * Send SSDP M-SEARCH and discover DLNA media renderers on the LAN.
     * If bindAddress is provided, the multicast socket is bound to that interface.
     */
    public void search(java.net.InetAddress bindAddress) {
        executor.submit(() -> {
            System.out.println("DLNA: Starting SSDP discovery"
                    + (bindAddress != null ? " on " + bindAddress.getHostAddress() : "") + "...");
            List<DeviceModel> found = new ArrayList<>();
            Set<String> seenUdns = new HashSet<>();

            try {
                // Build M-SEARCH request
                String msearch = "M-SEARCH * HTTP/1.1\r\n"
                        + "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n"
                        + "MAN: \"ssdp:discover\"\r\n"
                        + "MX: 3\r\n"
                        + "ST: " + SEARCH_TARGET + "\r\n"
                        + "\r\n";

                byte[] data = msearch.getBytes(StandardCharsets.UTF_8);
                InetAddress group = InetAddress.getByName(SSDP_ADDR);
                InetSocketAddress groupAddr = new InetSocketAddress(group, SSDP_PORT);

                try (MulticastSocket socket = new MulticastSocket(null)) {
                    socket.setReuseAddress(true);

                    // Bind to the specific interface if provided
                    if (bindAddress != null) {
                        socket.bind(new InetSocketAddress(bindAddress, 0));
                        NetworkInterface ni = NetworkInterface.getByInetAddress(bindAddress);
                        if (ni != null) {
                            socket.setNetworkInterface(ni);
                            socket.joinGroup(groupAddr, ni);
                            System.out.println("DLNA: Joined multicast on " + ni.getDisplayName()
                                    + " (" + bindAddress.getHostAddress() + ")");
                        } else {
                            socket.joinGroup(groupAddr, null);
                        }
                    } else {
                        socket.bind(new InetSocketAddress(0));
                        socket.joinGroup(groupAddr, null);
                    }

                    socket.setSoTimeout(4000);
                    socket.setTimeToLive(4);

                    // Send M-SEARCH three times for reliability
                    DatagramPacket packet = new DatagramPacket(data, data.length, group, SSDP_PORT);
                    socket.send(packet);
                    Thread.sleep(200);
                    socket.send(packet);
                    Thread.sleep(200);
                    socket.send(packet);

                    System.out.println("DLNA: M-SEARCH sent, waiting for responses...");

                    // Collect responses
                    byte[] buf = new byte[4096];
                    long deadline = System.currentTimeMillis() + 4000;

                    while (System.currentTimeMillis() < deadline) {
                        try {
                            DatagramPacket resp = new DatagramPacket(buf, buf.length);
                            socket.receive(resp);
                            String response = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8);

                            System.out.println("DLNA: Got response from " + resp.getAddress().getHostAddress());

                            String location = extractHeader(response, "LOCATION");
                            if (location == null || location.isEmpty()) {
                                System.out.println("DLNA: Response has no LOCATION header, skipping");
                                continue;
                            }

                            System.out.println("DLNA: Fetching device description: " + location);

                            // Fetch and parse device description
                            DeviceModel device = fetchDeviceDescription(location);
                            if (device != null && !seenUdns.contains(device.getUdn())) {
                                seenUdns.add(device.getUdn());
                                found.add(device);
                                System.out.println("DLNA: Found device: " + device.toString()
                                        + " at " + device.getControlUrl());
                            }
                        } catch (SocketTimeoutException e) {
                            break; // done waiting
                        }
                    }

                    // Leave the multicast group
                    try {
                        if (bindAddress != null) {
                            NetworkInterface ni = NetworkInterface.getByInetAddress(bindAddress);
                            socket.leaveGroup(groupAddr, ni);
                        } else {
                            socket.leaveGroup(groupAddr, null);
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                System.err.println("DLNA: Discovery error: " + e.getMessage());
            }

            System.out.println("DLNA: Discovery complete. Found " + found.size() + " device(s).");

            // Update UI on JavaFX thread
            Platform.runLater(() -> {
                devices.clear();
                devices.addAll(found);
            });
        });
    }

    /**
     * Extract a header value from an HTTP response string (case-insensitive).
     */
    private String extractHeader(String response, String headerName) {
        for (String line : response.split("\r\n")) {
            if (line.toUpperCase().startsWith(headerName.toUpperCase() + ":")) {
                return line.substring(headerName.length() + 1).trim();
            }
        }
        return null;
    }

    /**
     * Fetch the UPnP device description XML and extract friendly name, UDN,
     * and AVTransport control URL.
     */
    private DeviceModel fetchDeviceDescription(String locationUrl) {
        try {
            URL url = new URL(locationUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() != 200)
                return null;

            String xml;
            try (InputStream is = conn.getInputStream()) {
                xml = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xml)));

            // Extract device info
            String friendlyName = getTagText(doc, "friendlyName");
            String udn = getTagText(doc, "UDN");

            if (friendlyName == null || udn == null)
                return null;

            // Find AVTransport service control URL
            String controlUrl = findAVTransportControlUrl(doc);
            if (controlUrl == null)
                return null;

            // Resolve relative control URL against the device base URL
            controlUrl = resolveUrl(locationUrl, controlUrl);

            return new DeviceModel(friendlyName, udn, controlUrl);

        } catch (Exception e) {
            System.err.println("DLNA: Failed to parse device at " + locationUrl + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Find the AVTransport service control URL from the device XML.
     */
    private String findAVTransportControlUrl(Document doc) {
        NodeList serviceList = doc.getElementsByTagName("service");
        for (int i = 0; i < serviceList.getLength(); i++) {
            Element service = (Element) serviceList.item(i);
            String serviceType = getChildText(service, "serviceType");
            if (AV_TRANSPORT_TYPE.equals(serviceType)) {
                return getChildText(service, "controlURL");
            }
        }
        return null;
    }

    /**
     * Resolve a potentially relative URL against a base URL.
     */
    private String resolveUrl(String baseUrl, String relativeUrl) {
        try {
            if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://"))
                return relativeUrl;
            return new URL(new URL(baseUrl), relativeUrl).toString();
        } catch (MalformedURLException e) {
            return relativeUrl;
        }
    }

    /**
     * Get the text content of the first element with the given tag name.
     */
    private String getTagText(Document doc, String tagName) {
        NodeList nodes = doc.getElementsByTagName(tagName);
        if (nodes.getLength() > 0)
            return nodes.item(0).getTextContent().trim();
        return null;
    }

    /**
     * Get the text content of a direct child element with the given tag name.
     */
    private String getChildText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0)
            return nodes.item(0).getTextContent().trim();
        return null;
    }

    // =================================================================
    // AVTransport SOAP Control
    // =================================================================

    /**
     * Cast media to a DLNA device: set the URI and start playback.
     */
    public void play(DeviceModel device, String mediaUrl, String title) {
        executor.submit(() -> {
            try {
                System.out.println("DLNA: Sending SetAVTransportURI to " + device + " -> " + mediaUrl);

                // 1. Set the media URI
                String didl = buildDIDL(title, mediaUrl);
                String setUriBody = buildSoapEnvelope("SetAVTransportURI",
                        "<InstanceID>0</InstanceID>"
                                + "<CurrentURI>" + escapeXml(mediaUrl) + "</CurrentURI>"
                                + "<CurrentURIMetaData>" + escapeXml(didl) + "</CurrentURIMetaData>");
                sendSoapAction(device.getControlUrl(), "SetAVTransportURI", setUriBody);

                // 2. Play
                String playBody = buildSoapEnvelope("Play",
                        "<InstanceID>0</InstanceID>"
                                + "<Speed>1</Speed>");
                sendSoapAction(device.getControlUrl(), "Play", playBody);

                System.out.println("DLNA: Play command sent successfully.");
                Platform.runLater(() -> System.out.println("DLNA: Playing on " + device));
            } catch (Exception e) {
                System.err.println("DLNA: Play failed: " + e.getMessage());
            }
        });
    }

    /**
     * Stop playback on a DLNA device.
     */
    public void stop(DeviceModel device) {
        if (device == null)
            return;
        executor.submit(() -> {
            try {
                System.out.println("DLNA: Sending Stop to " + device);
                String stopBody = buildSoapEnvelope("Stop",
                        "<InstanceID>0</InstanceID>");
                sendSoapAction(device.getControlUrl(), "Stop", stopBody);
                System.out.println("DLNA: Stop command sent successfully.");
            } catch (Exception e) {
                System.err.println("DLNA: Stop failed: " + e.getMessage());
            }
        });
    }

    /**
     * Build a SOAP envelope for an AVTransport action.
     */
    private String buildSoapEnvelope(String action, String bodyContent) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body>"
                + "<u:" + action + " xmlns:u=\"" + AV_TRANSPORT_TYPE + "\">"
                + bodyContent
                + "</u:" + action + ">"
                + "</s:Body>"
                + "</s:Envelope>";
    }

    /**
     * Build minimal DIDL-Lite metadata for the media item.
     */
    private String buildDIDL(String title, String url) {
        String mimeType = guessMimeType(url);
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
                + "<dc:title>" + escapeXml(title) + "</dc:title>"
                + "<upnp:class>object.item.videoItem</upnp:class>"
                + "<res protocolInfo=\"http-get:*:" + mimeType + ":*\">" + escapeXml(url) + "</res>"
                + "</item>"
                + "</DIDL-Lite>";
    }

    /**
     * Send a SOAP action to the device's AVTransport control URL.
     */
    private void sendSoapAction(String controlUrl, String action, String soapBody) throws IOException {
        System.out.println("\n--- SOAP Request ---");
        System.out.println("DLNA: Action: " + action);
        System.out.println("DLNA: URL: " + controlUrl);
        System.out.println("DLNA: Body:\n" + soapBody);

        URL url = new URL(controlUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        conn.setRequestProperty("SOAPAction", "\"" + AV_TRANSPORT_TYPE + "#" + action + "\"");

        byte[] body = soapBody.getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Length", String.valueOf(body.length));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }

        int code = conn.getResponseCode();
        System.out.println("\n--- SOAP Response ---");
        System.out.println("DLNA: " + action + " -> HTTP " + code);

        if (code >= 200 && code < 300) {
            String responseBody = "";
            try (InputStream is = conn.getInputStream()) {
                if (is != null)
                    responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            System.out.println("DLNA: Response Body:\n" + responseBody);
        } else {
            String error = "";
            try (InputStream es = conn.getErrorStream()) {
                if (es != null)
                    error = new String(es.readAllBytes(), StandardCharsets.UTF_8);
            }
            System.err.println("DLNA: Error Body:\n" + error);
        }
        System.out.println("--------------------\n");
    }

    /**
     * Guess MIME type from URL (simple extension-based).
     */
    private String guessMimeType(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".mp4"))
            return "video/mp4";
        if (lower.contains(".mkv"))
            return "video/x-matroska";
        if (lower.contains(".avi"))
            return "video/x-msvideo";
        if (lower.contains(".mp3"))
            return "audio/mpeg";
        if (lower.contains(".flac"))
            return "audio/flac";
        if (lower.contains(".wav"))
            return "audio/wav";
        return "video/mp4"; // default
    }

    /**
     * Escape XML special characters.
     */
    private String escapeXml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    public ObservableList<DeviceModel> getDevices() {
        return devices;
    }
}
