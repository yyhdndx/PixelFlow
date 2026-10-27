package com.PixelFlow.app;

import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.PixelFlow.app.API.ApiClient;
import com.PixelFlow.app.API.CommunityClient;

/** Main-thread lifecycle policy for account pages. Reads may cancel; writes must reconcile. */
public abstract class AccountDataActivity extends AppCompatActivity {
    protected ApiClient api;
    protected CommunityClient.Job job;
    private int version;
    private long session;
    private boolean active, ready, writing;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        api = ApiClient.getInstance(this);
    }

    @Override protected void onResume() {
        super.onResume();
        active = true;
        restart();
    }

    @Override protected void onStop() {
        active = false;
        invalidate();
        clearPage();
        super.onStop();
    }

    private void invalidate() {
        ++version;
        if (job != null) job.cancel();
        job = null;
        ready = false;
        writing = false;
        setActionsEnabled(false);
    }

    private void restart() {
        invalidate();
        session = api.sessionVersion();
        clearPage();
        reloadPage();
    }

    protected final boolean canLoad() { return active && !writing; }

    protected final int beginRead() {
        if (job != null) job.cancel();
        ready = false;
        setActionsEnabled(false);
        return ++version;
    }

    protected final boolean alive(int requestVersion) {
        if (!active || isFinishing() || isDestroyed() || requestVersion != version) return false;
        if (session != api.sessionVersion()) { restart(); return false; }
        return true;
    }

    protected final void readSucceeded() {
        ready = true;
        setActionsEnabled(true);
    }

    protected final boolean beginWrite() {
        if (!alive(version) || !ready || writing) return false;
        writing = true;
        ready = false;
        setActionsEnabled(false);
        return true;
    }

    protected final <T> CommunityClient.Callback<T> writeResult(String successMessage) {
        final int requestVersion = version;
        return new CommunityClient.Callback<T>() {
            public void onSuccess(T value) { finishWrite(successMessage); }
            public void onFailure(String message) { finishWrite(message + "；正在核对服务器结果"); }
            private void finishWrite(String message) {
                if (!alive(requestVersion)) return;
                writing = false;
                if (message != null) Toast.makeText(AccountDataActivity.this, message, Toast.LENGTH_LONG).show();
                // A transport error does not prove rollback. Re-read before allowing another write.
                reloadPage();
            }
        };
    }

    // Do not cancel writes on stop: the client's serial queue puts the next read behind them.
    protected abstract void clearPage();
    protected abstract void reloadPage();
    protected void setActionsEnabled(boolean enabled) { }
}
