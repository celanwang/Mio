package org.celanwang.mio.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Path;

@Data
@Component
@ConfigurationProperties("app.mcp.weibo")
public class WeiboProperties {
    private boolean enabled = true;
    private URI url = URI.create("https://cli.weibo.com/mcp");
    private URI callbackUri = URI.create("http://127.0.0.1:8080/api/weibo/callback");
    private Path credentialsFile = Path.of(".weibo/auth.json");
}
