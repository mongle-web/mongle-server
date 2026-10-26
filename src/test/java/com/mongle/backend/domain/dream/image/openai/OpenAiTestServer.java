package com.mongle.backend.domain.dream.image.openai;

import com.mongle.backend.domain.dream.image.*;
import com.mongle.backend.domain.dream.image.s3.ImageS3Properties;
import com.mongle.backend.domain.dream.story.StoryResult;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

public final class OpenAiTestServer implements AutoCloseable {
    public record Reply(int status, String body, Map<String,String> headers, CountDownLatch beforeHeaders, CountDownLatch bodyGate) {
        public Reply(int status,String body) { this(status,body,Map.of(),null,null); }
    }
    public record Received(String method,String authorization,String body) {}
    public final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    public final List<Received> received = new CopyOnWriteArrayList<>();
    private final HttpServer server;
    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    public OpenAiTestServer() {
        try {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(handlers);
            server.createContext("/images/generations", exchange -> {
                received.add(new Received(exchange.getRequestMethod(),exchange.getRequestHeaders().getFirst("Authorization"),
                        new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
                var queued=replies.poll(); var reply=queued==null ? new Reply(500,"{}") : queued;
                try {
                    if (reply.beforeHeaders()!=null) reply.beforeHeaders().await(5,TimeUnit.SECONDS);
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    reply.headers().forEach((name,value)->exchange.getResponseHeaders().set(name,value));
                    byte[] bytes=reply.body().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(reply.status(),bytes.length);
                    if (reply.bodyGate()!=null) reply.bodyGate().await(5,TimeUnit.SECONDS);
                    exchange.getResponseBody().write(bytes);
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            }); server.start();
        } catch (IOException ex) { throw new IllegalStateException(ex); }
    }
    public URI endpoint() { return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/images/generations"); }
    public static ImageS3Properties storage() {
        return new ImageS3Properties(false,"","",Duration.ofSeconds(2),Duration.ofSeconds(4),Duration.ofSeconds(10),2);
    }
    public static OpenAiImageProperties properties(Duration timeout) {
        return new OpenAiImageProperties(true,"fake-openai-secret","gpt-image-1-mini","1024x1024","low","png",
                Duration.ofMillis(100),timeout,2,Map.of("test-style","watercolor"),Map.of("test-mood","calm"));
    }
    public static ImageOptions options() { return new ImageOptions(List.of("test-style"),List.of("test-mood")); }
    public OpenAiImageGenerator generator(Duration timeout,int capacity) {
        var properties=properties(timeout);
        return new OpenAiImageGenerator(properties,options(),new ImageExecutionProperties(capacity,2),storage(),
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build(),endpoint());
    }
    public static ImageGenerator.Input input() {
        return new ImageGenerator.Input(1L,2L,UUID.randomUUID().toString(),DreamImage.PROMPT_VERSION,"test-style","test-mood",
                List.of(new StoryResult.Section(1,StoryResult.Kind.SCENE,1,"private-story-scene"),
                        new StoryResult.Section(2,StoryResult.Kind.AI_BRIDGE,null,"private-story-bridge")));
    }
    public static byte[] image(String format) {
        try {
            var bytes=new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2,3,BufferedImage.TYPE_INT_RGB),format,bytes); return bytes.toByteArray();
        } catch (IOException ex) { throw new IllegalStateException(ex); }
    }
    public static String body(byte[] bytes) {
        return JsonMapper.builder().build().writeValueAsString(Map.of("data",List.of(Map.of("b64_json",Base64.getEncoder().encodeToString(bytes)))));
    }
    public void reset() { replies.clear(); received.clear(); }
    @Override public void close() { server.stop(0); handlers.shutdownNow(); }
}
