package com.example.minseo3;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.minseo3.nas.DsAuth;
import com.example.minseo3.nas.RemoteProgressRepository;

import com.google.android.material.switchmaterial.SwitchMaterial;

public class NasSettingsActivity extends AppCompatActivity {

    private static final int COLOR_SUCCESS = 0xFF2ECC71; // green
    private static final int COLOR_ERROR   = 0xFFFF6B6B; // red
    private static final int COLOR_NEUTRAL = 0xFFAAAAAA; // gray

    private NasSyncManager nas;
    private SwitchMaterial swEnabled;
    private EditText etHost, etPort, etLanHost, etLanPort, etUser, etPass, etPath;
    private Button btnTest, btnSave;
    private TextView tvResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nas_settings);
        setTitle("NAS 설정");

        nas = new NasSyncManager(this);

        swEnabled = findViewById(R.id.sw_enabled);
        etHost    = findViewById(R.id.et_host);
        etPort    = findViewById(R.id.et_port);
        etLanHost = findViewById(R.id.et_lan_host);
        etLanPort = findViewById(R.id.et_lan_port);
        etUser    = findViewById(R.id.et_user);
        etPass    = findViewById(R.id.et_pass);
        etPath    = findViewById(R.id.et_path);
        btnTest   = findViewById(R.id.btn_test);
        btnSave   = findViewById(R.id.btn_save);
        tvResult  = findViewById(R.id.tv_result);

        // Load current values
        swEnabled.setChecked(nas.isEnabled());
        etHost.setText(nas.getHost());
        etPort.setText(String.valueOf(nas.getPort()));
        etLanHost.setText(nas.getLanHost());
        etLanPort.setText(String.valueOf(nas.getLanPort()));
        etUser.setText(nas.getUser());
        etPass.setText(nas.getPass());
        etPath.setText(nas.getPath());

        btnTest.setOnClickListener(v -> runConnectionTest());
        btnSave.setOnClickListener(v -> save(false));
    }

    private static final class FormValues {
        final boolean enabled;
        final String host;
        final int port;
        final String lanHost;
        final int lanPort;
        final String user;
        final String pass;
        final String path;
        FormValues(boolean enabled, String host, int port, String lanHost, int lanPort,
                   String user, String pass, String path) {
            this.enabled = enabled; this.host = host; this.port = port;
            this.lanHost = lanHost; this.lanPort = lanPort;
            this.user = user; this.pass = pass; this.path = path;
        }
    }

    private FormValues readForm() {
        int port    = parseIntOr(etPort.getText().toString(), 5000);
        int lanPort = parseIntOr(etLanPort.getText().toString(), 5000);
        return new FormValues(
                swEnabled.isChecked(),
                etHost.getText().toString().trim(),
                port,
                etLanHost.getText().toString().trim(),
                lanPort,
                etUser.getText().toString().trim(),
                etPass.getText().toString(),
                etPath.getText().toString().trim());
    }

    private static int parseIntOr(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private void save(boolean silent) {
        FormValues f = readForm();
        nas.save(f.enabled, f.host, f.port, f.user, f.pass,
                f.path.isEmpty() ? "/web/.minseo/" : f.path,
                f.lanHost, f.lanPort);
        if (!silent) {
            Toast.makeText(this, "저장되었습니다.", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    // ── 연결 테스트 ──────────────────────────────────────────────────────────

    private void runConnectionTest() {
        FormValues f = readForm();

        if (f.host.isEmpty() || f.user.isEmpty() || f.pass.isEmpty()) {
            showResult(COLOR_ERROR, "주소 / 사용자 이름 / 비밀번호를 모두 입력해 주세요.");
            return;
        }

        // 테스트용으로 DsAuth 를 현재 폼 값으로 초기화. 실패 시 기존 prefs 값으로 복원.
        String testBaseUrl = NasSyncManager.buildBaseUrl(f.host, f.port);
        String testLanUrl  = NasSyncManager.buildBaseUrl(f.lanHost, f.lanPort);
        DsAuth.init(testBaseUrl, testLanUrl, f.user, f.pass, /*basePath*/ "/",
                f.path.isEmpty() ? "/web/.minseo/" : f.path);

        setFieldsEnabled(false);
        String probeNote = testLanUrl.isEmpty()
                ? "DDNS 로 직접 연결 중…"
                : "LAN (" + f.lanHost + ":" + f.lanPort + ") probe → 실패 시 DDNS 폴백…";
        showResult(COLOR_NEUTRAL, "연결 중… " + probeNote);

        DsAuth.login(new RemoteProgressRepository.Callback<String>() {
            @Override public void onResult(String sid) {
                if (isFinishing() || isDestroyed()) return;
                setFieldsEnabled(true);
                String reached = DsAuth.apiBase();
                showResult(COLOR_SUCCESS, "✓ 연결 성공\n접속 경로: " + reached);
                // 테스트 성공 → form 값으로 저장 후 닫기
                save(/*silent*/ true);
                Toast.makeText(NasSettingsActivity.this, "연결 성공. 저장되었습니다.",
                        Toast.LENGTH_SHORT).show();
                finish();
            }
            @Override public void onError(String msg) {
                if (isFinishing() || isDestroyed()) return;
                setFieldsEnabled(true);
                showResult(COLOR_ERROR, "✗ 연결 실패\n" + msg);
                // 실패 — DsAuth 를 기존 prefs 기준으로 복원 (다음 호출에서 save() 가 재초기화)
                nas.save(nas.isEnabled(), nas.getHost(), nas.getPort(),
                        nas.getUser(), nas.getPass(), nas.getPath(),
                        nas.getLanHost(), nas.getLanPort());
            }
        });
    }

    private void setFieldsEnabled(boolean enabled) {
        btnTest.setEnabled(enabled);
        btnSave.setEnabled(enabled);
        swEnabled.setEnabled(enabled);
        etHost.setEnabled(enabled);
        etPort.setEnabled(enabled);
        etLanHost.setEnabled(enabled);
        etLanPort.setEnabled(enabled);
        etUser.setEnabled(enabled);
        etPass.setEnabled(enabled);
        etPath.setEnabled(enabled);
    }

    private void showResult(int color, String msg) {
        tvResult.setVisibility(View.VISIBLE);
        tvResult.setText(msg);
        tvResult.setTextColor(color);
    }
}
