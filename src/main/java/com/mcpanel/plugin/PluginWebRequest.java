package com.mcpanel.plugin;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.Map;

public class PluginWebRequest {
    private final HttpServerExchange exchange;

    public PluginWebRequest(HttpServerExchange exchange) {
        this.exchange = exchange;
    }

    public String getMethod() { return exchange.getRequestMethod().toString(); }
    public String getPath() { return exchange.getRequestPath(); }

    public String getQuery(String key, String def) {
        Deque<String> q = exchange.getQueryParameters().get(key);
        return (q == null || q.isEmpty()) ? def : q.peekFirst();
    }

    public Map<String, Deque<String>> getAllQuery() {
        return exchange.getQueryParameters();
    }

    public String getHeader(String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    public InputStream getInputStream() throws Exception {
        exchange.startBlocking();
        return exchange.getInputStream();
    }

    public OutputStream getOutputStream() throws Exception {
        exchange.startBlocking();
        return exchange.getOutputStream();
    }

    public void setStatusCode(int code) { exchange.setStatusCode(code); }
    public void setHeader(String name, String value) {
        if (name == null || value == null || name.contains("\r") || name.contains("\n") || value.contains("\r") || value.contains("\n")) return;
        exchange.getResponseHeaders().put(new HttpString(name), value);
    }

    public void sendResponse(String contentType, byte[] data) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
        exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, String.valueOf(data.length));
        exchange.getResponseSender().send(ByteBuffer.wrap(data));
    }

    public void sendText(String text) {
        sendResponse("text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }
}