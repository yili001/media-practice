# Media Center Project

A JavaFX application for downloading torrents and casting media to DLNA devices on the local network.

## Requirements
- Java 17
- Maven 3.x

## Setup
1. The project depends on Java 17. The `setup-env.ps1` script in the root helps configure the environment.
2. Build the project:
   ```powershell
   cd media-center
   mvn clean install
   ```

## Running
Use the provided script in the root directory:
```powershell
.\run.ps1
```
Or manually from `media-center` directory:
```powershell
mvn javafx:run
```

## Features
- **Downloads**: 
  - Add magnet links.
  - Support for manual tracker addition.
  - Background downloading (Minimize to Tray).
- **Casting**: 
  - Auto-discovery of DLNA renderers (Smart TVs, etc.).
  - Stream local files to devices.
