package org.celanwang.mio.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.config.TencentRideProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class TencentRideService {
    private final TencentRideProperties properties;
    private final ObjectMapper json;
    private final Path skillsDir;

    public TencentRideService(TencentRideProperties properties, ObjectMapper json,
                              @Value("${app.agent.skills-dir:./skills}") String skillsDir) {
        this.properties = properties;
        this.json = json;
        this.skillsDir = Path.of(skillsDir);
    }

    // 同一账号串行执行；会话各自保存起终点和报价，不能覆盖另一会话的已确认行程。
    public synchronized String execute(String sessionId, Map<String, Object> input) throws IOException {
        if (!properties.isEnabled()) return "disabled：腾讯打车服务已停用。";
        if (sessionId == null || sessionId.isBlank()) return "error：缺少对话标识，请开启新对话。";
        if (!org.celanwang.mio.tool.TencentRideTool.isSupported(input.get("command"))) {
            return "error：不支持的打车命令。";
        }
        Path root = properties.getDataDirectory().toAbsolutePath().normalize();
        Path runtime = root.resolve("runtime");
        Path session = root.resolve("sessions").resolve(sessionKey(sessionId));
        Files.createDirectories(runtime);
        Files.createDirectories(session);
        for (String name : new String[]{"tms_takecar.py", "tms_takecar_api.py", "personal_agent_bridge.py"}) {
            copyScript(name, runtime.resolve(name));
        }
        Map<String, Object> request = new LinkedHashMap<>(input);
        // 凭据只通过 stdin 交给子进程，不放在工具 schema、命令行、日志或持久化状态中。
        request.put("token", properties.getToken());
        request.put("residentCity", properties.getResidentCity());
        request.put("sessionDirectory", session.toString());
        request.put("accountDirectory", root.resolve("accounts").resolve(sessionKey(properties.getToken())).toString());
        Path output = Files.createTempFile(session, "output-", ".json");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(properties.getPython(), "-B", "-u",
                    runtime.resolve("personal_agent_bridge.py").toString());
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            builder.redirectErrorStream(true).redirectOutput(output.toFile());
            process = builder.start();
            try (var stdin = process.getOutputStream()) {
                stdin.write(json.writeValueAsBytes(request));
            }
            if (!process.waitFor(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                stop(process);
                return "error：打车工具超时，操作结果可能不明。请查询当前订单，不要重复下单或取消。";
            }
            if (Files.size(output) > 2_000_000) return "error：工具结果过大，请缩小查询范围。";
            String result = Files.readString(output, StandardCharsets.UTF_8);
            if (!properties.getToken().isBlank()) result = result.replace(properties.getToken(), "[REDACTED]");
            return result.isBlank() ? "error：打车工具没有返回结果，请先核对当前订单。" : result;
        } catch (InterruptedException failure) {
            if (process != null) stop(process);
            Thread.currentThread().interrupt();
            throw new IOException("打车操作已中断，请先核对当前订单。", failure);
        } catch (IOException failure) {
            // 不把进程参数、凭据或原始系统错误回传模型。
            return "error：无法运行腾讯打车脚本，请检查 Python 路径和数据目录权限。";
        } finally {
            if (process != null && process.isAlive()) stop(process);
            Files.deleteIfExists(output);
        }
    }

    // 技能在文件系统目录（默认 ./skills，MIO_SKILLS_DIR 可覆盖），修改脚本无需重新构建；
    // 目录缺失时回退到 classpath 内置副本（若打包进 resources）。
    private void copyScript(String name, Path target) throws IOException {
        Path local = skillsDir.resolve("tencent-ride").resolve("scripts").resolve(name);
        if (Files.isRegularFile(local)) {
            Files.copy(local, target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        // SDK/HTTP worker threads may not inherit the Boot loader that can read nested JAR resources.
        try (InputStream source = new ClassPathResource("skills/tencent-ride/scripts/" + name,
                TencentRideService.class.getClassLoader()).getInputStream()) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void stop(Process process) {
        process.descendants().forEach(handle -> handle.destroyForcibly());
        process.destroyForcibly();
    }

    private static String sessionKey(String id) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(id.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
