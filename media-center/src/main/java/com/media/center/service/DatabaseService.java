package com.media.center.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class DatabaseService {
    private static final String DB_URL = "jdbc:sqlite:mediacenter.db";

    public DatabaseService() {
        init();
        System.out.println("Database path: " + new java.io.File("mediacenter.db").getAbsolutePath());
    }

    private void init() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

        try (Connection conn = DriverManager.getConnection(DB_URL);
                Statement stmt = conn.createStatement()) {

            // App Config Table
            String sqlConfig = "CREATE TABLE IF NOT EXISTS app_config (" +
                    "key TEXT PRIMARY KEY, " +
                    "value TEXT)";
            stmt.execute(sqlConfig);

            // Download Sessions Table
            String sqlSessions = "CREATE TABLE IF NOT EXISTS download_sessions (" +
                    "magnet_link TEXT PRIMARY KEY, " +
                    "name TEXT, " +
                    "status TEXT, " +
                    "progress REAL)";
            stmt.execute(sqlSessions);

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void saveConfig(String key, String value) {
        String sql = "INSERT OR REPLACE INTO app_config(key, value) VALUES(?, ?)";
        try (Connection conn = DriverManager.getConnection(DB_URL);
                PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, key);
            pstmt.setString(2, value);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public String getConfig(String key) {
        String sql = "SELECT value FROM app_config WHERE key = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
                PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, key);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getString("value");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void saveSession(DownloadSession session) {
        if (session.isDeleted())
            return;
        String sql = "INSERT OR REPLACE INTO download_sessions(magnet_link, name, status, progress) VALUES(?, ?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(DB_URL);
                PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, session.getMagnetLink());
            pstmt.setString(2, session.nameProperty().get());
            pstmt.setString(3, session.statusProperty().get());
            pstmt.setDouble(4, session.progressProperty().get());
            pstmt.executeUpdate();
            System.out.println("Saved session: " + session.getName() + " [" + session.statusProperty().get() + "]");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public List<DownloadSession> loadSessions() {
        List<DownloadSession> sessions = new ArrayList<>();
        String sql = "SELECT magnet_link, name, status, progress FROM download_sessions";
        try (Connection conn = DriverManager.getConnection(DB_URL);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                String magnet = rs.getString("magnet_link");
                String name = rs.getString("name");
                String status = rs.getString("status");
                double progress = rs.getDouble("progress");

                DownloadSession session = new DownloadSession(magnet);
                session.nameProperty().set(name);
                session.statusProperty().set(status);
                session.progressProperty().set(progress);
                sessions.add(session);
            }
            System.out.println("Loaded " + sessions.size() + " sessions from DB.");
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return sessions;
    }

    public void deleteSession(DownloadSession session) {
        String sql = "DELETE FROM download_sessions WHERE magnet_link = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
                PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, session.getMagnetLink());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
