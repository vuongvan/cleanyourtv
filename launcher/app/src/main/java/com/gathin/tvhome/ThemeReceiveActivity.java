package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.IOException;
import java.util.List;

/**
 * "Receive a theme from a phone": shows this TV's address and a PIN while a {@link ThemeServer} runs, and lists the
 * files that arrive. The server runs only while this screen is visible; Home rebuilds with the new theme afterwards.
 */
public class ThemeReceiveActivity extends Activity {
    private TextView address;
    private TextView pinLabel;
    private TextView pinText;
    private TextView log;
    private ThemeServer server;
    private final StringBuilder lines = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme_receive);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        address = findViewById(R.id.receiveAddress);
        pinLabel = findViewById(R.id.receivePinLabel);
        pinText = findViewById(R.id.receivePin);
        log = findViewById(R.id.receiveLog);
    }

    @Override
    protected void onStart() {
        super.onStart();
        startServer();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopServer();
    }

    private void startServer() {
        stopServer();
        List<String> addresses = ThemeServer.localAddresses();
        if (addresses.isEmpty()) {
            showProblem(R.string.theme_receive_no_network);
            return;
        }
        String pin = ThemeServer.newPin();
        ThemeServer candidate = new ThemeServer(this, pin, (name, ok, message) -> runOnUiThread(() -> {
            lines.append(ok ? "\u2713 " : "\u2717 ").append(name).append(": ").append(message).append('\n');
            log.setText(lines.toString().trim());
            log.setVisibility(View.VISIBLE);
        }));
        try {
            int port = candidate.start();
            server = candidate;
            StringBuilder urls = new StringBuilder();
            for (String ip : addresses) {
                if (urls.length() > 0) urls.append('\n');
                urls.append("http://").append(ip).append(':').append(port);
            }
            address.setText(urls.toString());
            pinText.setText(pin);
            pinLabel.setVisibility(View.VISIBLE);
            pinText.setVisibility(View.VISIBLE);
        } catch (IOException e) {
            showProblem(R.string.theme_receive_start_failed);
        }
    }

    private void showProblem(int message) {
        address.setText(message);
        pinLabel.setVisibility(View.GONE);
        pinText.setVisibility(View.GONE);
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }
}
