package com.example.minseo3;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.switchmaterial.SwitchMaterial;

public class NasSettingsActivity extends AppCompatActivity {

    private NasSyncManager nas;
    private SwitchMaterial swEnabled;
    private EditText etHost, etPort, etUser, etPass, etPath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nas_settings);
        setTitle("NAS 설정");

        nas = new NasSyncManager(this);

        swEnabled = findViewById(R.id.sw_enabled);
        etHost    = findViewById(R.id.et_host);
        etPort    = findViewById(R.id.et_port);
        etUser    = findViewById(R.id.et_user);
        etPass    = findViewById(R.id.et_pass);
        etPath    = findViewById(R.id.et_path);
        Button btnSave = findViewById(R.id.btn_save);

        // Load current values
        swEnabled.setChecked(nas.isEnabled());
        etHost.setText(nas.getHost());
        etPort.setText(String.valueOf(nas.getPort()));
        etUser.setText(nas.getUser());
        etPass.setText(nas.getPass());
        etPath.setText(nas.getPath());

        btnSave.setOnClickListener(v -> save());
    }

    private void save() {
        int port;
        try {
            port = Integer.parseInt(etPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            port = 5000;
        }
        nas.save(
                swEnabled.isChecked(),
                etHost.getText().toString().trim(),
                port,
                etUser.getText().toString().trim(),
                etPass.getText().toString(),
                etPath.getText().toString().trim()
        );
        Toast.makeText(this, "저장되었습니다.", Toast.LENGTH_SHORT).show();
        finish();
    }
}
