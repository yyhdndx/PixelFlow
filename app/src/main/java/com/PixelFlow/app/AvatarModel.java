package com.PixelFlow.app;

import android.app.Application;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import com.PixelFlow.app.API.*;
import com.PixelFlow.app.creation.DraftImage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Activity-owned avatar operation: survives tab navigation and configuration changes. */
public final class AvatarModel extends AndroidViewModel {
    public final MutableLiveData<Integer> changes = new MutableLiveData<>(0);
    private final ApiClient api;
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private long session, pickerSession;
    private String owner, pickerOwner;
    private int operation;
    private boolean busy, cleared;
    public int completedVersion;
    public String error;
    public String resultMessage;

    public AvatarModel(Application application) {
        super(application);
        api = ApiClient.getInstance(application);
    }
    private boolean sameAccount(long version, String username) {
        return api.isLoggedIn() && version == api.sessionVersion() && api.username().equals(username);
    }
    public boolean belongsToSession() { return sameAccount(session, owner); }
    public boolean isBusy() { return busy && belongsToSession(); }
    public boolean preparePicker() {
        if (!api.isLoggedIn() || isBusy()) return false;
        pickerSession = api.sessionVersion(); pickerOwner = api.username();
        return true;
    }
    public boolean consumePicker() {
        boolean valid = sameAccount(pickerSession, pickerOwner);
        pickerOwner = null;
        return valid;
    }
    private boolean begin() {
        if (cleared || !api.isLoggedIn() || isBusy()) return false;
        session = api.sessionVersion(); owner = api.username();
        operation++; busy = true; error = null; resultMessage = null;
        changed();
        return true;
    }
    private boolean current(int version) {
        return !cleared && operation == version && belongsToSession();
    }
    private void changed() { changes.setValue(changes.getValue() + 1); }
    public void replace(Uri uri) {
        if (!begin()) return;
        final int version = operation;
        images.execute(() -> {
            try {
                byte[] bytes = DraftImage.compress(getApplication().getContentResolver(), uri);
                main.post(() -> upload(bytes, version));
            } catch (Exception failure) {
                main.post(() -> failed(getApplication().getString(R.string.avatar_upload_failed), version));
            }
        });
    }
    private void upload(byte[] bytes, int version) {
        if (!current(version)) return;
        api.uploadMedia(bytes, "image/jpeg", new CommunityClient.Callback<MediaItem>() {
            public void onSuccess(MediaItem media) {
                if (!current(version)) return;
                api.setAvatar(media.mediaId, callback(version));
            }
            public void onFailure(String message) { failed(message, version); }
        });
    }
    public void clear() {
        if (!begin()) return;
        api.clearAvatar(callback(operation));
    }
    private CommunityClient.Callback<AvatarResult> callback(int version) {
        return new CommunityClient.Callback<AvatarResult>() {
            public void onSuccess(AvatarResult value) {
                if (!current(version)) return;
                resultMessage = getApplication().getString(value.avatar.isEmpty()
                        ? R.string.avatar_cleared : R.string.avatar_updated);
                finished();
            }
            public void onFailure(String message) { failed(message, version); }
        };
    }
    private void failed(String message, int version) {
        if (!current(version)) return;
        error = message; resultMessage = message;
        finished();
    }
    private void finished() {
        busy = false; completedVersion++;
        changed();
    }
    @Override protected void onCleared() {
        cleared = true;
        images.shutdown();
        // Issued writes keep their original session and are never cancelled by a view lifecycle.
        // No subsequent upload/set stage starts after the owning Activity is permanently closed.
    }
}
