package org.celanwang.mio.memory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * JSON 文件读写公共工具：~ 展开、原子写（保留 .bak）。
 */
public final class JsonFileSupport {

    private JsonFileSupport() {
    }

    /** Spring 不会展开路径开头的 ~，这里手动展开为用户主目录。 */
    public static Path expandHome(String path) {
        if (path != null && path.startsWith("~")) {
            return Path.of(System.getProperty("user.home") + path.substring(1));
        }
        return Path.of(path);
    }

    /** 原子写：先写临时文件，已有文件保留为 .bak，再移动替换。 */
    public static void writeAtomic(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        if (Files.exists(file)) {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 读取文件内容，文件不存在时返回 null。 */
    public static String read(Path file) throws IOException {
        return Files.exists(file) ? Files.readString(file) : null;
    }
}
