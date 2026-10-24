package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;

/** Operator-owned response contracts. No guessed supplier fields, model installation or executable scripts. */
public final class EvidenceAdapters {
    public record Field(String pointer,CapabilityOutputContract.ValueType type,String unit,String label){}
    public record Manifest(String id,boolean enabled,String capabilityId,String tool,String mode,String description,String factKey,String factType,int ttlSeconds,Map<String,JsonNode> conditions,Map<String,Field> fields,String resourceIdPointer,Set<String> invalidatingTools){}
    private final List<Manifest> entries;private final ObjectMapper json;
    public EvidenceAdapters(Path directory,ObjectMapper mapper){
        json=mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        var loaded=new ArrayList<Manifest>();try{if(Files.exists(directory))try(var files=Files.list(directory)){var paths=files.filter(p->p.getFileName().toString().endsWith(".json")&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).sorted().limit(33).toList();if(paths.size()>32)throw new IllegalArgumentException("证据适配配置过多");for(var file:paths){
            if(loaded.size()>=32||Files.size(file)>32768)throw new IllegalArgumentException("证据适配配置过多或过大");var entry=json.readValue(Files.readAllBytes(file),Manifest.class);if(!entry.enabled())continue;validate(entry);loaded.add(entry);
        }}}catch(java.io.IOException e){throw new IllegalStateException("证据适配配置不可读",e);}
        if(loaded.stream().map(Manifest::tool).distinct().count()!=loaded.size()||loaded.stream().map(Manifest::id).distinct().count()!=loaded.size())throw new IllegalArgumentException("证据适配器编号或工具重复");entries=List.copyOf(loaded);
    }
    private void validate(Manifest entry){
        if(entry.id()==null||!entry.id().matches("[a-z][a-z0-9-]{0,63}")||entry.capabilityId()==null||entry.tool()==null||entry.tool().isBlank()||entry.tool().length()>100||entry.description()==null||entry.description().isBlank()||entry.description().length()>1000||!Set.of("READ","WRITE").contains(entry.mode())||entry.conditions()==null||entry.conditions().isEmpty()||entry.conditions().size()>10)throw new IllegalArgumentException("证据适配必须指定能力、工具、说明与严格成功条件");
        if(Set.of("tencent_ride","tencent_ride_create_order").contains(entry.tool()))throw new IllegalArgumentException("不能覆盖内置出行证据适配器");
        for(var condition:entry.conditions().entrySet()){pointer(condition.getKey());if(!condition.getValue().isValueNode()||condition.getValue().isNull())throw new IllegalArgumentException("成功条件须为非空标量");}
        if(entry.mode().equals("WRITE"))pointer(entry.resourceIdPointer());else{
            if(entry.factKey()==null||!entry.factKey().matches("[a-z][a-z0-9_.]{0,79}")||entry.factType()==null||!entry.factType().matches("[a-z][a-z0-9_]{0,63}")||entry.ttlSeconds()<5||entry.ttlSeconds()>300||entry.fields()==null||entry.fields().isEmpty()||entry.fields().size()>8||entry.invalidatingTools()!=null&&entry.invalidatingTools().size()>20)throw new IllegalArgumentException("只读事实契约、字段或有效窗口无效");
            entry.fields().forEach((id,f)->{pointer(f.pointer());new CapabilityOutputContract.Field(id,f.label(),entry.factKey(),id,f.type(),f.unit());});
        }
    }
    private static void pointer(String pointer){if(pointer==null||!pointer.startsWith("/")||pointer.length()>300)throw new IllegalArgumentException("字段路径须为明确的 JSON Pointer");}
    private Optional<JsonNode> response(Manifest entry,String raw){try{if(raw.length()>128000)return Optional.empty();var root=json.readTree(raw);for(var condition:entry.conditions().entrySet())if(!root.at(condition.getKey()).equals(condition.getValue()))return Optional.empty();return Optional.of(root);}catch(Exception malformed){return Optional.empty();}}
    public List<OperationVerifier> verifiers(){return entries.stream().filter(e->e.mode().equals("WRITE")).map(e->new OperationVerifier(){
        public boolean supports(String tool){return e.tool().equals(tool);}
        public Verification verify(String raw){var node=response(e,raw).map(r->r.at(e.resourceIdPointer())).orElse(null);if(node!=null&&node.isTextual()&&node.asText().matches("[A-Za-z0-9_-]{1,128}")&&!node.asText().equals("null"))return new Verification(OperationOutcome.CONFIRMED_APPLIED,node.asText(),"configured:"+e.id()+":"+JsonFingerprint.hash(e).substring(0,12));return new Verification(OperationOutcome.UNKNOWN,"","configured:"+e.id());}
    }).map(v->(OperationVerifier)v).toList();}
    public List<FactExtractor> extractors(){return entries.stream().filter(e->e.mode().equals("READ")).map(e->new FactExtractor(){
        public String source(){return "configured:"+e.id()+":"+JsonFingerprint.hash(e).substring(0,12);}
        public Optional<String> invalidatedKey(String tool,Map<String,Object> input){return e.tool().equals(tool)||e.invalidatingTools()!=null&&e.invalidatingTools().contains(tool)?Optional.of(e.factKey()):Optional.empty();}
        public Optional<Value> extract(String tool,Map<String,Object> input,String raw,long now){
            if(!e.tool().equals(tool))return Optional.empty();var root=response(e,raw).orElse(null);if(root==null)return Optional.empty();var values=new LinkedHashMap<String,String>();
            for(var field:e.fields().entrySet()){var node=root.at(field.getValue().pointer());if(field.getValue().type()==CapabilityOutputContract.ValueType.NUMBER){if(!node.isNumber()||node.asText().length()>100||node.decimalValue().precision()>100||Math.abs((long)node.decimalValue().scale())>100)return Optional.empty();values.put(field.getKey(),node.decimalValue().toPlainString());}else{if(!node.isTextual()||node.asText().isBlank()||node.asText().length()>1000)return Optional.empty();values.put(field.getKey(),node.asText());}}
            return Optional.of(new Value(e.factType(),values,now+e.ttlSeconds()*1000L));
        }
    }).map(v->(FactExtractor)v).toList();}
    public List<DomainDefinition> decorate(List<DomainDefinition> domains){
        var known=domains.stream().map(d->d.capability().id()).collect(java.util.stream.Collectors.toSet());if(entries.stream().anyMatch(e->!known.contains(e.capabilityId())))throw new IllegalArgumentException("证据适配引用未知能力");
        return domains.stream().map(domain->{var matching=entries.stream().filter(e->e.capabilityId().equals(domain.capability().id())).toList();if(matching.isEmpty())return domain;
            var capability=domain.capability();var outputs=new ArrayList<>(capability.outputContracts().isEmpty()?List.of(CapabilityOutputContract.report()):capability.outputContracts());
            for(var entry:matching)if(entry.mode().equals("WRITE")){if(outputs.stream().noneMatch(c->c.id().equals("resource-reference")))outputs.add(CapabilityOutputContract.resourceReference());}else{var fields=entry.fields().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(f->new CapabilityOutputContract.Field(f.getKey(),f.getValue().label(),entry.factKey(),f.getKey(),f.getValue().type(),f.getValue().unit())).toList();outputs.add(new CapabilityOutputContract(entry.id(),entry.description(),Map.of(entry.factKey(),entry.factType()),TaskScope.PREPARE,fields));}
            var configured=new AgentCapability(capability.id(),capability.name(),capability.description(),capability.skill(),JsonFingerprint.hash(Map.of("version",capability.version(),"evidence",matching)),capability.constraints(),capability.inputContracts(),outputs);return new DomainDefinition(configured,domain.prompt(),domain.tools(),domain::checkAvailability);
        }).toList();
    }
    public List<Map<String,Object>> descriptions(){return entries.stream().map(e->Map.<String,Object>of("id",e.id(),"capabilityId",e.capabilityId(),"tool",e.tool(),"mode",e.mode(),"validation","OPERATOR_CONFIGURED_RESPONSE_CONTRACT")).toList();}
    public Set<String> writes(){return entries.stream().filter(e->e.mode().equals("WRITE")).map(Manifest::tool).collect(java.util.stream.Collectors.toUnmodifiableSet());}
}
