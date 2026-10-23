package org.celanwang.mio.goals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.orchestration.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** Startup-only trusted configuration. Neither agents nor HTTP clients may install capability packages. */
public final class CapabilityPackages {
    public record Manifest(String id,String name,String description,String kind,String prompt,boolean enabled,String endpoint,String tokenEnv){}
    private final List<DomainDefinition> definitions;
    public CapabilityPackages(Path directory,Path skills,JdbcDocuments documents,ObjectMapper mapper,Function<String,String> environment){
        var result=new ArrayList<DomainDefinition>();var json=mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        try{
            if(!Files.exists(directory)){definitions=List.of();return;}
            List<Path> files;try(var stream=Files.list(directory)){files=stream.filter(p->p.getFileName().toString().endsWith(".json")&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).sorted().limit(21).toList();}if(files.size()>20)throw new IllegalArgumentException("最多加载20个能力包");
            for(Path file:files){if(Files.size(file)>32768)throw new IllegalArgumentException("能力包过大");byte[] bytes=Files.readAllBytes(file);var manifest=json.readValue(bytes,Manifest.class);if(!manifest.enabled())continue;GoalProgram.key(manifest.id());if(manifest.name()==null||manifest.name().isBlank()||manifest.description()==null||manifest.description().isBlank()||manifest.prompt()==null||manifest.prompt().length()>12000)throw new IllegalArgumentException("能力包缺少名称、说明或提示");
                String skill="document-assistant",skillText=Files.readString(skills.resolve(skill).resolve("SKILL.md"));String version=JsonFingerprint.hash(Map.of("manifest",manifest,"skill",skillText));
                var capability=new AgentCapability(manifest.id(),manifest.name(),manifest.description(),skill,version,List.of("仅查询、分析与文稿准备","远程输出是未核验报告"),CapabilityInputContract.contextual(),List.of(CapabilityOutputContract.report()));
                if("LOCAL_DOCUMENTS".equals(manifest.kind()))result.add(new DomainDefinition(capability,skillText+"\n"+manifest.prompt(),()->List.of(new DocumentReadTool(documents,true),new DocumentReadTool(documents,false))));
                else if("REMOTE_PREPARE".equals(manifest.kind())){
                    if(manifest.tokenEnv()!=null&&!manifest.tokenEnv().isBlank()&&!manifest.tokenEnv().matches("[A-Z][A-Z0-9_]{0,80}"))throw new IllegalArgumentException("远程凭据应引用环境变量");
                    String token=manifest.tokenEnv()==null||manifest.tokenEnv().isBlank()?"":environment.apply(manifest.tokenEnv());var tool=new RemotePreparationTool(manifest.endpoint(),token,json);
                    result.add(new DomainDefinition(capability,skillText+"\n"+manifest.prompt(),()->List.of(tool),()->manifest.tokenEnv()!=null&&!manifest.tokenEnv().isBlank()&&(token==null||token.isBlank())?CapabilityAvailability.of(CapabilityAvailability.State.UNAVAILABLE,"REMOTE_CREDENTIAL_MISSING","远程只读凭据未配置"):CapabilityAvailability.of(CapabilityAvailability.State.UNKNOWN,"CONFIGURED_REMOTE_PREPARATION","已配置的远程准备服务，尚未探测健康")));
                }else throw new IllegalArgumentException("能力包 kind 只能是 LOCAL_DOCUMENTS 或 REMOTE_PREPARE");
            }
            definitions=List.copyOf(result);
        }catch(java.io.IOException e){throw new IllegalStateException("能力包读取失败",e);}
    }
    public List<DomainDefinition> definitions(){return definitions;}
    public List<DomainDefinition> combine(List<DomainDefinition> builtins){var result=new ArrayList<>(builtins);result.addAll(definitions);return List.copyOf(result);}
}
