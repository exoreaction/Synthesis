package io.exoreaction.synthesis.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 2.0 message types used by the MCP protocol.
 *
 * <p>This file defines the request, response, error, and notification
 * structures as specified by the JSON-RPC 2.0 specification
 * (https://www.jsonrpc.org/specification).
 */
public class JsonRpcMessage {

    private JsonRpcMessage() {}

    /**
     * A JSON-RPC 2.0 request message.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(
            String jsonrpc,
            Object id,
            String method,
            JsonNode params
    ) {
        public boolean isNotification() {
            return id == null;
        }
    }

    /**
     * A JSON-RPC 2.0 successful response.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(
            String jsonrpc,
            Object id,
            Object result
    ) {
        public static Response success(Object id, Object result) {
            return new Response("2.0", id, result);
        }
    }

    /**
     * A JSON-RPC 2.0 error response.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(
            String jsonrpc,
            Object id,
            ErrorDetail error
    ) {
        public static ErrorResponse error(Object id, int code, String message) {
            return new ErrorResponse("2.0", id, new ErrorDetail(code, message, null));
        }

        public static ErrorResponse error(Object id, int code, String message, Object data) {
            return new ErrorResponse("2.0", id, new ErrorDetail(code, message, data));
        }
    }

    /**
     * Error detail within an error response.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorDetail(
            int code,
            String message,
            Object data
    ) {}

    // Standard JSON-RPC error codes
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
}
