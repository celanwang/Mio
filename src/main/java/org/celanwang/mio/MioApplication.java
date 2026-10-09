package org.celanwang.mio;

import org.celanwang.mio.config.DotenvSupport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MioApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(MioApplication.class);
        // 让 IDE 直接运行也能读到项目根目录的 .env；真实环境变量优先级更高。
        app.addInitializers(ctx -> DotenvSupport.loadInto(ctx.getEnvironment()));
        app.run(args);
    }

}
