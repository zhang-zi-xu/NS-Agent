package com.nongxin.service.chat;

import com.nongxin.domain.chat.ChatResult.Reason;
import com.nongxin.domain.field.FieldProfile;
import com.nongxin.domain.field.FieldRecord;
import com.nongxin.dto.chat.ChatMsg;
import com.nongxin.dto.chat.ChatRequest;
import com.nongxin.integration.model.VisionSupport;
import com.nongxin.service.ApiKeyService;
import com.nongxin.service.UploadService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Validates and prepares snapshots/images before reserving any model quota. */
@Component
public class ChatInputPreparer {
    private static final Logger log = LoggerFactory.getLogger(ChatInputPreparer.class);
    private final UploadService uploads;
    private final VisionSupport vision;

    public ChatInputPreparer(UploadService uploads, VisionSupport vision) {
        this.uploads = uploads;
        this.vision = vision;
    }

    public record Prepared(
            List<Map<String, Object>> history,
            FieldProfile field,
            List<UploadService.Stored> images) {}

    public Prepared prepare(ChatRequest request, ApiKeyService.ModelSelection selected) {
        List<Map<String, Object>> history = sanitizeMessages(request.messages());
        // 图片：只收 id，原图由服务端读盘后转发给供应商；不支持视觉的模型明确拒绝，绝不发"伪视觉请求"
        List<String> imageIds =
                request.imageIds() == null
                        ? List.of()
                        : request.imageIds().stream()
                                .filter(id -> id != null && !id.isBlank())
                                .distinct()
                                .toList();
        // Only the field snapshot supplied in this request may be used.
        FieldProfile field = toField(request.field());

        List<UploadService.Stored> images = new ArrayList<>();
        if (!imageIds.isEmpty()) {
            images.addAll(uploads.find(imageIds));
            if (images.size() != imageIds.size()) {
                throw new ChatRejection(Reason.INVALID_INPUT, "有图片不存在或已被清理，请重新上传后再发送");
            }
            if (!vision.effective(request.imageInput(), selected.model())) {
                throw new ChatRejection(
                        Reason.UNSUPPORTED_MEDIA,
                        "当前模型「"
                                + selected.model()
                                + "」不在支持看图的名单里。请在「模型设置」里换成支持图片的模型，"
                                + "或在那里把「图片输入」设为“支持”。图片没有发送给供应商。");
            }
        }
        // 田块近况分析：自动带上该田块最近 3 张照片（仅在明确要求时；模型不支持看图就跳过并记录日志）
        if (Boolean.TRUE.equals(request.autoFieldPhotos()) && field != null) {
            if (vision.effective(request.imageInput(), selected.model())) {
                for (UploadService.Stored photo : uploads.latestForField(field.id(), 3)) {
                    if (images.stream().noneMatch(existing -> existing.id().equals(photo.id())))
                        images.add(photo);
                }
            } else {
                log.info("chat 田块近况：模型 {} 未标记支持看图，已跳过自动带图", selected.model());
            }
        }
        if (!images.isEmpty()) {
            if (history.isEmpty() || !"user".equals(history.getLast().get("role"))) {
                throw new ChatRejection(Reason.INVALID_INPUT, "图片只能随提问一起发送");
            }
            attachImages(history, images);
        }
        return new Prepared(history, field, images);
    }

    private static final Pattern AGRI_RE =
            Pattern.compile(
                    "农药|打药|用药|施药|喷药|间隔期|剂量|用量|残留|肥料|施肥|追肥|底肥|病虫|病害|虫害|防治|防控|"
                            + "稻瘟|稻飞虱|螟虫|纹枯|稻曲|赤霉|锈病|白粉|蚜虫|红蜘蛛|除草|种子|品种|育苗|插秧|移栽|"
                            + "灌溉|排水|断水|晒田|倒伏|控旺|抽穗|灌浆|分蘖|孕穗|破口|播种|收获|收割|天气|气温|降雨|霜冻|高温|干旱|涝|"
                            + "水稻|小麦|玉米|油菜|大豆|薯|蔬菜|果树|茶|农事|整地|秸秆|备耕");

