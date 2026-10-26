package com.mongle.backend.domain.payment;

import jakarta.annotation.PreDestroy;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

final class PayAppGateway implements PaymentGateway {
    private static final URI ENDPOINT=URI.create("https://api.payapp.kr/oapi/v2");
    private final PayAppProperties properties;
    private final HttpClient client;
    private final URI endpoint;
    private final Clock clock;
    private final ScheduledThreadPoolExecutor timer=new ScheduledThreadPoolExecutor(1,Thread.ofPlatform().name("payapp-timer-",0).factory());
    private final Set<CompletableFuture<?>> calls=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    PayAppGateway(PayAppProperties p,Clock clock) {
        this(p,clock,HttpClient.newBuilder().connectTimeout(p.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build(),ENDPOINT);
    }
    // 테스트에서만 모의 전송 경로를 주입한다. 운영 endpoint는 설정으로 받지 않는다.
    PayAppGateway(PayAppProperties p,Clock clock,HttpClient client,URI endpoint) {
        properties=p; this.clock=clock; this.client=client; this.endpoint=endpoint; timer.setRemoveOnCancelPolicy(true);
    }
    public boolean available() { return properties.enabled() && !closed; }
    public CompletableFuture<Result> request(Command command) {
        if(!available()) return CompletableFuture.completedFuture(Result.unknown());
        String date=DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withZone(ZoneId.of("Asia/Seoul")).format(clock.instant());
        var values=new LinkedHashMap<String,String>();
        values.put("cmd","payrequest"); values.put("userid",properties.sellerId()); values.put("linkkey",properties.linkKey());
        values.put("requestdate",date); values.put("hash",PaymentHashes.sha256(properties.sellerId()+date+properties.requestKey()));
        values.put("goodname",command.name()); values.put("price",Long.toString(command.price())); values.put("recvphone",command.phone());
        values.put("reqaddr","0"); values.put("feedbackurl",properties.feedbackUrl().toString());
        values.put("returnurl",org.springframework.web.util.UriComponentsBuilder.fromUri(properties.returnUrl()).queryParam("orderId",command.orderId()).build().toUriString());
        values.put("var1",command.orderId()); values.put("var2",command.token()); values.put("smsuse","n"); values.put("checkretry","y");
        var request=HttpRequest.newBuilder(endpoint).timeout(properties.totalTimeout())
                .header("Content-Type","application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(PayAppForm.encode(values),StandardCharsets.UTF_8)).build();
        final CompletableFuture<HttpResponse<byte[]>> network;
        try { network=client.sendAsync(request,info -> new PayAppBody()); }
        catch(RuntimeException e) { return CompletableFuture.completedFuture(Result.unknown()); }
        calls.add(network);
        final ScheduledFuture<?> timeout;
        try { timeout=timer.schedule(() -> network.cancel(true),properties.totalTimeout().toMillis(),TimeUnit.MILLISECONDS); }
        catch(RejectedExecutionException e) { network.cancel(true); calls.remove(network); return CompletableFuture.completedFuture(Result.unknown()); }
        return network.handle((response,error) -> {
            timeout.cancel(false); calls.remove(network);
            if(error!=null || response.statusCode()!=200) return Result.unknown();
            try {
                var form=PayAppForm.decode(new String(response.body(),StandardCharsets.UTF_8));
                if("0".equals(form.get("state"))) return Result.rejected();
                String id=form.get("mul_no"),url=form.get("payurl");
                if(!"1".equals(form.get("state")) || id==null || !id.matches("[1-9][0-9]{0,39}") || !validPaymentUrl(url)) return Result.unknown();
                return new Result(Outcome.READY,id,url);
            } catch(RuntimeException e) { return Result.unknown(); }
        });
    }
    static boolean validPaymentUrl(String value) {
        try {
            if(value==null || value.length()>2048) return false;
            var u=URI.create(value); String host=u.getHost();
            return "https".equals(u.getScheme()) && host!=null && (host.equals("payapp.kr") || host.endsWith(".payapp.kr"))
                    && u.getUserInfo()==null && u.getFragment()==null && (u.getPort()==-1 || u.getPort()==443);
        } catch(RuntimeException e) { return false; }
    }
    @PreDestroy public void close() { closed=true; calls.forEach(f -> f.cancel(true)); timer.shutdownNow(); client.shutdownNow(); }
}
