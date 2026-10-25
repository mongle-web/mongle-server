package com.mongle.backend.domain.payment;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
final class PayAppForm {
    static String encode(Map<String,String> values) {
        return values.entrySet().stream().map(e -> enc(e.getKey())+"="+enc(e.getValue())).collect(java.util.stream.Collectors.joining("&"));
    }
    private static String enc(String s) { return URLEncoder.encode(s,StandardCharsets.UTF_8); }
    static Map<String,String> decode(String text) {
        var values=new LinkedHashMap<String,String>();
        if(text.length()>32768) throw new IllegalArgumentException("잘못된 결제 응답");
        for(String part:text.split("&",-1)) {
            if(part.isEmpty()) continue;
            int split=part.indexOf('=');
            if(split<1) throw new IllegalArgumentException("잘못된 결제 응답");
            String key=URLDecoder.decode(part.substring(0,split),StandardCharsets.UTF_8);
            String value=URLDecoder.decode(part.substring(split+1),StandardCharsets.UTF_8);
            if(values.putIfAbsent(key,value)!=null) throw new IllegalArgumentException("중복된 결제 필드");
        }
        return values;
    }
}
