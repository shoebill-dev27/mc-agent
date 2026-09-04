package dev.shinobu.mcagent.acp;

import com.google.gson.JsonElement;

/** A JSON-RPC error object returned by the peer. */
public class JsonRpcException extends RuntimeException {
    private final int code;
    private final transient JsonElement data;

    public JsonRpcException(int code, String message, JsonElement data) {
        super("JSON-RPC error " + code + ": " + message);
        this.code = code;
        this.data = data;
    }

    public int code() {
        return code;
    }

    public JsonElement data() {
        return data;
    }
}
