package cn.lunalhx.ai.domain.tool.model;

import com.fasterxml.jackson.databind.JsonNode;

/** Canonicalized tool input used for matching; raw input remains transient. */
public record NormalizedToolCall(String toolName, JsonNode canonicalInput,
                                 PermissionSubject permissionSubject) {
}
