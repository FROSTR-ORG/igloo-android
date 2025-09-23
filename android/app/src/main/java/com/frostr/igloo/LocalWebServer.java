package com.frostr.igloo;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

public class LocalWebServer {
    private static final String TAG = "LocalWebServer";
    private static final int PORT = 8090;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private Context context;
    private boolean isRunning = false;

    public LocalWebServer(Context context) {
        this.context = context;
    }

    public void start() {
        if (isRunning) return;

        serverThread = new Thread(() -> {
            try {
                // Bind only to loopback interface for security
                serverSocket = new ServerSocket(PORT, 50, InetAddress.getLoopbackAddress());
                isRunning = true;
                Log.d(TAG, "Local web server started on port " + PORT);

                while (isRunning && !serverSocket.isClosed()) {
                    Socket clientSocket = serverSocket.accept();
                    handleClient(clientSocket);
                }
            } catch (IOException e) {
                if (isRunning) {
                    Log.e(TAG, "Server error", e);
                }
            }
        });
        serverThread.start();
    }

    public void stop() {
        isRunning = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            if (serverThread != null) {
                serverThread.interrupt();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error stopping server", e);
        }
    }

    private void handleClient(Socket clientSocket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             OutputStream out = clientSocket.getOutputStream()) {

            String requestLine = in.readLine();
            if (requestLine == null) return;

            Log.d(TAG, "Request: " + requestLine);

            // Parse request
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;

            String path = parts[1];

            // Remove query parameters for file serving
            if (path.contains("?")) {
                path = path.substring(0, path.indexOf("?"));
            }

            if (path.equals("/")) {
                path = "/index.html";
            }

            // Remove leading slash for asset path
            String assetPath = "www" + path;

            try {
                AssetManager assetManager = context.getAssets();
                InputStream assetStream = assetManager.open(assetPath);

                // Determine content type
                String contentType = getContentType(path);

                // Send response headers
                String response = "HTTP/1.1 200 OK\r\n";
                response += "Content-Type: " + contentType + "\r\n";
                response += "Access-Control-Allow-Origin: *\r\n";
                response += "Access-Control-Allow-Headers: *\r\n";
                response += "Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n";
                response += "\r\n";

                out.write(response.getBytes());

                // Send file content
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = assetStream.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }

                assetStream.close();
                Log.d(TAG, "Served: " + assetPath);

            } catch (IOException e) {
                // File not found
                String notFound = "HTTP/1.1 404 Not Found\r\n\r\n404 - File not found";
                out.write(notFound.getBytes());
                Log.w(TAG, "File not found: " + assetPath);
            }

        } catch (IOException e) {
            Log.e(TAG, "Error handling client", e);
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing client socket", e);
            }
        }
    }

    private String getContentType(String path) {
        Map<String, String> mimeTypes = new HashMap<>();
        mimeTypes.put(".html", "text/html");
        mimeTypes.put(".css", "text/css");
        mimeTypes.put(".js", "application/javascript");
        mimeTypes.put(".json", "application/json");
        mimeTypes.put(".png", "image/png");
        mimeTypes.put(".jpg", "image/jpeg");
        mimeTypes.put(".jpeg", "image/jpeg");
        mimeTypes.put(".ico", "image/x-icon");
        mimeTypes.put(".map", "application/json");

        for (Map.Entry<String, String> entry : mimeTypes.entrySet()) {
            if (path.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "text/plain";
    }

    public String getBaseUrl() {
        return "http://localhost:" + PORT;
    }
}