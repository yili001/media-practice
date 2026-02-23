package com.media.center.service;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.UUID;

public class ContentServer {
    private HttpServer server;
    private final int port = 8192;
    private String hostname;
    private static File activeFile = null;
    private static String activeFileId = null;

    public ContentServer() throws IOException {
        String host = InetAddress.getLocalHost().getHostAddress();
        this.hostname = host;

        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/video", new FileHandler());
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    }

    public void start() {
        server.start();
        System.out.println("Content Server started at http://" + hostname + ":" + port + "/video/");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public void setFileToServe(File file) {
        activeFile = file;
        activeFileId = UUID.randomUUID().toString();
    }

    public String getServeUrl() {
        if (activeFile == null || activeFileId == null)
            return "";
        try {
            String ext = "";
            int i = activeFile.getName().lastIndexOf('.');
            if (i > 0) {
                ext = activeFile.getName().substring(i);
            }
            return "http://" + hostname + ":" + port + "/video/" + activeFileId + ext;
        } catch (Exception e) {
            return "http://" + hostname + ":" + port + "/video/media.mp4";
        }
    }

    public int getPort() {
        return port;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    static class FileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String requestURI = exchange.getRequestURI().toString();
            String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
            System.out.println("ContentServer: Incoming request from " + clientIp + " for " + requestURI);

            if (activeFile == null || !activeFile.exists()) {
                System.out.println("ContentServer: 404 Not Found - No active file to serve");
                String response = "404 (Not Found)";
                exchange.sendResponseHeaders(404, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            File file = activeFile;
            long fileLen = file.length();
            String range = exchange.getRequestHeaders().getFirst("Range");
            long start = 0;
            long end = fileLen - 1;

            if (range != null && range.startsWith("bytes=")) {
                System.out.println("ContentServer: Range request requested: " + range);
                String[] ranges = range.substring("bytes=".length()).split("-");
                try {
                    start = Long.parseLong(ranges[0]);
                    if (ranges.length > 1 && !ranges[1].isEmpty()) {
                        end = Long.parseLong(ranges[1]);
                    }
                } catch (NumberFormatException e) {
                    System.out.println("ContentServer: Invalid range requested: " + range);
                }
            }

            String contentType = Files.probeContentType(file.toPath());
            if (contentType == null) {
                String name = file.getName().toLowerCase();
                if (name.endsWith(".mp3"))
                    contentType = "audio/mpeg";
                else if (name.endsWith(".flac"))
                    contentType = "audio/flac";
                else if (name.endsWith(".wav"))
                    contentType = "audio/wav";
                else
                    contentType = "video/mp4";
            }

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");

            if (range != null && range.startsWith("bytes=")) {
                exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + fileLen);
                exchange.sendResponseHeaders(206, end - start + 1);
            } else {
                exchange.sendResponseHeaders(200, fileLen);
            }

            try (FileInputStream fis = new FileInputStream(file);
                    OutputStream os = exchange.getResponseBody()) {
                fis.skip(start);
                byte[] buffer = new byte[8192];
                int bytesRead;
                long bytesToRead = end - start + 1;
                while (bytesToRead > 0
                        && (bytesRead = fis.read(buffer, 0, (int) Math.min(buffer.length, bytesToRead))) != -1) {
                    os.write(buffer, 0, bytesRead);
                    bytesToRead -= bytesRead;
                }
                System.out.println("ContentServer: Successfully served chunk to " + clientIp);
            } catch (IOException e) {
                // Ignore broken pipe/connection reset exceptions
                System.out.println(
                        "ContentServer: Connection closed by client " + clientIp + " (" + e.getMessage() + ")");
            }
        }
    }
}
