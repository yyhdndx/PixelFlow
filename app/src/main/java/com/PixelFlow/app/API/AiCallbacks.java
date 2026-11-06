package com.PixelFlow.app.API;

public final class AiCallbacks {
    private AiCallbacks() { }
    public static <T> CommunityClient.Callback<T> forward(CommunityClient.Callback<Object> target) {
        return new CommunityClient.Callback<T>() {
            public void onStarted() { target.onStarted(); }
            public void onSuccess(T value) { target.onSuccess(value); }
            public void onFailure(String message) { target.onFailure(message); }
        };
    }
}
