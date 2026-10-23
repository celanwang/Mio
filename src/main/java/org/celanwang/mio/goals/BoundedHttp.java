package org.celanwang.mio.goals;

import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.io.ByteArrayOutputStream;

/** Deadline covers response headers and the complete, size-limited body. */
public final class BoundedHttp {
    private BoundedHttp() {}
    public static HttpResponse<byte[]> send(HttpClient client,HttpRequest request,int maxBytes,Duration deadline) throws Exception {
        var future=client.sendAsync(request,info->new LimitedBody(maxBytes));
        try{return future.get(deadline.toMillis(),TimeUnit.MILLISECONDS);}
        catch(Exception failure){future.cancel(true);if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw failure;}
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum;
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int maximum){this.maximum=maximum;}
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(var buffer:buffers){if(buffer.remaining()>maximum-bytes.size()){subscription.cancel();result.completeExceptionally(new IllegalStateException("响应正文过大"));return;}byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);}
            subscription.request(1);
        }
        public void onError(Throwable failure){result.completeExceptionally(failure);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
