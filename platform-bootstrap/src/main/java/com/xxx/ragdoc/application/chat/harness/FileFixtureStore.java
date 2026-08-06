package com.xxx.ragdoc.application.chat.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * PR-5 / EMS-PR5: 文件系统 {@link FixtureStore}。
 *
 * <h2>布局</h2>
 *
 * <pre>
 *   {root}/
 *     {caseId}/
 *       {componentType}/  (router|tool|citation|...)
 *         {componentName}-{version}-call{idx}-{shortHash}.json
 * </pre>
 *
 * <h2>写入安全 (EMS-PR5 §12.1)</h2>
 *
 * <ol>
 *   <li>caseId / componentName 安全规范化 (只 [a-z0-9_-]) — 防目录穿越
 *   <li>先写 .tmp 再 Files.move 原子 rename
 *   <li>同 replayKey 内容一致 → 幂等; 不一致 → FixtureConflictException
 *   <li>读取 corrupted → FixtureUnavailableException(CORRUPTED)
 * </ol>
 *
 * <p>限制文件大小 (1MB) + JSON 深度 (Jackson 默认 1000); 超过 → 失败。
 */
@Slf4j
public class FileFixtureStore implements FixtureStore {

    private static final long MAX_FILE_BYTES = 1L * 1024 * 1024;
    private static final java.util.regex.Pattern SAFE_NAME = java.util.regex.Pattern.compile("[a-z0-9_-]+");

    private final Path root;
    private final ObjectMapper mapper;

    public FileFixtureStore(String rootDir, ObjectMapper mapper) {
        this.root = Paths.get(rootDir).toAbsolutePath().normalize();
        this.mapper = mapper;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("fixture_root 创建失败: " + root, e);
        }
    }

    @Override
    public Optional<FixtureRecord> find(String replayKey) {
        if (replayKey == null || replayKey.isBlank()) return Optional.empty();
        Path file = locateNoCheck(replayKey);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                throw new FixtureUnavailableException(replayKey,
                        FixtureUnavailableException.Reason.CORRUPTED,
                        "fixture 文件超 1MB: " + size);
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            FixtureRecord record = mapper.readValue(content, FixtureRecord.class);
            if (!replayKey.equals(record.replayKey())) {
                throw new FixtureUnavailableException(replayKey,
                        FixtureUnavailableException.Reason.CORRUPTED,
                        "replayKey mismatch 文件名/内容");
            }
            return Optional.of(record);
        } catch (IOException e) {
            throw new FixtureUnavailableException(replayKey,
                    FixtureUnavailableException.Reason.CORRUPTED,
                    "fixture 反序列化失败: " + e.getMessage());
        }
    }

    @Override
    public void save(FixtureRecord record) {
        String key = record.replayKey();
        Path target = locateNoCheck(key);
        try {
            Files.createDirectories(target.getParent());
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(record);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IllegalStateException("fixture 写入大小超 1MB: " + bytes.length);
            }
            // 同 Key 内容一致 → 幂等
            if (Files.isRegularFile(target)) {
                String existingJson = Files.readString(target, StandardCharsets.UTF_8);
                if (businessEquals(existingJson, json, mapper)) {
                    log.debug("fixture.idempotent_skip key={}", shortKey(key));
                    return;
                }
                throw new FixtureConflictException(key,
                        "fixture 内容冲突 key=" + shortKey(key) + " (重新跑 record 时请求/响应不同; 业务字段去 recordedAt 后不等)");
            }
            // 原子写入: tmp + rename (ATOMIC_MOVE 在某些 FS 不支持, 降级 REPLACE_EXISTING)
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException amns) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("fixture.recorded key={} file={}", shortKey(key), target);
        } catch (IOException e) {
            throw new IllegalStateException("fixture 写入失败 key=" + shortKey(key) + ": " + e.getMessage(), e);
        }
    }

    // ─── 内部 ─────────────────────────────────────────────────────────

    /**
     * 通过 sha 第一段做一级目录 (减小单目录文件数), 第二段做文件名。
     * 这是内部计算 key → path, 不与 caseId (caller 传入) 直接绑, 简化 replay 查找。
     * caseId 已参与 replayKey 计算; 此处 mapping 不需要再带 caseId 安全名。
     */
    private Path locateNoCheck(String replayKey) {
        // replayKey 是 64 字符 sha256 hex; 路径: root / first2 / rest.json
        if (!SAFE_NAME.matcher(replayKey).matches()) {
            throw new FixtureUnavailableException(replayKey,
                    FixtureUnavailableException.Reason.CORRUPTED,
                    "replayKey 含非法字符");
        }
        String first2 = replayKey.substring(0, 2);
        String rest = replayKey.substring(2);
        Path p = root.resolve(first2).resolve(rest + ".json").normalize();
        // 防 ../ 穿越: 必须在 root 内
        if (!p.startsWith(root)) {
            throw new FixtureUnavailableException(replayKey,
                    FixtureUnavailableException.Reason.CORRUPTED,
                    "fixture 路径越出 root (path-traversal 拦截)");
        }
        return p;
    }

    private static String shortKey(String key) {
        return key == null ? "?" : key.substring(0, Math.min(12, key.length()));
    }

    /**
     * 业务指纹比对: 只做字符串 diff，去掉 recordedAt (录制时间永远不同)。
     * Simple + Tough: 在序列化稳定的 Jackson 路径上, 同 record 两次产出的 json 除 recordedAt 外应一致。
     */
    static boolean businessEquals(String existingJson, String newJson, ObjectMapper mapper) {
        return stripRecordedAt(existingJson).equals(stripRecordedAt(newJson));
    }

    private static String stripRecordedAt(String json) {
        return json.replaceAll("\"recordedAt\"\\s*:\\s*\"[^\"]*\"", "\"recordedAt\":\"\"");
    }

    /** 测试可见 root path。 */
    Path rootForTest() {
        return root;
    }
}
