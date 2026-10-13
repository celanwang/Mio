package org.celanwang.mio;

import org.celanwang.mio.config.DotenvSupport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

// DataSource 由 config.PgMemoryConfig 自建（PG 是唯一记忆后端），
// 排除自动装配避免与自建连接池冲突。
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class MioApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(MioApplication.class);
        // 让 IDE 直接运行也能读到项目根目录的 .env；真实环境变量优先级更高。
        app.addInitializers(ctx -> DotenvSupport.loadInto(ctx.getEnvironment()));
        app.run(args);
    }

}
