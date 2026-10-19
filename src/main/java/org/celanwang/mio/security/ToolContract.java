package org.celanwang.mio.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.core.tool.AgentTool;
import java.security.MessageDigest;
import java.util.*;

/** Binds an approval to the actual server-side tool contract, not just its display name. */
public final class ToolContract {
    /** Trusted wrappers expose the underlying contract so permission metadata cannot disappear. */
    public interface SourceBound { AgentTool contractSource(); }
    private static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    public static String hash(AgentTool tool){
        if(tool==null)return "missing";
        try{
            var value=new LinkedHashMap<String,Object>();value.put("name",tool.getName());value.put("description",tool.getDescription());value.put("schema",tool.getParameters());
            if(tool instanceof io.agentscope.core.tool.ToolBase base)value.put("readOnly",base.isReadOnly());
            if(tool instanceof SourceBound bound)value.put("sourceContract",hash(bound.contractSource()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value)));
        }catch(Exception e){throw new IllegalStateException("工具契约无法校验",e);}
    }
    private ToolContract(){}
}
