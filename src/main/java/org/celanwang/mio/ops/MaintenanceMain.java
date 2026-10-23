package org.celanwang.mio.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;

/** Deliberately independent of MioApplication and application credentials. */
public final class MaintenanceMain {
    private MaintenanceMain() {}
    public static void main(String[] args) {
        try { System.exit(run(args,System.getenv())); }
        catch(Exception failure) {
            // Driver messages may contain connection strings or stored content. Do not print causes or stack traces.
            System.err.println("维护失败：参数、预览版本、数据库状态或审计写入不满足要求。请核对配置后重新预览。");
            System.exit(1);
        }
    }
    static int run(String[] args,Map<String,String> env) throws Exception {
        if(args.length==0||Arrays.asList(args).contains("--help")) {
            System.out.println("用法: admin.sh retry-memory-job --job ID | recover-restored-runtime [--apply --expected SHA256 --reason 原因 --offline]\n默认只读预览。显式设置 MIO_ADMIN_PG_URL / MIO_ADMIN_PG_USERNAME / MIO_ADMIN_PG_PASSWORD；执行前停止所有实例。");
            return 0;
        }
        var operation=switch(args[0]) {
            case "retry-memory-job" -> JdbcMaintenance.Operation.RETRY_MEMORY_JOB;
            case "recover-restored-runtime" -> JdbcMaintenance.Operation.RECOVER_RESTORED_RUNTIME;
            default -> throw new IllegalArgumentException("未知操作");
        };
        Map<String,String> options=new HashMap<>();
        for(int i=1;i<args.length;i++) {
            String key=args[i];
            if(!Set.of("--job","--apply","--expected","--reason","--offline").contains(key)||options.containsKey(key)) throw new IllegalArgumentException("参数无效");
            String value="true";
            if(!key.equals("--apply")&&!key.equals("--offline")) {
                if(++i>=args.length) throw new IllegalArgumentException("缺少参数值");
                value=args[i];
            }
            options.put(key,value);
        }
        if(operation==JdbcMaintenance.Operation.RETRY_MEMORY_JOB!=options.containsKey("--job")) throw new IllegalArgumentException("作业目标无效");
        if(!options.containsKey("--apply")&&(options.containsKey("--expected")||options.containsKey("--reason")||options.containsKey("--offline")))
            throw new IllegalArgumentException("执行参数缺少 --apply");
        String url=required(env,"MIO_ADMIN_PG_URL");
        if(!url.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("只支持 PostgreSQL");
        var source=new DriverManagerDataSource(url,required(env,"MIO_ADMIN_PG_USERNAME"),required(env,"MIO_ADMIN_PG_PASSWORD"));
        var json=new ObjectMapper();
        var service=new JdbcMaintenance(new JdbcTemplate(source),source,json);
        String target=options.getOrDefault("--job","");
        if(options.containsKey("--apply")) {
            String audit=service.apply(operation,target,options.get("--expected"),options.get("--reason"),options.containsKey("--offline"));
            System.out.println(json.writeValueAsString(Map.of("status","APPLIED","auditId",audit)));
        } else System.out.println(json.writeValueAsString(service.preview(operation,target)));
        return 0;
    }
    private static String required(Map<String,String> env,String key) {
        String value=env.get(key);
        if(value==null||value.isBlank()) throw new IllegalArgumentException("需要显式维护配置");
        return value;
    }
}
