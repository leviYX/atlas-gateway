package dev.atlas.gateway.validation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import java.io.IOException;
import java.util.Set;

/**
 * 校验地图行为批次信封和事件公共字段，全部通过后返回事件数。
 * 只做结构校验，不保存数据、不去重，也不校验各事件类型的业务字段。
 */
public final class EventBatchValidator {
    private static final Set<String> SOURCES = Set.of("vehicle", "app", "web");
    private static final Set<String> TYPES = Set.of("map_search", "map_click", "sug_impression", "sug_click");
    // 完成配置后共享只读 ObjectMapper，避免为每条连接重复创建解析器配置。
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).build())
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final int maxEvents;

    public EventBatchValidator(int maxEvents) {
        this.maxEvents = maxEvents;
    }

    /** 整批校验通过才返回事件数；任何字段非法都拒绝整批，避免部分成功语义。 */
    public int validate(ByteBuf content) throws IOException {
        // duplicate 共享底层内存但隔离 readerIndex；流关闭不释放 ByteBuf，由请求统一管理引用计数。
        try (ByteBufInputStream input = new ByteBufInputStream(content.duplicate(), false)) {
            JsonNode root = MAPPER.readTree(input);
            require(root != null && root.isObject(), "body must be a JSON object");
            text(root, "batchId");
            require(SOURCES.contains(text(root, "source")), "unsupported source");
            JsonNode version = root.path("schemaVersion");
            require(version.isIntegralNumber() && version.canConvertToInt() && version.intValue() == 1,
                    "schemaVersion must be 1");
            JsonNode events = root.path("events");
            require(events.isArray() && !events.isEmpty() && events.size() <= maxEvents,
                    "events must contain 1 to " + maxEvents + " entries");
            for (JsonNode event : events) {
                require(event.isObject(), "each event must be an object");
                text(event, "eventId");
                require(TYPES.contains(text(event, "eventType")), "unsupported eventType");
                JsonNode time = event.path("eventTime");
                require(time.isIntegralNumber() && time.canConvertToLong() && time.longValue() > 0,
                        "eventTime must be a positive epoch millisecond integer");
                require(!event.has("attributes") || event.path("attributes").isObject(),
                        "attributes must be an object");
            }
            return events.size();
        }
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        require(value.isTextual() && !value.textValue().isBlank(), name + " must be a nonempty string");
        return value.textValue();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
