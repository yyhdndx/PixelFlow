package com.pixelflow.server.intelligence;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Bounds upstream data while the controller enforces a deadline through body completion. */
final class SearchResponseBody implements HttpResponse.BodySubscriber<String> {
    private final CompletableFuture<String> result=new CompletableFuture<>();
    private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    public CompletionStage<String> getBody() { return result; }
    public void onSubscribe(Flow.Subscription value) { subscription=value; value.request(1); }
    public void onNext(List<ByteBuffer> buffers) {
        for (var buffer:buffers) {
            if (buffer.remaining()>65536-bytes.size()) {
                subscription.cancel(); result.completeExceptionally(new java.io.IOException("Response exceeds limit")); return;
            }
            byte[] chunk=new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
        }
        subscription.request(1);
    }
    public void onError(Throwable error) { result.completeExceptionally(error); }
    public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
}
