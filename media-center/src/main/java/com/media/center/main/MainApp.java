package com.media.center.main;

import com.media.center.ui.MainController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

public class MainApp extends Application {
    private MainController controller;

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/media/center/ui/MainView.fxml"));
        Parent root = loader.load();

        controller = loader.getController();

        primaryStage.setTitle("Media Center");
        primaryStage.setScene(new Scene(root, 800, 600));

        setupSystemTray(primaryStage);

        primaryStage.show();
    }

    private void setupSystemTray(Stage primaryStage) {
        if (SystemTray.isSupported()) {
            Platform.setImplicitExit(false);

            SystemTray tray = SystemTray.getSystemTray();

            // Create a simple icon
            BufferedImage trayIconImage = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = trayIconImage.createGraphics();
            g.setColor(Color.BLUE);
            g.fillOval(0, 0, 16, 16);
            g.dispose();

            TrayIcon trayIcon = new TrayIcon(trayIconImage, "Media Center");
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> Platform.runLater(() -> {
                primaryStage.show();
                primaryStage.toFront();
            }));

            MenuItem openItem = new MenuItem("Open");
            openItem.addActionListener(e -> Platform.runLater(() -> {
                primaryStage.show();
                primaryStage.toFront();
            }));

            MenuItem exitItem = new MenuItem("Exit");
            exitItem.addActionListener(e -> {
                if (controller != null)
                    controller.shutdown();
                Platform.exit();
                System.exit(0);
            });

            PopupMenu popup = new PopupMenu();
            popup.add(openItem);
            popup.addSeparator();
            popup.add(exitItem);
            trayIcon.setPopupMenu(popup);

            try {
                tray.add(trayIcon);
            } catch (AWTException e) {
                System.err.println("TrayIcon could not be added.");
                e.printStackTrace();
            }

            primaryStage.setOnCloseRequest(e -> {
                e.consume();
                primaryStage.hide();
            });
        } else {
            primaryStage.setOnCloseRequest(e -> {
                if (controller != null)
                    controller.shutdown();
                Platform.exit();
                System.exit(0);
            });
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
