package com.lmao.jvcprojectorcontrol;

import android.content.Context;
import android.content.SharedPreferences;
// import android.icu.util.Output;
import android.net.wifi.WifiManager;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import android.view.HapticFeedbackConstants;
import android.view.View;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.EditText;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
//import java.net.SocketTimeoutException;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.ArrayList;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.SocketException;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "JVCProjectorControl";
    private static String PROJECTOR_IP;
    private static final int PROJECTOR_PORT = 20554;

    private Socket socket;
    private OutputStream output;
    private InputStream input;

    private TextView status_text;
    private EditText ipInput;

    private WifiManager wifiMngr;
    private WifiManager.MulticastLock multicastLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // save IP for later & retrieve it if saved
        ipInput = findViewById(R.id.ip_field);
        Button connectButton = findViewById(R.id.connect_btn);
        SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        // first launch dialog
        boolean firstLaunch = prefs.getBoolean("firstLaunch", true);
        if (firstLaunch) {
            showHelpDialog();
            editor.putBoolean("firstLaunch", false).apply();
        }
        // retrieve
        String savedIp = prefs.getString("saved_ip", "");
        ipInput.setText(savedIp);
        PROJECTOR_IP = savedIp;
        // save
        connectButton.setOnClickListener(v -> {
            String ip = ipInput.getText().toString();
            editor.putString("saved_ip", ip);
            editor.apply();
            PROJECTOR_IP = ip;
            new Thread(this::connectToProjector).start();
        });

        Button disconnectButton = findViewById(R.id.disconnect_btn);
        disconnectButton.setOnClickListener(v -> {
            new Thread(this::disconnect).start();
        });

        Button autodetectButton = findViewById(R.id.autodetect_btn);
        autodetectButton.setOnClickListener(v -> {
            new Thread(this::autodetectProjectors).start();
        });

        Button infoButton = findViewById(R.id.info_btn);
        infoButton.setOnClickListener(v -> {
            showHelpDialog();
        });

        // Set up buttons with their respective commands
        setupButton(R.id.on_btn, "21 89 01 50 57 31 0A");     // Power On
        setupButton(R.id.off_btn, "21 89 01 50 57 30 0A");    // Power Off
        setupButton(R.id.up_btn, "21 89 01 52 43 37 33 30 31 0A");    // Up
        setupButton(R.id.down_btn, "21 89 01 52 43 37 33 30 32 0A");  // Down
        setupButton(R.id.left_btn, "21 89 01 52 43 37 33 33 36 0A");  // Left
        setupButton(R.id.right_btn, "21 89 01 52 43 37 33 33 34 0A"); // Right
        setupButton(R.id.ok_btn, "21 89 01 52 43 37 33 32 46 0A");    // OK
        setupButton(R.id.menu_btn, "21 89 01 52 43 37 33 32 45 0A");  // Menu
        setupButton(R.id.back_btn, "21 89 01 52 43 37 33 30 33 0A");  // Back

        status_text = findViewById(R.id.status_text);

        wifiMngr = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiMngr != null) {
            multicastLock = wifiMngr.createMulticastLock("myMulticastLock");
            multicastLock.setReferenceCounted(true);
            multicastLock.acquire();
        }
    }
    private void setupButton(int buttonId, String commandHex) {
        Button btn = findViewById((buttonId));
        btn.setOnClickListener((View v) -> {
            if (commandHex == "21 89 01 50 57 30 0A") { // if power off
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("Confirm Power Off")
                        .setMessage("Are you sure you want to power off?")
                        .setPositiveButton("Yes", (dialog, which) -> poweroff())
                        .setNegativeButton("No", (dialog, which) -> dialog.dismiss())
                        .show();
            } else {
                Log.d(TAG, "Button pressed. Sending command: " + commandHex);
                new Thread(() -> sendCommand(commandHex)).start();
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
            if (commandHex == "21 89 01 50 57 31 0A") { // if power on
                status_text.setText("Status: Connected, powered ON");
            }
        });
    }

    private void poweroff() {
        new Thread(() -> sendCommand("21 89 01 50 57 30 0A")).start();
        status_text.setText("Status: Connected, powered OFF");
    }

    private void connectToProjector() {
        status_text.setText("Status: Attempting to connect...");
        try {
            Log.d(TAG, "Attempting to connect to projector at " + PROJECTOR_IP + ":" + PROJECTOR_PORT);
            socket = new Socket(PROJECTOR_IP, PROJECTOR_PORT);
            output = socket.getOutputStream();
            input = socket.getInputStream();
            Log.d(TAG, "Socket connected. Sending authentication request (PJREQ).");

            // Send authentication request
            output.write("PJREQ".getBytes());
            output.flush();
            Log.d(TAG, "PJREQ sent, waiting for response...");

            // Read authentication response
            byte[] response = new byte[5];
            int bytesRead = input.read(response);
            if (bytesRead == -1) {
                Log.e(TAG, "No response received from projector.");
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "No response from projector!", Toast.LENGTH_SHORT).show());
                return;
            }
            String responseString = new String(response, 0, bytesRead).trim();
            Log.d(TAG, "Received authentication response: " + responseString);

            if (!"PJ_OK".equals(responseString)) {
                Log.e(TAG, "Authentication failed. Expected 'PJ_OK' but received: " + responseString);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Authentication failed!", Toast.LENGTH_SHORT).show());
            } else {
                Log.d(TAG, "Authentication successful. Connected to projector.");
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Connected to projector", Toast.LENGTH_SHORT).show());
                status_text.setText("Status: Connected!");
            }
            readPowerStatus();
        } catch (Exception e) {
            Log.e(TAG, "Error connecting to projector: " + e.getMessage(), e);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error connecting: " + e.getMessage(), Toast.LENGTH_LONG).show());
            status_text.setText("Status: Disconnected");
        }
    }

    private void sendCommand(String commandHex) {
        try {
            if (socket == null || socket.isClosed()) {
                Log.e(TAG, "Socket is null or closed. Cannot send command.");
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Not connected", Toast.LENGTH_SHORT).show());
                return;
            }
            // Convert hex string to byte array
            byte[] commandBytes = hexStringToByteArray(commandHex);
            Log.d(TAG, "Sending command bytes: " + bytesToHex(commandBytes));
            output.write(commandBytes);
            output.flush();

            // Optionally read the acknowledgment from the projector
            byte[] ack = new byte[6];
            int bytesRead = input.read(ack);
            String ackString = bytesToHex(ack, bytesRead);
            Log.d(TAG, "Received acknowledgment: " + ackString);

            // runOnUiThread(() -> Toast.makeText(MainActivity.this, "Command Sent", Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            Log.e(TAG, "Error sending command: " + e.getMessage(), e);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error sending command: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private void readPowerStatus() {
        try {
            if (socket == null || socket.isClosed()) {
                Log.e(TAG, "Socket is not connected.");
                runOnUiThread(() -> Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show());
                return;
            }

            // Send power status query: 3F 89 01 50 57 0A
            byte[] queryCommand = hexStringToByteArray("3F 89 01 50 57 0A");
            output.write(queryCommand);
            output.flush();
            Log.d(TAG, "Sent power status query command.");

            // Skip handshake ACK ("PJACK")
            byte[] pjack = new byte[5];
            input.read(pjack);
            Log.d(TAG, "Received handshake ACK: " + new String(pjack));

            // Read command ACK: 06 89 01 50 57 0A
            byte[] ack = new byte[6];
            input.read(ack);
            Log.d(TAG, "Received command ACK: " + bytesToHex(ack));

            // Now read the actual status response
            byte[] response = new byte[7];
            while (true) {
                int b = input.read();
                if (b == -1) {
                    Log.e(TAG, "End of stream reached");
                    return;
                }

                if (b == 0x40) {
                    response[0] = (byte) b;
                    int readRest = input.read(response, 1, 6); // Read the rest of the message
                    if (readRest == 6) break;
                }
            }

            Log.d(TAG, "Received power status response: " + bytesToHex(response));

            // Check response
            if (response[0] != 0x40) {
                Log.w(TAG, "Response does not start with 0x40.");
                return;
            }

            byte status = response[5];
            switch (status) {
                case 0x32:
                case 0x30:
                    Log.i(TAG, "Projector is OFF");
                    runOnUiThread(() ->
//                            Toast.makeText(this, "Projector is OFF", Toast.LENGTH_SHORT).show());
                                status_text.setText("Status: Connected, powered OFF"));
                    break;
                case 0x31:
                    Log.i(TAG, "Projector is ON");
                    runOnUiThread(() ->
//                            Toast.makeText(this, "Projector is ON", Toast.LENGTH_SHORT).show());
                                status_text.setText("Status: Connected, powered ON"));
                    break;
                default:
                    Log.w(TAG, String.format("Unknown status byte: 0x%02X", status));
                    runOnUiThread(() ->
                            Toast.makeText(this, "Unknown status", Toast.LENGTH_SHORT).show());
            }

        } catch (Exception e) {
            Log.e(TAG, "Error reading power status", e);
            runOnUiThread(() ->
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private void autodetectProjectors() {
        new Thread(() -> {
            runOnUiThread(() -> {
                Toast.makeText(this, "Starting autodetection...", Toast.LENGTH_LONG).show();
            });
            String foundIp = trySDDPDiscovery();
            if (foundIp != null) {
                Log.i(TAG, "Projector found via SDDP at " + foundIp);
                String finalFoundIp1 = foundIp;
                runOnUiThread(() -> {
                    // 🔧 Do something with the projector IP
                    ipInput.setText(finalFoundIp1);
                    PROJECTOR_IP = finalFoundIp1;
                    Toast.makeText(this, "Projector found via SDDP: " + finalFoundIp1, Toast.LENGTH_LONG).show();
                });
                return;
            }

            Log.i(TAG, "SDDP failed, falling back to TCP port scan...");

            foundIp = tryTCPScan();
            if (foundIp != null) {
                Log.i(TAG, "Projector found via TCP scan at " + foundIp);
                String finalFoundIp = foundIp;
                runOnUiThread(() -> {
                    // 🔧 Do something with the projector IP
                    ipInput.setText(finalFoundIp);
                    PROJECTOR_IP = finalFoundIp;
                    Toast.makeText(this, "Projector found via TCP: " + finalFoundIp, Toast.LENGTH_LONG).show();
                });
            } else {
                Log.w(TAG, "Projector not found on network.");
                runOnUiThread(() -> {
                    Toast.makeText(this, "Projector not found", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private String trySDDPDiscovery() {
        try {
            String sddpRequest = "M-SEARCH * SDDP/1.0\r\n" +
                    "HOST: 239.255.255.250:1902\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "ST: urn:schemas-upnp-org:device:basic:1\r\n" +
                    "\r\n";

            DatagramSocket socket = new DatagramSocket();
            socket.setSoTimeout(2000);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            DatagramPacket packet = new DatagramPacket(
                    sddpRequest.getBytes(), sddpRequest.length(), group, 1902);
            socket.send(packet);

            byte[] buffer = new byte[1024];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);

            while (true) {
                socket.receive(response);
                String data = new String(buffer, 0, response.getLength());
                if (data.contains("JVCKENWOOD:Projector")) {
                    String ip = response.getAddress().getHostAddress();
                    socket.close();
                    return ip;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "SDDP discovery failed: " + e.getMessage());
            return null;
        }
    }

    private String getLocalSubnet() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        String hostAddress = inetAddress.getHostAddress();
                        return hostAddress.substring(0, hostAddress.lastIndexOf('.') + 1); // e.g. 192.168.2.
                    }
                }
            }
        } catch (SocketException ex) {
            Log.e(TAG, "Failed to get local subnet: " + ex.getMessage());
        }
        return null;
    }

    private String tryTCPScan() {
        String subnet = getLocalSubnet(); // e.g., "192.168.2."
        if (subnet == null) return null;

        ExecutorService executor = Executors.newFixedThreadPool(20); // 20 threads
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 1; i <= 254; i++) {
            final String ip = subnet + i;
            futures.add(executor.submit(() -> {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(ip, 20554), 300);
                    InputStream in = socket.getInputStream();
                    byte[] buf = new byte[5];
                    int read = in.read(buf);
                    if (read == 5 && "PJ_OK".equals(new String(buf, 0, read))) {
                        return ip; // Projector found
                    }
                } catch (IOException ignored) {}
                return null;
            }));
        }

        executor.shutdown();

        // Return the first IP that responded correctly
        for (Future<String> future : futures) {
            try {
                String result = future.get(); // blocks until result is ready
                if (result != null) {
                    executor.shutdownNow(); // stop other threads early
                    return result;
                }
            } catch (Exception ignored) {}
        }

        return null; // no projector found
    }

    private byte[] hexStringToByteArray(String hex) {
        String[] hexArray = hex.split(" ");
        byte[] bytes = new byte[hexArray.length];
        for (int i = 0; i < hexArray.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hexArray[i], 16);
        }
        return bytes;
    }

    private String bytesToHex(byte[] bytes) {
        return bytesToHex(bytes, bytes.length);
    }

    private String bytesToHex(byte[] bytes, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        return sb.toString().trim();
    }

    private void disconnect() {
        try {
            if (socket != null) {
                Log.d(TAG, "Closing socket connection.");
                socket.close();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Disconnected successfully", Toast.LENGTH_SHORT).show());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing socket: " + e.getMessage(), e);
        }
        status_text.setText("Status: Disconnected");
    }

    private void showHelpDialog() {
        runOnUiThread(() -> {
            new AlertDialog.Builder(this)
                    .setTitle("JVC Projector Control")
                    .setMessage("This app is a remote to control most JVC projectors. In order to work properly, please ensure you have done the following:\n\n"+
                            "1. Your phone and your projector must be on the same Wi-Fi network. Make sure your projector is connected via an Ethernet cable to your network.\n\n"+
                            "2. In your projector settings, the option to enable the network must be turned ON. You can most likely use the buttons at the back of the projector to access the menu, and enable the option there.\n\n"+
                            "3. Once your projector is on the same network as your phone, you can use the Auto-Detect button to find the IP of your projector. Or, if that doesn't work you can find the IP by accessing your router and finding your projector there, then input it manually into the \"Projector IP\" field\n\n"+
                            "4. After you found the IP from auto detection or inputting it manually, you can connect to the projector by pressing the Connect button.\n\n"+
                            "5. You can now turn the projector on by pressing the ON button, or if you're done using it you can press the OFF button.\n\n"+
                            "6. The rest of the buttons are just like the remote, the arrow keys are used for navigation, the menu button is used to access the menu, the back button is used to go back, etc.\n\n"+
                            "7. If you have any issues, please contact me and I can resolve it in no time.\n\n"+
                            "Note: This app only works with ONE projector on the network. If you have multiple, then you must find the IP of the one you wish to control and type it into the field above.")
                    .setPositiveButton("Got it", null)
                    .show();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        new Thread(this::disconnect).start();
        if (multicastLock != null) {
            multicastLock.release();
        }
    }
}