    public static String lastUserText(List<Map<String, Object>> history) {
        for (int index = history.size() - 1; index >= 0; index--) {
            Map<String, Object> message = history.get(index);
            if (!"user".equals(message.get("role"))) continue;
            Object content = message.get("content");
            if (content instanceof String text) return text;
            if (content instanceof List<?> parts) {
                StringBuilder sb = new StringBuilder();
                for (Object part : parts) {
                    if (part instanceof Map<?, ?> map && map.get("text") instanceof String piece)
                        sb.append(piece).append(' ');
                }
                return sb.toString();
            }
        }
        return "";
    }

    public static boolean looksAgricultural(String text) {
        return text != null && !text.isBlank() && AGRI_RE.matcher(text).find();
    }

    private List<Map<String, Object>> sanitizeMessages(List<ChatMsg> messages) {
        if (messages == null || messages.isEmpty()) throw new IllegalArgumentException("对话内容格式不正确");
        if (messages.size() > 500) throw new IllegalArgumentException("对话消息数量超出限制，请新建对话");
        List<Map<String, Object>> out = new ArrayList<>();
        int from = Math.max(0, messages.size() - 20);
        for (int i = from; i < messages.size(); i++) {
            ChatMsg m = messages.get(i);
            if (m == null) continue;
            if (!"user".equals(m.role()) && !"assistant".equals(m.role())) continue;
            if (m.content() == null || m.content().isBlank()) continue;
            String content = m.content().trim();
            if (content.length() > 20000)
                throw new IllegalArgumentException("单条对话上下文超过 20,000 字符，请新建对话或缩短内容");
            out.add(Map.of("role", m.role(), "content", content));
        }
        if (out.isEmpty() || !"user".equals(out.get(out.size() - 1).get("role"))) {
            throw new IllegalArgumentException("请先输入问题");
        }
        return out;
    }

    private void attachImages(
            List<Map<String, Object>> history, List<UploadService.Stored> images) {
        Map<String, Object> last = history.getLast();
        List<Map<String, Object>> parts = new ArrayList<>();
        // 把拍摄日期/备注一并写进文字部分：模型才能分辨"这是三周前的叶子"还是"今天的"
        String timeline =
                images.stream()
                        .map(
                                image ->
                                        (image.observedAt() == null || image.observedAt().isBlank()
                                                        ? "日期未知"
                                                        : image.observedAt())
                                                + (image.note() == null || image.note().isBlank()
                                                        ? ""
                                                        : "（" + image.note() + "）"))
                        .collect(java.util.stream.Collectors.joining("、"));
        parts.add(
                Map.of(
                        "type",
                        "text",
                        "text",
                        String.valueOf(last.get("content"))
                                + "\n\n【随本次问题附带的照片："
                                + images.size()
                                + " 张，按时间从新到旧："
                                + timeline
                                + "。只能描述这些照片里确实看得见的内容；照片之间有时间差时，可以对比变化，但不要假设中间发生了什么。】"));
        for (UploadService.Stored image : images) {
            byte[] data = uploads.read(image.id());
            if (data == null || data.length == 0) {
                throw new IllegalArgumentException("附带图片已不可读取，请重新上传后再发送。图片没有发送给供应商。");
            }
            parts.add(
                    Map.of(
                            "type",
                            "image_url",
                            "image_url",
                            Map.of(
                                    "url",
                                    "data:image/jpeg;base64,"
                                            + Base64.getEncoder().encodeToString(data))));
        }
        // sanitizeMessages 里用的是不可变 Map，这里换成可变副本再替换回去
        Map<String, Object> updated = new LinkedHashMap<>(last);
        updated.put("content", parts);
        history.set(history.size() - 1, updated);
    }

    private FieldProfile toField(Map<String, Object> fieldObj) {
        if (fieldObj == null || fieldObj.get("id") == null) return null;
        List<FieldRecord> records = new ArrayList<>();
        if (fieldObj.get("records") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> r
                        && r.get("date") instanceof String d
                        && r.get("note") instanceof String n) {
                    records.add(new FieldRecord(d, n));
                }
            }
        }
        Double area = fieldObj.get("areaMu") instanceof Number n ? n.doubleValue() : null;
        return new FieldProfile(
                String.valueOf(fieldObj.get("id")),
                String.valueOf(fieldObj.getOrDefault("name", "未名田块")),
                String.valueOf(fieldObj.getOrDefault("crop", "")),
                fieldObj.get("variety") instanceof String v ? v : null,
                String.valueOf(fieldObj.getOrDefault("sowDate", "")),
                area,
                fieldObj.get("notes") instanceof String n ? n : null,
                records);
    }
}
