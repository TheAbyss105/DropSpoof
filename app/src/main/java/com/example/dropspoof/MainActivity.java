package com.example.dropspoof;

import android.app.PendingIntent;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {

    private NfcAdapter nfcAdapter;
    private PendingIntent pendingIntent;
    private TextView statusText, subStatusText;

    private String urlToWrite = null;
    private String vcardDataToWrite = null;
    private String currentFileName = "shared_file";

    private ServerSocket serverSocket;
    private Thread serverThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_display);
        subStatusText = findViewById(R.id.sub_status);

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        Intent intent = new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE);

        handleShareIntent(getIntent());
    }

    private void handleShareIntent(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String type = intent.getType();
            if (type == null) return;

            // Reset buffers
            urlToWrite = null;
            vcardDataToWrite = null;
            stopServer();

            if (type.contains("vcard")) {
                Uri fileUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (fileUri != null) {
                    vcardDataToWrite = readAndCleanVcard(fileUri);
                    statusText.setText("ARMED: Contact Card");
                    subStatusText.setText("Photo data stripped for tag size.");
                }
            } else if (type.equals("text/plain") && intent.hasExtra(Intent.EXTRA_TEXT)) {
                urlToWrite = intent.getStringExtra(Intent.EXTRA_TEXT);
                statusText.setText("ARMED: Web Link");
                subStatusText.setText(urlToWrite);
            } else if (intent.hasExtra(Intent.EXTRA_STREAM)) {
                Uri fileUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (fileUri != null) {
                    currentFileName = getFileName(fileUri);
                    startLocalServer(fileUri);
                }
            }
        }
    }

    private String readAndCleanVcard(Uri uri) {
        StringBuilder sb = new StringBuilder();
        boolean inPhotoBlock = false;
        try (InputStream is = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.toUpperCase().startsWith("PHOTO")) {
                    inPhotoBlock = true;
                    continue;
                }
                if (inPhotoBlock && (line.startsWith(" ") || line.startsWith("\t"))) continue;
                inPhotoBlock = false;
                sb.append(line).append("\n");
            }
        } catch (Exception e) { return ""; }
        return sb.toString();
    }

    private void startLocalServer(Uri fileUri) {
        String ip = getLocalIpAddress();
        urlToWrite = "http://" + ip + ":8080";
        statusText.setText("ARMED: File Host");
        subStatusText.setText("Serving: " + currentFileName);

        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(8080);
                while (!serverSocket.isClosed()) {
                    Socket client = serverSocket.accept();
                    new Thread(() -> serveFile(client, fileUri)).start();
                }
            } catch (Exception ignored) {}
        });
        serverThread.start();
    }

    private void serveFile(Socket socket, Uri uri) {
        try (OutputStream out = socket.getOutputStream();
             InputStream in = getContentResolver().openInputStream(uri)) {
            String headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/octet-stream\r\n" +
                    "Content-Disposition: attachment; filename=\"" + currentFileName + "\"\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(headers.getBytes(StandardCharsets.UTF_8));
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
            out.flush();
        } catch (Exception ignored) {}
    }

    private void processTag(Tag tag) {
        try {
            NdefMessage msg;
            if (vcardDataToWrite != null) {
                msg = new NdefMessage(NdefRecord.createMime("text/vcard", vcardDataToWrite.getBytes(StandardCharsets.UTF_8)));
            } else if (urlToWrite != null) {
                msg = new NdefMessage(NdefRecord.createUri(urlToWrite));
            } else return;

            Ndef ndef = Ndef.get(tag);
            if (ndef != null) {
                ndef.connect();
                if (msg.getByteArrayLength() > ndef.getMaxSize()) {
                    Toast.makeText(this, "Tag too small!", Toast.LENGTH_LONG).show();
                } else {
                    ndef.writeNdefMessage(msg);
                    Toast.makeText(this, "TAG UPDATED!", Toast.LENGTH_SHORT).show();
                }
                ndef.close();
            } else {
                NdefFormatable format = NdefFormatable.get(tag);
                if (format != null) {
                    format.connect();
                    format.format(msg);
                    format.close();
                    Toast.makeText(this, "Formatted & Written!", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String getLocalIpAddress() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        int ip = wm.getConnectionInfo().getIpAddress();
        return String.format("%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
    }

    private String getFileName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
            }
        } catch (Exception ignored) {}
        return "file";
    }

    private void stopServer() {
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (Intent.ACTION_SEND.equals(intent.getAction())) handleShareIntent(intent);
        else {
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) processTag(tag);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (nfcAdapter != null) nfcAdapter.enableForegroundDispatch(this, pendingIntent, null, null);
    }

    @Override protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) nfcAdapter.disableForegroundDispatch(this);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopServer();
    }
}