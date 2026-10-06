package com.simmc.meplayeractions.command;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Correlation metadata is removed before ordinary disguise option validation and comparison. */
public final class DisguiseRequestId {
    private static final String KEY = "request-id";
    private DisguiseRequestId() {}

    public record Parsed(String[] arguments, UUID requestId) {
        public Parsed { arguments = arguments.clone(); }
        @Override public String[] arguments() { return arguments.clone(); }
        public String modelId() {
            return arguments.length > 1 && arguments[1].matches("[a-z0-9_-]{1,64}") ? arguments[1] : "";
        }
        public String fingerprint() { return String.join("\u0000", arguments); }
        public int wireBytes() { return fingerprint().getBytes(StandardCharsets.UTF_8).length + 48; }
    }

    public static Parsed parse(String[] input) {
        Objects.requireNonNull(input);
        if (input.length == 0 || !input[0].equalsIgnoreCase("disguise")) return new Parsed(input, null);
        UUID request = null;
        for (int i = 2; i < input.length; i++) {
            String token = input[i], key = token.split("=", 2)[0];
            if (!key.equalsIgnoreCase(KEY) && !key.equalsIgnoreCase("--" + KEY)) continue;
            if (request != null || i != input.length - 1 || !token.startsWith(KEY + "="))
                throw new IllegalArgumentException("request-id 必须是最后一个参数，且只能提供一次");
            String text = token.substring(KEY.length() + 1);
            try { request = UUID.fromString(text); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("request-id 须为标准小写 UUID"); }
            if (!request.toString().equals(text)) throw new IllegalArgumentException("request-id 须为标准小写 UUID");
        }
        return new Parsed(request == null ? input : Arrays.copyOf(input, input.length - 1), request);
    }
}
